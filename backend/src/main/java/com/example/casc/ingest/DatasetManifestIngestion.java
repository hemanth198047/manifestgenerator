package com.example.casc.ingest;

import com.example.casc.ingest.ManifestTables.Column;
import com.example.casc.ingest.ManifestTables.Table;
import com.example.casc.ingest.TableMetadataCsv.Row;
import com.example.casc.json.Json;
import com.example.casc.validation.Finding;
import com.example.casc.validation.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dataset-manifest ingestion: reads the table definitions from a manifest JSON (databases section) and the table
 * metadata CSV (table name, domain, boundary, upstream dataset, producer EON ID), and builds one manifest per table
 * whose "datasets" section maps that table (format ResultSet) to its upstream dataset.
 * Tables without a metadata row are not generated (the CSV decides which tables are mapped); metadata rows without a
 * matching table are reported as warnings.
 */
public final class DatasetManifestIngestion {
    private DatasetManifestIngestion() {}

    public static final String FORMAT = "ResultSet";
    public static final String CATALOGING_SOURCE = "FDC";

    /** One generated manifest. fileName is a suggestion such as manifest_TAPSAccount.json. */
    public record Generated(String schema, String table, String fileName, String content) {}

    public record Result(List<Generated> manifests, List<Finding> findings) {
        public boolean hasErrors() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        }
    }

    /** Tables from the databases section of a manifest JSON. */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv) {
        return generate(manifestJson, metadataCsv, null);
    }

    /** As above, with optional external column metadata (a system data element per column); columnCsv may be null. */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv, SourceInput columnCsv) {
        return generate(manifestJson, metadataCsv, columnCsv, true);
    }

    /** includeUpstream false: no upstreamDatasets are written and the metadata needs no upstream columns. */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv, SourceInput columnCsv, boolean includeUpstream) {
        return generate(manifestJson, metadataCsv, columnCsv, includeUpstream, null);
    }

    /**
     * databaseName (may be null) is the database part of every dataset name (db.schema.table); null uses the database of the source
     * (the manifest's logicalName, or the database written in the SQL).
     */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv, SourceInput columnCsv, boolean includeUpstream, String databaseName) {
        List<Finding> findings = new ArrayList<>();
        ManifestTables.Result tables = ManifestTables.read(manifestJson);
        findings.addAll(tables.findings());
        return build(tables.tables(), findings, manifestJson.name(), metadataCsv, columnCsv, includeUpstream, databaseName);
    }

    /**
     * Tables from SQL DDL (CREATE TABLE statements, read by the normal SQL reader). defaultSchema (may be null) is used for tables
     * that have no schema in the SQL. Views are not mapped. A column's description comes from the SQL comment, if any;
     * SQL has no system data elements, so none are written.
     */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema) {
        return generateFromSql(sql, metadataCsv, options, defaultSchema, null);
    }

    /** As above, with optional external column metadata (a system data element per column); columnCsv may be null. */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema,
                                         SourceInput columnCsv) {
        return generateFromSql(sql, metadataCsv, options, defaultSchema, columnCsv, true);
    }

    /** includeUpstream false: no upstreamDatasets are written and the metadata needs no upstream columns. */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema,
                                         SourceInput columnCsv, boolean includeUpstream) {
        return generateFromSql(sql, metadataCsv, options, defaultSchema, columnCsv, includeUpstream, null);
    }

    /** databaseName (may be null): the database part of every dataset name (db.schema.table); null uses the database written in the SQL. */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema,
                                         SourceInput columnCsv, boolean includeUpstream, String databaseName) {
        List<Finding> findings = new ArrayList<>();
        RawMetadataBundle bundle = SourceIngestionService.standard().ingest(sql, options);
        findings.addAll(bundle.findings());
        List<Table> tables = new ArrayList<>();
        int views = 0;
        int noSchema = 0;
        for (RawMetadataBundle.RawTable t : bundle.tables()) {
            if ("VIEW".equals(t.kind())) {
                views++;
                continue;
            }
            String schema = t.schema() != null ? t.schema() : defaultSchema;
            if (schema == null) {
                noSchema++;
            }
            List<Column> cols = new ArrayList<>();
            for (RawMetadataBundle.RawColumn c : t.columns()) {
                cols.add(new Column(c.name(), null, c.description() == null || c.description().isBlank() ? null : c.description()));
            }
            tables.add(new Table(t.database(), schema, t.name(), t.description() == null || t.description().isBlank() ? null : t.description(), cols));
        }
        String label = sql.size() == 1 ? sql.get(0).name() : sql.size() + " SQL files";
        if (views > 0) {
            findings.add(Finding.info("DATASET_VIEW_SKIPPED", label, views + " view(s) in the SQL were not mapped; only tables get a dataset manifest.",
                    "Nothing to do unless a view needs a dataset manifest; then it must be described as a table."));
        }
        if (noSchema > 0) {
            findings.add(Finding.warning("DATASET_SCHEMA_MISSING", label, noSchema + " table(s) have no schema in the SQL, so their datasets are named with the table name only.",
                    "Qualify the table names in the SQL (schema.table) or pass --default-schema SCHEMA, then run again with --overwrite."));
        }
        return build(tables, findings, label, metadataCsv, columnCsv, includeUpstream, databaseName);
    }

    private static Result build(List<Table> sourceTables, List<Finding> findings, String sourceName, SourceInput metadataCsv, SourceInput columnCsv,
                                boolean includeUpstream, String databaseName) {
        TableMetadataCsv.Result meta = TableMetadataCsv.parse(metadataCsv, includeUpstream);
        findings.addAll(meta.findings());
        ColumnMetadataCsv.Result colMeta = columnCsv == null ? ColumnMetadataCsv.Result.empty() : ColumnMetadataCsv.parse(columnCsv);
        findings.addAll(colMeta.findings());
        List<Table> withDb = new ArrayList<>();
        for (Table t : sourceTables) {
            withDb.add(databaseName == null || databaseName.isBlank() ? t : new Table(databaseName.trim(), t.schema(), t.name(), t.description(), t.columns()));
        }
        List<Table> allTables = applyColumnMetadata(withDb, colMeta, columnCsv == null ? null : columnCsv.name(), sourceName, findings);
        if (allTables.isEmpty() || meta.rows().isEmpty() || findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return new Result(List.of(), findings);
        }
        Map<String, List<Table>> byName = new HashMap<>();
        for (Table t : allTables) {
            byName.computeIfAbsent(t.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(t);
        }
        List<Generated> out = new ArrayList<>();
        Set<String> seenRows = new HashSet<>();
        int missingDb = 0;
        Set<String> usedNames = new HashSet<>();
        for (Row row : meta.rows()) {
            String key = row.table().toLowerCase(Locale.ROOT);
            if (!seenRows.add(key)) {
                findings.add(Finding.warning("METADATA_DUPLICATE_TABLE", metadataCsv.name() + ":line " + row.line(),
                        "Table '" + row.table() + "' is listed more than once; the first row was used.", "Remove the duplicate row."));
                continue;
            }
            List<Table> matches = byName.get(key);
            if (matches == null) {
                findings.add(Finding.warning("METADATA_TABLE_NOT_IN_MANIFEST", metadataCsv.name() + ":line " + row.line(),
                        "Table '" + row.table() + "' is not defined in " + sourceName + ", so no manifest was generated for it.",
                        "Correct the table name in the CSV (names are matched ignoring case), or add the table to " + sourceName + ", or delete the row. If " + sourceName + " defines it as a view, only tables are mapped. Details: docs/FINDINGS.md (METADATA_TABLE_NOT_IN_MANIFEST)."));
                continue;
            }
            for (String problem : problems(row, includeUpstream)) {
                findings.add(Finding.warning("METADATA_VALUE_MISSING", metadataCsv.name() + ":line " + row.line(),
                        "Table '" + row.table() + "': " + problem, "Fill in the value in the metadata CSV and run again with --overwrite."));
            }
            for (Table t : matches) {
                if (t.database() == null) {
                    missingDb++;
                }
                String file = fileName(t, matches.size() > 1, usedNames);
                out.add(new Generated(t.schema(), t.name(), file, Json.write(manifest(t, row, findings, metadataCsv.name(), includeUpstream)) + "\n"));
            }
        }
        if (missingDb > 0) {
            findings.add(Finding.warning("DATASET_DATABASE_MISSING", sourceName, missingDb + " dataset(s) have no database, so their names are schema.table instead of database.schema.table.",
                    "Pass --database-name NAME, or give the database in the source (the manifest's logicalName, or database.schema.table in the SQL), then run again with --overwrite."));
        }
        Set<String> described = new HashSet<>();
        for (Row r : meta.rows()) {
            described.add(r.table().toLowerCase(Locale.ROOT));
        }
        int skipped = 0;
        for (Table t : allTables) {
            if (!described.contains(t.name().toLowerCase(Locale.ROOT))) {
                skipped++;
            }
        }
        if (skipped > 0) {
            findings.add(Finding.info("TABLE_WITHOUT_METADATA", sourceName, skipped + " table(s) in " + sourceName + " have no row in "
                    + metadataCsv.name() + " and were not generated.", "If those tables need a dataset manifest, add a row for each to the metadata CSV and run again with --overwrite. Otherwise nothing to do."));
        }
        findings.add(Finding.info("DATASET_SUMMARY", sourceName, "Read " + allTables.size() + " table(s) from " + sourceName + "; " + metadataCsv.name() + " has "
                + meta.rows().size() + " row(s); generated " + out.size() + " dataset manifest(s). Only tables that have a metadata row are generated.",
                "If fewer manifests were generated than you expected, check the warnings above: rows that match no table, duplicate rows, and tables without a row."));
        if (out.isEmpty()) {
            findings.add(Finding.error("NO_TABLES_MATCHED", metadataCsv.name(), "No table in the metadata file matches a table in the manifest.",
                    "Check the table names in both files."));
        }
        return new Result(out, findings);
    }

    /** Replaces (or supplies) each column's system data element with the externally given one; reports entries that match nothing. */
    private static List<Table> applyColumnMetadata(List<Table> tables, ColumnMetadataCsv.Result meta, String metaName, String sourceName, List<Finding> findings) {
        if (meta.entries().isEmpty()) {
            return tables;
        }
        java.util.Set<String> used = new HashSet<>();
        List<Table> out = new ArrayList<>();
        for (Table t : tables) {
            Map<String, ColumnMetadataCsv.Entry> forTable = meta.entries().get(t.name().toLowerCase(Locale.ROOT));
            if (forTable == null) {
                out.add(t);
                continue;
            }
            List<Column> cols = new ArrayList<>();
            for (Column c : t.columns()) {
                ColumnMetadataCsv.Entry e = forTable.get(c.physicalName().toLowerCase(Locale.ROOT));
                if (e == null) {
                    cols.add(c);
                } else {
                    used.add(t.name().toLowerCase(Locale.ROOT) + "." + c.physicalName().toLowerCase(Locale.ROOT));
                    cols.add(new Column(c.physicalName(), e.systemDataElement(), c.description()));
                }
            }
            out.add(new Table(t.database(), t.schema(), t.name(), t.description(), cols));
        }
        Set<String> tableNames = new HashSet<>();
        for (Table t : tables) {
            tableNames.add(t.name().toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<String, Map<String, ColumnMetadataCsv.Entry>> te : meta.entries().entrySet()) {
            for (Map.Entry<String, ColumnMetadataCsv.Entry> ce : te.getValue().entrySet()) {
                if (!used.contains(te.getKey() + "." + ce.getKey())) {
                    ColumnMetadataCsv.Entry e = ce.getValue();
                    findings.add(Finding.warning("COLUMN_METADATA_NOT_FOUND", metaName + ":line " + e.line(),
                            tableNames.contains(te.getKey())
                                    ? "Column '" + e.column() + "' is not a column of table '" + e.table() + "' in " + sourceName + ", so its system data element was not used."
                                    : "Table '" + e.table() + "' is not defined in " + sourceName + ", so the system data element for column '" + e.column() + "' was not used.",
                            "Correct the table or column name in the column metadata file (names are matched ignoring case), or delete the row."));
                }
            }
        }
        return out;
    }

    private static List<String> problems(Row r, boolean includeUpstream) {
        List<String> p = new ArrayList<>();
        if (r.domain().isEmpty()) {
            p.add("no domain; the dataset has no data domain.");
        }
        if (r.boundary().isEmpty()) {
            p.add("no boundary; the dataset has no boundary.");
        }
        if (includeUpstream && r.upstreamDataset().isEmpty()) {
            p.add("no upstream dataset; the dataset has no upstream mapping.");
        }
        return p;
    }

    private static String fileName(Table t, boolean ambiguous, Set<String> used) {
        String base = "manifest_" + safe(ambiguous && t.schema() != null ? t.schema() + "_" + t.name() : t.name());
        String name = base + ".json";
        int n = 2;
        while (!used.add(name.toLowerCase(Locale.ROOT))) {
            name = base + "_" + n++ + ".json";
        }
        return name;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Map<String, Object> manifest(Table t, Row row, List<Finding> findings, String metaName, boolean includeUpstream) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", (t.database() != null ? t.database() + "." : "") + (t.schema() != null ? t.schema() + "." : "") + t.name());
        d.put("description", t.description() != null ? t.description()
                : t.name() + " table" + (t.schema() != null ? " of the " + t.schema() + " schema" : ""));
        d.put("format", FORMAT);
        if (!row.boundary().isEmpty()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("product", List.of(row.boundary()));
            d.put("boundaries", List.of(b));
        }
        if (!row.domain().isEmpty()) {
            Map<String, Object> dom = new LinkedHashMap<>();
            dom.put("domainName", row.domain());
            d.put("dgcDataDomains", new LinkedHashMap<>(Map.of(row.glossary(), List.of(dom))));
        }
        if (includeUpstream && !row.upstreamDataset().isEmpty()) {
            Map<String, Object> up = new LinkedHashMap<>();
            up.put("name", row.upstreamDataset());
            Object eon = eon(row, findings, metaName);
            if (eon != null) {
                up.put("producerEonId", eon);
            }
            d.put("upstreamDatasets", List.of(up));
        }
        List<Object> els = new ArrayList<>();
        for (Column c : t.columns()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", c.physicalName());
            String desc = c.description() != null ? c.description() : c.systemDataElement();
            if (desc != null) {
                e.put("description", desc);
            }
            if (c.systemDataElement() != null) {
                e.put("systemDataElements", List.of(c.systemDataElement()));
            }
            els.add(e);
        }
        d.put("datasetElements", els);
        d.put("eucOrManualProcess", new LinkedHashMap<String, Object>());
        d.put("originatingCatalogingSource", CATALOGING_SOURCE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("datasets", List.of(d));
        return root;
    }

    /** The producer EON ID as a number when it is one; otherwise kept as text with a warning. */
    private static Object eon(Row row, List<Finding> findings, String metaName) {
        String v = row.producerEonId();
        if (v.isEmpty()) {
            findings.add(Finding.warning("METADATA_VALUE_MISSING", metaName + ":line " + row.line(),
                    "Table '" + row.table() + "': no producer EON ID; it was left out.", "Fill in the producer eonid."));
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            findings.add(Finding.warning("METADATA_EON_NOT_NUMERIC", metaName + ":line " + row.line(),
                    "Table '" + row.table() + "': producer EON ID '" + v + "' is not a number; it was written as text.", "Use the numeric EON ID."));
            return v;
        }
    }
}

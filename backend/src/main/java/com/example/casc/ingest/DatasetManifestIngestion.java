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
    /** Every dataset is named database.schema.table with these defaults (a2.dbo.tableName), whatever database and schema the source uses. */
    public static final String DEFAULT_DATABASE = "a2";
    public static final String DEFAULT_SCHEMA = "dbo";
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
        return generate(manifestJson, metadataCsv, columnCsv, includeUpstream, databaseName, null);
    }

    /**
     * Optional extra inputs: a table list (which tables to generate, with a default domain and boundary) instead of, or together with, the metadata CSV,
     * and a table descriptions file (a description per table). Each part may be null.
     */
    public record TableSelection(SourceInput tableList, String domain, String boundary, SourceInput descriptions) {
        public TableSelection(SourceInput tableList, String domain, String boundary) {
            this(tableList, domain, boundary, null);
        }
    }

    /** metadataCsv may be null when selection has a table list. selection may be null. */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv, SourceInput columnCsv, boolean includeUpstream, String databaseName,
                                  TableSelection selection) {
        return generate(manifestJson, metadataCsv, columnCsv, includeUpstream, databaseName, null, selection);
    }

    /** databaseName and schemaName (each may be null) replace the defaults a2 and dbo in every dataset name. */
    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv, SourceInput columnCsv, boolean includeUpstream, String databaseName,
                                  String schemaName, TableSelection selection) {
        List<Finding> findings = new ArrayList<>();
        ManifestTables.Result tables = ManifestTables.read(manifestJson);
        findings.addAll(tables.findings());
        return build(tables.tables(), findings, manifestJson.name(), metadataCsv, columnCsv, includeUpstream, databaseName, schemaName, selection);
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
        return generateFromSql(sql, metadataCsv, options, defaultSchema, columnCsv, includeUpstream, databaseName, null);
    }

    /** metadataCsv may be null when selection has a table list. selection may be null. */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema,
                                         SourceInput columnCsv, boolean includeUpstream, String databaseName, TableSelection selection) {
        return generateFromSql(sql, metadataCsv, options, defaultSchema, columnCsv, includeUpstream, databaseName, null, selection);
    }

    /** databaseName and schemaName (each may be null) replace the defaults a2 and dbo in every dataset name. */
    public static Result generateFromSql(List<SourceInput> sql, SourceInput metadataCsv, IngestOptions options, String defaultSchema,
                                         SourceInput columnCsv, boolean includeUpstream, String databaseName, String schemaName, TableSelection selection) {
        List<Finding> findings = new ArrayList<>();
        RawMetadataBundle bundle = SourceIngestionService.standard().ingest(sql, options);
        findings.addAll(bundle.findings());
        List<Table> tables = new ArrayList<>();
        int views = 0;
        for (RawMetadataBundle.RawTable t : bundle.tables()) {
            if ("VIEW".equals(t.kind())) {
                views++;
                continue;
            }
            String schema = t.schema() != null ? t.schema() : defaultSchema;
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
        return build(tables, findings, label, metadataCsv, columnCsv, includeUpstream, databaseName, schemaName, selection);
    }

    private static Result build(List<Table> sourceTables, List<Finding> findings, String sourceName, SourceInput metadataCsv, SourceInput columnCsv,
                                boolean includeUpstream, String databaseName, String schemaName, TableSelection selection) {
        SourceInput listInput = selection == null ? null : selection.tableList();
        String metaName = metadataCsv != null ? metadataCsv.name() : listInput != null ? listInput.name() : "the metadata";
        TableMetadataCsv.Result meta = metadataCsv != null ? TableMetadataCsv.parse(metadataCsv, includeUpstream) : new TableMetadataCsv.Result(List.of(), List.of());
        findings.addAll(meta.findings());
        TableListCsv.Result list = listInput != null ? TableListCsv.parse(listInput) : null;
        if (list != null) {
            findings.addAll(list.findings());
        }
        ColumnMetadataCsv.Result colMeta = columnCsv == null ? ColumnMetadataCsv.Result.empty() : ColumnMetadataCsv.parse(columnCsv);
        findings.addAll(colMeta.findings());
        // table names are compared by the bare name only: a prefix such as a2.dbo. in the source is ignored too
        List<Table> bareTables = new ArrayList<>();
        for (Table t : sourceTables) {
            bareTables.add(new Table(t.database(), t.schema(), TableNames.bare(t.name()), t.description(), t.columns()));
        }
        sourceTables = bareTables;
        String prefix = (databaseName == null || databaseName.isBlank() ? DEFAULT_DATABASE : databaseName.trim()) + "."
                + (schemaName == null || schemaName.isBlank() ? DEFAULT_SCHEMA : schemaName.trim()) + ".";
        List<Table> allTables = applyColumnMetadata(sourceTables, colMeta, columnCsv == null ? null : columnCsv.name(), sourceName, findings);
        SourceInput descInput = selection == null ? null : selection.descriptions();
        Set<String> describedByFile = new HashSet<>();
        if (descInput != null) {
            TableDescriptionsCsv.Result desc = TableDescriptionsCsv.parse(descInput);
            findings.addAll(desc.findings());
            allTables = applyDescriptions(allTables, desc, descInput.name(), sourceName, describedByFile, findings);
        }

        // the rows that drive generation: the metadata rows, or (with a table list) one row per listed table
        java.util.Set<Row> defaulted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Row> rows;
        if (list == null) {
            rows = meta.rows();
        } else {
            Map<String, Row> metaByTable = new HashMap<>();
            for (Row r : meta.rows()) {
                metaByTable.putIfAbsent(r.table().toLowerCase(Locale.ROOT), r);
            }
            String[] dom = TableMetadataCsv.splitDomain(selection.domain());
            rows = new ArrayList<>();
            for (TableListCsv.Entry e : list.entries()) {
                Row m = metaByTable.get(e.table().toLowerCase(Locale.ROOT));
                if (m != null) {
                    rows.add(new Row(e.table(), m.domain(), m.glossary(), m.boundary(), m.upstreamDataset(), m.producerEonId(), m.line(), e.qualifier(), m.description()));
                } else {
                    Row d = new Row(e.table(), dom[0], dom[1], selection.boundary() == null ? "" : selection.boundary().trim(), "", "", e.line(), e.qualifier(), "");
                    rows.add(d);
                    defaulted.add(d);
                }
            }
            if (!defaulted.isEmpty() && (!dom[0].isEmpty() || selection.boundary() != null)) {
                findings.add(Finding.info("TABLE_LIST_DEFAULTS_USED", listInput.name(), defaulted.size() + " listed table(s) have no row in the table metadata, so the domain and boundary "
                        + "given with --domain and --boundary were used" + (includeUpstream ? " and they have no upstream dataset" : "") + ".",
                        "Add a row for a table to the table metadata if it needs its own domain, boundary or upstream."));
            }
        }
        if (allTables.isEmpty() || rows.isEmpty() || findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return new Result(List.of(), findings);
        }
        Map<String, List<Table>> byName = new HashMap<>();
        for (Table t : allTables) {
            byName.computeIfAbsent(t.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(t);
        }
        List<Generated> out = new ArrayList<>();
        Set<String> seenRows = new HashSet<>();
        java.util.Map<String, Integer> nameCount = new java.util.LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>();
        List<Table> usedTables = new ArrayList<>();
        Set<String> describedKeys = new HashSet<>();
        for (Row row : rows) {
            String key = ((row.qualifier() == null ? "" : row.qualifier() + ".") + row.table()).toLowerCase(Locale.ROOT);
            if (!seenRows.add(key)) {
                findings.add(Finding.warning("METADATA_DUPLICATE_TABLE", metaName + ":line " + row.line(),
                        "Table '" + row.table() + "' is listed more than once; the first row was used.", "Remove the duplicate row."));
                continue;
            }
            List<Table> matches = new ArrayList<>();
            for (Table t : byName.getOrDefault(row.table().toLowerCase(Locale.ROOT), List.of())) {
                if (qualifierMatches(t, row.qualifier())) {
                    matches.add(t);
                }
            }
            if (matches.isEmpty()) {
                String shown = (row.qualifier() == null ? "" : row.qualifier() + ".") + row.table();
                if (list != null) {
                    findings.add(Finding.warning("TABLE_LIST_NOT_IN_SOURCE", listInput.name() + ":line " + row.line(),
                            "Table '" + shown + "' is not defined in " + sourceName + ", so no manifest was generated for it.",
                            "Correct the name in the table list (names are matched ignoring case, and a prefix such as a2.dbo. is ignored), or add the table to "
                                    + sourceName + ". If " + sourceName + " defines it as a view, only tables are mapped. Details: docs/FINDINGS.md (TABLE_LIST_NOT_IN_SOURCE)."));
                } else {
                    findings.add(Finding.warning("METADATA_TABLE_NOT_IN_MANIFEST", metaName + ":line " + row.line(),
                            "Table '" + row.table() + "' is not defined in " + sourceName + ", so no manifest was generated for it.",
                            "Correct the table name in the CSV (names are matched ignoring case), or add the table to " + sourceName + ", or delete the row. If " + sourceName
                                    + " defines it as a view, only tables are mapped. Details: docs/FINDINGS.md (METADATA_TABLE_NOT_IN_MANIFEST)."));
                }
                continue;
            }
            for (String problem : problems(row, includeUpstream && !defaulted.contains(row))) {
                findings.add(Finding.warning("METADATA_VALUE_MISSING", (defaulted.contains(row) ? listInput.name() : metaName) + ":line " + row.line(),
                        "Table '" + row.table() + "': " + problem,
                        defaulted.contains(row) ? "Pass --domain and --boundary, or add a row for the table to --table-metadata, then run again with --overwrite."
                                : "Fill in the value in the metadata CSV and run again with --overwrite."));
            }
            for (Table t : matches) {
                nameCount.merge(prefix + t.name(), 1, Integer::sum);
                usedTables.add(t);
                String file = fileName(t, matches.size() > 1, usedNames);
                // description: the descriptions file, then the description column of the metadata file, then the source (a SQL comment), then a generated one
                boolean fromFile = describedByFile.contains(tableKey(t));
                boolean fromRow = !fromFile && !row.description().isEmpty();
                if (fromFile || fromRow) {
                    describedKeys.add(tableKey(t));
                }
                String description = fromRow ? row.description() : t.description();
                out.add(new Generated(t.schema(), t.name(), file, Json.write(manifest(t, row, findings, metaName, includeUpstream, prefix, description)) + "\n"));
            }
        }
        boolean rowsDescribe = rows.stream().anyMatch(r -> !r.description().isEmpty());
        if (descInput != null || rowsDescribe) {
            int without = 0;
            for (Table t : usedTables) {
                if (!describedKeys.contains(tableKey(t))) {
                    without++;
                }
            }
            if (without > 0) {
                String where = descInput != null && rowsDescribe ? descInput.name() + " or the description column of " + metaName : descInput != null ? descInput.name() : "the description column of " + metaName;
                findings.add(Finding.info("TABLE_DESCRIPTION_DEFAULT_USED", descInput != null ? descInput.name() : metaName, without + " of the " + usedTables.size()
                        + " generated dataset(s) have no description in " + where + ", so their description comes from the source (a SQL comment) or is generated from the table name.",
                        "Add a description for each of those tables and run again with --overwrite."));
            }
        }
        for (java.util.Map.Entry<String, Integer> e : nameCount.entrySet()) {
            if (e.getValue() > 1) {
                findings.add(Finding.warning("DATASET_NAME_DUPLICATE", sourceName, e.getValue() + " tables of " + sourceName + " (from different schemas or databases) all get the dataset name '" + e.getKey()
                        + "', because every dataset is named " + prefix + "tableName. Their files are kept apart, but the names are the same.",
                        "Generate them from separate sources, or give them different names in the source."));
            }
        }
        Set<String> described = new HashSet<>();
        for (Row r : rows) {
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
                    + metaName + " and were not generated.", list != null
                    ? "If those tables need a dataset manifest, add them to the table list and run again with --overwrite. Otherwise nothing to do."
                    : "If those tables need a dataset manifest, add a row for each to the metadata CSV and run again with --overwrite. Otherwise nothing to do."));
        }
        findings.add(Finding.info("DATASET_SUMMARY", sourceName, "Read " + allTables.size() + " table(s) from " + sourceName + "; " + metaName + " has "
                + rows.size() + (list != null ? " table(s)" : " row(s)") + "; generated " + out.size() + " dataset manifest(s). Only "
                + (list != null ? "tables in the table list" : "tables that have a metadata row") + " are generated.",
                "If fewer manifests were generated than you expected, check the warnings above: entries that match no table, duplicates, and tables that are not listed."));
        if (out.isEmpty()) {
            findings.add(Finding.error("NO_TABLES_MATCHED", metaName, "No table in " + metaName + " matches a table in " + sourceName + ".",
                    "Check the table names in both files."));
        }
        return new Result(out, findings);
    }

    private static String tableKey(Table t) {
        return ((t.database() == null ? "" : t.database() + ".") + (t.schema() == null ? "" : t.schema() + ".") + t.name()).toLowerCase(Locale.ROOT);
    }

    /** The description of a table comes from the descriptions file when it has a row there; entries that match no table are reported. */
    private static List<Table> applyDescriptions(List<Table> tables, TableDescriptionsCsv.Result desc, String descName, String sourceName, Set<String> describedKeys,
                                                 List<Finding> findings) {
        if (desc.entries().isEmpty()) {
            return tables;
        }
        Set<TableDescriptionsCsv.Entry> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Table> out = new ArrayList<>();
        for (Table t : tables) {
            TableDescriptionsCsv.Entry hit = null;
            for (TableDescriptionsCsv.Entry e : desc.entries()) {
                if (e.table().equalsIgnoreCase(t.name()) && qualifierMatches(t, e.qualifier())) {
                    hit = e;
                    used.add(e);
                    break;
                }
            }
            if (hit == null) {
                out.add(t);
            } else {
                Table n = new Table(t.database(), t.schema(), t.name(), hit.description(), t.columns());
                describedKeys.add(tableKey(n));
                out.add(n);
            }
        }
        for (TableDescriptionsCsv.Entry e : desc.entries()) {
            if (!used.contains(e)) {
                findings.add(Finding.warning("TABLE_DESCRIPTION_NOT_FOUND", descName + ":line " + e.line(),
                        "Table '" + (e.qualifier() == null ? "" : e.qualifier() + ".") + e.table() + "' is not defined in " + sourceName + ", so its description was not used.",
                        "Correct the table name in the descriptions file (matching ignores case; a schema.table or database.schema.table must match the source), or delete the row."));
            }
        }
        return out;
    }

    /** qualifier null: any table; "schema": the schema must match; "database.schema": both must match (case ignored). */
    private static boolean qualifierMatches(Table t, String qualifier) {
        if (qualifier == null) {
            return true;
        }
        int dot = qualifier.lastIndexOf('.');
        String schema = dot >= 0 ? qualifier.substring(dot + 1) : qualifier;
        String db = dot >= 0 ? qualifier.substring(0, dot) : null;
        return t.schema() != null && t.schema().equalsIgnoreCase(schema.trim()) && (db == null || t.database() != null && t.database().equalsIgnoreCase(db.trim()));
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

    private static Map<String, Object> manifest(Table t, Row row, List<Finding> findings, String metaName, boolean includeUpstream, String prefix, String description) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", prefix + t.name());
        d.put("description", description != null ? description
                : t.name() + " table of the " + prefix.substring(prefix.indexOf('.') + 1, prefix.length() - 1) + " schema");
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

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

    public static Result generate(SourceInput manifestJson, SourceInput metadataCsv) {
        List<Finding> findings = new ArrayList<>();
        ManifestTables.Result tables = ManifestTables.read(manifestJson);
        TableMetadataCsv.Result meta = TableMetadataCsv.parse(metadataCsv);
        findings.addAll(tables.findings());
        findings.addAll(meta.findings());
        if (tables.tables().isEmpty() || meta.rows().isEmpty() || findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return new Result(List.of(), findings);
        }
        Map<String, List<Table>> byName = new HashMap<>();
        for (Table t : tables.tables()) {
            byName.computeIfAbsent(t.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(t);
        }
        List<Generated> out = new ArrayList<>();
        Set<String> seenRows = new HashSet<>();
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
                        "Table '" + row.table() + "' is not defined in " + manifestJson.name() + ", so no manifest was generated for it.",
                        "Correct the table name in the CSV (names are matched ignoring case), or add the table to the manifest, or delete the row. Details: docs/FINDINGS.md (METADATA_TABLE_NOT_IN_MANIFEST)."));
                continue;
            }
            for (String problem : problems(row)) {
                findings.add(Finding.warning("METADATA_VALUE_MISSING", metadataCsv.name() + ":line " + row.line(),
                        "Table '" + row.table() + "': " + problem, "Fill in the value in the metadata CSV and run again with --overwrite."));
            }
            for (Table t : matches) {
                String file = fileName(t, matches.size() > 1, usedNames);
                out.add(new Generated(t.schema(), t.name(), file, Json.write(manifest(t, row, findings, metadataCsv.name())) + "\n"));
            }
        }
        Set<String> described = new HashSet<>();
        for (Row r : meta.rows()) {
            described.add(r.table().toLowerCase(Locale.ROOT));
        }
        int skipped = 0;
        for (Table t : tables.tables()) {
            if (!described.contains(t.name().toLowerCase(Locale.ROOT))) {
                skipped++;
            }
        }
        if (skipped > 0) {
            findings.add(Finding.info("TABLE_WITHOUT_METADATA", manifestJson.name(), skipped + " table(s) in the manifest have no row in "
                    + metadataCsv.name() + " and were not generated.", "If those tables need a dataset manifest, add a row for each to the metadata CSV and run again with --overwrite. Otherwise nothing to do."));
        }
        if (out.isEmpty()) {
            findings.add(Finding.error("NO_TABLES_MATCHED", metadataCsv.name(), "No table in the metadata file matches a table in the manifest.",
                    "Check the table names in both files."));
        }
        return new Result(out, findings);
    }

    private static List<String> problems(Row r) {
        List<String> p = new ArrayList<>();
        if (r.domain().isEmpty()) {
            p.add("no domain; the dataset has no data domain.");
        }
        if (r.boundary().isEmpty()) {
            p.add("no boundary; the dataset has no boundary.");
        }
        if (r.upstreamDataset().isEmpty()) {
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

    private static Map<String, Object> manifest(Table t, Row row, List<Finding> findings, String metaName) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", (t.schema() != null ? t.schema() + "." : "") + t.name());
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
        if (!row.upstreamDataset().isEmpty()) {
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
            if (c.systemDataElement() != null) {
                e.put("description", c.systemDataElement());
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

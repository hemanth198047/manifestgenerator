package com.example.casc.ingest;

import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional external column metadata for dataset-manifest generation: one row per column with its system data element.
 * Header: table name, column name, system data element (names are matched ignoring case, spaces and punctuation).
 * A prefix such as a2.dbo. on the table name is ignored; only the bare table name is used for matching.
 * A value given here replaces the one from the source (a manifest JSON's systemDataElement) and fills in what SQL cannot supply.
 */
public final class ColumnMetadataCsv {
    private ColumnMetadataCsv() {}

    /** key: lower-case table, then lower-case column. */
    public record Entry(String table, String column, String systemDataElement, int line) {}

    public record Result(Map<String, Map<String, Entry>> entries, List<Finding> findings) {
        public static Result empty() {
            return new Result(Map.of(), List.of());
        }
    }

    public static Result parse(SourceInput in) {
        List<Finding> findings = new ArrayList<>();
        if (in.readError() != null || in.text() == null) {
            findings.add(Finding.error("COLUMN_METADATA_UNREADABLE", in.name(), in.readError() == null ? "The column metadata file has no text." : in.readError(),
                    "Fix the file and run the command again."));
            return new Result(Map.of(), findings);
        }
        char delim = SourceIngestionService.extension(in.name()).equals("tsv") ? '\t' : ',';
        Csv.Result r;
        try {
            r = Csv.parse(in.text(), delim, 1_000_000);
        } catch (Csv.CsvException e) {
            findings.add(Finding.error("CSV_SYNTAX", in.name() + ":line " + e.line(), e.getMessage(),
                    "Fix the quoting at that line, or export the file again with standard CSV quoting."));
            return new Result(Map.of(), findings);
        }
        if (r.records().isEmpty()) {
            findings.add(Finding.error("COLUMN_METADATA_UNREADABLE", in.name(), "The column metadata file has no rows.",
                    "Add the header: table name, column name, system data element, and one row per column."));
            return new Result(Map.of(), findings);
        }
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> header = r.records().get(0);
        for (int i = 0; i < header.size(); i++) {
            String k = canonical(header.get(i));
            if (k != null) {
                idx.putIfAbsent(k, i);
            }
        }
        for (String need : List.of("table", "column", "element")) {
            if (!idx.containsKey(need)) {
                findings.add(Finding.error("COLUMN_METADATA_COLUMN_MISSING", in.name() + ":line " + r.lines().get(0),
                        "The column metadata file has no '" + label(need) + "' column.",
                        "Use the header: table name, column name, system data element."));
            }
        }
        if (!findings.isEmpty()) {
            return new Result(Map.of(), findings);
        }
        Map<String, Map<String, Entry>> out = new HashMap<>();
        for (int k = 1; k < r.records().size(); k++) {
            List<String> rec = r.records().get(k);
            int line = r.lines().get(k);
            String table = cell(rec, idx.get("table"));
            String column = cell(rec, idx.get("column"));
            String element = cell(rec, idx.get("element"));
            if (table.isEmpty() || column.isEmpty() || element.isEmpty()) {
                if (!(table.isEmpty() && column.isEmpty() && element.isEmpty())) {
                    findings.add(Finding.warning("COLUMN_METADATA_ROW_SKIPPED", in.name() + ":line " + line,
                            "A row is missing its table name, column name or system data element and was skipped.",
                            "Fill in all three values or delete the row."));
                }
                continue;
            }
            String t = TableNames.bare(table).toLowerCase(Locale.ROOT);
            Map<String, Entry> cols = out.computeIfAbsent(t, x -> new HashMap<>());
            String c = column.toLowerCase(Locale.ROOT);
            if (cols.containsKey(c)) {
                findings.add(Finding.warning("COLUMN_METADATA_DUPLICATE", in.name() + ":line " + line,
                        "Column '" + column + "' of table '" + table + "' is listed more than once; the first row was used.", "Delete the duplicate row."));
                continue;
            }
            cols.put(c, new Entry(table, column, element, line));
        }
        if (r.truncated()) {
            findings.add(Finding.warning("COLUMN_METADATA_ROW_SKIPPED", in.name(), "The column metadata file is longer than the supported number of rows; the rest was not read.", "Split the file."));
        }
        return new Result(out, findings);
    }

    private static String cell(List<String> rec, int i) {
        return i < rec.size() ? rec.get(i).trim() : "";
    }

    private static String label(String key) {
        return switch (key) {
            case "table" -> "table name";
            case "column" -> "column name";
            default -> "system data element";
        };
    }

    private static String canonical(String header) {
        String h = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (h) {
            case "tablename", "table" -> "table";
            case "columnname", "column", "physicalname" -> "column";
            case "systemdataelement", "systemdataelements", "sde" -> "element";
            default -> null;
        };
    }
}

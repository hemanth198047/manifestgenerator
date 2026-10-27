package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CSV and TSV. Two modes:
 * data       the file is a data dump or dataset: one table named after the file, columns from the header,
 *            types inferred from at most maxRows sampled rows. Row values are NOT retained.
 * dictionary the rows describe tables and columns (see Dictionary for header names and overrides).
 * auto picks dictionary when a column-name header and a description or type header are found.
 */
public final class DelimitedStrategy implements SourceIngestionStrategy {
    private static final Pattern INT = Pattern.compile("[+-]?\\d{1,18}");
    private static final Pattern DEC = Pattern.compile("[+-]?\\d+\\.\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");

    @Override public String id() { return "delimited"; }

    @Override public Set<String> extensions() { return Set.of("csv", "tsv"); }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String ext = SourceIngestionService.extension(in.name());
        String format = ext.equals("tsv") ? "tsv" : "csv";
        char delim = o.delimiter() != 0 ? o.delimiter() : (ext.equals("tsv") ? '\t' : ',');
        int headerIndex = o.hasHeader() ? o.headerRow() - 1 : -1;
        Csv.Result r;
        try {
            r = Csv.parse(in.text(), delim, Math.max(o.headerRow(), 1) + o.maxRows());
        } catch (Csv.CsvException e) {
            b.error("CSV_SYNTAX", in.name() + ":line " + e.line, e.getMessage(),
                    "Fix the quoting at that line, or export the file again with standard CSV quoting.");
            b.source(in.name(), format, "REJECTED", e.getMessage());
            return b.build();
        }
        List<List<String>> recs = r.records();
        List<Integer> lines = r.lines();
        if (recs.isEmpty() || (o.hasHeader() && recs.size() <= headerIndex)) {
            b.error("CSV_NO_HEADER", in.name(), "No header row was found at row " + o.headerRow() + ".",
                    "Check headerRow and hasHeader, or confirm the file is not empty.");
            b.source(in.name(), format, "REJECTED", "no header row");
            return b.build();
        }
        List<String> headers = new ArrayList<>();
        int dataStart;
        if (o.hasHeader()) {
            for (String h : recs.get(headerIndex)) {
                headers.add(h.trim());
            }
            dataStart = headerIndex + 1;
            if (headerIndex > 0) {
                b.info("CSV_ROWS_BEFORE_HEADER", in.name(), headerIndex + " row(s) before the header row were skipped.",
                        "Check headerRow if this is not intended.");
            }
        } else {
            for (int i = 1; i <= recs.get(0).size(); i++) {
                headers.add("column" + i);
            }
            dataStart = 0;
        }
        List<List<String>> rows = recs.subList(dataStart, recs.size());
        List<Integer> rowLines = lines.subList(dataStart, lines.size());

        Dictionary.Resolved res = Dictionary.resolve(headers, o.columnMap());
        boolean dictionary;
        switch (o.mode()) {
            case "dictionary" -> dictionary = true;
            case "data" -> dictionary = false;
            default -> dictionary = res.mapping().looksLikeDictionary();
        }
        if (dictionary) {
            if (!res.mapping().usable() || !res.problems().isEmpty()) {
                String detail = res.problems().isEmpty()
                        ? "No column-name header was found. Headers seen: " + headers + "."
                        : String.join("; ", res.problems());
                b.error("DICT_COLUMN_UNMAPPED", in.name(), detail,
                        "Add a header such as 'Column' or 'Field', or set map.column to the right header.");
                b.source(in.name(), format, "REJECTED", "dictionary columns not mapped");
                return b.build();
            }
            Dictionary.apply(in.name(), res.mapping(), rows, rowLines, null, b);
            if (r.truncated()) {
                b.warn("DICT_TRUNCATED", in.name(), "Only the first " + o.maxRows() + " rows were read (maxRows).",
                        "Increase maxRows to read the rest.");
            }
            b.source(in.name(), format, "INGESTED", "dictionary: " + b.tableCount() + " table(s), "
                    + b.termCount() + " unassigned term(s), " + rows.size() + " row(s) read");
            return b.build();
        }
        int headerLine = lines.get(o.hasHeader() ? headerIndex : 0);
        dataDump(in, format, headers, rows, headerLine, r.truncated(), o, b);
        return b.build();
    }

    private void dataDump(SourceInput in, String format, List<String> headers, List<List<String>> rows,
                          int headerLine, boolean truncated, IngestOptions o, Builder b) {
        int n = headers.size();
        boolean[] anyValue = new boolean[n];
        boolean[] anyEmpty = new boolean[n];
        String[] type = new String[n];
        int ragged = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() != n) {
                ragged++;
            }
            for (int c = 0; c < n; c++) {
                String v = c < row.size() ? row.get(c).trim() : "";
                if (v.isEmpty()) {
                    anyEmpty[c] = true;
                } else {
                    anyValue[c] = true;
                    type[c] = merge(type[c], classify(v));
                }
            }
        }
        List<RawColumn> columns = new ArrayList<>();
        String headerLoc = "line " + headerLine;
        for (int c = 0; c < n; c++) {
            String name = headers.get(c);
            if (name.isEmpty()) {
                name = "column" + (c + 1);
                b.warn("CSV_BLANK_HEADER", in.name() + ":" + headerLoc, "Header " + (c + 1) + " is blank; it was named '" + name + "'.",
                        "Give the column a name in the source file.");
            }
            columns.add(new RawColumn(name, anyValue[c] ? type[c] : null, anyValue[c], anyEmpty[c] ? Boolean.TRUE : null,
                    false, null, headerLoc));
        }
        Set<String> seen = new java.util.HashSet<>();
        for (RawColumn c : columns) {
            if (!seen.add(c.name().toLowerCase(java.util.Locale.ROOT))) {
                b.warn("CSV_DUPLICATE_HEADER", in.name() + ":" + headerLoc, "Header '" + c.name() + "' appears more than once; both columns are kept.",
                        "Rename one of the columns in the source file.");
            }
        }
        if (ragged > 0) {
            b.warn("CSV_RAGGED", in.name(), ragged + " row(s) do not have " + n + " fields.",
                    "Check quoting and delimiters; missing cells are treated as empty.");
        }
        if (truncated) {
            b.info("SAMPLING_LIMIT", in.name(), "Types were inferred from the first " + o.maxRows() + " data rows only (maxRows).",
                    "Increase maxRows to sample more rows.");
        }
        String base = baseName(in.name());
        b.table(new RawTable(null, null, base, "TABLE", null, List.copyOf(columns), in.name(), headerLoc));
        b.info("TYPES_INFERRED", in.name(), "Column types are inferred from sampled values and are not authoritative. Row values were not retained.",
                "Confirm types against the real source.");
        b.source(in.name(), format, "INGESTED", "data: table '" + base + "', " + n + " column(s), " + rows.size() + " row(s) sampled");
    }

    static String baseName(String name) {
        String n = name.substring(name.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String classify(String v) {
        if (INT.matcher(v).matches()) return "INTEGER";
        if (DEC.matcher(v).matches()) return "DECIMAL";
        if (DATE.matcher(v).matches()) return "DATE";
        if (TS.matcher(v).matches()) return "TIMESTAMP";
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return "BOOLEAN";
        return "TEXT";
    }

    private static String merge(String a, String b) {
        if (a == null || a.equals(b)) return b;
        if ((a.equals("INTEGER") && b.equals("DECIMAL")) || (a.equals("DECIMAL") && b.equals("INTEGER"))) return "DECIMAL";
        if ((a.equals("DATE") && b.equals("TIMESTAMP")) || (a.equals("TIMESTAMP") && b.equals("DATE"))) return "TIMESTAMP";
        return "TEXT";
    }
}

package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for any table of cells, whatever file it came from (CSV, TSV, a spreadsheet sheet, a document table).
 * Two modes: data (a data dump: one table, columns from the header, types inferred from at most maxRows sampled rows,
 * row values NOT retained) and dictionary (rows describe tables and columns; see Dictionary). auto picks dictionary when a
 * column-name header and a description or type header are found.
 */
public final class Tabular {
    private Tabular() {}

    private static final Pattern INT = Pattern.compile("[+-]?\\d{1,18}");
    private static final Pattern DEC = Pattern.compile("[+-]?\\d+\\.\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");

    /** rejected is true when nothing usable was extracted; detail is the one-line summary for the source list. */
    public record Result(boolean rejected, String detail) {}

    /**
     * @param source    the source name used in findings
     * @param tableName name for the table in data mode (file name or sheet name)
     * @param where     prefix of locations, for example "line " or "sheet 'Data', row "
     * @param recs      the rows, each a list of cell texts
     * @param numbers   the number of each row as shown to the user (same size as recs)
     */
    public static Result ingest(Builder b, String source, String tableName, String where, List<List<String>> recs,
                                List<Integer> numbers, boolean truncated, IngestOptions o) {
        int headerIndex = o.hasHeader() ? o.headerRow() - 1 : -1;
        if (recs.isEmpty() || (o.hasHeader() && recs.size() <= headerIndex)) {
            b.error("CSV_NO_HEADER", source, "No header row was found at row " + o.headerRow() + ".",
                    "Check headerRow and hasHeader, or confirm the source is not empty.");
            return new Result(true, "no header row");
        }
        List<String> headers = new ArrayList<>();
        int dataStart;
        if (o.hasHeader()) {
            for (String h : recs.get(headerIndex)) {
                headers.add(h.trim());
            }
            dataStart = headerIndex + 1;
            if (headerIndex > 0) {
                b.info("CSV_ROWS_BEFORE_HEADER", source, headerIndex + " row(s) before the header row were skipped.",
                        "Check headerRow if this is not intended.");
            }
        } else {
            for (int i = 1; i <= recs.get(0).size(); i++) {
                headers.add("column" + i);
            }
            dataStart = 0;
        }
        List<List<String>> rows = recs.subList(dataStart, recs.size());
        List<Integer> rowNumbers = numbers.subList(dataStart, numbers.size());

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
                b.error("DICT_COLUMN_UNMAPPED", source, detail,
                        "Add a header such as 'Column' or 'Field', or set map.column to the right header.");
                return new Result(true, "dictionary columns not mapped");
            }
            Dictionary.apply(source, res.mapping(), rows, rowNumbers, null, b, where);
            if (truncated) {
                b.warn("DICT_TRUNCATED", source, "Only the first " + o.maxRows() + " rows were read (maxRows).",
                        "Increase maxRows to read the rest.");
            }
            return new Result(false, "dictionary: " + b.tableCount() + " table(s), " + b.termCount()
                    + " unassigned term(s), " + rows.size() + " row(s) read");
        }
        int headerNumber = numbers.get(o.hasHeader() ? headerIndex : 0);
        return dataDump(b, source, tableName, where, headers, rows, headerNumber, truncated, o);
    }

    private static Result dataDump(Builder b, String source, String tableName, String where, List<String> headers,
                                   List<List<String>> rows, int headerNumber, boolean truncated, IngestOptions o) {
        int n = headers.size();
        boolean[] anyValue = new boolean[n];
        boolean[] anyEmpty = new boolean[n];
        String[] type = new String[n];
        int ragged = 0;
        for (List<String> row : rows) {
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
        String headerLoc = where + headerNumber;
        for (int c = 0; c < n; c++) {
            String name = headers.get(c);
            if (name.isEmpty()) {
                name = "column" + (c + 1);
                b.warn("CSV_BLANK_HEADER", source + ":" + headerLoc, "Header " + (c + 1) + " is blank; it was named '" + name + "'.",
                        "Give the column a name in the source.");
            }
            columns.add(new RawColumn(name, anyValue[c] ? type[c] : null, anyValue[c], anyEmpty[c] ? Boolean.TRUE : null,
                    false, null, headerLoc));
        }
        Set<String> seen = new HashSet<>();
        for (RawColumn c : columns) {
            if (!seen.add(c.name().toLowerCase(Locale.ROOT))) {
                b.warn("CSV_DUPLICATE_HEADER", source + ":" + headerLoc, "Header '" + c.name() + "' appears more than once; both columns are kept.",
                        "Rename one of the columns in the source.");
            }
        }
        if (ragged > 0) {
            b.warn("CSV_RAGGED", source, ragged + " row(s) do not have " + n + " fields.",
                    "Check quoting and delimiters; missing cells are treated as empty.");
        }
        if (truncated) {
            b.info("SAMPLING_LIMIT", source, "Types were inferred from the first " + o.maxRows() + " data rows only (maxRows).",
                    "Increase maxRows to sample more rows.");
        }
        b.table(new RawTable(null, null, tableName, "TABLE", null, List.copyOf(columns), source, headerLoc));
        b.info("TYPES_INFERRED", source, "Column types are inferred from sampled values and are not authoritative. Row values were not retained.",
                "Confirm types against the real source.");
        return new Result(false, "data: table '" + tableName + "', " + n + " column(s), " + rows.size() + " row(s) sampled");
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

    /** File or sheet name without directories (either separator) and without its extension. */
    public static String baseName(String name) {
        String n = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}

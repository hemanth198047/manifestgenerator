package com.example.casc.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ingestion settings, identical for the CLI, REST API and web form.
 * delimiter 0 means "choose by file extension" (.tsv is tab, anything else comma).
 * maxRows is the sampling limit: data rows read per tabular source. sheet selects one spreadsheet sheet by name or by
 * 1-based number; empty means every visible sheet.
 */
public record IngestOptions(String dialect, char delimiter, boolean hasHeader, int headerRow, String mode,
                            int maxRows, Map<String, String> columnMap, int maxSourceChars, String sheet) {

    public static final Set<String> DIALECTS =
            Set.of("auto", "oracle", "db2", "postgresql", "sybase", "snowflake", "teradata", "sqlserver");
    public static final Set<String> MODES = Set.of("auto", "data", "dictionary");
    public static final List<String> MAP_FIELDS = List.of("table", "column", "description", "type", "schema");

    public IngestOptions {
        columnMap = Map.copyOf(columnMap);
    }

    public static IngestOptions defaults() {
        return new IngestOptions("auto", (char) 0, true, 1, "auto", 10_000, Map.of(), 10_000_000, "");
    }

    /**
     * Keys: dialect, delimiter (comma, tab, semicolon, pipe or one character), hasHeader, headerRow, mode,
     * maxRows, maxSourceChars, sheet, map.table, map.column, map.description, map.type, map.schema.
     * Unknown keys and bad values throw IllegalArgumentException with an actionable message.
     */
    public static IngestOptions fromMap(Map<String, String> in) {
        IngestOptions d = defaults();
        String dialect = d.dialect;
        char delimiter = d.delimiter;
        boolean hasHeader = d.hasHeader;
        int headerRow = d.headerRow;
        String mode = d.mode;
        int maxRows = d.maxRows;
        int maxChars = d.maxSourceChars;
        String sheet = d.sheet;
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() == null ? "" : e.getValue().trim();
            switch (k) {
                case "dialect" -> {
                    dialect = v.toLowerCase(java.util.Locale.ROOT);
                    if (!DIALECTS.contains(dialect)) {
                        throw new IllegalArgumentException("dialect must be one of " + new java.util.TreeSet<>(DIALECTS));
                    }
                }
                case "delimiter" -> delimiter = parseDelimiter(v);
                case "hasHeader" -> hasHeader = parseBool(k, v);
                case "headerRow" -> headerRow = parseInt(k, v, 1, 1000);
                case "mode" -> {
                    mode = v.toLowerCase(java.util.Locale.ROOT);
                    if (!MODES.contains(mode)) {
                        throw new IllegalArgumentException("mode must be one of auto, data, dictionary");
                    }
                }
                case "maxRows" -> maxRows = parseInt(k, v, 1, 1_000_000);
                case "maxSourceChars" -> maxChars = parseInt(k, v, 1000, 50_000_000);
                case "sheet" -> sheet = v;
                default -> {
                    if (k.startsWith("map.") && MAP_FIELDS.contains(k.substring(4))) {
                        if (v.isEmpty()) {
                            throw new IllegalArgumentException(k + " needs a header name");
                        }
                        map.put(k.substring(4), v);
                    } else {
                        throw new IllegalArgumentException("Unknown option '" + k + "'");
                    }
                }
            }
        }
        return new IngestOptions(dialect, delimiter, hasHeader, headerRow, mode, maxRows, map, maxChars, sheet);
    }

    private static char parseDelimiter(String v) {
        switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "comma": return ',';
            case "tab": return '\t';
            case "semicolon": return ';';
            case "pipe": return '|';
            default:
                if (v.length() == 1 && v.charAt(0) != '"' && v.charAt(0) != '\n' && v.charAt(0) != '\r') {
                    return v.charAt(0);
                }
                throw new IllegalArgumentException("delimiter must be comma, tab, semicolon, pipe or one character (not a quote or line break)");
        }
    }

    private static boolean parseBool(String k, String v) {
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(k + " must be true or false");
    }

    private static int parseInt(String k, String v, int min, int max) {
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new IllegalArgumentException(k + " must be a whole number between " + min + " and " + max);
    }
}

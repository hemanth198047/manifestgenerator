package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.RawMetadataBundle.RawTerm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps dictionary-style rows (from CSV, TSV or Markdown tables) onto tables and columns. */
public final class Dictionary {
    private Dictionary() {}

    private static final Map<String, Set<String>> SYNONYMS = Map.of(
            "table", Set.of("table", "tablename", "entity", "entityname"),
            "column", Set.of("column", "columnname", "field", "fieldname", "attribute", "attributename", "element", "elementname"),
            "description", Set.of("description", "definition", "comment", "desc", "meaning", "businessdescription"),
            "type", Set.of("type", "datatype", "columntype"),
            "schema", Set.of("schema", "schemaname"));

    public record Mapping(int table, int column, int description, int type, int schema) {
        public boolean usable() {
            return column >= 0;
        }

        public boolean looksLikeDictionary() {
            return column >= 0 && (description >= 0 || type >= 0);
        }
    }

    public record Resolved(Mapping mapping, List<String> problems) {}

    public static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static int find(List<String> headers, String field, Map<String, String> overrides, List<String> problems) {
        String override = overrides.get(field);
        if (override != null) {
            for (int i = 0; i < headers.size(); i++) {
                if (norm(headers.get(i)).equals(norm(override))) {
                    return i;
                }
            }
            problems.add("map." + field + "='" + override + "' does not match any header (headers: " + headers + ")");
            return -1;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (SYNONYMS.get(field).contains(norm(headers.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    public static Resolved resolve(List<String> headers, Map<String, String> overrides) {
        List<String> problems = new ArrayList<>();
        Mapping m = new Mapping(find(headers, "table", overrides, problems), find(headers, "column", overrides, problems),
                find(headers, "description", overrides, problems), find(headers, "type", overrides, problems),
                find(headers, "schema", overrides, problems));
        return new Resolved(m, problems);
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? "" : row.get(i).trim();
    }

    private static final class Acc {
        final String schema;
        final String name;
        final String location;
        final List<RawColumn> columns = new ArrayList<>();
        final Set<String> seen = new java.util.HashSet<>();

        Acc(String schema, String name, String location) {
            this.schema = schema;
            this.name = name;
            this.location = location;
        }
    }

    /**
     * Rows become columns of tables (grouped by schema and table, in first-seen order). Without a table column and
     * without defaultTable the rows become candidate terms. Blank rows are reported, never dropped silently.
     */
    public static void apply(String source, Mapping m, List<List<String>> rows, List<Integer> lines, String defaultTable,
                             RawMetadataBundle.Builder out) {
        apply(source, m, rows, lines, defaultTable, out, "line ");
    }

    /** where is the prefix of locations, for example "line " or "sheet 'Data', row ". */
    public static void apply(String source, Mapping m, List<List<String>> rows, List<Integer> lines, String defaultTable,
                             RawMetadataBundle.Builder out, String where) {
        Map<String, Acc> tables = new LinkedHashMap<>();
        boolean usedDefault = false;
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String loc = where + lines.get(r);
            String col = cell(row, m.column());
            if (col.isEmpty()) {
                out.warn("DICT_ROW_SKIPPED", source + ":" + loc, "Row has no value in the column-name field and was not used.",
                        "Fill in the column name or remove the row.");
                continue;
            }
            String desc = cell(row, m.description());
            String type = cell(row, m.type());
            String table = m.table() >= 0 ? cell(row, m.table()) : defaultTable;
            if (m.table() < 0 && defaultTable != null) {
                usedDefault = true;
            }
            if (table == null) {
                out.term(new RawTerm(col, desc.isEmpty() ? null : desc, source, loc));
                continue;
            }
            if (table.isEmpty()) {
                out.warn("DICT_ROW_SKIPPED", source + ":" + loc, "Row has no table name and was not used.",
                        "Fill in the table name or remove the row.");
                continue;
            }
            String schema = m.schema() >= 0 && !cell(row, m.schema()).isEmpty() ? cell(row, m.schema()) : null;
            int dot = table.indexOf('.');
            if (schema == null && dot > 0 && dot == table.lastIndexOf('.') && dot < table.length() - 1) {
                schema = table.substring(0, dot);
                table = table.substring(dot + 1);
            }
            final String tableName = table;
            final String schemaName = schema;
            String key = (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "|" + table.toLowerCase(Locale.ROOT);
            Acc acc = tables.computeIfAbsent(key, k -> new Acc(schemaName, tableName, loc));
            if (!acc.seen.add(col.toLowerCase(Locale.ROOT))) {
                out.warn("DICT_DUPLICATE_COLUMN", source + ":" + loc,
                        "Column '" + col + "' appears more than once for table '" + table + "'; both entries are kept.",
                        "Remove or rename the duplicate before generating a manifest.");
            }
            acc.columns.add(new RawColumn(col, type.isEmpty() ? null : type, false, null, false,
                    desc.isEmpty() ? null : desc, loc));
        }
        for (Acc a : tables.values()) {
            out.table(new RawTable(null, a.schema, a.name, "TABLE", null, List.copyOf(a.columns), source, a.location));
        }
        if (usedDefault) {
            out.info("TABLE_FROM_HEADING", source, "No table column was found; rows were assigned to the nearest preceding heading.",
                    "Check that each heading is really the table name.");
        }
    }
}

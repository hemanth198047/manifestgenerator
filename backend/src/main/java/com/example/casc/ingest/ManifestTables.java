package com.example.casc.ingest;

import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the table definitions (databases, schemas, tables, columns with their system data element) from the
 * "databases" section of a CasC manifest JSON. A manifest that is not strictly valid JSON (trailing or missing commas,
 * which hand-edited manifests often have) is still read: only the "databases" section is parsed, leniently, and a warning says so.
 */
public final class ManifestTables {
    private ManifestTables() {}

    /** description is null when the source has none (manifest JSON columns carry a system data element instead). */
    public record Column(String physicalName, String systemDataElement, String description) {}

    public record Table(String database, String schema, String name, String description, List<Column> columns) {}

    public record Result(List<Table> tables, List<Finding> findings) {}

    public static Result read(SourceInput in) {
        List<Finding> findings = new ArrayList<>();
        if (in.readError() != null || in.text() == null) {
            findings.add(Finding.error("MANIFEST_UNREADABLE", in.name(), in.readError() == null ? "The manifest has no text." : in.readError(),
                    "Fix the file and add it again."));
            return new Result(List.of(), findings);
        }
        Object databases;
        try {
            Object root = Json.parse(in.text());
            if (!(root instanceof Map<?, ?> m)) {
                findings.add(Finding.error("MANIFEST_NOT_OBJECT", in.name(), "The manifest must be a JSON object.", "Check the file."));
                return new Result(List.of(), findings);
            }
            databases = m.get("databases");
        } catch (JsonException strict) {
            Object lenient = Lenient.databases(in.text());
            if (lenient == Lenient.MISSING) {
                findings.add(Finding.error("MANIFEST_SYNTAX", in.name(), "The manifest is not valid JSON and its \"databases\" section could not be located: "
                        + strict.getMessage(), "Fix the JSON syntax."));
                return new Result(List.of(), findings);
            }
            if (lenient instanceof Lenient.Failure f) {
                findings.add(Finding.error("MANIFEST_SYNTAX", in.name(), "The \"databases\" section is not valid JSON: " + f.message(), "Fix the JSON syntax in that section."));
                return new Result(List.of(), findings);
            }
            databases = lenient;
            findings.add(Finding.warning("MANIFEST_LENIENT_READ", in.name(), "The manifest is not strictly valid JSON (" + strict.getMessage()
                    + "). Only its \"databases\" section was read, ignoring trailing and missing commas.", "Fix the syntax errors in the manifest."));
        }
        if (!(databases instanceof List<?> dbs) || dbs.isEmpty()) {
            findings.add(Finding.error("MANIFEST_NO_DATABASES", in.name(), "The manifest has no \"databases\" section with tables.",
                    "Add databases, schemas and tables to the manifest."));
            return new Result(List.of(), findings);
        }
        List<Table> out = new ArrayList<>();
        int noName = 0;
        String firstNoName = null;
        int dbIdx = -1;
        for (Object d : dbs) {
            dbIdx++;
            if (!(d instanceof Map<?, ?> db)) {
                continue;
            }
            String dbName = text(db.get("logicalName"));
            int scIdx = -1;
            for (Object s : list(db.get("schemas"))) {
                scIdx++;
                if (!(s instanceof Map<?, ?> sc)) {
                    continue;
                }
                String schema = text(sc.get("physicalName"));
                int tIdx = -1;
                for (Object t : list(sc.get("tables"))) {
                    tIdx++;
                    if (!(t instanceof Map<?, ?> tb) || text(tb.get("physicalName")) == null) {
                        noName++;
                        if (firstNoName == null) {
                            firstNoName = "databases[" + dbIdx + "].schemas[" + scIdx + "].tables[" + tIdx + "]";
                        }
                        continue;
                    }
                    List<Column> cols = new ArrayList<>();
                    for (Object c : list(tb.get("columns"))) {
                        if (c instanceof Map<?, ?> cm && text(cm.get("physicalName")) != null) {
                            cols.add(new Column(text(cm.get("physicalName")), element(cm.get("systemDataElement")), null));
                        }
                    }
                    out.add(new Table(dbName, schema, text(tb.get("physicalName")), text(tb.get("description")), cols));
                }
            }
        }
        if (noName > 0) {
            findings.add(Finding.warning("MANIFEST_TABLE_SKIPPED", in.name() + ":" + firstNoName, noName + " table entr" + (noName == 1 ? "y has" : "ies have")
                    + " no physicalName and were skipped (the first is at " + firstNoName + "). A table is only read when it has a physicalName.",
                    "Add physicalName to those table entries (the tool reads databases[].schemas[].tables[].physicalName)."));
        }
        if (out.isEmpty()) {
            findings.add(Finding.error("MANIFEST_NO_TABLES", in.name(), "The \"databases\" section contains no tables.", "Add tables under databases[].schemas[].tables[]."));
        }
        return new Result(out, findings);
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /** systemDataElement is a name, or an object with a name. */
    private static String element(Object o) {
        if (o instanceof Map<?, ?> m) {
            return text(m.get("name"));
        }
        return text(o);
    }

    /** Tolerant reader for the "databases" value: accepts trailing commas and missing commas between members. */
    static final class Lenient {
        static final Object MISSING = new Object();

        record Failure(String message) {}

        static Object databases(String text) {
            String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
            int start = findTopLevelKey(t, "databases");
            if (start < 0) {
                return MISSING;
            }
            try {
                return com.example.casc.json.LenientJson.parseAt(t, start);
            } catch (IllegalArgumentException e) {
                return new Failure(e.getMessage());
            }
        }

        /** Index of the first character of the value of a depth-1 key, or -1. String aware; counts braces and brackets only. */
        private static int findTopLevelKey(String t, String key) {
            int depth = 0;
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                if (c == '"') {
                    int end = k + 1;
                    StringBuilder b = new StringBuilder();
                    while (end < t.length() && t.charAt(end) != '"') {
                        if (t.charAt(end) == '\\' && end + 1 < t.length()) {
                            b.append(t.charAt(end + 1));
                            end += 2;
                        } else if (t.charAt(end) == '\n') {
                            break;     // an unterminated string ends at the line end
                        } else {
                            b.append(t.charAt(end++));
                        }
                    }
                    if (end < t.length() && t.charAt(end) == '"') {
                        int after = end + 1;
                        while (after < t.length() && Character.isWhitespace(t.charAt(after))) {
                            after++;
                        }
                        if (depth == 1 && after < t.length() && t.charAt(after) == ':' && b.toString().equals(key)) {
                            int v = after + 1;
                            while (v < t.length() && Character.isWhitespace(t.charAt(v))) {
                                v++;
                            }
                            return v;
                        }
                        k = end;
                    } else {
                        k = end;
                    }
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
            }
            return -1;
        }
    }
}

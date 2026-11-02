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

    public record Column(String physicalName, String systemDataElement) {}

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
        for (Object d : dbs) {
            if (!(d instanceof Map<?, ?> db)) {
                continue;
            }
            String dbName = text(db.get("logicalName"));
            for (Object s : list(db.get("schemas"))) {
                if (!(s instanceof Map<?, ?> sc)) {
                    continue;
                }
                String schema = text(sc.get("physicalName"));
                for (Object t : list(sc.get("tables"))) {
                    if (!(t instanceof Map<?, ?> tb) || text(tb.get("physicalName")) == null) {
                        continue;
                    }
                    List<Column> cols = new ArrayList<>();
                    for (Object c : list(tb.get("columns"))) {
                        if (c instanceof Map<?, ?> cm && text(cm.get("physicalName")) != null) {
                            cols.add(new Column(text(cm.get("physicalName")), element(cm.get("systemDataElement"))));
                        }
                    }
                    out.add(new Table(dbName, schema, text(tb.get("physicalName")), text(tb.get("description")), cols));
                }
            }
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

        private final String s;
        private int i;

        private Lenient(String s, int start) {
            this.s = s;
            this.i = start;
        }

        static Object databases(String text) {
            String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
            int start = findTopLevelKey(t, "databases");
            if (start < 0) {
                return MISSING;
            }
            try {
                Lenient p = new Lenient(t, start);
                return p.value(0);
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

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value(int depth) {
            if (depth > 64) {
                throw new IllegalArgumentException("nesting is too deep");
            }
            ws();
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of text");
            }
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    ws();
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("unterminated object");
                    }
                    if (s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (s.charAt(i) != '"') {
                        throw new IllegalArgumentException("expected a property name at offset " + i);
                    }
                    String k = string();
                    ws();
                    if (i >= s.length() || s.charAt(i) != ':') {
                        throw new IllegalArgumentException("expected ':' after \"" + k + "\"");
                    }
                    i++;
                    m.put(k, value(depth + 1));
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                while (true) {
                    ws();
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("unterminated array");
                    }
                    if (s.charAt(i) == ']') {
                        i++;
                        return l;
                    }
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    l.add(value(depth + 1));
                }
            }
            if (c == '"') {
                return string();
            }
            int st = i;
            while (i < s.length() && ",]}".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String w = s.substring(st, i);
            switch (w) {
                case "true": return Boolean.TRUE;
                case "false": return Boolean.FALSE;
                case "null": return null;
                default:
                    try {
                        return Long.parseLong(w);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("unexpected '" + w + "' at offset " + st);
                    }
            }
        }

        private String string() {
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }
    }
}

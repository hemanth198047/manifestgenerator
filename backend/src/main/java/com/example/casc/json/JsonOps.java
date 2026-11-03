package com.example.casc.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Operations on a parsed JSON tree: set, delete, merge, sort, flatten, statistics, CSV and text search. */
public final class JsonOps {
    private JsonOps() {}

    public static String type(Object v) {
        if (v == null) {
            return "null";
        } else if (v instanceof Map<?, ?>) {
            return "object";
        } else if (v instanceof List<?>) {
            return "array";
        } else if (v instanceof String) {
            return "string";
        } else if (v instanceof Boolean) {
            return "boolean";
        }
        return "number";
    }

    /** "object with 6 keys", "array of 694 items", "string". */
    public static String describe(Object v) {
        if (v instanceof Map<?, ?> m) {
            return "object with " + m.size() + (m.size() == 1 ? " key" : " keys");
        } else if (v instanceof List<?> l) {
            return "array of " + l.size() + (l.size() == 1 ? " item" : " items");
        }
        return type(v);
    }

    // ------------------------------------------------------------------ set / delete

    /**
     * Sets the value at every place the path selects; a missing key on an object is created. Returns the new root and the number of changes.
     * An empty path or "$" replaces the whole document.
     */
    public static Change set(Object root, String path, Object value) {
        List<JsonPath.Seg> segs = JsonPath.parse(path);
        if (segs.isEmpty()) {
            return new Change(value, 1);
        }
        int[] count = {0};
        apply(root, segs, 0, true, value, count);
        return new Change(root, count[0]);
    }

    public static Change delete(Object root, String path) {
        List<JsonPath.Seg> segs = JsonPath.parse(path);
        if (segs.isEmpty()) {
            throw new IllegalArgumentException("Delete needs a path that is not the whole document.");
        }
        int[] count = {0};
        apply(root, segs, 0, false, null, count);
        return new Change(root, count[0]);
    }

    public record Change(Object root, int changes) {}

    @SuppressWarnings("unchecked")
    private static void apply(Object node, List<JsonPath.Seg> segs, int i, boolean set, Object value, int[] count) {
        JsonPath.Seg seg = segs.get(i);
        boolean last = i == segs.size() - 1;
        if (!last) {
            // descend into everything this segment selects
            for (JsonPath.Match m : JsonPath.select(node, render(seg))) {
                if (seg.kind() == JsonPath.Kind.KEY && set && node instanceof Map<?, ?> mp && !mp.containsKey(seg.text())) {
                    continue;
                }
                apply(m.value(), segs, i + 1, set, value, count);
            }
            if (set && seg.kind() == JsonPath.Kind.KEY && node instanceof Map<?, ?> mp && !mp.containsKey(seg.text())) {
                Map<String, Object> created = new LinkedHashMap<>();
                ((Map<String, Object>) node).put(seg.text(), created);
                apply(created, segs, i + 1, set, value, count);
            }
            return;
        }
        switch (seg.kind()) {
            case KEY -> {
                if (node instanceof Map<?, ?> mp) {
                    Map<String, Object> m = (Map<String, Object>) mp;
                    if (set) {
                        m.put(seg.text(), value);
                        count[0]++;
                    } else if (m.containsKey(seg.text())) {
                        m.remove(seg.text());
                        count[0]++;
                    }
                }
            }
            case INDEX -> {
                if (node instanceof List<?> l) {
                    List<Object> list = (List<Object>) l;
                    int idx = seg.a() < 0 ? list.size() + seg.a() : seg.a();
                    if (idx >= 0 && idx < list.size()) {
                        if (set) {
                            list.set(idx, value);
                        } else {
                            list.remove(idx);
                        }
                        count[0]++;
                    } else if (set && idx == list.size()) {
                        list.add(value);
                        count[0]++;
                    }
                }
            }
            case WILD, SLICE, FILTER, DEEP -> {
                List<JsonPath.Match> hits = JsonPath.select(node, render(seg));
                if (seg.kind() == JsonPath.Kind.DEEP) {
                    deepApply(node, seg.text(), set, value, count);
                } else if (node instanceof Map<?, ?> mp) {
                    Map<String, Object> m = (Map<String, Object>) mp;
                    for (JsonPath.Match h : hits) {
                        String key = lastKey(h.path());
                        if (set) {
                            m.put(key, value);
                        } else {
                            m.remove(key);
                        }
                        count[0]++;
                    }
                } else if (node instanceof List<?> l) {
                    List<Object> list = (List<Object>) l;
                    List<Integer> idxs = new ArrayList<>();
                    for (JsonPath.Match h : hits) {
                        idxs.add(lastIndex(h.path()));
                    }
                    for (int k = idxs.size() - 1; k >= 0; k--) {
                        if (set) {
                            list.set(idxs.get(k), value);
                        } else {
                            list.remove((int) idxs.get(k));
                        }
                        count[0]++;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepApply(Object node, String key, boolean set, Object value, int[] count) {
        if (node instanceof Map<?, ?> mp) {
            Map<String, Object> m = (Map<String, Object>) mp;
            for (String k : new ArrayList<>(m.keySet())) {
                if (k.equals(key)) {
                    if (set) {
                        m.put(k, value);
                    } else {
                        m.remove(k);
                    }
                    count[0]++;
                } else {
                    deepApply(m.get(k), key, set, value, count);
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                deepApply(o, key, set, value, count);
            }
        }
    }

    private static String render(JsonPath.Seg seg) {
        return switch (seg.kind()) {
            case KEY -> "[\"" + seg.text().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
            case INDEX -> "[" + seg.a() + "]";
            case WILD -> "[*]";
            case SLICE -> "[" + (seg.hasA() ? seg.a() : "") + ":" + (seg.hasB() ? seg.b() : "") + "]";
            case FILTER -> "[?" + seg.field() + seg.op() + seg.value() + "]";
            case DEEP -> ".." + seg.text();
        };
    }

    private static String lastKey(String path) {
        if (path.endsWith("\"]")) {
            int at = path.lastIndexOf("[\"");
            return path.substring(at + 2, path.length() - 2).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static int lastIndex(String path) {
        return Integer.parseInt(path.substring(path.lastIndexOf('[') + 1, path.length() - 1));
    }

    // ------------------------------------------------------------------ merge / sort

    /** Deep merge: objects are merged key by key, everything else (including arrays) is replaced by the later value. */
    @SuppressWarnings("unchecked")
    public static Object merge(Object a, Object b) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) ma);
            for (Map.Entry<String, Object> e : ((Map<String, Object>) mb).entrySet()) {
                out.put(e.getKey(), out.containsKey(e.getKey()) ? merge(out.get(e.getKey()), e.getValue()) : e.getValue());
            }
            return out;
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    public static Object sortKeys(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            new java.util.TreeMap<>((Map<String, Object>) m).forEach((k, x) -> out.put(k, sortKeys(x)));
            return out;
        } else if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(sortKeys(o));
            }
            return out;
        }
        return v;
    }

    // ------------------------------------------------------------------ flatten / stats / find / csv

    /** Every leaf (scalar, empty object, empty array) with its path. */
    public static List<JsonPath.Match> flatten(Object root, String basePath) {
        List<JsonPath.Match> out = new ArrayList<>();
        flatten(basePath == null ? "$" : basePath, root, out);
        return out;
    }

    private static void flatten(String path, Object v, List<JsonPath.Match> out) {
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(JsonPath.child(path, String.valueOf(e.getKey())), e.getValue(), out);
            }
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            for (int i = 0; i < l.size(); i++) {
                flatten(path + "[" + i + "]", l.get(i), out);
            }
        } else {
            out.add(new JsonPath.Match(path, v));
        }
    }

    public record Stats(int nodes, int objects, int arrays, int strings, int numbers, int booleans, int nulls, int maxDepth) {}

    public static Stats stats(Object root) {
        int[] c = new int[8];
        walk(root, 1, c);
        return new Stats(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    private static void walk(Object v, int depth, int[] c) {
        c[0]++;
        c[7] = Math.max(c[7], depth);
        if (v instanceof Map<?, ?> m) {
            c[1]++;
            for (Object x : m.values()) {
                walk(x, depth + 1, c);
            }
        } else if (v instanceof List<?> l) {
            c[2]++;
            for (Object x : l) {
                walk(x, depth + 1, c);
            }
        } else if (v instanceof String) {
            c[3]++;
        } else if (v instanceof Boolean) {
            c[5]++;
        } else if (v == null) {
            c[6]++;
        } else {
            c[4]++;
        }
    }

    public record Found(String path, String where, String text) {}

    /** Keys and scalar values that contain the text (case ignored). */
    public static List<Found> find(Object root, String text, int max) {
        List<Found> out = new ArrayList<>();
        find("$", root, text.toLowerCase(Locale.ROOT), out, max);
        return out;
    }

    private static void find(String path, Object v, String t, List<Found> out, int max) {
        if (out.size() >= max) {
            return;
        }
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                String p = JsonPath.child(path, key);
                if (key.toLowerCase(Locale.ROOT).contains(t)) {
                    out.add(new Found(p, "key", key));
                }
                find(p, e.getValue(), t, out, max);
            }
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                find(path + "[" + i + "]", l.get(i), t, out, max);
            }
        } else if (v != null) {
            String s = v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
            if (s.toLowerCase(Locale.ROOT).contains(t)) {
                out.add(new Found(path, "value", s));
            }
        }
    }

    /** An array of objects as CSV: the columns are all keys in first-seen order; nested values are written as compact JSON. */
    public static String csv(Object arrayOfObjects) {
        if (!(arrayOfObjects instanceof List<?> rows)) {
            throw new IllegalArgumentException("CSV needs an array of objects, but the selected value is " + describe(arrayOfObjects)
                    + ". Select the array with --path, for example --path databases[0].schemas[0].tables.");
        }
        Set<String> cols = new LinkedHashSet<>();
        for (Object r : rows) {
            if (!(r instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("CSV needs an array of objects, but the array contains " + describe(r) + ".");
            }
            for (Object k : m.keySet()) {
                cols.add(String.valueOf(k));
            }
        }
        StringBuilder b = new StringBuilder();
        b.append(String.join(",", cols.stream().map(JsonOps::csvCell).toList())).append("\n");
        for (Object r : rows) {
            Map<?, ?> m = (Map<?, ?>) r;
            List<String> cells = new ArrayList<>();
            for (String c : cols) {
                Object v = m.get(c);
                cells.add(csvCell(v == null ? "" : v instanceof Map<?, ?> || v instanceof List<?> ? Json.writeCompact(v)
                        : v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v)));
            }
            b.append(String.join(",", cells)).append("\n");
        }
        return b.toString();
    }

    private static String csvCell(String s) {
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}

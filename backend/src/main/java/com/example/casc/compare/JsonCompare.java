package com.example.casc.compare;

import com.example.casc.json.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares several JSON documents with each other. Every document is reduced to its leaf values, each under a stable path;
 * arrays of objects that have an identifying field (name, physicalName, id, ... or the fields you name) are matched by that field instead of
 * by position, so the same table is compared with the same table even when the order differs.
 * The first document is the baseline; every row says how the others differ from it.
 */
public final class JsonCompare {
    private JsonCompare() {}

    /** Stands for "this path does not exist in this document". */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "(missing)";
        }
    };

    public static final List<String> DEFAULT_KEYS = List.of("name", "physicalName", "id", "logicalName", "key");

    public record Source(String name, Object root, long bytes) {}

    /** status: ADDED (not in the baseline), REMOVED (in the baseline, missing elsewhere), CHANGED (a different value), SAME. */
    public record Row(String path, List<Object> values, String status) {}

    public record FileSummary(String name, long bytes, int values, int added, int removed, int changed, int same) {}

    public record SectionInfo(String name, int[] valuesPerFile, int differingRows) {}

    public record Report(List<Source> sources, List<FileSummary> files, List<Row> rows, List<SectionInfo> sections, int totalPaths, int identical,
                         int differing, boolean truncated, List<String> keyFields, String section) {}

    public static Report compare(List<Source> sources, List<String> keyFields, int maxRows, String section) {
        if (sources.size() < 2) {
            throw new IllegalArgumentException("Comparing needs at least two documents.");
        }
        List<String> keys = keyFields == null || keyFields.isEmpty() ? DEFAULT_KEYS : keyFields;
        List<Map<String, Object>> leaves = new ArrayList<>();
        Set<String> union = new LinkedHashSet<>();
        for (Source s : sources) {
            Map<String, Object> m = new LinkedHashMap<>();
            walk("$", s.root(), keys, m);
            leaves.add(m);
            union.addAll(m.keySet());
        }
        List<Row> differing = new ArrayList<>();
        int identical = 0;
        int totalDiff = 0;
        int[] added = new int[sources.size()];
        int[] removed = new int[sources.size()];
        int[] changed = new int[sources.size()];
        int[] same = new int[sources.size()];
        Map<String, int[]> perSection = new LinkedHashMap<>();
        Map<String, Integer> sectionDiff = new LinkedHashMap<>();
        for (int j = 0; j < sources.size(); j++) {
            for (String p : leaves.get(j).keySet()) {
                perSection.computeIfAbsent(sectionOf(p), k -> new int[sources.size()])[j]++;
            }
        }
        for (String p : union) {
            List<Object> vals = new ArrayList<>();
            for (Map<String, Object> m : leaves) {
                vals.add(m.containsKey(p) ? m.get(p) : MISSING);
            }
            Object base = vals.get(0);
            boolean anyDiff = false;
            boolean baseMissing = base == MISSING;
            boolean anyChanged = false;
            boolean anyMissingElsewhere = false;
            for (int j = 1; j < vals.size(); j++) {
                Object v = vals.get(j);
                if (baseMissing && v != MISSING) {
                    added[j]++;
                    anyDiff = true;
                } else if (!baseMissing && v == MISSING) {
                    removed[j]++;
                    anyDiff = true;
                    anyMissingElsewhere = true;
                } else if (!baseMissing && !sameValue(base, v)) {
                    changed[j]++;
                    anyDiff = true;
                    anyChanged = true;
                } else if (!baseMissing) {
                    same[j]++;
                }
            }
            if (!anyDiff) {
                identical++;
                continue;
            }
            totalDiff++;
            sectionDiff.merge(sectionOf(p), 1, Integer::sum);
            if (differing.size() < maxRows) {
                String status = baseMissing ? "ADDED" : anyChanged ? "CHANGED" : anyMissingElsewhere ? "REMOVED" : "CHANGED";
                differing.add(new Row(p, vals, status));
            }
        }
        List<FileSummary> files = new ArrayList<>();
        for (int j = 0; j < sources.size(); j++) {
            files.add(new FileSummary(sources.get(j).name(), sources.get(j).bytes(), leaves.get(j).size(), added[j], removed[j], changed[j], same[j]));
        }
        List<SectionInfo> sections = new ArrayList<>();
        for (Map.Entry<String, int[]> e : perSection.entrySet()) {
            sections.add(new SectionInfo(e.getKey(), e.getValue(), sectionDiff.getOrDefault(e.getKey(), 0)));
        }
        return new Report(sources, files, differing, sections, union.size(), identical, totalDiff, totalDiff > differing.size(), keys, section);
    }

    /** The first path segment below the root: "databases" for $.databases[0].x, or "(document)" for a document that is not an object. */
    static String sectionOf(String path) {
        if (path.equals("$")) {
            return "(document)";
        }
        String rest = path.substring(2);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '.' || c == '[') {
                cut = i;
                break;
            }
        }
        if (cut == 0 && rest.startsWith("[\"")) {
            int end = rest.indexOf("\"]");
            return end > 0 ? rest.substring(2, end) : rest;
        }
        return rest.substring(0, cut);
    }

    private static void walk(String path, Object v, List<String> keys, Map<String, Object> out) {
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                walk(JsonPath.child(path, String.valueOf(e.getKey())), e.getValue(), keys, out);
            }
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            String id = identity(l, keys);
            for (int i = 0; i < l.size(); i++) {
                String seg = id == null ? "[" + i + "]" : "[" + id + "=" + scalar(((Map<?, ?>) l.get(i)).get(id)) + "]";
                walk(path + seg, l.get(i), keys, out);
            }
        } else {
            out.put(path, v);
        }
    }

    /** The first candidate field that every item has as a distinct, non-empty scalar, or null. */
    private static String identity(List<?> l, List<String> keys) {
        for (String k : keys) {
            Set<String> seen = new LinkedHashSet<>();
            boolean ok = true;
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m) || !m.containsKey(k) || m.get(k) == null || m.get(k) instanceof Map<?, ?> || m.get(k) instanceof List<?>
                        || scalar(m.get(k)).isBlank() || !seen.add(scalar(m.get(k)))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return k;
            }
        }
        return null;
    }

    private static String scalar(Object o) {
        return o instanceof BigDecimal b ? b.toPlainString() : String.valueOf(o);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return Objects.equals(a, b);
    }
}

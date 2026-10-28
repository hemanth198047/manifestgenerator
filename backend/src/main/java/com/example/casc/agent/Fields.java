package com.example.casc.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict typed access to parsed JSON for the agent contracts. Every failure names the path. */
final class Fields {
    private Fields() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String path) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(path + ": expected an object");
    }

    static List<?> arr(Object o, String path) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException(path + ": expected an array");
    }

    static void only(Map<String, Object> m, String path, Set<String> allowed) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(path + "." + k + ": unknown field (allowed: " + new java.util.TreeSet<>(allowed) + ")");
            }
        }
    }

    static String nullable(Map<String, Object> m, String k, String path) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(path + "." + k + ": expected a string");
    }

    static String opt(Map<String, Object> m, String k, String path) {
        String s = nullable(m, k, path);
        return s == null ? "" : s;
    }

    static String req(Map<String, Object> m, String k, String path) {
        String s = nullable(m, k, path);
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(path + "." + k + ": required");
        }
        return s;
    }

    static boolean bool(Map<String, Object> m, String k, String path, boolean fallback) {
        Boolean b = boolOrNull(m, k, path);
        return b == null ? fallback : b;
    }

    static Boolean boolOrNull(Map<String, Object> m, String k, String path) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(path + "." + k + ": expected true or false");
    }

    static List<String> strings(Object o, String path) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (Object x : arr(o, path)) {
            if (!(x instanceof String s)) {
                throw new IllegalArgumentException(path + "[" + i + "]: expected a string");
            }
            out.add(s);
            i++;
        }
        return out;
    }
}

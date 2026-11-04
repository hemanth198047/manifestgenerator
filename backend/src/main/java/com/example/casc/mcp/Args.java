package com.example.casc.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The arguments of one tool call. Unknown names are rejected (a typo or an injected option never goes unnoticed); every text value is checked before it is used. */
public final class Args {
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");
    private final Map<String, Object> map;

    private Args(Map<String, Object> map) {
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    public static Args of(Object raw, Set<String> allowed) throws ToolError {
        if (raw == null) {
            return new Args(Map.of());
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ToolError("The arguments must be a JSON object.");
        }
        for (Object k : m.keySet()) {
            if (!allowed.contains(String.valueOf(k))) {
                throw new ToolError("Unknown argument '" + k + "'. Allowed: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
        }
        return new Args((Map<String, Object>) m);
    }

    public boolean has(String key) {
        Object v = map.get(key);
        return v != null && !(v instanceof String s && s.isBlank()) && !(v instanceof List<?> l && l.isEmpty());
    }

    /** Optional text. Values that start with '-' are refused so that a value can never be taken for an option. */
    public String str(String key) throws ToolError {
        return str(key, false);
    }

    public String str(String key, boolean allowDash) throws ToolError {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new ToolError("'" + key + "' must be text.");
        }
        if (s.isBlank()) {
            return null;
        }
        if (CONTROL.matcher(s).find()) {
            throw new ToolError("'" + key + "' contains a control character.");
        }
        if (!allowDash && s.startsWith("-")) {
            throw new ToolError("'" + key + "' must not start with '-'.");
        }
        if (s.length() > 4000) {
            throw new ToolError("'" + key + "' is longer than 4000 characters.");
        }
        return s;
    }

    public String req(String key) throws ToolError {
        String v = str(key);
        if (v == null) {
            throw new ToolError("'" + key + "' is required.");
        }
        return v;
    }

    /** Text, or an array of text. */
    public List<String> list(String key) throws ToolError {
        Object v = map.get(key);
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof String s) {
            if (!s.isBlank()) {
                out.add(checked(key, s));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof String s)) {
                    throw new ToolError("'" + key + "' must be a list of text values.");
                }
                if (!s.isBlank()) {
                    out.add(checked(key, s));
                }
            }
            return out;
        }
        throw new ToolError("'" + key + "' must be text or a list of text values.");
    }

    private static String checked(String key, String s) throws ToolError {
        if (CONTROL.matcher(s).find() || s.startsWith("-")) {
            throw new ToolError("'" + key + "' has a value that starts with '-' or contains a control character.");
        }
        return s;
    }

    public boolean bool(String key) throws ToolError {
        Object v = map.get(key);
        if (v == null) {
            return false;
        }
        if (!(v instanceof Boolean b)) {
            throw new ToolError("'" + key + "' must be true or false.");
        }
        return b;
    }

    public Integer integer(String key, int min, int max) throws ToolError {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        long n;
        if (v instanceof Long l) {
            n = l;
        } else if (v instanceof BigDecimal b && b.stripTrailingZeros().scale() <= 0) {
            n = b.longValueExact();
        } else {
            throw new ToolError("'" + key + "' must be a whole number.");
        }
        if (n < min || n > max) {
            throw new ToolError("'" + key + "' must be between " + min + " and " + max + ".");
        }
        return (int) n;
    }

    /** One of the allowed words. */
    public String choice(String key, Set<String> allowed, boolean required) throws ToolError {
        String v = str(key);
        if (v == null) {
            if (required) {
                throw new ToolError("'" + key + "' is required: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
            return null;
        }
        if (!allowed.contains(v)) {
            throw new ToolError("'" + key + "' must be one of: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
        }
        return v;
    }

    /** The arguments without the confirmation fields, as a stable map (for the plan hash). */
    public Map<String, Object> withoutConfirmation() {
        Map<String, Object> m = new java.util.TreeMap<>(map);
        m.remove("confirm");
        m.remove("planHash");
        return m;
    }
}

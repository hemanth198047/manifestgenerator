package com.example.casc.output;

import com.example.casc.domain.CasCManifest;
import com.example.casc.manifest.ManifestMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Narrow YAML emitter for the manifest tree only. It is NOT a general YAML library and cannot parse YAML.
 * Supported values: Map, List, String, Boolean, integral and decimal numbers, null, empty {} and [].
 * Strings are always double-quoted, so values such as Yes, No, true or 123 are never read as other types.
 * Escapes: \" \\ \n \r \t, and \\uXXXX for other control characters, DEL, U+0085, U+2028, U+2029, U+FEFF.
 * Keys are plain when they match [A-Za-z_][A-Za-z0-9_]* and are not YAML 1.1 boolean/null words; otherwise quoted.
 * Indentation is two spaces; sequence dashes are indented under their key; line endings are LF.
 */
public final class YamlManifestSerializer implements ManifestSerializer {
    private static final Set<String> RESERVED = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~");

    @Override public String format() { return "yaml"; }

    @Override public String fileExtension() { return "yaml"; }

    @Override public String contentType() { return "application/yaml; charset=UTF-8"; }

    @Override
    public String serialize(CasCManifest manifest) {
        return emit(ManifestMapper.toTree(manifest));
    }

    public static String emit(Map<String, Object> root) {
        if (root.isEmpty()) {
            return "{}\n";
        }
        StringBuilder b = new StringBuilder();
        map(b, root, 0);
        return b.toString();
    }

    private static void pad(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) {
            b.append(' ');
        }
    }

    private static void map(StringBuilder b, Map<?, ?> m, int indent) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            pad(b, indent);
            b.append(key(String.valueOf(e.getKey()))).append(':');
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub && !sub.isEmpty()) {
                b.append('\n');
                map(b, sub, indent + 2);
            } else if (v instanceof List<?> l && !l.isEmpty()) {
                b.append('\n');
                list(b, l, indent + 2);
            } else {
                b.append(' ').append(scalar(v)).append('\n');
            }
        }
    }

    private static void list(StringBuilder b, List<?> l, int indent) {
        for (Object item : l) {
            pad(b, indent);
            b.append('-');
            if (item instanceof Map<?, ?> sub && !sub.isEmpty()) {
                StringBuilder inner = new StringBuilder();
                map(inner, sub, indent + 2);
                b.append(' ').append(inner, indent + 2, inner.length());
            } else if (item instanceof List<?> sub && !sub.isEmpty()) {
                b.append('\n');
                list(b, sub, indent + 2);
            } else {
                b.append(' ').append(scalar(item)).append('\n');
            }
        }
    }

    static String key(String k) {
        if (k.matches("[A-Za-z_][A-Za-z0-9_]*") && !RESERVED.contains(k.toLowerCase(Locale.ROOT))) {
            return k;
        }
        return quote(k);
    }

    static String scalar(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer
                || v instanceof Short || v instanceof Byte) {
            return v.toString();
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("NaN and Infinity are not supported");
            }
            return n.toString();
        }
        if (v instanceof Map<?, ?> m && m.isEmpty()) {
            return "{}";
        }
        if (v instanceof List<?> l && l.isEmpty()) {
            return "[]";
        }
        throw new IllegalArgumentException("Unsupported YAML value type: " + v.getClass().getName());
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029 || c == 0xFEFF) {
                        b.append(String.format("\\u%04X", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}

package com.example.casc.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 JSON reader and writer.
 * Reads into Map (insertion ordered), List, String, Boolean, Long, BigDecimal or null.
 * Rejects duplicate keys, trailing content, control characters in strings and nesting deeper than 64.
 * Writes with two-space indentation and LF line endings regardless of platform.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    // ---------------------------------------------------------------- reading

    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("Input is null");
        }
        String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
        Json p = new Json(t);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i < t.length()) {
            throw p.err("Unexpected content after the JSON value");
        }
        return v;
    }

    private JsonException err(String message) {
        int line = 1;
        int col = 1;
        for (int k = 0; k < i && k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException(message + " at line " + line + ", column " + col);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) throws JsonException {
        if (!peek(c)) {
            throw err("Expected '" + c + "'");
        }
        i++;
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw err("Nesting is deeper than " + MAX_DEPTH);
        }
        if (i >= s.length()) {
            throw err("Unexpected end of input");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw err("Unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object result) throws JsonException {
        if (!s.startsWith(word, i)) {
            throw err("Invalid literal");
        }
        i += word.length();
        return result;
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw err("Expected a string key");
            }
            String k = string();
            ws();
            expect(':');
            ws();
            Object v = value(depth + 1);
            if (m.containsKey(k)) {
                throw err("Duplicate key \"" + k + "\"");
            }
            m.put(k, v);
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            if (peek('}')) {
                i++;
                return m;
            }
            throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            if (peek(']')) {
                i++;
                return l;
            }
            throw err("Expected ',' or ']'");
        }
    }

    private String string() throws JsonException {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw err("Unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw err("Unescaped control character in string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw err("Unterminated escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw err("Invalid \\u escape");
                    }
                    int cp = 0;
                    for (int k = 0; k < 4; k++) {
                        int d = Character.digit(s.charAt(i + k), 16);
                        if (d < 0) {
                            throw err("Invalid \\u escape");
                        }
                        cp = cp * 16 + d;
                    }
                    i += 4;
                    b.append((char) cp);
                    break;
                default:
                    throw err("Invalid escape \\" + e);
            }
        }
    }

    private boolean digit() {
        return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    private Object number() throws JsonException {
        int start = i;
        if (peek('-')) {
            i++;
        }
        if (peek('0')) {
            i++;
        } else if (digit()) {
            while (digit()) {
                i++;
            }
        } else {
            throw err("Invalid number");
        }
        boolean integer = true;
        if (peek('.')) {
            integer = false;
            i++;
            int d = i;
            while (digit()) {
                i++;
            }
            if (i == d) {
                throw err("Invalid number");
            }
        }
        if (peek('e') || peek('E')) {
            integer = false;
            i++;
            if (peek('+') || peek('-')) {
                i++;
            }
            int d = i;
            while (digit()) {
                i++;
            }
            if (i == d) {
                throw err("Invalid number");
            }
        }
        String n = s.substring(start, i);
        if (integer) {
            try {
                return Long.valueOf(n);
            } catch (NumberFormatException ex) {
                return new BigDecimal(n);
            }
        }
        return new BigDecimal(n);
    }

    // ---------------------------------------------------------------- writing

    /** Pretty output, two-space indent, LF, no trailing newline. */
    public static String write(Object tree) {
        StringBuilder b = new StringBuilder();
        write(b, tree, 0, true);
        return b.toString();
    }

    public static String writeCompact(Object tree) {
        StringBuilder b = new StringBuilder();
        write(b, tree, 0, false);
        return b.toString();
    }

    private static void newline(StringBuilder b, int indent, boolean pretty) {
        if (pretty) {
            b.append('\n');
            for (int k = 0; k < indent; k++) {
                b.append("  ");
            }
        }
    }

    private static void write(StringBuilder b, Object v, int indent, boolean pretty) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer
                || v instanceof Short || v instanceof Byte || v instanceof BigDecimal) {
            b.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("NaN and Infinity are not valid JSON");
            }
            b.append(n);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent + 1, pretty);
                quote(b, key);
                b.append(pretty ? ": " : ":");
                write(b, e.getValue(), indent + 1, pretty);
            }
            newline(b, indent, pretty);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            boolean first = true;
            for (Object item : l) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent + 1, pretty);
                write(b, item, indent + 1, pretty);
            }
            newline(b, indent, pretty);
            b.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + v.getClass().getName());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}

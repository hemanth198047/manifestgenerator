package com.example.casc.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tolerant JSON reader for hand-edited files: accepts trailing commas and missing commas between members.
 * Everything else must be valid JSON. Reads into the same types as {@link Json} (duplicate keys: the last one wins).
 */
public final class LenientJson {
    private final String s;
    private int i;

    private LenientJson(String s, int start) {
        this.s = s;
        this.i = start;
    }

    /** Parses a whole text. Throws IllegalArgumentException (with the line and column) when it is not readable even leniently. */
    public static Object parse(String text) {
        String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
        LenientJson p = new LenientJson(t, 0);
        Object v = p.value(0);
        p.ws();
        if (p.i < t.length()) {
            throw p.err("unexpected content after the JSON value");
        }
        return v;
    }

    /** Parses the value that starts at the given offset and ignores whatever follows it. */
    public static Object parseAt(String text, int start) {
        return new LenientJson(text, start).value(0);
    }

    private IllegalArgumentException err(String message) {
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
        return new IllegalArgumentException(message + " at line " + line + ", column " + col);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw err("nesting is deeper than 64");
        }
        ws();
        if (i >= s.length()) {
            throw err("unexpected end of text");
        }
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            while (true) {
                ws();
                if (i >= s.length()) {
                    throw err("unterminated object");
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
                    throw err("expected a property name");
                }
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw err("expected ':' after \"" + k + "\"");
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
                    throw err("unterminated array");
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
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            case "null":
                return null;
            default:
                try {
                    return Long.valueOf(w);
                } catch (NumberFormatException e) {
                    try {
                        return new BigDecimal(w);
                    } catch (NumberFormatException e2) {
                        i = st;
                        throw err("unexpected '" + w + "'");
                    }
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
                            try {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("invalid \\u escape");
                            }
                            i += 4;
                        }
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        throw err("unterminated string");
    }
}

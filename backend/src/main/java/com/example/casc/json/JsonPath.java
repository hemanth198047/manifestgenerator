package com.example.casc.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small path language for picking sections out of a JSON tree.
 * <pre>
 *   databases                       a key
 *   databases[0].schemas[1]         an array item (negative counts from the end: [-1] is the last)
 *   databases[*].schemas[*].tables  every item / every value ([*] or .*)
 *   tables[0:5]                     a slice of an array (items 0 to 4)
 *   tables[?physicalName=TAPSAccount]   array items whose field equals a value ( != not equal, ~ contains, case ignored )
 *   ..physicalName                  every "physicalName" key at any depth
 *   ["key.with.dots"]               a key written in quotes
 *   $ or empty                      the whole document
 * </pre>
 */
public final class JsonPath {
    private JsonPath() {}

    public record Match(String path, Object value) {}

    enum Kind { KEY, INDEX, WILD, SLICE, FILTER, DEEP }

    record Seg(Kind kind, String text, int a, int b, boolean hasA, boolean hasB, String field, String op, String value) {}

    public static List<Match> select(Object root, String expr) {
        List<Seg> segs = parse(expr);
        List<Match> cur = new ArrayList<>();
        cur.add(new Match("$", root));
        for (Seg seg : segs) {
            List<Match> next = new ArrayList<>();
            for (Match m : cur) {
                expand(m, seg, next);
            }
            cur = next;
        }
        return cur;
    }

    /** Parses and checks the path; throws IllegalArgumentException with a message that says what is wrong. */
    public static void check(String expr) {
        parse(expr);
    }

    // ------------------------------------------------------------------ evaluation

    private static void expand(Match m, Seg seg, List<Match> out) {
        Object v = m.value();
        switch (seg.kind()) {
            case KEY -> {
                if (v instanceof Map<?, ?> map && map.containsKey(seg.text())) {
                    out.add(new Match(child(m.path(), seg.text()), map.get(seg.text())));
                }
            }
            case INDEX -> {
                if (v instanceof List<?> l) {
                    int idx = seg.a() < 0 ? l.size() + seg.a() : seg.a();
                    if (idx >= 0 && idx < l.size()) {
                        out.add(new Match(m.path() + "[" + idx + "]", l.get(idx)));
                    }
                }
            }
            case WILD -> {
                if (v instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        out.add(new Match(child(m.path(), String.valueOf(e.getKey())), e.getValue()));
                    }
                } else if (v instanceof List<?> l) {
                    for (int i = 0; i < l.size(); i++) {
                        out.add(new Match(m.path() + "[" + i + "]", l.get(i)));
                    }
                }
            }
            case SLICE -> {
                if (v instanceof List<?> l) {
                    int from = seg.hasA() ? (seg.a() < 0 ? Math.max(0, l.size() + seg.a()) : Math.min(seg.a(), l.size())) : 0;
                    int to = seg.hasB() ? (seg.b() < 0 ? Math.max(0, l.size() + seg.b()) : Math.min(seg.b(), l.size())) : l.size();
                    for (int i = from; i < to; i++) {
                        out.add(new Match(m.path() + "[" + i + "]", l.get(i)));
                    }
                }
            }
            case FILTER -> {
                if (v instanceof List<?> l) {
                    for (int i = 0; i < l.size(); i++) {
                        if (l.get(i) instanceof Map<?, ?> item && matches(item.get(seg.field()), item.containsKey(seg.field()), seg)) {
                            out.add(new Match(m.path() + "[" + i + "]", l.get(i)));
                        }
                    }
                } else if (v instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> item && matches(item.get(seg.field()), item.containsKey(seg.field()), seg)) {
                            out.add(new Match(child(m.path(), String.valueOf(e.getKey())), e.getValue()));
                        }
                    }
                }
            }
            case DEEP -> deep(m.path(), v, seg.text(), out);
        }
    }

    private static void deep(String path, Object v, String key, List<Match> out) {
        if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String p = child(path, String.valueOf(e.getKey()));
                if (String.valueOf(e.getKey()).equals(key)) {
                    out.add(new Match(p, e.getValue()));
                }
                deep(p, e.getValue(), key, out);
            }
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                deep(path + "[" + i + "]", l.get(i), key, out);
            }
        }
    }

    private static boolean matches(Object actual, boolean present, Seg seg) {
        String a = scalarText(actual);
        return switch (seg.op()) {
            case "=" -> present && a != null && a.equals(seg.value());
            case "!=" -> !(present && a != null && a.equals(seg.value()));
            default -> present && a != null && a.toLowerCase(Locale.ROOT).contains(seg.value().toLowerCase(Locale.ROOT));
        };
    }

    private static String scalarText(Object o) {
        if (o == null || o instanceof Map<?, ?> || o instanceof List<?>) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b.toPlainString();
        }
        return String.valueOf(o);
    }

    /** The path of a key under a parent, written the way the path language reads it. */
    public static String child(String parent, String key) {
        if (key.matches("[A-Za-z_][A-Za-z0-9_\\-]*")) {
            return parent + ("$".equals(parent) ? "." : ".") + key;
        }
        return parent + "[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    // ------------------------------------------------------------------ parsing

    static List<Seg> parse(String expr) {
        String e = expr == null ? "" : expr.trim();
        List<Seg> out = new ArrayList<>();
        int i = 0;
        if (e.startsWith("$")) {
            i = 1;
        }
        boolean first = i == 0;
        while (i < e.length()) {
            char c = e.charAt(i);
            if (c == '.' && i + 1 < e.length() && e.charAt(i + 1) == '.') {
                i += 2;
                int st = i;
                while (i < e.length() && e.charAt(i) != '.' && e.charAt(i) != '[') {
                    i++;
                }
                if (st == i) {
                    throw new IllegalArgumentException("'..' must be followed by a key name, for example ..physicalName (at position " + st + " of the path)");
                }
                out.add(new Seg(Kind.DEEP, e.substring(st, i), 0, 0, false, false, null, null, null));
            } else if (c == '.') {
                i++;
                if (i < e.length() && e.charAt(i) == '*') {
                    out.add(new Seg(Kind.WILD, null, 0, 0, false, false, null, null, null));
                    i++;
                } else {
                    int st = i;
                    while (i < e.length() && e.charAt(i) != '.' && e.charAt(i) != '[') {
                        i++;
                    }
                    if (st == i) {
                        throw new IllegalArgumentException("A key name is missing after the '.' at position " + (st - 1) + " of the path");
                    }
                    out.add(new Seg(Kind.KEY, e.substring(st, i), 0, 0, false, false, null, null, null));
                }
            } else if (c == '[') {
                int close = closing(e, i);
                String inner = e.substring(i + 1, close).trim();
                i = close + 1;
                out.add(bracket(inner));
            } else if (first) {
                int st = i;
                while (i < e.length() && e.charAt(i) != '.' && e.charAt(i) != '[') {
                    i++;
                }
                String name = e.substring(st, i);
                out.add(name.equals("*") ? new Seg(Kind.WILD, null, 0, 0, false, false, null, null, null)
                        : new Seg(Kind.KEY, name, 0, 0, false, false, null, null, null));
            } else {
                throw new IllegalArgumentException("Unexpected '" + c + "' at position " + i + " of the path '" + expr + "'");
            }
            first = false;
        }
        return out;
    }

    private static int closing(String e, int open) {
        boolean q = false;
        char quote = 0;
        for (int k = open + 1; k < e.length(); k++) {
            char c = e.charAt(k);
            if (q) {
                if (c == '\\') {
                    k++;
                } else if (c == quote) {
                    q = false;
                }
            } else if (c == '"' || c == '\'') {
                q = true;
                quote = c;
            } else if (c == ']') {
                return k;
            }
        }
        throw new IllegalArgumentException("A '[' at position " + open + " of the path '" + e + "' has no closing ']'");
    }

    private static Seg bracket(String inner) {
        if (inner.equals("*")) {
            return new Seg(Kind.WILD, null, 0, 0, false, false, null, null, null);
        }
        if (inner.startsWith("?")) {
            String f = inner.substring(1).trim();
            String op = f.contains("!=") ? "!=" : f.contains("~") ? "~" : "=";
            int at = f.indexOf(op);
            if (at <= 0) {
                throw new IllegalArgumentException("A filter must look like [?field=value], [?field!=value] or [?field~text]; got [" + inner + "]");
            }
            return new Seg(Kind.FILTER, null, 0, 0, false, false, unquote(f.substring(0, at).trim()), op, unquote(f.substring(at + op.length()).trim()));
        }
        if (inner.startsWith("\"") || inner.startsWith("'")) {
            return new Seg(Kind.KEY, unquote(inner), 0, 0, false, false, null, null, null);
        }
        if (inner.contains(":")) {
            String[] p = inner.split(":", -1);
            if (p.length != 2) {
                throw new IllegalArgumentException("A slice must look like [from:to]; got [" + inner + "]");
            }
            try {
                return new Seg(Kind.SLICE, null, p[0].isBlank() ? 0 : Integer.parseInt(p[0].trim()), p[1].isBlank() ? 0 : Integer.parseInt(p[1].trim()),
                        !p[0].isBlank(), !p[1].isBlank(), null, null, null);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("A slice needs whole numbers, for example [0:5]; got [" + inner + "]");
            }
        }
        try {
            return new Seg(Kind.INDEX, null, Integer.parseInt(inner), 0, true, false, null, null, null);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Inside [ ] write a number, *, a slice like 0:5, a filter like ?name=value or a quoted key; got [" + inner + "]");
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }
}

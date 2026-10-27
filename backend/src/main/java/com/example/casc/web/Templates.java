package com.example.casc.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads /templates/NAME.html from the classpath and replaces {{token}} markers in a single pass.
 * Values must already be HTML-escaped (or intentional HTML); they are not re-scanned for tokens.
 * A token without a value is a programming error and fails loudly.
 */
final class Templates {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Templates() {}

    private static String load(String name) throws IOException {
        String t = CACHE.get(name);
        if (t == null) {
            try (InputStream in = Templates.class.getResourceAsStream("/templates/" + name + ".html")) {
                if (in == null) {
                    throw new IOException("Missing template: " + name);
                }
                t = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            CACHE.put(name, t);
        }
        return t;
    }

    static String render(String name, Map<String, String> values) throws IOException {
        String t = load(name);
        StringBuilder b = new StringBuilder(t.length() + 256);
        int pos = 0;
        while (true) {
            int start = t.indexOf("{{", pos);
            if (start < 0) {
                b.append(t, pos, t.length());
                return b.toString();
            }
            int end = t.indexOf("}}", start);
            if (end < 0) {
                throw new IllegalStateException("Unclosed token in template " + name);
            }
            b.append(t, pos, start);
            String key = t.substring(start + 2, end).trim();
            String value = values.get(key);
            if (value == null) {
                throw new IllegalStateException("No value for {{" + key + "}} in template " + name);
            }
            b.append(value);
            pos = end + 2;
        }
    }
}

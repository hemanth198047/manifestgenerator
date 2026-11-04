package com.example.casc.mcp;

import com.example.casc.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One JSON line per tool call: when, which tool, which phase, what happened. File contents are never logged and long text values are cut. */
public final class Audit {
    private final Path file;

    public Audit(Path file) {
        this.file = file;
    }

    public static Audit none() {
        return new Audit(null);
    }

    public void log(String tool, String phase, boolean ok, Map<String, Object> args, List<String> files, String planHash) {
        if (file == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", Instant.now().toString());
        line.put("tool", tool);
        line.put("phase", phase);
        line.put("ok", ok);
        line.put("arguments", shorten(args));
        if (files != null && !files.isEmpty()) {
            line.put("files", new java.util.ArrayList<Object>(files));
        }
        if (planHash != null) {
            line.put("planHash", planHash);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Json.writeCompact(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("casc-generator mcp: could not write the audit log: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object shorten(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), shorten(e.getValue()));
            }
            return out;
        } else if (v instanceof List<?> l) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object o : l) {
                out.add(shorten(o));
            }
            return out;
        } else if (v instanceof String s && s.length() > 200) {
            return s.substring(0, 200) + "...(" + s.length() + " characters)";
        }
        return v;
    }
}

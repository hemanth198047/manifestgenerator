package com.example.casc.ingest;

import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.DatabaseType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON request shared by the REST API and agent tools: sources, options and optional system context. */
public record IngestRequest(List<SourceInput> sources, List<String> urls, IngestOptions options,
                            DraftBuilder.SystemContext system) {

    /**
     * {"sources":[{"name":"a.csv","text":"..."}], "urls":["https://wiki.corp.example/spec.json"],
     *  "options":{"dialect":"oracle","hasHeader":true,"maxRows":500,"map.table":"Tbl"},
     *  "system":{"databaseLogicalName":"TRADE_DB","databaseType":"PostgreSQL","defaultSchema":"public",
     *           "interfaceDirection":"DISTRIBUTION"}}
     * At least one source or URL is required.
     */
    public static IngestRequest fromTree(Object tree) {
        Map<String, Object> root = object(tree, "request");
        List<SourceInput> sources = new ArrayList<>();
        Object s = root.get("sources");
        if (s != null && !(s instanceof List<?>)) {
            throw new IllegalArgumentException("'sources' must be an array");
        }
        List<?> list = s == null ? List.of() : (List<?>) s;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> o = object(list.get(i), "sources[" + i + "]");
            if (!(o.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("sources[" + i + "].name must be a non-blank string");
            }
            if (!(o.get("text") instanceof String text)) {
                throw new IllegalArgumentException("sources[" + i + "].text must be a string");
            }
            sources.add(SourceInput.of(name, text));
        }
        List<String> urls = new ArrayList<>();
        Object u = root.get("urls");
        if (u != null) {
            if (!(u instanceof List<?> ul)) {
                throw new IllegalArgumentException("'urls' must be an array of strings");
            }
            for (int i = 0; i < ul.size(); i++) {
                if (!(ul.get(i) instanceof String us) || us.isBlank()) {
                    throw new IllegalArgumentException("urls[" + i + "] must be a non-blank string");
                }
                urls.add(us);
            }
        }
        if (s == null && urls.isEmpty()) {
            throw new IllegalArgumentException("'sources' or 'urls' is required");
        }
        Map<String, String> opts = new LinkedHashMap<>();
        if (root.get("options") != null) {
            for (Map.Entry<String, Object> e : object(root.get("options"), "options").entrySet()) {
                Object v = e.getValue();
                if (v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof BigDecimal) {
                    opts.put(e.getKey(), String.valueOf(v));
                } else {
                    throw new IllegalArgumentException("options." + e.getKey() + " must be a string, number or boolean");
                }
            }
        }
        DraftBuilder.SystemContext ctx = null;
        if (root.get("system") != null) {
            Map<String, Object> sys = object(root.get("system"), "system");
            String type = str(sys, "databaseType");
            DatabaseType dt = null;
            if (type != null) {
                dt = Enums.parse(DatabaseType.class, type).orElseThrow(() ->
                        new IllegalArgumentException("system.databaseType '" + type + "' is not an allowed value"));
            }
            String dir = str(sys, "interfaceDirection");
            com.example.casc.domain.Enums.InterfaceType idir = null;
            if (dir != null) {
                idir = Enums.parse(com.example.casc.domain.Enums.InterfaceType.class, dir).orElseThrow(() ->
                        new IllegalArgumentException("system.interfaceDirection must be SOURCING or DISTRIBUTION"));
            }
            ctx = new DraftBuilder.SystemContext(str(sys, "databaseLogicalName"), dt, str(sys, "defaultSchema"), idir);
        }
        return new IngestRequest(sources, urls, IngestOptions.fromMap(opts), ctx);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("system." + k + " must be a string");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(what + " must be an object");
    }
}

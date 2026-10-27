package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawStructure;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 3.x and Swagger 2.0 in JSON form (YAML needs an approved YAML parser and is reported as unavailable).
 * Operations become CANDIDATE interfaces; their request and response schemas are flattened into hierarchical field
 * paths such as "trade.items[].qty". Component schemas become candidate structures. Nothing is turned into a relational
 * table and no SOURCING/DISTRIBUTION direction is inferred. Only local "#/..." references are followed; external
 * references are reported and never fetched. Parameters (query, path, header) are not extracted.
 */
public final class OpenApiStrategy implements SourceIngestionStrategy {
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FIELDS = 2000;
    private static final int MAX_OPERATIONS = 5000;

    @Override public String id() { return "openapi"; }

    @Override public Set<String> extensions() { return Set.of("json"); }

    private static final class Ctx {
        final Map<String, Object> root;
        final String source;
        final Builder out;
        final Set<String> warned = new HashSet<>();

        Ctx(Map<String, Object> root, String source, Builder out) {
            this.root = root;
            this.source = source;
            this.out = out;
        }

        void warnOnce(String code, String message, String fix) {
            if (warned.add(code + message)) {
                out.warn(code, source, message, fix);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        Object tree;
        try {
            tree = Json.parse(in.text());
        } catch (JsonException e) {
            b.error("JSON_SYNTAX", in.name(), e.getMessage(), "Fix the JSON, or export it again.");
            b.source(in.name(), "json", "REJECTED", "invalid JSON");
            return b.build();
        }
        if (!(tree instanceof Map<?, ?> m) || !(m.get("openapi") instanceof String || m.get("swagger") instanceof String)) {
            b.error("OPENAPI_NOT_RECOGNISED", in.name(),
                    "The JSON document is not OpenAPI or Swagger (no top-level 'openapi' or 'swagger' field).",
                    "Only OpenAPI/Swagger JSON is supported as a JSON source. To check a manifest use the validate command.");
            b.source(in.name(), "json", "REJECTED", "not OpenAPI");
            return b.build();
        }
        Map<String, Object> root = (Map<String, Object>) m;
        String version = root.get("openapi") instanceof String s ? "OpenAPI " + s : "Swagger " + root.get("swagger");
        Ctx x = new Ctx(root, in.name(), b);
        Object info = root.get("info");
        if (info instanceof Map<?, ?> im && im.get("title") instanceof String title) {
            String v = im.get("version") instanceof String ver ? " " + ver : "";
            b.note("HEADING", title + v, in.name(), "#/info");
        }
        int ops = 0;
        if (root.get("paths") instanceof Map<?, ?> paths) {
            for (Map.Entry<?, ?> pe : paths.entrySet()) {
                if (!(pe.getValue() instanceof Map<?, ?> item) || !(pe.getKey() instanceof String path)) {
                    continue;
                }
                for (String method : METHODS) {
                    if (item.get(method) instanceof Map<?, ?> op) {
                        if (++ops > MAX_OPERATIONS) {
                            x.warnOnce("OPENAPI_LIMIT", "More than " + MAX_OPERATIONS + " operations; the rest were not read.",
                                    "Split the specification.");
                            break;
                        }
                        operation(x, path, method, (Map<String, Object>) op);
                    }
                }
            }
        }
        if (ops == 0) {
            b.warn("OPENAPI_NO_OPERATIONS", in.name(), "The document has no operations under 'paths'.",
                    "Check that this is the right file.");
        }
        int schemas = 0;
        Map<String, Object> defs = null;
        String defsPointer = "";
        if (root.get("components") instanceof Map<?, ?> comp && comp.get("schemas") instanceof Map<?, ?> sc) {
            defs = (Map<String, Object>) sc;
            defsPointer = "#/components/schemas/";
        } else if (root.get("definitions") instanceof Map<?, ?> d) {
            defs = (Map<String, Object>) d;
            defsPointer = "#/definitions/";
        }
        if (defs != null) {
            for (Map.Entry<String, Object> e : defs.entrySet()) {
                Map<String, RawField> fields = new LinkedHashMap<>();
                visit(x, e.getValue(), "", 0, new HashSet<>(), fields, null);
                String desc = e.getValue() instanceof Map<?, ?> sm ? text(sm.get("description")) : null;
                b.structure(new RawStructure(e.getKey(), desc, new ArrayList<>(fields.values()), in.name(), defsPointer + esc(e.getKey())));
                schemas++;
            }
        }
        b.info("OPENAPI_INTERPRETATION", in.name(), "Operations are candidate interfaces and schemas are candidate structures. "
                + "The SOURCING/DISTRIBUTION direction is not inferred and no relational tables are created from schemas.",
                "Classify interfaces during review.");
        b.source(in.name(), "openapi", "INGESTED", version + ": " + ops + " operation(s), " + schemas + " schema(s)");
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private void operation(Ctx x, String path, String method, Map<String, Object> op) {
        String name = op.get("operationId") instanceof String id && !id.isBlank() ? id : method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        String desc = text(op.get("summary")) != null ? text(op.get("summary")) : text(op.get("description"));
        Map<String, RawField> fields = new LinkedHashMap<>();
        Object reqSchema = null;
        Object rb = deref(x, op.get("requestBody"));
        if (rb instanceof Map<?, ?> rm) {
            reqSchema = mediaSchema(x, rm.get("content"));
        }
        if (reqSchema == null && op.get("parameters") instanceof List<?> params) {
            for (Object p : params) {
                Object pd = deref(x, p);
                if (pd instanceof Map<?, ?> pm && "body".equals(pm.get("in"))) {
                    reqSchema = pm.get("schema");
                }
            }
        }
        if (reqSchema != null) {
            visit(x, reqSchema, "", 0, new HashSet<>(), fields, "request");
        }
        Object respSchema = null;
        if (op.get("responses") instanceof Map<?, ?> responses) {
            for (String code : List.of("200", "201", "202", "203", "default")) {
                Object r = deref(x, responses.get(code));
                if (r instanceof Map<?, ?> rm) {
                    Object schema = rm.get("content") != null ? mediaSchema(x, rm.get("content")) : rm.get("schema");
                    if (schema != null) {
                        respSchema = schema;
                        break;
                    }
                }
            }
        }
        if (respSchema != null) {
            visit(x, respSchema, "", 0, new HashSet<>(), fields, "response");
        }
        x.out.iface(new RawInterface(name, method.toUpperCase(java.util.Locale.ROOT), path, desc,
                new ArrayList<>(fields.values()), x.source, "#/paths/" + esc(path) + "/" + method));
    }

    private Object mediaSchema(Ctx x, Object content) {
        if (!(content instanceof Map<?, ?> cm) || cm.isEmpty()) {
            return null;
        }
        Object chosen = null;
        for (Map.Entry<?, ?> e : cm.entrySet()) {
            if (String.valueOf(e.getKey()).contains("json")) {
                chosen = e.getValue();
                break;
            }
        }
        if (chosen == null) {
            chosen = cm.values().iterator().next();
        }
        return chosen instanceof Map<?, ?> m ? m.get("schema") : null;
    }

    /** Resolves a local "#/..." reference; returns the node itself when it has no $ref. */
    private Object deref(Ctx x, Object node) {
        if (node instanceof Map<?, ?> m && m.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/")) {
                x.warnOnce("OPENAPI_EXTERNAL_REF", "External reference '" + redactRef(ref) + "' was not followed.",
                        "Inline the referenced schema or add that document as its own source.");
                return null;
            }
            Object cur = x.root;
            for (String part : ref.substring(2).split("/")) {
                String key = part.replace("~1", "/").replace("~0", "~");
                if (cur instanceof Map<?, ?> cm && cm.containsKey(key)) {
                    cur = cm.get(key);
                } else {
                    x.warnOnce("OPENAPI_BAD_REF", "Reference '" + ref + "' could not be resolved.", "Fix the reference.");
                    return null;
                }
            }
            return cur;
        }
        return node;
    }

    private static String redactRef(String ref) {
        int q = ref.indexOf('?');
        return q < 0 ? ref : ref.substring(0, q) + "?...";
    }

    @SuppressWarnings("unchecked")
    private void visit(Ctx x, Object node, String prefix, int depth, Set<String> stack, Map<String, RawField> out, String role) {
        if (out.size() >= MAX_FIELDS) {
            x.warnOnce("OPENAPI_LIMIT", "More than " + MAX_FIELDS + " fields in one schema; the rest were not listed.", "Split the schema.");
            return;
        }
        if (depth > MAX_DEPTH) {
            x.warnOnce("OPENAPI_DEPTH", "Schemas nested deeper than " + MAX_DEPTH + " levels were cut off.", "Review the cut-off structures by hand.");
            return;
        }
        if (!(node instanceof Map<?, ?> m)) {
            return;
        }
        if (m.get("$ref") instanceof String ref) {
            if (!stack.add(ref)) {
                x.warnOnce("OPENAPI_CYCLE", "A circular reference ('" + redactRef(ref) + "') was cut off.", "Review recursive structures by hand.");
                return;
            }
            visit(x, deref(x, node), prefix, depth + 1, stack, out, role);
            stack.remove(ref);
            return;
        }
        for (String comp : List.of("allOf", "oneOf", "anyOf")) {
            if (m.get(comp) instanceof List<?> l) {
                if (!comp.equals("allOf")) {
                    x.warnOnce("OPENAPI_COMPOSITION", comp + " alternatives were merged into one list of candidate fields.",
                            "Review which alternatives really apply.");
                }
                for (Object part : l) {
                    visit(x, part, prefix, depth + 1, stack, out, role);
                }
            }
        }
        if (m.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                String path = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                property(x, e.getValue(), path, depth + 1, stack, out, role);
            }
        } else if ("array".equals(m.get("type")) && m.get("items") != null) {
            visit(x, m.get("items"), prefix.isEmpty() ? prefix : prefix + "[]", depth + 1, stack, out, role);
        }
    }

    private void property(Ctx x, Object node, String path, int depth, Set<String> stack, Map<String, RawField> out, String role) {
        Object resolved = node;
        String ref = null;
        if (node instanceof Map<?, ?> m && m.get("$ref") instanceof String r) {
            ref = r;
            if (!stack.add(r)) {
                x.warnOnce("OPENAPI_CYCLE", "A circular reference ('" + redactRef(r) + "') was cut off.", "Review recursive structures by hand.");
                return;
            }
            resolved = deref(x, node);
        }
        if (resolved instanceof Map<?, ?> rm) {
            boolean composite = rm.get("properties") instanceof Map<?, ?> || rm.get("allOf") != null
                    || rm.get("oneOf") != null || rm.get("anyOf") != null;
            if ("array".equals(rm.get("type")) && rm.get("items") != null) {
                Object items = rm.get("items");
                Object itemResolved = items instanceof Map<?, ?> im && im.get("$ref") != null ? deref(x, items) : items;
                boolean itemComposite = itemResolved instanceof Map<?, ?> ir && (ir.get("properties") instanceof Map<?, ?>
                        || ir.get("allOf") != null || ir.get("oneOf") != null || ir.get("anyOf") != null);
                if (itemComposite) {
                    visit(x, items, path + "[]", depth + 1, stack, out, role);
                } else {
                    add(out, path + "[]", itemResolved, rm, role);
                }
            } else if (composite) {
                visit(x, resolved, path, depth + 1, stack, out, role);
            } else {
                add(out, path, resolved, rm, role);
            }
        }
        if (ref != null) {
            stack.remove(ref);
        }
    }

    private void add(Map<String, RawField> out, String path, Object typeNode, Map<?, ?> descNode, String role) {
        Map<?, ?> t = typeNode instanceof Map<?, ?> tm ? tm : descNode;
        String type = typeName(t);
        String desc = text(descNode.get("description"));
        out.putIfAbsent(path, new RawField(path, type, desc, role));
    }

    private static String typeName(Map<?, ?> m) {
        Object t = m.get("type");
        String type = t instanceof String s ? s : t instanceof List<?> l ? String.join("/", l.stream().map(String::valueOf).toList()) : null;
        if (type != null && m.get("format") instanceof String f) {
            type = type + "(" + f + ")";
        }
        return type;
    }

    private static String text(Object o) {
        if (!(o instanceof String s) || s.isBlank()) {
            return null;
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 300 ? one.substring(0, 300) : one;
    }

    private static String esc(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }
}

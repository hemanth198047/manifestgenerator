package com.example.casc.wizard;

import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.domain.Enums.TargetTableType;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.SourceInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Save and restore a wizard session as a JSON project file that the user keeps. The server never stores it.
 * It contains the source texts and every decision, so treat it as sensitive as the sources themselves.
 * Loading is strict: wrong types, unknown fields at the top level, unknown enum values and a different version
 * are rejected with the path of the problem.
 */
public final class ProjectFile {
    private ProjectFile() {}

    public static final int VERSION = 1;
    private static final Set<String> TOP = Set.of("projectVersion", "profile", "sources", "options", "system", "services",
            "assets", "elements", "columns", "fields", "entities", "transformations", "acknowledged");

    // ------------------------------------------------------------------ write

    public static Map<String, Object> toTree(WizardState st) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("projectVersion", VERSION);
        t.put("profile", com.example.casc.profile.SchemaProfile.provisional().name());
        t.put("sources", list(st.sources, s -> s.isBinary() ? map("name", s.name(), "contentBase64", s.base64()) : map("name", s.name(), "text", s.text())));
        t.put("options", new LinkedHashMap<String, Object>(st.options));
        Map<String, Object> sys = map("eonId", st.system.eonId, "name", st.system.name,
                "databaseLogicalName", st.system.databaseLogicalName, "defaultSchema", st.system.defaultSchema);
        if (st.system.databaseType != null) {
            sys.put("databaseType", st.system.databaseType.label());
        }
        t.put("system", sys);
        t.put("services", list(st.services, s -> {
            Map<String, Object> o = map("name", s.name, "cloudProvider", s.cloudProvider, "systemKey", s.systemKey);
            if (s.onPrem != null) {
                o.put("onPrem", s.onPrem);
            }
            return o;
        }));
        List<Object> assets = new ArrayList<>();
        for (Map.Entry<String, WizardState.Asset> e : st.assets.entrySet()) {
            WizardState.Asset a = e.getValue();
            Map<String, Object> o = map("key", e.getKey(), "included", a.included, "systemKey", a.systemKey);
            if (a.tableType != null) {
                o.put("tableType", a.tableType.label());
            }
            if (a.direction != null) {
                o.put("direction", a.direction.label());
            }
            o.put("services", new ArrayList<Object>(a.services));
            o.put("excludedFields", new ArrayList<Object>(a.excludedFields));
            assets.add(o);
        }
        t.put("assets", assets);
        t.put("elements", list(st.elements, e -> {
            Map<String, Object> o = map("name", e.name, "description", e.description, "concept", e.concept, "domain", e.domain,
                    "systemKey", e.systemKey);
            o.put("services", new ArrayList<Object>(e.services));
            return o;
        }));
        t.put("columns", gov(st.columns));
        t.put("fields", gov(st.fields));
        t.put("entities", list(st.entities, e -> {
            Map<String, Object> o = map("name", e.name, "description", e.description, "systemKey", e.systemKey);
            o.put("services", new ArrayList<Object>(e.services));
            o.put("elements", new ArrayList<Object>(e.elements));
            return o;
        }));
        t.put("transformations", list(st.transformations, x -> {
            Map<String, Object> o = map("name", x.name, "description", x.description, "systemKey", x.systemKey);
            o.put("sources", new ArrayList<Object>(x.sources));
            o.put("targets", new ArrayList<Object>(x.targets));
            return o;
        }));
        t.put("acknowledged", new ArrayList<Object>(st.acknowledged));
        return t;
    }

    private static List<Object> gov(Map<String, WizardState.Gov> m) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, WizardState.Gov> e : m.entrySet()) {
            WizardState.Gov g = e.getValue();
            if (g.isEmpty()) {
                continue;
            }
            Map<String, Object> o = map("key", e.getKey(), "description", g.description, "element", g.element, "lineageJson", g.lineageJson);
            if (g.attestation != null) {
                o.put("attestation", g.attestation.label());
            }
            o.put("pii", new ArrayList<Object>(g.pii));
            List<Object> cats = new ArrayList<>();
            g.categories.forEach(c -> cats.add(c.label()));
            o.put("categories", cats);
            out.add(o);
        }
        return out;
    }

    private static <T> List<Object> list(List<T> in, java.util.function.Function<T, Object> f) {
        List<Object> out = new ArrayList<>();
        for (T x : in) {
            out.add(f.apply(x));
        }
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ read

    /** Throws IllegalArgumentException naming the offending path. */
    public static WizardState fromTree(Object tree) {
        Map<String, Object> root = obj(tree, "$");
        for (String k : root.keySet()) {
            if (!TOP.contains(k)) {
                throw new IllegalArgumentException("$." + k + ": unknown field");
            }
        }
        if (!(root.get("projectVersion") instanceof Long v) || v != VERSION) {
            throw new IllegalArgumentException("$.projectVersion: expected " + VERSION);
        }
        WizardState st = new WizardState();
        int i = 0;
        for (Object o : arr(root.get("sources"), "$.sources")) {
            Map<String, Object> m = obj(o, "$.sources[" + i + "]");
            String sp = "$.sources[" + i + "]";
            String b64 = str(m, "contentBase64", sp);
            try {
                st.sources.add(b64.isEmpty() ? SourceInput.of(str(m, "name", sp), str(m, "text", sp)) : SourceInput.fromBase64(str(m, "name", sp), b64));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(sp + ".contentBase64: not valid Base64");
            }
            i++;
        }
        Map<String, Object> opts = root.get("options") == null ? Map.of() : obj(root.get("options"), "$.options");
        Map<String, String> optStrings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : opts.entrySet()) {
            if (!(e.getValue() instanceof String s)) {
                throw new IllegalArgumentException("$.options." + e.getKey() + ": expected a string");
            }
            optStrings.put(e.getKey(), s);
        }
        try {
            IngestOptions.fromMap(optStrings);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("$.options: " + e.getMessage());
        }
        st.options.putAll(optStrings);
        if (root.get("system") != null) {
            Map<String, Object> m = obj(root.get("system"), "$.system");
            st.system.eonId = str(m, "eonId", "$.system");
            st.system.name = str(m, "name", "$.system");
            st.system.databaseLogicalName = str(m, "databaseLogicalName", "$.system");
            st.system.defaultSchema = str(m, "defaultSchema", "$.system");
            st.system.databaseType = en(DatabaseType.class, m, "databaseType", "$.system");
        }
        i = 0;
        for (Object o : arr(root.get("services"), "$.services")) {
            String p = "$.services[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Service s = new WizardState.Service();
            s.name = str(m, "name", p);
            s.cloudProvider = str(m, "cloudProvider", p);
            s.systemKey = str(m, "systemKey", p);
            if (m.get("onPrem") != null) {
                if (!(m.get("onPrem") instanceof Boolean b)) {
                    throw new IllegalArgumentException(p + ".onPrem: expected true or false");
                }
                s.onPrem = b;
            }
            st.services.add(s);
        }
        i = 0;
        for (Object o : arr(root.get("assets"), "$.assets")) {
            String p = "$.assets[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Asset a = new WizardState.Asset();
            a.included = !(m.get("included") instanceof Boolean b) || b;
            a.systemKey = str(m, "systemKey", p);
            a.tableType = en(TargetTableType.class, m, "tableType", p);
            a.direction = en(InterfaceType.class, m, "direction", p);
            a.services.addAll(strs(m.get("services"), p + ".services"));
            a.excludedFields.addAll(strs(m.get("excludedFields"), p + ".excludedFields"));
            st.assets.put(strReq(m, "key", p), a);
        }
        i = 0;
        for (Object o : arr(root.get("elements"), "$.elements")) {
            String p = "$.elements[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Element e = new WizardState.Element();
            e.name = str(m, "name", p);
            e.description = str(m, "description", p);
            e.concept = str(m, "concept", p);
            e.domain = str(m, "domain", p);
            e.systemKey = str(m, "systemKey", p);
            e.services.addAll(strs(m.get("services"), p + ".services"));
            st.elements.add(e);
        }
        govIn(root.get("columns"), "$.columns", st.columns);
        govIn(root.get("fields"), "$.fields", st.fields);
        i = 0;
        for (Object o : arr(root.get("entities"), "$.entities")) {
            String p = "$.entities[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Entity e = new WizardState.Entity();
            e.name = str(m, "name", p);
            e.description = str(m, "description", p);
            e.systemKey = str(m, "systemKey", p);
            e.services.addAll(strs(m.get("services"), p + ".services"));
            e.elements.addAll(strs(m.get("elements"), p + ".elements"));
            st.entities.add(e);
        }
        i = 0;
        for (Object o : arr(root.get("transformations"), "$.transformations")) {
            String p = "$.transformations[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Transform x = new WizardState.Transform();
            x.name = str(m, "name", p);
            x.description = str(m, "description", p);
            x.systemKey = str(m, "systemKey", p);
            x.sources.addAll(strs(m.get("sources"), p + ".sources"));
            x.targets.addAll(strs(m.get("targets"), p + ".targets"));
            st.transformations.add(x);
        }
        st.acknowledged.addAll(strs(root.get("acknowledged"), "$.acknowledged"));
        return st;
    }

    private static void govIn(Object node, String path, Map<String, WizardState.Gov> into) {
        int i = 0;
        for (Object o : arr(node, path)) {
            String p = path + "[" + i++ + "]";
            Map<String, Object> m = obj(o, p);
            WizardState.Gov g = new WizardState.Gov();
            g.description = str(m, "description", p);
            g.element = str(m, "element", p);
            g.lineageJson = str(m, "lineageJson", p);
            g.attestation = en(Attestation.class, m, "attestation", p);
            g.pii.addAll(strs(m.get("pii"), p + ".pii"));
            int c = 0;
            for (String label : strs(m.get("categories"), p + ".categories")) {
                g.categories.add(Enums.parse(DataSubjectCategory.class, label).orElseThrow(() ->
                        new IllegalArgumentException(p + ".categories: '" + label + "' is not an allowed value")));
            }
            into.put(strReq(m, "key", p), g);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o, String path) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(path + ": expected an object");
    }

    private static List<?> arr(Object o, String path) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException(path + ": expected an array");
    }

    private static String str(Map<String, Object> m, String k, String path) {
        Object v = m.get(k);
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(path + "." + k + ": expected a string");
    }

    private static String strReq(Map<String, Object> m, String k, String path) {
        String s = str(m, k, path);
        if (s.isEmpty()) {
            throw new IllegalArgumentException(path + "." + k + ": required");
        }
        return s;
    }

    private static List<String> strs(Object o, String path) {
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

    private static <E extends Enum<E> & Enums.Labeled> E en(Class<E> type, Map<String, Object> m, String k, String path) {
        Object v = m.get(k);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(path + "." + k + ": expected a string");
        }
        return Enums.parse(type, s).orElseThrow(() -> new IllegalArgumentException(path + "." + k + ": '" + s + "' is not an allowed value"));
    }
}

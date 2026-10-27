package com.example.casc.manifest;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.CasCManifest.*;
import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.*;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.validation.Finding;
import com.example.casc.validation.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Converts between the typed manifest and a generic tree (Map/List/String/Boolean/Number).
 * toTree fixes key order and omits absent values. fromTree reads leniently but reports every
 * type mismatch, invalid enum and unknown field as a Finding; nothing is dropped silently.
 */
public final class ManifestMapper {
    private ManifestMapper() {}

    public record ReadResult(CasCManifest manifest, List<Finding> findings) {}

    public static ReadResult fromJson(String text) throws JsonException {
        return fromTree(Json.parse(text));
    }

    // ------------------------------------------------------------------ typed -> tree

    public static Map<String, Object> toTree(CasCManifest m) {
        Map<String, Object> t = new LinkedHashMap<>();
        list(t, "logicalServices", m.logicalServices(), ManifestMapper::service);
        list(t, "systemDataElements", m.systemDataElements(), ManifestMapper::element);
        list(t, "interfaces", m.interfaces(), ManifestMapper::iface);
        list(t, "dataEntities", m.dataEntities(), ManifestMapper::entity);
        list(t, "databases", m.databases(), ManifestMapper::database);
        list(t, "transformations", m.transformations(), ManifestMapper::transformation);
        return t;
    }

    private static <T> void list(Map<String, Object> t, String key, List<T> items, Function<T, Object> f) {
        if (items == null) {
            return;
        }
        List<Object> out = new ArrayList<>();
        for (T item : items) {
            out.add(f.apply(item));
        }
        t.put(key, out);
    }

    private static void put(Map<String, Object> t, String key, Object v) {
        if (v != null) {
            t.put(key, v);
        }
    }

    private static void strings(Map<String, Object> t, String key, List<String> l) {
        if (l != null) {
            t.put(key, new ArrayList<Object>(l));
        }
    }

    private static Object label(Enums.Labeled e) {
        return e == null ? null : e.label();
    }

    private static Object service(LogicalService s) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "name", s.name());
        put(t, "isDeployedOnPrem", s.isDeployedOnPrem());
        put(t, "cloudProvider", s.cloudProvider());
        put(t, "systemKey", s.systemKey());
        return t;
    }

    private static Object element(SystemDataElement e) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "name", e.name());
        strings(t, "logicalServices", e.logicalServices());
        put(t, "systemKey", e.systemKey());
        put(t, "description", e.description());
        put(t, "dgcDataConcept", e.dgcDataConcept());
        put(t, "dgcDataDomain", e.dgcDataDomain());
        return t;
    }

    private static void pii(Map<String, Object> t, List<PiiElement> pii, List<DataSubjectCategory> cats) {
        if (pii != null) {
            List<Object> out = new ArrayList<>();
            for (PiiElement p : pii) {
                Map<String, Object> o = new LinkedHashMap<>();
                put(o, "name", p.name());
                out.add(o);
            }
            t.put("piiElements", out);
        }
        if (cats != null) {
            List<Object> out = new ArrayList<>();
            for (DataSubjectCategory c : cats) {
                out.add(c.label());
            }
            t.put("dataSubjectCategories", out);
        }
    }

    private static Object iface(Interface i) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "name", i.name());
        put(t, "type", label(i.type()));
        strings(t, "logicalServices", i.logicalServices());
        put(t, "systemKey", i.systemKey());
        list(t, "interfaceDataElements", i.interfaceDataElements(), ManifestMapper::ifaceElement);
        return t;
    }

    private static Object ifaceElement(InterfaceDataElement e) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "physicalName", e.physicalName());
        put(t, "systemDataElement", e.systemDataElement());
        put(t, "systemKey", e.systemKey());
        put(t, "description", e.description());
        put(t, "sensitiveDataAttestation", label(e.sensitiveDataAttestation()));
        pii(t, e.piiElements(), e.dataSubjectCategories());
        return t;
    }

    private static Object entity(DataEntity e) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "name", e.name());
        strings(t, "logicalServices", e.logicalServices());
        put(t, "systemKey", e.systemKey());
        put(t, "description", e.description());
        list(t, "systemDataElements", e.systemDataElements(), r -> {
            Map<String, Object> o = new LinkedHashMap<>();
            put(o, "name", r.name());
            return o;
        });
        return t;
    }

    private static Object database(Database d) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "logicalName", d.logicalName());
        put(t, "databaseType", label(d.databaseType()));
        put(t, "systemKey", d.systemKey());
        list(t, "schemas", d.schemas(), ManifestMapper::schema);
        return t;
    }

    private static Object schema(Schema s) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "physicalName", s.physicalName());
        put(t, "systemKey", s.systemKey());
        list(t, "tables", s.tables(), ManifestMapper::table);
        return t;
    }

    private static Object table(Table tb) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "physicalName", tb.physicalName());
        put(t, "targetTableType", label(tb.targetTableType()));
        put(t, "systemKey", tb.systemKey());
        put(t, "description", tb.description());
        list(t, "columns", tb.columns(), ManifestMapper::column);
        return t;
    }

    private static Object column(Column c) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "physicalName", c.physicalName());
        put(t, "systemDataElement", c.systemDataElement());
        put(t, "systemKey", c.systemKey());
        put(t, "description", c.description());
        put(t, "sensitiveDataAttestation", label(c.sensitiveDataAttestation()));
        pii(t, c.piiElements(), c.dataSubjectCategories());
        if (c.sourcedFromColumns() != null) {
            t.put("sourcedFromColumns", new ArrayList<Object>(c.sourcedFromColumns()));
        }
        return t;
    }

    private static Object transformation(Transformation x) {
        Map<String, Object> t = new LinkedHashMap<>();
        put(t, "name", x.name());
        put(t, "systemKey", x.systemKey());
        put(t, "logicalDescription", x.logicalDescription());
        strings(t, "sourceDataElements", x.sourceDataElements());
        strings(t, "targetDataElements", x.targetDataElements());
        return t;
    }

    // ------------------------------------------------------------------ tree -> typed

    public static ReadResult fromTree(Object root) {
        Reader r = new Reader();
        CasCManifest m = r.root(root);
        return new ReadResult(m, r.findings);
    }

    private static final class Reader {
        final List<Finding> findings = new ArrayList<>();

        void error(String code, String path, String message, String fix) {
            findings.add(new Finding(code, Severity.ERROR, path.isEmpty() ? "$" : path, message, fix));
        }

        static String join(String path, String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        Map<String, Object> obj(Object v, String path) {
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> c = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    c.put(String.valueOf(e.getKey()), e.getValue());
                }
                return c;
            }
            error("TYPE_MISMATCH", path, "Expected an object.", "Use a JSON object here.");
            return null;
        }

        void keys(Map<String, Object> m, String path, String... allowed) {
            Set<String> ok = Set.of(allowed);
            for (String k : m.keySet()) {
                if (!ok.contains(k)) {
                    findings.add(Finding.warning("UNKNOWN_FIELD", join(path, k),
                            "Field is not part of the supported model and is not carried into the output.",
                            "Remove it, or confirm it against the official CasC specification."));
                }
            }
        }

        String str(Map<String, Object> m, String k, String path) {
            Object v = m.get(k);
            if (v == null) {
                return null;
            }
            if (v instanceof String s) {
                return s;
            }
            error("TYPE_MISMATCH", join(path, k), "Expected a string.", "Use a JSON string.");
            return null;
        }

        Boolean bool(Map<String, Object> m, String k, String path) {
            Object v = m.get(k);
            if (v == null) {
                return null;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            error("TYPE_MISMATCH", join(path, k), "Expected true or false.", "Use a JSON boolean, not a string.");
            return null;
        }

        <E extends Enum<E> & Enums.Labeled> String labels(Class<E> type) {
            List<String> out = new ArrayList<>();
            for (E e : type.getEnumConstants()) {
                out.add(e.label());
            }
            return String.join(", ", out);
        }

        <E extends Enum<E> & Enums.Labeled> E parseEnum(Class<E> type, String text, String path) {
            Optional<E> o = Enums.parse(type, text);
            if (o.isPresent()) {
                return o.get();
            }
            error("ENUM_INVALID", path, "'" + text + "' is not an allowed value.",
                    "Use exactly one of: " + labels(type) + " (case-sensitive).");
            return null;
        }

        <E extends Enum<E> & Enums.Labeled> E en(Class<E> type, Map<String, Object> m, String k, String path) {
            String s = str(m, k, path);
            return s == null ? null : parseEnum(type, s, join(path, k));
        }

        List<String> strs(Map<String, Object> m, String k, String path) {
            Object v = m.get(k);
            if (v == null) {
                return null;
            }
            if (!(v instanceof List<?> l)) {
                error("TYPE_MISMATCH", join(path, k), "Expected an array.", "Use a JSON array of strings.");
                return null;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) instanceof String s) {
                    out.add(s);
                } else {
                    error("TYPE_MISMATCH", join(path, k) + "[" + i + "]", "Expected a string.", "Use a JSON string.");
                }
            }
            return out;
        }

        /** Bad items become empty objects so later indexes stay aligned and required-field rules still fire. */
        List<Map<String, Object>> objs(Map<String, Object> m, String k, String path) {
            Object v = m.get(k);
            if (v == null) {
                return null;
            }
            if (!(v instanceof List<?> l)) {
                error("TYPE_MISMATCH", join(path, k), "Expected an array.", "Use a JSON array of objects.");
                return null;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                Map<String, Object> o = obj(l.get(i), join(path, k) + "[" + i + "]");
                out.add(o == null ? new LinkedHashMap<>() : o);
            }
            return out;
        }

        List<PiiElement> piiList(Map<String, Object> o, String path) {
            List<Map<String, Object>> a = objs(o, "piiElements", path);
            if (a == null) {
                return null;
            }
            List<PiiElement> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = join(path, "piiElements") + "[" + i + "]";
                keys(a.get(i), p, "name");
                out.add(new PiiElement(str(a.get(i), "name", p)));
            }
            return out;
        }

        List<DataSubjectCategory> categories(Map<String, Object> o, String path) {
            List<String> raw = strs(o, "dataSubjectCategories", path);
            if (raw == null) {
                return null;
            }
            List<DataSubjectCategory> out = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                DataSubjectCategory c = parseEnum(DataSubjectCategory.class, raw.get(i),
                        join(path, "dataSubjectCategories") + "[" + i + "]");
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        }

        CasCManifest root(Object v) {
            Map<String, Object> m = obj(v, "");
            if (m == null) {
                return new CasCManifest(null, null, null, null, null, null);
            }
            keys(m, "", "logicalServices", "systemDataElements", "interfaces", "dataEntities",
                    "databases", "transformations");
            return new CasCManifest(
                    services(m), elements(m), interfaces(m), entities(m), databases(m), transformations(m));
        }

        List<LogicalService> services(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "logicalServices", "");
            if (a == null) {
                return null;
            }
            List<LogicalService> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "logicalServices[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "name", "isDeployedOnPrem", "cloudProvider", "systemKey");
                out.add(new LogicalService(str(o, "name", p), bool(o, "isDeployedOnPrem", p),
                        str(o, "cloudProvider", p), str(o, "systemKey", p)));
            }
            return out;
        }

        List<SystemDataElement> elements(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "systemDataElements", "");
            if (a == null) {
                return null;
            }
            List<SystemDataElement> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "systemDataElements[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "name", "logicalServices", "systemKey", "description", "dgcDataConcept", "dgcDataDomain");
                out.add(new SystemDataElement(str(o, "name", p), strs(o, "logicalServices", p),
                        str(o, "systemKey", p), str(o, "description", p),
                        str(o, "dgcDataConcept", p), str(o, "dgcDataDomain", p)));
            }
            return out;
        }

        List<Interface> interfaces(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "interfaces", "");
            if (a == null) {
                return null;
            }
            List<Interface> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "interfaces[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "name", "type", "logicalServices", "systemKey", "interfaceDataElements");
                String name = str(o, "name", p);
                InterfaceType type = en(InterfaceType.class, o, "type", p);
                List<String> svc = strs(o, "logicalServices", p);
                String key = str(o, "systemKey", p);
                out.add(new Interface(name, type, svc, key, interfaceElements(o, p)));
            }
            return out;
        }

        List<InterfaceDataElement> interfaceElements(Map<String, Object> o, String path) {
            List<Map<String, Object>> a = objs(o, "interfaceDataElements", path);
            if (a == null) {
                return null;
            }
            List<InterfaceDataElement> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = join(path, "interfaceDataElements") + "[" + i + "]";
                Map<String, Object> e = a.get(i);
                keys(e, p, "physicalName", "systemDataElement", "systemKey", "description",
                        "sensitiveDataAttestation", "piiElements", "dataSubjectCategories");
                String phys = str(e, "physicalName", p);
                String sde = str(e, "systemDataElement", p);
                String key = str(e, "systemKey", p);
                String desc = str(e, "description", p);
                Attestation att = en(Attestation.class, e, "sensitiveDataAttestation", p);
                List<PiiElement> pii = piiList(e, p);
                List<DataSubjectCategory> cats = categories(e, p);
                out.add(new InterfaceDataElement(phys, sde, key, desc, att, pii, cats));
            }
            return out;
        }

        List<DataEntity> entities(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "dataEntities", "");
            if (a == null) {
                return null;
            }
            List<DataEntity> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "dataEntities[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "name", "logicalServices", "systemKey", "description", "systemDataElements");
                String name = str(o, "name", p);
                List<String> svc = strs(o, "logicalServices", p);
                String key = str(o, "systemKey", p);
                String desc = str(o, "description", p);
                List<EntityElementRef> refs = null;
                List<Map<String, Object>> ra = objs(o, "systemDataElements", p);
                if (ra != null) {
                    refs = new ArrayList<>();
                    for (int j = 0; j < ra.size(); j++) {
                        String rp = p + ".systemDataElements[" + j + "]";
                        keys(ra.get(j), rp, "name");
                        refs.add(new EntityElementRef(str(ra.get(j), "name", rp)));
                    }
                }
                out.add(new DataEntity(name, svc, key, desc, refs));
            }
            return out;
        }

        List<Database> databases(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "databases", "");
            if (a == null) {
                return null;
            }
            List<Database> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "databases[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "logicalName", "databaseType", "systemKey", "schemas");
                String name = str(o, "logicalName", p);
                DatabaseType type = en(DatabaseType.class, o, "databaseType", p);
                String key = str(o, "systemKey", p);
                out.add(new Database(name, type, key, schemas(o, p)));
            }
            return out;
        }

        List<Schema> schemas(Map<String, Object> o, String path) {
            List<Map<String, Object>> a = objs(o, "schemas", path);
            if (a == null) {
                return null;
            }
            List<Schema> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = join(path, "schemas") + "[" + i + "]";
                Map<String, Object> s = a.get(i);
                keys(s, p, "physicalName", "systemKey", "tables");
                String name = str(s, "physicalName", p);
                String key = str(s, "systemKey", p);
                out.add(new Schema(name, key, tables(s, p)));
            }
            return out;
        }

        List<Table> tables(Map<String, Object> o, String path) {
            List<Map<String, Object>> a = objs(o, "tables", path);
            if (a == null) {
                return null;
            }
            List<Table> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = join(path, "tables") + "[" + i + "]";
                Map<String, Object> t = a.get(i);
                keys(t, p, "physicalName", "targetTableType", "systemKey", "description", "columns");
                String name = str(t, "physicalName", p);
                TargetTableType type = en(TargetTableType.class, t, "targetTableType", p);
                String key = str(t, "systemKey", p);
                String desc = str(t, "description", p);
                out.add(new Table(name, type, key, desc, columns(t, p)));
            }
            return out;
        }

        List<Column> columns(Map<String, Object> o, String path) {
            List<Map<String, Object>> a = objs(o, "columns", path);
            if (a == null) {
                return null;
            }
            List<Column> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = join(path, "columns") + "[" + i + "]";
                Map<String, Object> c = a.get(i);
                keys(c, p, "physicalName", "systemDataElement", "systemKey", "description",
                        "sensitiveDataAttestation", "piiElements", "dataSubjectCategories", "sourcedFromColumns");
                String phys = str(c, "physicalName", p);
                String sde = str(c, "systemDataElement", p);
                String key = str(c, "systemKey", p);
                String desc = str(c, "description", p);
                Attestation att = en(Attestation.class, c, "sensitiveDataAttestation", p);
                List<PiiElement> pii = piiList(c, p);
                List<DataSubjectCategory> cats = categories(c, p);
                out.add(new Column(phys, sde, key, desc, att, pii, cats, objs(c, "sourcedFromColumns", p)));
            }
            return out;
        }

        List<Transformation> transformations(Map<String, Object> m) {
            List<Map<String, Object>> a = objs(m, "transformations", "");
            if (a == null) {
                return null;
            }
            List<Transformation> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                String p = "transformations[" + i + "]";
                Map<String, Object> o = a.get(i);
                keys(o, p, "name", "systemKey", "logicalDescription", "sourceDataElements", "targetDataElements");
                out.add(new Transformation(str(o, "name", p), str(o, "systemKey", p),
                        str(o, "logicalDescription", p), strs(o, "sourceDataElements", p),
                        strs(o, "targetDataElements", p)));
            }
            return out;
        }
    }
}

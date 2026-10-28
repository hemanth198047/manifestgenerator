package com.example.casc.agent;

import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.domain.Enums.TargetTableType;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.json.Json;
import com.example.casc.wizard.AssetKeys;
import com.example.casc.wizard.WizardState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the userInputs object of generate_casc_manifest into wizard decisions. It uses manifest field names and the
 * names the agent already saw in parsedMetadata (qualified table names, column names, interface names, payload paths).
 * Strict: unknown fields, unknown tables, columns, interfaces and paths, ambiguous names and bad enum values are all
 * rejected with the path of the problem. Nothing the caller says is silently ignored.
 *
 * {"system":{"databaseLogicalName","databaseType","defaultSchema","eonId","name"},
 *  "services":[{"name","isDeployedOnPrem","cloudProvider","systemKey"}],
 *  "elements":[{"name","description","dgcDataConcept","dgcDataDomain","logicalServices","systemKey"}],
 *  "entities":[{"name","description","logicalServices","systemDataElements","systemKey"}],
 *  "transformations":[{"name","logicalDescription","sourceDataElements","targetDataElements","systemKey"}],
 *  "tables":{"schema.table":{"include","tableType","source","columns":{"col":{governance}}}},
 *  "interfaces":{"name":{"include","direction","logicalServices","excludePaths","source","fields":{"path":{governance}}}},
 *  "acknowledged":["decision-id"]}
 * governance: description, systemDataElement, sensitiveDataAttestation, piiElements, dataSubjectCategories, sourcedFromColumns (tables only).
 */
final class UserInputs {
    private UserInputs() {}

    static void apply(WizardState st, RawMetadataBundle b, Object tree) {
        if (tree == null) {
            return;
        }
        Map<String, Object> root = Fields.obj(tree, "userInputs");
        Fields.only(root, "userInputs", Set.of("system", "services", "elements", "entities", "transformations", "tables", "interfaces", "acknowledged"));
        system(st, root.get("system"));
        int i = 0;
        for (Object o : Fields.arr(root.get("services"), "userInputs.services")) {
            String p = "userInputs.services[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "isDeployedOnPrem", "cloudProvider", "systemKey"));
            WizardState.Service s = new WizardState.Service();
            s.name = Fields.req(m, "name", p);
            s.onPrem = Fields.boolOrNull(m, "isDeployedOnPrem", p);
            s.cloudProvider = Fields.opt(m, "cloudProvider", p);
            s.systemKey = Fields.opt(m, "systemKey", p);
            st.services.add(s);
        }
        i = 0;
        for (Object o : Fields.arr(root.get("elements"), "userInputs.elements")) {
            String p = "userInputs.elements[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "description", "dgcDataConcept", "dgcDataDomain", "logicalServices", "systemKey"));
            WizardState.Element e = new WizardState.Element();
            e.name = Fields.req(m, "name", p);
            e.description = Fields.opt(m, "description", p);
            e.concept = Fields.opt(m, "dgcDataConcept", p);
            e.domain = Fields.opt(m, "dgcDataDomain", p);
            e.services = Fields.strings(m.get("logicalServices"), p + ".logicalServices");
            e.systemKey = Fields.opt(m, "systemKey", p);
            st.elements.add(e);
        }
        i = 0;
        for (Object o : Fields.arr(root.get("entities"), "userInputs.entities")) {
            String p = "userInputs.entities[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "description", "logicalServices", "systemDataElements", "systemKey"));
            WizardState.Entity e = new WizardState.Entity();
            e.name = Fields.req(m, "name", p);
            e.description = Fields.opt(m, "description", p);
            e.services = Fields.strings(m.get("logicalServices"), p + ".logicalServices");
            e.elements = Fields.strings(m.get("systemDataElements"), p + ".systemDataElements");
            e.systemKey = Fields.opt(m, "systemKey", p);
            st.entities.add(e);
        }
        i = 0;
        for (Object o : Fields.arr(root.get("transformations"), "userInputs.transformations")) {
            String p = "userInputs.transformations[" + i++ + "]";
            Map<String, Object> m = Fields.obj(o, p);
            Fields.only(m, p, Set.of("name", "logicalDescription", "sourceDataElements", "targetDataElements", "systemKey"));
            WizardState.Transform t = new WizardState.Transform();
            t.name = Fields.req(m, "name", p);
            t.description = Fields.opt(m, "logicalDescription", p);
            t.sources = Fields.strings(m.get("sourceDataElements"), p + ".sourceDataElements");
            t.targets = Fields.strings(m.get("targetDataElements"), p + ".targetDataElements");
            t.systemKey = Fields.opt(m, "systemKey", p);
            st.transformations.add(t);
        }
        if (root.get("tables") != null) {
            for (Map.Entry<String, Object> e : Fields.obj(root.get("tables"), "userInputs.tables").entrySet()) {
                table(st, b, e.getKey(), e.getValue());
            }
        }
        if (root.get("interfaces") != null) {
            for (Map.Entry<String, Object> e : Fields.obj(root.get("interfaces"), "userInputs.interfaces").entrySet()) {
                iface(st, b, e.getKey(), e.getValue());
            }
        }
        st.acknowledged.addAll(Fields.strings(root.get("acknowledged"), "userInputs.acknowledged"));
    }

    private static void system(WizardState st, Object node) {
        if (node == null) {
            return;
        }
        Map<String, Object> m = Fields.obj(node, "userInputs.system");
        Fields.only(m, "userInputs.system", Set.of("databaseLogicalName", "databaseType", "defaultSchema", "eonId", "name"));
        st.system.databaseLogicalName = Fields.opt(m, "databaseLogicalName", "userInputs.system");
        st.system.defaultSchema = Fields.opt(m, "defaultSchema", "userInputs.system");
        st.system.eonId = Fields.opt(m, "eonId", "userInputs.system");
        st.system.name = Fields.opt(m, "name", "userInputs.system");
        String type = Fields.nullable(m, "databaseType", "userInputs.system");
        if (type != null) {
            st.system.databaseType = Enums.parse(DatabaseType.class, type).orElseThrow(() ->
                    new IllegalArgumentException("userInputs.system.databaseType: '" + type + "' is not an allowed value"));
        }
    }

    private static void table(WizardState st, RawMetadataBundle b, String name, Object node) {
        String p = "userInputs.tables." + name;
        Map<String, Object> m = Fields.obj(node, p);
        Fields.only(m, p, Set.of("include", "tableType", "source", "columns"));
        String source = Fields.nullable(m, "source", p);
        List<RawTable> found = new ArrayList<>();
        for (RawTable t : b.tables()) {
            if (t.kind().equals("TABLE") && AssetKeys.qualified(t).equals(name.toLowerCase(Locale.ROOT)) && (source == null || source.equals(t.source()))) {
                found.add(t);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(p + ": no such table in parsedMetadata (use schema.table as shown there)");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(p + ": the table is defined by several sources; add \"source\" with one of "
                    + found.stream().map(RawTable::source).toList());
        }
        RawTable t = found.get(0);
        WizardState.Asset a = st.asset(AssetKeys.table(t));
        a.included = Fields.bool(m, "include", p, true);
        String type = Fields.nullable(m, "tableType", p);
        if (type != null) {
            a.tableType = Enums.parse(TargetTableType.class, type).orElseThrow(() ->
                    new IllegalArgumentException(p + ".tableType: '" + type + "' is not an allowed value"));
        }
        if (m.get("columns") != null) {
            for (Map.Entry<String, Object> c : Fields.obj(m.get("columns"), p + ".columns").entrySet()) {
                RawColumn col = t.columns().stream().filter(x -> x.name().equals(c.getKey())).findFirst().orElseThrow(() ->
                        new IllegalArgumentException(p + ".columns." + c.getKey() + ": no such column"));
                st.columns.put(AssetKeys.column(AssetKeys.table(t), col.name()), gov(c.getValue(), p + ".columns." + c.getKey(), true));
            }
        }
    }

    private static void iface(WizardState st, RawMetadataBundle b, String name, Object node) {
        String p = "userInputs.interfaces." + name;
        Map<String, Object> m = Fields.obj(node, p);
        Fields.only(m, p, Set.of("include", "direction", "logicalServices", "excludePaths", "source", "fields"));
        String source = Fields.nullable(m, "source", p);
        List<RawInterface> found = new ArrayList<>();
        for (RawInterface i : b.interfaces()) {
            if (i.name().equals(name) && (source == null || source.equals(i.source()))) {
                found.add(i);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(p + ": no such interface in parsedMetadata");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(p + ": several interfaces have this name; add \"source\" with one of "
                    + found.stream().map(RawInterface::source).toList());
        }
        RawInterface ri = found.get(0);
        String key = AssetKeys.iface(ri);
        WizardState.Asset a = st.asset(key);
        a.included = Fields.bool(m, "include", p, true);
        String dir = Fields.nullable(m, "direction", p);
        if (dir != null) {
            a.direction = Enums.parse(InterfaceType.class, dir).orElseThrow(() ->
                    new IllegalArgumentException(p + ".direction: must be SOURCING or DISTRIBUTION"));
        }
        a.services = Fields.strings(m.get("logicalServices"), p + ".logicalServices");
        Set<String> paths = new java.util.HashSet<>();
        for (RawField f : ri.fields()) {
            paths.add(f.path());
        }
        for (String ex : Fields.strings(m.get("excludePaths"), p + ".excludePaths")) {
            if (!paths.contains(ex)) {
                throw new IllegalArgumentException(p + ".excludePaths: no such payload path '" + ex + "'");
            }
            a.excludedFields.add(ex);
        }
        if (m.get("fields") != null) {
            for (Map.Entry<String, Object> f : Fields.obj(m.get("fields"), p + ".fields").entrySet()) {
                if (!paths.contains(f.getKey())) {
                    throw new IllegalArgumentException(p + ".fields." + f.getKey() + ": no such payload path");
                }
                st.fields.put(AssetKeys.column(key, f.getKey()), gov(f.getValue(), p + ".fields." + f.getKey(), false));
            }
        }
    }

    private static WizardState.Gov gov(Object node, String p, boolean lineage) {
        Map<String, Object> m = Fields.obj(node, p);
        Fields.only(m, p, lineage
                ? Set.of("description", "systemDataElement", "sensitiveDataAttestation", "piiElements", "dataSubjectCategories", "sourcedFromColumns")
                : Set.of("description", "systemDataElement", "sensitiveDataAttestation", "piiElements", "dataSubjectCategories"));
        WizardState.Gov g = new WizardState.Gov();
        g.description = Fields.opt(m, "description", p);
        g.element = Fields.opt(m, "systemDataElement", p);
        String att = Fields.nullable(m, "sensitiveDataAttestation", p);
        if (att != null) {
            g.attestation = Enums.parse(Attestation.class, att).orElseThrow(() ->
                    new IllegalArgumentException(p + ".sensitiveDataAttestation: must be Yes or No"));
        }
        int i = 0;
        for (Object o : Fields.arr(m.get("piiElements"), p + ".piiElements")) {
            if (o instanceof String s) {
                g.pii.add(s);
            } else if (o instanceof Map<?, ?> pm && pm.get("name") instanceof String n) {
                g.pii.add(n);
            } else {
                throw new IllegalArgumentException(p + ".piiElements[" + i + "]: expected a name or {\"name\": ...}");
            }
            i++;
        }
        for (String c : Fields.strings(m.get("dataSubjectCategories"), p + ".dataSubjectCategories")) {
            g.categories.add(Enums.parse(DataSubjectCategory.class, c).orElseThrow(() ->
                    new IllegalArgumentException(p + ".dataSubjectCategories: '" + c + "' is not an allowed value")));
        }
        if (lineage && m.get("sourcedFromColumns") != null) {
            g.lineageJson = Json.writeCompact(m.get("sourcedFromColumns"));
        }
        return g;
    }
}

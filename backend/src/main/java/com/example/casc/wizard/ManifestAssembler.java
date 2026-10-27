package com.example.casc.wizard;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.CasCManifest.Column;
import com.example.casc.domain.CasCManifest.DataEntity;
import com.example.casc.domain.CasCManifest.Database;
import com.example.casc.domain.CasCManifest.EntityElementRef;
import com.example.casc.domain.CasCManifest.Interface;
import com.example.casc.domain.CasCManifest.InterfaceDataElement;
import com.example.casc.domain.CasCManifest.LogicalService;
import com.example.casc.domain.CasCManifest.PiiElement;
import com.example.casc.domain.CasCManifest.Schema;
import com.example.casc.domain.CasCManifest.SystemDataElement;
import com.example.casc.domain.CasCManifest.Table;
import com.example.casc.domain.CasCManifest.Transformation;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the manifest from the user's decisions and the extracted bundle. Only what the user included and decided
 * is written. Nothing is guessed: unset attestations stay unset, interfaces without a direction are left out (and
 * reported by the Reconciler), and the system EON ID and name are never written.
 */
public final class ManifestAssembler {
    private ManifestAssembler() {}

    /** findings are problems found while assembling, for example malformed lineage JSON. */
    public record Assembly(CasCManifest manifest, List<Finding> findings) {}

    private static <T> List<T> orNull(List<T> l) {
        return l.isEmpty() ? null : l;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static String wantedRole(InterfaceType direction) {
        return direction == InterfaceType.DISTRIBUTION ? "response" : "request";
    }

    public static Assembly assemble(WizardState st, RawMetadataBundle bundle) {
        List<Finding> findings = new ArrayList<>();
        List<LogicalService> services = new ArrayList<>();
        for (WizardState.Service s : st.services) {
            if (!s.name.isBlank()) {
                services.add(new LogicalService(s.name, s.onPrem, blankToNull(s.cloudProvider), blankToNull(s.systemKey)));
            }
        }
        List<SystemDataElement> elements = new ArrayList<>();
        for (WizardState.Element e : st.elements) {
            if (!e.name.isBlank()) {
                elements.add(new SystemDataElement(e.name, orNull(e.services), blankToNull(e.systemKey), blankToNull(e.description),
                        blankToNull(e.concept), blankToNull(e.domain)));
            }
        }
        List<Database> databases = null;
        List<Interface> interfaces = new ArrayList<>();
        if (bundle != null) {
            databases = databases(st, bundle, findings);
            for (RawInterface ri : bundle.interfaces()) {
                String key = AssetKeys.iface(ri);
                WizardState.Asset a = st.assets.get(key);
                if (a == null) {
                    a = new WizardState.Asset();
                }
                if (!a.included) {
                    continue;
                }
                if (a.direction == null) {
                    continue;
                }
                List<InterfaceDataElement> els = new ArrayList<>();
                for (RawField f : ri.fields()) {
                    if (!wantedRole(a.direction).equals(f.role()) || a.excludedFields.contains(f.path())) {
                        continue;
                    }
                    WizardState.Gov g = st.fields.get(AssetKeys.column(key, f.path()));
                    els.add(new InterfaceDataElement(f.path(), g == null ? null : blankToNull(g.element),
                            null, g != null && !g.description.isBlank() ? g.description : f.description(),
                            g == null ? null : g.attestation, g == null ? null : pii(g), g == null ? null : categories(g)));
                }
                interfaces.add(new Interface(ri.name(), a.direction, orNull(a.services), blankToNull(a.systemKey), orNull(els)));
            }
        }
        List<DataEntity> entities = new ArrayList<>();
        for (WizardState.Entity e : st.entities) {
            if (e.name.isBlank()) {
                continue;
            }
            List<EntityElementRef> refs = new ArrayList<>();
            for (String n : e.elements) {
                refs.add(new EntityElementRef(n));
            }
            entities.add(new DataEntity(e.name, orNull(e.services), blankToNull(e.systemKey), blankToNull(e.description), orNull(refs)));
        }
        List<Transformation> transformations = new ArrayList<>();
        for (WizardState.Transform t : st.transformations) {
            if (!t.name.isBlank()) {
                transformations.add(new Transformation(t.name, blankToNull(t.systemKey), blankToNull(t.description),
                        orNull(t.sources), orNull(t.targets)));
            }
        }
        CasCManifest m = new CasCManifest(orNull(services), orNull(elements), orNull(interfaces), orNull(entities), databases,
                orNull(transformations));
        return new Assembly(m, findings);
    }

    private static List<PiiElement> pii(WizardState.Gov g) {
        List<PiiElement> out = new ArrayList<>();
        for (String n : g.pii) {
            if (!n.isBlank()) {
                out.add(new PiiElement(n));
            }
        }
        return orNull(out);
    }

    private static List<DataSubjectCategory> categories(WizardState.Gov g) {
        return orNull(new ArrayList<>(g.categories));
    }

    private static List<Database> databases(WizardState st, RawMetadataBundle bundle, List<Finding> findings) {
        Map<String, List<Table>> bySchema = new LinkedHashMap<>();
        for (RawTable t : bundle.tables()) {
            if (!t.kind().equals("TABLE")) {
                continue;
            }
            String key = AssetKeys.table(t);
            WizardState.Asset a = st.assets.get(key);
            if (a != null && !a.included) {
                continue;
            }
            List<Column> cols = new ArrayList<>();
            for (RawColumn c : t.columns()) {
                String ck = AssetKeys.column(key, c.name());
                WizardState.Gov g = st.columns.get(ck);
                List<Map<String, Object>> lineage = null;
                if (g != null && !g.lineageJson.isBlank()) {
                    lineage = lineage(g.lineageJson, t.name() + "." + c.name(), findings);
                }
                cols.add(new Column(c.name(), g == null ? null : blankToNull(g.element), null,
                        g != null && !g.description.isBlank() ? g.description : c.description(),
                        g == null ? null : g.attestation, g == null ? null : pii(g), g == null ? null : categories(g), lineage));
            }
            String schema = t.schema() != null ? t.schema() : st.system.defaultSchema;
            bySchema.computeIfAbsent(schema, k -> new ArrayList<>())
                    .add(new Table(t.name(), a == null ? null : a.tableType, null, t.description(), cols));
        }
        if (bySchema.isEmpty()) {
            return null;
        }
        List<Schema> schemas = new ArrayList<>();
        for (Map.Entry<String, List<Table>> e : bySchema.entrySet()) {
            schemas.add(new Schema(e.getKey(), null, e.getValue()));
        }
        return List.of(new Database(st.system.databaseLogicalName, st.system.databaseType, null, schemas));
    }

    /** Accepts one JSON object or an array of objects; anything else is reported and dropped visibly. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lineage(String json, String where, List<Finding> findings) {
        try {
            Object v = Json.parse(json);
            List<Map<String, Object>> out = new ArrayList<>();
            if (v instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            } else if (v instanceof List<?> l) {
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> om)) {
                        throw new JsonException("every item must be a JSON object");
                    }
                    out.add((Map<String, Object>) om);
                }
            } else {
                throw new JsonException("expected a JSON object or an array of objects");
            }
            return out;
        } catch (JsonException e) {
            findings.add(Finding.error("LINEAGE_JSON_INVALID", where + ".sourcedFromColumns",
                    "The lineage JSON entered for this column is not usable: " + e.getMessage(),
                    "Fix the JSON or clear the field. It is passed through unverified once it is valid JSON."));
            return null;
        }
    }
}

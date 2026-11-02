package com.example.casc.validation;

import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.CasCManifest.*;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local (provisional-profile) validation. Official-schema validation is NOT performed here.
 * The validator only reports; it never modifies or removes data.
 */
public final class CasCValidatorService {

    public ValidationReport validate(CasCManifest m) {
        return validate(m, List.of());
    }

    public ValidationReport validate(CasCManifest m, List<String> unresolvedDecisions) {
        Run r = new Run(m);
        r.run();
        for (String d : unresolvedDecisions) {
            r.out.add(Finding.warning("UNRESOLVED_DECISION", "decisions", d,
                    "Make this decision and record it in the manifest, or explicitly acknowledge it where the tool allows. Details: docs/INTEGRATION.md."));
        }
        return new ValidationReport(r.out);
    }

    private static final class Run {
        final CasCManifest m;
        final List<Finding> out = new ArrayList<>();
        final Set<String> services = new HashSet<>();
        final Set<String> elements = new HashSet<>();
        final Map<String, String> keys = new HashMap<>();

        Run(CasCManifest m) {
            this.m = m;
        }

        static <T> List<T> nz(List<T> l) {
            return l == null ? List.of() : l;
        }

        static boolean blank(String s) {
            return s == null || s.isBlank();
        }

        void required(String v, String path, String what) {
            if (blank(v)) {
                out.add(Finding.error("REQ_BLANK", path, what + " is required, but it is missing, empty or only spaces.",
                        "Fill in " + what + " at this path with a real value (not blank)."));
            }
        }

        void duplicate(Set<String> seen, String v, String path, String scope) {
            if (!blank(v) && !seen.add(v)) {
                out.add(Finding.error("DUP_NAME", path, "The name '" + v + "' appears more than once in this " + scope + ". Names must be unique inside a " + scope + " so that references are not ambiguous.",
                        "Rename or remove one of the entries called '" + v + "' in this " + scope + "."));
            }
        }

        void systemKey(String k, String path) {
            if (k == null) {
                return;
            }
            if (k.isBlank()) {
                out.add(Finding.warning("KEY_BLANK", path, "systemKey is present but empty, so it cannot identify anything.",
                        "Give systemKey a value, or delete the systemKey field."));
                return;
            }
            String prev = keys.putIfAbsent(k, path);
            if (prev != null) {
                out.add(Finding.error("KEY_DUP", path,
                        "systemKey '" + k + "' is already used at " + prev + ". In this tool's provisional rules every systemKey must be unique across the whole manifest.",
                        "Choose a different systemKey for this item or for " + prev + "."));
            }
        }

        void refs(List<String> l, Set<String> known, String path, String code, String what) {
            List<String> items = nz(l);
            for (int i = 0; i < items.size(); i++) {
                String v = items.get(i);
                String p = path + "[" + i + "]";
                if (blank(v)) {
                    out.add(Finding.error("REQ_BLANK", p, "This reference is blank. A reference must name a " + what + " that is declared in the manifest.", "Delete this entry, or write the name of a declared " + what + "."));
                } else if (!known.contains(v)) {
                    out.add(Finding.error(code, p, "'" + v + "' does not match any " + what + " declared in this manifest. Names must match exactly, including upper and lower case.",
                            "Add a " + what + " called '" + v + "' to the manifest, or correct this name so it matches a declared one."));
                }
            }
        }

        void ref(String v, Set<String> known, String path, String code, String what) {
            if (v != null && !known.contains(v)) {
                out.add(Finding.error(code, path, "'" + v + "' does not match any " + what + " declared in this manifest. Names must match exactly, including upper and lower case.",
                        "Add a " + what + " called '" + v + "' to the manifest, or correct this name so it matches a declared one."));
            }
        }

        void unqualified(String v, String path, String what) {
            if (v != null && v.contains(".")) {
                out.add(Finding.error("NAME_QUALIFIED", path, what + " '" + v + "' contains a period. Schema, table and column names must be unqualified: no database or schema prefix.",
                        "Keep only the " + what + " itself, for example the part after the last period of '" + v + "'."));
            }
        }

        void pii(Attestation att, List<PiiElement> pii, List<DataSubjectCategory> cats, String path) {
            List<PiiElement> p = nz(pii);
            if (att == null) {
                out.add(Finding.warning("ATTEST_MISSING", path + ".sensitiveDataAttestation",
                        "This column or field has no sensitive-data attestation. Each one needs a reviewed answer (Yes or No), and this tool never decides it for you.",
                        "After review, add sensitiveDataAttestation: Yes or No here. If Yes, also list piiElements and dataSubjectCategories."));
            }
            if (att == Attestation.YES && p.isEmpty()) {
                out.add(Finding.error("PII_REQUIRED", path + ".piiElements",
                        "The attestation is Yes (sensitive data), but no PII elements are listed.", "List at least one entry in piiElements, or change the attestation to No if the review shows no sensitive data."));
            }
            if (att != Attestation.YES && !p.isEmpty()) {
                out.add(Finding.error("PII_CONFLICT", path + ".sensitiveDataAttestation",
                        "PII elements are listed but the attestation is " + (att == null ? "missing" : "No") + ", which contradicts them.",
                        "If the data is sensitive, set sensitiveDataAttestation to Yes. If it is not, remove the PII elements. Decide after review; the tool cannot decide."));
            }
            for (int i = 0; i < p.size(); i++) {
                required(p.get(i).name(), path + ".piiElements[" + i + "].name", "PII element name");
            }
            if (!p.isEmpty() && nz(cats).isEmpty()) {
                out.add(Finding.error("PII_CATEGORY_REQUIRED", path + ".dataSubjectCategories",
                        "PII elements are present but no data subject category says whose data it is.",
                        "Add dataSubjectCategories with at least one of: Client, Worker, Third Party."));
            }
        }

        void run() {
            for (LogicalService s : nz(m.logicalServices())) {
                if (!blank(s.name())) {
                    services.add(s.name());
                }
            }
            for (SystemDataElement e : nz(m.systemDataElements())) {
                if (!blank(e.name())) {
                    elements.add(e.name());
                }
            }
            logicalServices();
            systemDataElements();
            interfaces();
            dataEntities();
            databases();
            transformations();
        }

        void logicalServices() {
            Set<String> seen = new HashSet<>();
            List<LogicalService> l = nz(m.logicalServices());
            for (int i = 0; i < l.size(); i++) {
                LogicalService s = l.get(i);
                String p = "logicalServices[" + i + "]";
                required(s.name(), p + ".name", "Logical service name");
                duplicate(seen, s.name(), p + ".name", "manifest's logical services");
                if (s.isDeployedOnPrem() == null) {
                    out.add(Finding.error("BOOL_MISSING", p + ".isDeployedOnPrem",
                            "This logical service does not say whether it is deployed on premises. A missing value is not treated as false.",
                            "Add isDeployedOnPrem: true (on premises) or false (cloud)."));
                } else if (!s.isDeployedOnPrem() && blank(s.cloudProvider())) {
                    out.add(Finding.error("CLOUD_REQUIRED", p + ".cloudProvider",
                            "The service is not deployed on premises, so it must name its cloud provider.",
                            "Add cloudProvider with the provider name, or set isDeployedOnPrem to true if the service is on premises."));
                } else if (s.isDeployedOnPrem() && !blank(s.cloudProvider())) {
                    out.add(Finding.warning("CLOUD_UNEXPECTED", p + ".cloudProvider",
                            "cloudProvider is set, but the service is marked as deployed on premises. These two contradict each other.",
                            "Remove cloudProvider, or set isDeployedOnPrem to false if the service really is in the cloud."));
                }
                systemKey(s.systemKey(), p + ".systemKey");
            }
        }

        void systemDataElements() {
            Set<String> seen = new HashSet<>();
            List<SystemDataElement> l = nz(m.systemDataElements());
            for (int i = 0; i < l.size(); i++) {
                SystemDataElement e = l.get(i);
                String p = "systemDataElements[" + i + "]";
                required(e.name(), p + ".name", "System data element name");
                duplicate(seen, e.name(), p + ".name", "system's data elements");
                refs(e.logicalServices(), services, p + ".logicalServices", "REF_LOGICAL_SERVICE", "logical service");
                systemKey(e.systemKey(), p + ".systemKey");
            }
        }

        void interfaces() {
            Set<String> seen = new HashSet<>();
            List<Interface> l = nz(m.interfaces());
            for (int i = 0; i < l.size(); i++) {
                Interface x = l.get(i);
                String p = "interfaces[" + i + "]";
                required(x.name(), p + ".name", "Interface name");
                duplicate(seen, x.name(), p + ".name", "manifest's interfaces");
                if (x.type() == null) {
                    out.add(Finding.error("REQ_MISSING", p + ".type", "This interface has no type. The type says in which direction the data flows.",
                            "Add type: SOURCING (this system consumes the data) or DISTRIBUTION (this system provides the data)."));
                }
                refs(x.logicalServices(), services, p + ".logicalServices", "REF_LOGICAL_SERVICE", "logical service");
                systemKey(x.systemKey(), p + ".systemKey");
                Set<String> names = new HashSet<>();
                List<InterfaceDataElement> els = nz(x.interfaceDataElements());
                for (int j = 0; j < els.size(); j++) {
                    InterfaceDataElement e = els.get(j);
                    String ep = p + ".interfaceDataElements[" + j + "]";
                    required(e.physicalName(), ep + ".physicalName", "Physical name or path");
                    duplicate(names, e.physicalName(), ep + ".physicalName", "interface");
                    ref(e.systemDataElement(), elements, ep + ".systemDataElement", "REF_ELEMENT", "system data element");
                    systemKey(e.systemKey(), ep + ".systemKey");
                    pii(e.sensitiveDataAttestation(), e.piiElements(), e.dataSubjectCategories(), ep);
                }
            }
        }

        void dataEntities() {
            Set<String> seen = new HashSet<>();
            List<DataEntity> l = nz(m.dataEntities());
            for (int i = 0; i < l.size(); i++) {
                DataEntity e = l.get(i);
                String p = "dataEntities[" + i + "]";
                required(e.name(), p + ".name", "Data entity name");
                duplicate(seen, e.name(), p + ".name", "manifest's data entities");
                refs(e.logicalServices(), services, p + ".logicalServices", "REF_LOGICAL_SERVICE", "logical service");
                systemKey(e.systemKey(), p + ".systemKey");
                Set<String> names = new HashSet<>();
                List<EntityElementRef> refs = nz(e.systemDataElements());
                for (int j = 0; j < refs.size(); j++) {
                    String rp = p + ".systemDataElements[" + j + "].name";
                    String n = refs.get(j).name();
                    required(n, rp, "System data element name");
                    duplicate(names, n, rp, "data entity");
                    if (!blank(n)) {
                        ref(n, elements, rp, "REF_ELEMENT", "system data element");
                    }
                }
            }
        }

        void databases() {
            Set<String> seen = new HashSet<>();
            List<Database> l = nz(m.databases());
            for (int i = 0; i < l.size(); i++) {
                Database d = l.get(i);
                String p = "databases[" + i + "]";
                required(d.logicalName(), p + ".logicalName", "Database logical name");
                duplicate(seen, d.logicalName(), p + ".logicalName", "manifest's databases");
                if (d.logicalName() != null && d.logicalName().chars().anyMatch(Character::isWhitespace)) {
                    out.add(Finding.warning("DB_NAME_PROVISIONAL", p + ".logicalName",
                            "The logical database name contains a space. The naming rules for TAI logical names are not confirmed in this tool, so it may be rejected later.",
                            "Check the name against the official CasC specification and remove the space if the rules forbid it."));
                }
                systemKey(d.systemKey(), p + ".systemKey");
                schemas(d, p);
            }
        }

        void schemas(Database d, String path) {
            Set<String> seen = new HashSet<>();
            List<Schema> l = nz(d.schemas());
            for (int i = 0; i < l.size(); i++) {
                Schema s = l.get(i);
                String p = path + ".schemas[" + i + "]";
                required(s.physicalName(), p + ".physicalName", "Schema name");
                unqualified(s.physicalName(), p + ".physicalName", "schema name");
                duplicate(seen, s.physicalName(), p + ".physicalName", "database");
                systemKey(s.systemKey(), p + ".systemKey");
                tables(s, p);
            }
        }

        void tables(Schema s, String path) {
            Set<String> seen = new HashSet<>();
            List<Table> l = nz(s.tables());
            for (int i = 0; i < l.size(); i++) {
                Table t = l.get(i);
                String p = path + ".tables[" + i + "]";
                required(t.physicalName(), p + ".physicalName", "Table name");
                unqualified(t.physicalName(), p + ".physicalName", "table name");
                duplicate(seen, t.physicalName(), p + ".physicalName", "schema");
                systemKey(t.systemKey(), p + ".systemKey");
                columns(t, p);
            }
        }

        void columns(Table t, String path) {
            Set<String> seen = new HashSet<>();
            List<Column> l = nz(t.columns());
            for (int i = 0; i < l.size(); i++) {
                Column c = l.get(i);
                String p = path + ".columns[" + i + "]";
                required(c.physicalName(), p + ".physicalName", "Column name");
                unqualified(c.physicalName(), p + ".physicalName", "column name");
                duplicate(seen, c.physicalName(), p + ".physicalName", "table");
                ref(c.systemDataElement(), elements, p + ".systemDataElement", "REF_ELEMENT", "system data element");
                systemKey(c.systemKey(), p + ".systemKey");
                pii(c.sensitiveDataAttestation(), c.piiElements(), c.dataSubjectCategories(), p);
                if (c.sourcedFromColumns() != null && !c.sourcedFromColumns().isEmpty()) {
                    out.add(Finding.info("LINEAGE_UNVERIFIED", p + ".sourcedFromColumns",
                            "sourcedFromColumns is copied through exactly as written. This tool does not check its structure or references because the official shape is not confirmed.",
                            "Check this entry against the official CasC specification yourself."));
                }
            }
        }

        void transformations() {
            Set<String> seen = new HashSet<>();
            List<Transformation> l = nz(m.transformations());
            for (int i = 0; i < l.size(); i++) {
                Transformation t = l.get(i);
                String p = "transformations[" + i + "]";
                required(t.name(), p + ".name", "Transformation name");
                duplicate(seen, t.name(), p + ".name", "manifest's transformations");
                systemKey(t.systemKey(), p + ".systemKey");
                refs(t.sourceDataElements(), elements, p + ".sourceDataElements", "REF_ELEMENT", "system data element");
                refs(t.targetDataElements(), elements, p + ".targetDataElements", "REF_ELEMENT", "system data element");
            }
        }
    }
}

package com.example.casc.wizard;

import com.example.casc.domain.CasCManifest;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.validation.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the decisions that are still open. A decision is either something the user may explicitly acknowledge
 * (acknowledgeable) or something that must be fixed. Open decisions are reported as UNRESOLVED_DECISION warnings and
 * listed separately from validation errors and warnings. Nothing here changes the manifest.
 */
public final class Reconciler {
    private Reconciler() {}

    public record Decision(String id, String message, boolean acknowledgeable) {}

    public static List<Decision> decisions(WizardState st, RawMetadataBundle bundle, CasCManifest manifest) {
        List<Decision> out = new ArrayList<>();
        if (st.sources.isEmpty() && st.failedSources.isEmpty()) {
            out.add(new Decision("no-sources", "No sources have been added in step 1.", false));
        }
        if (bundle != null) {
            long errors = bundle.findings().stream().filter(f -> f.severity() == Severity.ERROR).count();
            if (errors > 0) {
                out.add(new Decision("source-errors", errors + " source problem(s) were reported in step 1; the affected sources "
                        + "contributed nothing to the manifest.", false));
            }
            tableConflicts(st, bundle, out);
            for (RawInterface ri : bundle.interfaces()) {
                WizardState.Asset a = st.assets.get(AssetKeys.iface(ri));
                if ((a == null || a.included) && (a == null || a.direction == null)) {
                    out.add(new Decision("interface-direction:" + AssetKeys.iface(ri), "Interface '" + ri.name()
                            + "' has no SOURCING or DISTRIBUTION direction and is not in the manifest.", false));
                }
            }
            int noType = 0;
            for (RawTable t : bundle.tables()) {
                WizardState.Asset a = st.assets.get(AssetKeys.table(t));
                if (t.kind().equals("TABLE") && (a == null || a.included) && (a == null || a.tableType == null)) {
                    noType++;
                }
            }
            if (noType > 0) {
                out.add(new Decision("no-table-type", noType + " included table(s) have no Transaction or Reference type.", true));
            }
            Set<String> elementNames = new HashSet<>();
            st.elementNames().forEach(n -> elementNames.add(n.toLowerCase(Locale.ROOT)));
            long unusedTerms = bundle.terms().stream().filter(t -> !elementNames.contains(t.name().toLowerCase(Locale.ROOT))).count();
            if (unusedTerms > 0) {
                out.add(new Decision("terms-unused", unusedTerms + " candidate term(s) were not turned into logical data elements.", true));
            }
            Set<String> entityNames = new HashSet<>();
            st.entities.forEach(e -> entityNames.add(e.name.toLowerCase(Locale.ROOT)));
            long unusedStructures = bundle.structures().stream().filter(s -> !entityNames.contains(s.name().toLowerCase(Locale.ROOT))).count();
            if (unusedStructures > 0) {
                out.add(new Decision("structures-unused", unusedStructures + " candidate structure(s) were not turned into data entities.", true));
            }
            long lineageNotes = bundle.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).count() + bundle.lineage().size();
            boolean anyLineageJson = st.columns.values().stream().anyMatch(g -> !g.lineageJson.isBlank());
            if (lineageNotes > 0 && !anyLineageJson) {
                out.add(new Decision("lineage-notes", lineageNotes + " lineage hint(s) or statement(s) were found and are not written to the "
                        + "manifest (the official sourcedFromColumns shape is unconfirmed).", true));
            }
        }
        out.add(new Decision("unreviewed-draft", "The manifest was assembled from automatically extracted metadata. Confirm that the "
                + "tables, columns, descriptions and interfaces were reviewed.", true));
        if (st.system.eonId.isBlank() || st.system.name.isBlank()) {
            out.add(new Decision("system-context", "The system EON ID and/or name are not set. They are kept outside the manifest until "
                    + "their official placement is confirmed.", true));
        }
        if (st.serviceNames().isEmpty()) {
            out.add(new Decision("no-services", "No logical services are defined.", true));
        }
        attestations(manifest, out);
        return out;
    }

    private static void tableConflicts(WizardState st, RawMetadataBundle bundle, List<Decision> out) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RawTable t : bundle.tables()) {
            WizardState.Asset a = st.assets.get(AssetKeys.table(t));
            if (t.kind().equals("TABLE") && (a == null || a.included)) {
                counts.merge(AssetKeys.qualified(t), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                out.add(new Decision("table-conflict:" + e.getKey(), "Table '" + e.getKey() + "' is defined by " + e.getValue()
                        + " included sources. Exclude all but one in step 3.", false));
            }
        }
    }

    private static void attestations(CasCManifest m, List<Decision> out) {
        if (m == null) {
            return;
        }
        int missing = 0;
        int hinted = 0;
        if (m.databases() != null) {
            for (var db : m.databases()) {
                for (var s : db.schemas()) {
                    for (var t : s.tables()) {
                        for (var c : t.columns()) {
                            if (c.sensitiveDataAttestation() == null) {
                                missing++;
                                if (PiiHints.hint(c.physicalName()) != null) {
                                    hinted++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (m.interfaces() != null) {
            for (var i : m.interfaces()) {
                if (i.interfaceDataElements() == null) {
                    continue;
                }
                for (var e : i.interfaceDataElements()) {
                    if (e.sensitiveDataAttestation() == null) {
                        missing++;
                        if (PiiHints.hint(e.physicalName()) != null) {
                            hinted++;
                        }
                    }
                }
            }
        }
        if (hinted > 0) {
            out.add(new Decision("attest-hint", hinted + " column(s) or field(s) have names that resemble sensitive data and no "
                    + "attestation decision. Set Yes or No for each; hints never decide this for you.", false));
        }
        if (missing - hinted > 0) {
            out.add(new Decision("attest-missing", (missing - hinted) + " other column(s) or field(s) have no sensitive-data "
                    + "attestation (the validator lists each one).", true));
        }
    }

    /** Messages of the decisions that are still open after the user's acknowledgements. */
    public static List<String> open(List<Decision> decisions, WizardState st) {
        List<String> out = new ArrayList<>();
        for (Decision d : decisions) {
            if (!d.acknowledgeable() || !st.acknowledged.contains(d.id())) {
                out.add(d.message());
            }
        }
        return out;
    }
}

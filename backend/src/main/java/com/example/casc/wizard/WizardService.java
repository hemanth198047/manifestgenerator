package com.example.casc.wizard;

import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceInput;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Operations on a WizardState shared by the web wizard and tests. Callers synchronize on the state. */
public final class WizardService {
    private final DraftPipeline pipeline;
    private final ExportService export = ExportService.standard();

    public WizardService(DraftPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public ExportService export() {
        return export;
    }

    /** Re-reads the sources with the current options. Decisions for assets that no longer exist are removed and counted. */
    public int ingest(WizardState st) {
        List<SourceInput> all = new ArrayList<>(st.sources);
        all.addAll(st.failedSources);
        IngestOptions options = IngestOptions.fromMap(st.options);
        st.bundle = pipeline.ingest(all, options);
        Set<String> live = new HashSet<>();
        for (RawTable t : st.bundle.tables()) {
            live.add(AssetKeys.table(t));
        }
        for (RawInterface i : st.bundle.interfaces()) {
            live.add(AssetKeys.iface(i));
        }
        int dropped = 0;
        for (var it = st.assets.keySet().iterator(); it.hasNext();) {
            if (!live.contains(it.next())) {
                it.remove();
                dropped++;
            }
        }
        for (var map : List.of(st.columns, st.fields)) {
            for (var it = map.keySet().iterator(); it.hasNext();) {
                String k = it.next();
                if (live.stream().noneMatch(l -> k.startsWith(l + "#"))) {
                    it.remove();
                    dropped++;
                }
            }
        }
        return dropped;
    }

    public ManifestAssembler.Assembly assemble(WizardState st) {
        return ManifestAssembler.assemble(st, st.bundle);
    }

    public record Review(ManifestAssembler.Assembly assembly, List<Reconciler.Decision> decisions, ExportResult result) {}

    public Review review(WizardState st, String format) throws CapabilityUnavailableException {
        ManifestAssembler.Assembly a = assemble(st);
        List<Reconciler.Decision> decisions = Reconciler.decisions(st, st.bundle, a.manifest());
        ExportResult r = export.export(new ReadResult(a.manifest(), a.findings()), Reconciler.open(decisions, st), format);
        return new Review(a, decisions, r);
    }

    /** Fills in missing system keys for services, elements, interfaces, entities and transformations. Returns how many were set. */
    public int generateKeys(WizardState st) {
        Set<String> taken = new HashSet<>();
        for (var s : st.services) addIfSet(taken, s.systemKey);
        for (var e : st.elements) addIfSet(taken, e.systemKey);
        for (var e : st.entities) addIfSet(taken, e.systemKey);
        for (var t : st.transformations) addIfSet(taken, t.systemKey);
        for (var a : st.assets.values()) addIfSet(taken, a.systemKey);
        int n = 0;
        for (var s : st.services) {
            if (!s.name.isBlank() && s.systemKey.isBlank()) { s.systemKey = SystemKeys.generate("svc", s.name, taken); n++; }
        }
        for (var e : st.elements) {
            if (!e.name.isBlank() && e.systemKey.isBlank()) { e.systemKey = SystemKeys.generate("elem", e.name, taken); n++; }
        }
        for (var e : st.entities) {
            if (!e.name.isBlank() && e.systemKey.isBlank()) { e.systemKey = SystemKeys.generate("ent", e.name, taken); n++; }
        }
        for (var t : st.transformations) {
            if (!t.name.isBlank() && t.systemKey.isBlank()) { t.systemKey = SystemKeys.generate("xform", t.name, taken); n++; }
        }
        if (st.bundle != null) {
            for (RawInterface ri : st.bundle.interfaces()) {
                WizardState.Asset a = st.asset(AssetKeys.iface(ri));
                if (a.included && a.systemKey.isBlank()) { a.systemKey = SystemKeys.generate("intf", ri.name(), taken); n++; }
            }
        }
        return n;
    }

    private static void addIfSet(Set<String> taken, String key) {
        if (key != null && !key.isBlank()) {
            taken.add(key);
        }
    }
}

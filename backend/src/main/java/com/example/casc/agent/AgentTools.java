package com.example.casc.agent;

import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.ingest.IngestRequest;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceInput;
import com.example.casc.manifest.ManifestMapper;
import com.example.casc.net.UrlFetcher;
import com.example.casc.wizard.Reconciler;
import com.example.casc.wizard.WizardService;
import com.example.casc.wizard.WizardState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three tool contracts for agents and automation. They are ordinary JSON-in, JSON-out calls over the same engines
 * as the UI and CLI. They are NOT an MCP server. Requests carry inline text only: there is no field through which a
 * caller can make the application read an arbitrary file, and URLs are fetched only under the configured allowlist.
 * Requests are strict: an unknown field is rejected, never ignored.
 */
public final class AgentTools {
    public static final String INGEST = "ingest_sources";
    public static final String VALIDATE = "validate_manifest_payload";
    public static final String GENERATE = "generate_casc_manifest";

    public record Descriptor(String name, String description, List<String> required, List<String> optional) {
        public Map<String, Object> toTree() {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", name);
            t.put("description", description);
            t.put("required", new ArrayList<Object>(required));
            t.put("optional", new ArrayList<Object>(optional));
            return t;
        }
    }

    private final DraftPipeline pipeline;
    private final WizardService wizard;

    public AgentTools(UrlFetcher fetcher) {
        this.pipeline = new DraftPipeline(fetcher);
        this.wizard = new WizardService(pipeline);
    }

    public static List<Descriptor> descriptors() {
        return List.of(
                new Descriptor(INGEST, "Extract tables, columns, terms, interface candidates and findings from inline sources (and allowlisted URLs). "
                        + "Returns the bundle to pass on as parsedMetadata.", List.of("sources"), List.of("urls", "options")),
                new Descriptor(VALIDATE, "Check a CasC manifest object with the local validation rules. Returns findings with paths and corrections. "
                        + "Level reached: LOCAL (not the official schema, not FDC acceptance).", List.of("manifest"), List.of()),
                new Descriptor(GENERATE, "Build an UNREVIEWED manifest from parsedMetadata (the ingest_sources result) and the user's decisions. "
                        + "Returns the content, the validation report and the decisions still open.", List.of("parsedMetadata"), List.of("userInputs", "format")));
    }

    public Map<String, Object> call(String tool, Object request) throws ToolException {
        try {
            return switch (tool) {
                case INGEST -> ingest(request);
                case VALIDATE -> validate(request);
                case GENERATE -> generate(request);
                default -> throw new ToolException("UNKNOWN_TOOL", "Unknown tool '" + tool + "'. Available: " + INGEST + ", " + VALIDATE + ", " + GENERATE + ".");
            };
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_REQUEST", e.getMessage());
        } catch (CapabilityUnavailableException e) {
            throw new ToolException("UNAVAILABLE_FORMAT", e.getMessage());
        }
    }

    private Map<String, Object> ingest(Object request) {
        Map<String, Object> m = Fields.obj(request, "request");
        Fields.only(m, "request", Set.of("sources", "urls", "options"));
        int i = 0;
        for (Object o : Fields.arr(m.get("sources"), "request.sources")) {
            String p = "request.sources[" + i++ + "]";
            Fields.only(Fields.obj(o, p), p, Set.of("name", "text", "contentBase64"));
        }
        IngestRequest r = IngestRequest.fromTree(m);
        List<SourceInput> all = new ArrayList<>(r.sources());
        all.addAll(pipeline.fetch(r.urls()));
        RawMetadataBundle bundle = pipeline.ingest(all, r.options());
        Map<String, Object> t = bundle.toTree();
        t.put("hasErrors", DraftPipeline.hasErrors(bundle));
        return t;
    }

    private Map<String, Object> validate(Object request) {
        Map<String, Object> m = Fields.obj(request, "request");
        Fields.only(m, "request", Set.of("manifest"));
        if (!m.containsKey("manifest")) {
            throw new IllegalArgumentException("request.manifest: required");
        }
        ManifestMapper.ReadResult in = ManifestMapper.fromTree(m.get("manifest"));
        Map<String, Object> t = new LinkedHashMap<>(wizardExport().validate(in, List.of()).toTree());
        t.put("validationLevel", ExportService.LEVEL);
        t.put("profile", profile());
        return t;
    }

    private ExportService wizardExport() {
        return wizard.export();
    }

    private static Map<String, Object> profile() {
        var p = com.example.casc.profile.SchemaProfile.provisional();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", p.name());
        o.put("version", p.version());
        o.put("confirmedAgainstOfficialSpec", p.confirmedAgainstOfficialSpec());
        return o;
    }

    private Map<String, Object> generate(Object request) throws CapabilityUnavailableException {
        Map<String, Object> m = Fields.obj(request, "request");
        Fields.only(m, "request", Set.of("parsedMetadata", "userInputs", "format"));
        if (!m.containsKey("parsedMetadata")) {
            throw new IllegalArgumentException("request.parsedMetadata: required (the result of ingest_sources)");
        }
        String format = Fields.nullable(m, "format", "request");
        RawMetadataBundle bundle = BundleReader.fromTree(m.get("parsedMetadata"));
        WizardState st = new WizardState();
        st.bundle = bundle;
        UserInputs.apply(st, bundle, m.get("userInputs"));
        WizardService.Review rv = wizard.review(st, format == null ? "json" : format);
        ExportResult r = rv.result();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("format", r.format());
        t.put("exportable", r.exportable());
        t.put("validationLevel", r.validationLevel());
        t.put("profile", profile());
        t.put("report", r.report().toTree());
        List<Object> open = new ArrayList<>();
        for (Reconciler.Decision d : rv.decisions()) {
            boolean ack = d.acknowledgeable() && st.acknowledged.contains(d.id());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", d.id());
            o.put("message", d.message());
            o.put("acknowledgeable", d.acknowledgeable());
            o.put("acknowledged", ack);
            open.add(o);
        }
        t.put("decisions", open);
        t.put("content", r.content());
        return t;
    }
}

package com.example.casc.app;

import com.example.casc.ingest.DraftBuilder;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceInput;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.net.UrlFetcher;
import com.example.casc.net.UrlSources;
import com.example.casc.validation.Finding;
import com.example.casc.validation.Severity;
import java.util.List;

/** ingest -> draft -> validate -> serialize, shared by the web UI, REST API and CLI. */
public final class DraftPipeline {
    private final SourceIngestionService ingestion = SourceIngestionService.standard();
    private final ExportService export = ExportService.standard();
    private final UrlFetcher fetcher;

    public DraftPipeline() {
        this(UrlFetcher.disabled());
    }

    public DraftPipeline(UrlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public UrlFetcher fetcher() {
        return fetcher;
    }

    /** Fetches URLs under the configured policy; failures come back as unreadable sources, never as silence. */
    public List<SourceInput> fetch(List<String> urls) {
        return urls.isEmpty() ? List.of() : UrlSources.fetchAll(urls, fetcher);
    }

    /** export is null when ingestion failed or the draft could not be built; the findings say why. */
    public record Outcome(RawMetadataBundle bundle, List<Finding> draftFindings, ExportResult export, List<String> unresolved) {}

    public SourceIngestionService ingestion() {
        return ingestion;
    }

    public RawMetadataBundle ingest(List<SourceInput> sources, IngestOptions options) {
        return ingestion.ingest(sources, options);
    }

    public static boolean hasErrors(RawMetadataBundle b) {
        return b.findings().stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    public Outcome draft(List<SourceInput> sources, IngestOptions options, DraftBuilder.SystemContext ctx, String format)
            throws CapabilityUnavailableException {
        RawMetadataBundle bundle = ingest(sources, options);
        if (hasErrors(bundle)) {
            return new Outcome(bundle, List.of(), null, List.of());
        }
        DraftBuilder.Result d = DraftBuilder.build(bundle, ctx);
        if (d.manifest() == null) {
            return new Outcome(bundle, d.findings(), null, List.of());
        }
        ExportResult result = export.export(new ReadResult(d.manifest(), List.of()), d.unresolved(), format);
        return new Outcome(bundle, d.findings(), result, d.unresolved());
    }
}

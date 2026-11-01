package com.example.casc.app;

import com.example.casc.app.Capability.Status;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SqlStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Single source of truth for what this build can and cannot do; shown in the UI, CLI and REST API. */
public final class CapabilityService {

    /** What this build can do right now: optional adapters that are installed switch their capabilities on. */
    public List<Capability> all() {
        SourceIngestionService svc = SourceIngestionService.standard();
        Set<String> ext = svc.supportedExtensions();
        Capability office = ext.contains("xlsx") || ext.contains("docx")
                ? new Capability("XLSX / XLS / DOCX ingestion", Status.AVAILABLE,
                        "Adapter " + svc.readerFor(ext.contains("xlsx") ? "xlsx" : "docx").id() + ": sheets and Word tables as dictionaries or data, Word headings, terms and lineage text. Macros are never run.")
                : new Capability("XLSX / DOCX ingestion", Status.UNAVAILABLE,
                        "Needs the Apache POI adapter (docs/ADAPTERS.md). Export each sheet to CSV or TSV and documents to text or Markdown instead.");
        Capability pdf = ext.contains("pdf")
                ? new Capability("PDF ingestion", Status.AVAILABLE,
                        "Adapter " + svc.readerFor("pdf").id() + ": text only. Tables are not reconstructed and scanned (image-only) PDFs have no text (no OCR); review required.")
                : new Capability("PDF ingestion", Status.UNAVAILABLE,
                        "Needs the Apache PDFBox adapter (docs/ADAPTERS.md). Provide a text export, and tables as CSV.");
        Capability html = ext.contains("html")
                ? new Capability("HTML / Confluence ingestion", Status.AVAILABLE,
                        "Adapter " + svc.readerFor("html").id() + ": headings, tables as dictionaries, terms and lineage text; Confluence page JSON is unwrapped. Pages are never rendered or scripted.")
                : new Capability("HTML / Confluence ingestion", Status.UNAVAILABLE,
                        "Needs the HTML adapter (docs/ADAPTERS.md). Confluence pages are HTML even through its JSON API; use a Markdown, text or CSV export instead.");
        int analyzers = SqlStrategy.analyzerCount();
        Capability dml = analyzers > 0
                ? new Capability("SQL DML lineage (table level)", Status.AVAILABLE,
                        "Adapter " + String.join(", ", SqlStrategy.analyzerIds()) + ": INSERT, UPDATE, MERGE and CREATE TABLE AS give table-to-table lineage hints. Column-level lineage is not inferred.")
                : new Capability("SQL DML and transformation lineage", Status.UNAVAILABLE,
                        "INSERT, UPDATE, MERGE and SELECT are reported but not analysed. Needs the JSqlParser adapter (docs/ADAPTERS.md).");
        List<Capability> all = new ArrayList<>(List.of(
                new Capability("Manifest import (JSON)", Status.AVAILABLE,
                        "Strict JSON parser built into the application."),
                new Capability("Manifest validation (local profile)", Status.AVAILABLE,
                        "Provisional rules only. Not validated against the official CasC schema."),
                new Capability("Manifest export: JSON", Status.AVAILABLE,
                        "Stable key order, LF line endings, UTF-8."),
                new Capability("Manifest export: YAML", Status.AVAILABLE,
                        "Narrow emitter for the known manifest structure only; cannot read YAML."),
                new Capability("Manifest import (YAML)", Status.UNAVAILABLE,
                        "Needs an approved YAML parser (for example Jackson YAML). Convert the file to JSON."),
                new Capability("Web UI (browser)", Status.AVAILABLE,
                        "Served by the JDK built-in HTTP server on a local address; no desktop session needed."),
                new Capability("Desktop UI (JavaFX)", Status.UNAVAILABLE,
                        "JavaFX is not part of the JDK. It needs OpenJFX modules and native libraries to be approved."),
                new Capability("CSV / TSV ingestion", Status.AVAILABLE,
                        "Quoted and multi-line fields, delimiter, header row and dictionary column mapping, sampling limit. Row values are not kept."),
                new Capability("TXT / Markdown ingestion", Status.AVAILABLE,
                        "Headings, dictionary tables, candidate terms and lineage text. Interface definitions are not detected."),
                new Capability("SQL ingestion (documented subset)", Status.AVAILABLE,
                        "CREATE TABLE/VIEW, ALTER TABLE ADD, COMMENT ON, PRIMARY KEY. Other statements are reported, never guessed."),
                dml,
                new Capability("Draft manifest from sources", Status.AVAILABLE,
                        "Databases, schemas, tables and columns only; services, elements, PII and attestations stay for review."),
                new Capability("OpenAPI / Swagger JSON ingestion", Status.AVAILABLE,
                        "Operations become candidate interfaces with field paths; schemas become candidate structures. Direction is never inferred."),
                new Capability("OpenAPI YAML ingestion", Status.UNAVAILABLE,
                        "Needs an approved YAML parser. Export the JSON form of the specification instead."),
                new Capability("URL sources", Status.AVAILABLE,
                        "JDK HttpClient. Blocked unless hosts are allowlisted (--url-allow or CASC_URL_ALLOW); https only by default; metadata and loopback addresses refused."),
                office,
                pdf,
                html,
                new Capability("Official CasC schema validation", Status.UNAVAILABLE,
                        "The official schema and an approved validator have not been supplied."),
                new Capability("FDC acceptance", Status.UNAVAILABLE,
                        "No evidence of ingestion by the target FDC service.")));
        List<String> problems = new ArrayList<>(svc.adapterProblems());
        problems.addAll(SqlStrategy.analyzerProblems());
        for (String problem : problems) {
            all.add(new Capability("Adapter problem", Status.UNAVAILABLE, problem));
        }
        return all;
    }
}

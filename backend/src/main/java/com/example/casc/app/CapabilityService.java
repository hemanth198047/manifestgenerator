package com.example.casc.app;

import com.example.casc.app.Capability.Status;
import java.util.List;

/** Single source of truth for what this build can and cannot do; shown in the UI, CLI and REST API. */
public final class CapabilityService {

    public List<Capability> all() {
        return List.of(
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
                new Capability("SQL DML and transformation lineage", Status.UNAVAILABLE,
                        "INSERT, UPDATE, MERGE and SELECT are reported but not analysed. Needs an approved SQL parser such as JSqlParser."),
                new Capability("Draft manifest from sources", Status.AVAILABLE,
                        "Databases, schemas, tables and columns only; services, elements, PII and attestations stay for review."),
                new Capability("OpenAPI / Swagger JSON ingestion", Status.AVAILABLE,
                        "Operations become candidate interfaces with field paths; schemas become candidate structures. Direction is never inferred."),
                new Capability("OpenAPI YAML ingestion", Status.UNAVAILABLE,
                        "Needs an approved YAML parser. Export the JSON form of the specification instead."),
                new Capability("URL sources", Status.AVAILABLE,
                        "JDK HttpClient. Blocked unless hosts are allowlisted (--url-allow or CASC_URL_ALLOW); https only by default; metadata and loopback addresses refused."),
                new Capability("XLSX / DOCX ingestion", Status.UNAVAILABLE,
                        "Needs Apache POI. Export the data to CSV, TSV or text instead."),
                new Capability("PDF ingestion", Status.UNAVAILABLE,
                        "Needs Apache PDFBox. Provide a text export instead."),
                new Capability("HTML / Confluence ingestion", Status.UNAVAILABLE,
                        "Needs an approved HTML parser (for example JSoup). Confluence pages are HTML even through its JSON API; use a Markdown, text or CSV export, or a plain-text endpoint."),
                new Capability("Official CasC schema validation", Status.UNAVAILABLE,
                        "The official schema and an approved validator have not been supplied."),
                new Capability("FDC acceptance", Status.UNAVAILABLE,
                        "No evidence of ingestion by the target FDC service."));
    }
}

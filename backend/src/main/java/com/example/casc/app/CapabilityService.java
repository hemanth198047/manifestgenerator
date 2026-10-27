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
                new Capability("CSV / TSV ingestion", Status.PLANNED, "Phase 3, JDK-only parser."),
                new Capability("SQL ingestion (documented subset)", Status.PLANNED,
                        "Phase 3, restricted parser; unsupported constructs will be reported, not guessed."),
                new Capability("TXT / Markdown ingestion", Status.PLANNED, "Phase 3."),
                new Capability("OpenAPI JSON ingestion", Status.PLANNED, "Phase 4, JDK HttpClient plus the built-in JSON parser."),
                new Capability("XLSX / DOCX ingestion", Status.UNAVAILABLE,
                        "Needs Apache POI. Export the data to CSV, TSV or text instead."),
                new Capability("PDF ingestion", Status.UNAVAILABLE,
                        "Needs Apache PDFBox. Provide a text export instead."),
                new Capability("HTML / Confluence ingestion", Status.UNAVAILABLE,
                        "Needs an approved HTML parser (for example JSoup). Prefer JSON APIs or user-provided exports."),
                new Capability("Official CasC schema validation", Status.UNAVAILABLE,
                        "The official schema and an approved validator have not been supplied."),
                new Capability("FDC acceptance", Status.UNAVAILABLE,
                        "No evidence of ingestion by the target FDC service."));
    }
}

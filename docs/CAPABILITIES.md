# Capability Matrix (end of Phase 3)

Also available at runtime: web home page, `GET /api/capabilities` and `casc-generator capabilities`.

| Capability | Dependency | Status | When unavailable | Verified |
|------------|------------|--------|------------------|----------|
| Manifest import (JSON) | none | Baseline | n/a | Tests executed (Linux) |
| Local validation, provisional profile | none | Baseline | n/a | Tests executed (Linux) |
| Export JSON | none | Baseline | n/a | Tests executed (Linux) |
| Export YAML (narrow emitter) | none | Baseline | n/a | Tests executed (Linux); not read back by a YAML parser |
| Import YAML | YAML parser | Unavailable | Convert to JSON | n/a |
| Browser UI | none (JDK HTTP server) | Baseline | n/a | Tests and manual curl (Linux) |
| JavaFX UI | OpenJFX + native libs | Unavailable | Use the browser UI | n/a |
| CSV/TSV ingestion | none | Baseline | Reported per source | Tests executed (Linux) |
| TXT/Markdown ingestion | none | Baseline | Reported per source | Tests executed (Linux) |
| SQL subset (DDL, comments, simple views) | none | Baseline | Unsupported statements reported with line | Tests executed (Linux) |
| SQL DML and transformation lineage | SQL parser (e.g. JSqlParser) | Unavailable | Reported, not analysed | n/a |
| Draft manifest from sources | none | Baseline | Errors name the missing context | Tests executed (Linux) |
| Directory and path-boundary input (CLI) | none | Baseline | n/a | Tests executed (Linux); Windows not run |
| OpenAPI JSON, URL fetch | none | Planned Phase 4 | n/a | n/a |
| XLSX, DOCX | Apache POI | Unavailable | Export to CSV/TSV/text | n/a |
| PDF | Apache PDFBox | Unavailable | Provide a text export | n/a |
| HTML / Confluence | JSoup or equivalent | Unavailable | JSON APIs or exports | n/a |
| Official schema validation | schema + validator | Unavailable | Local rules only | n/a |
| FDC acceptance | target service | Not verified | n/a | n/a |

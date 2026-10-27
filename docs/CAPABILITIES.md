# Capability Matrix (end of Phase 2)

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
| CSV/TSV, TXT/Markdown, SQL subset | none | Planned Phase 3 | n/a | n/a |
| OpenAPI JSON, URL fetch | none | Planned Phase 4 | n/a | n/a |
| XLSX, DOCX | Apache POI | Unavailable | Export to CSV/TSV/text | n/a |
| PDF | Apache PDFBox | Unavailable | Provide a text export | n/a |
| HTML / Confluence | JSoup or equivalent | Unavailable | JSON APIs or exports | n/a |
| Official schema validation | schema + validator | Unavailable | Local rules only | n/a |
| FDC acceptance | target service | Not verified | n/a | n/a |

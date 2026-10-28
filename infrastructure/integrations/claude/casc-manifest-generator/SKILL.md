---
name: casc-manifest-generator
description: Generates and validates CasC manifests for the Firmwide Data Catalog from SQL DDL, CSV or TSV data dictionaries, Markdown or text notes, and OpenAPI JSON by running the local casc-generator command line tool. Use when the user asks to extract metadata from schemas or specifications, draft a CasC manifest, validate a manifest, or review unresolved governance decisions such as PII attestation. Does not contact any external AI service and never claims FDC acceptance.
---

# CasC manifest generator

This skill runs the locally built `casc-generator` command line tool. It is not an MCP server and it adds no capability of its own:
everything below is a documented command of the application. If a command fails, report its message; do not work around it.

## Before you start
- Needs JDK 17+ and the built jar `build/libs/casc-generator.jar` (see the project README). Use the launcher from the repository root:
  Linux `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`.
- Check what this build can do: `run-cli tools` lists the three tools; `run-cli capabilities` lists available and unavailable formats.
- XLSX, DOCX, PDF, HTML and YAML are NOT supported. If the user has them, ask for CSV, TSV, text, Markdown or OpenAPI JSON exports. Do not try to convert them yourself.

## The three tools
Each reads one JSON request and prints one JSON document `{ "ok": true|false, "tool": ..., "result"|"error": ... }`. Put the request in a file you create
(inline text only; the tools cannot read other files) and pass it with `--request FILE`, or pipe it with `--request -`.

1. `run-cli tool ingest_sources --request ingest.json`
   Request: `{ "sources": [ { "name": "schema.sql", "text": "..." } ], "options": { } }`. The file name's extension chooses the reader (.sql .csv .tsv .txt .md .json for OpenAPI).
   Result: tables, columns, candidate terms, interface candidates, structures and findings with their source and location.
2. `run-cli tool validate_manifest_payload --request validate.json`
   Request: `{ "manifest": { ... } }`. Result: findings with rule code, path, message and suggested correction. The level reached is LOCAL only.
3. `run-cli tool generate_casc_manifest --request generate.json`
   Request: `{ "parsedMetadata": <the ingest_sources result>, "userInputs": { ... }, "format": "json" }` (format may be `yaml`).
   Result: `content` (the manifest text), `exportable`, a validation `report`, and `decisions` that are still open.

Examples of all three requests are in `infrastructure/integrations/examples/`. `userInputs` uses manifest field names; see `docs/INTEGRATION.md` for its shape.
Unknown fields in a request are rejected on purpose. Add `--fail-on-errors` in automation to get exit code 1 when the result has errors.

## Workflow
1. Run `ingest_sources` on the user's material. Show the user what was found and every finding, including unsupported statements and unavailable formats. Do not hide warnings.
2. **Ask the user** for anything the sources cannot tell you. Never invent: the TAI database logical name, database type, default schema, logical services and whether each is deployed on premises,
   cloud provider, table types (Transaction or Reference), interface directions (SOURCING means the system consumes data, DISTRIBUTION means it provides data), logical element names,
   PII elements, data subject categories (Client, Worker, Third Party) and the sensitive-data attestation (Yes or No) for each column or field.
3. Run `generate_casc_manifest` with the answers. Present `content`, then the errors, warnings and open `decisions` separately.
4. If `exportable` is false, explain each error with its path and the suggested correction, ask the user, and run again. Never delete data to make validation pass.
5. Offer `validate_manifest_payload` for manifests the user already has.

## Rules
- Never set or clear a sensitive-data attestation, PII element or category on your own. Names that look sensitive are prompts to ask the user, nothing more.
- Candidate terms, structures and lineage hints are suggestions. Add them to `userInputs` only when the user agrees.
- The EON ID and system name are kept outside the manifest. Do not put them into manifest fields.
- Say clearly that validation is LOCAL against a provisional profile. Never state that a manifest is valid for, or accepted by, the Firmwide Data Catalog.
- Do not send source material to any other service. URLs may be fetched only when the user has configured allowed hosts (`--url-allow`); never try to change or bypass that.
- Do not read files other than the ones the user gave you.
- Exit codes: 0 the tool ran, 1 errors with `--fail-on-errors`, 2 invalid request or usage, 3 I/O error, 4 internal error.

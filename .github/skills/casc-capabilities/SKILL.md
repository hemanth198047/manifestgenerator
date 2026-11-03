---
name: casc-capabilities
description: Reports what the local casc-generator build can and cannot do (available and unavailable formats, optional adapters for XLSX, DOCX, PDF, HTML and SQL DML, validation level) and its version. Use when the user asks what formats are supported, why a file type was rejected, how to enable an adapter, or what level of validation to expect.
---

# Capabilities, adapters and limits

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Commands
- `run-cli capabilities [--json]`: every capability with its status AVAILABLE or UNAVAILABLE and a detail line.
- `run-cli version`, `run-cli help`.

## What to tell the user
- Always available: CSV/TSV, TXT/Markdown, SQL (documented subset: CREATE TABLE/VIEW, ALTER TABLE ADD, COMMENT ON, PRIMARY KEY), OpenAPI/Swagger JSON, URL sources (allowlisted hosts only), manifest import/export in JSON, YAML export, local validation, the web UI, and dataset manifests per table (from a manifest JSON or SQL DDL plus a table metadata CSV; optional external column metadata for system data elements; `--no-upstream` for tables without upstreams; `combine` to merge the generated manifests into one, all or only some). Also available: `json` operations on any JSON file (get a section, keys, stats, format, set, delete, merge, flatten, csv, find, validate) and `compare` for an HTML report of differences between JSON files. The full command list is `docs/COMMANDS.md`.
- Optional adapters, off by default, need libraries from the user's approved sources (see `docs/ADAPTERS.md`): XLSX/XLS and DOCX (Apache POI), PDF (Apache PDFBox), HTML and Confluence (jsoup), SQL DML lineage (JSqlParser).
  Enable them at build time with `-Padapters=...`. The adapter sources live in `backend/src/main/java/com/example/casc/ingest/{poi,pdfbox,jsoup,jsqlparser}` and are compiled only when enabled.
- Never available: YAML input (no approved parser), official CasC schema validation, FDC acceptance, a desktop UI.
- When a format is unavailable the application reports it as an error with an alternative; suggest the alternative (export to CSV, TSV, text, Markdown or JSON). Do not convert files yourself.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- Validation is LOCAL against a provisional profile. Never say a manifest is valid for, or accepted by, the Firmwide Data Catalog.

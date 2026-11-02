---
name: casc-ingest-sources
description: Reads metadata from SQL DDL, CSV, TSV, text, Markdown, OpenAPI JSON and allowlisted URLs with the local casc-generator tool and reports the tables, columns, candidate terms, interface candidates and findings it found. Use when the user wants to extract or inspect metadata from schemas, data dictionaries, data files or API specifications before drafting a manifest. Reads only; writes no manifest unless asked.
---

# Ingest sources (extract metadata)

Runs the documented `ingest` command of the locally built `casc-generator`. It extracts tables, columns, descriptions, candidate terms, interface candidates (from OpenAPI) and findings, each with its source and location. Nothing in a source is executed.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Command
`run-cli ingest --input-files PATH [--input-files PATH ...] [--json] [--bundle-output FILE] [--overwrite]`

- `PATH` is a file or a directory (directories are scanned 4 levels deep, hidden files and symbolic links are skipped). Supported: `.sql .csv .tsv .txt .md` and OpenAPI/Swagger `.json`.
- `--json` prints the whole result as JSON; `--bundle-output FILE` saves it (needed later by the agent tools as `parsedMetadata`).
- URL sources: `--url URL` with `--url-allow HOST` (blocked unless the host is allowed; https only unless `--url-allow-http`). Never add hosts the user did not name.
- Reading options: `--dialect auto|oracle|db2|postgresql|sybase|snowflake|teradata|sqlserver`, `--delimiter comma|tab|semicolon|pipe|C`, `--no-header`, `--header-row N`,
  `--mode auto|data|dictionary`, `--max-rows N`, `--map table|column|description|type|schema=HEADER`, `--root DIR` (only read inside DIR).
- Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Workflow
1. Run the command on exactly the files the user named.
2. Show what was found (tables, columns, interface candidates, terms) and **every finding**, including unsupported statements and unavailable formats. Do not hide warnings.
3. XLSX, DOCX, PDF and HTML need optional adapters (see the `casc-capabilities` skill). If one is unavailable, ask the user for a CSV, TSV, text or Markdown export. Do not convert it yourself. YAML is never read; ask for JSON.
4. The same table defined in two sources is reported as `TABLE_CONFLICT` and not merged; ask the user which source is authoritative.

### Manifests are not sources
`OPENAPI_NOT_RECOGNISED` on a `.json` file usually means a CasC manifest or dataset manifest was given as a source. To check a manifest use `casc-validate-manifest`; to build per-table dataset manifests use `casc-dataset-manifests`.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- Candidate terms, structures and lineage hints are suggestions only; never promote them to manifest content without the user's agreement.
- Row values from data files are not kept, only column names, inferred types and counts.

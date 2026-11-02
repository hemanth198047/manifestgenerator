---
name: casc-agent-tools
description: Calls the three JSON tool contracts of the local casc-generator (ingest_sources, validate_manifest_payload, generate_casc_manifest) for step-by-step manifest generation with the user's decisions, or for automation that needs one JSON document back. Use when the user wants an interactive, reviewed manifest build, or a script or pipeline needs machine-readable results. These are plain JSON commands, not an MCP server.
---

# Agent tool contracts (JSON in, JSON out)

These are ordinary commands of the application, **not an MCP server**. Each reads one JSON request and prints one envelope `{ "ok": true|false, "tool": ..., "result"|"error": ... }`.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Commands
- `run-cli tools` lists the three tool contracts as JSON.
- `run-cli tool ingest_sources --request ingest.json`
- `run-cli tool validate_manifest_payload --request validate.json`
- `run-cli tool generate_casc_manifest --request generate.json`
- Add `--fail-on-errors` in automation (exit 1 when the result has errors). `--request -` reads the request from standard input. URL hosts: `--url-allow HOST`.
- Exit codes: 0 the tool ran (read the result), 1 errors with `--fail-on-errors`, 2 invalid request or usage, 3 I/O error, 4 internal error.

Requests carry inline text only (the tools cannot read other files) and unknown fields are rejected on purpose. Create the request file yourself from the user's material.

## The tools
1. `ingest_sources`: `{ "sources": [ { "name": "schema.sql", "text": "..." } ], "urls": [], "options": {} }`. The extension of `name` chooses the reader. Result: tables, columns, terms, interfaces, structures, findings, `hasErrors`.
2. `validate_manifest_payload`: `{ "manifest": { ... } }`. Result: `valid`, findings with rule code, path, message, suggestion; level LOCAL.
3. `generate_casc_manifest`: `{ "parsedMetadata": <ingest_sources result>, "userInputs": { ... }, "format": "json" }` (format may be `yaml`). Result: `content`, `exportable`, a `report`, and open `decisions`.

`userInputs` uses these sections (all optional): `system`, `services`, `elements`, `entities`, `transformations`, `tables`, `interfaces`, `acknowledged`. Full shape: `docs/INTEGRATION.md`; runnable examples: `infrastructure/integrations/examples/`.

## Workflow
1. Run `ingest_sources`; show the user what was found and every finding.
2. **Ask the user** for everything the sources cannot say (database logical name and type, default schema, services and where they are deployed, table types, interface directions, element names, PII, categories, attestations).
3. Run `generate_casc_manifest` with the answers. Present `content`, then errors, warnings and open `decisions` separately.
4. If `exportable` is false, explain each error with its path and correction, ask the user, and run again. Never delete data to make validation pass.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- Never set an attestation, PII element or category on your own. Add candidate terms or structures to `userInputs` only when the user agrees.
- The EON ID and system name are kept outside the manifest.
- Validation is LOCAL against a provisional profile; never state FDC acceptance.

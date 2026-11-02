---
name: casc-export-manifest
description: Reads a CasC manifest in JSON, validates it and writes it as normalised JSON or YAML with the local casc-generator tool (stable key order, LF line endings, UTF-8). Use when the user asks to export, convert, format or save a manifest as JSON or YAML. Refuses to write a manifest with validation errors unless told to.
---

# Export a CasC manifest (JSON or YAML)

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Command
`run-cli export --input manifest.json --output out.yaml --format json|yaml [--overwrite] [--allow-invalid]`

- Input must be JSON (YAML cannot be read). Output defaults to JSON; the YAML writer covers the known manifest structure only.
- By default nothing is written when validation finds errors; the findings are printed and the exit code is 1. `--allow-invalid` writes the file anyway; use it only when the user explicitly asks.
- The command refuses to replace an existing file unless `--overwrite` is given; add it only when the user wants the file replaced.
- JSON output is read back and compared as a round-trip check. For YAML that check is not available (reported as info).
- Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Workflow
1. Confirm input, output path and format with the user.
2. Run the command and show the validation summary it prints.
3. If it reports errors, use the `casc-validate-manifest` skill to explain and fix them; do not use `--allow-invalid` to hide them.

### Export drops unsupported fields
`export` rewrites the manifest from the supported model only (`logicalServices`, `systemDataElements`, `interfaces`, `dataEntities`, `databases`, `transformations`). Any other field, such as `datasets` or `dgcDataDomains` in a dataset manifest, is **not written** (warning `UNKNOWN_FIELD`).
Before exporting, run `casc-validate-manifest` first; if it reports `UNKNOWN_FIELD` for fields the user needs, do not export. Tell the user why and keep their original file.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

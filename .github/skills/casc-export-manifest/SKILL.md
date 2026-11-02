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

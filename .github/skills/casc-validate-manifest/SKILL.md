---
name: casc-validate-manifest
description: Validates an existing CasC manifest (JSON) against the local provisional rules with the casc-generator tool and explains each finding with its path and suggested correction. Use when the user asks to check, validate or review a manifest, or to find out why a manifest has errors. Local check only; never claims acceptance by the Firmwide Data Catalog.
---

# Validate a CasC manifest

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Command
`run-cli validate --input manifest.json [--json]`

- Reads a manifest in JSON (YAML cannot be read; ask the user to convert it to JSON).
- Prints each finding as severity, rule code, path, message and a suggested fix, then a summary. `--json` prints the report as JSON.
- Exit code 0 means no errors, 1 means validation errors, 3 means the file could not be read or parsed. Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Workflow
1. Run the command on the file the user named and show the findings grouped as errors, warnings and info.
2. For each error, explain it with its path and the suggested correction, and ask the user how to fix it. Do not change attestations, PII or categories on your own.
3. After the user agrees to changes, edit the file and run the command again.

## Facts you may rely on
- Enum values are exact and case-sensitive: interface type SOURCING or DISTRIBUTION; table type Transaction or Reference; attestation Yes or No; subject categories Client, Worker, Third Party.
- `isDeployedOnPrem` must be stated explicitly; `cloudProvider` is required when it is false.
- Schema, table and column names are unqualified (no periods).
- An attestation of Yes needs at least one PII element and one data subject category.

### `UNKNOWN_FIELD` on a dataset manifest
A manifest made by `ingest --manifest-input` has top-level `datasets` and `dgcDataDomains`. They are outside the CasC manifest model, so `validate` reports `UNKNOWN_FIELD` for them.
This is expected: the file is fine and unchanged. Tell the user this build cannot validate dataset manifests (review them against the official CasC specification) and that they must **not** run `export` on such a file, because export writes only the supported fields and would drop `datasets`.
The model `validate` understands: top level `logicalServices`, `systemDataElements`, `interfaces`, `dataEntities`, `databases`, `transformations`, each with its own known fields (the message lists them).

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- The level reached is LOCAL (a provisional profile). It is not the official CasC schema and not FDC acceptance; say so.

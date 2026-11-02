---
name: casc-draft-manifest
description: Drafts an unreviewed CasC manifest (databases, schemas, tables, columns and optionally interfaces) from SQL, CSV, TSV, text, Markdown or OpenAPI sources with the local casc-generator tool, then helps the user resolve the open governance decisions. Use when the user asks to create, generate or draft a CasC manifest from schemas or specifications. Never invents PII, attestations, services or table types.
---

# Draft a CasC manifest

Two routes, both over the application's own commands. The result is always an **UNREVIEWED draft** that is validated locally only.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## Route A: one command (quick draft)
`run-cli ingest --input-files PATH ... --db-name TAI_DB_NAME --output manifest.json [--db-type TYPE] [--default-schema SCHEMA] [--format json|yaml] [--interface-direction SOURCING|DISTRIBUTION] [--overwrite]`

- `--db-name` (the TAI logical database name) is required; `--default-schema` is required when a source has no schema (for example CSV files).
- `--interface-direction` is only needed to draft OpenAPI operations as interfaces: SOURCING means the system consumes the data, DISTRIBUTION means it provides it.
- Included in the draft: databases, schemas, tables, columns, and (with a direction) interfaces with field paths. Not included: logical services, logical data elements, data entities, table types, PII, attestations, system keys, lineage.
- Nothing is written when there are errors; the messages say what to fix. Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Route B: with decisions (agent tools)
Use the `casc-agent-tools` skill: `ingest_sources`, then `generate_casc_manifest` with `userInputs` that carry the user's answers (services, table types, element names, PII, attestations, interface directions), and repeat until `exportable` is true.

## Ask the user, never invent
The TAI database logical name, database type, default schema, logical services and whether each is deployed on premises, cloud provider, table types (Transaction or Reference), interface directions,
logical element names, PII elements, data subject categories (Client, Worker, Third Party) and the sensitive-data attestation (Yes or No) for each column or field.

## Rules
- Never set or clear an attestation, PII element or data subject category on your own. A name that looks sensitive is a reason to ask, nothing more.
- The EON ID and system name stay outside the manifest. Never put them into manifest fields.
- Say clearly that validation is LOCAL against a provisional profile. Never claim the manifest is valid for, or accepted by, the Firmwide Data Catalog.
- Never delete data to make validation pass; explain each error with its path and suggested correction and ask the user.

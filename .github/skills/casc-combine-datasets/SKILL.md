---
name: casc-combine-datasets
description: Combines the datasets of several dataset manifests (such as the manifest_<table>.json files made by casc-generator) into one manifest, either all of them or only a chosen number or named tables, with the local casc-generator tool. Use when the user wants a single manifest containing all or some of the generated dataset manifests, or wants to merge per-table manifests. Does not contact any external service.
---

# Combine dataset manifests into one

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## What to ask the user
1. **Which manifests?** A folder holding the generated `manifest_<table>.json` files, or specific files.
2. **Which datasets?** All of them (default), only the first N, or only named tables.
3. The output file, and whether an existing file may be replaced (`--overwrite`).

## Command
`run-cli combine --input DIR_OR_FILE [--input DIR_OR_FILE ...] --output COMBINED.json [--count N | --tables A,B,C] [--overwrite]`

- `--input` is a folder (its `.json` files are read in name order; the output file is skipped) or a file; repeat it for several.
- **All datasets:** give neither `--count` nor `--tables`.
- **Only some:** `--count N` takes the first N datasets in input order; `--tables A,B,C` takes only those tables (table name, `schema.table` or `database.schema.table`, case ignored). Use one of the two, not both.
- The result is one manifest `{"datasets": [ ... ]}`. A dataset name already in the result is not added twice.
- Without `--overwrite` the command refuses to replace an existing output file. Exit codes: 0 ok, 1 errors (nothing written), 2 usage error, 3 I/O error, 4 internal error.

Combining while generating: `run-cli ingest ... --combine-output COMBINED.json [--combine-count N | --combine-tables A,B] [--combined-only]` (see the `casc-dataset-manifests` skill).

## Workflow
1. Confirm the input, the selection and the output file with the user.
2. Run the command and show everything it printed, including the `COMBINE_SUMMARY` line (datasets read, files read, datasets combined).
3. Explain any warning: `COMBINE_TABLE_NOT_FOUND` (a requested table has no dataset), `COMBINE_DUPLICATE_DATASET` (same dataset twice, the first kept), `COMBINE_FILE_SKIPPED` (a file without `datasets`), `COMBINE_FIELD_DROPPED` (other top-level fields are not carried over).
4. If the command reports errors (unreadable file, nothing selected), say what each means and how to fix the input; run again after the user fixes it. Never edit the user's files yourself.

### Validating the combined file
`run-cli validate` on a combined manifest reports `UNKNOWN_FIELD` for `datasets`. That is expected (dataset manifests are outside the CasC model); explain it with `run-cli explain UNKNOWN_FIELD`. Never run `export` on it: it would drop `datasets`.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks, WARNING needs attention but does not block, INFO is for information), a code, a path (where), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain COMBINE_TABLE_NOT_FOUND`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- Combine only what the user named. Do not invent dataset names or tables.
- The output is not validated against the official schema and is not accepted by the Firmwide Data Catalog until the owners review it. Never say otherwise.

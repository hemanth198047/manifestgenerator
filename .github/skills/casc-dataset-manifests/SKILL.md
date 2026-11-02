---
name: casc-dataset-manifests
description: Creates one dataset manifest per table from a CasC manifest JSON (its databases section) and a table metadata CSV (table name, domain, boundary, upstream dataset, producer eonid) with the local casc-generator tool. Use when the user supplies a manifest JSON plus a table metadata CSV and wants a separate manifest, with datasets, upstreamDatasets, datasetElements and systemDataElements, for each table. Does not contact any external service.
---

# Dataset manifests per table

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

- You need two files from the user: the manifest JSON (tables are read from `databases[].schemas[].tables[]`) and the table metadata CSV with the header `table name, domain, boundary, upstream dataset, producer eonid`.

## Command
`run-cli ingest --manifest-input MANIFEST.json --table-metadata TABLE_METADATA.csv --dataset-output OUT_DIR [--overwrite]`

All three options are required together. Add `--overwrite` only when the user wants existing `manifest_<table>.json` files replaced; without it the command refuses to overwrite. Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Workflow
1. Confirm the manifest file, the metadata CSV and the output directory with the user.
2. Run the command and show everything it printed, including every warning.
3. Explain the outcome: one `manifest_<table>.json` per table that has a metadata row. A metadata row whose table is not in the manifest is a warning (`METADATA_TABLE_NOT_IN_MANIFEST`) and produces no file;
   a manifest table without a metadata row is skipped (`TABLE_WITHOUT_METADATA`). `MANIFEST_LENIENT_READ` means the manifest had JSON syntax errors (for example trailing commas) and only its `databases` section was read.
4. If the command reports errors, say what each one means and how to fix the input; run again after the user fixes it. Never edit the user's inputs yourself.
5. Each generated file holds one `datasets` entry: `name` (`schema.table`), `format` ResultSet, `boundaries`, `dgcDataDomains`, `upstreamDatasets` (name and `producerEonId`),
   `datasetElements` (`name`, `description`, `systemDataElements`), `eucOrManualProcess`, `originatingCatalogingSource`.

## Rules
- Do not invent metadata. A missing domain, boundary, upstream dataset or EON ID is reported by the tool; ask the user to fill it in the CSV.
- The output is not validated against the official schema and is not accepted by the Firmwide Data Catalog until the owners review it. Never say otherwise.

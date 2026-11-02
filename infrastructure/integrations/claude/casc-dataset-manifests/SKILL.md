---
name: casc-dataset-manifests
description: Creates one dataset manifest per table from a CasC manifest JSON (its databases section) and a table metadata CSV (table name, domain, boundary, upstream dataset, producer eonid) by running the local casc-generator command line tool. Use when the user gives a manifest JSON plus a table metadata CSV and wants a separate dataset manifest (datasets with upstreamDatasets, datasetElements and systemDataElements) for each table. Does not contact any external service.
---

# CasC dataset manifests

This skill runs the locally built `casc-generator` command line tool. It is not an MCP server and adds no capability of its own:
it uses the documented `ingest` command with the dataset options. If the command fails, report its message; do not work around it.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (see the project README). Use the launcher from the repository root:
  Linux `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`.
- You need two files from the user: the manifest JSON (tables are read from `databases[].schemas[].tables[]`) and the table metadata CSV
  with the header `table name, domain, boundary, upstream dataset, producer eonid`.
- Use only the files the user gave you. Do not read other files.

## Command
`run-cli ingest --manifest-input MANIFEST.json --table-metadata TABLE_METADATA.csv --dataset-output OUT_DIR`

Add `--overwrite` only when the user wants existing `manifest_<table>.json` files in OUT_DIR replaced; without it the command refuses to overwrite.
All three options are required together. Exit codes: 0 written, 1 errors (nothing written), 2 usage error, 3 I/O error, 4 internal error.

## Workflow
1. Confirm the manifest file, the metadata CSV and the output directory with the user.
2. Run the command and show the user everything it printed, including every warning. Do not hide them.
3. Explain the outcome: one `manifest_<table>.json` per table that has a metadata row. A metadata row whose table is not in the manifest is a warning
   (`METADATA_TABLE_NOT_IN_MANIFEST`) and produces no file; a manifest table without a metadata row is skipped (`TABLE_WITHOUT_METADATA`).
   `MANIFEST_LENIENT_READ` means the manifest had JSON syntax errors (for example trailing commas) and only its `databases` section was read.
4. If the command reports errors, tell the user what each one says and how to fix the input; run again after they fix it. Never edit the user's inputs yourself.
5. Show a generated file when asked. Each holds one `datasets` entry: `name` (`schema.table`), `format` ResultSet, `boundaries`, `dgcDataDomains`,
   `upstreamDatasets` (name and `producerEonId`), `datasetElements` (`name`, `description`, `systemDataElements`), `eucOrManualProcess`, `originatingCatalogingSource`.

## Rules
- Do not invent metadata. A missing domain, boundary, upstream dataset or EON ID is reported by the tool; ask the user to fill it in the CSV.
- The output is not validated against the official schema and is not accepted by the Firmwide Data Catalog until the owners review it. Never say otherwise.
- Do not send the files to any other service.

---
name: casc-dataset-manifests
description: Creates one dataset manifest per table (datasets, boundaries, data domains, datasetElements with systemDataElements and, optionally, upstreamDatasets) from a CasC manifest JSON (its databases section) or SQL DDL files, plus a table metadata CSV, with the local casc-generator tool. Use when the user supplies a manifest JSON or SQL file and a table metadata CSV and wants a separate manifest for each table, with or without upstream datasets, optionally with system data elements from an external column metadata CSV. Does not contact any external service.
---

# Dataset manifests per table

This skill runs the locally built `casc-generator` command line tool. It is not an MCP server and adds no capability of its own: it uses the documented `ingest` command with the dataset options.

## Before you start
- Needs JDK 21 and the built jar `build/libs/casc-generator.jar` (build it once with `./gradlew jar`; see the project README).
- Run commands from the repository root with the launcher: Linux/macOS `infrastructure/launchers/run-cli.sh`, Windows `infrastructure\launchers\run-cli.bat`. Below this is written `run-cli`.
- If a command fails, report its message and exit code. Do not work around it, and do not read files the user did not give you.

## What to ask the user
1. **Where do the tables come from?** A CasC manifest JSON (tables are read from `databases[].schemas[].tables[].physicalName`), or SQL DDL files (`CREATE TABLE` statements).
2. **The table metadata CSV.** Header `table name, domain, boundary, upstream dataset, producer eonid`; one row per table that needs a dataset manifest. A domain written `Account (Operations DGC Glossary)` is split into domain and glossary.
3. **Do the tables have upstream datasets?** If not, use `--no-upstream`: no `upstreamDatasets` is written and the CSV needs only `table name, domain, boundary`.
4. **Are the system data elements known?** A manifest JSON carries a `systemDataElement` per column. SQL does not, so offer an external column metadata CSV with the header `table name, column name, system data element` (`--column-metadata`). It also replaces a manifest's own value for the columns it lists.
5. Whether they also want everything (or only some datasets) combined into one manifest.
6. The output directory, and whether files already there may be replaced (`--overwrite`; without it the command refuses to overwrite).

## Command
`run-cli ingest --manifest-input MANIFEST.json --table-metadata TABLE_METADATA.csv --dataset-output OUT_DIR [--column-metadata COLUMNS.csv] [--no-upstream] [--overwrite]`

or, with the tables read from SQL:

`run-cli ingest --sql-input SCHEMA.sql [--sql-input MORE.sql ...] [--default-schema SCHEMA] [--dialect oracle|db2|postgresql|sybase|snowflake|teradata|sqlserver] --table-metadata TABLE_METADATA.csv --dataset-output OUT_DIR [--column-metadata COLUMNS.csv] [--no-upstream] [--overwrite]`

- Give exactly one of `--manifest-input` or `--sql-input`. `--table-metadata` and `--dataset-output` are always required.
- With SQL: the schema comes from `schema.table` (or `--default-schema`; without either the dataset is named with the table name only and `DATASET_SCHEMA_MISSING` is reported). A column's `description` comes from a SQL comment when there is one. Views are skipped.
- Exit codes: 0 ok, 1 validation errors (nothing written), 2 usage error, 3 I/O or parse failure, 4 internal error.

## Workflow
1. Confirm the inputs, the options above and the output directory with the user.
2. Run the command and show everything it printed, including every warning and the final `DATASET_SUMMARY` line (tables read, metadata rows, manifests generated).
3. Explain the outcome. One `manifest_<table>.json` is written per table that has a metadata row. **Only tables with a metadata row are generated**, so if fewer manifests than tables were written, the summary and `TABLE_WITHOUT_METADATA` say how many tables had no row.
   - `METADATA_TABLE_NOT_IN_MANIFEST`: a CSV row matches no table (spelling; views are not mapped); no file for it.
   - `MANIFEST_TABLE_SKIPPED`: table entries without a `physicalName` could not be read.
   - `MANIFEST_LENIENT_READ`: the manifest had JSON syntax errors (for example trailing commas) and only its `databases` section was read.
   - `COLUMN_METADATA_NOT_FOUND`: a row of the column metadata CSV matches no table or column; it was not used.
4. If the command reports errors, say what each one means and how to fix the input; run again after the user fixes it (with `--overwrite` if files were already written). Never edit the user's inputs yourself.
5. Each generated file holds one `datasets` entry: `name` (`schema.table`), `format` ResultSet, `boundaries`, `dgcDataDomains`, `upstreamDatasets` (name and `producerEonId`; absent with `--no-upstream`),
   `datasetElements` (`name`, `description`, and `systemDataElements` when a system data element is known for the column), `eucOrManualProcess`, `originatingCatalogingSource`.

## Combining into one manifest
To get a single manifest holding the datasets, add `--combine-output COMBINED.json` to the same command (`--combined-only` skips the per-table files). Choose which datasets: all (default), only the first N (`--combine-count N`), or only named tables (`--combine-tables A,B`). Already generated files can be combined afterwards with the `combine` command (see the `casc-combine-datasets` skill).

### Validating the generated files
`run-cli validate` on a generated dataset manifest reports `UNKNOWN_FIELD` for `datasets`. That is expected (dataset manifests are outside the CasC model); explain it with `run-cli explain UNKNOWN_FIELD`. Never run `export` on these files: it would drop `datasets`.

## Explaining findings (always do this)
Every finding has a severity (ERROR blocks export, WARNING needs attention but does not block, INFO is for information), a code, a path (where in the file), a message and a fix.
For each one, tell the user in plain words: **what it means**, **why it matters**, **exactly what to do**, and **where to look**. Do not just repeat the code.
- Get the authoritative explanation with `run-cli explain CODE` (for example `run-cli explain UNKNOWN_FIELD`); `run-cli explain` lists every code. The same text is in `docs/FINDINGS.md`.
- "Validation level LOCAL" means the manifest was checked only against this tool's provisional rules: not the official CasC schema and not FDC acceptance. Say so whenever you report a result.
- Never tell the user to ignore a finding without saying why it is safe. Never hide warnings.

## Rules
- Do not invent metadata. A missing domain, boundary, upstream dataset, EON ID or system data element is reported or left out by the tool; ask the user to fill it in the CSV.
- The output is not validated against the official schema and is not accepted by the Firmwide Data Catalog until the owners review it. Never say otherwise.

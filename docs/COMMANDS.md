# Command reference

Every command of the project in one place. Run them as `java -jar build/libs/casc-generator.jar COMMAND ...`, or with the launchers
(`infrastructure/launchers/run-cli.sh` and `run-cli.bat` for any command, `run-web.sh` and `run-web.bat` for the web UI). In this page the program is written `casc-generator`.
`casc-generator help` prints the short version of this page.

- **Exit codes** (all commands): 0 ok, 1 validation errors (or a requested difference), 2 usage error, 3 I/O or parse failure, 4 internal error.
- **Overwriting:** commands that write a file refuse to replace an existing one unless you add `--overwrite`. Files are written atomically (temporary file, then moved into place).
- **Findings:** every warning or error has a code. `casc-generator explain CODE` says what it means and how to fix it; all codes are in `docs/FINDINGS.md`.
- **Environment:** `CASC_BIND`, `CASC_PORT` (web), `CASC_INPUT_ROOT` (folder that `ingest` may read from, and the root of `mcp`), `CASC_AUDIT_LOG` (audit log of `mcp`), `CASC_URL_ALLOW` (comma separated hosts), `CASC_URL_TOKEN` (bearer token for URL sources), `JAVA_OPTS` (launchers).

| Command | What it does |
|---------|--------------|
| [`validate`](#validate) | Check a manifest against the local provisional rules |
| [`export`](#export) | Write a manifest as normalised JSON or YAML |
| [`explain`](#explain) | Explain a finding code in plain language |
| [`ingest`](#ingest) | Read sources and draft a manifest, or create dataset manifests per table |
| [`combine`](#combine) | Combine dataset manifests into one |
| [`json`](#json) | Extract sections from a JSON file and run operations on it |
| [`compare`](#compare) | Compare several JSON files and write an HTML report |
| [`tools`, `tool`](#tools-and-tool) | The three agent tool contracts (JSON in, JSON out) |
| [`mcp`](#mcp) | MCP server for GitHub Copilot: the agent's tools (see `docs/AGENT.md`) |
| [`web`](#web) | Start the web UI |
| [`capabilities`, `version`, `help`](#capabilities-version-and-help) | Information |
| [Build and test](#build-and-test) | Gradle and no-Gradle build, tests, verification |

## validate
`casc-generator validate --input FILE [--json]`

Reads a manifest in JSON (YAML cannot be read) and prints every finding with severity, code, path, message and suggested fix, then a summary. `--json` prints the report as JSON.
Exit 0 = no errors, 1 = validation errors, 3 = the file could not be read or parsed. The level is LOCAL: a provisional profile, not the official CasC schema and not FDC acceptance.
A dataset manifest (top-level `datasets`) shows the expected warning `UNKNOWN_FIELD`; see `explain UNKNOWN_FIELD`.

## export
`casc-generator export --input FILE --output FILE [--format json|yaml] [--overwrite] [--allow-invalid]`

Validates, then writes the manifest from the supported model (stable key order, LF, UTF-8). Nothing is written when validation finds errors unless `--allow-invalid` is given.
Fields outside the model (for example `datasets`) are not written. JSON output is read back and compared; YAML is written but cannot be read back.

## explain
`casc-generator explain [CODE]`

With a code (any case) prints what it means, how to fix it and where to look. Without a code lists every code with a short title. Unknown code: exit 2.

## ingest
`ingest` has three modes, chosen by its options.

### Read sources and draft a manifest
`casc-generator ingest --input-files PATH [--input-files PATH ...] [--url URL ...] [options]`

Reads `.sql .csv .tsv .txt .md` and OpenAPI/Swagger `.json` (directories are scanned 4 levels deep). XLSX, DOCX, PDF and HTML need optional adapters (`capabilities`).
Prints tables, columns, terms, interface candidates and findings.

| Option | Meaning |
|--------|---------|
| `--json` | Print the result as JSON |
| `--bundle-output FILE` | Save the result (the `parsedMetadata` that the agent tools take) |
| `--root DIR` | Only read files inside DIR (default: `CASC_INPUT_ROOT`, if set) |
| `--dialect D` | `auto` (default), `oracle`, `db2`, `postgresql`, `sybase`, `snowflake`, `teradata`, `sqlserver` |
| `--delimiter D` | `comma`, `tab`, `semicolon`, `pipe` or one character |
| `--no-header`, `--header-row N` | Header handling for CSV/TSV |
| `--mode auto\|data\|dictionary` | Treat a CSV as data or as a data dictionary |
| `--max-rows N` | Rows sampled for type inference |
| `--map FIELD=HEADER` | Name a dictionary column; FIELD is `table`, `column`, `description`, `type` or `schema` (repeatable) |
| `--sheet NAME\|NUMBER` | Spreadsheet sheet (POI adapter) |
| `--url URL`, `--url-allow HOST`, `--url-allow-http`, `--url-timeout S`, `--url-max-bytes N` | URL sources: blocked unless the host is allowed; https only unless `--url-allow-http` |
| `--overwrite` | Replace an existing output file |

Draft manifest: add `--db-name NAME --output FILE` and optionally `--db-type TYPE`, `--default-schema S` (needed for sources without a schema, such as CSV), `--format json|yaml`,
`--interface-direction SOURCING|DISTRIBUTION` (to draft OpenAPI operations as interfaces). Nothing is written when there are errors. The result is an unreviewed draft.

### Dataset manifests, one per table
```
casc-generator ingest --manifest-input MANIFEST.json --dataset-output OUT_DIR (--table-metadata META.csv | --table-list TABLES.csv ...) [options]
casc-generator ingest --sql-input SCHEMA.sql [--sql-input MORE.sql ...] [--default-schema S] [--dialect D] --dataset-output OUT_DIR (--table-metadata META.csv | --table-list TABLES.csv ...) [options]
```
Give the tables as a manifest JSON (`databases[].schemas[].tables[]`; a manifest with trailing or missing commas is still read) **or** as SQL DDL, not both. One `manifest_<table>.json` is written per selected table.
`META.csv` has the header `table name, domain, boundary, upstream dataset, producer eonid`; every table that has a row is generated. With `--table-list` the list decides which tables are generated and `--table-metadata` is optional.

| Option | Meaning |
|--------|---------|
| `--manifest-input FILE` / `--sql-input FILE` | The tables: a manifest JSON, or SQL DDL (repeatable). Exactly one of the two |
| `--dataset-output DIR` | Where the per-table manifests are written (required) |
| `--table-metadata FILE` | CSV `table name, domain, boundary, upstream dataset, producer eonid` and an optional `description` column: which tables, and each one's domain, boundary, upstream and description. A file that includes the description is the complete metadata file, so no separate `--table-descriptions` is needed |
| `--table-list FILE` | Generate only for the tables in FILE (a `table name` column, a one-column CSV, or a `.txt` with one table per line; `table`, `schema.table` or `database.schema.table`). `--table-metadata` then becomes optional |
| `--domain TEXT`, `--boundary TEXT` | With `--table-list`: the domain (`Account (Operations DGC Glossary)`) and boundary for listed tables that have no row in `--table-metadata` |
| `--table-descriptions FILE` | CSV/TSV with a table column (`table name`, `tables` or `table`) and a `description` column: each table's description (other columns are ignored). Only needed when the metadata file has no `description` column; if both give one, this file wins. Without a description the SQL comment or a generated one is used |
| `--column-metadata FILE` | CSV `table name, column name, system data element`: supplies `systemDataElements` per column (fills what SQL cannot, replaces a manifest's own value) |
| `--no-upstream` | Write no `upstreamDatasets`; the metadata CSV then needs only `table name, domain, boundary` |
| `--database-name NAME`, `--schema-name NAME` | The database and schema parts of every dataset name. **Every dataset is named `a2.dbo.<tableName>` by default**, whatever database and schema the source uses; these two options replace `a2` and `dbo` (for example `--database-name TRADE_DB --schema-name sales` gives `TRADE_DB.sales.<tableName>`) |
| `--default-schema S` | Schema to assume for SQL tables that have none, so that a `schema.table` in `--table-list` can still match them. It does not change the dataset name (see `--schema-name`) |
| `--dialect D` | SQL dialect (as for reading sources) |
| `--combine-output FILE` | Also write one combined manifest |
| `--combine-count N` / `--combine-tables A,B,...` | Combine only the first N datasets / only the named tables (not both) |
| `--combined-only` | With `--combine-output`: skip the per-table files |
| `--overwrite` | Replace existing files |

The run ends with a summary (tables read, rows or listed tables, manifests generated). Details: `docs/INGESTION.md`.

## combine
`casc-generator combine --input FILE_OR_DIR [--input ...] --output FILE [--count N | --tables A,B,...] [--overwrite]`

Merges the `datasets` of several dataset manifests into one `{"datasets": [...]}` file. A folder contributes its `.json` files in name order. All datasets by default; `--count N` takes the first N,
`--tables` takes the named tables (table name, `schema.table` or `database.schema.table`, case ignored); not both. A dataset name already present is not added twice.

## json
`casc-generator json OPERATION --input FILE [options]` (`json` alone lists the operations).

Works on any JSON file. Every operation takes `--lenient` (accept trailing or missing commas; output is always valid JSON). If a file is broken in one section only, `--lenient` still reads the other top-level sections and tells you which ones it left out. Results go to the console, or to a file with `--output FILE` (`--overwrite` to replace one).

| Operation | Options | Result |
|-----------|---------|--------|
| `get` | `--path P` `[--lines]` `[--with-paths]` `[--compact]` | The section(s) the path selects. One match prints the value; several print an array (or `{path: value}` with `--with-paths`). `--lines` prints one value per line (strings without quotes), handy for lists of names. Exit 1 when nothing matches. |
| `keys` | `[--path P]` | The keys of an object with the kind and size of each value, or the item kinds and keys of an array: a quick map of the file |
| `stats` | `[--path P]` | Counts of objects, arrays, strings, numbers, booleans, nulls and the depth |
| `format` | `[--compact]` `[--sort-keys]` | Pretty-print, minify, or sort keys |
| `set` | `--path P --value JSON --output FILE` | Set a value (missing keys are created; `*`, slices and filters set several places). Text must be quoted JSON: `--value '"Account ID"'`. Plain text that is not JSON is used as text. |
| `delete` | `--path P --output FILE` | Remove the selected keys or items |
| `merge` | `--input A --input B ... --output FILE` | Deep merge, later files win, arrays are replaced |
| `flatten` | `[--path P]` | One line per value: `path = value` |
| `csv` | `--path P` | An array of objects as CSV (nested values as compact JSON) |
| `find` | `--text T` `[--max N]` | Paths whose key or value contains the text (case ignored) |
| `validate` | | Check that the file is valid JSON; the error says the line and column |

Without `--output`, `set` and `delete` print the changed document and never touch the input file. To change a file in place, write `--output` to the same file with `--overwrite`.

**Paths** pick sections:

| Path | Selects |
|------|---------|
| `databases` | a key (`$` or no path is the whole document) |
| `databases[0].schemas[1]` | an array item; `[-1]` is the last |
| `databases[*].schemas[*].tables` | every item or value (`.*` also works) |
| `tables[0:5]` | a slice (items 0 to 4; `[:5]`, `[3:]` also work) |
| `tables[?physicalName=TAPSAccount]` | items whose field equals a value; `!=` is not equal, `~` is contains (case ignored) |
| `..physicalName` | every `physicalName` key at any depth |
| `["key.with.dots"]` | a key written in quotes |

Examples:
```
casc-generator json keys --input manifest.json
casc-generator json get --input manifest.json --path 'databases[0].schemas[0].tables[?physicalName=TAPSAccount]' --output taps.json
casc-generator json get --input manifest.json --path '..systemDataElement' --with-paths --compact
casc-generator json csv --input manifest.json --path 'databases[0].schemas[0].tables[0].columns' --output columns.csv
casc-generator json set --input m.json --path 'datasets[0].format' --value '"ResultSet"' --output m2.json
casc-generator json delete --input m.json --path '..description' --output m2.json
casc-generator json merge --input base.json --input override.json --output merged.json
casc-generator json find --input manifest.json --text account
casc-generator json get --input manifest.json --lenient --path 'databases[*].schemas[*].tables[*].physicalName' --lines   # just the table names
```

## compare
`casc-generator compare --input FILE_OR_DIR --input FILE_OR_DIR [...] --output REPORT.html [options]`

Compares two or more JSON files (a folder contributes its `.json` files). The first file is the baseline. Every value is compared under a stable path; arrays of objects are matched by an identifying field
(`name`, `physicalName`, `id`, `logicalName` or `key`, when every item has a distinct one), so the same table is compared with the same table even if the order differs; other arrays are compared by position.
The console shows added, removed, changed and same counts per file; the HTML report is one self-contained page (works offline, light and dark theme, printable) with a summary, a per-file table,
a per-section table and a searchable, filterable list of every difference, one column per file.

| Option | Meaning |
|--------|---------|
| `--output FILE` | The HTML report |
| `--json-output FILE` | The same result as JSON, for scripts (at least one of the two outputs is required) |
| `--path SECTION` | Compare only this section in every file (same path language as `json`) |
| `--key FIELD,FIELD` | Identifying field(s) for arrays of objects, instead of the defaults |
| `--title TEXT` | Title of the report |
| `--max-rows N` | Differing rows shown in the report (default 5000; the counts are always complete) |
| `--lenient` | Accept trailing or missing commas in the inputs |
| `--fail-on-diff` | Exit 1 when the files differ (the report is still written); for pipelines |
| `--overwrite` | Replace existing report files |

Example: `casc-generator compare --input a/manifest.json --input b/manifest.json --input c/manifest.json --path databases --output report.html`

## tools and tool
`casc-generator tools` lists the three tool contracts as JSON. `casc-generator tool NAME --request FILE|- [--fail-on-errors] [--url-allow HOST ...]` runs one:
`ingest_sources`, `validate_manifest_payload` or `generate_casc_manifest`. The request is JSON from a file, or from standard input with `-`; the answer is one JSON envelope on standard output.
They are ordinary commands, not an MCP server. `--fail-on-errors` returns exit 1 when the result has errors. Shapes and examples: `docs/INTEGRATION.md`, `infrastructure/integrations/examples/`.

## mcp
`casc-generator mcp [--root DIR] [--read-only] [--audit-log FILE]`

An MCP server on standard input and output, for **GitHub Copilot** (VS Code, IntelliJ, Copilot CLI): Copilot is the model, the application provides the tools. It is normally started by Copilot from `.vscode/mcp.json`, not by hand.

| Option | Meaning |
|--------|---------|
| `--root DIR` | The only folder the tools may read or write (default: `CASC_INPUT_ROOT`, else the current folder) |
| `--read-only` | Offer only the read tools; every tool that writes is removed |
| `--audit-log FILE` | One JSON line per tool call (default: `CASC_AUDIT_LOG`); file contents are never logged |

Fourteen tools: `list_files`, `read_file`, `capabilities`, `explain_finding`, `validate_manifest`, `ingest_sources`, `json_inspect`, `compare_summary` (read) and `generate_dataset_manifests`, `combine_datasets`, `json_edit`, `compare_report`, `export_manifest`, `draft_manifest` (write, always in two steps: a plan, then a confirmation with the plan hash). Setup, usage and safety: `docs/AGENT.md`.

## web
`casc-generator web [--bind ADDR] [--port N] [--max-body-bytes N] [--allow-clipboard] [--url-allow HOST ...] [--url-allow-http] [--url-timeout S] [--url-max-bytes N]`

Starts the five-step wizard at `http://127.0.0.1:8080/wizard` (defaults from `CASC_BIND` and `CASC_PORT`). There is no authentication: keep it on the loopback address.
`--allow-clipboard` adds a copy button to the export step. Launchers: `infrastructure/launchers/run-web.sh` and `run-web.bat`. Stop with Ctrl+C. Port in use: exit 3.

## capabilities, version and help
- `casc-generator capabilities [--json]` lists every capability as AVAILABLE or UNAVAILABLE with a detail line (optional adapters, formats).
- `casc-generator version` prints the version. `casc-generator help` prints the usage.

## Build and test
| Task | Command |
|------|---------|
| Compile | `./gradlew compileJava` (Windows: `gradlew.bat compileJava`) |
| Package the jar (`build/libs/casc-generator.jar`) | `./gradlew jar` |
| Tests | `./gradlew unitTests` and `./gradlew unitTestsSeparateLoaders` (`check` runs both) |
| Offline | `./gradlew --offline jar unitTests` |
| Enable the optional adapters | `./gradlew jar unitTests -Padapters=poi,pdfbox,jsoup,jsqlparser [-PinternalRepoUrl=URL \| -PadapterLibDir=DIR]` |
| Build and test without Gradle | `bash infrastructure/ci/build-without-gradle.sh` (Windows: `powershell -File infrastructure\ci\build-without-gradle.ps1`); the jar is `build/plain/casc-generator.jar` |
| Without Gradle, with adapters | `ADAPTERS=poi,pdfbox,jsoup ADAPTER_LIB_DIR=DIR bash infrastructure/ci/build-without-gradle.sh` |
| Verify a built jar | `bash infrastructure/ci/verify.sh` (Windows: `powershell -File infrastructure\ci\verify.ps1`; `CASC_JAR` selects the jar) |
| Run any command through a launcher | `infrastructure/launchers/run-cli.sh COMMAND ...` (Windows: `run-cli.bat`) |
| Start the web UI through a launcher | `infrastructure/launchers/run-web.sh` (Windows: `run-web.bat`) |

More: `docs/BUILD.md`, `docs/ADAPTERS.md`, `docs/INGESTION.md`, `docs/INTEGRATION.md`, `docs/FINDINGS.md`.

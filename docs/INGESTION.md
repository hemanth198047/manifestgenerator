# Ingestion reference (Phase 3)

Sources are read into a `RawMetadataBundle` first (tables, columns, terms, notes, lineage hints, findings, with the source and
location of everything). A manifest is only generated afterwards, and only as an unreviewed draft.

## Rules that apply to every source
- Text must be valid UTF-8 (a BOM is removed). Invalid bytes make the source unreadable; they are never replaced.
- Unsupported formats are errors with an alternative, never skipped: XLSX/XLS (Apache POI), DOCX/DOC (Apache POI), PDF (Apache PDFBox),
  HTML (an approved HTML parser), JSON/YAML (planned OpenAPI adapter). Convert to CSV, TSV, text or Markdown.
- Row values from data files are not retained, only column names, inferred types and counts.
- The same table defined by two sources is reported as `TABLE_CONFLICT` and not merged. Columns with the same name in different
  tables are never merged.
- Nothing in a source is executed or followed as an instruction.

## CSV and TSV
- Quoted fields, doubled quotes, delimiters and line breaks inside quotes; LF, CRLF or CR line ends; blank lines skipped.
- Rejected with the line number (never guessed): a quote inside an unquoted field, text after a closing quote, an unterminated quote.
- Options: `delimiter` (comma, tab, semicolon, pipe or one character; `.tsv` defaults to tab), `hasHeader`, `headerRow`, `maxRows`
  (sampling limit), `mode` (`auto`, `data`, `dictionary`) and `map.table|column|description|type|schema` to name the header of each field.
- `data` mode: one table named after the file; types (INTEGER, DECIMAL, DATE, TIMESTAMP, BOOLEAN, TEXT) are inferred from at most
  `maxRows` rows and flagged as inferred.
- `dictionary` mode: rows describe columns. Recognised headers (case and punctuation ignored): table/entity, column/field/attribute/element,
  description/definition/comment, type/data type, schema. `auto` picks dictionary when a column-name header and a description or type
  header exist. Without a table column the rows become candidate terms. `schema.table` in a table cell is split when it contains exactly one dot.
- XLSX sheet selection is not available (needs POI). Export the sheet to CSV.

## TXT and Markdown
Extracted: `#` headings (kept as notes); pipe tables with a Column/Field header plus Description or Type (table name from a Table column or the
nearest heading); candidate terms from `NAME: description` lines when NAME contains `_` or `.`, is camelCase, or is wrapped in backticks or bold;
lines with `->`, an arrow or wording such as "derived from" (kept as text, no structure inferred). Fenced code blocks are skipped and reported.
Not detected: interface definitions, free-prose field definitions, tables without a recognised header.

## SQL subset (never executed)
Supported: `CREATE [OR REPLACE] [GLOBAL|LOCAL] [TEMPORARY|VOLATILE|SET|MULTISET|TRANSIENT|...] TABLE`, column name, type, `NOT NULL`, `NULL`,
`PRIMARY KEY`, inline `COMMENT 'text'` (Snowflake style), table-level and `ALTER TABLE ... ADD CONSTRAINT ... PRIMARY KEY`,
`ALTER TABLE ... ADD [COLUMN] ...` (single, comma separated or parenthesised), `COMMENT ON TABLE|VIEW|COLUMN ... IS '...'`,
`CREATE [OR REPLACE] VIEW name [(columns)] AS SELECT ...` (column names when explicit or when the select list is plain columns).
View lineage hints are produced only for a single-table SELECT of plain columns; they are shown for review and are never written to a manifest.
Dialect handling is limited to tokenizing and batch separators: `[bracket]` identifiers (SQL Server, Sybase), `GO` (SQL Server, Sybase, auto),
`/` on its own line (Oracle, auto), `$$` strings (PostgreSQL), Teradata table options before the column list. Dialect samples are in the tests.
Names have up to three parts (database.schema.table); case is kept as written.

Reported, not applied: other ALTER actions, DROP, TRUNCATE, RENAME, CREATE TABLE AS/LIKE (table recorded without columns), external tables.
Reported, not analysed: INSERT, UPDATE, DELETE, MERGE, SELECT, CALL and similar DML (no lineage is inferred from them).
Skipped quietly (INFO): GRANT, indexes, sequences, functions, transactions. Column options such as DEFAULT, REFERENCES, CHECK, IDENTITY and UNIQUE/FOREIGN KEY/CHECK constraints are
not extracted. Oracle `q'[...]'` literals are not understood. Analysing general DML or complex queries needs an approved SQL parser (for example JSqlParser).

## Draft manifest
Contains database, schemas, tables and columns with descriptions. Not inferred: logical services, logical data elements (terms stay candidates),
table type, PII, attestations, system keys, lineage. Needs a database logical name and, for tables without a schema, a default schema.
The draft is validated like any manifest and carries one UNRESOLVED_DECISION warning.

## Commands
```
casc-generator ingest --input-files ./specs --root ./specs                      # inspect only
casc-generator ingest --input-files ./specs --db-name TRADE_DB --db-type Oracle --default-schema trade --output ./manifest.json
casc-generator ingest --input-files "./my specs/schema.sql" --bundle-output ./bundle.json --json
```
Exit codes: 0 success, 1 a source failed or the draft has validation errors (nothing written), 2 usage error, 3 I/O error, 4 internal error.
`--root` (or `CASC_INPUT_ROOT`) limits where files may be read, after symbolic links are resolved. Directory scans go four levels deep, skip hidden files
and symbolic links, ignore unknown file types (reported as notes), and read files in sorted order of their relative path with `/` separators.

## REST contracts (also the future agent tool contracts)
- `POST /api/ingest` with `{"sources":[{"name","text"}],"options":{...}}` returns the bundle, including findings and `hasErrors`.
- `POST /api/draft?format=json|yaml` with the same body plus `"system":{"databaseLogicalName","databaseType","defaultSchema"}` returns the draft,
  validation report and unresolved decisions (HTTP 422 if the draft cannot be built).

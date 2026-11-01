# Ingestion reference (Phases 3 and 4)

Sources are read into a `RawMetadataBundle` first (tables, columns, terms, notes, lineage hints, findings, with the source and
location of everything). A manifest is only generated afterwards, and only as an unreviewed draft.

## Rules that apply to every source
- Text must be valid UTF-8 (a BOM is removed). Invalid bytes make the source unreadable; they are never replaced.
- Formats that need a library are read only when the matching **optional adapter** is enabled (`docs/ADAPTERS.md`): XLSX/XLS and DOCX (Apache POI), PDF (Apache PDFBox), HTML and Confluence (jsoup),
  SQL DML lineage (JSqlParser). Without the adapter they are errors with an alternative, never skipped. YAML always needs an approved YAML parser (none is supplied). Convert to CSV, TSV, text, Markdown or (for OpenAPI) JSON.
- Row values from data files are not retained, only column names, inferred types and counts.
- The same table defined by two sources is reported as `TABLE_CONFLICT` and not merged. Columns with the same name in different
  tables are never merged.
- Nothing in a source is executed or followed as an instruction.

## CSV and TSV
- Quoted fields, doubled quotes, delimiters and line breaks inside quotes; LF, CRLF or CR line ends; blank lines skipped.
- Line breaks inside a quoted value are stored as LF whether the file used LF, CRLF or CR, so output does not depend on the operating system that saved the file.
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

## OpenAPI and Swagger JSON
A `.json` source must be OpenAPI 3.x or Swagger 2.0 (top-level `openapi` or `swagger`); other JSON is rejected with a reason. Directory scans include a `.json`
file only if it mentions "openapi" or "swagger". OpenAPI YAML is unavailable (needs a YAML parser).
- Each operation (path and method, in a fixed method order) becomes a **candidate interface** named by `operationId` or `METHOD /path`.
  Its request body (or Swagger body parameter) and its first of the 200, 201, 202, 203 or default responses are flattened into hierarchical field paths such as
  `client.clientId` and `items[].sku`, with type, description and role (request or response). Parameters in the query, path or header are not extracted.
- Each schema under `components.schemas` (or `definitions`) becomes a **candidate structure**. No relational table is ever created from a schema.
- Only local `#/...` references are followed. External references are reported and **never fetched**. Cycles and nesting deeper than 8 levels are cut off and reported.
  allOf is merged; oneOf/anyOf alternatives are merged into one candidate list and flagged.
- The SOURCING/DISTRIBUTION direction is never inferred. A draft includes interfaces only when you choose a direction: DISTRIBUTION lists response fields and
  SOURCING lists request fields (provisional rule; review it). Structures stay candidates and are not turned into data entities.

## URL sources
URLs are fetched with the JDK `HttpClient` and then handled exactly like files. Everything below is enforced in code and covered by tests.
- **Disabled until configured.** An empty allowlist blocks every URL. Configure `--url-allow HOST` (repeatable) or `CASC_URL_ALLOW=host1,host2`.
  Entries are `host`, `host:port` or `*.domain` (subdomains only). Without a port only the default port of the scheme is allowed.
- **https only** unless `--url-allow-http` is given. URLs with embedded credentials are refused.
- **Always refused**, even if listed: loopback, link-local (including 169.254.169.254), unspecified and multicast addresses, the Alibaba and AWS IPv6 metadata
  addresses, and metadata host names. Every address a name resolves to is checked. Private (RFC 1918) addresses are allowed only for allowlisted hosts.
- **Redirects**: at most 3, each hop is checked again, https is never downgraded to http.
- **Limits**: connect and overall time limit (default 15 s), response size limit (default 5 MiB), at most 10 URLs per request, UTF-8 text only.
- **Credentials**: an optional bearer token is read from `CASC_URL_TOKEN` and sent only to the host of the original URL, never after a redirect to another host.
  Query strings are never logged, shown or kept in source names (they are replaced by `?...`).
- **TLS**: JVM default trust, never weakened. Approved truststores and proxies use the standard JVM properties (see `infrastructure/README.md`).
- **Formats**: the URL path extension decides the format, otherwise the Content-Type: JSON, plain text, Markdown, CSV, TSV and SQL are read. HTML, YAML, Office and PDF
  responses are reported as unavailable with the alternative. Confluence pages are HTML even through its JSON API, so use a Markdown, text or CSV export or a plain-text endpoint.
- **Residual risk**: the connection resolves the host name again after the check, so a hostile DNS server could answer differently. The allowlist limits this to approved names;
  also rely on approved DNS and network controls. On a shared web deployment fetched content is shown to the requesting user, so require authentication (see D-015).

## Spreadsheets, Word, PDF and HTML (optional adapters)
Read as described in `docs/ADAPTERS.md`; they reuse the rules above: spreadsheet sheets follow the CSV rules (`--sheet` selects one), Word and HTML tables follow the Markdown table rules, and prose in Word, PDF and HTML
is scanned for candidate terms and lineage text. Locations name the sheet, table, paragraph or page. Binary files are carried as bytes (`contentBase64` in JSON requests).

## Draft manifest
Contains database, schemas, tables and columns with descriptions, and interfaces when a direction is chosen. Not inferred: logical services, logical data elements (terms stay candidates),
data entities (API structures stay candidates), table type, PII, attestations, system keys, lineage. Needs a database logical name when tables are present and, for tables without a schema, a default schema.
The draft is validated like any manifest and carries one UNRESOLVED_DECISION warning.

## Commands
```
casc-generator ingest --input-files ./specs --root ./specs                      # inspect only
casc-generator ingest --input-files ./specs --db-name TRADE_DB --db-type Oracle --default-schema trade --output ./manifest.json
casc-generator ingest --input-files "./my specs/schema.sql" --bundle-output ./bundle.json --json
casc-generator ingest --input-files ./trade-api.json --interface-direction DISTRIBUTION --output ./manifest.yaml --format yaml
casc-generator ingest --url https://wiki.corp.example/api/trade.json --url-allow wiki.corp.example --interface-direction DISTRIBUTION --output ./manifest.json
```
Exit codes: 0 success, 1 a source failed or the draft has validation errors (nothing written), 2 usage error, 3 I/O error, 4 internal error.
`--root` (or `CASC_INPUT_ROOT`) limits where files may be read, after symbolic links are resolved. Directory scans go four levels deep, skip hidden files
and symbolic links, ignore unknown file types (reported as notes), and read files in sorted order of their relative path with `/` separators.

## REST contracts (also the future agent tool contracts)
- `POST /api/ingest` with `{"sources":[{"name","text"}],"urls":["https://..."],"options":{...}}` returns the bundle, including findings and `hasErrors`. At least one source or URL is required.
- `POST /api/draft?format=json|yaml` with the same body plus `"system":{"databaseLogicalName","databaseType","defaultSchema","interfaceDirection"}` returns the draft,
  validation report and unresolved decisions (HTTP 422 if the draft cannot be built).

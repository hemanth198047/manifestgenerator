# Findings reference

Every finding the application reports has a code, a path (where in the file), a message and a suggested fix. This page explains each code in plain language.
Get the same text from the command line: `casc-generator explain CODE` (or `explain` alone to list all codes).

Severity: **ERROR** blocks export (nothing is written); **WARNING** needs your attention but does not block; **INFO** is for your information.
Validation level **LOCAL** means the manifest was checked only against this tool's provisional rules. It is not the official CasC schema and not FDC acceptance.

This file is generated from `FindingGuide.java`; a test fails if they differ.

## ADAPTER_LIBRARY_MISSING

**Adapter library is missing**

- **What it means:** The adapter is present but its library is not on the class path.
- **How to fix it:** Add the approved library as described in the adapter guide, or provide a text export.
- **Where to look:** docs/ADAPTERS.md

## ATTEST_MISSING

**No sensitive-data attestation**

- **What it means:** Every column or field needs a reviewed sensitive-data attestation (Yes or No). The tool never decides this for you.
- **How to fix it:** After review, set sensitiveDataAttestation to Yes or No at the reported path.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## BOOL_MISSING

**isDeployedOnPrem is not stated**

- **What it means:** A logical service must say explicitly whether it is deployed on premises. A missing value is not treated as false.
- **How to fix it:** Set isDeployedOnPrem to true or false.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## CELL_TEXT_AS_SHOWN

**Cells read as displayed**

- **What it means:** Values are read as Excel shows them; formulas use their calculated value; macros never run.
- **How to fix it:** Check dates and numbers against the source.
- **Where to look:** docs/ADAPTERS.md

## CLOUD_REQUIRED

**cloudProvider is missing**

- **What it means:** A service that is not deployed on premises must name its cloud provider.
- **How to fix it:** Set cloudProvider, or set isDeployedOnPrem to true if the service is on premises.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## CLOUD_UNEXPECTED

**cloudProvider on an on-premises service**

- **What it means:** cloudProvider is set although the service is on premises, which contradicts itself.
- **How to fix it:** Remove cloudProvider, or set isDeployedOnPrem to false.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## COLUMN_METADATA_COLUMN_MISSING

**Column metadata header incomplete**

- **What it means:** The column metadata file lacks one of the three required columns.
- **How to fix it:** Use the header `table name, column name, system data element`.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## COLUMN_METADATA_DUPLICATE

**Column listed twice in the column metadata**

- **What it means:** The same table and column appear twice; the first row was used.
- **How to fix it:** Delete the duplicate row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## COLUMN_METADATA_NOT_FOUND

**Column metadata row matches nothing**

- **What it means:** The table or column in the column metadata file does not exist in the source (manifest or SQL), so that system data element was not used.
- **How to fix it:** Correct the table or column name (matching ignores case), or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## COLUMN_METADATA_ROW_SKIPPED

**Column metadata row skipped**

- **What it means:** A row is missing its table name, column name or system data element (or the file is longer than the supported number of rows).
- **How to fix it:** Fill in all three values or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## COLUMN_METADATA_UNREADABLE

**Column metadata file unusable**

- **What it means:** The optional column metadata file (system data element per column) could not be read or has no rows.
- **How to fix it:** Fix the file and run again.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## COMBINE_DUPLICATE_DATASET

**Dataset appears twice**

- **What it means:** A dataset with the same name was already added, so this copy was left out.
- **How to fix it:** Remove the duplicate from the input, or check that the files are really different tables.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_FIELD_DROPPED

**Other top-level fields left out**

- **What it means:** The combined manifest holds only "datasets"; any other top-level field of the input files is not carried over.
- **How to fix it:** If you need it, add it to the combined file by hand.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_FILE_SKIPPED

**File without datasets skipped**

- **What it means:** The file has no "datasets" section with entries, so nothing was taken from it.
- **How to fix it:** Give dataset manifests only, such as the manifest_<table>.json files.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_FILE_UNREADABLE

**A file to combine could not be read**

- **What it means:** A dataset manifest file is missing, too large or not valid JSON, so nothing was combined.
- **How to fix it:** Fix or remove that file and run again.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_NOTHING_SELECTED

**No datasets selected**

- **What it means:** Nothing matched the input and the selection, so nothing was written.
- **How to fix it:** Check the input files and the count or table names.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_SUMMARY

**Summary of a combine run**

- **What it means:** How many datasets were read, from how many files, and how many were combined (all, the first N, or the named tables).
- **How to fix it:** None needed.
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## COMBINE_TABLE_NOT_FOUND

**Requested table not found**

- **What it means:** A table named in --tables has no dataset in the input, so it is not in the combined manifest.
- **How to fix it:** Check the spelling (matching ignores case; use the table name or schema.table).
- **Where to look:** docs/INGESTION.md (Combining dataset manifests)

## CSV_BLANK_HEADER

**A column header is blank**

- **What it means:** A header cell is empty, so the tool named it automatically.
- **How to fix it:** Give the column a name in the source file.
- **Where to look:** docs/INGESTION.md

## CSV_DUPLICATE_HEADER

**Two columns have the same header**

- **What it means:** Both columns are kept, which can confuse later steps.
- **How to fix it:** Rename one of the columns in the source.
- **Where to look:** docs/INGESTION.md

## CSV_NO_HEADER

**No header row found**

- **What it means:** The header row is missing or empty.
- **How to fix it:** Check the headerRow and hasHeader options, or that the file is not empty.
- **Where to look:** docs/INGESTION.md

## CSV_RAGGED

**Rows have different numbers of fields**

- **What it means:** Some rows have more or fewer cells than the header; missing cells are read as empty.
- **How to fix it:** Check quoting and the delimiter in the source.
- **Where to look:** docs/INGESTION.md

## CSV_ROWS_BEFORE_HEADER

**Rows before the header were skipped**

- **What it means:** Rows above the header row were ignored.
- **How to fix it:** Set headerRow if the header is not where the tool looked.
- **Where to look:** docs/INGESTION.md

## CSV_SYNTAX

**CSV quoting error**

- **What it means:** The file breaks CSV rules (unterminated quote, quote inside an unquoted value, text after a closing quote).
- **How to fix it:** Fix the quoting at the reported line or export the file again as standard CSV.
- **Where to look:** docs/INGESTION.md

## DATASET_DATABASE_MISSING

**Dataset names without a database**

- **What it means:** Dataset names are database.schema.table. Some tables have no database (a manifest database without a logicalName, or SQL tables not written as database.schema.table), so their names are schema.table only.
- **How to fix it:** Pass --database-name NAME, or give the database in the source, then run again with --overwrite.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## DATASET_SCHEMA_MISSING

**SQL table without a schema**

- **What it means:** A table in the SQL has no schema (it is not written as schema.table) and no default schema was given, so its dataset is named with the table name only.
- **How to fix it:** Qualify the table names in the SQL, or pass --default-schema SCHEMA, then run again with --overwrite.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## DATASET_SUMMARY

**Summary of a dataset-manifest run**

- **What it means:** How many tables were read from the source, how many rows the metadata CSV has and how many manifests were generated. Only tables that have a metadata row are generated, so the number generated is at most the number of metadata rows.
- **How to fix it:** If fewer manifests were generated than expected, read the warnings above it: rows that match no table, duplicate rows, tables without a row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## DATASET_VIEW_SKIPPED

**SQL views not mapped**

- **What it means:** Only tables get a dataset manifest; CREATE VIEW statements were skipped.
- **How to fix it:** Nothing to do unless a view needs a dataset manifest.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## DB_NAME_PROVISIONAL

**Database name contains a space**

- **What it means:** The naming rules for TAI logical database names are not confirmed; names with whitespace may be rejected later.
- **How to fix it:** Check the name against the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository) and remove the whitespace if the rules forbid it.
- **Where to look:** the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## DICT_COLUMN_UNMAPPED

**Data dictionary columns not recognised**

- **What it means:** The file looks like a data dictionary but no column-name header was found.
- **How to fix it:** Add a header such as Column or Field, or set map.column to the right header.
- **Where to look:** docs/INGESTION.md

## DICT_DUPLICATE_COLUMN

**Column listed twice in a dictionary**

- **What it means:** The same column appears twice for one table; both are kept.
- **How to fix it:** Remove or rename the duplicate before generating a manifest.
- **Where to look:** docs/INGESTION.md

## DICT_ROW_SKIPPED

**Dictionary row skipped**

- **What it means:** A row has no column name and was not used.
- **How to fix it:** Fill in the column name or delete the row.
- **Where to look:** docs/INGESTION.md

## DICT_TRUNCATED

**Only the first rows were read**

- **What it means:** The row limit (maxRows) was reached.
- **How to fix it:** Raise maxRows to read the rest.
- **Where to look:** docs/INGESTION.md

## DOCX_ENCRYPTED

**Document is password protected**

- **What it means:** No password is ever tried.
- **How to fix it:** Remove the protection and add the file again.
- **Where to look:** docs/ADAPTERS.md

## DOCX_TABLE_IGNORED

**Small Word table ignored**

- **What it means:** A table with fewer than two rows was ignored.
- **How to fix it:** None needed.
- **Where to look:** docs/ADAPTERS.md

## DOCX_TABLE_RAGGED

**Word table rows differ in width**

- **What it means:** Rows have different numbers of cells.
- **How to fix it:** Check the table.
- **Where to look:** docs/ADAPTERS.md

## DOCX_UNREADABLE

**Word file could not be read**

- **What it means:** The file is not a valid .docx (old .doc is not supported).
- **How to fix it:** Check the file or save it as text or Markdown.
- **Where to look:** docs/ADAPTERS.md

## DRAFT_DB_NAME_REQUIRED

**Database name needed for the draft**

- **What it means:** A draft manifest needs the TAI logical database name.
- **How to fix it:** Provide it (`--db-name`, or the System step in the web UI).
- **Where to look:** docs/INGESTION.md

## DRAFT_DIRECTION_REQUIRED

**Interface direction needed**

- **What it means:** Only interface candidates were found and the direction cannot be guessed.
- **How to fix it:** Choose SOURCING (the system consumes data) or DISTRIBUTION (it provides data).
- **Where to look:** docs/INGESTION.md

## DRAFT_INTERFACES_NOT_INCLUDED

**Interfaces left out of the draft**

- **What it means:** No interface direction was chosen, so they were not included.
- **How to fix it:** Choose SOURCING or DISTRIBUTION to include them.
- **Where to look:** docs/INGESTION.md

## DRAFT_INTERFACE_EMPTY

**Interface has no fields**

- **What it means:** The chosen direction has no request or response fields to list.
- **How to fix it:** Add the fields by hand if needed.
- **Where to look:** docs/INGESTION.md

## DRAFT_NO_TABLES

**Nothing to build a draft from**

- **What it means:** The sources contain no tables or interfaces.
- **How to fix it:** Add DDL, a data dictionary, a data file or an OpenAPI document.
- **Where to look:** docs/INGESTION.md

## DRAFT_SCHEMA_REQUIRED

**Default schema needed**

- **What it means:** Some tables (for example from CSV files) have no schema.
- **How to fix it:** Provide the schema they belong to (`--default-schema`).
- **Where to look:** docs/INGESTION.md

## DRAFT_STRUCTURES_NOT_INCLUDED

**Candidate structures not used**

- **What it means:** API structures were not turned into data entities.
- **How to fix it:** Review and add the ones you want.
- **Where to look:** docs/INGESTION.md

## DRAFT_TERMS_NOT_INCLUDED

**Candidate terms not used**

- **What it means:** Terms were not turned into logical data elements.
- **How to fix it:** Review and add the ones you want.
- **Where to look:** docs/INGESTION.md

## DRAFT_VIEW_EXCLUDED

**Views left out of the draft**

- **What it means:** Views are not included in drafts.
- **How to fix it:** Add them by hand if the specification expects them.
- **Where to look:** docs/INGESTION.md

## DUP_NAME

**A name is used twice**

- **What it means:** Names must be unique inside their scope (for example two tables in one schema). Duplicates make references ambiguous.
- **How to fix it:** Rename or remove one of the duplicates named in the message.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## ENUM_INVALID

**A value is not one of the allowed choices**

- **What it means:** The field accepts only a fixed list of values and matching is exact and case-sensitive (for example SOURCING, not sourcing).
- **How to fix it:** Replace the value with exactly one of the allowed values listed in the message.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## FORMAT_UNAVAILABLE

**File type needs an optional adapter**

- **What it means:** XLSX, DOCX, PDF and HTML/Confluence are read only when the matching adapter and its library are installed; this build does not have it.
- **How to fix it:** Export the content to CSV, TSV, text or Markdown, or install the adapter as described in the adapter guide.
- **Where to look:** docs/ADAPTERS.md

## FORMAT_UNKNOWN

**File type not supported**

- **What it means:** The file extension is not one this build reads.
- **How to fix it:** Convert the file to a supported type (the message lists them), such as CSV, TSV, SQL, Markdown, text or OpenAPI JSON.
- **Where to look:** docs/INGESTION.md

## FORMULA_UNREADABLE

**Formulas could not be evaluated**

- **What it means:** Those cells were read as empty.
- **How to fix it:** Check the formulas.
- **Where to look:** docs/ADAPTERS.md

## HTML_TABLE_IGNORED

**Small HTML table ignored**

- **What it means:** A table with fewer than two rows was ignored.
- **How to fix it:** None needed.
- **Where to look:** docs/ADAPTERS.md

## HTML_TABLE_RAGGED

**HTML table rows differ in width**

- **What it means:** Rows have different numbers of cells.
- **How to fix it:** Check the table.
- **Where to look:** docs/ADAPTERS.md

## HTML_UNREADABLE

**HTML could not be parsed**

- **What it means:** The page could not be read.
- **How to fix it:** Save the content as Markdown or text.
- **Where to look:** docs/ADAPTERS.md

## JSON_SYNTAX

**The JSON text is not valid**

- **What it means:** The file cannot be parsed as JSON, so nothing in it was read.
- **How to fix it:** Fix the syntax at the reported line and column (missing or extra commas, quotes or brackets). A JSON checker in your editor shows the exact spot.
- **Where to look:** docs/INGESTION.md

## KEY_BLANK

**systemKey is present but empty**

- **What it means:** A systemKey was written as empty text, which cannot identify anything.
- **How to fix it:** Give the systemKey a value, or remove the field.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## KEY_DUP

**systemKey used twice**

- **What it means:** In this tool's provisional rules every systemKey must be unique across the whole manifest.
- **How to fix it:** Choose a different systemKey for one of the two places named in the message.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## LINEAGE_JSON_INVALID

**Lineage JSON typed in the wizard is invalid**

- **What it means:** The text entered for sourcedFromColumns is not valid JSON.
- **How to fix it:** Fix the JSON or clear the field.
- **Where to look:** docs/WIZARD.md

## LINEAGE_UNVERIFIED

**Lineage is passed through unchecked**

- **What it means:** `sourcedFromColumns` is kept exactly as written; its structure and references are not validated because the official shape is not confirmed.
- **How to fix it:** Check the entry against the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository).
- **Where to look:** the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## MANIFEST_LENIENT_READ

**Manifest has JSON syntax errors but was read**

- **What it means:** The file is not strictly valid JSON (for example trailing or missing commas). Only the `databases` section was read, tolerantly. Tables were still found, so manifests were generated.
- **How to fix it:** Fix the syntax errors in the manifest so that other tools can read it too.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_NOT_OBJECT

**Manifest is not a JSON object**

- **What it means:** The top level of the file must be an object { }.
- **How to fix it:** Check that you gave the right file.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_NO_DATABASES

**No databases section**

- **What it means:** The manifest has no `databases` section to read tables from.
- **How to fix it:** Add databases[].schemas[].tables[] to the manifest, or give the right file.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_NO_TABLES

**No tables in the manifest**

- **What it means:** The `databases` section has no tables.
- **How to fix it:** Add tables under databases[].schemas[].tables[].
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_SYNTAX

**Manifest is not valid JSON**

- **What it means:** The file has JSON syntax errors and its `databases` section could not be located, so no tables were read.
- **How to fix it:** Fix the JSON syntax (the message gives the position), then run again.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_TABLE_SKIPPED

**Table entries without a physicalName**

- **What it means:** Some entries under tables[] have no physicalName, so they could not be read and no dataset manifest can be made for them. The message gives the position of the first one.
- **How to fix it:** Add physicalName to those entries.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MANIFEST_UNREADABLE

**Manifest file has no text**

- **What it means:** The manifest could not be read (empty or not valid UTF-8).
- **How to fix it:** Fix the file and run the command again.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## MD_TABLE_IGNORED

**Not a Markdown table**

- **What it means:** A line starting with | has no header separator row, so it was not read as a table.
- **How to fix it:** Use a standard Markdown table or provide a CSV.
- **Where to look:** docs/INGESTION.md

## MD_TABLE_RAGGED

**Markdown table rows differ in width**

- **What it means:** Some rows have a different number of cells than the header.
- **How to fix it:** Check for unescaped | characters.
- **Where to look:** docs/INGESTION.md

## METADATA_COLUMN_MISSING

**Metadata column missing**

- **What it means:** The CSV lacks one of the five required columns.
- **How to fix it:** Use the header `table name, domain, boundary, upstream dataset, producer eonid` (with --no-upstream only `table name, domain, boundary` is needed).
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_DUPLICATE_TABLE

**Table listed twice in the metadata**

- **What it means:** Only the first row for the table was used.
- **How to fix it:** Delete the duplicate row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_EMPTY

**Metadata file is empty**

- **What it means:** There is no header or no rows.
- **How to fix it:** Add the header `table name, domain, boundary, upstream dataset, producer eonid` and one row per table.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_EON_NOT_NUMERIC

**Producer EON ID is not a number**

- **What it means:** The value was written as text instead of a number.
- **How to fix it:** Use the numeric EON ID.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_ROW_SKIPPED

**Metadata row skipped**

- **What it means:** A row without a table name cannot be matched.
- **How to fix it:** Fill in the table name or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_TABLE_NOT_IN_MANIFEST

**Metadata row has no matching table**

- **What it means:** The table named in the CSV is not defined in the manifest, so no manifest was generated for it. Other tables are not affected.
- **How to fix it:** Correct the table name in the CSV (names are matched ignoring case), or add the table to the manifest, or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_TRUNCATED

**Metadata file too long**

- **What it means:** Only the supported number of rows was read.
- **How to fix it:** Split the file.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_UNREADABLE

**Metadata file has no text**

- **What it means:** The table metadata CSV could not be read.
- **How to fix it:** Fix the file and run the command again.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## METADATA_VALUE_MISSING

**A metadata value is empty**

- **What it means:** The domain, boundary, upstream dataset or producer EON ID is empty, so that part is missing from the generated manifest.
- **How to fix it:** Fill in the value in the CSV and run again with --overwrite.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## NAME_QUALIFIED

**A name contains a period**

- **What it means:** Schema, table and column names must be unqualified: no database or schema prefix and no periods.
- **How to fix it:** Keep only the name itself (for example `TAPSAccount`, not `a2oltp.TAPSAccount`).
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## NO_SOURCES

**No input files**

- **What it means:** Nothing was given to read.
- **How to fix it:** Add at least one file (or URL).
- **Where to look:** docs/INGESTION.md

## NO_TABLES_MATCHED

**No table matched**

- **What it means:** None of the tables in the CSV exist in the manifest, so nothing was written.
- **How to fix it:** Compare the table names in both files.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## OPENAPI_INTERPRETATION

**How OpenAPI is read**

- **What it means:** Operations become candidate interfaces and schemas become candidate structures. The SOURCING/DISTRIBUTION direction is never guessed.
- **How to fix it:** Choose the direction for each interface during review.
- **Where to look:** docs/INGESTION.md

## OPENAPI_NOT_RECOGNISED

**JSON is not OpenAPI**

- **What it means:** Only OpenAPI/Swagger JSON is read as a JSON source. A CasC manifest or a dataset manifest is not a source.
- **How to fix it:** To check a manifest use `validate`. To build dataset manifests use `ingest --manifest-input`.
- **Where to look:** docs/INGESTION.md

## OPENAPI_NO_OPERATIONS

**No operations in the OpenAPI file**

- **What it means:** The document has no operations under `paths`.
- **How to fix it:** Check that this is the right file.
- **Where to look:** docs/INGESTION.md

## PDF_ENCRYPTED

**PDF is encrypted**

- **What it means:** No password is ever tried.
- **How to fix it:** Remove the protection or provide a text export.
- **Where to look:** docs/ADAPTERS.md

## PDF_NO_TEXT

**PDF has no text**

- **What it means:** It is a scan or image-only; OCR is not available.
- **How to fix it:** Provide a text export, or run OCR with an approved tool.
- **Where to look:** docs/ADAPTERS.md

## PDF_PAGE_LIMIT

**Only some PDF pages read**

- **What it means:** The page limit was reached.
- **How to fix it:** Split the document.
- **Where to look:** docs/ADAPTERS.md

## PDF_TABLES_NOT_RECONSTRUCTED

**PDF tables not rebuilt**

- **What it means:** Only text was extracted; tables are not reconstructed and column order may be wrong.
- **How to fix it:** Export tables as CSV and review each candidate term.
- **Where to look:** docs/ADAPTERS.md

## PDF_TEXT_LIMIT

**PDF text limit reached**

- **What it means:** The rest of the text was not read.
- **How to fix it:** Split the document.
- **Where to look:** docs/ADAPTERS.md

## PDF_UNREADABLE

**PDF could not be read**

- **What it means:** The file is not a valid PDF.
- **How to fix it:** Check the file or provide a text export.
- **Where to look:** docs/ADAPTERS.md

## PII_CATEGORY_REQUIRED

**PII without a data subject category**

- **What it means:** PII elements need at least one data subject category.
- **How to fix it:** Add dataSubjectCategories with at least one of: Client, Worker, Third Party.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## PII_CONFLICT

**PII elements without a Yes attestation**

- **What it means:** PII elements are listed but the attestation is No or missing, which contradicts each other.
- **How to fix it:** Set the attestation to Yes, or remove the PII elements if the review shows there are none.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## PII_REQUIRED

**Attestation is Yes but no PII elements**

- **What it means:** If a column is attested as holding sensitive data, the PII elements must be listed.
- **How to fix it:** List at least one piiElements entry, or change the attestation to No if that is the correct answer after review.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## REF_ELEMENT

**Reference to a data element that is not declared**

- **What it means:** A name used as a system data element, source or target does not match any entry in the manifest's systemDataElements section.
- **How to fix it:** Add the element to systemDataElements, or correct the name so it matches exactly.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## REF_LOGICAL_SERVICE

**Reference to a logical service that is not declared**

- **What it means:** A name listed under logicalServices does not match any entry in the manifest's logicalServices section (names are case-sensitive).
- **How to fix it:** Add the service to logicalServices, or correct the name so it matches exactly.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## REQ_BLANK

**A required value is blank or empty**

- **What it means:** A field that must have a value is missing, empty or only spaces.
- **How to fix it:** Provide a non-blank value at the reported path.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## REQ_MISSING

**A required field is missing**

- **What it means:** A mandatory field is absent. For an interface, `type` is required: SOURCING means the system consumes the data, DISTRIBUTION means the system provides it.
- **How to fix it:** Add the field at the reported path with a valid value.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## ROUNDTRIP_MISMATCH

**Export check failed**

- **What it means:** The generated JSON was read back and did not give the same output. This is a defect in the tool, not in your data.
- **How to fix it:** Do not use the file. Report the defect with the input that caused it.
- **Where to look:** docs/VERIFICATION.md

## ROUNDTRIP_UNAVAILABLE

**Export check not available for YAML**

- **What it means:** The read-back check needs a YAML parser, which is not supplied.
- **How to fix it:** Export JSON for a checked file, or check the YAML with an approved YAML tool.
- **Where to look:** docs/CAPABILITIES.md

## SAMPLING_LIMIT

**Only some rows were sampled**

- **What it means:** Types were inferred from the first rows only (maxRows).
- **How to fix it:** Raise maxRows to sample more rows.
- **Where to look:** docs/INGESTION.md

## SHEET_EMPTY

**Empty sheet skipped**

- **What it means:** The sheet has no data.
- **How to fix it:** None needed.
- **Where to look:** docs/ADAPTERS.md

## SHEET_HIDDEN

**Hidden sheet skipped**

- **What it means:** Hidden sheets are not read by default.
- **How to fix it:** Unhide it or select it with the sheet option.
- **Where to look:** docs/ADAPTERS.md

## SHEET_LIMIT

**Only some sheets read**

- **What it means:** The sheet limit was reached.
- **How to fix it:** Select a sheet with the sheet option.
- **Where to look:** docs/ADAPTERS.md

## SHEET_NOT_FOUND

**Sheet not found**

- **What it means:** No sheet matches the name or number.
- **How to fix it:** Use a listed sheet name or its 1-based number.
- **Where to look:** docs/ADAPTERS.md

## SHEET_TOO_WIDE

**Sheet has too many columns**

- **What it means:** Extra columns were ignored.
- **How to fix it:** Split the sheet.
- **Where to look:** docs/ADAPTERS.md

## SOURCE_ENCODING

**A binary file was given as text**

- **What it means:** Spreadsheet, Word and PDF files must be sent as raw bytes, not pasted text.
- **How to fix it:** Add the file itself instead of its text.
- **Where to look:** docs/INGESTION.md

## SOURCE_TOO_LARGE

**A file is too large**

- **What it means:** The file is bigger than the size limit (maxSourceChars).
- **How to fix it:** Split the file, or raise the limit with the maxSourceChars option.
- **Where to look:** docs/INGESTION.md

## SOURCE_UNREADABLE

**A file could not be read**

- **What it means:** The file is missing, too large, outside the allowed folder, or not valid UTF-8 text.
- **How to fix it:** Fix the path or the file (save as UTF-8) and add it again.
- **Where to look:** docs/INGESTION.md

## SPREADSHEET_ENCRYPTED

**Workbook is password protected**

- **What it means:** No password is ever tried.
- **How to fix it:** Remove the protection or export the sheets to CSV.
- **Where to look:** docs/ADAPTERS.md

## SPREADSHEET_NOTHING_EXTRACTED

**No usable sheet**

- **What it means:** No visible sheet with data was found.
- **How to fix it:** Check the sheet selection and that sheets are not empty or hidden.
- **Where to look:** docs/ADAPTERS.md

## SPREADSHEET_UNREADABLE

**Spreadsheet could not be read**

- **What it means:** The file is not a valid .xlsx or .xls file.
- **How to fix it:** Check the file or export the sheets to CSV.
- **Where to look:** docs/ADAPTERS.md

## SQL_CONSTRAINT_IGNORED

**Constraints not extracted**

- **What it means:** Only PRIMARY KEY is extracted; UNIQUE, FOREIGN KEY and CHECK are ignored.
- **How to fix it:** None needed unless you need them in the manifest; add them by hand if the specification asks.
- **Where to look:** docs/INGESTION.md

## SQL_DML_ANALYSED

**SQL DML analysed**

- **What it means:** INSERT/UPDATE/MERGE/SELECT were analysed at table level by the JSqlParser adapter. Hints only; never written to a manifest.
- **How to fix it:** Review the hints.
- **Where to look:** docs/ADAPTERS.md

## SQL_DUPLICATE_COLUMN

**Column defined twice in SQL**

- **What it means:** The first definition was kept.
- **How to fix it:** Remove the duplicate.
- **Where to look:** docs/INGESTION.md

## SQL_DUPLICATE_TABLE

**Table defined twice in SQL**

- **What it means:** The later definition replaces the earlier one.
- **How to fix it:** Remove the duplicate definition.
- **Where to look:** docs/INGESTION.md

## SQL_LINEAGE_NOT_INFERRED

**No lineage for a view**

- **What it means:** Joins, expressions or set operations make the lineage unclear.
- **How to fix it:** Document the lineage separately.
- **Where to look:** docs/INGESTION.md

## SQL_NOTHING_EXTRACTED

**No tables found in the SQL**

- **What it means:** No CREATE TABLE or CREATE VIEW statements were found.
- **How to fix it:** Provide DDL, or a data dictionary as CSV.
- **Where to look:** docs/INGESTION.md

## SQL_PARSE_FAILED

**SQL statement not understood**

- **What it means:** A statement is outside the supported SQL subset and was not applied.
- **How to fix it:** Check it against the supported subset in the ingestion guide, or provide a data dictionary instead.
- **Where to look:** docs/INGESTION.md

## SQL_SKIPPED

**SQL statements skipped**

- **What it means:** Statements that do not define structure (for example INSERT) were skipped.
- **How to fix it:** None needed.
- **Where to look:** docs/INGESTION.md

## SQL_SYNTAX

**SQL text error**

- **What it means:** The SQL could not be tokenised, so no statement from the file was analysed.
- **How to fix it:** Fix the SQL at the reported line.
- **Where to look:** docs/INGESTION.md

## SQL_TABLE_OPTIONS_IGNORED

**Table options ignored**

- **What it means:** Options after the column list were not analysed.
- **How to fix it:** None needed.
- **Where to look:** docs/INGESTION.md

## SQL_UNKNOWN_TARGET

**PRIMARY KEY names an unknown column**

- **What it means:** The primary key refers to a column that is not defined on the table.
- **How to fix it:** Check the column name.
- **Where to look:** docs/INGESTION.md

## SQL_UNSUPPORTED

**SQL feature not supported**

- **What it means:** The statement uses a feature this tool does not read.
- **How to fix it:** See the supported subset; supply the structure another way.
- **Where to look:** docs/INGESTION.md

## SQL_VIEW_COLUMNS_UNKNOWN

**View columns not determined**

- **What it means:** The SELECT is too complex to read the view's columns.
- **How to fix it:** Give the view an explicit column list, or supply a data dictionary.
- **Where to look:** docs/INGESTION.md

## TABLE_CONFLICT

**Same table in two sources**

- **What it means:** Two sources define the same table; they are reported, never merged.
- **How to fix it:** Decide which source is authoritative and remove or rename the other.
- **Where to look:** docs/INGESTION.md

## TABLE_DESCRIPTIONS_COLUMN_MISSING

**Table descriptions header incomplete**

- **What it means:** The file has no table column (table name, tables or table) or no description column (description).
- **How to fix it:** Use a header with both columns; other columns are ignored.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_DESCRIPTIONS_UNREADABLE

**Table descriptions file unusable**

- **What it means:** The table descriptions file could not be read or has no rows.
- **How to fix it:** Fix the file: a header with a table column and a description column, then one table per row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_DESCRIPTION_DEFAULT_USED

**Datasets without a description from the file**

- **What it means:** Some generated datasets have no row in the descriptions file, so their description is the one in the source (a SQL comment) or is generated from the table name.
- **How to fix it:** Add a row for each of those tables and run again with --overwrite.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_DESCRIPTION_DUPLICATE

**Table described twice**

- **What it means:** The same table has more than one row in the descriptions file; the first one was used.
- **How to fix it:** Delete the duplicate row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_DESCRIPTION_NOT_FOUND

**Description for a table that is not in the source**

- **What it means:** The table in the descriptions file is not defined in the manifest or SQL, so its description was not used. This is expected when the file covers more tables than you generate.
- **How to fix it:** Correct the name (matching ignores case), or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_DESCRIPTION_ROW_SKIPPED

**Table description row skipped**

- **What it means:** A row is missing its table name or description, the table name is not usable, or the file is longer than the supported number of rows.
- **How to fix it:** Fill in both values or delete the row.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_FROM_HEADING

**Table names taken from headings**

- **What it means:** No table column was found, so rows were assigned to the nearest heading above them.
- **How to fix it:** Check that each heading really is a table name.
- **Where to look:** docs/INGESTION.md

## TABLE_LIST_COLUMN_MISSING

**Table list has no table name column**

- **What it means:** The file has several columns but none is called `table name`.
- **How to fix it:** Add the header `table name`, or use a file with one column of table names.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_LIST_DEFAULTS_USED

**Defaults used for listed tables**

- **What it means:** Some listed tables have no row in the table metadata, so the domain and boundary given with --domain and --boundary were used for them (and they have no upstream dataset).
- **How to fix it:** Add a row for a table to the table metadata if it needs its own domain, boundary or upstream.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_LIST_DUPLICATE

**Table listed twice**

- **What it means:** The same table appears more than once in the table list; the first entry was used.
- **How to fix it:** Delete the duplicate.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_LIST_ENTRY_SKIPPED

**Table list entry skipped**

- **What it means:** An entry is not a usable table name.
- **How to fix it:** Write the table as table, schema.table or database.schema.table.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_LIST_NOT_IN_SOURCE

**Listed table not found in the source**

- **What it means:** A table in the table list is not defined in the manifest or SQL (or is a view), so no manifest was generated for it. A schema.table or database.schema.table must match the source.
- **How to fix it:** Correct the name (matching ignores case), or add the table to the source, or delete the entry.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_LIST_UNREADABLE

**Table list unusable**

- **What it means:** The table list could not be read, has no rows or contains no table names.
- **How to fix it:** Fix the file: a header `table name` and one table per row, or one table per line in a .txt file.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TABLE_WITHOUT_METADATA

**Manifest table not in the metadata**

- **What it means:** Some tables in the manifest have no row in the CSV, so no manifest was generated for them. This is expected when only some tables need dataset manifests.
- **How to fix it:** Add a row to the CSV for every table that needs a dataset manifest.
- **Where to look:** docs/INGESTION.md (Dataset manifests per table)

## TEXT_CODE_BLOCKS_SKIPPED

**Code blocks skipped**

- **What it means:** Fenced code blocks in text or Markdown are not read.
- **How to fix it:** Add SQL blocks as separate .sql files if they should be analysed.
- **Where to look:** docs/INGESTION.md

## TEXT_LINEAGE_AS_TEXT

**Lineage kept as text**

- **What it means:** Statements that look like lineage were kept as text only.
- **How to fix it:** Model confirmed lineage in the manifest by hand once the official shape is known.
- **Where to look:** docs/INGESTION.md

## TEXT_NOTHING_EXTRACTED

**Nothing recognised in the text**

- **What it means:** No tables, terms or lineage statements were found.
- **How to fix it:** Provide a Markdown table with a Column/Field header, `NAME: description` lines, or a CSV dictionary.
- **Where to look:** docs/INGESTION.md

## TEXT_TERM_CANDIDATE

**Candidate terms found**

- **What it means:** Terms were guessed from text patterns; none is confirmed.
- **How to fix it:** Review each term before using it.
- **Where to look:** docs/INGESTION.md

## TYPES_INFERRED

**Column types are guesses**

- **What it means:** Types were inferred from a sample of values and are not authoritative; row values are not kept.
- **How to fix it:** Confirm the types against the real source system.
- **Where to look:** docs/INGESTION.md

## TYPE_MISMATCH

**A value has the wrong JSON type**

- **What it means:** A field holds a different kind of value than the model expects (for example a string where an array of objects is expected). The value is ignored, so later checks may report missing data.
- **How to fix it:** Change the value at the reported path to the expected type: text in quotes for strings, true or false (no quotes) for booleans, [ ] for arrays and { } for objects.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## UNKNOWN_FIELD

**A field this tool does not know**

- **What it means:** The manifest contains a field that is not part of the CasC manifest model this tool supports. The supported model is: logicalServices, systemDataElements, interfaces, dataEntities, databases and transformations at the top level, each with its own known fields. "Not carried into the output" means: this tool does not check the field, and `export` writes the manifest from the supported fields only, so the field is dropped from the exported file (your original file is never changed). Dataset manifests made by `ingest --manifest-input` use `datasets` and `dgcDataDomains`, which are outside this model, so they always show this warning.
- **How to fix it:** Misspelt name: correct it (the message suggests the closest known field). Dataset manifest: nothing to fix, keep the generated file as it is and do NOT run `export` on it, because export would drop the datasets. Anything else: remove the field if it is not needed; if the official specification requires it, this tool cannot carry it yet, so keep your original file and tell the tool owners.
- **Where to look:** infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)

## UNRESOLVED_DECISION

**A review decision is still open**

- **What it means:** The draft was generated automatically and some choices (services, table types, PII and so on) still need a person.
- **How to fix it:** Make the decision and add it to the manifest, or explicitly acknowledge it where the tool allows.
- **Where to look:** docs/INTEGRATION.md


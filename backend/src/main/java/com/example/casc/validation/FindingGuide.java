package com.example.casc.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Plain-language explanation of every finding code the application can report: what it means, how to fix it and where to look.
 * Used by "casc-generator explain CODE" and to generate docs/FINDINGS.md (a test keeps the file in step with this class).
 */
public final class FindingGuide {
    private FindingGuide() {}

    public record Entry(String code, String title, String meaning, String fix, String refer) {}

    private static final List<Entry> ALL = new ArrayList<>();

    private static void e(String code, String title, String meaning, String fix, String refer) {
        ALL.add(new Entry(code, title, meaning, fix, refer));
    }

    static {
        e("ADAPTER_LIBRARY_MISSING", "Adapter library is missing",
          "The adapter is present but its library is not on the class path.",
          "Add the approved library as described in the adapter guide, or provide a text export.",
          "docs/ADAPTERS.md");
        e("ATTEST_MISSING", "No sensitive-data attestation",
          "Every column or field needs a reviewed sensitive-data attestation (Yes or No). The tool never decides this for you.",
          "After review, set sensitiveDataAttestation to Yes or No at the reported path.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("BOOL_MISSING", "isDeployedOnPrem is not stated",
          "A logical service must say explicitly whether it is deployed on premises. A missing value is not treated as false.",
          "Set isDeployedOnPrem to true or false.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("CELL_TEXT_AS_SHOWN", "Cells read as displayed",
          "Values are read as Excel shows them; formulas use their calculated value; macros never run.",
          "Check dates and numbers against the source.",
          "docs/ADAPTERS.md");
        e("CLOUD_REQUIRED", "cloudProvider is missing",
          "A service that is not deployed on premises must name its cloud provider.",
          "Set cloudProvider, or set isDeployedOnPrem to true if the service is on premises.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("CLOUD_UNEXPECTED", "cloudProvider on an on-premises service",
          "cloudProvider is set although the service is on premises, which contradicts itself.",
          "Remove cloudProvider, or set isDeployedOnPrem to false.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("COLUMN_METADATA_COLUMN_MISSING", "Column metadata header incomplete",
          "The column metadata file lacks one of the three required columns.",
          "Use the header `table name, column name, system data element`.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("COLUMN_METADATA_DUPLICATE", "Column listed twice in the column metadata",
          "The same table and column appear twice; the first row was used.",
          "Delete the duplicate row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("COLUMN_METADATA_NOT_FOUND", "Column metadata row matches nothing",
          "The table or column in the column metadata file does not exist in the source (manifest or SQL), so that system data element was not used.",
          "Correct the table or column name (matching ignores case), or delete the row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("COLUMN_METADATA_ROW_SKIPPED", "Column metadata row skipped",
          "A row is missing its table name, column name or system data element (or the file is longer than the supported number of rows).",
          "Fill in all three values or delete the row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("COLUMN_METADATA_UNREADABLE", "Column metadata file unusable",
          "The optional column metadata file (system data element per column) could not be read or has no rows.",
          "Fix the file and run again.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("COMBINE_DUPLICATE_DATASET", "Dataset appears twice",
          "A dataset with the same name was already added, so this copy was left out.",
          "Remove the duplicate from the input, or check that the files are really different tables.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_FIELD_DROPPED", "Other top-level fields left out",
          "The combined manifest holds only \"datasets\"; any other top-level field of the input files is not carried over.",
          "If you need it, add it to the combined file by hand.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_FILE_SKIPPED", "File without datasets skipped",
          "The file has no \"datasets\" section with entries, so nothing was taken from it.",
          "Give dataset manifests only, such as the manifest_<table>.json files.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_FILE_UNREADABLE", "A file to combine could not be read",
          "A dataset manifest file is missing, too large or not valid JSON, so nothing was combined.",
          "Fix or remove that file and run again.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_NOTHING_SELECTED", "No datasets selected",
          "Nothing matched the input and the selection, so nothing was written.",
          "Check the input files and the count or table names.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_SUMMARY", "Summary of a combine run",
          "How many datasets were read, from how many files, and how many were combined (all, the first N, or the named tables).",
          "None needed.",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("COMBINE_TABLE_NOT_FOUND", "Requested table not found",
          "A table named in --tables has no dataset in the input, so it is not in the combined manifest.",
          "Check the spelling (matching ignores case; use the table name or schema.table).",
          "docs/INGESTION.md (Combining dataset manifests)");
        e("CSV_BLANK_HEADER", "A column header is blank",
          "A header cell is empty, so the tool named it automatically.",
          "Give the column a name in the source file.",
          "docs/INGESTION.md");
        e("CSV_DUPLICATE_HEADER", "Two columns have the same header",
          "Both columns are kept, which can confuse later steps.",
          "Rename one of the columns in the source.",
          "docs/INGESTION.md");
        e("CSV_NO_HEADER", "No header row found",
          "The header row is missing or empty.",
          "Check the headerRow and hasHeader options, or that the file is not empty.",
          "docs/INGESTION.md");
        e("CSV_RAGGED", "Rows have different numbers of fields",
          "Some rows have more or fewer cells than the header; missing cells are read as empty.",
          "Check quoting and the delimiter in the source.",
          "docs/INGESTION.md");
        e("CSV_ROWS_BEFORE_HEADER", "Rows before the header were skipped",
          "Rows above the header row were ignored.",
          "Set headerRow if the header is not where the tool looked.",
          "docs/INGESTION.md");
        e("CSV_SYNTAX", "CSV quoting error",
          "The file breaks CSV rules (unterminated quote, quote inside an unquoted value, text after a closing quote).",
          "Fix the quoting at the reported line or export the file again as standard CSV.",
          "docs/INGESTION.md");
        e("DATASET_DATABASE_MISSING", "Dataset names without a database",
          "Dataset names are database.schema.table. Some tables have no database (a manifest database without a logicalName, or SQL tables not written as database.schema.table), so their names are schema.table only.",
          "Pass --database-name NAME, or give the database in the source, then run again with --overwrite.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("DATASET_SCHEMA_MISSING", "SQL table without a schema",
          "A table in the SQL has no schema (it is not written as schema.table) and no default schema was given, so its dataset is named with the table name only.",
          "Qualify the table names in the SQL, or pass --default-schema SCHEMA, then run again with --overwrite.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("DATASET_SUMMARY", "Summary of a dataset-manifest run",
          "How many tables were read from the source, how many rows the metadata CSV has and how many manifests were generated. Only tables that have a metadata row are generated, so the number generated is at most the number of metadata rows.",
          "If fewer manifests were generated than expected, read the warnings above it: rows that match no table, duplicate rows, tables without a row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("DATASET_VIEW_SKIPPED", "SQL views not mapped",
          "Only tables get a dataset manifest; CREATE VIEW statements were skipped.",
          "Nothing to do unless a view needs a dataset manifest.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("DB_NAME_PROVISIONAL", "Database name contains a space",
          "The naming rules for TAI logical database names are not confirmed; names with whitespace may be rejected later.",
          "Check the name against the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository) and remove the whitespace if the rules forbid it.",
          "the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("DICT_COLUMN_UNMAPPED", "Data dictionary columns not recognised",
          "The file looks like a data dictionary but no column-name header was found.",
          "Add a header such as Column or Field, or set map.column to the right header.",
          "docs/INGESTION.md");
        e("DICT_DUPLICATE_COLUMN", "Column listed twice in a dictionary",
          "The same column appears twice for one table; both are kept.",
          "Remove or rename the duplicate before generating a manifest.",
          "docs/INGESTION.md");
        e("DICT_ROW_SKIPPED", "Dictionary row skipped",
          "A row has no column name and was not used.",
          "Fill in the column name or delete the row.",
          "docs/INGESTION.md");
        e("DICT_TRUNCATED", "Only the first rows were read",
          "The row limit (maxRows) was reached.",
          "Raise maxRows to read the rest.",
          "docs/INGESTION.md");
        e("DOCX_ENCRYPTED", "Document is password protected",
          "No password is ever tried.",
          "Remove the protection and add the file again.",
          "docs/ADAPTERS.md");
        e("DOCX_TABLE_IGNORED", "Small Word table ignored",
          "A table with fewer than two rows was ignored.",
          "None needed.",
          "docs/ADAPTERS.md");
        e("DOCX_TABLE_RAGGED", "Word table rows differ in width",
          "Rows have different numbers of cells.",
          "Check the table.",
          "docs/ADAPTERS.md");
        e("DOCX_UNREADABLE", "Word file could not be read",
          "The file is not a valid .docx (old .doc is not supported).",
          "Check the file or save it as text or Markdown.",
          "docs/ADAPTERS.md");
        e("DRAFT_DB_NAME_REQUIRED", "Database name needed for the draft",
          "A draft manifest needs the TAI logical database name.",
          "Provide it (`--db-name`, or the System step in the web UI).",
          "docs/INGESTION.md");
        e("DRAFT_DIRECTION_REQUIRED", "Interface direction needed",
          "Only interface candidates were found and the direction cannot be guessed.",
          "Choose SOURCING (the system consumes data) or DISTRIBUTION (it provides data).",
          "docs/INGESTION.md");
        e("DRAFT_INTERFACES_NOT_INCLUDED", "Interfaces left out of the draft",
          "No interface direction was chosen, so they were not included.",
          "Choose SOURCING or DISTRIBUTION to include them.",
          "docs/INGESTION.md");
        e("DRAFT_INTERFACE_EMPTY", "Interface has no fields",
          "The chosen direction has no request or response fields to list.",
          "Add the fields by hand if needed.",
          "docs/INGESTION.md");
        e("DRAFT_NO_TABLES", "Nothing to build a draft from",
          "The sources contain no tables or interfaces.",
          "Add DDL, a data dictionary, a data file or an OpenAPI document.",
          "docs/INGESTION.md");
        e("DRAFT_SCHEMA_REQUIRED", "Default schema needed",
          "Some tables (for example from CSV files) have no schema.",
          "Provide the schema they belong to (`--default-schema`).",
          "docs/INGESTION.md");
        e("DRAFT_STRUCTURES_NOT_INCLUDED", "Candidate structures not used",
          "API structures were not turned into data entities.",
          "Review and add the ones you want.",
          "docs/INGESTION.md");
        e("DRAFT_TERMS_NOT_INCLUDED", "Candidate terms not used",
          "Terms were not turned into logical data elements.",
          "Review and add the ones you want.",
          "docs/INGESTION.md");
        e("DRAFT_VIEW_EXCLUDED", "Views left out of the draft",
          "Views are not included in drafts.",
          "Add them by hand if the specification expects them.",
          "docs/INGESTION.md");
        e("DUP_NAME", "A name is used twice",
          "Names must be unique inside their scope (for example two tables in one schema). Duplicates make references ambiguous.",
          "Rename or remove one of the duplicates named in the message.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("ENUM_INVALID", "A value is not one of the allowed choices",
          "The field accepts only a fixed list of values and matching is exact and case-sensitive (for example SOURCING, not sourcing).",
          "Replace the value with exactly one of the allowed values listed in the message.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("FORMAT_UNAVAILABLE", "File type needs an optional adapter",
          "XLSX, DOCX, PDF and HTML/Confluence are read only when the matching adapter and its library are installed; this build does not have it.",
          "Export the content to CSV, TSV, text or Markdown, or install the adapter as described in the adapter guide.",
          "docs/ADAPTERS.md");
        e("FORMAT_UNKNOWN", "File type not supported",
          "The file extension is not one this build reads.",
          "Convert the file to a supported type (the message lists them), such as CSV, TSV, SQL, Markdown, text or OpenAPI JSON.",
          "docs/INGESTION.md");
        e("FORMULA_UNREADABLE", "Formulas could not be evaluated",
          "Those cells were read as empty.",
          "Check the formulas.",
          "docs/ADAPTERS.md");
        e("HTML_TABLE_IGNORED", "Small HTML table ignored",
          "A table with fewer than two rows was ignored.",
          "None needed.",
          "docs/ADAPTERS.md");
        e("HTML_TABLE_RAGGED", "HTML table rows differ in width",
          "Rows have different numbers of cells.",
          "Check the table.",
          "docs/ADAPTERS.md");
        e("HTML_UNREADABLE", "HTML could not be parsed",
          "The page could not be read.",
          "Save the content as Markdown or text.",
          "docs/ADAPTERS.md");
        e("JSON_SYNTAX", "The JSON text is not valid",
          "The file cannot be parsed as JSON, so nothing in it was read.",
          "Fix the syntax at the reported line and column (missing or extra commas, quotes or brackets). A JSON checker in your editor shows the exact spot.",
          "docs/INGESTION.md");
        e("KEY_BLANK", "systemKey is present but empty",
          "A systemKey was written as empty text, which cannot identify anything.",
          "Give the systemKey a value, or remove the field.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("KEY_DUP", "systemKey used twice",
          "In this tool's provisional rules every systemKey must be unique across the whole manifest.",
          "Choose a different systemKey for one of the two places named in the message.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("LINEAGE_JSON_INVALID", "Lineage JSON typed in the wizard is invalid",
          "The text entered for sourcedFromColumns is not valid JSON.",
          "Fix the JSON or clear the field.",
          "docs/WIZARD.md");
        e("LINEAGE_UNVERIFIED", "Lineage is passed through unchecked",
          "`sourcedFromColumns` is kept exactly as written; its structure and references are not validated because the official shape is not confirmed.",
          "Check the entry against the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository).",
          "the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("MANIFEST_LENIENT_READ", "Manifest has JSON syntax errors but was read",
          "The file is not strictly valid JSON (for example trailing or missing commas). Only the `databases` section was read, tolerantly. Tables were still found, so manifests were generated.",
          "Fix the syntax errors in the manifest so that other tools can read it too.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_NOT_OBJECT", "Manifest is not a JSON object",
          "The top level of the file must be an object { }.",
          "Check that you gave the right file.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_NO_DATABASES", "No databases section",
          "The manifest has no `databases` section to read tables from.",
          "Add databases[].schemas[].tables[] to the manifest, or give the right file.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_NO_TABLES", "No tables in the manifest",
          "The `databases` section has no tables.",
          "Add tables under databases[].schemas[].tables[].",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_SYNTAX", "Manifest is not valid JSON",
          "The file has JSON syntax errors and its `databases` section could not be located, so no tables were read.",
          "Fix the JSON syntax (the message gives the position), then run again.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_TABLE_SKIPPED", "Table entries without a physicalName",
          "Some entries under tables[] have no physicalName, so they could not be read and no dataset manifest can be made for them. The message gives the position of the first one.",
          "Add physicalName to those entries.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MANIFEST_UNREADABLE", "Manifest file has no text",
          "The manifest could not be read (empty or not valid UTF-8).",
          "Fix the file and run the command again.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("MD_TABLE_IGNORED", "Not a Markdown table",
          "A line starting with | has no header separator row, so it was not read as a table.",
          "Use a standard Markdown table or provide a CSV.",
          "docs/INGESTION.md");
        e("MD_TABLE_RAGGED", "Markdown table rows differ in width",
          "Some rows have a different number of cells than the header.",
          "Check for unescaped | characters.",
          "docs/INGESTION.md");
        e("METADATA_COLUMN_MISSING", "Metadata column missing",
          "The CSV lacks one of the five required columns.",
          "Use the header `table name, domain, boundary, upstream dataset, producer eonid` (with --no-upstream only `table name, domain, boundary` is needed).",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_DUPLICATE_TABLE", "Table listed twice in the metadata",
          "Only the first row for the table was used.",
          "Delete the duplicate row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_EMPTY", "Metadata file is empty",
          "There is no header or no rows.",
          "Add the header `table name, domain, boundary, upstream dataset, producer eonid` and one row per table.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_EON_NOT_NUMERIC", "Producer EON ID is not a number",
          "The value was written as text instead of a number.",
          "Use the numeric EON ID.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_ROW_SKIPPED", "Metadata row skipped",
          "A row without a table name cannot be matched.",
          "Fill in the table name or delete the row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_TABLE_NOT_IN_MANIFEST", "Metadata row has no matching table",
          "The table named in the CSV is not defined in the manifest, so no manifest was generated for it. Other tables are not affected.",
          "Correct the table name in the CSV (names are matched ignoring case), or add the table to the manifest, or delete the row.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_TRUNCATED", "Metadata file too long",
          "Only the supported number of rows was read.",
          "Split the file.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_UNREADABLE", "Metadata file has no text",
          "The table metadata CSV could not be read.",
          "Fix the file and run the command again.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("METADATA_VALUE_MISSING", "A metadata value is empty",
          "The domain, boundary, upstream dataset or producer EON ID is empty, so that part is missing from the generated manifest.",
          "Fill in the value in the CSV and run again with --overwrite.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("NAME_QUALIFIED", "A name contains a period",
          "Schema, table and column names must be unqualified: no database or schema prefix and no periods.",
          "Keep only the name itself (for example `TAPSAccount`, not `a2oltp.TAPSAccount`).",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("NO_SOURCES", "No input files",
          "Nothing was given to read.",
          "Add at least one file (or URL).",
          "docs/INGESTION.md");
        e("NO_TABLES_MATCHED", "No table matched",
          "None of the tables in the CSV exist in the manifest, so nothing was written.",
          "Compare the table names in both files.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("OPENAPI_INTERPRETATION", "How OpenAPI is read",
          "Operations become candidate interfaces and schemas become candidate structures. The SOURCING/DISTRIBUTION direction is never guessed.",
          "Choose the direction for each interface during review.",
          "docs/INGESTION.md");
        e("OPENAPI_NOT_RECOGNISED", "JSON is not OpenAPI",
          "Only OpenAPI/Swagger JSON is read as a JSON source. A CasC manifest or a dataset manifest is not a source.",
          "To check a manifest use `validate`. To build dataset manifests use `ingest --manifest-input`.",
          "docs/INGESTION.md");
        e("OPENAPI_NO_OPERATIONS", "No operations in the OpenAPI file",
          "The document has no operations under `paths`.",
          "Check that this is the right file.",
          "docs/INGESTION.md");
        e("PDF_ENCRYPTED", "PDF is encrypted",
          "No password is ever tried.",
          "Remove the protection or provide a text export.",
          "docs/ADAPTERS.md");
        e("PDF_NO_TEXT", "PDF has no text",
          "It is a scan or image-only; OCR is not available.",
          "Provide a text export, or run OCR with an approved tool.",
          "docs/ADAPTERS.md");
        e("PDF_PAGE_LIMIT", "Only some PDF pages read",
          "The page limit was reached.",
          "Split the document.",
          "docs/ADAPTERS.md");
        e("PDF_TABLES_NOT_RECONSTRUCTED", "PDF tables not rebuilt",
          "Only text was extracted; tables are not reconstructed and column order may be wrong.",
          "Export tables as CSV and review each candidate term.",
          "docs/ADAPTERS.md");
        e("PDF_TEXT_LIMIT", "PDF text limit reached",
          "The rest of the text was not read.",
          "Split the document.",
          "docs/ADAPTERS.md");
        e("PDF_UNREADABLE", "PDF could not be read",
          "The file is not a valid PDF.",
          "Check the file or provide a text export.",
          "docs/ADAPTERS.md");
        e("PII_CATEGORY_REQUIRED", "PII without a data subject category",
          "PII elements need at least one data subject category.",
          "Add dataSubjectCategories with at least one of: Client, Worker, Third Party.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("PII_CONFLICT", "PII elements without a Yes attestation",
          "PII elements are listed but the attestation is No or missing, which contradicts each other.",
          "Set the attestation to Yes, or remove the PII elements if the review shows there are none.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("PII_REQUIRED", "Attestation is Yes but no PII elements",
          "If a column is attested as holding sensitive data, the PII elements must be listed.",
          "List at least one piiElements entry, or change the attestation to No if that is the correct answer after review.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("REF_ELEMENT", "Reference to a data element that is not declared",
          "A name used as a system data element, source or target does not match any entry in the manifest's systemDataElements section.",
          "Add the element to systemDataElements, or correct the name so it matches exactly.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("REF_LOGICAL_SERVICE", "Reference to a logical service that is not declared",
          "A name listed under logicalServices does not match any entry in the manifest's logicalServices section (names are case-sensitive).",
          "Add the service to logicalServices, or correct the name so it matches exactly.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("REQ_BLANK", "A required value is blank or empty",
          "A field that must have a value is missing, empty or only spaces.",
          "Provide a non-blank value at the reported path.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("REQ_MISSING", "A required field is missing",
          "A mandatory field is absent. For an interface, `type` is required: SOURCING means the system consumes the data, DISTRIBUTION means the system provides it.",
          "Add the field at the reported path with a valid value.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("ROUNDTRIP_MISMATCH", "Export check failed",
          "The generated JSON was read back and did not give the same output. This is a defect in the tool, not in your data.",
          "Do not use the file. Report the defect with the input that caused it.",
          "docs/VERIFICATION.md");
        e("ROUNDTRIP_UNAVAILABLE", "Export check not available for YAML",
          "The read-back check needs a YAML parser, which is not supplied.",
          "Export JSON for a checked file, or check the YAML with an approved YAML tool.",
          "docs/CAPABILITIES.md");
        e("SAMPLING_LIMIT", "Only some rows were sampled",
          "Types were inferred from the first rows only (maxRows).",
          "Raise maxRows to sample more rows.",
          "docs/INGESTION.md");
        e("SHEET_EMPTY", "Empty sheet skipped",
          "The sheet has no data.",
          "None needed.",
          "docs/ADAPTERS.md");
        e("SHEET_HIDDEN", "Hidden sheet skipped",
          "Hidden sheets are not read by default.",
          "Unhide it or select it with the sheet option.",
          "docs/ADAPTERS.md");
        e("SHEET_LIMIT", "Only some sheets read",
          "The sheet limit was reached.",
          "Select a sheet with the sheet option.",
          "docs/ADAPTERS.md");
        e("SHEET_NOT_FOUND", "Sheet not found",
          "No sheet matches the name or number.",
          "Use a listed sheet name or its 1-based number.",
          "docs/ADAPTERS.md");
        e("SHEET_TOO_WIDE", "Sheet has too many columns",
          "Extra columns were ignored.",
          "Split the sheet.",
          "docs/ADAPTERS.md");
        e("SOURCE_ENCODING", "A binary file was given as text",
          "Spreadsheet, Word and PDF files must be sent as raw bytes, not pasted text.",
          "Add the file itself instead of its text.",
          "docs/INGESTION.md");
        e("SOURCE_TOO_LARGE", "A file is too large",
          "The file is bigger than the size limit (maxSourceChars).",
          "Split the file, or raise the limit with the maxSourceChars option.",
          "docs/INGESTION.md");
        e("SOURCE_UNREADABLE", "A file could not be read",
          "The file is missing, too large, outside the allowed folder, or not valid UTF-8 text.",
          "Fix the path or the file (save as UTF-8) and add it again.",
          "docs/INGESTION.md");
        e("SPREADSHEET_ENCRYPTED", "Workbook is password protected",
          "No password is ever tried.",
          "Remove the protection or export the sheets to CSV.",
          "docs/ADAPTERS.md");
        e("SPREADSHEET_NOTHING_EXTRACTED", "No usable sheet",
          "No visible sheet with data was found.",
          "Check the sheet selection and that sheets are not empty or hidden.",
          "docs/ADAPTERS.md");
        e("SPREADSHEET_UNREADABLE", "Spreadsheet could not be read",
          "The file is not a valid .xlsx or .xls file.",
          "Check the file or export the sheets to CSV.",
          "docs/ADAPTERS.md");
        e("SQL_CONSTRAINT_IGNORED", "Constraints not extracted",
          "Only PRIMARY KEY is extracted; UNIQUE, FOREIGN KEY and CHECK are ignored.",
          "None needed unless you need them in the manifest; add them by hand if the specification asks.",
          "docs/INGESTION.md");
        e("SQL_DML_ANALYSED", "SQL DML analysed",
          "INSERT/UPDATE/MERGE/SELECT were analysed at table level by the JSqlParser adapter. Hints only; never written to a manifest.",
          "Review the hints.",
          "docs/ADAPTERS.md");
        e("SQL_DUPLICATE_COLUMN", "Column defined twice in SQL",
          "The first definition was kept.",
          "Remove the duplicate.",
          "docs/INGESTION.md");
        e("SQL_DUPLICATE_TABLE", "Table defined twice in SQL",
          "The later definition replaces the earlier one.",
          "Remove the duplicate definition.",
          "docs/INGESTION.md");
        e("SQL_LINEAGE_NOT_INFERRED", "No lineage for a view",
          "Joins, expressions or set operations make the lineage unclear.",
          "Document the lineage separately.",
          "docs/INGESTION.md");
        e("SQL_NOTHING_EXTRACTED", "No tables found in the SQL",
          "No CREATE TABLE or CREATE VIEW statements were found.",
          "Provide DDL, or a data dictionary as CSV.",
          "docs/INGESTION.md");
        e("SQL_PARSE_FAILED", "SQL statement not understood",
          "A statement is outside the supported SQL subset and was not applied.",
          "Check it against the supported subset in the ingestion guide, or provide a data dictionary instead.",
          "docs/INGESTION.md");
        e("SQL_SKIPPED", "SQL statements skipped",
          "Statements that do not define structure (for example INSERT) were skipped.",
          "None needed.",
          "docs/INGESTION.md");
        e("SQL_SYNTAX", "SQL text error",
          "The SQL could not be tokenised, so no statement from the file was analysed.",
          "Fix the SQL at the reported line.",
          "docs/INGESTION.md");
        e("SQL_TABLE_OPTIONS_IGNORED", "Table options ignored",
          "Options after the column list were not analysed.",
          "None needed.",
          "docs/INGESTION.md");
        e("SQL_UNKNOWN_TARGET", "PRIMARY KEY names an unknown column",
          "The primary key refers to a column that is not defined on the table.",
          "Check the column name.",
          "docs/INGESTION.md");
        e("SQL_UNSUPPORTED", "SQL feature not supported",
          "The statement uses a feature this tool does not read.",
          "See the supported subset; supply the structure another way.",
          "docs/INGESTION.md");
        e("SQL_VIEW_COLUMNS_UNKNOWN", "View columns not determined",
          "The SELECT is too complex to read the view's columns.",
          "Give the view an explicit column list, or supply a data dictionary.",
          "docs/INGESTION.md");
        e("TABLE_CONFLICT", "Same table in two sources",
          "Two sources define the same table; they are reported, never merged.",
          "Decide which source is authoritative and remove or rename the other.",
          "docs/INGESTION.md");
        e("TABLE_FROM_HEADING", "Table names taken from headings",
          "No table column was found, so rows were assigned to the nearest heading above them.",
          "Check that each heading really is a table name.",
          "docs/INGESTION.md");
        e("TABLE_WITHOUT_METADATA", "Manifest table not in the metadata",
          "Some tables in the manifest have no row in the CSV, so no manifest was generated for them. This is expected when only some tables need dataset manifests.",
          "Add a row to the CSV for every table that needs a dataset manifest.",
          "docs/INGESTION.md (Dataset manifests per table)");
        e("TEXT_CODE_BLOCKS_SKIPPED", "Code blocks skipped",
          "Fenced code blocks in text or Markdown are not read.",
          "Add SQL blocks as separate .sql files if they should be analysed.",
          "docs/INGESTION.md");
        e("TEXT_LINEAGE_AS_TEXT", "Lineage kept as text",
          "Statements that look like lineage were kept as text only.",
          "Model confirmed lineage in the manifest by hand once the official shape is known.",
          "docs/INGESTION.md");
        e("TEXT_NOTHING_EXTRACTED", "Nothing recognised in the text",
          "No tables, terms or lineage statements were found.",
          "Provide a Markdown table with a Column/Field header, `NAME: description` lines, or a CSV dictionary.",
          "docs/INGESTION.md");
        e("TEXT_TERM_CANDIDATE", "Candidate terms found",
          "Terms were guessed from text patterns; none is confirmed.",
          "Review each term before using it.",
          "docs/INGESTION.md");
        e("TYPES_INFERRED", "Column types are guesses",
          "Types were inferred from a sample of values and are not authoritative; row values are not kept.",
          "Confirm the types against the real source system.",
          "docs/INGESTION.md");
        e("TYPE_MISMATCH", "A value has the wrong JSON type",
          "A field holds a different kind of value than the model expects (for example a string where an array of objects is expected). The value is ignored, so later checks may report missing data.",
          "Change the value at the reported path to the expected type: text in quotes for strings, true or false (no quotes) for booleans, [ ] for arrays and { } for objects.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("UNKNOWN_FIELD", "A field this tool does not know",
          "The manifest contains a field that is not part of the CasC manifest model this tool supports. The supported model is: logicalServices, systemDataElements, interfaces, dataEntities, databases and transformations at the top level, each with its own known fields. \"Not carried into the output\" means: this tool does not check the field, and `export` writes the manifest from the supported fields only, so the field is dropped from the exported file (your original file is never changed). Dataset manifests made by `ingest --manifest-input` use `datasets` and `dgcDataDomains`, which are outside this model, so they always show this warning.",
          "Misspelt name: correct it (the message suggests the closest known field). Dataset manifest: nothing to fix, keep the generated file as it is and do NOT run `export` on it, because export would drop the datasets. Anything else: remove the field if it is not needed; if the official specification requires it, this tool cannot carry it yet, so keep your original file and tell the tool owners.",
          "infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md (manifest rules) and the official CasC specification (from your Firmwide Data Catalog team; it is not part of this repository)");
        e("UNRESOLVED_DECISION", "A review decision is still open",
          "The draft was generated automatically and some choices (services, table types, PII and so on) still need a person.",
          "Make the decision and add it to the manifest, or explicitly acknowledge it where the tool allows.",
          "docs/INTEGRATION.md");
    }

    public static List<Entry> all() {
        return List.copyOf(ALL);
    }

    public static Optional<Entry> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return ALL.stream().filter(x -> x.code().equals(c)).findFirst();
    }

    /** The text printed by "explain CODE". */
    public static String text(Entry x) {
        return x.code() + ": " + x.title() + "\n\nWhat it means:\n  " + x.meaning() + "\n\nHow to fix it:\n  " + x.fix()
                + "\n\nWhere to look:\n  " + x.refer() + "\n";
    }

    /** The content of docs/FINDINGS.md. */
    public static String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("# Findings reference\n\n");
        b.append("Every finding the application reports has a code, a path (where in the file), a message and a suggested fix. This page explains each code in plain language.\n");
        b.append("Get the same text from the command line: `casc-generator explain CODE` (or `explain` alone to list all codes).\n\n");
        b.append("Severity: **ERROR** blocks export (nothing is written); **WARNING** needs your attention but does not block; **INFO** is for your information.\n");
        b.append("Validation level **LOCAL** means the manifest was checked only against this tool's provisional rules. It is not the official CasC schema and not FDC acceptance.\n\n");
        b.append("This file is generated from `FindingGuide.java`; a test fails if they differ.\n\n");
        for (Entry x : ALL) {
            b.append("## ").append(x.code()).append("\n\n**").append(x.title()).append("**\n\n");
            b.append("- **What it means:** ").append(x.meaning()).append("\n");
            b.append("- **How to fix it:** ").append(x.fix()).append("\n");
            b.append("- **Where to look:** ").append(x.refer()).append("\n\n");
        }
        return b.toString();
    }
}

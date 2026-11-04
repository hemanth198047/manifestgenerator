package com.example.casc.ingest;

import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Descriptions of tables for dataset-manifest generation: a CSV/TSV with a table column (table name, tables, table) and a description column
 * (description, descriptions, table description). Other columns are ignored. A prefix such as a2.dbo. on a table name is ignored.
 */
public final class TableDescriptionsCsv {
    private TableDescriptionsCsv() {}

    /** The bare table name; any prefix (a2.dbo.) was removed. qualifier is always null (kept for compatibility). */
    public record Entry(String table, String qualifier, String description, int line) {}

    public record Result(List<Entry> entries, List<Finding> findings) {
        public static Result empty() {
            return new Result(List.of(), List.of());
        }
    }

    public static Result parse(SourceInput in) {
        List<Finding> findings = new ArrayList<>();
        if (in.readError() != null || in.text() == null) {
            findings.add(Finding.error("TABLE_DESCRIPTIONS_UNREADABLE", in.name(), in.readError() == null ? "The table descriptions file has no text." : in.readError(),
                    "Fix the file and run the command again."));
            return new Result(List.of(), findings);
        }
        Csv.Result r;
        try {
            r = Csv.parse(in.text(), SourceIngestionService.extension(in.name()).equals("tsv") ? '\t' : ',', 1_000_000);
        } catch (Csv.CsvException e) {
            findings.add(Finding.error("CSV_SYNTAX", in.name() + ":line " + e.line(), e.getMessage(),
                    "Fix the quoting at that line, or export the file again with standard CSV quoting."));
            return new Result(List.of(), findings);
        }
        if (r.records().isEmpty()) {
            findings.add(Finding.error("TABLE_DESCRIPTIONS_UNREADABLE", in.name(), "The table descriptions file has no rows.", "Add a header (table name, description) and one table per row."));
            return new Result(List.of(), findings);
        }
        int tableCol = -1;
        int descCol = -1;
        List<String> header = r.records().get(0);
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (tableCol < 0 && (h.equals("tablename") || h.equals("table") || h.equals("tables") || h.equals("tablenames"))) {
                tableCol = i;
            } else if (descCol < 0 && (h.equals("description") || h.equals("descriptions") || h.equals("tabledescription") || h.equals("tabledescriptions") || h.equals("desc"))) {
                descCol = i;
            }
        }
        if (tableCol < 0 || descCol < 0) {
            findings.add(Finding.error("TABLE_DESCRIPTIONS_COLUMN_MISSING", in.name() + ":line " + r.lines().get(0),
                    "The table descriptions file has no '" + (tableCol < 0 ? "table name" : "description") + "' column.",
                    "Use a header with a table column (table name, tables or table) and a description column (description)."));
            return new Result(List.of(), findings);
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int k = 1; k < r.records().size(); k++) {
            List<String> rec = r.records().get(k);
            int line = r.lines().get(k);
            String name = tableCol < rec.size() ? rec.get(tableCol).trim() : "";
            String desc = descCol < rec.size() ? rec.get(descCol).trim() : "";
            if (name.isEmpty() && desc.isEmpty()) {
                continue;
            }
            if (name.isEmpty() || desc.isEmpty()) {
                findings.add(Finding.warning("TABLE_DESCRIPTION_ROW_SKIPPED", in.name() + ":line " + line,
                        "A row is missing its " + (name.isEmpty() ? "table name" : "description") + " and was skipped.", "Fill in both values or delete the row."));
                continue;
            }
            String table = TableNames.bare(name);
            String qualifier = null;
            if (table.isEmpty()) {
                findings.add(Finding.warning("TABLE_DESCRIPTION_ROW_SKIPPED", in.name() + ":line " + line, "'" + name + "' is not a usable table name and was skipped.",
                        "Write the table name, for example TAPSAccount (a prefix such as a2.dbo. is ignored)."));
                continue;
            }
            if (!seen.add(((qualifier == null ? "" : qualifier + ".") + table).toLowerCase(Locale.ROOT))) {
                findings.add(Finding.warning("TABLE_DESCRIPTION_DUPLICATE", in.name() + ":line " + line,
                        "Table '" + name + "' has more than one description; the first one was used.", "Delete the duplicate row."));
                continue;
            }
            out.add(new Entry(table, qualifier, desc, line));
        }
        if (r.truncated()) {
            findings.add(Finding.warning("TABLE_DESCRIPTION_ROW_SKIPPED", in.name(), "The file is longer than the supported number of rows; the rest was not read.", "Split the file."));
        }
        return new Result(out, findings);
    }
}

package com.example.casc.ingest;

import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A list of the tables to generate dataset manifests for: a CSV/TSV with a "table name" column, a one-column file, or a text file with one table per line
 * (blank lines and lines starting with # are ignored). A name may carry a prefix such as a2.dbo.; the prefix is ignored and only the table name is used.
 */
public final class TableListCsv {
    private TableListCsv() {}

    /** The bare table name; any prefix (a2.dbo.) was removed. qualifier is always null (kept for compatibility). */
    public record Entry(String table, String qualifier, int line) {}

    public record Result(List<Entry> entries, List<Finding> findings) {}

    public static Result parse(SourceInput in) {
        List<Finding> findings = new ArrayList<>();
        if (in.readError() != null || in.text() == null) {
            findings.add(Finding.error("TABLE_LIST_UNREADABLE", in.name(), in.readError() == null ? "The table list has no text." : in.readError(),
                    "Fix the file and run the command again."));
            return new Result(List.of(), findings);
        }
        String ext = SourceIngestionService.extension(in.name());
        List<String> names = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        if (ext.equals("txt")) {
            String[] all = in.text().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int i = 0; i < all.length; i++) {
                String t = all[i].trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    names.add(t);
                    lines.add(i + 1);
                }
            }
        } else {
            Csv.Result r;
            try {
                r = Csv.parse(in.text(), ext.equals("tsv") ? '\t' : ',', 1_000_000);
            } catch (Csv.CsvException e) {
                findings.add(Finding.error("CSV_SYNTAX", in.name() + ":line " + e.line(), e.getMessage(),
                        "Fix the quoting at that line, or export the file again with standard CSV quoting."));
                return new Result(List.of(), findings);
            }
            if (r.records().isEmpty()) {
                findings.add(Finding.error("TABLE_LIST_UNREADABLE", in.name(), "The table list has no rows.", "Add a header `table name` and one table per row."));
                return new Result(List.of(), findings);
            }
            int col = -1;
            List<String> header = r.records().get(0);
            for (int i = 0; i < header.size() && col < 0; i++) {
                String h = header.get(i).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (h.equals("tablename") || h.equals("table") || h.equals("tables")) {
                    col = i;
                }
            }
            int first = 1;
            if (col < 0) {
                boolean oneColumn = r.records().stream().allMatch(x -> x.size() <= 1);
                if (!oneColumn) {
                    findings.add(Finding.error("TABLE_LIST_COLUMN_MISSING", in.name() + ":line " + r.lines().get(0), "The table list has no 'table name' column.",
                            "Use the header `table name`, or a file with only one column of table names."));
                    return new Result(List.of(), findings);
                }
                col = 0;
                first = 0;
            }
            for (int k = first; k < r.records().size(); k++) {
                List<String> rec = r.records().get(k);
                String t = col < rec.size() ? rec.get(col).trim() : "";
                if (!t.isEmpty()) {
                    names.add(t);
                    lines.add(r.lines().get(k));
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            String table = TableNames.bare(n);
            String qualifier = null;
            if (table.isEmpty()) {
                findings.add(Finding.warning("TABLE_LIST_ENTRY_SKIPPED", in.name() + ":line " + lines.get(i), "'" + n + "' is not a usable table name and was skipped.",
                        "Write the table name, for example TAPSAccount (a prefix such as a2.dbo. is ignored)."));
                continue;
            }
            if (!seen.add(((qualifier == null ? "" : qualifier + ".") + table).toLowerCase(Locale.ROOT))) {
                findings.add(Finding.warning("TABLE_LIST_DUPLICATE", in.name() + ":line " + lines.get(i), "Table '" + n + "' is listed more than once; the first entry was used.",
                        "Delete the duplicate."));
                continue;
            }
            out.add(new Entry(table, qualifier, lines.get(i)));
        }
        if (out.isEmpty() && findings.stream().noneMatch(f -> f.code().equals("TABLE_LIST_UNREADABLE"))) {
            findings.add(Finding.error("TABLE_LIST_UNREADABLE", in.name(), "The table list contains no table names.", "Add one table per row."));
        }
        return new Result(out, findings);
    }
}

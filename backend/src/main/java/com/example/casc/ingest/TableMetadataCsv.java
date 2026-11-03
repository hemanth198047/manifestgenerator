package com.example.casc.ingest;

import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the table metadata CSV that drives dataset-manifest generation. One row per table with the columns
 * table name, domain, boundary, upstream dataset and producer EON ID (header names are matched ignoring case, spaces and punctuation).
 * A domain written as "Account (Operations DGC Glossary)" is split into the domain and its glossary.
 */
public final class TableMetadataCsv {
    private TableMetadataCsv() {}

    public static final String DEFAULT_GLOSSARY = "Operations DGC Glossary";

    public record Row(String table, String domain, String glossary, String boundary, String upstreamDataset,
                      String producerEonId, int line, String qualifier) {}

    public record Result(List<Row> rows, List<Finding> findings) {}

    public static Result parse(SourceInput in) {
        return parse(in, true);
    }

    /** requireUpstream false: the upstream dataset and producer eonid columns are optional (datasets without upstreams). */
    public static Result parse(SourceInput in, boolean requireUpstream) {
        List<Finding> findings = new ArrayList<>();
        if (in.readError() != null || in.text() == null) {
            findings.add(Finding.error("METADATA_UNREADABLE", in.name(), in.readError() == null ? "The metadata file has no text." : in.readError(),
                    "Fix the file and add it again."));
            return new Result(List.of(), findings);
        }
        char delim = SourceIngestionService.extension(in.name()).equals("tsv") ? '\t' : ',';
        Csv.Result r;
        try {
            r = Csv.parse(in.text(), delim, 1_000_000);
        } catch (Csv.CsvException e) {
            findings.add(Finding.error("CSV_SYNTAX", in.name() + ":line " + e.line(), e.getMessage(),
                    "Fix the quoting at that line, or export the file again with standard CSV quoting."));
            return new Result(List.of(), findings);
        }
        if (r.records().isEmpty()) {
            findings.add(Finding.error("METADATA_EMPTY", in.name(), "The metadata file has no rows.", "Add a header row and one row per table."));
            return new Result(List.of(), findings);
        }
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> header = r.records().get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = canonical(header.get(i));
            if (key != null) {
                idx.putIfAbsent(key, i);
            }
        }
        for (String need : List.of("table", "domain", "boundary", "upstream", "eon")) {
            if (!idx.containsKey(need) && (requireUpstream || !(need.equals("upstream") || need.equals("eon")))) {
                findings.add(Finding.error("METADATA_COLUMN_MISSING", in.name() + ":line " + r.lines().get(0),
                        "The metadata file has no '" + label(need) + "' column.",
                        requireUpstream ? "Use the header: table name, domain, boundary, upstream dataset, producer eonid."
                                : "Use the header: table name, domain, boundary (upstream dataset and producer eonid are optional with --no-upstream)."));
            }
        }
        if (!findings.isEmpty()) {
            return new Result(List.of(), findings);
        }
        List<Row> rows = new ArrayList<>();
        for (int k = 1; k < r.records().size(); k++) {
            List<String> rec = r.records().get(k);
            int line = r.lines().get(k);
            String table = cell(rec, idx.get("table"));
            if (table.isEmpty()) {
                findings.add(Finding.warning("METADATA_ROW_SKIPPED", in.name() + ":line " + line, "A row without a table name was skipped.",
                        "Fill in the table name or remove the row."));
                continue;
            }
            String[] dom = splitDomain(cell(rec, idx.get("domain")));
            rows.add(new Row(table, dom[0], dom[1], cell(rec, idx.get("boundary")), cell(rec, idx.get("upstream")),
                    cell(rec, idx.get("eon")), line, null));
        }
        if (truncatedRows(r)) {
            findings.add(Finding.warning("METADATA_TRUNCATED", in.name(), "The metadata file is longer than the supported number of rows.", "Split the file."));
        }
        return new Result(rows, findings);
    }

    /** "Account (Operations DGC Glossary)" to {"Account", "Operations DGC Glossary"}; without parentheses the default glossary is used. */
    public static String[] splitDomain(String text) {
        String domain = text == null ? "" : text.trim();
        String glossary = DEFAULT_GLOSSARY;
        int open = domain.lastIndexOf('(');
        if (open > 0 && domain.endsWith(")")) {
            glossary = domain.substring(open + 1, domain.length() - 1).trim();
            domain = domain.substring(0, open).trim();
        }
        return new String[] {domain, glossary};
    }

    private static boolean truncatedRows(Csv.Result r) {
        return r.truncated();
    }

    private static String cell(List<String> rec, Integer i) {
        return i != null && i < rec.size() ? rec.get(i).trim() : "";
    }

    private static String label(String key) {
        return switch (key) {
            case "table" -> "table name";
            case "upstream" -> "upstream dataset";
            case "eon" -> "producer eonid";
            default -> key;
        };
    }

    private static String canonical(String header) {
        String h = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (h) {
            case "tablename", "table" -> "table";
            case "domain", "domainname" -> "domain";
            case "boundary", "boundaries" -> "boundary";
            case "upstreamdataset", "upstreamdatasets", "upstream" -> "upstream";
            case "producereonid", "producereon", "producerid", "eonid" -> "eon";
            default -> null;
        };
    }

    /** True when the text looks like this metadata file (used to tell it apart from an ordinary data CSV). */
    public static boolean looksLikeMetadata(String text) {
        if (text == null) {
            return false;
        }
        int nl = text.indexOf('\n');
        String head = (nl < 0 ? text : text.substring(0, nl)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9,\t;]", "");
        return head.contains("upstreamdataset") && head.contains("eonid");
    }
}

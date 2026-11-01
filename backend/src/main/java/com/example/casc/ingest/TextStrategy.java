package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.RawTerm;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT and Markdown. Conservative extraction only:
 *  - '#' headings are kept as notes;
 *  - pipe tables with a column-name header (Column, Field, ...) become dictionary entries;
 *  - lines like "TRADE_QTY: description" or "**Term**: description" become CANDIDATE terms (review required);
 *  - lines with an arrow or "derived from" style wording are kept as lineage TEXT, with no structure inferred.
 * Fenced code blocks are skipped (and reported). Interface definitions are not detected in this release.
 */
public final class TextStrategy implements SourceIngestionStrategy {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*$");
    private static final Pattern SEPARATOR = Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$");
    private static final Pattern TERM = Pattern.compile(
            "^(?:[-*+]\\s+)?(`[^`]+`|\\*\\*[^*]+\\*\\*|[A-Za-z_][A-Za-z0-9_.]*)\\s*(?::|\\s[-\u2013\u2014]\\s)\\s*(\\S.*)$");
    private static final Pattern LINEAGE = Pattern.compile(
            "->|\u2192|\\b(?:derived|sourced|populated|loaded|mapped) from\\b", Pattern.CASE_INSENSITIVE);

    @Override public String id() { return "text"; }

    @Override public Set<String> extensions() { return Set.of("txt", "md", "markdown"); }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String src = in.name();
        String[] lines = in.text().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String heading = null;
        boolean fence = false;
        int fences = 0;
        int headings = 0;
        Prose prose = new Prose(src, b);
        int mdTables = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            int ln = i + 1;
            if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = !fence;
                if (fence) {
                    fences++;
                }
                continue;
            }
            if (fence || t.isEmpty()) {
                continue;
            }
            Matcher h = HEADING.matcher(t);
            if (h.matches()) {
                heading = h.group(1).trim();
                headings++;
                b.note("HEADING", heading, src, "line " + ln);
                continue;
            }
            if (t.startsWith("|")) {
                int j = i;
                List<String> block = new ArrayList<>();
                while (j < lines.length && lines[j].trim().startsWith("|")) {
                    block.add(lines[j].trim());
                    j++;
                }
                if (table(src, block, i + 1, heading, o, b)) {
                    mdTables++;
                }
                i = j - 1;
                continue;
            }
            prose.line(t, "line " + ln);
        }
        int terms = prose.terms();
        int lineage = prose.lineage();
        if (fences > 0) {
            b.info("TEXT_CODE_BLOCKS_SKIPPED", src, fences + " fenced code block(s) were skipped.",
                    "Add SQL code blocks as separate .sql sources if they should be analysed.");
        }
        prose.summary();
        if (b.tableCount() == 0 && terms == 0 && lineage == 0) {
            b.warn("TEXT_NOTHING_EXTRACTED", src, "No tables, terms or lineage statements were recognised"
                    + (headings > 0 ? " (" + headings + " heading(s) were kept)." : "."),
                    "Provide a Markdown table with a Column/Field header, 'NAME: description' lines, or a CSV data dictionary.");
        }
        b.source(src, SourceIngestionService.extension(src).equals("txt") ? "txt" : "markdown", "INGESTED",
                b.tableCount() + " table(s), " + terms + " candidate term(s), " + lineage + " lineage statement(s), "
                        + headings + " heading(s), " + mdTables + " Markdown table(s)");
        return b.build();
    }

    /**
     * Recognises candidate terms ("NAME: description") and lineage statements in ordinary sentences. Shared by every reader that
     * produces running text (Markdown, Word, PDF, HTML). Everything it finds is a candidate for review, never a confirmed fact.
     */
    public static final class Prose {
        private final String source;
        private final Builder out;
        private int terms;
        private int lineage;

        public Prose(String source, Builder out) {
            this.source = source;
            this.out = out;
        }

        /** One trimmed line of text and where it was found. */
        public void line(String text, String location) {
            Matcher m = TERM.matcher(text);
            if (m.matches()) {
                String raw = m.group(1);
                boolean wrapped = raw.startsWith("`") || raw.startsWith("**");
                String name = wrapped ? raw.replaceAll("^(`|\\*\\*)|(`|\\*\\*)$", "") : raw;
                if (wrapped || identifierLike(name)) {
                    out.term(new RawTerm(name.trim(), m.group(2).trim(), source, location));
                    terms++;
                }
            }
            if (LINEAGE.matcher(text).find()) {
                out.note("LINEAGE_TEXT", text.length() > 500 ? text.substring(0, 500) : text, source, location);
                lineage++;
            }
        }

        public int terms() {
            return terms;
        }

        public int lineage() {
            return lineage;
        }

        /** Adds the two aggregate INFO findings. */
        public void summary() {
            if (terms > 0) {
                out.info("TEXT_TERM_CANDIDATE", source, terms + " candidate term(s) were extracted from text patterns.",
                        "Review each term; none is treated as confirmed.");
            }
            if (lineage > 0) {
                out.info("TEXT_LINEAGE_AS_TEXT", source, lineage + " lineage statement(s) were kept as text only; no structure was inferred.",
                        "Model confirmed lineage in the manifest by hand once the official shape is known.");
            }
        }
    }

    private static boolean identifierLike(String s) {
        if (s.contains("_") || s.contains(".")) {
            return true;
        }
        for (int i = 1; i < s.length(); i++) {
            if (Character.isLowerCase(s.charAt(i - 1)) && Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean table(String src, List<String> block, int startLine, String heading, IngestOptions o, Builder b) {
        String loc = src + ":line " + startLine;
        if (block.size() < 2 || !SEPARATOR.matcher(block.get(1)).matches()) {
            b.info("MD_TABLE_IGNORED", loc, "A line starting with '|' is not a Markdown table (no header separator row).",
                    "Use a standard Markdown table, or provide a CSV export.");
            return false;
        }
        List<String> headers = cells(block.get(0));
        Dictionary.Resolved res = Dictionary.resolve(headers, o.columnMap());
        if (!res.problems().isEmpty() || !res.mapping().looksLikeDictionary()) {
            b.info("MD_TABLE_IGNORED", loc, "Table not recognised as a data dictionary (needs a Column/Field header plus Description or Type). Headers: " + headers + ".",
                    "Rename the headers or set the map.* options.");
            return false;
        }
        List<List<String>> rows = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        boolean ragged = false;
        for (int k = 2; k < block.size(); k++) {
            List<String> c = cells(block.get(k));
            if (c.size() != headers.size()) {
                ragged = true;
            }
            rows.add(c);
            lines.add(startLine + k);
        }
        if (ragged) {
            b.warn("MD_TABLE_RAGGED", loc, "Some rows have a different number of cells than the header; missing cells are empty and extra cells ignored.",
                    "Check the table for unescaped '|' characters.");
        }
        Dictionary.apply(src, res.mapping(), rows, lines, res.mapping().table() < 0 ? heading : null, b);
        return true;
    }

    /** Splits a Markdown table row on unescaped pipes. */
    static List<String> cells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.endsWith("\\|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }
}

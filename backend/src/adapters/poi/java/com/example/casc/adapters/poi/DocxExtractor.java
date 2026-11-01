package com.example.casc.adapters.poi;

import com.example.casc.ingest.Dictionary;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.TextStrategy;
import java.util.ArrayList;
import java.util.List;

/**
 * The extraction rules for a Word document, mirroring those for Markdown: headings become notes; a table whose header has a
 * Column/Field name plus a Description or Type becomes dictionary entries (the table name comes from a Table column or from the
 * nearest heading above it); paragraphs are scanned for candidate terms and lineage text. Everything found is for review.
 */
public final class DocxExtractor {
    private DocxExtractor() {}

    private static final int MAX_TABLE_ROWS = 100_000;

    /** Adds findings, tables, terms and notes to b and returns the one-line summary for the source list. */
    public static String extract(List<DocxModel.Block> blocks, String source, IngestOptions o, Builder b) {
        TextStrategy.Prose prose = new TextStrategy.Prose(source, b);
        String heading = null;
        int headings = 0;
        int tables = 0;
        int dictionaryTables = 0;
        for (DocxModel.Block block : blocks) {
            if (block instanceof DocxModel.Heading h) {
                heading = h.text();
                headings++;
                b.note("HEADING", h.text(), source, "paragraph " + h.number());
            } else if (block instanceof DocxModel.Paragraph p) {
                for (String line : p.text().replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        prose.line(t, "paragraph " + p.number());
                    }
                }
            } else if (block instanceof DocxModel.Table t) {
                tables++;
                if (table(t, heading, source, o, b)) {
                    dictionaryTables++;
                }
            }
        }
        prose.summary();
        if (b.tableCount() == 0 && prose.terms() == 0 && prose.lineage() == 0) {
            b.warn("TEXT_NOTHING_EXTRACTED", source, "No tables, terms or lineage statements were recognised"
                    + (headings > 0 ? " (" + headings + " heading(s) were kept)." : "."),
                    "Use a table with a Column/Field header plus Description or Type, 'NAME: description' lines, or export a CSV data dictionary.");
        }
        return b.tableCount() + " table(s), " + prose.terms() + " candidate term(s), " + prose.lineage() + " lineage statement(s), "
                + headings + " heading(s), " + dictionaryTables + " of " + tables + " Word table(s) used";
    }

    private static boolean table(DocxModel.Table t, String heading, String source, IngestOptions o, Builder b) {
        String where = "table " + t.number();
        if (t.rows().size() < 2) {
            b.info("DOCX_TABLE_IGNORED", source + ":" + where, "A table with fewer than two rows was ignored.", "None needed.");
            return false;
        }
        List<String> headers = new ArrayList<>();
        for (String h : t.rows().get(0)) {
            headers.add(h.trim());
        }
        Dictionary.Resolved res = Dictionary.resolve(headers, o.columnMap());
        if (!res.problems().isEmpty() || !res.mapping().looksLikeDictionary()) {
            b.info("DOCX_TABLE_IGNORED", source + ":" + where, "Table not recognised as a data dictionary (needs a Column/Field header plus Description or Type). Headers: " + headers + ".",
                    "Rename the headers or set the map.* options.");
            return false;
        }
        List<List<String>> rows = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i < t.rows().size() && i <= MAX_TABLE_ROWS; i++) {
            rows.add(t.rows().get(i));
            numbers.add(i + 1);
        }
        boolean ragged = rows.stream().anyMatch(r -> r.size() != headers.size());
        if (ragged) {
            b.warn("DOCX_TABLE_RAGGED", source + ":" + where, "Some rows have a different number of cells than the header (merged cells?); missing cells are empty.",
                    "Check merged cells in the table.");
        }
        Dictionary.apply(source, res.mapping(), rows, numbers, res.mapping().table() < 0 ? heading : null, b, where + ", row ");
        return true;
    }
}

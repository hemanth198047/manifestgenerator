package com.example.casc.adapters.jsoup;

import com.example.casc.ingest.Dictionary;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.ingest.TextStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * HTML pages and wiki exports through jsoup (an optional adapter), including Confluence page content (the service unwraps Confluence
 * REST JSON into HTML first). The page is parsed, never rendered: scripts, styles and any network resources are ignored.
 * Extracted, with the same rules as Markdown and Word: headings (as notes), tables with a Column/Field header plus Description or
 * Type (as dictionary entries; the table name comes from a Table column or the nearest heading above), and candidate terms and
 * lineage text from paragraphs and list items. Everything is for review. Pages that build their content with scripts have nothing to read.
 */
public final class HtmlStrategy implements SourceIngestionStrategy {
    private static final int MAX_TABLE_ROWS = 100_000;

    @Override public String id() { return "jsoup"; }

    @Override public Set<String> extensions() { return Set.of("html", "htm"); }

    @Override
    public RawMetadataBundle ingest(SourceInput in, IngestOptions o) {
        Builder b = new Builder();
        String src = in.name();
        try {
            Document doc = Jsoup.parse(in.text());
            doc.select("script, style, noscript, template").remove();
            TextStrategy.Prose prose = new TextStrategy.Prose(src, b);
            String heading = null;
            int headings = 0;
            int tables = 0;
            int used = 0;
            if (!doc.title().isBlank()) {
                b.note("HEADING", doc.title().trim(), src, "title");
            }
            Elements all = doc.body() == null ? new Elements() : doc.body().select("h1, h2, h3, h4, h5, h6, table, p, li, dt, dd, pre");
            for (Element el : all) {
                if (el.parents().is("table") && !el.tagName().equals("table")) {
                    continue;                                       // content of a table cell is handled with its table
                }
                if (el.tagName().equals("table")) {
                    if (el.parents().is("table")) {
                        continue;                                   // a nested table belongs to its outer table's cell
                    }
                    tables++;
                    if (table(el, heading, src, o, b)) {
                        used++;
                    }
                } else if (el.tagName().matches("h[1-6]")) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        heading = text;
                        headings++;
                        b.note("HEADING", text, src, where(el));
                    }
                } else {
                    String text = el.tagName().equals("pre") ? el.wholeText() : emphasised(el);
                    for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty()) {
                            prose.line(t, where(el));
                        }
                    }
                }
            }
            prose.summary();
            if (b.tableCount() == 0 && prose.terms() == 0 && prose.lineage() == 0) {
                b.warn("TEXT_NOTHING_EXTRACTED", src, "No tables, terms or lineage statements were recognised"
                        + (headings > 0 ? " (" + headings + " heading(s) were kept)." : "."),
                        "Use a table with a Column/Field header plus Description or Type, 'NAME: description' lines, or export a CSV data dictionary.");
            }
            b.source(src, "html", "INGESTED", b.tableCount() + " table(s), " + prose.terms() + " candidate term(s), " + prose.lineage()
                    + " lineage statement(s), " + headings + " heading(s), " + used + " of " + tables + " HTML table(s) used");
        } catch (RuntimeException e) {
            b.error("HTML_UNREADABLE", src, "The page could not be parsed (" + e.getClass().getSimpleName() + ").", "Save the content as Markdown or text.");
            b.source(src, "html", "REJECTED", "unreadable");
        }
        return b.build();
    }

    /** The element's text with bold and code marked the Markdown way, so "<b>Term</b>: text" is read like "**Term**: text". */
    private static String emphasised(Element el) {
        Element copy = el.clone();
        for (Element e : copy.select("b, strong")) {
            e.prependText("**");
            e.appendText("**");
        }
        for (Element e : copy.select("code")) {
            e.prependText("`");
            e.appendText("`");
        }
        return copy.text();
    }

    private static String where(Element el) {
        String s = el.cssSelector();
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    private boolean table(Element table, String heading, String src, IngestOptions o, Builder b) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            Element owner = null;
            for (Element p : tr.parents()) {
                if (p.tagName().equals("table")) {
                    owner = p;
                    break;
                }
            }
            if (owner != table) {
                continue;                                           // a row of a nested table
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.children()) {
                if (cell.tagName().equals("th") || cell.tagName().equals("td")) {
                    cells.add(cell.text().trim());
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        String loc = where(table);
        if (rows.size() < 2) {
            b.info("HTML_TABLE_IGNORED", src + ":" + loc, "A table with fewer than two rows was ignored.", "None needed.");
            return false;
        }
        List<String> headers = rows.get(0);
        Dictionary.Resolved res = Dictionary.resolve(headers, o.columnMap());
        if (!res.problems().isEmpty() || !res.mapping().looksLikeDictionary()) {
            b.info("HTML_TABLE_IGNORED", src + ":" + loc, "Table not recognised as a data dictionary (needs a Column/Field header plus Description or Type). Headers: " + headers + ".",
                    "Rename the headers or set the map.* options.");
            return false;
        }
        List<List<String>> data = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i < rows.size() && i <= MAX_TABLE_ROWS; i++) {
            data.add(rows.get(i));
            numbers.add(i + 1);
        }
        if (data.stream().anyMatch(r -> r.size() != headers.size())) {
            b.warn("HTML_TABLE_RAGGED", src + ":" + loc, "Some rows have a different number of cells than the header (merged cells?); missing cells are empty.", "Check merged cells.");
        }
        Dictionary.apply(src, res.mapping(), data, numbers, res.mapping().table() < 0 ? heading : null, b, "table " + loc + ", row ");
        return true;
    }
}

package com.example.casc.adapters.jsoup;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.OpenApiStrategy;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import java.util.List;
import java.util.Map;

/** Tests for the jsoup adapter: pages are parsed as text, never rendered or scripted. */
public final class HtmlTests {
    private HtmlTests() {}

    private static SourceIngestionService service() {
        return new SourceIngestionService(List.<SourceIngestionStrategy>of(new HtmlStrategy(), new OpenApiStrategy()));
    }

    private static RawMetadataBundle html(String text) {
        return service().ingest(List.of(SourceInput.of("page.html", text)), IngestOptions.defaults());
    }

    private static boolean has(RawMetadataBundle b, String code) {
        return b.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    private static final String PAGE = "<html><head><title>Trade data</title><style>.x{color:red}</style><script>var ignored = 'SECRET_JS: not a term';</script></head><body>"
            + "<h1>Overview</h1><h2>trade.positions</h2>"
            + "<table><thead><tr><th>Field</th><th>Description</th><th>Type</th></tr></thead><tbody>"
            + "<tr><td>position_id</td><td>Identifier</td><td>INTEGER</td></tr>"
            + "<tr><td>quantity</td><td>Net <b>quantity</b><table><tr><td>nested</td><td>ignored</td></tr></table></td><td>DECIMAL</td></tr></tbody></table>"
            + "<p>TRADE_QTY: Quantity of the trade</p><p>Note: just prose</p><ul><li><b>Client Identifier</b>: Identifies the client</li></ul>"
            + "<p>The v_orders view is derived from trade.orders.</p>"
            + "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>";

    public static void register() {
        test("html: headings, a dictionary table named from its heading, terms and lineage; scripts and nested tables are ignored", () -> {
            RawMetadataBundle b = html(PAGE);
            eq("INGESTED", b.sources().get(0).status());
            RawTable t = b.tables().get(0);
            eq("positions", t.name());
            eq("trade", t.schema());
            eq(List.of("position_id", "quantity"), t.columns().stream().map(c -> c.name()).toList());
            eq("Net quantity nested ignored", t.columns().get(1).description());
            eq(List.of("TRADE_QTY", "Client Identifier"), b.terms().stream().map(x -> x.name()).toList());
            eq(1L, b.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).count());
            yes(b.notes().stream().anyMatch(n -> n.kind().equals("HEADING") && n.text().equals("Trade data")), "the title is kept");
            yes(has(b, "HTML_TABLE_IGNORED") && has(b, "TEXT_TERM_CANDIDATE"), b.findings().toString());
            excludes(Json.write(b.toTree()), "SECRET_JS");
        });

        test("html: pages with nothing recognisable and malformed markup", () -> {
            yes(has(html("<html><body><p>Just words.</p></body></html>"), "TEXT_NOTHING_EXTRACTED"), "an empty extraction is a warning");
            RawMetadataBundle messy = html("<h2>s.t<table><tr><th>Column<th>Description<tr><td>a<td>b");
            eq(1, messy.tables().size());
            eq("a", messy.tables().get(0).columns().get(0).name());
            yes(has(html("<table><tr><th>Column</th><th>Description</th></tr><tr><td>a</td></tr></table>"), "HTML_TABLE_RAGGED"), "ragged rows are reported");
        });

        test("html: Confluence page JSON is unwrapped into an HTML source; without the adapter it is reported", () -> {
            String page = "{\"id\":\"1\",\"title\":\"Orders spec\",\"body\":{\"storage\":{\"value\":\"<h2>trade.orders</h2><table><tr><th>Column</th><th>Description</th></tr><tr><td>order_id</td><td>Identifier</td></tr></table>\",\"representation\":\"storage\"}}}";
            RawMetadataBundle b = service().ingest(List.of(SourceInput.of("page.json", page)), IngestOptions.defaults());
            eq("orders", b.tables().get(0).name());
            eq("page.html", b.sources().get(0).name());
            RawMetadataBundle without = new SourceIngestionService(List.<SourceIngestionStrategy>of(new OpenApiStrategy()))
                    .ingest(List.of(SourceInput.of("page.json", page)), IngestOptions.defaults());
            yes(has(without, "FORMAT_UNAVAILABLE"), without.findings().toString());
            contains(without.findings().get(0).message(), "Confluence");
            RawMetadataBundle other = service().ingest(List.of(SourceInput.of("x.json", "{\"body\":{\"unrelated\":1}}")), IngestOptions.defaults());
            yes(has(other, "OPENAPI_NOT_RECOGNISED"), "other JSON is still handled as before");
        });

        test("html: through the service when the adapter is registered; URL-fetched pages arrive as text", () -> {
            if (SourceIngestionService.standard().readerFor("html") == null) {
                throw new Skipped("the adapter is not registered with ServiceLoader on this class path");
            }
            RawMetadataBundle b = SourceIngestionService.standard().ingest(List.of(SourceInput.of("p.htm", PAGE)), IngestOptions.defaults());
            eq(1, b.tables().size());
        });
    }
}

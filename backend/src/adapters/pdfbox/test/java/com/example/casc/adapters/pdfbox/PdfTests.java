package com.example.casc.adapters.pdfbox;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Tests for the PDFBox adapter. The test PDFs are written by hand below so they do not depend on PDFBox's (version specific) creation API. */
public final class PdfTests {
    private PdfTests() {}

    /** A minimal valid PDF with one page per list entry; each entry is the lines of text of that page (Helvetica, ASCII only). */
    public static byte[] pdf(List<List<String>> pages) {
        List<String> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(4 + 2 * i).append(" 0 R ");
        }
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [ " + kids + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int i = 0; i < pages.size(); i++) {
            StringBuilder text = new StringBuilder("BT /F1 12 Tf 72 720 Td 14 TL ");
            for (String line : pages.get(i)) {
                text.append('(').append(line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj T* ");
            }
            text.append("ET");
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " + (5 + 2 * i) + " 0 R /Resources << /Font << /F1 3 0 R >> >> >>");
            objects.add("<< /Length " + text.length() + " >>\nstream\n" + text + "\nendstream");
        }
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(sb.length());
            sb.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static RawMetadataBundle run(byte[] bytes) {
        return new SourceIngestionService(List.<SourceIngestionStrategy>of(new PdfStrategy()))
                .ingest(List.of(SourceInput.binary("spec.pdf", bytes)), IngestOptions.defaults());
    }

    private static boolean has(RawMetadataBundle b, String code) {
        return b.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    public static void register() {
        test("pdf: text is read page by page; terms and lineage text are candidates; the limits are stated", () -> {
            RawMetadataBundle b = run(pdf(List.of(
                    List.of("Trade interface", "TRADE_QTY: Quantity of the trade", "Plain sentence: with a colon"),
                    List.of("The v_orders view is derived from trade.orders."))));
            eq("INGESTED", b.sources().get(0).status());
            eq(List.of("TRADE_QTY"), b.terms().stream().map(t -> t.name()).toList());
            eq("page 1, line 2", b.terms().get(0).location());
            eq(1L, b.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).count());
            eq("page 2, line 1", b.notes().get(0).location());
            yes(has(b, "PDF_TABLES_NOT_RECONSTRUCTED") && has(b, "TEXT_TERM_CANDIDATE"), b.findings().toString());
            eq(0, b.tables().size());
        });

        test("pdf: a document without text is reported, never an empty success", () -> {
            RawMetadataBundle b = run(pdf(List.of(List.of())));
            yes(has(b, "PDF_NO_TEXT"), b.findings().toString());
            RawMetadataBundle prose = run(pdf(List.of(List.of("Only ordinary sentences here."))));
            yes(has(prose, "TEXT_NOTHING_EXTRACTED"), prose.findings().toString());
        });

        test("pdf: damaged files are rejected with a clear message and no content echoed", () -> {
            RawMetadataBundle bad = run("definitely not a pdf, SECRET-TEXT".getBytes(StandardCharsets.UTF_8));
            yes(has(bad, "PDF_UNREADABLE"), bad.findings().toString());
            eq("REJECTED", bad.sources().get(0).status());
            excludes(Json.write(bad.toTree()), "SECRET-TEXT");
        });

        test("pdf: through the service when the adapter is registered", () -> {
            if (SourceIngestionService.standard().readerFor("pdf") == null) {
                throw new Skipped("the adapter is not registered with ServiceLoader on this class path");
            }
            RawMetadataBundle b = SourceIngestionService.standard().ingest(List.of(SourceInput.binary("a.pdf", pdf(List.of(List.of("X_Y: described"))))), IngestOptions.defaults());
            eq(1, b.terms().size());
            yes(SourceIngestionService.standard().ingest(List.of(SourceInput.of("a.pdf", "text")), IngestOptions.defaults()).findings().stream()
                    .anyMatch(f -> f.code().equals("SOURCE_ENCODING")), "a PDF must arrive as bytes");
        });
    }
}

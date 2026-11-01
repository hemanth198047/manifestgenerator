package com.example.casc.adapters.poi;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * A real .docx made with POI. Kept in its own file because creating tables needs POI's complete OOXML schema classes at compile time
 * (the jar called poi-ooxml-lite or poi-ooxml-full in current POI releases). Skips where POI's OOXML engine cannot start.
 */
public final class PoiDocxTests {
    private PoiDocxTests() {}

    public static void register() {
        test("poi: a real .docx is read: heading, dictionary table named from it, candidate term", () -> {
            byte[] docx;
            try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                var h = doc.createParagraph();
                h.setStyle("Heading1");
                h.createRun().setText("s.orders");
                var t = doc.createTable(2, 3);
                t.getRow(0).getCell(0).setText("Field");
                t.getRow(0).getCell(1).setText("Description");
                t.getRow(0).getCell(2).setText("Type");
                t.getRow(1).getCell(0).setText("order_id");
                t.getRow(1).getCell(1).setText("Identifier");
                t.getRow(1).getCell(2).setText("INT");
                doc.createParagraph().createRun().setText("TRADE_QTY: Quantity of the trade");
                doc.write(out);
                docx = out.toByteArray();
            } catch (LinkageError | RuntimeException e) {
                throw new Skipped("POI's OOXML engine cannot start on this class path (" + e.getClass().getSimpleName() + ")");
            }
            RawMetadataBundle d = new SourceIngestionService(List.<SourceIngestionStrategy>of(new PoiStrategy())).ingest(List.of(SourceInput.binary("spec.docx", docx)), IngestOptions.defaults());
            eq("orders", d.tables().stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow().name());
            eq("order_id", d.tables().stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow().columns().get(0).name());
            eq(List.of("TRADE_QTY"), d.terms().stream().map(x -> x.name()).toList());
        });
    }
}

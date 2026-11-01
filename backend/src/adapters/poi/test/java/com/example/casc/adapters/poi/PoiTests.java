package com.example.casc.adapters.poi;

import static com.example.casc.TestKit.*;

import com.example.casc.agent.AgentTools;
import com.example.casc.cli.Cli;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceIngestionStrategy;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import com.example.casc.net.UrlFetcher;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Tests for the POI adapter. Spreadsheets run through the shared Workbook interface, so the legacy .xls tests exercise the same
 * extraction code as .xlsx; tests that need POI's OOXML engine (.xlsx, .docx files) skip themselves where that engine cannot start.
 */
public final class PoiTests {
    private PoiTests() {}

    private static SourceIngestionService service() {
        return new SourceIngestionService(List.<SourceIngestionStrategy>of(new PoiStrategy(), new com.example.casc.ingest.OpenApiStrategy()));
    }

    private static RawMetadataBundle run(String name, byte[] bytes, IngestOptions o) {
        return service().ingest(List.of(SourceInput.binary(name, bytes)), o);
    }

    private static boolean has(RawMetadataBundle b, String code) {
        return b.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    private static RawTable table(RawMetadataBundle b, String name) {
        return b.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no table " + name + " in " + b.tables().stream().map(RawTable::name).toList()));
    }

    private static void put(Sheet s, int rowIndex, Object... values) {
        Row row = s.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Double d) {
                row.createCell(i).setCellValue(d);
            } else if (values[i] instanceof Boolean x) {
                row.createCell(i).setCellValue(x);
            } else if (values[i] != null) {
                row.createCell(i).setCellValue(values[i].toString());
            }
        }
    }

    /** A legacy workbook with a dictionary sheet, a data sheet, a hidden sheet and an empty sheet. */
    private static byte[] workbook() throws Exception {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet dict = wb.createSheet("Dictionary");
            put(dict, 0, "Table", "Column", "Description", "Type");
            put(dict, 1, "trade.orders", "order_id", "Identifier", "NUMBER");
            put(dict, 2, "trade.orders", "qty", "Quantity", "NUMBER");
            put(dict, 3, "trade.clients", "client_id", "Client id", "VARCHAR");
            Sheet data = wb.createSheet("positions");
            put(data, 0, "id", "price", "created", "flag", "note", "calc");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            for (int i = 0; i < 2; i++) {
                Row row = data.createRow(i + 1);
                row.createCell(0).setCellValue((double) (i + 1));
                row.createCell(1).setCellValue(i == 0 ? 2.5 : 3.0);
                var c = row.createCell(2);
                c.setCellValue(new Date(1704153600000L + i * 86_400_000L));   // 2024-01-02 and 2024-01-03
                c.setCellStyle(date);
                row.createCell(3).setCellValue(i == 0);
                row.createCell(4).setCellValue(i == 0 ? "SECRET-VALUE-123" : "");
                row.createCell(5).setCellFormula("B" + (i + 2) + "*2");
            }
            Sheet hidden = wb.createSheet("hidden");
            put(hidden, 0, "x");
            wb.setSheetHidden(2, true);
            wb.createSheet("empty");
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static void register() {
        try {
            Class.forName("com.example.casc.adapters.poi.PoiDocxTests").getMethod("register").invoke(null);
        } catch (ClassNotFoundException e) {
            System.out.println("NOTE  PoiDocxTests not compiled");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        test("poi: a workbook's sheets become dictionary entries and a data table, with sheet and row locations", () -> {
            RawMetadataBundle b = run("book.xls", workbook(), IngestOptions.defaults());
            eq("INGESTED", b.sources().get(0).status());
            RawTable orders = table(b, "orders");
            eq("trade", orders.schema());
            eq(2, orders.columns().size());
            eq("Quantity", orders.columns().get(1).description());
            eq("sheet 'Dictionary', row 3", orders.columns().get(1).location());
            RawTable positions = table(b, "positions");
            eq("sheet 'positions', row 1", positions.location());
            var cols = positions.columns();
            eq("INTEGER", cols.get(0).dataType());
            eq("DECIMAL", cols.get(1).dataType());
            eq("DATE", cols.get(2).dataType());
            eq("BOOLEAN", cols.get(3).dataType());
            eq("INTEGER", cols.get(5).dataType());                       // =B*2 is evaluated: 5 and 6
            yes(has(b, "SHEET_HIDDEN") && has(b, "SHEET_EMPTY") && has(b, "TYPES_INFERRED") && has(b, "CELL_TEXT_AS_SHOWN"), "findings: " + b.findings());
            excludes(Json.write(b.toTree()), "SECRET-VALUE-123");           // row values are not retained
        });

        test("poi: sheet selection by name or number, unknown sheets, sampling limit", () -> {
            byte[] book = workbook();
            RawMetadataBundle byName = run("book.xls", book, IngestOptions.fromMap(Map.of("sheet", "POSITIONS")));
            eq(List.of("positions"), byName.tables().stream().map(RawTable::name).toList());
            RawMetadataBundle byNumber = run("book.xls", book, IngestOptions.fromMap(Map.of("sheet", "1")));
            eq(List.of("orders", "clients"), byNumber.tables().stream().map(RawTable::name).toList());
            RawMetadataBundle missing = run("book.xls", book, IngestOptions.fromMap(Map.of("sheet", "nope")));
            yes(has(missing, "SHEET_NOT_FOUND"), missing.findings().toString());
            eq("REJECTED", missing.sources().get(0).status());
            yes(has(run("book.xls", book, IngestOptions.fromMap(Map.of("sheet", "positions", "maxRows", "1"))), "SAMPLING_LIMIT"), "maxRows applies to sheets");
            // a hidden sheet is read when it is selected explicitly? No: it stays hidden and is reported.
            yes(has(run("book.xls", book, IngestOptions.fromMap(Map.of("sheet", "hidden"))), "SHEET_HIDDEN"), "hidden sheets are skipped even when named");
        });

        test("poi: damaged, empty and mistyped files are reported clearly", () -> {
            RawMetadataBundle bad = run("bad.xls", "this is not a spreadsheet".getBytes(StandardCharsets.UTF_8), IngestOptions.defaults());
            yes(has(bad, "SPREADSHEET_UNREADABLE"), bad.findings().toString());
            eq("REJECTED", bad.sources().get(0).status());
            excludes(Json.write(bad.toTree()), "this is not a spreadsheet");
            RawMetadataBundle text = service().ingest(List.of(SourceInput.of("t.xls", "a,b")), IngestOptions.defaults());
            yes(has(text, "SOURCE_ENCODING"), "a spreadsheet must arrive as bytes: " + text.findings());
        });

        test("poi: through the service, the CLI and the tool contract (when the adapter is registered)", () -> {
            if (SourceIngestionService.standard().readerFor("xls") == null) {
                throw new Skipped("the adapter is not registered with ServiceLoader on this class path");
            }
            Path dir = tempDir("casc poi ");
            try {
                Path file = dir.resolve("my book.xls");
                Files.write(file, workbook());
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                int code = Cli.run(new String[] {"ingest", "--input-files", file.toString(), "--sheet", "positions"}, java.io.InputStream.nullInputStream(),
                        new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), k -> null);
                eq(Cli.OK, code);
                contains(o.toString(StandardCharsets.UTF_8), "1 table(s)/view(s)");
                String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                Map<?, ?> r = new AgentTools(UrlFetcher.disabled()).call("ingest_sources", Json.parse("{\"sources\":[{\"name\":\"b.xls\",\"contentBase64\":\"" + b64 + "\"}]}"));
                eq(Boolean.FALSE, r.get("hasErrors"));
                eq(3, ((List<?>) r.get("tables")).size());
            } finally {
                deleteTree(dir);
            }
        });

        // ---- Word extraction rules (pure: no Word files needed)
        test("docx rules: headings, dictionary tables named from the heading, terms and lineage text", () -> {
            RawMetadataBundle.Builder b = new RawMetadataBundle.Builder();
            List<DocxModel.Block> blocks = List.of(
                    new DocxModel.Heading(1, 1, "Trade data"),
                    new DocxModel.Heading(2, 2, "trade.positions"),
                    new DocxModel.Table(1, List.of(List.of("Field", "Description", "Type"), List.of("position_id", "Identifier", "INTEGER"), List.of("quantity", "Net quantity", "DECIMAL"))),
                    new DocxModel.Paragraph(4, "TRADE_QTY: Quantity of the trade\nplain prose: with a colon"),
                    new DocxModel.Paragraph(5, "The v_orders view is derived from trade.orders."),
                    new DocxModel.Table(2, List.of(List.of("A", "B"), List.of("1", "2"))));
            String detail = DocxExtractor.extract(blocks, "spec.docx", IngestOptions.defaults(), b);
            RawMetadataBundle r = b.build();
            RawTable t = table(r, "positions");
            eq("trade", t.schema());
            eq("table 1, row 2", t.columns().get(0).location());
            eq(List.of("TRADE_QTY"), r.terms().stream().map(x -> x.name()).toList());
            eq("paragraph 4", r.terms().get(0).location());
            eq(1L, r.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).count());
            eq(2L, r.notes().stream().filter(n -> n.kind().equals("HEADING")).count());
            yes(has(r, "DOCX_TABLE_IGNORED") && has(r, "TEXT_TERM_CANDIDATE"), r.findings().toString());
            contains(detail, "1 of 2 Word table(s) used");
        });

        test("docx rules: ragged tables, tiny tables and documents with nothing recognisable", () -> {
            RawMetadataBundle.Builder b = new RawMetadataBundle.Builder();
            DocxExtractor.extract(List.of(new DocxModel.Heading(1, 1, "T"),
                    new DocxModel.Table(1, List.of(List.of("Column", "Description"), List.of("a", "b", "extra"), List.of("c"))),
                    new DocxModel.Table(2, List.of(List.of("only one row")))), "d.docx", IngestOptions.defaults(), b);
            RawMetadataBundle r = b.build();
            yes(has(r, "DOCX_TABLE_RAGGED") && has(r, "DOCX_TABLE_IGNORED"), r.findings().toString());
            RawMetadataBundle.Builder nothing = new RawMetadataBundle.Builder();
            DocxExtractor.extract(List.of(new DocxModel.Heading(1, 1, "Title"), new DocxModel.Paragraph(2, "Just prose.")), "n.docx", IngestOptions.defaults(), nothing);
            yes(has(nothing.build(), "TEXT_NOTHING_EXTRACTED"), "an empty extraction is a warning, never a silent success");
        });

        test("docx: heading styles (names and ids) are recognised", () -> {
            eq(1, DocxReader.headingLevel("Title", null));
            eq(1, DocxReader.headingLevel("Heading1", null) );
            eq(2, DocxReader.headingLevel("Heading2", null));
            eq(3, DocxReader.headingLevel("heading 3", null));
            eq(0, DocxReader.headingLevel("Normal", null));
            eq(0, DocxReader.headingLevel("ListParagraph", null));
            eq(0, DocxReader.headingLevel(null, null));
        });

        // ---- real OOXML files: need POI's OOXML engine, which not every class path can start
        test("poi: a real .xlsx (skipped where POI's OOXML engine cannot start) and a damaged .docx", () -> {
            byte[] xlsx;
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet s = wb.createSheet("Dictionary");
                put(s, 0, "Table", "Column", "Description");
                put(s, 1, "s.t", "c", "A column");
                wb.write(out);
                xlsx = out.toByteArray();
            } catch (LinkageError | RuntimeException e) {
                throw new Skipped("POI's OOXML engine cannot start on this class path (" + e.getClass().getSimpleName() + ")");
            }
            RawMetadataBundle b = run("book.xlsx", xlsx, IngestOptions.defaults());
            eq("t", b.tables().get(0).name());
            eq("A column", b.tables().get(0).columns().get(0).description());
            RawMetadataBundle junk = run("broken.docx", "not a zip".getBytes(StandardCharsets.UTF_8), IngestOptions.defaults());
            yes(has(junk, "DOCX_UNREADABLE"), junk.findings().toString());
        });
    }
}

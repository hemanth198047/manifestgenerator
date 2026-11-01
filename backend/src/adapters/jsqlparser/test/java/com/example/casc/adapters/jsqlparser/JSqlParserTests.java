package com.example.casc.adapters.jsqlparser;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.LineageHint;
import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceInput;
import com.example.casc.ingest.SqlStrategy;
import java.util.List;

/**
 * Tests for the JSqlParser adapter. They need the real library; without it they skip. They have been run against JSqlParser 4.9,
 * built from its source tag (the published jar was not obtainable where this was written). JSqlParser 5.x has not been tried.
 */
public final class JSqlParserTests {
    private JSqlParserTests() {}

    private static void needLibrary() {
        try {
            Class.forName("net.sf.jsqlparser.parser.CCJSqlParserUtil");
        } catch (ClassNotFoundException | LinkageError e) {
            throw new Skipped("JSqlParser is not on the class path");
        }
    }

    private static List<String> pairs(List<LineageHint> hints) {
        return hints.stream().map(h -> h.target() + " <- " + h.source()).toList();
    }

    public static void register() {
        test("jsqlparser: INSERT, UPDATE, MERGE and CREATE TABLE AS give table-level lineage hints", () -> {
            needLibrary();
            JSqlParserAnalyzer a = new JSqlParserAnalyzer();
            eq(List.of("dw.sales_summary <- raw.sales", "dw.sales_summary <- raw.regions"),
                    pairs(a.analyze("INSERT INTO dw.sales_summary (region, total) SELECT r.name, SUM(s.amount) FROM raw.sales s JOIN raw.regions r ON s.region_id = r.id GROUP BY r.name", "auto", "etl.sql", "line 3")));
            eq(List.of("dw.t <- raw.s"), pairs(a.analyze("UPDATE dw.t SET x = (SELECT MAX(y) FROM raw.s)", "auto", "etl.sql", "line 9")));
            eq(List.of("dw.t <- raw.s"), pairs(a.analyze("MERGE INTO dw.t USING raw.s ON (dw.t.id = raw.s.id) WHEN MATCHED THEN UPDATE SET dw.t.v = raw.s.v", "auto", "etl.sql", "line 12")));
            eq(List.of("dw.copy <- raw.s"), pairs(a.analyze("CREATE TABLE dw.copy AS SELECT * FROM raw.s", "auto", "etl.sql", "line 15")));
            eq(List.of(), pairs(a.analyze("INSERT INTO dw.t VALUES (1)", "auto", "etl.sql", "line 1")));
            contains(a.analyze("INSERT INTO t SELECT a FROM s", "auto", "f.sql", "line 7").get(0).location(), "line 7");
        });

        test("jsqlparser: statements it cannot or should not handle come back as null", () -> {
            needLibrary();
            JSqlParserAnalyzer a = new JSqlParserAnalyzer();
            eq(null, a.analyze("SELECT * FROM t", "auto", "f.sql", "line 1"));
            eq(null, a.analyze("DELETE FROM t WHERE a = 1", "auto", "f.sql", "line 1"));
            eq(null, a.analyze("INSERT INTO ((( nonsense", "auto", "f.sql", "line 1"));
            eq(null, a.analyze("", "auto", "f.sql", "line 1"));
        });

        test("jsqlparser: wired into the SQL reader, DML is analysed and everything else is still reported", () -> {
            needLibrary();
            if (SqlStrategy.analyzerCount() == 0) {
                throw new Skipped("the analyzer is not registered with ServiceLoader on this class path");
            }
            RawMetadataBundle b = SourceIngestionService.standard().ingest(List.of(SourceInput.of("etl.sql",
                    "CREATE TABLE dw.t (a INT);\nINSERT INTO dw.t SELECT a FROM raw.s;\nSELECT 1;\nDROP TABLE x;\n")), IngestOptions.defaults());
            eq(List.of("dw.t <- raw.s"), b.lineage().stream().map(h -> h.target() + " <- " + h.source()).toList());
            yes(b.findings().stream().anyMatch(f -> f.code().equals("SQL_DML_ANALYSED")), b.findings().toString());
            yes(b.findings().stream().filter(f -> f.code().equals("SQL_UNSUPPORTED")).count() == 2, "SELECT and DROP are still reported: " + b.findings());
        });
    }
}

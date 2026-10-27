package com.example.casc.ingest;

import static com.example.casc.TestKit.*;

import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.json.Json;
import com.example.casc.validation.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IngestTests {
    private IngestTests() {}

    static RawMetadataBundle run(String name, String text, IngestOptions o) {
        return SourceIngestionService.standard().ingest(List.of(SourceInput.of(name, text)), o);
    }

    static RawMetadataBundle run(String name, String text) {
        return run(name, text, IngestOptions.defaults());
    }

    static IngestOptions opts(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return IngestOptions.fromMap(m);
    }

    static boolean has(RawMetadataBundle b, String code) {
        return b.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    static void expect(RawMetadataBundle b, String code) {
        yes(has(b, code), "expected finding " + code + " but got " + b.findings().stream().map(Finding::code).toList());
    }

    static RawTable table(RawMetadataBundle b, String name) {
        return b.tables().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no table " + name + " in " + b.tables().stream().map(RawTable::name).toList()));
    }

    static RawColumn col(RawTable t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no column " + name));
    }

    public static void register() {
        csvTests();
        delimitedTests();
        textTests();
        sqlTests();
        serviceTests();
        draftTests();
        fileTests();
    }

    // ------------------------------------------------------------------ CSV reader

    private static void csvTests() {
        test("csv: quotes, doubled quotes, embedded delimiter and line breaks", () -> {
            Csv.Result r = Csv.parse("a,\"b,1\",\"say \"\"hi\"\"\"\n\"multi\nline\",x,\n", ',', 100);
            eq(List.of(List.of("a", "b,1", "say \"hi\""), List.of("multi\nline", "x", "")), r.records());
            eq(List.of(1, 2), r.lines());
        });

        test("csv: LF, CRLF, CR, BOM, blank lines and tab delimiter", () -> {
            eq(List.of(List.of("a", "b"), List.of("c", "d")), Csv.parse("\uFEFFa,b\r\n\r\nc,d", ',', 10).records());
            eq(List.of(List.of("a", "b"), List.of("c", "d")), Csv.parse("a,b\rc,d\r", ',', 10).records());
            eq(List.of(List.of("a", "b c")), Csv.parse("a\tb c\n", '\t', 10).records());
        });

        test("csv: malformed input is rejected, not guessed", () -> {
            throwsA(Csv.CsvException.class, () -> Csv.parse("a,b\"c\n", ',', 10));
            throwsA(Csv.CsvException.class, () -> Csv.parse("\"abc\"x,1\n", ',', 10));
            throwsA(Csv.CsvException.class, () -> Csv.parse("a,\"unterminated\n", ',', 10));
        });

        test("csv: record limit reports truncation", () -> {
            Csv.Result r = Csv.parse("1\n2\n3\n", ',', 2);
            eq(2, r.records().size());
            yes(r.truncated(), "must report truncation");
            yes(!Csv.parse("1\n2\n", ',', 2).truncated(), "exact fit is not truncated");
        });
    }

    // ------------------------------------------------------------------ CSV / TSV strategy

    private static void delimitedTests() {
        test("data dump: table from file name, inferred types, nullability, no row values retained", () -> {
            RawMetadataBundle b = run("dir/orders.csv", "id,price,created,flag,note,empty\n"
                    + "1,2.50,2024-01-02,true,SECRET-VALUE-123,\n2,3,2024-01-03T10:00:00Z,false,,\n");
            RawTable t = table(b, "orders");
            eq("INTEGER", col(t, "id").dataType());
            eq("DECIMAL", col(t, "price").dataType());
            eq("TIMESTAMP", col(t, "created").dataType());
            eq("BOOLEAN", col(t, "flag").dataType());
            eq("TEXT", col(t, "note").dataType());
            eq(Boolean.TRUE, col(t, "note").nullable());
            eq(null, col(t, "empty").dataType());
            yes(col(t, "id").typeInferred(), "types are flagged as inferred");
            excludes(Json.write(b.toTree()), "SECRET-VALUE-123");
            expect(b, "TYPES_INFERRED");
        });

        test("data dump: ragged rows, blank and duplicate headers, sampling limit", () -> {
            RawMetadataBundle b = run("t.csv", "a,,a\n1,2\n3,4,5\n6,7,8\n", opts("maxRows", "2"));
            expect(b, "CSV_RAGGED");
            expect(b, "CSV_BLANK_HEADER");
            expect(b, "CSV_DUPLICATE_HEADER");
            expect(b, "SAMPLING_LIMIT");
            eq(List.of("a", "column2", "a"), table(b, "t").columns().stream().map(RawColumn::name).toList());
        });

        test("data dump: header row number, no header, TSV by extension, delimiter override", () -> {
            RawMetadataBundle b = run("t.csv", "Report 2024\nid,name\n1,x\n", opts("headerRow", "2"));
            eq(List.of("id", "name"), table(b, "t").columns().stream().map(RawColumn::name).toList());
            expect(b, "CSV_ROWS_BEFORE_HEADER");
            eq(List.of("column1", "column2"), table(run("n.csv", "1,a\n2,b\n", opts("hasHeader", "false")), "n")
                    .columns().stream().map(RawColumn::name).toList());
            eq(List.of("id", "name"), table(run("t.tsv", "id\tname\n1\tx\n"), "t").columns().stream().map(RawColumn::name).toList());
            eq(List.of("id", "name"), table(run("t.csv", "id;name\n1;x\n", opts("delimiter", "semicolon")), "t")
                    .columns().stream().map(RawColumn::name).toList());
        });

        test("dictionary: grouped by table, schema split, descriptions and types", () -> {
            RawMetadataBundle b = run("dict.csv", "Schema,Table,Column,Description,Type\n"
                    + "s,a,id,Identifier,INT\ns,a,name,\"Name, full\",TEXT\n,s.b,id,Other id,INT\n");
            eq(2, b.tables().size());
            RawTable a = table(b, "a");
            eq("s", a.schema());
            eq("Name, full", col(a, "name").description());
            eq("s", table(b, "b").schema());
            yes(b.findings().stream().noneMatch(f -> f.code().equals("TABLE_CONFLICT")), "same column name in different tables is fine");
        });

        test("dictionary: override mapping, unmapped error, skipped rows, duplicates, no table column", () -> {
            RawMetadataBundle b = run("d.csv", "Entity,Attr,Meaning\nT1,c1,desc\nT1,,missing name\nT1,c1,again\n", opts("map.column", "Attr"));
            eq(2, table(b, "T1").columns().size());
            expect(b, "DICT_ROW_SKIPPED");
            expect(b, "DICT_DUPLICATE_COLUMN");
            RawMetadataBundle bad = run("d.csv", "Foo,Bar\n1,2\n", opts("mode", "dictionary"));
            expect(bad, "DICT_COLUMN_UNMAPPED");
            eq("REJECTED", bad.sources().get(0).status());
            RawMetadataBundle terms = run("g.csv", "Field,Description\nTRADE_QTY,Quantity\n");
            eq(0, terms.tables().size());
            eq("TRADE_QTY", terms.terms().get(0).name());
        });

        test("csv syntax errors are reported with the line", () -> {
            RawMetadataBundle b = run("bad.csv", "a,b\n1,\"oops\n");
            expect(b, "CSV_SYNTAX");
            yes(b.findings().get(0).path().startsWith("bad.csv:line"), b.findings().get(0).path());
            expect(run("empty.csv", ""), "CSV_NO_HEADER");
        });
    }

    // ------------------------------------------------------------------ text and Markdown

    private static void textTests() {
        test("markdown: dictionary table uses the nearest heading, terms are candidates, lineage kept as text", () -> {
            RawMetadataBundle b = run("n.md", "# Title\n\n## s.positions\n\n| Field | Description | Type |\n|---|---|---|\n"
                    + "| position_id | Identifier \\| key | INTEGER |\n| qty | Quantity | DECIMAL |\n\n"
                    + "- TRADE_QTY: Quantity of the trade\n- **Client Identifier**: Identifies the client\n"
                    + "Note: this is just prose\nplain sentence: with a colon\n"
                    + "The v_orders view is derived from trade.orders.\nA -> B mapping\n");
            RawTable t = table(b, "positions");
            eq("s", t.schema());
            eq("Identifier | key", col(t, "position_id").description());
            eq(List.of("TRADE_QTY", "Client Identifier"), b.terms().stream().map(x -> x.name()).toList());
            eq(2L, b.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).count());
            eq(2L, b.notes().stream().filter(n -> n.kind().equals("HEADING")).count());
            expect(b, "TABLE_FROM_HEADING");
            expect(b, "TEXT_TERM_CANDIDATE");
        });

        test("markdown: unrecognised tables, code blocks and empty extraction are reported", () -> {
            RawMetadataBundle b = run("x.md", "| A | B |\n|---|---|\n| 1 | 2 |\n\n```sql\nCREATE TABLE t (a INT);\n```\n| lonely line\n");
            expect(b, "MD_TABLE_IGNORED");
            expect(b, "TEXT_CODE_BLOCKS_SKIPPED");
            expect(b, "TEXT_NOTHING_EXTRACTED");
            eq(0, b.tables().size());
            expect(run("p.txt", "Just some prose without structure.\n"), "TEXT_NOTHING_EXTRACTED");
        });

        test("markdown: ragged table rows are reported", () -> {
            expect(run("r.md", "| Field | Description |\n|---|---|\n| a | b | extra |\n| c |\n"), "MD_TABLE_RAGGED");
        });
    }

    // ------------------------------------------------------------------ SQL

    private static RawMetadataBundle sql(String text) {
        return run("t.sql", text);
    }

    private static RawMetadataBundle sql(String dialect, String text) {
        return run("t.sql", text, opts("dialect", dialect));
    }

    private static void sqlTests() {
        test("sql: CREATE TABLE columns, types, nullability, primary key, comments, locations", () -> {
            RawMetadataBundle b = sql("-- header\n\nCREATE TABLE trade.orders (\n  order_id NUMBER(10,2) NOT NULL PRIMARY KEY,\n"
                    + "  qty INTEGER DEFAULT NULL,\n  d INT DEFAULT 5 NOT NULL,\n  e INT CHECK (e IS NOT NULL),\n"
                    + "  \"Client Id\" VARCHAR(20) COMMENT 'The client',\n  ts TIMESTAMP WITH TIME ZONE,\n"
                    + "  CONSTRAINT uq UNIQUE (qty)\n) TABLESPACE users;\n");
            RawTable t = table(b, "orders");
            eq("trade", t.schema());
            eq("line 3", t.location());
            eq("NUMBER(10,2)", col(t, "order_id").dataType());
            eq(Boolean.FALSE, col(t, "order_id").nullable());
            yes(col(t, "order_id").primaryKey(), "inline primary key");
            eq(null, col(t, "qty").nullable());
            eq(Boolean.FALSE, col(t, "d").nullable());
            eq(null, col(t, "e").nullable());
            eq("The client", col(t, "Client Id").description());
            eq("TIMESTAMP WITH TIME ZONE", col(t, "ts").dataType());
            eq("line 4", col(t, "order_id").location());
            expect(b, "SQL_CONSTRAINT_IGNORED");
            expect(b, "SQL_TABLE_OPTIONS_IGNORED");
        });

        test("sql: table-level PRIMARY KEY, ALTER ADD forms, COMMENT ON", () -> {
            RawMetadataBundle b = sql("CREATE TABLE s.t (a INT, b INT, CONSTRAINT pk PRIMARY KEY (a, b));\n"
                    + "ALTER TABLE s.t ADD (c INT NULL, d INT);\n"
                    + "ALTER TABLE s.t ADD COLUMN e INT, ADD f INT;\n"
                    + "ALTER TABLE s.t ADD CONSTRAINT pk2 PRIMARY KEY (c);\n"
                    + "COMMENT ON TABLE s.t IS 'A table';\nCOMMENT ON COLUMN s.t.a IS 'First';\nCOMMENT ON COLUMN t.b IS 'Second';\n");
            RawTable t = table(b, "t");
            eq(List.of("a", "b", "c", "d", "e", "f"), t.columns().stream().map(RawColumn::name).toList());
            yes(col(t, "a").primaryKey() && col(t, "b").primaryKey() && col(t, "c").primaryKey(), "primary keys");
            eq(Boolean.TRUE, col(t, "c").nullable());
            eq("A table", t.description());
            eq("First", col(t, "a").description());
            eq("Second", col(t, "b").description());
        });

        test("sql: unsupported and unknown things are reported with lines, never guessed", () -> {
            RawMetadataBundle b = sql("CREATE TABLE s.t (a INT);\nALTER TABLE s.t DROP COLUMN a;\nINSERT INTO s.t VALUES (1);\n"
                    + "DROP TABLE s.t;\nFROBNICATE s.t;\nALTER TABLE s.other ADD (x INT);\nCOMMENT ON COLUMN s.t.zzz IS 'x';\n"
                    + "GRANT SELECT ON s.t TO bob;\nCREATE INDEX i ON s.t(a);\nCREATE TABLE s.c AS SELECT * FROM s.t;\n");
            List<String> unsupported = b.findings().stream().filter(f -> f.code().equals("SQL_UNSUPPORTED")).map(Finding::path).toList();
            eq(List.of("t.sql:line 2", "t.sql:line 3", "t.sql:line 4", "t.sql:line 5", "t.sql:line 10"), unsupported);
            expect(b, "SQL_UNKNOWN_TARGET");
            expect(b, "SQL_SKIPPED");
            eq(0, table(b, "c").columns().size());
            eq(1, table(b, "t").columns().size());
            eq("INGESTED", b.sources().get(0).status());
        });

        test("sql: views - explicit columns, simple lineage, joins, star and expressions", () -> {
            RawMetadataBundle b = sql("CREATE VIEW v AS SELECT a, b AS bee FROM t;\nCREATE VIEW v2 (x, y) AS SELECT a, b FROM t;\n"
                    + "CREATE VIEW v3 AS SELECT * FROM t;\nCREATE VIEW v4 AS SELECT t.a, u.b FROM t JOIN u ON t.id = u.id;\n"
                    + "CREATE VIEW v5 AS SELECT UPPER(a) AS ua FROM t;\nCREATE VIEW v6 AS SELECT z.a FROM t x;\n");
            eq(List.of("a", "bee"), table(b, "v").columns().stream().map(RawColumn::name).toList());
            eq(List.of("x", "y"), table(b, "v2").columns().stream().map(RawColumn::name).toList());
            eq(0, table(b, "v3").columns().size());
            eq(List.of("a", "b"), table(b, "v4").columns().stream().map(RawColumn::name).toList());
            eq(List.of("v.a <- t.a", "v.bee <- t.b", "v2.x <- t.a", "v2.y <- t.b"),
                    b.lineage().stream().map(l -> l.target() + " <- " + l.source()).toList());
            expect(b, "SQL_VIEW_COLUMNS_UNKNOWN");
            expect(b, "SQL_LINEAGE_NOT_INFERRED");
            eq("VIEW", table(b, "v").kind());
        });

        test("sql: dialect samples (SQL Server, Sybase, Oracle, Teradata, Snowflake, DB2, PostgreSQL)", () -> {
            RawMetadataBundle ss = sql("sqlserver", "CREATE TABLE [dbo].[Orders] ([Id] INT IDENTITY(1,1) NOT NULL PRIMARY KEY, [Name] NVARCHAR(50) NULL)\nGO\n"
                    + "CREATE TABLE dbo.Two (a INT)\nGO\n");
            eq(List.of("Orders", "Two"), ss.tables().stream().map(RawTable::name).toList());
            yes(col(table(ss, "Orders"), "Id").primaryKey(), "identity column with primary key");
            eq(Boolean.FALSE, col(table(ss, "Orders"), "Id").nullable());
            eq(List.of("Orders"), sql("CREATE TABLE [dbo].[Orders] ([Id] INT)\n").tables().stream().map(RawTable::name).toList());
            eq(2, sql("sybase", "CREATE TABLE dbo.a (id int NOT NULL)\ngo\nCREATE TABLE dbo.b (id int NULL)\ngo\n").tables().size());
            eq(2, sql("oracle", "CREATE TABLE t1 (a NUMBER)\n/\nCREATE TABLE t2 (b NUMBER)\n/\n").tables().size());
            RawTable td = table(sql("teradata", "CREATE MULTISET TABLE db1.t, NO FALLBACK (a INTEGER NOT NULL, b VARCHAR(10) CHARACTER SET LATIN) PRIMARY INDEX (a);"), "t");
            eq("db1", td.schema());
            eq(2, td.columns().size());
            RawTable sf = table(sql("snowflake", "CREATE OR REPLACE TRANSIENT TABLE analytics.public.events (id NUMBER(38,0) COMMENT 'Event id', ts TIMESTAMP_NTZ);"), "events");
            eq("analytics", sf.database());
            eq("public", sf.schema());
            eq("Event id", col(sf, "id").description());
            RawTable db2 = table(sql("db2", "CREATE TABLE sales.items (item_id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY, name VARCHAR(50)) IN tbsp;"), "items");
            eq("INTEGER", col(db2, "item_id").dataType());
            RawMetadataBundle pg = sql("postgresql", "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;\nCREATE TABLE public.t (a int[], b text);\n");
            eq(1, pg.tables().size());
            eq("int[]", col(table(pg, "t"), "a").dataType());
            expect(pg, "SQL_SKIPPED");
        });

        test("sql: syntax problems are contained to the statement or file", () -> {
            RawMetadataBundle b = sql("CREATE TABLE a.b.c.d (x INT);\nCREATE TABLE ok (x INT);\nCREATE TABLE dup (x INT, X INT);\n");
            expect(b, "SQL_PARSE_FAILED");
            expect(b, "SQL_DUPLICATE_COLUMN");
            eq(List.of("ok", "dup"), b.tables().stream().map(RawTable::name).toList());
            RawMetadataBundle bad = sql("CREATE TABLE t (a VARCHAR(5) DEFAULT 'oops);\n");
            expect(bad, "SQL_SYNTAX");
            eq("REJECTED", bad.sources().get(0).status());
            eq(0, bad.tables().size());
            expect(sql("/* never closed\nCREATE TABLE t (a INT);"), "SQL_SYNTAX");
            expect(sql("SELECT 1;"), "SQL_NOTHING_EXTRACTED");
        });

        test("sql: the sample file", () -> {
            RawMetadataBundle b = sql(resource("/static/samples/sample-schema.sql"));
            RawTable t = table(b, "orders");
            eq(5, t.columns().size());
            eq("Customer trade orders", t.description());
            yes(col(t, "order_id").primaryKey(), "pk from table constraint");
            eq(2, b.lineage().size());
        });
    }

    // ------------------------------------------------------------------ service

    private static void serviceTests() {
        test("service: unavailable formats are errors with an alternative, never skipped", () -> {
            for (String name : List.of("a.xlsx", "a.docx", "a.pdf", "a.html", "a.json", "a.yaml")) {
                RawMetadataBundle b = run(name, "x");
                expect(b, "FORMAT_UNAVAILABLE");
                eq("UNAVAILABLE", b.sources().get(0).status());
                yes(b.findings().get(0).suggestion().length() > 10, "alternative is given for " + name);
            }
            contains(run("a.pdf", "x").findings().get(0).message(), "Apache PDFBox");
            expect(run("a.unknown", "x"), "FORMAT_UNKNOWN");
            expect(run("noextension", "x"), "FORMAT_UNKNOWN");
        });

        test("service: unreadable, oversize and empty inputs", () -> {
            SourceIngestionService s = SourceIngestionService.standard();
            RawMetadataBundle b = s.ingest(List.of(SourceInput.failed("a.csv", "boom")), IngestOptions.defaults());
            expect(b, "SOURCE_UNREADABLE");
            expect(s.ingest(List.of(SourceInput.of("a.csv", "x".repeat(2000))), opts("maxSourceChars", "1000")), "SOURCE_TOO_LARGE");
            expect(s.ingest(List.of(), IngestOptions.defaults()), "NO_SOURCES");
        });

        test("service: same table in two sources is a conflict, not a merge; same column names stay separate", () -> {
            SourceIngestionService s = SourceIngestionService.standard();
            RawMetadataBundle b = s.ingest(List.of(SourceInput.of("one.sql", "CREATE TABLE s.t (id INT);"),
                    SourceInput.of("two.sql", "CREATE TABLE s.t (id INT, extra INT);"),
                    SourceInput.of("three.sql", "CREATE TABLE s.u (id INT);")), IngestOptions.defaults());
            expect(b, "TABLE_CONFLICT");
            eq(3, b.tables().size());
            eq(1L, b.findings().stream().filter(f -> f.code().equals("TABLE_CONFLICT")).count());
        });

        test("options: unknown keys and bad values are rejected", () -> {
            throwsA(IllegalArgumentException.class, () -> opts("bogus", "1"));
            throwsA(IllegalArgumentException.class, () -> opts("dialect", "mysql"));
            throwsA(IllegalArgumentException.class, () -> opts("maxRows", "0"));
            throwsA(IllegalArgumentException.class, () -> opts("delimiter", "\""));
            throwsA(IllegalArgumentException.class, () -> opts("map.bogus", "x"));
            eq('\t', opts("delimiter", "tab").delimiter());
        });
    }

    // ------------------------------------------------------------------ draft

    private static void draftTests() {
        test("draft: tables and columns only; views, terms, PII and attestations left for review", () -> {
            RawMetadataBundle b = SourceIngestionService.standard().ingest(List.of(
                    SourceInput.of("schema.sql", resource("/static/samples/sample-schema.sql")),
                    SourceInput.of("notes.md", resource("/static/samples/sample-notes.md"))), IngestOptions.defaults());
            DraftBuilder.Result r = DraftBuilder.build(b, new DraftBuilder.SystemContext("TRADE_DB", null, null));
            yes(r.manifest() != null, r.findings().toString());
            var db = r.manifest().databases().get(0);
            eq("TRADE_DB", db.logicalName());
            eq(1, db.schemas().size());
            eq(List.of("orders", "positions"), db.schemas().get(0).tables().stream().map(t -> t.physicalName()).toList());
            yes(r.manifest().logicalServices() == null && r.manifest().systemDataElements() == null, "nothing invented");
            var c = db.schemas().get(0).tables().get(0).columns().get(0);
            yes(c.sensitiveDataAttestation() == null && c.piiElements() == null, "no attestation or PII");
            yes(r.findings().stream().anyMatch(f -> f.code().equals("DRAFT_VIEW_EXCLUDED")), "views excluded");
            yes(r.findings().stream().anyMatch(f -> f.code().equals("DRAFT_TERMS_NOT_INCLUDED")), "terms excluded");
            eq(1, r.unresolved().size());
        });

        test("draft: required context is demanded, not invented", () -> {
            RawMetadataBundle csv = run("orders.csv", "id,name\n1,x\n");
            DraftBuilder.Result noSchema = DraftBuilder.build(csv, new DraftBuilder.SystemContext("DB", null, ""));
            yes(noSchema.manifest() == null && noSchema.findings().get(0).code().equals("DRAFT_SCHEMA_REQUIRED"), noSchema.findings().toString());
            DraftBuilder.Result noName = DraftBuilder.build(csv, new DraftBuilder.SystemContext(" ", null, "s"));
            eq("DRAFT_DB_NAME_REQUIRED", noName.findings().get(0).code());
            DraftBuilder.Result ok = DraftBuilder.build(csv, new DraftBuilder.SystemContext("DB", null, "s"));
            eq("s", ok.manifest().databases().get(0).schemas().get(0).physicalName());
            eq("DRAFT_NO_TABLES", DraftBuilder.build(run("g.csv", "Field,Description\na,b\n"),
                    new DraftBuilder.SystemContext("DB", null, "s")).findings().get(0).code());
        });

        test("draft: request parsing validates input", () -> {
            Object ok = Json.parse("{\"sources\":[{\"name\":\"a.csv\",\"text\":\"a\\n1\"}],\"options\":{\"maxRows\":5,\"hasHeader\":true},"
                    + "\"system\":{\"databaseLogicalName\":\"D\",\"databaseType\":\"Oracle\"}}");
            IngestRequest r = IngestRequest.fromTree(ok);
            eq(5, r.options().maxRows());
            eq("Oracle", r.system().databaseType().label());
            throwsA(IllegalArgumentException.class, () -> IngestRequest.fromTree(Json.parse("{}")));
            throwsA(IllegalArgumentException.class, () -> IngestRequest.fromTree(Json.parse("{\"sources\":[{\"name\":\"\",\"text\":\"\"}]}")));
            throwsA(IllegalArgumentException.class, () -> IngestRequest.fromTree(Json.parse(
                    "{\"sources\":[],\"system\":{\"databaseType\":\"Mongo\"}}")));
            throwsA(IllegalArgumentException.class, () -> IngestRequest.fromTree(Json.parse("{\"sources\":[],\"options\":{\"x\":[1]}}")));
        });
    }

    // ------------------------------------------------------------------ input files

    private static void fileTests() {
        test("input files: ordering, hidden files, unknown types, invalid UTF-8, symlinks and root boundary", () -> {
            java.nio.file.Path dir = tempDir("casc files ");
            java.nio.file.Path outside = tempDir("casc outside ");
            try {
                java.nio.file.Path sub = java.nio.file.Files.createDirectory(dir.resolve("sub"));
                java.nio.file.Files.writeString(dir.resolve("b.sql"), "CREATE TABLE t (a INT);");
                java.nio.file.Files.writeString(dir.resolve("a.csv"), "x\n1\n");
                java.nio.file.Files.writeString(sub.resolve("c.md"), "# h\n");
                java.nio.file.Files.writeString(dir.resolve(".hidden.sql"), "CREATE TABLE h (a INT);");
                java.nio.file.Files.writeString(dir.resolve("notes.bin"), "x");
                java.nio.file.Files.write(dir.resolve("d.xlsx"), new byte[] {1, 2});
                java.nio.file.Files.write(dir.resolve("bad.txt"), new byte[] {(byte) 0xC3, 0x28});
                java.nio.file.Files.writeString(outside.resolve("secret.sql"), "CREATE TABLE secret (a INT);");
                boolean links = true;
                try {
                    java.nio.file.Files.createSymbolicLink(dir.resolve("link.sql"), outside.resolve("secret.sql"));
                } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
                    links = false;
                }
                Set<String> known = SourceIngestionService.standard().knownExtensions();
                InputFiles.Result r = InputFiles.collect(List.of(dir.toString()), null, known);
                eq(List.of("a.csv", "b.sql", "bad.txt", "d.xlsx", "sub/c.md"), r.inputs().stream().map(SourceInput::name).toList());
                yes(r.inputs().get(2).readError() != null, "invalid UTF-8 is unreadable, not replaced");
                yes(r.notes().stream().anyMatch(n -> n.contains("notes.bin")), "unknown type noted");
                if (links) {
                    yes(r.notes().stream().anyMatch(n -> n.contains("symbolic link") && n.contains("link.sql")), "symlink skipped and reported");
                }
                InputFiles.Result bounded = InputFiles.collect(List.of(outside.resolve("secret.sql").toString()), dir, known);
                contains(bounded.inputs().get(0).readError(), "outside the allowed input root");
                if (links) {
                    InputFiles.Result viaLink = InputFiles.collect(List.of(dir.resolve("link.sql").toString()), dir, known);
                    contains(viaLink.inputs().get(0).readError(), "outside the allowed input root");
                }
                InputFiles.Result inside = InputFiles.collect(List.of(dir.resolve("b.sql").toString()), dir, known);
                eq(null, inside.inputs().get(0).readError());
                contains(InputFiles.collect(List.of(dir.resolve("missing.sql").toString()), null, known).inputs().get(0).readError(), "Not found");
            } finally {
                deleteTree(dir);
                deleteTree(outside);
            }
        });
    }
}

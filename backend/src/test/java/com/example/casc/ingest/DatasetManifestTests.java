package com.example.casc.ingest;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import com.example.casc.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class DatasetManifestTests {
    private DatasetManifestTests() {}

    /** Valid JSON. */
    static final String STRICT = """
            {"databases":[{"logicalName":"TBD","schemas":[{"physicalName":"a2oltp","tables":[
              {"physicalName":"CanadaReportingJurisdiction","columns":[{"physicalName":"creationTS","systemDataElement":"Creation Timestamp"}]},
              {"physicalName":"TAPSAccount","columns":[
                {"physicalName":"accountID","systemDataElement":"Account ID"},
                {"physicalName":"AccountUUID","systemDataElement":{"name":"Account UUID"}},
                {"physicalName":"noElement"}]}
            ]}]}]}
            """;

    /** Like hand-edited manifests: trailing commas, a missing comma and broken JSON after the databases section. */
    static final String LOOSE = """
            {
              "systemDataElements": [ { "name": "x", "boundaries": [ {} ] }, ],
              "databases": [
              { "logicalName": "TBD",
                "schemas": [ { "physicalName": "a2oltp", "tables": [
                  { "physicalName": "TAPSAccount", "columns": [
                    { "physicalName": "accountID", "systemDataElement": "Account ID" },
                    { "physicalName": "AccountUUID", "systemDataElement": "Account UUID" },
                  ] }
                ] } ]
              } ],
              "datasets": [ { "boundaries": [ "frontToBack": ["x"] ] }
            }
            """;

    static final String CSV = "table name, domain, boundary, upstream dataset, producer eonid\r\n"
            + "TAPSAccount, Account (Operations DGC Glossary), Firmwide - *All Product Types,a2.dbo.TAPSAccount,3212\r\n"
            + "TAPSAccount1, Account (Operations DGC Glossary), Firmwide - *All Product Types,a2.dbo.TAPSAccount,3212\r\n";

    static final String SQL = """
            CREATE TABLE a2oltp.TAPSAccount (
              accountID INT NOT NULL,
              AccountUUID VARCHAR(36)
            );
            COMMENT ON COLUMN a2oltp.TAPSAccount.accountID IS 'Account identifier';
            CREATE TABLE Plain (id INT);
            CREATE VIEW a2oltp.TAPSView AS SELECT accountID FROM a2oltp.TAPSAccount;
            """;

    @SuppressWarnings("unchecked")
    static Map<String, Object> dataset(DatasetManifestIngestion.Generated g) throws Exception {
        Map<String, Object> root = (Map<String, Object>) Json.parse(g.content());
        return (Map<String, Object>) ((List<Object>) root.get("datasets")).get(0);
    }

    static boolean has2(DatasetManifestCombiner.Result r, String code) {
        return r.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    static boolean has(DatasetManifestIngestion.Result r, String code) {
        return r.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    public static void register() {
        test("datasets: one manifest per table that has metadata, built from the metadata row", () -> {
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", CSV));
            eq(1, r.manifests().size());
            var g = r.manifests().get(0);
            eq("manifest_TAPSAccount.json", g.fileName());
            Map<String, Object> d = dataset(g);
            eq("a2.dbo.TAPSAccount", d.get("name"));
            eq("ResultSet", d.get("format"));
            eq("FDC", d.get("originatingCatalogingSource"));
            eq("{\"Operations DGC Glossary\":[{\"domainName\":\"Account\"}]}", Json.writeCompact(d.get("dgcDataDomains")));
            eq("[{\"product\":[\"Firmwide - *All Product Types\"]}]", Json.writeCompact(d.get("boundaries")));
            eq("[{\"name\":\"a2.dbo.TAPSAccount\",\"producerEonId\":3212}]", Json.writeCompact(d.get("upstreamDatasets")));
            eq("[{\"name\":\"accountID\",\"description\":\"Account ID\",\"systemDataElements\":[\"Account ID\"]},"
                    + "{\"name\":\"AccountUUID\",\"description\":\"Account UUID\",\"systemDataElements\":[\"Account UUID\"]},{\"name\":\"noElement\"}]",
                    Json.writeCompact(d.get("datasetElements")));
        });
        test("datasets: a metadata row for a table that is not in the manifest is a warning, a table without a row is skipped", () -> {
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", CSV));
            yes(has(r, "METADATA_TABLE_NOT_IN_MANIFEST"), "TAPSAccount1 is not in the manifest");
            yes(has(r, "TABLE_WITHOUT_METADATA"), "CanadaReportingJurisdiction has no row");
            yes(!r.hasErrors(), "warnings only");
        });
        test("datasets: a manifest with trailing or missing commas is read leniently with a warning", () -> {
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", LOOSE), SourceInput.of("t.csv", CSV));
            yes(has(r, "MANIFEST_LENIENT_READ"), "lenient warning");
            eq(1, r.manifests().size());
            eq(2, ((List<?>) dataset(r.manifests().get(0)).get("datasetElements")).size());
        });
        test("datasets: missing metadata columns and an empty manifest are errors", () -> {
            var bad = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", "table name,domain\nTAPSAccount,Account\n"));
            yes(has(bad, "METADATA_COLUMN_MISSING") && bad.hasErrors() && bad.manifests().isEmpty(), "needs all five columns");
            var none = DatasetManifestIngestion.generate(SourceInput.of("m.json", "{\"a\":1}"), SourceInput.of("t.csv", CSV));
            yes(has(none, "MANIFEST_NO_DATABASES") && none.hasErrors(), "no databases");
            var junk = DatasetManifestIngestion.generate(SourceInput.of("m.json", "not json"), SourceInput.of("t.csv", CSV));
            yes(has(junk, "MANIFEST_SYNTAX") && junk.hasErrors(), "unreadable manifest");
        });
        test("datasets: a domain without a glossary uses the default glossary; a non-numeric EON ID is kept as text with a warning", () -> {
            String csv = "table name,domain,boundary,upstream dataset,producer eonid\nTAPSAccount,Account,Firmwide,a2.dbo.TAPSAccount,abc\n";
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", csv));
            Map<String, Object> d = dataset(r.manifests().get(0));
            eq("{\"Operations DGC Glossary\":[{\"domainName\":\"Account\"}]}", Json.writeCompact(d.get("dgcDataDomains")));
            eq("[{\"name\":\"a2.dbo.TAPSAccount\",\"producerEonId\":\"abc\"}]", Json.writeCompact(d.get("upstreamDatasets")));
            yes(has(r, "METADATA_EON_NOT_NUMERIC"), "warning");
        });
        test("datasets: tables can come from SQL DDL; descriptions from comments, no system data elements, views skipped", () -> {
            String csv = "table name,domain,boundary,upstream dataset,producer eonid\nTAPSAccount,Account (Operations DGC Glossary),Firmwide - *All Product Types,a2.dbo.TAPSAccount,3212\n"
                    + "Plain,Account,Firmwide,a2.dbo.Plain,3212\nTAPSView,Account,Firmwide,a2.dbo.V,3212\n";
            var r = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("schema.sql", SQL)), SourceInput.of("t.csv", csv),
                    IngestOptions.defaults(), null);
            yes(!r.hasErrors(), r.findings().toString());
            eq(2, r.manifests().size());
            Map<String, Object> d = dataset(r.manifests().get(0));
            eq("a2.dbo.TAPSAccount", d.get("name"));
            eq("[{\"name\":\"accountID\",\"description\":\"Account identifier\"},{\"name\":\"AccountUUID\"}]", Json.writeCompact(d.get("datasetElements")));
            yes(!r.manifests().get(0).content().contains("systemDataElements"), "SQL has no system data elements");
            eq("a2.dbo.Plain", dataset(r.manifests().get(1)).get("name"));
            yes(has(r, "DATASET_VIEW_SKIPPED"), "view skipped");
            yes(has(r, "METADATA_TABLE_NOT_IN_MANIFEST"), "TAPSView is a view, not a table");
            var withDefault = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("schema.sql", SQL)), SourceInput.of("t.csv", csv),
                    IngestOptions.defaults(), "a2oltp");
            eq("a2.dbo.Plain", dataset(withDefault.manifests().get(1)).get("name"));
        });
        test("datasets: external column metadata supplies systemDataElements for SQL tables and overrides a manifest's own", () -> {
            String meta = "table name,domain,boundary,upstream dataset,producer eonid\nTAPSAccount,Account,Firmwide,a2.dbo.TAPSAccount,3212\n";
            String cols = "table name,column name,system data element\na2oltp.TAPSAccount,ACCOUNTID,Account ID\nTAPSAccount,AccountUUID,Account UUID\n"
                    + "TAPSAccount,nope,X\nOther,id,Y\nTAPSAccount,accountid,Duplicate\nTAPSAccount,,Z\n";
            var sql = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("schema.sql", SQL)), SourceInput.of("t.csv", meta),
                    IngestOptions.defaults(), null, SourceInput.of("c.csv", cols));
            yes(!sql.hasErrors(), sql.findings().toString());
            eq("[{\"name\":\"accountID\",\"description\":\"Account identifier\",\"systemDataElements\":[\"Account ID\"]},"
                    + "{\"name\":\"AccountUUID\",\"description\":\"Account UUID\",\"systemDataElements\":[\"Account UUID\"]}]",
                    Json.writeCompact(dataset(sql.manifests().get(0)).get("datasetElements")));
            yes(has(sql, "COLUMN_METADATA_NOT_FOUND") && has(sql, "COLUMN_METADATA_DUPLICATE") && has(sql, "COLUMN_METADATA_ROW_SKIPPED"), "bad rows are reported");
            var man = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", meta),
                    SourceInput.of("c.csv", "table name,column name,system data element\nTAPSAccount,accountID,Overridden\n"));
            String first = Json.writeCompact(dataset(man.manifests().get(0)).get("datasetElements"));
            contains(first, "\"name\":\"accountID\",\"description\":\"Overridden\",\"systemDataElements\":[\"Overridden\"]");
            contains(first, "\"name\":\"AccountUUID\",\"description\":\"Account UUID\",\"systemDataElements\":[\"Account UUID\"]");
            var bad = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("schema.sql", SQL)), SourceInput.of("t.csv", meta),
                    IngestOptions.defaults(), null, SourceInput.of("c.csv", "table name,column name\nTAPSAccount,accountID\n"));
            yes(has(bad, "COLUMN_METADATA_COLUMN_MISSING") && bad.hasErrors() && bad.manifests().isEmpty(), "needs all three columns");
        });
        test("datasets: all tables of a large manifest are read; only tables with a metadata row are generated, and the summary says so", () -> {
            StringBuilder m = new StringBuilder("{\"databases\":[{\"schemas\":[{\"physicalName\":\"s\",\"tables\":[");
            StringBuilder c = new StringBuilder("table name,domain,boundary,upstream dataset,producer eonid\n");
            for (int i = 0; i < 694; i++) {
                m.append(i > 0 ? "," : "").append("{\"physicalName\":\"T").append(i).append("\",\"columns\":[{\"physicalName\":\"c\"}]}");
                if (i < 91) {
                    c.append("T").append(i).append(",Account,Firmwide,a2.dbo.T").append(i).append(",3212\n");
                }
            }
            m.append(",{\"columns\":[]}]}]}]}");
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", m.toString()), SourceInput.of("t.csv", c.toString()));
            eq(91, r.manifests().size());
            yes(r.findings().stream().anyMatch(f -> f.code().equals("DATASET_SUMMARY") && f.message().contains("Read 694 table(s)") && f.message().contains("has 91 row(s)")), "summary");
            yes(has(r, "TABLE_WITHOUT_METADATA") && has(r, "MANIFEST_TABLE_SKIPPED"), "skipped tables are reported");
            var all = new StringBuilder("table name,domain,boundary,upstream dataset,producer eonid\n");
            for (int i = 0; i < 694; i++) {
                all.append("T").append(i).append(",Account,Firmwide,a2.dbo.T").append(i).append(",3212\n");
            }
            eq(694, DatasetManifestIngestion.generate(SourceInput.of("m.json", m.toString()), SourceInput.of("t.csv", all.toString())).manifests().size());
        });
        test("datasets: without upstreams no upstreamDatasets are written and the upstream columns are optional", () -> {
            String short3 = "table name,domain,boundary\nTAPSAccount,Account,Firmwide - *All Product Types\n";
            var strict = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", short3), null, true);
            yes(has(strict, "METADATA_COLUMN_MISSING") && strict.hasErrors(), "upstream columns are required by default");
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", short3), null, false);
            yes(!r.hasErrors(), r.findings().toString());
            eq(1, r.manifests().size());
            Map<String, Object> d = dataset(r.manifests().get(0));
            yes(!d.containsKey("upstreamDatasets"), "no upstreamDatasets");
            eq("[{\"product\":[\"Firmwide - *All Product Types\"]}]", Json.writeCompact(d.get("boundaries")));
            var full = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", CSV), null, false);
            yes(!dataset(full.manifests().get(0)).containsKey("upstreamDatasets"), "also omitted when the CSV has upstream columns");
            yes(!has(full, "METADATA_VALUE_MISSING"), "no upstream warnings");
        });
        test("combine: all datasets, the first N, or the named tables go into one manifest; duplicates and bad input are reported", () -> {
            StringBuilder csv = new StringBuilder("table name,domain,boundary\n");
            StringBuilder m = new StringBuilder("{\"databases\":[{\"schemas\":[{\"physicalName\":\"s\",\"tables\":[");
            for (int i = 0; i < 5; i++) {
                csv.append("T").append(i).append(",Account,Firmwide\n");
                m.append(i > 0 ? "," : "").append("{\"physicalName\":\"T").append(i).append("\",\"columns\":[{\"physicalName\":\"c\"}]}");
            }
            m.append("]}]}]}");
            var gen = DatasetManifestIngestion.generate(SourceInput.of("m.json", m.toString()), SourceInput.of("t.csv", csv.toString()), null, false);
            List<SourceInput> parts = new java.util.ArrayList<>();
            for (var g : gen.manifests()) {
                parts.add(SourceInput.of(g.fileName(), g.content()));
            }
            var all = DatasetManifestCombiner.combine(parts, DatasetManifestCombiner.Selection.all());
            eq(5, all.datasets());
            eq(5, ((List<?>) ((Map<?, ?>) Json.parse(all.content())).get("datasets")).size());
            var two = DatasetManifestCombiner.combine(parts, new DatasetManifestCombiner.Selection(2, null));
            eq(2, two.datasets());
            contains(two.content(), "a2.dbo.T0");
            contains(two.content(), "a2.dbo.T1");
            yes(!two.content().contains("a2.dbo.T2"), "only the first two");
            var named = DatasetManifestCombiner.combine(parts, new DatasetManifestCombiner.Selection(null, List.of("t3", "dbo.T1", "Nope")));
            eq(2, named.datasets());
            yes(has2(named, "COMBINE_TABLE_NOT_FOUND"), "unknown table reported");
            var dup = new java.util.ArrayList<>(parts);
            dup.add(parts.get(0));
            dup.add(SourceInput.of("other.json", "{\"a\":1}"));
            var withDup = DatasetManifestCombiner.combine(dup, DatasetManifestCombiner.Selection.all());
            eq(5, withDup.datasets());
            yes(has2(withDup, "COMBINE_DUPLICATE_DATASET") && has2(withDup, "COMBINE_FILE_SKIPPED"), "duplicate and non-dataset file reported");
            var bad = DatasetManifestCombiner.combine(List.of(SourceInput.of("x.json", "not json")), DatasetManifestCombiner.Selection.all());
            yes(bad.hasErrors() && bad.content() == null, "unreadable input is an error");
        });
        test("combine: the CLI combines a folder of manifests (all, --count, --tables) and ingest can combine while generating", () -> {
            Path dir = tempDir("casc-combine");
            try {
                Files.writeString(dir.resolve("s.sql"), "CREATE TABLE s.A (x INT); CREATE TABLE s.B (x INT); CREATE TABLE s.C (x INT);");
                Files.writeString(dir.resolve("t.csv"), "table name,domain,boundary\nA,Account,F\nB,Account,F\nC,Account,F\n");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                Path out = dir.resolve("out");
                eq(0, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", out.toString(), "--no-upstream"}, InputStream.nullInputStream(), po, po, k -> null));
                Path combined = dir.resolve("combined.json");
                eq(0, Cli.run(new String[] {"combine", "--input", out.toString(), "--output", combined.toString()}, InputStream.nullInputStream(), po, po, k -> null));
                eq(3, ((List<?>) ((Map<?, ?>) Json.parse(Files.readString(combined))).get("datasets")).size());
                eq(3, Cli.run(new String[] {"combine", "--input", out.toString(), "--output", combined.toString()}, InputStream.nullInputStream(), po, po, k -> null));
                eq(0, Cli.run(new String[] {"combine", "--input", out.toString(), "--output", combined.toString(), "--count", "2", "--overwrite"},
                        InputStream.nullInputStream(), po, po, k -> null));
                eq(2, ((List<?>) ((Map<?, ?>) Json.parse(Files.readString(combined))).get("datasets")).size());
                eq(0, Cli.run(new String[] {"combine", "--input", out.toString(), "--output", combined.toString(), "--tables", "c", "--overwrite"},
                        InputStream.nullInputStream(), po, po, k -> null));
                contains(Files.readString(combined), "a2.dbo.C");
                eq(2, Cli.run(new String[] {"combine", "--input", out.toString(), "--output", combined.toString(), "--count", "2", "--tables", "a", "--overwrite"},
                        InputStream.nullInputStream(), po, po, k -> null));
                Path one = dir.resolve("one");
                Path all = dir.resolve("all.json");
                eq(0, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", one.toString(), "--no-upstream", "--combine-output", all.toString(), "--combined-only"},
                        InputStream.nullInputStream(), po, po, k -> null));
                eq(3, ((List<?>) ((Map<?, ?>) Json.parse(Files.readString(all))).get("datasets")).size());
                yes(!Files.exists(one.resolve("manifest_A.json")), "--combined-only skips the per-table files");
                eq(2, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", one.toString(), "--combine-count", "1"}, InputStream.nullInputStream(), po, po, k -> null));
            } finally {
                deleteTree(dir);
            }
        });
        test("datasets: every dataset is named a2.dbo.tableName, whatever the source says; --database-name and --schema-name change the parts", () -> {
            String meta = "table name,domain,boundary\nTAPSAccount,Account,Firmwide\n";
            // manifest: the source database (logicalName TBD) and schema (a2oltp) do not appear in the name
            var m = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", meta), null, false, null);
            eq("a2.dbo.TAPSAccount", dataset(m.manifests().get(0)).get("name"));
            var over = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", meta), null, false, "TRADE_DB", "sales", null);
            eq("TRADE_DB.sales.TAPSAccount", dataset(over.manifests().get(0)).get("name"));
            var dbOnly = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", meta), null, false, "TRADE_DB", null);
            eq("TRADE_DB.dbo.TAPSAccount", dataset(dbOnly.manifests().get(0)).get("name"));
            // SQL: a three-part name, a two-part name and an unqualified name all give a2.dbo.table
            String sql = "CREATE TABLE mydb.sch.Three (a INT); CREATE TABLE sch.Two (a INT); CREATE TABLE One (a INT);";
            var all = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("s.sql", sql)), SourceInput.of("t.csv", "table name,domain,boundary\nThree,A,F\nTwo,A,F\nOne,A,F\n"),
                    IngestOptions.defaults(), null, null, false, null, null, null);
            yes(!all.hasErrors(), all.findings().toString());
            eq(3, all.manifests().size());
            for (int i = 0; i < 3; i++) {
                yes(String.valueOf(dataset(all.manifests().get(i)).get("name")).startsWith("a2.dbo."), "a2.dbo prefix");
            }
            yes(!has(all, "DATASET_SCHEMA_MISSING") && !has(all, "DATASET_DATABASE_MISSING"), "no missing database or schema warnings any more");
            // the same table name in two schemas gives the same dataset name: warn, and keep the files apart
            String two = "CREATE TABLE s1.Dup (a INT); CREATE TABLE s2.Dup (a INT);";
            var dup = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("s.sql", two)), SourceInput.of("t.csv", "table name,domain,boundary\nDup,A,F\n"),
                    IngestOptions.defaults(), null, null, false, null, null, null);
            eq(2, dup.manifests().size());
            yes(has(dup, "DATASET_NAME_DUPLICATE"), dup.findings().toString());
            yes(!dup.manifests().get(0).fileName().equals(dup.manifests().get(1).fileName()), "distinct file names");
            // combine --tables still finds a table by table name, schema.table or database.schema.table
            for (String w : List.of("TAPSAccount", "dbo.TAPSAccount", "TRADE_DB.dbo.TAPSAccount")) {
                var c = DatasetManifestCombiner.combine(List.of(SourceInput.of("x.json", dbOnly.manifests().get(0).content())), new DatasetManifestCombiner.Selection(null, List.of(w)));
                eq(1, c.datasets());
            }
        });
        test("table list: only the listed tables are generated, with defaults from --domain/--boundary or from a metadata row", () -> {
            var sel = new DatasetManifestIngestion.TableSelection(SourceInput.of("tables.csv", "table name\nTAPSAccount\nNope\nTAPSAccount\n"), "Account (Operations DGC Glossary)", "Firmwide");
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), null, null, false, null, sel);
            yes(!r.hasErrors(), r.findings().toString());
            eq(1, r.manifests().size());
            Map<String, Object> d = dataset(r.manifests().get(0));
            eq("a2.dbo.TAPSAccount", d.get("name"));
            eq("{\"Operations DGC Glossary\":[{\"domainName\":\"Account\"}]}", Json.writeCompact(d.get("dgcDataDomains")));
            eq("[{\"product\":[\"Firmwide\"]}]", Json.writeCompact(d.get("boundaries")));
            yes(!d.containsKey("upstreamDatasets"), "no upstream");
            yes(has(r, "TABLE_LIST_NOT_IN_SOURCE") && has(r, "TABLE_LIST_DUPLICATE") && has(r, "TABLE_LIST_DEFAULTS_USED") && has(r, "TABLE_WITHOUT_METADATA"), r.findings().toString());
            // a metadata row for a listed table wins over the defaults; rows of tables that are not listed are ignored
            String meta = "table name,domain,boundary,upstream dataset,producer eonid\nTAPSAccount,Party,Own boundary,a2.dbo.T,3212\nCanadaReportingJurisdiction,Party,X,a2.dbo.C,3212\n";
            var both = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("meta.csv", meta), null, true, null,
                    new DatasetManifestIngestion.TableSelection(SourceInput.of("tables.csv", "TAPSAccount\n"), "Account", "Firmwide"));
            eq(1, both.manifests().size());
            Map<String, Object> b = dataset(both.manifests().get(0));
            eq("[{\"product\":[\"Own boundary\"]}]", Json.writeCompact(b.get("boundaries")));
            eq("[{\"name\":\"a2.dbo.T\",\"producerEonId\":3212}]", Json.writeCompact(b.get("upstreamDatasets")));
            // without metadata or defaults the missing values are reported
            var bare = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), null, null, false, null,
                    new DatasetManifestIngestion.TableSelection(SourceInput.of("tables.csv", "TAPSAccount\n"), null, null));
            eq(1, bare.manifests().size());
            yes(has(bare, "METADATA_VALUE_MISSING"), "no domain or boundary");
        });
        test("table list: .txt, one-column CSV and qualified names; wrong qualifier or header is reported", () -> {
            var sel = new DatasetManifestIngestion.TableSelection(SourceInput.of("t.txt", "# my tables\n\na2oltp.TAPSAccount\nTBD.a2oltp.CanadaReportingJurisdiction\nother.TAPSAccount\n"), "Account", "F");
            var r = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), null, null, false, null, sel);
            eq(2, r.manifests().size());
            yes(has(r, "TABLE_LIST_NOT_IN_SOURCE"), "other.TAPSAccount has the wrong schema");
            var one = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), null, null, false, null,
                    new DatasetManifestIngestion.TableSelection(SourceInput.of("t.csv", "TAPSAccount\nCanadaReportingJurisdiction\n"), "Account", "F"));
            eq(2, one.manifests().size());
            var bad = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), null, null, false, null,
                    new DatasetManifestIngestion.TableSelection(SourceInput.of("t.csv", "a,b\nx,y\n"), "Account", "F"));
            yes(has(bad, "TABLE_LIST_COLUMN_MISSING") && bad.hasErrors() && bad.manifests().isEmpty(), "needs a table name column");
        });
        test("table list: the CLI works without --table-metadata, and rejects missing or misplaced options", () -> {
            Path dir = tempDir("casc-tablelist");
            try {
                Files.writeString(dir.resolve("s.sql"), SQL);
                Files.writeString(dir.resolve("tables.csv"), "table name\nTAPSAccount\n");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                eq(0, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-list", dir.resolve("tables.csv").toString(),
                        "--domain", "Account (Operations DGC Glossary)", "--boundary", "Firmwide", "--no-upstream", "--database-name", "DB", "--dataset-output", dir.resolve("out").toString()},
                        InputStream.nullInputStream(), po, po, k -> null));
                String written = Files.readString(dir.resolve("out/manifest_TAPSAccount.json"));
                contains(written, "\"name\": \"DB.dbo.TAPSAccount\"");
                contains(written, "\"domainName\": \"Account\"");
                yes(!Files.exists(dir.resolve("out/manifest_Plain.json")), "only listed tables");
                eq(2, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--dataset-output", dir.resolve("o2").toString()},
                        InputStream.nullInputStream(), po, po, k -> null));
                eq(2, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("tables.csv").toString(),
                        "--domain", "Account", "--dataset-output", dir.resolve("o3").toString()}, InputStream.nullInputStream(), po, po, k -> null));
            } finally {
                deleteTree(dir);
            }
        });
        test("descriptions: a table's description comes from the descriptions file, then the SQL comment, then the generated text", () -> {
            String meta = "table name,domain,boundary\nTAPSAccount,Account,F\nPlain,Account,F\n";
            String desc = "Tables,Description,Domain,Boundary,Revised Table Name\r\nTAPSAccount, Stores TAPS account records ,.Account,F,a2.dbo.T\r\na2oltp.Nope,Not here,,,\r\nTAPSAccount,Again,,,\r\nPlain,,,,\r\n";
            var sel = new DatasetManifestIngestion.TableSelection(null, null, null, SourceInput.of("descriptions.csv", desc));
            var r = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("s.sql", SQL + "COMMENT ON TABLE Plain IS 'From the SQL';")), SourceInput.of("t.csv", meta),
                    IngestOptions.defaults(), "a2oltp", null, false, "DB", sel);
            yes(!r.hasErrors(), r.findings().toString());
            eq("Stores TAPS account records", dataset(r.manifests().get(0)).get("description"));
            yes(has(r, "TABLE_DESCRIPTION_NOT_FOUND") && has(r, "TABLE_DESCRIPTION_DUPLICATE") && has(r, "TABLE_DESCRIPTION_ROW_SKIPPED"), r.findings().toString());
            yes(has(r, "TABLE_DESCRIPTION_DEFAULT_USED"), "Plain has no description row");
            var man = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", "table name,domain,boundary\nTAPSAccount,Account,F\n"), null, false, null,
                    new DatasetManifestIngestion.TableSelection(null, null, null, SourceInput.of("d.csv", "table name,description\nTAPSAccount,Account records\n")));
            eq("Account records", dataset(man.manifests().get(0)).get("description"));
            var none = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", "table name,domain,boundary\nTAPSAccount,Account,F\n"), null, false, null, null);
            eq("TAPSAccount table of the dbo schema", dataset(none.manifests().get(0)).get("description"));
            var bad = DatasetManifestIngestion.generate(SourceInput.of("m.json", STRICT), SourceInput.of("t.csv", "table name,domain,boundary\nTAPSAccount,Account,F\n"), null, false, null,
                    new DatasetManifestIngestion.TableSelection(null, null, null, SourceInput.of("d.csv", "table name,notes\nTAPSAccount,x\n")));
            yes(has(bad, "TABLE_DESCRIPTIONS_COLUMN_MISSING") && bad.hasErrors() && bad.manifests().isEmpty(), "needs a description column");
        });
        test("descriptions: the CLI takes --table-descriptions together with a table list", () -> {
            Path dir = tempDir("casc-desc");
            try {
                Files.writeString(dir.resolve("m.json"), STRICT);
                Files.writeString(dir.resolve("tables.csv"), "table name\nTAPSAccount\n");
                Files.writeString(dir.resolve("d.csv"), "Tables,Description\nTAPSAccount,Stores TAPS account records\n");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                eq(0, Cli.run(new String[] {"ingest", "--manifest-input", dir.resolve("m.json").toString(), "--table-list", dir.resolve("tables.csv").toString(),
                        "--domain", "Account", "--boundary", "F", "--no-upstream", "--table-descriptions", dir.resolve("d.csv").toString(), "--dataset-output", dir.resolve("out").toString()},
                        InputStream.nullInputStream(), po, po, k -> null));
                contains(Files.readString(dir.resolve("out/manifest_TAPSAccount.json")), "\"description\": \"Stores TAPS account records\"");
            } finally {
                deleteTree(dir);
            }
        });
        test("datasets: the CLI accepts --no-upstream", () -> {
            Path dir = tempDir("casc-datasets-noup");
            try {
                Files.writeString(dir.resolve("m.json"), STRICT);
                Files.writeString(dir.resolve("t.csv"), "table name,domain,boundary\nTAPSAccount,Account,Firmwide\n");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                String[] base = {"ingest", "--manifest-input", dir.resolve("m.json").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", dir.resolve("out").toString()};
                eq(1, Cli.run(base, InputStream.nullInputStream(), po, po, k -> null));
                String[] withFlag = java.util.Arrays.copyOf(base, base.length + 1);
                withFlag[base.length] = "--no-upstream";
                eq(0, Cli.run(withFlag, InputStream.nullInputStream(), po, po, k -> null));
                yes(!Files.readString(dir.resolve("out/manifest_TAPSAccount.json")).contains("upstreamDatasets"), "no upstreamDatasets");
            } finally {
                deleteTree(dir);
            }
        });
        test("datasets: the CLI accepts --column-metadata", () -> {
            Path dir = tempDir("casc-datasets-cols");
            try {
                Files.writeString(dir.resolve("s.sql"), SQL);
                Files.writeString(dir.resolve("t.csv"), CSV);
                Files.writeString(dir.resolve("c.csv"), "table name,column name,system data element\nTAPSAccount,accountID,Account ID\n");
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                eq(0, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--column-metadata", dir.resolve("c.csv").toString(), "--dataset-output", dir.resolve("out").toString()},
                        InputStream.nullInputStream(), po, po, k -> null));
                contains(Files.readString(dir.resolve("out/manifest_TAPSAccount.json")), "\"systemDataElements\"");
            } finally {
                deleteTree(dir);
            }
        });
        test("datasets: the CLI accepts --sql-input, and rejects giving both or neither source", () -> {
            Path dir = tempDir("casc-datasets-sql");
            try {
                Files.writeString(dir.resolve("s.sql"), SQL);
                Files.writeString(dir.resolve("m.json"), STRICT);
                Files.writeString(dir.resolve("t.csv"), CSV);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                PrintStream pe = new PrintStream(e, true, StandardCharsets.UTF_8);
                eq(0, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", dir.resolve("out").toString()}, InputStream.nullInputStream(), po, pe, k -> null));
                yes(Files.isRegularFile(dir.resolve("out/manifest_TAPSAccount.json")), "file written from SQL");
                eq(2, Cli.run(new String[] {"ingest", "--sql-input", dir.resolve("s.sql").toString(), "--manifest-input", dir.resolve("m.json").toString(),
                        "--table-metadata", dir.resolve("t.csv").toString(), "--dataset-output", dir.resolve("o2").toString()},
                        InputStream.nullInputStream(), po, pe, k -> null));
                eq(2, Cli.run(new String[] {"ingest", "--table-metadata", dir.resolve("t.csv").toString(), "--dataset-output", dir.resolve("o3").toString()},
                        InputStream.nullInputStream(), po, pe, k -> null));
            } finally {
                deleteTree(dir);
            }
        });
        test("datasets: the CLI writes one file per table and refuses to overwrite without --overwrite", () -> {
            Path dir = tempDir("casc-datasets");
            try {
                Files.writeString(dir.resolve("m.json"), LOOSE);
                Files.writeString(dir.resolve("t.csv"), CSV);
                Path outDir = dir.resolve("out");
                String[] args = {"ingest", "--manifest-input", dir.resolve("m.json").toString(), "--table-metadata", dir.resolve("t.csv").toString(),
                        "--dataset-output", outDir.toString()};
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                eq(0, Cli.run(args, InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8),
                        new PrintStream(e, true, StandardCharsets.UTF_8), k -> null));
                yes(Files.isRegularFile(outDir.resolve("manifest_TAPSAccount.json")), "file written");
                yes(Cli.run(args, InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8),
                        new PrintStream(e, true, StandardCharsets.UTF_8), k -> null) == 3, "second run without --overwrite fails");
            } finally {
                deleteTree(dir);
            }
        });
    }
}

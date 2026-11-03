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
            eq("a2oltp.TAPSAccount", d.get("name"));
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
            eq("a2oltp.TAPSAccount", d.get("name"));
            eq("[{\"name\":\"accountID\",\"description\":\"Account identifier\"},{\"name\":\"AccountUUID\"}]", Json.writeCompact(d.get("datasetElements")));
            yes(!r.manifests().get(0).content().contains("systemDataElements"), "SQL has no system data elements");
            eq("Plain", dataset(r.manifests().get(1)).get("name"));
            yes(has(r, "DATASET_SCHEMA_MISSING"), "Plain has no schema");
            yes(has(r, "DATASET_VIEW_SKIPPED"), "view skipped");
            yes(has(r, "METADATA_TABLE_NOT_IN_MANIFEST"), "TAPSView is a view, not a table");
            var withDefault = DatasetManifestIngestion.generateFromSql(List.of(SourceInput.of("schema.sql", SQL)), SourceInput.of("t.csv", csv),
                    IngestOptions.defaults(), "a2oltp");
            eq("a2oltp.Plain", dataset(withDefault.manifests().get(1)).get("name"));
            yes(!has(withDefault, "DATASET_SCHEMA_MISSING"), "default schema applied");
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

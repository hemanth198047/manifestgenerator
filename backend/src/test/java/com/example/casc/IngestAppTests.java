package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import com.example.casc.json.Json;
import com.example.casc.web.WebServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class IngestAppTests {
    private IngestAppTests() {}

    private record Run(int code, String out, String err) {}

    private static Run cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static String sample(String name) throws Exception {
        return resource("/static/samples/" + name);
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body, String type) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type)
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String jsonRequest(String system, String... nameText) {
        StringBuilder b = new StringBuilder("{\"sources\":[");
        for (int i = 0; i < nameText.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"name\":").append(Json.writeCompact(nameText[i])).append(",\"text\":").append(Json.writeCompact(nameText[i + 1])).append('}');
        }
        b.append(']');
        if (system != null) {
            b.append(",\"system\":").append(system);
        }
        return b.append('}').toString();
    }

    static void register() {
        test("web: sources page inspects mixed sources and escapes everything", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(base + "/sources")).GET().build(), BodyHandlers.ofString());
                eq(200, get.statusCode());
                contains(get.body(), "Step 1: Sources");

                String body = form("name", "schema.sql", "text", sample("sample-schema.sql"),
                        "name", "dictionary.csv", "text", sample("sample-dictionary.csv"),
                        "name", "notes.md", "text", sample("sample-notes.md"),
                        "name", "<img src=x onerror=alert(1)>.sql", "text", "CREATE TABLE <b>t</b> (a INT);",
                        "dialect", "auto", "delimiter", "auto", "mode", "auto", "hasHeader", "true", "headerRow", "1", "maxRows", "100");
                HttpResponse<String> r = post(http, base + "/sources", body, "application/x-www-form-urlencoded");
                eq(200, r.statusCode());
                contains(r.body(), "trade.orders");
                contains(r.body(), "trade.clients");
                contains(r.body(), "Create a draft manifest");
                contains(r.body(), "Candidate terms");
                excludes(r.body(), "<img src=x");
                excludes(r.body(), "<b>t</b>");
                contains(r.body(), "&lt;img src=x");

                HttpResponse<String> unavailable = post(http, base + "/sources", form("name", "d.xlsx", "text", "x", "hasHeader", "true"),
                        "application/x-www-form-urlencoded");
                eq(200, unavailable.statusCode());
                contains(unavailable.body(), "FORMAT_UNAVAILABLE");
                contains(unavailable.body(), "Apache POI");
                excludes(unavailable.body(), "Create a draft manifest");

                eq(400, post(http, base + "/sources", form("name", "", "text", "CREATE TABLE t (a INT);"), "application/x-www-form-urlencoded").statusCode());
                eq(400, post(http, base + "/sources", form("name", "a.sql", "text", "x", "dialect", "mysql"), "application/x-www-form-urlencoded").statusCode());
            }
        });

        test("web: draft creation hands over to validation with the unresolved decision", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> ok = post(http, base + "/draft", form("name", "schema.sql", "text", sample("sample-schema.sql"),
                        "databaseLogicalName", "TRADE_DB", "databaseType", "Oracle", "defaultSchema", "", "hasHeader", "true"),
                        "application/x-www-form-urlencoded");
                eq(200, ok.statusCode());
                contains(ok.body(), "TRADE_DB");
                contains(ok.body(), "UNRESOLVED_DECISION");
                contains(ok.body(), "ATTEST_MISSING");
                contains(ok.body(), "Preview");

                HttpResponse<String> needSchema = post(http, base + "/draft", form("name", "orders.csv", "text", "id,name\n1,x\n",
                        "databaseLogicalName", "TRADE_DB", "defaultSchema", "", "hasHeader", "true"), "application/x-www-form-urlencoded");
                eq(422, needSchema.statusCode());
                contains(needSchema.body(), "default schema");

                HttpResponse<String> badType = post(http, base + "/draft", form("name", "a.sql", "text", "x", "databaseLogicalName", "D",
                        "databaseType", "Mongo", "hasHeader", "true"), "application/x-www-form-urlencoded");
                eq(400, badType.statusCode());
            }
        });

        test("web: REST ingest and draft contracts", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> in = post(http, base + "/api/ingest", jsonRequest(null, "schema.sql", sample("sample-schema.sql")), "application/json");
                eq(200, in.statusCode());
                Map<?, ?> tree = (Map<?, ?>) Json.parse(in.body());
                eq(2, ((List<?>) tree.get("tables")).size());
                eq(Boolean.FALSE, tree.get("hasErrors"));
                Map<?, ?> failed = (Map<?, ?>) Json.parse(post(http, base + "/api/ingest", jsonRequest(null, "x.pdf", "x"), "application/json").body());
                eq(Boolean.TRUE, failed.get("hasErrors"));
                eq(400, post(http, base + "/api/ingest", "{\"sources\":5}", "application/json").statusCode());

                String system = "{\"databaseLogicalName\":\"TRADE_DB\",\"databaseType\":\"PostgreSQL\"}";
                HttpResponse<String> d = post(http, base + "/api/draft?format=yaml", jsonRequest(system, "schema.sql", sample("sample-schema.sql")), "application/json");
                eq(200, d.statusCode());
                Map<?, ?> dt = (Map<?, ?>) Json.parse(d.body());
                eq(Boolean.TRUE, dt.get("drafted"));
                contains((String) dt.get("content"), "logicalName: \"TRADE_DB\"");
                eq(1, ((List<?>) dt.get("unresolved")).size());
                eq(400, post(http, base + "/api/draft", jsonRequest(null, "schema.sql", "x"), "application/json").statusCode());
                eq(422, post(http, base + "/api/draft", jsonRequest(system, "x.csv", "a\n1\n"), "application/json").statusCode());
                eq(400, post(http, base + "/api/draft?format=xml", jsonRequest(system, "schema.sql", sample("sample-schema.sql")), "application/json").statusCode());
            }
        });

        test("cli: ingest summary, json, bundle output, draft output, spaces in paths", () -> {
            Path dir = tempDir("casc ingest ");
            try {
                Path specs = Files.createDirectory(dir.resolve("my specs"));
                Files.writeString(specs.resolve("schema.sql"), sample("sample-schema.sql"), StandardCharsets.UTF_8);
                Files.writeString(specs.resolve("dictionary.csv"), sample("sample-dictionary.csv"), StandardCharsets.UTF_8);
                Files.writeString(specs.resolve("config.json"), "{}", StandardCharsets.UTF_8);

                Run summary = cli("ingest", "--input-files", specs.toString());
                eq(Cli.OK, summary.code());
                contains(summary.out(), "3 table(s)/view(s)");
                contains(summary.err(), "config.json");

                Run json = cli("ingest", "--input-files", specs.toString(), "--json");
                eq(Cli.OK, json.code());
                eq(3, ((List<?>) ((Map<?, ?>) Json.parse(json.out())).get("tables")).size());

                Path bundle = dir.resolve("bundle.json");
                Path draft = dir.resolve("draft out.json");
                Run d = cli("ingest", "--input-files", specs.toString(), "--bundle-output", bundle.toString(),
                        "--db-name", "TRADE_DB", "--db-type", "Oracle", "--output", draft.toString());
                eq(Cli.OK, d.code());
                contains(Files.readString(draft, StandardCharsets.UTF_8), "\"logicalName\": \"TRADE_DB\"");
                contains(Files.readString(bundle, StandardCharsets.UTF_8), "\"tables\"");
                contains(d.out(), "UNRESOLVED_DECISION");
                eq(List.of("bundle.json", "draft out.json", "my specs"), names(dir));
                eq(Cli.IO, cli("ingest", "--input-files", specs.toString(), "--db-name", "X", "--output", draft.toString()).code());
                eq(Cli.OK, cli("ingest", "--input-files", specs.toString(), "--db-name", "X", "--output", draft.toString(), "--overwrite", "--format", "yaml").code());
                contains(Files.readString(draft, StandardCharsets.UTF_8), "logicalName: \"X\"");
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: ingest failures return clear exit codes and write nothing", () -> {
            Path dir = tempDir("casc ingest ");
            Path other = tempDir("casc other ");
            try {
                eq(Cli.USAGE, cli("ingest").code());
                eq(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--db-name", "X").code());
                eq(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--output", "x.json").code());
                eq(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--db-type", "Mongo").code());
                eq(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--map", "nonsense").code());
                eq(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--dialect", "mysql").code());

                Path xlsx = dir.resolve("book.xlsx");
                Files.write(xlsx, new byte[] {1});
                Path out = dir.resolve("out.json");
                Run r = cli("ingest", "--input-files", xlsx.toString(), "--db-name", "X", "--output", out.toString());
                eq(Cli.INVALID, r.code());
                contains(r.out(), "FORMAT_UNAVAILABLE");
                yes(!Files.exists(out), "no draft after a failed source");

                Path outside = other.resolve("a.sql");
                Files.writeString(outside, "CREATE TABLE t (a INT);", StandardCharsets.UTF_8);
                Run bounded = cli("ingest", "--input-files", outside.toString(), "--root", dir.toString());
                eq(Cli.INVALID, bounded.code());
                contains(bounded.out(), "outside the allowed input root");
                eq(Cli.INVALID, cli("ingest", "--input-files", dir.resolve("missing.sql").toString()).code());

                Path csv = dir.resolve("orders.csv");
                Files.writeString(csv, "id\n1\n", StandardCharsets.UTF_8);
                Run noSchema = cli("ingest", "--input-files", csv.toString(), "--db-name", "X", "--output", out.toString());
                eq(Cli.INVALID, noSchema.code());
                contains(noSchema.err(), "DRAFT_SCHEMA_REQUIRED");
                eq(Cli.OK, cli("ingest", "--input-files", csv.toString(), "--db-name", "X", "--default-schema", "s", "--output", out.toString()).code());
                eq(List.of("book.xlsx", "orders.csv", "out.json"), names(dir));
            } finally {
                deleteTree(dir);
                deleteTree(other);
            }
        });
    }
}

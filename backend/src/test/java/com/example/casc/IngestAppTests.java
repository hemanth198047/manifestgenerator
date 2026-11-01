package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import com.example.casc.json.Json;
import com.example.casc.net.UrlPolicy;
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

    /** True when an optional reader adapter for the extension is installed on this class path. */
    private static boolean adapter(String ext) {
        return com.example.casc.ingest.SourceIngestionService.standard().readerFor(ext) != null;
    }

    private record Run(int code, String out, String err) {}

    /** Asserts an exit code and, on failure, shows everything the command printed. */
    private static void exit(int expected, Run r) {
        if (r.code() != expected) {
            throw new AssertionError("expected exit code " + expected + " but was " + r.code()
                    + "\n--- stdout ---\n" + r.out() + "--- stderr ---\n" + r.err() + "--- end ---");
        }
    }

    private static Run cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, java.io.InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), k -> null);
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
                contains(unavailable.body(), adapter("xlsx") ? "SOURCE_ENCODING" : "FORMAT_UNAVAILABLE");   // text given for a binary format
                if (!adapter("xlsx")) {
                    contains(unavailable.body(), "Apache POI");
                }
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
                exit(Cli.OK, summary);
                contains(summary.out(), "3 table(s)/view(s)");
                contains(summary.err(), "config.json");

                Run json = cli("ingest", "--input-files", specs.toString(), "--json");
                exit(Cli.OK, json);
                eq(3, ((List<?>) ((Map<?, ?>) Json.parse(json.out())).get("tables")).size());

                Path bundle = dir.resolve("bundle.json");
                Path draft = dir.resolve("draft out.json");
                Run d = cli("ingest", "--input-files", specs.toString(), "--bundle-output", bundle.toString(),
                        "--db-name", "TRADE_DB", "--db-type", "Oracle", "--output", draft.toString());
                exit(Cli.OK, d);
                contains(Files.readString(draft, StandardCharsets.UTF_8), "\"logicalName\": \"TRADE_DB\"");
                contains(Files.readString(bundle, StandardCharsets.UTF_8), "\"tables\"");
                contains(d.out(), "UNRESOLVED_DECISION");
                eq(List.of("bundle.json", "draft out.json", "my specs"), names(dir));
                exit(Cli.IO, cli("ingest", "--input-files", specs.toString(), "--db-name", "X", "--output", draft.toString()));
                exit(Cli.OK, cli("ingest", "--input-files", specs.toString(), "--db-name", "X", "--output", draft.toString(), "--overwrite", "--format", "yaml"));
                contains(Files.readString(draft, StandardCharsets.UTF_8), "logicalName: \"X\"");
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: environment variables are honoured when supplied and the tests never depend on the real environment", () -> {
            Path dir = tempDir("casc env ");
            try {
                Path csv = dir.resolve("orders.csv");
                Files.writeString(csv, "id\n1\n", StandardCharsets.UTF_8);
                Path out = dir.resolve("out.json");
                String[] args = {"ingest", "--input-files", csv.toString(), "--db-name", "X", "--default-schema", "s", "--output", out.toString()};
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                PrintStream po = new PrintStream(o, true, StandardCharsets.UTF_8);
                PrintStream pe = new PrintStream(e, true, StandardCharsets.UTF_8);
                int blocked = Cli.run(args, java.io.InputStream.nullInputStream(), po, pe, k -> k.equals("CASC_INPUT_ROOT") ? "/no/such/root" : null);
                eq(Cli.INVALID, blocked);
                contains(o.toString(StandardCharsets.UTF_8), "input root cannot be resolved");
                yes(!Files.exists(out), "nothing written when the configured root is unusable");
                int badUrl = Cli.run(new String[] {"ingest", "--url", "https://x.example/a.json"}, java.io.InputStream.nullInputStream(), po, pe,
                        k -> k.equals("CASC_URL_ALLOW") ? "bad entry" : null);
                eq(Cli.USAGE, badUrl);
                exit(Cli.OK, cli(args));          // an empty environment: the same command succeeds
                yes(Files.exists(out), "draft written");
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: ingest failures return clear exit codes and write nothing", () -> {
            Path dir = tempDir("casc ingest ");
            Path other = tempDir("casc other ");
            try {
                exit(Cli.USAGE, cli("ingest"));
                exit(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--db-name", "X"));
                exit(Cli.INVALID, cli("ingest", "--input-files", dir.toString(), "--output", dir.resolve("x.json").toString()));
                exit(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--db-type", "Mongo"));
                exit(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--map", "nonsense"));
                exit(Cli.USAGE, cli("ingest", "--input-files", dir.toString(), "--dialect", "mysql"));

                Path xlsx = dir.resolve("book.xlsx");
                Files.write(xlsx, new byte[] {1});
                Path out = dir.resolve("out.json");
                Run r = cli("ingest", "--input-files", xlsx.toString(), "--db-name", "X", "--output", out.toString());
                exit(Cli.INVALID, r);
                contains(r.out(), adapter("xlsx") ? "SPREADSHEET_UNREADABLE" : "FORMAT_UNAVAILABLE");
                yes(!Files.exists(out), "no draft after a failed source");

                Path outside = other.resolve("a.sql");
                Files.writeString(outside, "CREATE TABLE t (a INT);", StandardCharsets.UTF_8);
                Run bounded = cli("ingest", "--input-files", outside.toString(), "--root", dir.toString());
                exit(Cli.INVALID, bounded);
                contains(bounded.out(), "outside the allowed input root");
                exit(Cli.INVALID, cli("ingest", "--input-files", dir.resolve("missing.sql").toString()));

                Path csv = dir.resolve("orders.csv");
                Files.writeString(csv, "id\n1\n", StandardCharsets.UTF_8);
                Run noSchema = cli("ingest", "--input-files", csv.toString(), "--db-name", "X", "--output", out.toString());
                exit(Cli.INVALID, noSchema);
                contains(noSchema.err(), "DRAFT_SCHEMA_REQUIRED");
                exit(Cli.OK, cli("ingest", "--input-files", csv.toString(), "--db-name", "X", "--default-schema", "s", "--output", out.toString()));
                eq(List.of("book.xlsx", "orders.csv", "out.json"), names(dir));
            } finally {
                deleteTree(dir);
                deleteTree(other);
            }
        });

        test("web: URL sources are fetched under the allowlist, escaped, and never echo the query", () -> {
            String spec = sample("sample-openapi.json").replace("List trades", "<script>alert(1)</script> trades");
            try (TestServer ts = TestServer.start(Map.of("/spec.json", TestServer.Resp.ok("application/json", spec),
                    "/page", TestServer.Resp.ok("text/html", "<p>x</p>"), "/notes", TestServer.Resp.ok("text/markdown", "# T\n- TRADE_QTY: Quantity\n")))) {
                int port = ts.port();
                UrlPolicy policy = new UrlPolicy(List.of("127.0.0.1:" + port), true, true, 3, 1_000_000, 3);
                try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000, policy))) {
                    server.start();
                    String base = "http://127.0.0.1:" + server.port();
                    HttpClient http = HttpClient.newHttpClient();
                    contains(http.send(HttpRequest.newBuilder(URI.create(base + "/sources")).GET().build(), BodyHandlers.ofString()).body(), "URLs to fetch");
                    String f = "application/x-www-form-urlencoded";
                    HttpResponse<String> ok = post(http, base + "/sources", form("urls", "http://127.0.0.1:" + port + "/spec.json?token=SECRET\nhttp://127.0.0.1:" + port + "/notes",
                            "hasHeader", "true"), f);
                    eq(200, ok.statusCode());
                    contains(ok.body(), "Interface candidates");
                    contains(ok.body(), "listTrades");
                    contains(ok.body(), "Candidate terms");
                    contains(ok.body(), "Create a draft manifest");
                    contains(ok.body(), "interfaceDirection");
                    excludes(ok.body(), "SECRET");
                    excludes(ok.body(), "<script>alert(1)");
                    contains(ok.body(), "&lt;script&gt;alert(1)");

                    HttpResponse<String> draft = post(http, base + "/draft", form("name", "127.0.0.1:" + port + "/spec.json", "text", spec,
                            "interfaceDirection", "DISTRIBUTION", "hasHeader", "true"), f);
                    eq(200, draft.statusCode());
                    contains(draft.body(), "&quot;interfaces&quot;");
                    contains(draft.body(), "UNRESOLVED_DECISION");

                    HttpResponse<String> html = post(http, base + "/sources", form("urls", "http://127.0.0.1:" + port + "/page", "hasHeader", "true"), f);
                    if (adapter("html")) {
                        contains(html.body(), "INGESTED");                      // the HTML adapter reads the page
                    } else {
                        contains(html.body(), "FORMAT_UNAVAILABLE");
                        excludes(html.body(), "Create a draft manifest");
                    }
                    contains(post(http, base + "/sources", form("urls", "http://127.0.0.1:" + port + "/missing", "hasHeader", "true"), f).body(), "HTTP 404");
                    contains(post(http, base + "/sources", form("urls", "http://127.0.0.2:" + port + "/spec.json", "hasHeader", "true"), f).body(), "not in the URL allowlist");

                    String req = "{\"urls\":[\"http://127.0.0.1:" + port + "/spec.json\"],\"system\":{\"interfaceDirection\":\"DISTRIBUTION\"}}";
                    Map<?, ?> ingested = (Map<?, ?>) Json.parse(post(http, base + "/api/ingest", req, "application/json").body());
                    eq(2, ((List<?>) ingested.get("interfaces")).size());
                    eq(2, ((List<?>) ingested.get("structures")).size());
                    HttpResponse<String> d = post(http, base + "/api/draft", req, "application/json");
                    eq(200, d.statusCode());
                    contains((String) ((Map<?, ?>) Json.parse(d.body())).get("content"), "\"interfaces\"");
                    eq(400, post(http, base + "/api/ingest", "{}", "application/json").statusCode());
                }
            }
        });

        test("web: URL sources are refused when no hosts are allowed", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                contains(http.send(HttpRequest.newBuilder(URI.create(base + "/sources")).GET().build(), BodyHandlers.ofString()).body(), "URL sources are disabled");
                HttpResponse<String> r = post(http, base + "/sources", form("urls", "https://wiki.example/spec.json", "hasHeader", "true"),
                        "application/x-www-form-urlencoded");
                contains(r.body(), "URL sources are disabled");
                contains(r.body(), "SOURCE_UNREADABLE");
                Map<?, ?> api = (Map<?, ?>) Json.parse(post(http, base + "/api/ingest", "{\"urls\":[\"https://wiki.example/x.json\"]}", "application/json").body());
                eq(Boolean.TRUE, api.get("hasErrors"));
            }
        });

        test("cli: URL options are validated and the block list holds", () -> {
            Run none = cli("ingest", "--url", "https://wiki.example/spec.json");
            exit(Cli.INVALID, none);
            contains(none.out(), "URL sources are disabled");
            exit(Cli.USAGE, cli("ingest", "--url", "https://x.example/a.json", "--url-allow", "bad entry"));
            exit(Cli.USAGE, cli("ingest", "--url", "https://x.example/a.json", "--url-allow", "x.example", "--url-timeout", "0"));
            Run loop = cli("ingest", "--url", "http://127.0.0.1:1/a.json", "--url-allow", "127.0.0.1:1", "--url-allow-http");
            exit(Cli.INVALID, loop);
            contains(loop.out(), "prohibited address");
            Run meta = cli("ingest", "--url", "https://169.254.169.254/latest/meta-data", "--url-allow", "169.254.169.254");
            exit(Cli.INVALID, meta);
            contains(meta.out(), "prohibited address");
            Run cred = cli("ingest", "--url", "https://user:pw@x.example/a.json", "--url-allow", "x.example");
            exit(Cli.INVALID, cred);
            contains(cred.out(), "embedded credentials");
            exit(Cli.USAGE, cli("ingest"));
        });

        test("cli: OpenAPI sources draft interfaces only with a direction", () -> {
            Path dir = tempDir("casc api ");
            try {
                Path api = dir.resolve("trade api.json");
                Files.writeString(api, sample("sample-openapi.json"), StandardCharsets.UTF_8);
                Run inspect = cli("ingest", "--input-files", api.toString());
                exit(Cli.OK, inspect);
                contains(inspect.out(), "2 interface candidate(s), 2 structure candidate(s)");
                Path out = dir.resolve("m.yaml");
                Run noDir = cli("ingest", "--input-files", api.toString(), "--output", out.toString());
                exit(Cli.INVALID, noDir);
                contains(noDir.err(), "DRAFT_DIRECTION_REQUIRED");
                yes(!Files.exists(out), "nothing written");
                Run ok = cli("ingest", "--input-files", api.toString(), "--interface-direction", "DISTRIBUTION", "--output", out.toString(), "--format", "yaml");
                exit(Cli.OK, ok);
                String y = Files.readString(out, StandardCharsets.UTF_8);
                contains(y, "interfaces:");
                contains(y, "type: \"DISTRIBUTION\"");
                contains(y, "physicalName: \"client.clientId\"");
                exit(Cli.USAGE, cli("ingest", "--input-files", api.toString(), "--interface-direction", "BOTH"));
                eq(List.of("m.yaml", "trade api.json"), names(dir));
            } finally {
                deleteTree(dir);
            }
        });

        test("input files: directory scans take OpenAPI JSON and skip other JSON", () -> {
            Path dir = tempDir("casc scan ");
            try {
                Files.writeString(dir.resolve("api.json"), sample("sample-openapi.json"), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("package.json"), "{\"name\":\"x\"}", StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("old.json"), "{\"swagger\":\"2.0\",\"paths\":{}}", StandardCharsets.UTF_8);
                var r = com.example.casc.ingest.InputFiles.collect(List.of(dir.toString()), null,
                        com.example.casc.ingest.SourceIngestionService.standard().knownExtensions());
                eq(List.of("api.json", "old.json"), r.inputs().stream().map(com.example.casc.ingest.SourceInput::name).toList());
                yes(r.notes().stream().anyMatch(n -> n.contains("package.json")), "other JSON is noted as skipped");
            } finally {
                deleteTree(dir);
            }
        });
    }
}

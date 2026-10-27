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

final class AppTests {
    private AppTests() {}

    private record Run(int code, String out, String err) {}

    private static Run cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static final String BAD = "{\"logicalServices\":[{\"name\":\"A\"}]}";

    static void register() {
        test("cli: usage errors never wait for input", () -> {
            eq(Cli.USAGE, cli().code());
            eq(Cli.USAGE, cli("nonsense").code());
            eq(Cli.USAGE, cli("validate").code());
            contains(cli("validate").err(), "--input FILE is required");
            eq(Cli.USAGE, cli("export", "--input", "x").code());
            eq(Cli.USAGE, cli("validate", "--input").code());
            eq(Cli.USAGE, cli("web", "--port", "99999").code());
            eq(Cli.OK, cli("help").code());
        });

        test("cli: validate and export with spaces and unicode in paths", () -> {
            String jnu = System.getProperty("sun.jnu.encoding", "");
            boolean unicodeNames = java.nio.charset.Charset.forName(jnu).newEncoder().canEncode('\u00fc');
            Path dir = tempDir("casc test ");
            try {
                Path sub = Files.createDirectory(dir.resolve("dir with spaces"));
                String fileName = unicodeNames ? "\u00fcn\u00ef manifest.json" : "plain manifest.json";
                Path in = sub.resolve(fileName);
                Files.writeString(in, resource("/static/samples/sample-manifest.json"), StandardCharsets.UTF_8);
                Run v = cli("validate", "--input", in.toString());
                eq(Cli.OK, v.code());
                contains(v.out(), "0 error(s)");
                Path out = sub.resolve("out put.json");
                Run e = cli("export", "--input", in.toString(), "--output", out.toString());
                eq(Cli.OK, e.code());
                contains(Files.readString(out, StandardCharsets.UTF_8), "\"TradeService\"");
                eq(Cli.IO, cli("export", "--input", in.toString(), "--output", out.toString()).code());
                eq(Cli.OK, cli("export", "--input", in.toString(), "--output", out.toString(), "--overwrite", "--format", "yaml").code());
                contains(Files.readString(out, StandardCharsets.UTF_8), "logicalServices:");
                eq(List.of(fileName, "out put.json"), names(sub).stream().sorted(java.util.Comparator.comparing(n -> n.equals("out put.json") ? 1 : 0)).toList());
                if (!unicodeNames) {
                    throw new Skipped("spaces verified; Unicode file names need a UTF-8 locale (sun.jnu.encoding=" + jnu + ")");
                }
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: invalid manifest returns 1 and writes nothing unless allowed", () -> {
            Path dir = tempDir("casc");
            try {
                Path in = dir.resolve("bad.json");
                Files.writeString(in, BAD, StandardCharsets.UTF_8);
                Path out = dir.resolve("out.json");
                eq(Cli.INVALID, cli("validate", "--input", in.toString()).code());
                Run e = cli("export", "--input", in.toString(), "--output", out.toString());
                eq(Cli.INVALID, e.code());
                yes(!Files.exists(out), "nothing may be written");
                contains(e.err(), "BOOL_MISSING");
                eq(Cli.OK, cli("export", "--input", in.toString(), "--output", out.toString(), "--allow-invalid").code());
                yes(Files.exists(out), "written with --allow-invalid");
                eq(List.of("bad.json", "out.json"), names(dir));
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: missing file, bad JSON, bad UTF-8, missing output directory return 3", () -> {
            Path dir = tempDir("casc");
            try {
                eq(Cli.IO, cli("validate", "--input", dir.resolve("none.json").toString()).code());
                Path bad = dir.resolve("bad.json");
                Files.writeString(bad, "{not json", StandardCharsets.UTF_8);
                eq(Cli.IO, cli("validate", "--input", bad.toString()).code());
                Path enc = dir.resolve("enc.json");
                Files.write(enc, new byte[] {'{', '"', (byte) 0xC3, (byte) 0x28, '"', '}'});
                eq(Cli.IO, cli("validate", "--input", enc.toString()).code());
                Path ok = dir.resolve("ok.json");
                Files.writeString(ok, "{}", StandardCharsets.UTF_8);
                eq(Cli.IO, cli("export", "--input", ok.toString(), "--output", dir.resolve("nodir").resolve("o.json").toString()).code());
            } finally {
                deleteTree(dir);
            }
        });

        test("cli: json report and capabilities", () -> {
            Path dir = tempDir("casc");
            try {
                Path in = dir.resolve("bad.json");
                Files.writeString(in, BAD, StandardCharsets.UTF_8);
                Run r = cli("validate", "--input", in.toString(), "--json");
                eq(Cli.INVALID, r.code());
                eq(Boolean.FALSE, ((Map<?, ?>) Json.parse(r.out())).get("valid"));
                Run c = cli("capabilities");
                contains(c.out(), "UNAVAILABLE");
                contains(c.out(), "JavaFX");
            } finally {
                deleteTree(dir);
            }
        });

        test("web: pages, static files, API, escaping, limits, origin check", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 100_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();

                HttpResponse<String> home = get(http, base + "/");
                eq(200, home.statusCode());
                contains(home.body(), "Capabilities");
                contains(home.headers().firstValue("Content-Type").orElse(""), "text/html");
                contains(home.headers().firstValue("Content-Security-Policy").orElse(""), "default-src 'self'");
                eq(200, get(http, base + "/validate").statusCode());
                eq(200, get(http, base + "/static/css/app.css").statusCode());
                eq(200, get(http, base + "/static/samples/sample-manifest.json").statusCode());
                eq(404, get(http, base + "/static/nope.css").statusCode());
                eq(404, get(http, base + "/static/..%2Fx").statusCode());
                eq(404, get(http, base + "/missing").statusCode());
                eq(405, post(http, base + "/", "x", Map.of()).statusCode());
                eq("ok", get(http, base + "/health").body());

                String sample = resource("/static/samples/sample-manifest.json");
                HttpResponse<String> v = post(http, base + "/api/validate", sample, Map.of());
                eq(200, v.statusCode());
                eq(Boolean.TRUE, ((Map<?, ?>) Json.parse(v.body())).get("valid"));
                eq(400, post(http, base + "/api/validate", "{oops", Map.of()).statusCode());

                HttpResponse<String> y = post(http, base + "/api/export?format=yaml", sample, Map.of());
                eq(200, y.statusCode());
                contains((String) ((Map<?, ?>) Json.parse(y.body())).get("content"), "logicalServices:");
                eq(400, post(http, base + "/api/export?format=xml", sample, Map.of()).statusCode());

                String xss = "{\"logicalServices\":[{\"name\":\"<script>alert(1)</script>\",\"isDeployedOnPrem\":true}]}";
                HttpResponse<String> page = post(http, base + "/validate", form("manifest", xss, "format", "json"), Map.of());
                eq(200, page.statusCode());
                excludes(page.body(), "<script>alert(1)");
                contains(page.body(), "&lt;script&gt;alert(1)");
                eq(400, post(http, base + "/validate", form("manifest", "{oops", "format", "json"), Map.of()).statusCode());

                String bad = "{\"logicalServices\":[{\"name\":\"A\"}]}";
                HttpResponse<String> blocked = post(http, base + "/download", form("manifest", bad, "format", "json"), Map.of());
                eq(422, blocked.statusCode());
                HttpResponse<String> forced = post(http, base + "/download", form("manifest", bad, "format", "json", "allowInvalid", "on"), Map.of());
                eq(200, forced.statusCode());
                contains(forced.headers().firstValue("Content-Disposition").orElse(""), "manifest.json");
                HttpResponse<String> okDownload = post(http, base + "/download", form("manifest", sample, "format", "yaml"), Map.of());
                eq(200, okDownload.statusCode());
                contains(okDownload.headers().firstValue("Content-Disposition").orElse(""), "manifest.yaml");

                eq(403, post(http, base + "/api/validate", sample, Map.of("Origin", "http://evil.example")).statusCode());
                eq(200, post(http, base + "/api/validate", sample, Map.of("Origin", base)).statusCode());
            }
        });

        test("web: oversize body is rejected with 413", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 100))) {
                server.start();
                HttpResponse<String> r = post(HttpClient.newHttpClient(),
                        "http://127.0.0.1:" + server.port() + "/api/validate", "x".repeat(200), Map.of());
                eq(413, r.statusCode());
            }
        });
    }

    private static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", body.contains("=") && !body.startsWith("{") ? "application/x-www-form-urlencoded" : "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return http.send(b.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

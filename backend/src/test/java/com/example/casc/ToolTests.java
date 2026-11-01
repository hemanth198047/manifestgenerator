package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.agent.AgentTools;
import com.example.casc.agent.ToolException;
import com.example.casc.cli.Cli;
import com.example.casc.json.Json;
import com.example.casc.net.UrlFetcher;
import com.example.casc.web.WebServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ToolTests {
    private ToolTests() {}

    private static final Path EXAMPLES = Path.of("infrastructure/integrations/examples");
    private static final AgentTools TOOLS = new AgentTools(UrlFetcher.disabled());

    private record Run(int code, String out, String err) {}

    /** Asserts an exit code and, on failure, shows everything the command printed. */
    private static void exit(int expected, Run r) {
        if (r.code() != expected) {
            throw new AssertionError("expected exit code " + expected + " but was " + r.code()
                    + "\n--- stdout ---\n" + r.out() + "--- stderr ---\n" + r.err() + "--- end ---");
        }
    }

    private static Run cli(InputStream in, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, in, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), k -> null);
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static InputStream stdin(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream forbidden() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new AssertionError("standard input must not be read");
            }
        };
    }

    private static Map<?, ?> obj(String json) throws Exception {
        return (Map<?, ?>) Json.parse(json);
    }

    private static Map<?, ?> call(String tool, String json) throws Exception {
        return TOOLS.call(tool, Json.parse(json));
    }

    private static String code(String tool, String json) throws Exception {
        try {
            TOOLS.call(tool, Json.parse(json));
        } catch (ToolException e) {
            return e.code() + ": " + e.getMessage();
        }
        throw new AssertionError("expected the call to be rejected: " + json);
    }

    /** Text as an editor on any OS might have saved it: a leading byte-order mark and CRLF line endings are tolerated. */
    private static String lf(String s) {
        return (s.startsWith("\uFEFF") ? s.substring(1) : s).replace("\r\n", "\n");
    }

    private static String example(String name) throws Exception {
        Path p = EXAMPLES.resolve(name);
        if (!Files.exists(p)) {
            throw new Skipped("run from the repository root to check " + p);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** The ingest result for the tiny orders example, as an agent would pass it on. */
    private static Object parsed() throws Exception {
        return Json.parse(Json.write(call("ingest_sources", example("ingest_sources.request.json"))));
    }

    private static String generateRequest(String userInputs) throws Exception {
        return "{\"parsedMetadata\":" + Json.write(parsed()) + (userInputs == null ? "" : ",\"userInputs\":" + userInputs) + "}";
    }

    static void register() {
        test("tools: exactly the three contracts are described", () -> {
            List<String> names = AgentTools.descriptors().stream().map(AgentTools.Descriptor::name).toList();
            eq(List.of("ingest_sources", "validate_manifest_payload", "generate_casc_manifest"), names);
            Run r = cli(forbidden(), "tools");
            exit(Cli.OK, r);
            eq(3, ((List<?>) obj(r.out()).get("tools")).size());
            for (var d : AgentTools.descriptors()) {
                yes(d.description().length() > 30 && !d.required().isEmpty(), d.name());
            }
        });

        test("tools: the example requests run and the generate example matches a fresh ingest (no drift)", () -> {
            Map<?, ?> ingest = call("ingest_sources", example("ingest_sources.request.json"));
            eq(Boolean.FALSE, ingest.get("hasErrors"));
            eq(1, ((List<?>) ingest.get("tables")).size());
            Map<?, ?> valid = call("validate_manifest_payload", example("validate_manifest_payload.request.json"));
            eq(Boolean.TRUE, valid.get("valid"));
            eq("LOCAL", valid.get("validationLevel"));
            Map<?, ?> gen = call("generate_casc_manifest", example("generate_casc_manifest.request.json"));
            eq(Boolean.TRUE, gen.get("exportable"));
            Map<?, ?> req = obj(example("generate_casc_manifest.request.json"));
            Map<?, ?> fresh = ingest;
            Map<String, Object> expected = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : fresh.entrySet()) {
                expected.put((String) e.getKey(), e.getValue());
            }
            eq(Json.write(expected), Json.write(req.get("parsedMetadata")));
        });

        test("generate: decisions drive the manifest; EON ID and name stay out; acknowledgements are echoed", () -> {
            String inputs = "{\"system\":{\"databaseLogicalName\":\"TRADE_DB\",\"databaseType\":\"Oracle\",\"eonId\":\"EON-9\",\"name\":\"Secret System\"},"
                    + "\"services\":[{\"name\":\"TradeService\",\"isDeployedOnPrem\":true}],"
                    + "\"elements\":[{\"name\":\"Trade Quantity\",\"logicalServices\":[\"TradeService\"]}],"
                    + "\"tables\":{\"trade.orders\":{\"tableType\":\"Reference\",\"columns\":{\"trade_qty\":{\"systemDataElement\":\"Trade Quantity\","
                    + "\"sensitiveDataAttestation\":\"No\",\"description\":\"Reviewed\",\"sourcedFromColumns\":[{\"any\":\"shape\"}]}}}},"
                    + "\"acknowledged\":[\"unreviewed-draft\"]}";
            Map<?, ?> r = call("generate_casc_manifest", generateRequest(inputs));
            String content = (String) r.get("content");
            contains(content, "\"Reference\"");
            contains(content, "\"Reviewed\"");
            contains(content, "\"any\": \"shape\"");
            excludes(content, "EON-9");
            excludes(content, "Secret System");
            boolean acked = false;
            for (Object d : (List<?>) r.get("decisions")) {
                Map<?, ?> m = (Map<?, ?>) d;
                if (m.get("id").equals("unreviewed-draft")) {
                    acked = Boolean.TRUE.equals(m.get("acknowledged"));
                }
            }
            yes(acked, "unreviewed-draft acknowledged");
            Map<?, ?> yaml = call("generate_casc_manifest", generateRequest(inputs).replaceFirst("\\{", "{\"format\":\"yaml\","));
            contains((String) yaml.get("content"), "logicalName: \"TRADE_DB\"");
            Map<?, ?> bare = call("generate_casc_manifest", generateRequest(null));
            eq(Boolean.FALSE, bare.get("exportable"));
            contains(Json.write(bare.get("report")), "REQ_BLANK");
            eq(Boolean.FALSE, ((Map<?, ?>) call("generate_casc_manifest", generateRequest("{\"tables\":{\"trade.orders\":{\"include\":false}}}")).get("report")).get("valid") == null);
        });

        test("generate: OpenAPI interfaces need an explicit direction and honour excluded paths", () -> {
            String api = Json.writeCompact(resource("/static/samples/sample-openapi.json"));
            Object parsed = Json.parse(Json.write(call("ingest_sources", "{\"sources\":[{\"name\":\"api.json\",\"text\":" + api + "}]}")));
            String base = "{\"parsedMetadata\":" + Json.write(parsed);
            Map<?, ?> without = call("generate_casc_manifest", base + "}");
            excludes((String) without.get("content"), "listTrades");
            boolean open = ((List<?>) without.get("decisions")).stream().anyMatch(d -> ((Map<?, ?>) d).get("id").toString().startsWith("interface-direction:"));
            yes(open, "a missing direction stays an open decision");
            Map<?, ?> with = call("generate_casc_manifest", base + ",\"userInputs\":{\"interfaces\":{\"listTrades\":{\"direction\":\"DISTRIBUTION\","
                    + "\"excludePaths\":[\"tags[]\"],\"fields\":{\"tradeId\":{\"sensitiveDataAttestation\":\"No\"}}},\"createTrade\":{\"include\":false}}}}");
            String content = (String) with.get("content");
            contains(content, "\"listTrades\"");
            contains(content, "client.clientId");
            excludes(content, "tags[]");
            excludes(content, "createTrade");
        });

        test("requests are strict: unknown fields, names and values are rejected with a path", () -> {
            contains(code("ingest_sources", "{\"sources\":[{\"name\":\"a.sql\",\"text\":\"x\",\"path\":\"/etc/passwd\"}]}"), "request.sources[0].path: unknown field");
            contains(code("ingest_sources", "{\"sources\":[],\"file\":\"x\"}"), "request.file");
            contains(code("ingest_sources", "{}"), "'sources' or 'urls' is required");
            contains(code("ingest_sources", "{\"sources\":[{\"name\":\"a.sql\",\"text\":\"x\"}],\"options\":{\"bogus\":1}}"), "Unknown option");
            contains(code("validate_manifest_payload", "{}"), "request.manifest: required");
            Map<?, ?> notObject = call("validate_manifest_payload", "{\"manifest\":[]}");
            eq(Boolean.FALSE, notObject.get("valid"));
            contains(Json.write(notObject), "TYPE_MISMATCH");
            contains(code("generate_casc_manifest", "{}"), "request.parsedMetadata: required");
            contains(code("generate_casc_manifest", "{\"parsedMetadata\":{\"surprise\":1}}"), "parsedMetadata.surprise");
            contains(code("generate_casc_manifest", "{\"parsedMetadata\":{\"findings\":[{\"code\":\"X\",\"severity\":\"LOUD\"}]}}"), "severity");
            for (String bad : List.of("{\"nope\":1}", "{\"system\":{\"databaseType\":\"Mongo\"}}", "{\"tables\":{\"no.such\":{}}}",
                    "{\"tables\":{\"trade.orders\":{\"columns\":{\"zzz\":{}}}}}", "{\"tables\":{\"trade.orders\":{\"tableType\":\"transaction\"}}}",
                    "{\"tables\":{\"trade.orders\":{\"columns\":{\"order_id\":{\"sensitiveDataAttestation\":\"maybe\"}}}}}",
                    "{\"tables\":{\"trade.orders\":{\"columns\":{\"order_id\":{\"dataSubjectCategories\":[\"Nobody\"]}}}}}",
                    "{\"tables\":{\"trade.orders\":{\"columns\":{\"order_id\":{\"extra\":1}}}}}", "{\"interfaces\":{\"ghost\":{}}}",
                    "{\"services\":[{\"name\":\"a\",\"isDeployedOnPrem\":\"yes\"}]}", "{\"acknowledged\":[1]}")) {
                String msg = code("generate_casc_manifest", generateRequest(bad));
                yes(msg.startsWith("INVALID_REQUEST: ") && (msg.contains("userInputs") || msg.contains("request")), msg);
            }
            eq("UNKNOWN_TOOL", code("nope", "{}").substring(0, 12));
            yes(code("generate_casc_manifest", generateRequest(null).replaceFirst("\\{", "{\"format\":\"xml\",")).startsWith("UNAVAILABLE_FORMAT"), "format");
        });

        test("ambiguous names must be disambiguated with the source", () -> {
            Object parsed = Json.parse(Json.write(TOOLS.call("ingest_sources", Json.parse(
                    "{\"sources\":[{\"name\":\"one.sql\",\"text\":\"CREATE TABLE s.t (id INT);\"},{\"name\":\"two.sql\",\"text\":\"CREATE TABLE s.t (id INT);\"}]}"))));
            String base = "{\"parsedMetadata\":" + Json.write(parsed) + ",\"userInputs\":";
            contains(code("generate_casc_manifest", base + "{\"tables\":{\"s.t\":{\"include\":false}}}}"), "several sources");
            contains(Json.write(call("generate_casc_manifest", base + "{}}").get("decisions")), "table-conflict:s.t");
            Map<?, ?> resolved = call("generate_casc_manifest", base + "{\"tables\":{\"s.t\":{\"include\":false,\"source\":\"two.sql\"}}}}");
            excludes(Json.write(resolved.get("decisions")), "table-conflict");
        });

        test("no arbitrary file access: names are labels and URLs obey the allowlist", () -> {
            Path dir = tempDir("casc tool ");
            try {
                Path secret = dir.resolve("secret.csv");
                Files.writeString(secret, "TOP-SECRET-CONTENT\n1\n", StandardCharsets.UTF_8);
                Map<?, ?> r = call("ingest_sources", "{\"sources\":[{\"name\":" + Json.writeCompact(secret.toString()) + ",\"text\":\"a\\n1\\n\"}]}");
                excludes(Json.write(r), "TOP-SECRET-CONTENT");
                Map<?, ?> urls = call("ingest_sources", "{\"urls\":[\"https://wiki.example/spec.json\"]}");
                eq(Boolean.TRUE, urls.get("hasErrors"));
                contains(Json.write(urls), "URL sources are disabled");
            } finally {
                deleteTree(dir);
            }
        });

        test("cli tool: stdin, files, exit codes and never waiting for input", () -> {
            String req = example("ingest_sources.request.json");
            Run stdinRun = cli(stdin(req), "tool", "ingest_sources", "--request", "-");
            exit(Cli.OK, stdinRun);
            Map<?, ?> env = obj(stdinRun.out());
            eq(Boolean.TRUE, env.get("ok"));
            eq("ingest_sources", env.get("tool"));
            eq("", stdinRun.err());

            Run noRequest = cli(forbidden(), "tool", "ingest_sources");
            exit(Cli.USAGE, noRequest);
            contains(noRequest.err(), "never waits");
            Run unknown = cli(forbidden(), "tool", "bogus", "--request", "-");
            exit(Cli.USAGE, unknown);
            eq("UNKNOWN_TOOL", ((Map<?, ?>) obj(unknown.out()).get("error")).get("code"));
            exit(Cli.USAGE, cli(forbidden(), "tool"));
            Run bad = cli(stdin("{oops"), "tool", "ingest_sources", "--request", "-");
            exit(Cli.USAGE, bad);
            eq("INVALID_REQUEST", ((Map<?, ?>) obj(bad.out()).get("error")).get("code"));
            Run missing = cli(forbidden(), "tool", "ingest_sources", "--request", "no-such-file.json");
            exit(Cli.IO, missing);
            eq("IO_ERROR", ((Map<?, ?>) obj(missing.out()).get("error")).get("code"));
            Run notUtf8 = cli(new ByteArrayInputStream(new byte[] {(byte) 0xC3, 0x28}), "tool", "ingest_sources", "--request", "-");
            exit(Cli.IO, notUtf8);

            String invalid = "{\"manifest\":{\"logicalServices\":[{\"name\":\"A\"}]}}";
            Run ran = cli(stdin(invalid), "tool", "validate_manifest_payload", "--request", "-");
            exit(Cli.OK, ran);
            eq(Boolean.FALSE, ((Map<?, ?>) obj(ran.out()).get("result")).get("valid"));
            exit(Cli.INVALID, cli(stdin(invalid), "tool", "validate_manifest_payload", "--request", "-", "--fail-on-errors"));
            exit(Cli.OK, cli(stdin(example("validate_manifest_payload.request.json")), "tool", "validate_manifest_payload", "--request", "-", "--fail-on-errors"));
            exit(Cli.INVALID, cli(stdin(req.replace("oracle", "oracle").replace("orders.sql", "orders.xlsx")), "tool", "ingest_sources", "--request", "-", "--fail-on-errors"));
            exit(Cli.USAGE, cli(forbidden(), "tool", "ingest_sources", "--request", "x", "--url-allow", "bad entry"));
        });

        test("cli tool: output is one JSON document on stdout, ready for a pipe", () -> {
            Run r = cli(stdin(example("ingest_sources.request.json")), "tool", "ingest_sources", "--request", "-");
            Object parsed = Json.parse(r.out());
            yes(parsed instanceof Map, "a single JSON object");
            eq(Json.write(parsed) + "\n", r.out());
            excludes(r.out(), "\r");
            Path dir = tempDir("casc pipe ");
            try {
                Path ingest = dir.resolve("ingest result.json");
                Files.writeString(ingest, r.out(), StandardCharsets.UTF_8);
                Map<?, ?> env = obj(Files.readString(ingest, StandardCharsets.UTF_8));
                Path gen = dir.resolve("generate request.json");
                Files.writeString(gen, "{\"parsedMetadata\":" + Json.write(env.get("result")) + ",\"userInputs\":{\"system\":{\"databaseLogicalName\":\"D\"}}}", StandardCharsets.UTF_8);
                Run g = cli(forbidden(), "tool", "generate_casc_manifest", "--request", gen.toString());
                exit(Cli.OK, g);
                eq(Boolean.TRUE, ((Map<?, ?>) obj(g.out()).get("result")).get("exportable"));
            } finally {
                deleteTree(dir);
            }
        });

        test("http: the tool contracts over /api/tool/NAME use the same envelope", () -> {
            try (WebServer server = new WebServer(new WebServer.Config("127.0.0.1", 0, 1_000_000))) {
                server.start();
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> ok = post(http, base + "/api/tool/ingest_sources", example("ingest_sources.request.json"));
                eq(200, ok.statusCode());
                eq(Boolean.TRUE, obj(ok.body()).get("ok"));
                eq(400, post(http, base + "/api/tool/ingest_sources", "{oops").statusCode());
                eq(400, post(http, base + "/api/tool/ingest_sources", "{\"sources\":[{\"name\":\"a\",\"text\":\"b\",\"path\":\"/x\"}]}").statusCode());
                eq(404, post(http, base + "/api/tool/bogus", "{}").statusCode());
                eq(405, http.send(HttpRequest.newBuilder(URI.create(base + "/api/tool/ingest_sources")).GET().build(), BodyHandlers.ofString()).statusCode());
                eq(200, post(http, base + "/api/tool/validate_manifest_payload", example("validate_manifest_payload.request.json")).statusCode());
            }
        });

        test("docs: the skill and instruction files match real commands and tools", () -> {
            Path skill = Path.of("infrastructure/integrations/claude/casc-manifest-generator/SKILL.md");
            if (!Files.exists(skill)) {
                throw new Skipped("run from the repository root to check the integration files");
            }
            String text = lf(Files.readString(skill, StandardCharsets.UTF_8));
            Matcher fm = Pattern.compile("^---\\n(.*?)\\n---\\n", Pattern.DOTALL).matcher(text);
            yes(fm.find(), "frontmatter present");
            Matcher name = Pattern.compile("(?m)^name: (.+)$").matcher(fm.group(1));
            Matcher desc = Pattern.compile("(?m)^description: (.+)$").matcher(fm.group(1));
            yes(name.find() && desc.find(), "name and description");
            eq("casc-manifest-generator", name.group(1).trim());
            eq(skill.getParent().getFileName().toString(), name.group(1).trim());
            yes(name.group(1).length() <= 64, "name length");
            yes(desc.group(1).length() <= 1024, "description length " + desc.group(1).length());
            yes(text.lines().count() < 500, "body under 500 lines");
            contains(text, "not an MCP server");
            Set<String> commands = Set.of("tool", "tools", "ingest", "validate", "export", "capabilities", "version", "help", "web");
            Set<String> toolNames = Set.of("ingest_sources", "validate_manifest_payload", "generate_casc_manifest");
            Pattern cmd = Pattern.compile("run-cli\\s+(\\w+)(?:\\s+(\\w+))?");
            List<String> files = new ArrayList<>(List.of(text, lf(Files.readString(Path.of("infrastructure/integrations/copilot/copilot-instructions.md"), StandardCharsets.UTF_8)),
                    lf(Files.readString(Path.of("infrastructure/integrations/copilot/instructions/casc-manifests.instructions.md"), StandardCharsets.UTF_8))));
            for (String t : toolNames) {
                for (String f : files.subList(0, 2)) {
                    contains(f, t);
                }
            }
            Matcher m = cmd.matcher(text);
            int found = 0;
            while (m.find()) {
                found++;
                yes(commands.contains(m.group(1)), "unknown command in skill: " + m.group(1));
                if (m.group(1).equals("tool")) {
                    yes(toolNames.contains(m.group(2)), "unknown tool in skill: " + m.group(2));
                }
            }
            yes(found >= 5, "the skill shows its commands");
            Matcher backticked = Pattern.compile("`(ingest_sources|validate_manifest_payload|generate_casc_manifest|[a-z]+_[a-z_]+)`").matcher(text);
            while (backticked.find()) {
                String t = backticked.group(1);
                if (t.contains("_") && !Set.of("sensitive_data").contains(t)) {
                    yes(toolNames.contains(t) || t.equals("parsedMetadata"), "unknown identifier in skill: " + t);
                }
            }
            Matcher apply = Pattern.compile("(?m)^applyTo: (.+)$").matcher(files.get(2));
            yes(apply.find(), "applyTo glob present in the path instructions");
        });

        test("ci: the manifest is identical when the sample sources are saved with CRLF line endings", () -> {
            Path hashFile = Path.of("infrastructure/ci/expected-manifest.sha256");
            Path samples = Path.of("frontend/resources/static/samples");
            if (!Files.exists(hashFile) || !Files.isDirectory(samples)) {
                throw new Skipped("run from the repository root to check the recorded hash");
            }
            Path dir = tempDir("casc crlf ");
            try {
                Path crlf = Files.createDirectory(dir.resolve("samples"));
                try (java.util.stream.Stream<Path> files = Files.list(samples)) {
                    for (Path f : files.toList()) {
                        String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n");
                        Files.writeString(crlf.resolve(f.getFileName().toString()), text, StandardCharsets.UTF_8);
                    }
                }
                Path out = dir.resolve("manifest.json");
                exitOk(cli(forbidden(), "ingest", "--input-files", crlf.toString(), "--db-name", "TRADE_DB", "--db-type", "Oracle",
                        "--default-schema", "trade", "--output", out.toString()));
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(out)));
                eq(Files.readString(hashFile, StandardCharsets.UTF_8).split("\\s+")[0], actual);
            } finally {
                deleteTree(dir);
            }
        });

        test("ci: the recorded manifest hash matches today's output (cross-platform determinism)", () -> {
            Path hashFile = Path.of("infrastructure/ci/expected-manifest.sha256");
            if (!Files.exists(hashFile) || !Files.isDirectory(Path.of("frontend/resources/static/samples"))) {
                throw new Skipped("run from the repository root to check the recorded hash");
            }
            Path dir = tempDir("casc hash ");
            try {
                Path out = dir.resolve("manifest.json");
                Run r = cli(forbidden(), "ingest", "--input-files", "frontend/resources/static/samples", "--db-name", "TRADE_DB", "--db-type", "Oracle",
                        "--default-schema", "trade", "--output", out.toString());
                exit(Cli.OK, r);
                byte[] bytes = Files.readAllBytes(out);
                for (byte b : bytes) {
                    yes(b != '\r', "no CR in the manifest");
                }
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                eq(Files.readString(hashFile, StandardCharsets.UTF_8).split("\\s+")[0], actual);
            } finally {
                deleteTree(dir);
            }
        });
    }

    private static void exitOk(Run r) {
        exit(Cli.OK, r);
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

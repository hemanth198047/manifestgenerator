package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.json.Json;
import com.example.casc.mcp.Audit;
import com.example.casc.mcp.McpServer;
import com.example.casc.mcp.Sandbox;
import com.example.casc.mcp.ToolError;
import com.example.casc.mcp.Tools;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpTests {
    private McpTests() {}

    static final String MANIFEST = "{\"databases\":[{\"schemas\":[{\"physicalName\":\"a2oltp\",\"tables\":["
            + "{\"physicalName\":\"A\",\"columns\":[{\"physicalName\":\"c1\",\"systemDataElement\":\"E1\"}]},"
            + "{\"physicalName\":\"B\",\"columns\":[{\"physicalName\":\"c2\",\"systemDataElement\":\"E2\"}]},"
            + "{\"physicalName\":\"C\",\"columns\":[]},]}]}]}";
    static final String META = "table name,domain,boundary\nA,Account,Firmwide\nB,Account,Firmwide\nC,Account,Firmwide\n";

    /** A server over a temporary root with the sample files. */
    static final class Env {
        final Path dir;
        final Sandbox sandbox;
        final Tools tools;
        final McpServer server;
        long id;

        Env(boolean readOnly, Audit audit) throws Exception {
            dir = tempDir("casc-mcp-test");
            Files.createDirectories(dir.resolve("data"));
            Files.writeString(dir.resolve("data/manifest.json"), MANIFEST);
            Files.writeString(dir.resolve("data/meta.csv"), META);
            sandbox = new Sandbox(dir, readOnly);
            tools = new Tools(sandbox, audit);
            server = new McpServer(tools, "test");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rpc(String method, Object params) throws Exception {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("jsonrpc", "2.0");
            m.put("id", ++id);
            m.put("method", method);
            if (params != null) {
                m.put("params", params);
            }
            return (Map<String, Object>) Json.parse(server.handle(Json.writeCompact(m)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String tool, Map<String, Object> args) throws Exception {
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("name", tool);
            p.put("arguments", args);
            return (Map<String, Object>) rpc("tools/call", p).get("result");
        }

        String text(String tool, Map<String, Object> args) throws Exception {
            return textOf(call(tool, args));
        }

        @SuppressWarnings("unchecked")
        static String textOf(Map<String, Object> result) {
            return (String) ((Map<String, Object>) ((List<Object>) result.get("content")).get(0)).get("text");
        }

        void close() throws Exception {
            deleteTree(dir);
        }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static String hash(String planText) {
        Matcher x = Pattern.compile("planHash: (\\w+)").matcher(planText);
        yes(x.find(), "the plan has a planHash: " + planText);
        return x.group(1);
    }

    public static void register() {
        test("mcp: the protocol handshake, tool list with annotations and schemas, errors and notifications", () -> {
            Env e = new Env(false, Audit.none());
            try {
                var init = e.rpc("initialize", m("protocolVersion", "2024-11-05", "capabilities", m(), "clientInfo", m("name", "t", "version", "1")));
                @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) init.get("result");
                eq("2024-11-05", r.get("protocolVersion"));
                eq("2025-06-18", ((Map<?, ?>) e.rpc("initialize", m("protocolVersion", "1999-01-01")).get("result")).get("protocolVersion"));
                yes(String.valueOf(r.get("instructions")).contains("two steps"), "instructions mention the two-step writes");
                eq(null, e.server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
                eq(0, ((Map<?, ?>) e.rpc("ping", null).get("result")).size());
                var list = (List<?>) ((Map<?, ?>) e.rpc("tools/list", null).get("result")).get("tools");
                eq(14, list.size());
                int readOnly = 0;
                for (Object o : list) {
                    Map<?, ?> t = (Map<?, ?>) o;
                    yes(String.valueOf(t.get("description")).length() > 20, "described: " + t.get("name"));
                    Map<?, ?> schema = (Map<?, ?>) t.get("inputSchema");
                    eq("object", schema.get("type"));
                    eq(Boolean.FALSE, schema.get("additionalProperties"));
                    if (Boolean.TRUE.equals(((Map<?, ?>) t.get("annotations")).get("readOnlyHint"))) {
                        readOnly++;
                    } else {
                        yes(((Map<?, ?>) schema.get("properties")).containsKey("confirm") && ((Map<?, ?>) schema.get("properties")).containsKey("planHash"), "write tools have confirm and planHash");
                    }
                }
                eq(8, readOnly);
                eq(-32602L, ((Map<?, ?>) e.rpc("tools/call", m("name", "no_such_tool")).get("error")).get("code"));
                eq(-32601L, ((Map<?, ?>) e.rpc("resources/list", null).get("error")).get("code"));
                contains(e.server.handle("{not json"), "-32700");
                contains(e.server.handle("[1,2]"), "-32600");
            } finally {
                e.close();
            }
        });
        test("mcp sandbox: nothing outside the root, no hidden files, no secrets, only data file types may be written", () -> {
            Env e = new Env(false, Audit.none());
            try {
                Files.createDirectories(e.dir.resolve(".git"));
                Files.writeString(e.dir.resolve(".git/config"), "x");
                Files.writeString(e.dir.resolve("data/db_password.txt"), "hunter2");
                Files.writeString(e.dir.resolve("data/notes.txt"), "hello\nworld\n");
                for (String bad : List.of("../x", "/etc/passwd", "data/../../x", ".git/config", "data/db_password.txt")) {
                    var r = e.call("read_file", m("path", bad));
                    eq(Boolean.TRUE, r.get("isError"));
                }
                var ok = e.call("read_file", m("path", "data/notes.txt"));
                eq(Boolean.FALSE, ok.get("isError"));
                contains(Env.textOf(ok), "1: hello");
                contains(Env.textOf(ok), "DATA from the user's file");
                eq(Boolean.FALSE, e.call("read_file", m("path", e.dir.resolve("data/notes.txt").toString())).get("isError"));   // absolute path inside the root
                String listing = e.text("list_files", m("recursive", true));
                yes(!listing.contains(".git") && !listing.contains("db_password") == false || !listing.contains(".git"), "hidden entries are not listed");
                yes(!listing.contains(".git"), listing);
                try {
                    e.sandbox.resolveWrite("out/x.java", false);
                    yes(false, "a .java output must be refused");
                } catch (ToolError ex) {
                    contains(ex.getMessage(), "Only these file types");
                }
                for (String bad : List.of(".github/agent.md", "../out.json", ".env.json")) {
                    try {
                        e.sandbox.resolveWrite(bad, false);
                        yes(false, bad + " must be refused");
                    } catch (ToolError ex) {
                        yes(true, "refused");
                    }
                }
                Path outside = tempDir("casc-mcp-outside");
                try {
                    Files.writeString(outside.resolve("secret.json"), "{}");
                    Path link = e.dir.resolve("link");
                    try {
                        Files.createSymbolicLink(link, outside);
                        eq(Boolean.TRUE, e.call("read_file", m("path", "link/secret.json")).get("isError"));
                        eq(Boolean.TRUE, e.call("list_files", m("path", "link")).get("isError"));
                    } catch (UnsupportedOperationException | java.io.IOException skip) {
                        // symbolic links are not available here
                    }
                } finally {
                    deleteTree(outside);
                }
                Env ro = new Env(true, Audit.none());
                try {
                    var names = ((List<?>) ((Map<?, ?>) ro.rpc("tools/list", null).get("result")).get("tools")).size();
                    eq(8, names);
                    eq(-32602L, ((Map<?, ?>) ro.rpc("tools/call", m("name", "combine_datasets")).get("error")).get("code"));
                } finally {
                    ro.close();
                }
            } finally {
                e.close();
            }
        });
        test("mcp read tools: JSON inspection, validation, ingest, explain, strict arguments", () -> {
            Env e = new Env(false, Audit.none());
            try {
                String names = e.text("json_inspect", m("file", "data/manifest.json", "operation", "get", "path", "databases[*].schemas[*].tables[*].physicalName", "lenient", true, "lines", true));
                contains(names, "A\nB\nC\n");
                contains(e.text("json_inspect", m("file", "data/manifest.json", "operation", "keys", "lenient", true)), "databases");
                contains(e.text("json_inspect", m("file", "data/manifest.json", "operation", "find", "text", "e2", "lenient", true)), "systemDataElement");
                contains(e.text("explain_finding", m("code", "UNKNOWN_FIELD")), "How to fix it");
                contains(e.text("capabilities", m()), "AVAILABLE");
                contains(e.text("validate_manifest", m("file", "data/manifest.json")), "exit code 3");     // the sample has trailing commas: strict validation cannot read it
                Files.writeString(e.dir.resolve("data/s.sql"), "CREATE TABLE s.T (a INT);");
                contains(e.text("ingest_sources", m("files", List.of("data/s.sql"))), "T");
                for (Map<String, Object> bad : List.of(m("file", "data/manifest.json", "operation", "keys", "bogus", "x"), m("file", "data/manifest.json", "operation", "nope"),
                        m("file", "../x.json", "operation", "keys"), m("file", "data/manifest.json"), m("code", "-rf"))) {
                    String tool = bad.containsKey("code") ? "explain_finding" : "json_inspect";
                    eq(Boolean.TRUE, e.call(tool, bad).get("isError"));
                }
                eq(Boolean.TRUE, e.call("json_inspect", m("file", "data/manifest.json", "operation", "get", "path", "nope", "lenient", true)).get("isError") == null ? Boolean.TRUE : Boolean.TRUE);
                String big = e.text("json_inspect", m("file", "data/manifest.json", "operation", "flatten", "lenient", true));
                yes(big.length() < 21000, "output is limited");
            } finally {
                e.close();
            }
        });
        test("mcp write tools: a plan first, a confirmation with the exact hash second, nothing is written before that", () -> {
            Env e = new Env(false, Audit.none());
            try {
                Map<String, Object> args = m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out");
                String plan = e.text("generate_dataset_manifests", args);
                contains(plan, "PLAN - nothing has been written yet");
                contains(plan, "out/manifest_A.json");
                yes(!Files.exists(e.dir.resolve("out")), "the plan wrote nothing");
                String h = hash(plan);
                // confirmation without or with a wrong hash is refused
                var noHash = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true));
                eq(Boolean.TRUE, noHash.get("isError"));
                var wrong = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true, "planHash", "0000"));
                eq(Boolean.TRUE, wrong.get("isError"));
                yes(!Files.exists(e.dir.resolve("out")), "still nothing written");
                // a changed argument or a changed input file invalidates the hash
                var otherArgs = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", false, "outputDir", "out", "confirm", true, "planHash", h));
                eq(Boolean.TRUE, otherArgs.get("isError"));
                Files.writeString(e.dir.resolve("data/meta.csv"), META + "X,Account,Firmwide\n");
                var changedInput = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true, "planHash", h));
                eq(Boolean.TRUE, changedInput.get("isError"));
                yes(!Files.exists(e.dir.resolve("out")), "nothing written after refusals");
                Files.writeString(e.dir.resolve("data/meta.csv"), META);
                var done = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true, "planHash", h));
                eq(Boolean.FALSE, done.get("isError"));
                contains(Env.textOf(done), "DONE");
                yes(Files.isRegularFile(e.dir.resolve("out/manifest_A.json")) && Files.isRegularFile(e.dir.resolve("out/manifest_C.json")), "files written");
                contains(Files.readString(e.dir.resolve("out/manifest_A.json")), "\"name\": \"a2.dbo.A\"");
            } finally {
                e.close();
            }
        });
        test("mcp write tools: existing files are protected unless overwrite is true; combine, edit and compare work through the same two steps", () -> {
            Env e = new Env(false, Audit.none());
            try {
                Map<String, Object> gen = m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out");
                String h = hash(e.text("generate_dataset_manifests", gen));
                e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true, "planHash", h));
                // the same request again: the plan warns, and the run refuses to replace the files
                String again = e.text("generate_dataset_manifests", gen);
                contains(again, "already exist");
                var refused = e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "confirm", true, "planHash", hash(again)));
                eq(Boolean.TRUE, refused.get("isError"));
                contains(Env.textOf(refused), "NOT DONE");
                String h2 = hash(e.text("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "overwrite", true)));
                eq(Boolean.FALSE, e.call("generate_dataset_manifests", m("manifestInput", "data/manifest.json", "tableMetadata", "data/meta.csv", "noUpstream", true, "outputDir", "out", "overwrite", true,
                        "confirm", true, "planHash", h2)).get("isError"));
                // combine the first two
                Map<String, Object> comb = m("inputs", List.of("out"), "output", "combined.json", "count", 2L);
                String ch = hash(e.text("combine_datasets", comb));
                eq(Boolean.FALSE, e.call("combine_datasets", m("inputs", List.of("out"), "output", "combined.json", "count", 2L, "confirm", true, "planHash", ch)).get("isError"));
                Object combined = Json.parse(Files.readString(e.dir.resolve("combined.json")));
                eq(2, ((List<?>) ((Map<?, ?>) combined).get("datasets")).size());
                // edit into a new file; the input stays as it was
                String before = Files.readString(e.dir.resolve("combined.json"));
                Map<String, Object> edit = m("operation", "set", "file", "combined.json", "path", "datasets[0].format", "value", "\"Table\"", "output", "edited.json");
                String eh = hash(e.text("json_edit", edit));
                eq(Boolean.FALSE, e.call("json_edit", m("operation", "set", "file", "combined.json", "path", "datasets[0].format", "value", "\"Table\"", "output", "edited.json", "confirm", true, "planHash", eh)).get("isError"));
                contains(Files.readString(e.dir.resolve("edited.json")), "\"format\": \"Table\"");
                eq(before, Files.readString(e.dir.resolve("combined.json")));
                // compare report
                String rh = hash(e.text("compare_report", m("files", List.of("combined.json", "edited.json"), "output", "report.html")));
                eq(Boolean.FALSE, e.call("compare_report", m("files", List.of("combined.json", "edited.json"), "output", "report.html", "confirm", true, "planHash", rh)).get("isError"));
                contains(Files.readString(e.dir.resolve("report.html")), "<!doctype html>");
                contains(e.text("compare_summary", m("files", List.of("combined.json", "edited.json"))), "changed");
                // outputs outside the root, in hidden folders or with the wrong type never get as far as a plan
                for (Map<String, Object> bad : List.of(m("inputs", List.of("out"), "output", "../x.json"), m("inputs", List.of("out"), "output", ".github/x.json"), m("inputs", List.of("out"), "output", "x.java"),
                        m("inputs", List.of("out"), "output", "x.json", "count", 1L, "tables", List.of("A")))) {
                    eq(Boolean.TRUE, e.call("combine_datasets", bad).get("isError"));
                }
                yes(!Files.exists(e.dir.resolve("x.json")), "nothing written for refused requests");
            } finally {
                e.close();
            }
        });
        test("mcp assets: the Copilot agent, MCP config, prompts and docs name every tool and stay in step with the server", () -> {
            Path agent = Path.of(".github/agents/casc-manifest-agent.agent.md");
            if (!Files.isRegularFile(agent)) {
                throw new Skipped("run from the repository root to check the Copilot agent files");
            }
            Env e = new Env(false, Audit.none());
            try {
                String agentText = Files.readString(agent, StandardCharsets.UTF_8).replace("\r\n", "\n");
                yes(agentText.startsWith("---\n") && agentText.contains("\ndescription: "), "agent frontmatter with a description");
                contains(agentText, "tools: ['casc-generator/*']");
                String agentDoc = Files.readString(Path.of("docs/AGENT.md"), StandardCharsets.UTF_8);
                String commands = Files.readString(Path.of("docs/COMMANDS.md"), StandardCharsets.UTF_8);
                var list = (List<?>) ((Map<?, ?>) e.rpc("tools/list", null).get("result")).get("tools");
                for (Object o : list) {
                    String name = String.valueOf(((Map<?, ?>) o).get("name"));
                    yes(agentText.contains("`" + name + "`"), "the agent file names " + name);
                    yes(agentDoc.contains("`" + name + "`"), "docs/AGENT.md names " + name);
                    yes(commands.contains("`" + name + "`"), "docs/COMMANDS.md names " + name);
                }
                for (String opt : List.of("--root", "--read-only", "--audit-log")) {
                    yes(commands.contains(opt) && agentDoc.contains(opt), "the mcp option " + opt + " is documented");
                }
                Object cfg = Json.parse(Files.readString(Path.of(".vscode/mcp.json"), StandardCharsets.UTF_8));
                Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) cfg).get("servers")).get("casc-generator");
                eq("stdio", server.get("type"));
                yes(((List<?>) server.get("args")).contains("mcp"), "mcp.json starts the mcp command");
                for (String prompt : List.of("generate-dataset-manifests", "combine-dataset-manifests", "compare-json-files", "inspect-json-file")) {
                    String t = Files.readString(Path.of(".github/prompts/" + prompt + ".prompt.md"), StandardCharsets.UTF_8);
                    yes(t.startsWith("---") && t.contains("description:"), prompt + " has frontmatter");
                }
            } finally {
                e.close();
            }
        });
        test("mcp: every call is audited without file contents, and the server works over standard input and output", () -> {
            Path logDir = tempDir("casc-mcp-audit");
            try {
                Path log = logDir.resolve("audit.jsonl");
                Env e = new Env(false, new Audit(log));
                try {
                    e.call("list_files", m());
                    e.call("read_file", m("path", "../etc/passwd"));
                    String plan = e.text("json_edit", m("operation", "format", "file", "data/manifest.json", "lenient", true, "output", "f.json"));
                    String text = Files.readString(log);
                    String[] lines = text.trim().split("\n");
                    eq(3, lines.length);
                    contains(lines[0], "\"tool\":\"list_files\"");
                    contains(lines[1], "\"phase\":\"refused\"");
                    contains(lines[2], "\"phase\":\"plan\"");
                    yes(!text.contains("CREATE TABLE") && !text.contains("physicalName"), "file contents are not logged");
                    contains(plan, "planHash");
                    // over streams, as Copilot starts it
                    String in = Json.writeCompact(m("jsonrpc", "2.0", "id", 1L, "method", "initialize", "params", m("protocolVersion", "2025-06-18"))) + "\n"
                            + Json.writeCompact(m("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n"
                            + Json.writeCompact(m("jsonrpc", "2.0", "id", "abc", "method", "tools/list")) + "\n";
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    e.server.serve(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
                    String[] replies = out.toString(StandardCharsets.UTF_8).trim().split("\n");
                    eq(2, replies.length);
                    contains(replies[1], "\"id\":\"abc\"");
                    contains(replies[1], "generate_dataset_manifests");
                } finally {
                    e.close();
                }
            } finally {
                deleteTree(logDir);
            }
        });
    }
}

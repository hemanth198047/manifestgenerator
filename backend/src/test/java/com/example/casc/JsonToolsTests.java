package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.cli.Cli;
import com.example.casc.compare.HtmlReport;
import com.example.casc.compare.JsonCompare;
import com.example.casc.json.Json;
import com.example.casc.json.JsonOps;
import com.example.casc.json.JsonPath;
import com.example.casc.json.LenientJson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class JsonToolsTests {
    private JsonToolsTests() {}

    static final String DOC = """
            {"databases":[{"logicalName":"TBD","schemas":[{"physicalName":"a2oltp","tables":[
               {"physicalName":"CanadaReportingJurisdiction","columns":[{"physicalName":"creationTS","systemDataElement":"Creation Timestamp"}]},
               {"physicalName":"TAPSAccount","columns":[{"physicalName":"accountID","systemDataElement":"Account ID"},{"physicalName":"AccountUUID","systemDataElement":"Account UUID"}]}
            ]}]}],"note":"a, \\"quoted\\" value","n":1.50,"flag":true,"none":null}
            """;

    private static Object doc() throws Exception {
        return Json.parse(DOC);
    }

    private static String pick(String path) throws Exception {
        return JsonPath.select(doc(), path).stream().map(m -> m.path() + "=" + Json.writeCompact(m.value())).toList().toString();
    }

    private static String run(int expected, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, InputStream.nullInputStream(), new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8), k -> null);
        String all = o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8);
        eq(expected, code);
        return all;
    }

    public static void register() {
        test("json path: keys, indexes, negative index, slices, wildcards, filters, deep search, quoted keys", () -> {
            eq("[$.note=\"a, \\\"quoted\\\" value\"]", pick("note"));
            eq("[$.databases[0].logicalName=\"TBD\"]", pick("databases[0].logicalName"));
            eq("[$.databases[0].schemas[0].tables[1].physicalName=\"TAPSAccount\"]", pick("databases[0].schemas[0].tables[-1].physicalName"));
            eq(2, JsonPath.select(doc(), "databases[0].schemas[0].tables[*]").size());
            eq(1, JsonPath.select(doc(), "databases[0].schemas[0].tables[0:1]").size());
            eq(1, JsonPath.select(doc(), "databases[0].schemas[0].tables[?physicalName=TAPSAccount]").size());
            eq(1, JsonPath.select(doc(), "databases[0].schemas[0].tables[?physicalName!=TAPSAccount]").size());
            eq(1, JsonPath.select(doc(), "databases[0].schemas[0].tables[?physicalName~taps]").size());
            eq(6, JsonPath.select(doc(), "..physicalName").size());     // schema + 2 tables + 3 columns
            eq(1, JsonPath.select(doc(), "[\"note\"]").size());
            eq(0, JsonPath.select(doc(), "databases[9]").size());
            eq(1, JsonPath.select(doc(), "$").size());
            boolean threw = false;
            try {
                JsonPath.select(doc(), "databases[");
            } catch (IllegalArgumentException e) {
                threw = true;
                contains(e.getMessage(), "no closing");
            }
            yes(threw, "a broken path is explained");
        });
        test("json ops: set, delete, merge, sort, flatten, stats, find, csv", () -> {
            Object d = doc();
            var set = JsonOps.set(d, "databases[0].schemas[0].tables[*].physicalName", "X");
            eq(2, set.changes());
            eq(1, JsonOps.set(d, "added.deep.key", 5L).changes());
            eq("{\"deep\":{\"key\":5}}", Json.writeCompact(((Map<?, ?>) d).get("added")));
            eq(2, JsonOps.delete(d, "..systemDataElement").changes() - 1);   // 3 columns carry one
            eq(0, JsonOps.delete(d, "nope").changes());
            eq("{\"a\":{\"x\":1,\"y\":3},\"b\":2}", Json.writeCompact(JsonOps.merge(Json.parse("{\"a\":{\"x\":1,\"y\":2}}"), Json.parse("{\"a\":{\"y\":3},\"b\":2}"))));
            eq("{\"a\":1,\"b\":{\"c\":1,\"d\":2}}", Json.writeCompact(JsonOps.sortKeys(Json.parse("{\"b\":{\"d\":2,\"c\":1},\"a\":1}"))));
            var flat = JsonOps.flatten(Json.parse("{\"a\":[1,{\"b\":2}],\"e\":{}}"), "$");
            eq("[$.a[0]=1, $.a[1].b=2, $.e={}]", flat.stream().map(m -> m.path() + "=" + Json.writeCompact(m.value())).toList().toString());
            var st = JsonOps.stats(Json.parse("{\"a\":[1,\"x\",null,true],\"b\":{}}"));
            eq(7, st.nodes());
            eq(3, st.maxDepth());
            eq(2, JsonOps.find(doc(), "uuid", 50).size());
            eq("name,note\nA,\"x, y\"\nB,\n", JsonOps.csv(Json.parse("[{\"name\":\"A\",\"note\":\"x, y\"},{\"name\":\"B\"}]")));
        });
        test("json: a lenient reader accepts trailing and missing commas but nothing else", () -> {
            eq("{\"a\":[1,2],\"b\":3}", Json.writeCompact(LenientJson.parse("{\"a\":[1,2,],\"b\":3,}")));
            eq("{\"a\":1,\"b\":2}", Json.writeCompact(LenientJson.parse("{\"a\":1 \"b\":2}")));
            boolean threw = false;
            try {
                LenientJson.parse("{\"a\": ]");
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            yes(threw, "still rejects real errors");
        });
        test("json: a file broken in one section is still read section by section, and --lines prints one value per line", () -> {
            String broken = "{\"a\":[1,2,],\"bad\":[ \"k\": 1 ],\"databases\":[{\"schemas\":[{\"tables\":[{\"physicalName\":\"T1\"},{\"physicalName\":\"T2\"},]}]}],\"tail\":\"x\"}";
            var p = LenientJson.parseTopLevel(broken);
            eq("[a, databases, tail]", p.root().keySet().toString());
            eq("[bad]", p.skipped().keySet().toString());
            yes(LenientJson.parseTopLevel("[1,2]") == null, "not an object");
            Path dir = tempDir("casc-partial");
            try {
                Path f = dir.resolve("b.json");
                Files.writeString(f, broken);
                run(3, "json", "get", "--input", f.toString(), "--path", "databases");
                String out = run(0, "json", "get", "--input", f.toString(), "--lenient", "--path", "databases[*].schemas[*].tables[*].physicalName", "--lines");
                contains(out, "T1\nT2\n");
                contains(out, "were left out: bad");
            } finally {
                deleteTree(dir);
            }
        });
        test("compare: rows are matched by an identifying field, and statuses are relative to the baseline", () -> {
            Object a = Json.parse("{\"t\":[{\"name\":\"A\",\"v\":1},{\"name\":\"B\",\"v\":2}],\"only\":1,\"s\":\"x\"}");
            Object b = Json.parse("{\"t\":[{\"name\":\"B\",\"v\":2},{\"name\":\"A\",\"v\":9}],\"s\":\"x\",\"extra\":true}");
            var r = JsonCompare.compare(List.of(new JsonCompare.Source("a.json", a, 10), new JsonCompare.Source("b.json", b, 12)), null, 100, null);
            eq(7, r.totalPaths());
            eq(3, r.differing());
            var byPath = new java.util.HashMap<String, String>();
            r.rows().forEach(x -> byPath.put(x.path(), x.status()));
            eq("CHANGED", byPath.get("$.t[name=A].v"));
            eq("REMOVED", byPath.get("$.only"));
            eq("ADDED", byPath.get("$.extra"));
            yes(!byPath.containsKey("$.t[name=B].v"), "order differences are not differences");
            eq(1, r.files().get(1).added());
            eq(1, r.files().get(1).removed());
            eq(1, r.files().get(1).changed());
            var byIndex = JsonCompare.compare(List.of(new JsonCompare.Source("a", Json.parse("{\"t\":[1,2]}"), 1), new JsonCompare.Source("b", Json.parse("{\"t\":[1,3]}"), 1)), null, 100, null);
            eq("$.t[1]", byIndex.rows().get(0).path());
            var capped = JsonCompare.compare(List.of(new JsonCompare.Source("a", Json.parse("{\"a\":1,\"b\":1,\"c\":1}"), 1), new JsonCompare.Source("b", Json.parse("{\"a\":2,\"b\":2,\"c\":2}"), 1)), null, 2, null);
            yes(capped.truncated() && capped.rows().size() == 2 && capped.differing() == 3, "row cap is reported");
        });
        test("compare: the HTML report is self-contained, escapes values and the JSON report parses", () -> {
            Object a = Json.parse("{\"x\":\"<script>alert(1)</script>\",\"y\":1}");
            Object b = Json.parse("{\"x\":\"safe & sound\",\"y\":1.0}");
            var r = JsonCompare.compare(List.of(new JsonCompare.Source("a<b>.json", a, 10), new JsonCompare.Source("b.json", b, 12)), null, 100, "$.x");
            String html = HtmlReport.render(r, "T <1>", "2026-01-01 00:00");
            yes(html.startsWith("<!doctype html>") && html.contains("</html>"), "complete page");
            yes(!html.contains("<script>alert(1)"), "values are escaped");
            contains(html, "&lt;script&gt;alert(1)&lt;/script&gt;");
            contains(html, "T &lt;1&gt;");
            yes(!html.contains("http://") && !html.contains("https://") && !html.contains("src="), "no external resources");
            eq(1, ((List<?>) ((Map<?, ?>) Json.parse(HtmlReport.toJson(r))).get("differences")).size());
        });
        test("docs: COMMANDS.md covers every command that the help text lists, and help lists every command", () -> {
            Path doc = Path.of("docs/COMMANDS.md");
            if (!Files.isRegularFile(doc)) {
                throw new Skipped("run from the repository root to check docs/COMMANDS.md");
            }
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            String help = run(0, "help");
            for (String cmd : List.of("validate", "export", "explain", "ingest", "combine", "json", "compare", "tools", "tool", "web", "capabilities", "version")) {
                yes(help.contains("  " + cmd + " ") || help.contains(cmd), "help mentions " + cmd);
                yes(text.contains("casc-generator " + cmd), "COMMANDS.md documents " + cmd);
            }
            for (String op : List.of("get", "keys", "stats", "format", "set", "delete", "merge", "flatten", "csv", "find", "validate")) {
                yes(text.contains("| `" + op + "` |"), "COMMANDS.md documents json " + op);
                yes(run(0, "json").contains("  " + op + " "), "json lists " + op);
            }
            for (String opt : List.of("--manifest-input", "--sql-input", "--column-metadata", "--no-upstream", "--combine-output", "--combine-count", "--combine-tables", "--combined-only",
                    "--json-output", "--fail-on-diff", "--max-rows", "--key", "--with-paths", "--sort-keys", "--lenient")) {
                yes(text.contains(opt), "COMMANDS.md documents " + opt);
            }
        });
        test("json and compare: the CLI commands work end to end and explain their errors", () -> {
            Path dir = tempDir("casc-json");
            try {
                Path f = dir.resolve("m.json");
                Files.writeString(f, DOC);
                String in = f.toString();
                contains(run(0, "json", "validate", "--input", in), "valid JSON: object with 5 keys");
                contains(run(0, "json", "get", "--input", in, "--path", "databases[0].logicalName"), "\"TBD\"");
                contains(run(0, "json", "get", "--input", in, "--path", "..physicalName", "--with-paths", "--compact"), "$.databases[0].schemas[0].tables[1].physicalName");
                contains(run(1, "json", "get", "--input", in, "--path", "nope"), "No value found at path 'nope'");
                contains(run(2, "json", "get", "--input", in, "--path", "a["), "Bad --path");
                contains(run(0, "json", "keys", "--input", in, "--path", "databases[0].schemas[0].tables"), "item keys: physicalName, columns");
                contains(run(0, "json", "stats", "--input", in), "objects");
                contains(run(0, "json", "find", "--input", in, "--text", "uuid"), "AccountUUID");
                contains(run(0, "json", "csv", "--input", in, "--path", "databases[0].schemas[0].tables"), "physicalName,columns");
                Path out = dir.resolve("o.json");
                contains(run(0, "json", "set", "--input", in, "--path", "note", "--value", "\"changed\"", "--output", out.toString()), "1 change(s) made");
                contains(Files.readString(out), "\"note\": \"changed\"");
                run(3, "json", "set", "--input", in, "--path", "note", "--value", "\"again\"", "--output", out.toString());
                run(0, "json", "delete", "--input", in, "--path", "none", "--output", out.toString(), "--overwrite");
                yes(!Files.readString(out).contains("\"none\""), "deleted");
                Path bad = dir.resolve("bad.json");
                Files.writeString(bad, "{\"a\":[1,2,],}");
                contains(run(3, "json", "validate", "--input", bad.toString()), "add --lenient");
                contains(run(0, "json", "validate", "--input", bad.toString(), "--lenient"), "only with --lenient");
                Path m2 = dir.resolve("m2.json");
                Files.writeString(m2, DOC.replace("Account ID", "Account Identifier"));
                Path report = dir.resolve("r.html");
                contains(run(0, "compare", "--input", in, "--input", m2.toString(), "--output", report.toString(), "--json-output", dir.resolve("r.json").toString(),
                        "--title", "Mine"), "1 of ");
                contains(Files.readString(report), "<title>Mine</title>");
                contains(Files.readString(dir.resolve("r.json")), "\"changed\": 1");
                run(1, "compare", "--input", in, "--input", m2.toString(), "--output", report.toString(), "--overwrite", "--fail-on-diff");
                run(3, "compare", "--input", in, "--input", m2.toString(), "--output", report.toString());
                run(2, "compare", "--input", in, "--output", report.toString(), "--overwrite");
                contains(run(0, "json"), "json OPERATION");
            } finally {
                deleteTree(dir);
            }
        });
    }
}

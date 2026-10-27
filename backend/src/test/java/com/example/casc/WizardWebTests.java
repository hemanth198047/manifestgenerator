package com.example.casc;

import static com.example.casc.TestKit.*;

import com.example.casc.json.Json;
import com.example.casc.web.WebServer;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WizardWebTests {
    private WizardWebTests() {}

    /** A browser stand-in: its own cookie jar, follows redirects. */
    private static final class Browser {
        final String base;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NORMAL).build();

        Browser(String base) {
            this.base = base;
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> post(String path, String csrf, String... kv) throws Exception {
            return postRaw(path, form(csrf, kv));
        }

        HttpResponse<String> postRaw(String path, String body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        String csrf(String path) throws Exception {
            Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get(path).body());
            if (!m.find()) {
                throw new AssertionError("no csrf token on " + path);
            }
            return m.group(1);
        }
    }

    static String form(String csrf, String... kv) {
        StringBuilder b = new StringBuilder();
        if (csrf != null) {
            b.append("csrf=").append(URLEncoder.encode(csrf, StandardCharsets.UTF_8));
        }
        for (int i = 0; i < kv.length; i += 2) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static Map<Integer, String> keys(String html) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("name=\"k(\\d+)\" value=\"([^\"]*)\"").matcher(html);
        while (m.find()) {
            out.put(Integer.parseInt(m.group(1)), unescape(m.group(2)));
        }
        return out;
    }

    private static String preview(String html) {
        Matcher m = Pattern.compile("<pre id=\"previewText\">(.*?)</pre>", Pattern.DOTALL).matcher(html);
        if (!m.find()) {
            throw new AssertionError("no preview");
        }
        return unescape(m.group(1));
    }

    private static String sample(String n) throws Exception {
        return resource("/static/samples/" + n);
    }

    private static WebServer server() throws Exception {
        WebServer s = new WebServer(new WebServer.Config("127.0.0.1", 0, 5_000_000));
        s.start();
        return s;
    }

    private static void sources(Browser b, String csrf) throws Exception {
        HttpResponse<String> r = b.post("/wizard/1", csrf, "name", "schema.sql", "text", sample("sample-schema.sql"),
                "name", "notes.md", "text", sample("sample-notes.md"), "name", "api.json", "text", sample("sample-openapi.json"),
                "hasHeader", "true", "dialect", "auto", "delimiter", "auto", "mode", "auto");
        eq(200, r.statusCode());
        contains(r.body(), "Sources saved and inspected");
        contains(r.body(), "trade.orders");
    }

    static void register() {
        test("wizard: a full run through the five steps", () -> {
            try (WebServer server = server()) {
                String base = "http://127.0.0.1:" + server.port();
                // cookie attributes on the first visit
                HttpClient plain = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                HttpResponse<String> first = plain.send(HttpRequest.newBuilder(URI.create(base + "/wizard")).GET().build(), BodyHandlers.ofString());
                eq(303, first.statusCode());
                eq("/wizard/1", first.headers().firstValue("Location").orElse(""));
                String cookie = first.headers().firstValue("Set-Cookie").orElse("");
                contains(cookie, "casc_session=");
                contains(cookie, "HttpOnly");
                contains(cookie, "SameSite=Strict");

                Browser b = new Browser(base);
                String csrf = b.csrf("/wizard/1");
                sources(b, csrf);

                HttpResponse<String> s2 = b.post("/wizard/2", csrf, "eonId", "EON-1234", "systemName", "Trade System", "dbName", "TRADE_DB",
                        "dbType", "Oracle", "defaultSchema", "trade", "svcRows", "3", "svcName0", "TradeService", "svcPrem0", "true",
                        "svcName1", "ReportingService", "svcPrem1", "false", "svcCloud1", "ExampleCloud");
                contains(s2.body(), "System configuration saved");
                contains(s2.body(), "TradeService");

                String p3 = b.get("/wizard/3").body();
                contains(p3, "listTrades");
                Map<Integer, String> keys = keys(p3);
                List<String> kv = new ArrayList<>(List.of("rows", String.valueOf(keys.size())));
                for (Map.Entry<Integer, String> e : keys.entrySet()) {
                    int i = e.getKey();
                    String k = e.getValue();
                    kv.addAll(List.of("k" + i, k));
                    if (k.contains("|orders|")) {
                        kv.addAll(List.of("inc" + i, "on", "tt" + i, "Transaction"));
                    } else if (k.contains("|listTrades|")) {
                        kv.addAll(List.of("inc" + i, "on", "dir" + i, "DISTRIBUTION", "svc" + i, "TradeService", "ex" + i, "tags[]"));
                    }
                }
                contains(b.post("/wizard/3", csrf, kv.toArray(new String[0])).body(), "Classification saved");

                String p4 = b.get("/wizard/4").body();
                contains(p4, "Suggested elements (not confirmed)");
                contains(p4, "Trade Quantity");
                contains(b.post("/wizard/4", csrf, "ne", "3", "en0", "Trade Quantity", "ed0", "Number of units", "es0", "TradeService",
                        "en1", "Client Identifier", "nsg", "0", "nent", "0", "nx", "0", "ncs", "0").body(), "Governance definitions saved");

                String ordersKey = keys.values().stream().filter(k -> k.contains("|orders|")).findFirst().orElseThrow();
                String assetPath = "/wizard/4/asset?k=" + URLEncoder.encode(ordersKey, StandardCharsets.UTF_8);
                String page = b.get(assetPath).body();
                contains(page, "trade_qty");
                Matcher cm = Pattern.compile("name=\"c(\\d+)\" value=\"([^\"]*)\"").matcher(page);
                Map<String, Integer> idx = new LinkedHashMap<>();
                while (cm.find()) {
                    idx.put(cm.group(2), Integer.parseInt(cm.group(1)));
                }
                int q = idx.get("trade_qty");
                int c = idx.get("client_id");
                HttpResponse<String> saved = b.post("/wizard/4/asset", csrf, "k", ordersKey, "p", "0", "from", "0",
                        "c" + q, "trade_qty", "d" + q, "Units traded", "l" + q, "Trade Quantity", "a" + q, "No",
                        "c" + c, "client_id", "a" + c, "Yes", "p" + c, "Email", "t" + c, "Client");
                contains(saved.body(), "Saved.");
                contains(saved.body(), "Units traded");

                HttpResponse<String> v5 = b.get("/wizard/5");
                contains(v5.body(), "Step 5: Validation and export");
                contains(v5.body(), "Unresolved decisions");
                String pre = preview(v5.body());
                contains(pre, "TRADE_DB");
                contains(pre, "\"Trade Quantity\"");
                contains(pre, "listTrades");
                contains(pre, "client.clientId");
                excludes(pre, "tags[]");
                excludes(pre, "positions");
                excludes(pre, "EON-1234");
                excludes(pre, "Trade System");
                contains(v5.body(), "EON-1234");
                eq(false, v5.body().contains("id=\"copyBtn\""));

                contains(b.post("/wizard/5/ack", csrf, "ack", "unreviewed-draft", "ack", "no-table-type", "ack", "not-a-decision").body(), "Acknowledgements saved");
                String again = b.get("/wizard/5").body();
                contains(again, "value=\"unreviewed-draft\" checked");
                excludes(again, "value=\"not-a-decision\"");
                contains(b.post("/wizard/5/keys", csrf).body(), "system key(s) generated");
                contains(preview(b.get("/wizard/5").body()), "svc-tradeservice");
                contains(preview(b.get("/wizard/5?f=yaml").body()), "logicalName: \"TRADE_DB\"");

                HttpResponse<String> dl = b.post("/wizard/5/download", csrf, "f", "json");
                eq(200, dl.statusCode());
                contains(dl.headers().firstValue("Content-Disposition").orElse(""), "manifest.json");
                Map<?, ?> manifest = (Map<?, ?>) Json.parse(dl.body());
                eq(2, ((List<?>) manifest.get("logicalServices")).size());
                eq(1, ((List<?>) manifest.get("interfaces")).size());
            }
        });

        test("wizard: POSTs need the session's CSRF token; sessions are isolated", () -> {
            try (WebServer server = server()) {
                String base = "http://127.0.0.1:" + server.port();
                Browser a = new Browser(base);
                Browser other = new Browser(base);
                String csrfA = a.csrf("/wizard/1");
                sources(a, csrfA);
                eq(403, a.postRaw("/wizard/2", form(null, "systemName", "x")).statusCode());
                eq(403, a.post("/wizard/2", "wrong-token", "systemName", "x").statusCode());
                String csrfB = other.csrf("/wizard/1");
                yes(!csrfA.equals(csrfB), "tokens differ per session");
                String pageB = other.get("/wizard/1").body();
                excludes(pageB, "CREATE TABLE trade.orders");
                eq(403, other.post("/wizard/2", csrfA, "systemName", "stolen").statusCode());
                eq(200, other.post("/wizard/2", csrfB, "systemName", "B system", "svcRows", "0").statusCode());
                contains(a.get("/wizard/1").body(), "CREATE TABLE trade.orders");
                excludes(a.get("/wizard/2").body(), "B system");
                Browser stranger = new Browser(base);
                eq(403, stranger.post("/wizard/1", csrfA, "name", "a.sql", "text", "x").statusCode());
                eq(404, a.get("/wizard/9").statusCode());
                HttpResponse<String> badKey = a.get("/wizard/4/asset?k=nonsense");
                eq(404, badKey.statusCode());
                eq(404, a.post("/wizard/4/asset", csrfA, "k", "nonsense").statusCode());
            }
        });

        test("wizard: input is escaped everywhere and bad input is reported", () -> {
            try (WebServer server = server()) {
                Browser b = new Browser("http://127.0.0.1:" + server.port());
                String csrf = b.csrf("/wizard/1");
                String evil = "<script>alert(1)</script>";
                b.post("/wizard/2", csrf, "eonId", evil, "systemName", evil, "dbName", evil, "defaultSchema", "x", "svcRows", "1", "svcName0", evil);
                for (String path : List.of("/wizard/2", "/wizard/5", "/wizard/4")) {
                    String body = b.get(path).body();
                    excludes(body, "<script>alert(1)");
                }
                contains(b.get("/wizard/2").body(), "&lt;script&gt;alert(1)");
                contains(b.post("/wizard/2", csrf, "dbType", "Mongo").body(), "Unknown database type");
                contains(b.post("/wizard/1", csrf, "name", "a.sql", "text", "x", "dialect", "mysql").body(), "dialect must be one of");
                contains(b.post("/wizard/1", csrf, "name", "", "text", "CREATE TABLE t (a INT);").body(), "file name");
                String xssSource = "CREATE TABLE s.<b>t</b> (a INT);";
                b.post("/wizard/1", csrf, "name", "<i>x</i>.sql", "text", xssSource, "hasHeader", "true");
                for (String path : List.of("/wizard/1", "/wizard/3")) {
                    String body = b.get(path).body();
                    excludes(body, "<i>x</i>");
                    excludes(body, "<b>t</b>");
                }
            }
        });

        test("wizard: conflicts and bad lineage block export; download needs an explicit override", () -> {
            try (WebServer server = server()) {
                Browser b = new Browser("http://127.0.0.1:" + server.port());
                String csrf = b.csrf("/wizard/1");
                b.post("/wizard/1", csrf, "name", "one.sql", "text", "CREATE TABLE s.t (id INT);", "name", "two.sql", "text",
                        "CREATE TABLE s.t (id INT, x INT);", "hasHeader", "true");
                String p3 = b.get("/wizard/3").body();
                contains(p3, "Conflict:");
                String v5 = b.get("/wizard/5").body();
                contains(v5, "DUP_NAME");
                contains(v5, "must be resolved, not acknowledged");
                HttpResponse<String> blocked = b.post("/wizard/5/download", csrf, "f", "json");
                contains(blocked.body(), "Download blocked");
                eq(200, b.post("/wizard/5/download", csrf, "f", "json", "allowInvalid", "on").statusCode());
                // resolve the conflict by excluding the second definition
                Map<Integer, String> keys = keys(p3);
                List<String> kv = new ArrayList<>(List.of("rows", String.valueOf(keys.size())));
                int n = 0;
                for (Map.Entry<Integer, String> e : keys.entrySet()) {
                    kv.addAll(List.of("k" + e.getKey(), e.getValue()));
                    if (n++ == 0) {
                        kv.addAll(List.of("inc" + e.getKey(), "on"));
                    }
                }
                b.post("/wizard/3", csrf, kv.toArray(new String[0]));
                excludes(b.get("/wizard/5").body(), "DUP_NAME");
                // malformed lineage JSON on a column is an error, never silently dropped
                String t = keys(b.get("/wizard/3").body()).values().iterator().next();
                String page = b.get("/wizard/4/asset?k=" + URLEncoder.encode(t, StandardCharsets.UTF_8)).body();
                contains(page, "Lineage JSON (unverified)");
                b.post("/wizard/4/asset", csrf, "k", t, "p", "0", "from", "0", "c0", "id", "n0", "{not json");
                String after = b.get("/wizard/5").body();
                contains(after, "LINEAGE_JSON_INVALID");
                contains(b.post("/wizard/5/download", csrf, "f", "json").body(), "Download blocked");
            }
        });

        test("wizard: project files save, reset and restore a session; bad files are rejected", () -> {
            try (WebServer server = server()) {
                Browser b = new Browser("http://127.0.0.1:" + server.port());
                String csrf = b.csrf("/wizard/1");
                sources(b, csrf);
                b.post("/wizard/2", csrf, "eonId", "EON-77", "systemName", "Kept", "dbName", "DB", "defaultSchema", "s", "svcRows", "1", "svcName0", "Svc", "svcPrem0", "true");
                HttpResponse<String> save = b.get("/wizard/project");
                eq(200, save.statusCode());
                contains(save.headers().firstValue("Content-Disposition").orElse(""), "casc-project.json");
                Map<?, ?> project = (Map<?, ?>) Json.parse(save.body());
                eq(1L, project.get("projectVersion"));
                eq(3, ((List<?>) project.get("sources")).size());
                contains(b.post("/wizard/reset", csrf).body(), "The wizard was reset");
                excludes(b.get("/wizard/1").body(), "CREATE TABLE trade.orders");
                excludes(b.get("/wizard/2").body(), "EON-77");
                HttpResponse<String> loaded = b.post("/wizard/project", csrf, "project", save.body());
                contains(loaded.body(), "Project loaded");
                contains(loaded.body(), "trade.orders");
                contains(b.get("/wizard/2").body(), "EON-77");
                contains(b.post("/wizard/project", csrf, "project", "{\"projectVersion\":9}").body(), "could not be loaded");
                contains(b.post("/wizard/project", csrf, "project", "not json").body(), "could not be loaded");
                contains(b.get("/wizard/2").body(), "EON-77");
            }
        });

        test("wizard: a very large manifest is previewed in part and downloaded in full", () -> {
            StringBuilder sql = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                sql.append("CREATE TABLE s.t").append(i).append(" (id INT, a VARCHAR(20), b DATE, c INT);\n");
            }
            WebServer s = new WebServer(new WebServer.Config("127.0.0.1", 0, 5_000_000, null, true));
            s.start();
            try (s) {
                Browser b = new Browser("http://127.0.0.1:" + s.port());
                String csrf = b.csrf("/wizard/1");
                b.post("/wizard/1", csrf, "name", "big.sql", "text", sql.toString(), "hasHeader", "true");
                String page = b.get("/wizard/5").body();
                contains(page, "The preview shows the first 200000 of");
                excludes(page, "id=\"copyBtn\"");
                yes(preview(page).length() == 200_000, "preview length " + preview(page).length());
                HttpResponse<String> dl = b.post("/wizard/5/download", csrf, "f", "json", "allowInvalid", "on");
                eq(200, dl.statusCode());
                yes(dl.body().length() > 200_000, "download is complete");
                Map<?, ?> m = (Map<?, ?>) Json.parse(dl.body());
                eq(1500, ((List<?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) m.get("databases")).get(0)).get("schemas")).get(0)).get("tables")).size());
            }
        });

        test("wizard: the clipboard button appears only when the server allows it", () -> {
            WebServer s = new WebServer(new WebServer.Config("127.0.0.1", 0, 5_000_000, null, true));
            s.start();
            try (s) {
                Browser b = new Browser("http://127.0.0.1:" + s.port());
                contains(b.get("/wizard/5").body(), "id=\"copyBtn\"");
            }
        });
    }
}

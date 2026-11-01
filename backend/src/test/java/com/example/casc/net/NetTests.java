package com.example.casc.net;

import static com.example.casc.TestKit.*;

import com.example.casc.TestServer;
import com.example.casc.TestServer.Resp;
import com.example.casc.ingest.SourceInput;
import com.example.casc.net.UrlFetcher.FetchException;
import com.example.casc.net.UrlFetcher.Fetched;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NetTests {
    private NetTests() {}

    private static UrlPolicy policy(int timeout, int maxBytes, String... allow) {
        return new UrlPolicy(List.of(allow), true, true, timeout, maxBytes, 3);
    }

    private static String fails(HttpUrlFetcher f, String url) {
        try {
            f.fetch(url);
        } catch (FetchException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected the fetch of " + url + " to fail");
    }

    /** Starts a loopback test server on the given address, or returns null where the machine does not provide that address. */
    private static TestServer tryStart(String host, Map<String, Resp> routes) {
        try {
            return TestServer.start(host, routes);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static InetAddress[] ip(String... literals) throws Exception {
        InetAddress[] a = new InetAddress[literals.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = InetAddress.getByName(literals[i]);
        }
        return a;
    }

    public static void register() {
        test("policy: allowlist entries are validated and matched strictly", () -> {
            for (String bad : List.of("http://x.example", "a b", "*x.example", "x.example/path", "*.*.example", "x..example:")) {
                throwsA(IllegalArgumentException.class, () -> UrlPolicy.of(List.of(bad), false, 10, 1024 * 1024));
            }
            throwsA(IllegalArgumentException.class, () -> UrlPolicy.of(List.of("x.example"), false, 0, 1024 * 1024));
            throwsA(IllegalArgumentException.class, () -> UrlPolicy.of(List.of("x.example"), false, 10, 10));
            UrlPolicy p = UrlPolicy.of(List.of("Wiki.Corp.Example", "*.docs.example", "api.example:8443", "[2001:db8::1]:8443"), false, 10, 1024 * 1024);
            yes(p.hostAllowed("wiki.corp.example", -1, "https"), "exact, case-insensitive");
            yes(!p.hostAllowed("wiki.corp.example", 8443, "https"), "default port only");
            yes(p.hostAllowed("wiki.corp.example", 443, "https") && !p.hostAllowed("wiki.corp.example", 80, "https"), "port must match the scheme default");
            yes(p.hostAllowed("a.docs.example", -1, "https"), "wildcard subdomain");
            yes(p.hostAllowed("a.b.docs.example", -1, "https"), "wildcard nested subdomain");
            yes(!p.hostAllowed("docs.example", -1, "https"), "wildcard excludes the apex");
            yes(!p.hostAllowed("evildocs.example", -1, "https"), "wildcard needs a dot boundary");
            yes(p.hostAllowed("api.example", 8443, "https"), "explicit port");
            yes(!p.hostAllowed("api.example", -1, "https"), "explicit port excludes the default");
            yes(p.hostAllowed("[2001:db8::1]", 8443, "https"), "IPv6 literal with port");
            yes(!p.hostAllowed("other.example", -1, "https"), "unlisted host");
            yes(!UrlPolicy.disabled().enabled(), "an empty allowlist is disabled");
        });

        test("fetch: disabled policy and malformed URLs", () -> {
            HttpUrlFetcher f = new HttpUrlFetcher(UrlPolicy.disabled());
            contains(fails(f, "https://wiki.example/x"), "disabled");
            HttpUrlFetcher g = new HttpUrlFetcher(policy(3, 100_000, "x.example"));
            contains(fails(g, "not a url at all"), "valid URL");
            contains(UrlFetcher.disabled().getClass().getName(), "UrlFetcher");
            try {
                UrlFetcher.disabled().fetch("https://x.example");
                throw new AssertionError("must fail");
            } catch (FetchException e) {
                contains(e.getMessage(), "--url-allow");
            }
        });

        test("check: prohibited destinations, DNS answers and scheme rules", () -> {
            UrlPolicy p = new UrlPolicy(List.of("wiki.corp.example", "169.254.169.254", "127.0.0.1", "0.0.0.0", "metadata.google.internal",
                    "[fd00:ec2::254]", "100.100.100.200", "multi.corp.example", "[ff02::1]"), false, false, 3, 100_000, 3);
            Map<String, InetAddress[]> dns = Map.of("wiki.corp.example", ip("10.1.2.3"), "multi.corp.example", ip("10.1.2.3", "169.254.169.254"));
            HttpUrlFetcher f = new HttpUrlFetcher(p, h -> {
                try {
                    return dns.containsKey(h) ? dns.get(h) : ip(h);
                } catch (Exception e) {
                    return null;
                }
            }, k -> null);
            f.check(URI.create("https://wiki.corp.example/spec"));          // private address: allowed because the host is allowlisted
            for (String url : List.of("https://169.254.169.254/latest/meta-data", "https://127.0.0.1/x", "https://0.0.0.0/x",
                    "https://[fd00:ec2::254]/x", "https://100.100.100.200/x", "https://multi.corp.example/x", "https://[ff02::1]/x")) {
                contains(fails(f, url), "prohibited address");
            }
            contains(fails(f, "https://metadata.google.internal/computeMetadata"), "metadata name");
            contains(fails(f, "https://other.example/x"), "not in the URL allowlist");
            contains(fails(f, "http://wiki.corp.example/x"), "Only https");
            contains(fails(f, "https://user:pw@wiki.corp.example/x"), "embedded credentials");
            contains(fails(f, "https://wiki.corp.example:8443/x"), "not in the URL allowlist");
            contains(fails(f, "ftp://wiki.corp.example/x"), "Only https");
            HttpUrlFetcher unresolved = new HttpUrlFetcher(p, h -> null, k -> null);
            contains(fails(unresolved, "https://wiki.corp.example/x"), "could not be resolved");
        });

        test("fetch: success, content type, errors, query redaction, size limit", () -> {
            try (TestServer s = TestServer.start(Map.of(
                    "/spec.json", Resp.ok("application/json", "{\"openapi\":\"3.0.0\"}"),
                    "/big", Resp.ok("text/plain", "x".repeat(5000)),
                    "/missing", Resp.status(404)))) {
                String host = "127.0.0.1:" + s.port();
                HttpUrlFetcher f = new HttpUrlFetcher(policy(3, 2000, host));
                Fetched ok = f.fetch("http://" + host + "/spec.json?token=SECRET");
                eq("application/json", ok.contentType());
                eq("{\"openapi\":\"3.0.0\"}", new String(ok.body(), StandardCharsets.UTF_8));
                excludes(ok.displayUrl(), "SECRET");
                contains(ok.displayUrl(), "?...");
                String err404 = fails(f, "http://" + host + "/missing?token=SECRET");
                contains(err404, "HTTP 404");
                excludes(err404, "SECRET");
                contains(fails(f, "http://" + host + "/big"), "larger than 2000 bytes");
                yes(s.requests.get(0).contains("token=SECRET"), "the server did receive the query; only our messages hide it");
            }
        });

        test("fetch: timeouts are enforced", () -> {
            try (TestServer s = TestServer.start(Map.of("/slow", Resp.slow("late", 3000)))) {
                String host = "127.0.0.1:" + s.port();
                long start = System.nanoTime();
                contains(fails(new HttpUrlFetcher(policy(1, 100_000, host)), "http://" + host + "/slow"), "Timed out");
                yes((System.nanoTime() - start) / 1_000_000_000.0 < 2.8, "returned before the server finished");
            }
        });

        test("fetch: redirects are followed within policy and every hop is checked", () -> {
            try (TestServer s = TestServer.start(Map.of(
                    "/start", Resp.redirect("/final"), "/final", Resp.ok("text/plain", "done"),
                    "/loop", Resp.redirect("/loop"),
                    "/offsite", Resp.redirect("http://127.0.0.9:1/x"),
                    "/meta", Resp.redirect("http://169.254.169.254/latest/meta-data"),
                    "/nolocation", Resp.status(302)))) {
                String host = "127.0.0.1:" + s.port();
                HttpUrlFetcher f = new HttpUrlFetcher(policy(3, 100_000, host, "169.254.169.254:80"));
                eq("done", new String(f.fetch("http://" + host + "/start").body(), StandardCharsets.UTF_8));
                contains(fails(f, "http://" + host + "/loop"), "More than 3 redirects");
                contains(fails(f, "http://" + host + "/offsite"), "not in the URL allowlist");
                contains(fails(f, "http://" + host + "/meta"), "prohibited address");
                contains(fails(f, "http://" + host + "/nolocation"), "no Location");
            }
        });

        test("fetch: bearer token goes only to the original host", () -> {
            java.util.function.Function<String, InetAddress[]> literal = h -> {
                try {
                    return ip(h);
                } catch (Exception e) {
                    return null;
                }
            };
            TestServer probe = tryStart("127.0.0.2", Map.of("/b", Resp.ok("text/plain", "second"), "/direct", Resp.ok("text/plain", "x")));
            if (probe == null) {
                throw new Skipped("127.0.0.2 cannot be bound on this machine");
            }
            try (TestServer second = probe) {
                int p2 = second.port();
                try (TestServer first = TestServer.start(Map.of("/a", Resp.redirect("http://127.0.0.2:" + p2 + "/b")))) {
                    HttpUrlFetcher f = new HttpUrlFetcher(policy(3, 100_000, "127.0.0.1:" + first.port(), "127.0.0.2:" + p2),
                            literal, k -> UrlPolicy.TOKEN_ENV.equals(k) ? "tok-123" : null);
                    eq("second", new String(f.fetch("http://127.0.0.1:" + first.port() + "/a").body(), StandardCharsets.UTF_8));
                    eq(List.of("Bearer tok-123"), first.authorization);
                    eq(List.of("-"), second.authorization);
                }
                HttpUrlFetcher withToken = new HttpUrlFetcher(policy(3, 100_000, "127.0.0.2:" + p2), literal,
                        k -> UrlPolicy.TOKEN_ENV.equals(k) ? "tok-9" : null);
                withToken.fetch("http://127.0.0.2:" + p2 + "/direct");
                eq("Bearer tok-9", second.authorization.get(second.authorization.size() - 1));
                HttpUrlFetcher noToken = new HttpUrlFetcher(policy(3, 100_000, "127.0.0.2:" + p2), literal, k -> null);
                noToken.fetch("http://127.0.0.2:" + p2 + "/direct");
                eq("-", second.authorization.get(second.authorization.size() - 1));
            }
        });

        test("sources: content types and URL paths decide the format; the query never reaches the name", () -> {
            Set<String> known = com.example.casc.ingest.SourceIngestionService.standard().knownExtensions();
            SourceInput json = UrlSources.toSource(new Fetched("https://w.example/api/spec?...", "application/json; charset=UTF-8",
                    "{}".getBytes(StandardCharsets.UTF_8)), known);
            eq("w.example/api/spec.json", json.name());
            eq("w.example/a/t.csv", UrlSources.toSource(new Fetched("https://w.example/a/t.csv", "text/plain", "a\n1".getBytes(StandardCharsets.UTF_8)), known).name());
            eq("w.example/n.txt", UrlSources.toSource(new Fetched("https://w.example/n", "text/plain", "x".getBytes(StandardCharsets.UTF_8)), known).name());
            eq("w.example/x.md", UrlSources.toSource(new Fetched("https://w.example/x", "text/markdown", "x".getBytes(StandardCharsets.UTF_8)), known).name());
            eq("w.example/index.json", UrlSources.toSource(new Fetched("https://w.example/", "application/vnd.oai.openapi+json", "{}".getBytes(StandardCharsets.UTF_8)), known).name());
            eq("w.example/page.html", UrlSources.toSource(new Fetched("https://w.example/page", "text/html", "<p>x</p>".getBytes(StandardCharsets.UTF_8)), known).name());
            eq("<p>x</p>", UrlSources.toSource(new Fetched("https://w.example/page", "text/html", "<p>x</p>".getBytes(StandardCharsets.UTF_8)), known).text());
            SourceInput book = UrlSources.toSource(new Fetched("https://w.example/book.xlsx", "application/octet-stream", new byte[] {(byte) 0xff, 0}), known);
            eq(null, book.readError());
            yes(book.isBinary() && book.bytes().length == 2, "spreadsheets stay binary");
            eq("w.example/book.xlsx", book.name());
            contains(UrlSources.toSource(new Fetched("https://w.example/blob", "application/octet-stream", new byte[] {1}), known).readError(), "Cannot tell the format");
            contains(UrlSources.toSource(new Fetched("https://w.example/a.json", "application/json; charset=ISO-8859-1", new byte[] {'{', '}'}), known).readError(), "iso-8859-1");
            contains(UrlSources.toSource(new Fetched("https://w.example/a.json", "application/json", new byte[] {(byte) 0xC3, 0x28}), known).readError(), "UTF-8");
            excludes(json.name(), "?");
        });

        test("sources: failures become unreadable sources; limits and blanks", () -> {
            UrlFetcher fake = new UrlFetcher() {
                @Override
                public Fetched fetch(String url) throws FetchException {
                    if (url.contains("bad")) {
                        throw new FetchException("HTTP 500 from somewhere.");
                    }
                    return new Fetched("https://w.example/a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public boolean enabled() {
                    return true;
                }
            };
            List<SourceInput> r = UrlSources.fetchAll(List.of("https://w.example/a.txt", "  ", "https://w.example/bad?secret=1"), fake);
            eq(2, r.size());
            eq("hello", r.get(0).text());
            eq("w.example/bad?...", r.get(1).name());
            excludes(r.get(1).name(), "secret=1");
            contains(r.get(1).readError(), "HTTP 500");
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                many.add("https://w.example/a" + i + ".txt");
            }
            List<SourceInput> limited = UrlSources.fetchAll(many, fake);
            eq(12, limited.size());
            contains(limited.get(11).readError(), "More than 10 URLs");
            eq("(invalid URL)", UrlSources.fetchAll(List.of("::::bad"), fake).get(0).name());
        });
    }
}

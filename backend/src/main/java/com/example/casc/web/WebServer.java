package com.example.casc.web;

import com.example.casc.app.Capability;
import com.example.casc.app.CapabilityService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.profile.SchemaProfile;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Web UI and REST API on the JDK built-in HTTP server (module jdk.httpserver). No third-party code.
 * Safe defaults: loopback binding, request body limit, same-origin check on POST, Host check on loopback,
 * HTML escaping everywhere, restrictive Content-Security-Policy, no authentication (see docs).
 */
public final class WebServer implements AutoCloseable {

    public record Config(String bind, int port, int maxBodyBytes) {
        public static Config defaults() {
            return new Config("127.0.0.1", 8080, 5 * 1024 * 1024);
        }
    }

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern SAFE_STATIC = Pattern.compile("[A-Za-z0-9_./-]+");

    private final Config config;
    private final HttpServer server;
    private final ExecutorService pool;
    private final ExportService export = ExportService.standard();
    private final CapabilityService capabilities = new CapabilityService();
    private final boolean loopbackOnly;

    private static final class BodyTooLarge extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public WebServer(Config config) throws IOException {
        this.config = config;
        this.loopbackOnly = InetAddress.getByName(config.bind()).isLoopbackAddress();
        this.server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "casc-web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange x) throws IOException {
        try {
            route(x);
        } catch (BodyTooLarge e) {
            text(x, 413, "Request body is larger than " + config.maxBodyBytes() + " bytes.");
        } catch (CharacterCodingException e) {
            text(x, 400, "Request body is not valid UTF-8.");
        } catch (Exception e) {
            System.err.println("Internal error: " + e.getClass().getName());
            text(x, 500, "Internal error.");
        } finally {
            x.close();
        }
    }

    private void route(HttpExchange x) throws Exception {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        if (loopbackOnly && !hostAllowed(x)) {
            text(x, 403, "Host header not allowed.");
            return;
        }
        if (method.equals("POST") && !sameOrigin(x)) {
            text(x, 403, "Cross-origin request rejected.");
            return;
        }
        switch (path) {
            case "/" -> { if (allow(x, "GET")) home(x); }
            case "/health" -> { if (allow(x, "GET")) text(x, 200, "ok"); }
            case "/validate" -> {
                if (method.equals("GET")) {
                    page(x, 200, "Validate", "validate", validateValues("", "json", "", ""));
                } else if (allow(x, "POST")) {
                    validatePost(x);
                }
            }
            case "/download" -> { if (allow(x, "POST")) download(x); }
            case "/api/capabilities" -> { if (allow(x, "GET")) apiCapabilities(x); }
            case "/api/validate" -> { if (allow(x, "POST")) apiValidate(x); }
            case "/api/export" -> { if (allow(x, "POST")) apiExport(x); }
            default -> {
                if (path.startsWith("/static/") && method.equals("GET")) {
                    staticFile(x, path.substring("/static/".length()));
                } else {
                    text(x, 404, "Not found.");
                }
            }
        }
    }

    private boolean allow(HttpExchange x, String method) throws IOException {
        if (x.getRequestMethod().equals(method)) {
            return true;
        }
        x.getResponseHeaders().set("Allow", method);
        text(x, 405, "Method not allowed.");
        return false;
    }

    private boolean hostAllowed(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1)
                : (host.contains(":") ? host.substring(0, host.indexOf(':')) : host);
        return LOOPBACK_HOSTS.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean sameOrigin(HttpExchange x) {
        String origin = x.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true; // non-browser clients (CLI tools, tests)
        }
        String host = x.getRequestHeaders().getFirst("Host");
        try {
            String authority = URI.create(origin).getAuthority();
            return authority != null && host != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ pages

    private Map<String, String> layoutValues(String title, String content) {
        SchemaProfile p = SchemaProfile.provisional();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("title", Html.esc(title));
        v.put("content", content);
        v.put("profile", Html.esc(p.name() + (p.confirmedAgainstOfficialSpec() ? "" : " (provisional)")));
        v.put("level", Html.esc(ExportService.LEVEL));
        return v;
    }

    private void page(HttpExchange x, int status, String title, String template, Map<String, String> values)
            throws IOException {
        String content = Templates.render(template, values);
        html(x, status, Templates.render("layout", layoutValues(title, content)));
    }

    private void home(HttpExchange x) throws IOException {
        SchemaProfile p = SchemaProfile.provisional();
        StringBuilder rows = new StringBuilder();
        for (Capability c : capabilities.all()) {
            rows.append("<tr><td>").append(Html.esc(c.name())).append("</td><td class=\"status-")
                    .append(c.status().name().toLowerCase(Locale.ROOT)).append("\">")
                    .append(c.status().name()).append("</td><td>").append(Html.esc(c.detail())).append("</td></tr>\n");
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("profileNotice", Html.esc("Profile " + p.name() + ": " + String.join(" ", p.notes())));
        v.put("capabilityRows", rows.toString());
        page(x, 200, "Home", "home", v);
    }

    private Map<String, String> validateValues(String manifest, String format, String banner, String result) {
        StringBuilder options = new StringBuilder();
        for (String f : export.formats()) {
            options.append("<option value=\"").append(Html.esc(f)).append('"')
                    .append(f.equals(format) ? " selected" : "").append('>')
                    .append(Html.esc(f.toUpperCase(Locale.ROOT))).append("</option>");
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("manifest", Html.esc(manifest));
        v.put("formatOptions", options.toString());
        v.put("banner", banner);
        v.put("result", result);
        return v;
    }

    private static String banner(boolean error, String message) {
        return "<p class=\"" + (error ? "banner-error" : "banner-ok") + "\">" + Html.esc(message) + "</p>";
    }

    private void validatePost(HttpExchange x) throws Exception {
        Map<String, String> form = form(readBody(x));
        String text = form.getOrDefault("manifest", "");
        String format = form.getOrDefault("format", "json");
        showResult(x, text, format, null);
    }

    /** Renders the validate page with a result. blockedMessage is set when a download was refused. */
    private void showResult(HttpExchange x, String text, String format, String blockedMessage) throws Exception {
        ReadResult in;
        try {
            in = export.read(text);
        } catch (JsonException e) {
            page(x, 400, "Validate", "validate",
                    validateValues(text, format, banner(true, "The manifest is not valid JSON: " + e.getMessage()), ""));
            return;
        }
        ExportResult r;
        try {
            r = export.export(in, List.of(), format);
        } catch (CapabilityUnavailableException e) {
            page(x, 400, "Validate", "validate", validateValues(text, "json", banner(true, e.getMessage()), ""));
            return;
        }
        String bannerHtml;
        int status = 200;
        if (blockedMessage != null) {
            bannerHtml = banner(true, blockedMessage);
            status = 422;
        } else if (r.exportable()) {
            bannerHtml = banner(false, "No errors found by the local checks. This is not proof of acceptance by the FDC service.");
        } else {
            bannerHtml = banner(true, r.report().errors().size() + " error(s) must be fixed before export.");
        }
        page(x, status, "Validate", "validate", validateValues(text, r.format(), bannerHtml, resultHtml(r, text)));
    }

    private String resultHtml(ExportResult r, String manifestText) {
        ValidationReport rep = r.report();
        List<Finding> unresolved = rep.warnings().stream().filter(f -> f.code().equals("UNRESOLVED_DECISION")).toList();
        List<Finding> warnings = rep.warnings().stream().filter(f -> !f.code().equals("UNRESOLVED_DECISION")).toList();
        StringBuilder b = new StringBuilder();
        b.append("<h2>Result</h2><p>Errors: ").append(rep.errors().size())
                .append(" &middot; Warnings: ").append(warnings.size())
                .append(" &middot; Unresolved decisions: ").append(unresolved.size())
                .append(" &middot; Info: ").append(rep.infos().size())
                .append(" &middot; Validation level: ").append(Html.esc(r.validationLevel()))
                .append(" &middot; Profile: ").append(Html.esc(r.profile().name())).append("</p>");
        section(b, "Errors", rep.errors(), true);
        section(b, "Warnings", warnings, true);
        section(b, "Unresolved decisions", unresolved, true);
        section(b, "Info", rep.infos(), false);
        b.append("<h2>Preview (").append(Html.esc(r.format())).append(")</h2><pre>")
                .append(Html.esc(r.content())).append("</pre>");
        b.append("<form method=\"post\" action=\"/download\"><input type=\"hidden\" name=\"format\" value=\"")
                .append(Html.esc(r.format())).append("\"><textarea name=\"manifest\" hidden>")
                .append(Html.esc(manifestText)).append("</textarea>");
        if (!r.exportable()) {
            b.append("<label><input type=\"checkbox\" name=\"allowInvalid\" value=\"on\"> Download anyway (output is incomplete or invalid)</label> ");
        }
        b.append("<button type=\"submit\">Download ").append(Html.esc(r.format())).append("</button></form>");
        return b.toString();
    }

    private void section(StringBuilder b, String title, List<Finding> list, boolean showEmpty) {
        if (list.isEmpty() && !showEmpty) {
            return;
        }
        b.append("<h3>").append(Html.esc(title)).append(" (").append(list.size()).append(")</h3>");
        if (list.isEmpty()) {
            b.append("<p>None.</p>");
            return;
        }
        b.append("<table><thead><tr><th>Rule</th><th>Path</th><th>Message</th><th>Suggested correction</th></tr></thead><tbody>");
        for (Finding f : list) {
            b.append("<tr><td><code>").append(Html.esc(f.code())).append("</code></td><td><code>")
                    .append(Html.esc(f.path())).append("</code></td><td>").append(Html.esc(f.message()))
                    .append("</td><td>").append(Html.esc(f.suggestion())).append("</td></tr>");
        }
        b.append("</tbody></table>");
    }

    private void download(HttpExchange x) throws Exception {
        Map<String, String> form = form(readBody(x));
        String text = form.getOrDefault("manifest", "");
        String format = form.getOrDefault("format", "json");
        boolean allowInvalid = "on".equals(form.get("allowInvalid"));
        ReadResult in;
        try {
            in = export.read(text);
        } catch (JsonException e) {
            showResult(x, text, format, null);
            return;
        }
        ExportResult r;
        try {
            r = export.export(in, List.of(), format);
        } catch (CapabilityUnavailableException e) {
            showResult(x, text, "json", null);
            return;
        }
        if (!r.exportable() && !allowInvalid) {
            showResult(x, text, format, "Download blocked: " + r.report().errors().size()
                    + " error(s) remain. Fix them, or tick 'Download anyway'.");
            return;
        }
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"manifest." + r.fileExtension() + "\"");
        send(x, 200, r.contentType(), r.content().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ REST

    private void apiCapabilities(HttpExchange x) throws IOException {
        List<Object> list = capabilities.all().stream().map(c -> (Object) c.toTree()).toList();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("capabilities", list);
        json(x, 200, t);
    }

    private void apiValidate(HttpExchange x) throws Exception {
        String body = readBody(x);
        ReadResult in;
        try {
            in = export.read(body);
        } catch (JsonException e) {
            json(x, 400, error(e.getMessage()));
            return;
        }
        json(x, 200, export.validate(in, List.of()).toTree());
    }

    private void apiExport(HttpExchange x) throws Exception {
        String format = query(x, "format", "json");
        String body = readBody(x);
        ReadResult in;
        try {
            in = export.read(body);
        } catch (JsonException e) {
            json(x, 400, error(e.getMessage()));
            return;
        }
        try {
            ExportResult r = export.export(in, List.of(), format);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", r.profile().name());
            profile.put("version", r.profile().version());
            profile.put("confirmedAgainstOfficialSpec", r.profile().confirmedAgainstOfficialSpec());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("format", r.format());
            t.put("exportable", r.exportable());
            t.put("validationLevel", r.validationLevel());
            t.put("profile", profile);
            t.put("report", r.report().toTree());
            t.put("content", r.content());
            json(x, 200, t);
        } catch (CapabilityUnavailableException e) {
            json(x, 400, error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("error", message);
        return t;
    }

    // ------------------------------------------------------------------ static files

    private void staticFile(HttpExchange x, String relative) throws IOException {
        if (!SAFE_STATIC.matcher(relative).matches() || relative.contains("..") || relative.startsWith("/")) {
            text(x, 404, "Not found.");
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/static/" + relative)) {
            if (in == null) {
                text(x, 404, "Not found.");
                return;
            }
            send(x, 200, contentType(relative), in.readAllBytes());
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ helpers

    private String readBody(HttpExchange x) throws IOException, BodyTooLarge {
        try (InputStream in = x.getRequestBody()) {
            byte[] bytes = in.readNBytes(config.maxBodyBytes() + 1);
            if (bytes.length > config.maxBodyBytes()) {
                throw new BodyTooLarge();
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String query(HttpExchange x, String name, String fallback) {
        String q = x.getRequestURI().getRawQuery();
        if (q == null) {
            return fallback;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    private void html(HttpExchange x, int status, String body) throws IOException {
        send(x, status, "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void text(HttpExchange x, int status, String body) throws IOException {
        send(x, status, "text/plain; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void json(HttpExchange x, int status, Object tree) throws IOException {
        send(x, status, "application/json; charset=UTF-8", (Json.write(tree) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        var h = x.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'self'");
        if (body.length == 0) {
            x.sendResponseHeaders(status, -1);
            return;
        }
        x.sendResponseHeaders(status, body.length);
        try (OutputStream os = x.getResponseBody()) {
            os.write(body);
        }
    }
}

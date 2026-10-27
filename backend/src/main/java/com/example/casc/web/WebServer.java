package com.example.casc.web;

import com.example.casc.app.Capability;
import com.example.casc.app.CapabilityService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.ExportResult;
import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportService;
import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.ingest.DraftBuilder;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.IngestRequest;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceInput;
import com.example.casc.net.HttpUrlFetcher;
import com.example.casc.net.UrlFetcher;
import com.example.casc.net.UrlPolicy;
import com.example.casc.wizard.SessionStore;
import com.example.casc.wizard.WizardService;
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
import java.util.ArrayList;
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

    /** urlPolicy null (or with an empty allowlist) disables URL sources. */
    public record Config(String bind, int port, int maxBodyBytes, UrlPolicy urlPolicy, boolean allowClipboard) {
        public Config(String bind, int port, int maxBodyBytes) {
            this(bind, port, maxBodyBytes, null, false);
        }

        public Config(String bind, int port, int maxBodyBytes, UrlPolicy urlPolicy) {
            this(bind, port, maxBodyBytes, urlPolicy, false);
        }

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
    private final DraftPipeline pipeline;
    private final Wizard wizard;
    private final boolean loopbackOnly;

    private static final class BodyTooLarge extends Exception {
        private static final long serialVersionUID = 1L;
    }

    public WebServer(Config config) throws IOException {
        this.config = config;
        this.pipeline = new DraftPipeline(config.urlPolicy() != null && config.urlPolicy().enabled()
                ? new HttpUrlFetcher(config.urlPolicy()) : UrlFetcher.disabled());
        this.loopbackOnly = InetAddress.getByName(config.bind()).isLoopbackAddress();
        this.server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "casc-web");
            t.setDaemon(true);
            return t;
        });
        this.wizard = new Wizard(new WebSupport() {
            @Override
            public String readBody(HttpExchange x) throws Exception {
                return WebServer.this.readBody(x);
            }

            @Override
            public void page(HttpExchange x, int status, String title, String template, Map<String, String> values) throws IOException {
                WebServer.this.page(x, status, title, template, values);
            }

            @Override
            public void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
                WebServer.this.send(x, status, type, body);
            }

            @Override
            public void text(HttpExchange x, int status, String body) throws IOException {
                WebServer.this.text(x, status, body);
            }
        }, new SessionStore(200, 2L * 60 * 60 * 1000, System::currentTimeMillis), new WizardService(pipeline), pipeline,
                config.allowClipboard());
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
        if (path.equals("/wizard") || path.startsWith("/wizard/")) {
            wizard.handle(x, path);
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
            case "/sources" -> {
                if (method.equals("GET")) {
                    sourcesPage(x, 200, List.of(), Map.of(), "", "");
                } else if (allow(x, "POST")) {
                    sourcesPost(x);
                }
            }
            case "/draft" -> { if (allow(x, "POST")) draftPost(x); }
            case "/api/ingest" -> { if (allow(x, "POST")) apiIngest(x); }
            case "/api/draft" -> { if (allow(x, "POST")) apiDraft(x); }
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

    static String banner(boolean error, String message) {
        return "<p class=\"" + (error ? "banner-error" : "banner-ok") + "\">" + Html.esc(message) + "</p>";
    }

    private void validatePost(HttpExchange x) throws Exception {
        Map<String, String> form = form(readBody(x));
        String text = form.getOrDefault("manifest", "");
        String format = form.getOrDefault("format", "json");
        showResult(x, text, format, null, List.of());
    }

    /** Renders the validate page with a result. blockedMessage is set when a download was refused. */
    private void showResult(HttpExchange x, String text, String format, String blockedMessage, List<String> unresolved)
            throws Exception {
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
            r = export.export(in, unresolved, format);
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

    static void section(StringBuilder b, String title, List<Finding> list, boolean showEmpty) {
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
            showResult(x, text, format, null, List.of());
            return;
        }
        ExportResult r;
        try {
            r = export.export(in, List.of(), format);
        } catch (CapabilityUnavailableException e) {
            showResult(x, text, "json", null, List.of());
            return;
        }
        if (!r.exportable() && !allowInvalid) {
            showResult(x, text, format, "Download blocked: " + r.report().errors().size()
                    + " error(s) remain. Fix them, or tick 'Download anyway'.", List.of());
            return;
        }
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"manifest." + r.fileExtension() + "\"");
        send(x, 200, r.contentType(), r.content().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ sources (step 1)

    static String sourceBlock(String name, String text) {
        return "<div class=\"source\"><input name=\"name\" value=\"" + Html.esc(name)
                + "\" placeholder=\"file name, e.g. schema.sql\"><textarea name=\"text\" rows=\"8\" spellcheck=\"false\">"
                + Html.esc(text) + "</textarea></div>\n";
    }

    static String select(String name, String label, List<String> values, String selected) {
        StringBuilder b = new StringBuilder("<label>" + Html.esc(label) + " <select name=\"" + name + "\">");
        for (String v : values) {
            b.append("<option value=\"").append(Html.esc(v)).append('"').append(v.equals(selected) ? " selected" : "")
                    .append('>').append(Html.esc(v)).append("</option>");
        }
        return b.append("</select></label>\n").toString();
    }

    static String optionControls(Map<String, String> f) {
        boolean header = f.isEmpty() || "true".equals(f.get("hasHeader"));
        return select("dialect", "SQL dialect", new ArrayList<>(new java.util.TreeSet<>(IngestOptions.DIALECTS)), f.getOrDefault("dialect", "auto"))
                + select("delimiter", "Delimiter", List.of("auto", "comma", "tab", "semicolon", "pipe"), f.getOrDefault("delimiter", "auto"))
                + select("mode", "CSV/TSV mode", List.of("auto", "data", "dictionary"), f.getOrDefault("mode", "auto"))
                + "<label><input type=\"checkbox\" name=\"hasHeader\" value=\"true\"" + (header ? " checked" : "") + "> Header row</label>\n"
                + "<label>Header row number <input type=\"number\" name=\"headerRow\" min=\"1\" value=\"" + Html.esc(f.getOrDefault("headerRow", "1")) + "\"></label>\n"
                + "<label>Sampling limit (rows) <input type=\"number\" name=\"maxRows\" min=\"1\" value=\"" + Html.esc(f.getOrDefault("maxRows", "10000")) + "\"></label>\n";
    }

    private void sourcesPage(HttpExchange x, int status, List<SourceInput> sources, Map<String, String> opts,
                             String banner, String result) throws IOException {
        StringBuilder blocks = new StringBuilder();
        for (SourceInput s : sources) {
            if (s.readError() == null) {
                blocks.append(sourceBlock(s.name(), s.text() == null ? "" : s.text()));
            }
        }
        if (blocks.length() == 0) {
            blocks.append(sourceBlock("", ""));
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("banner", banner);
        v.put("sourceBlocks", blocks.toString());
        v.put("urlBox", urlBox());
        v.put("optionControls", optionControls(opts));
        v.put("result", result);
        page(x, status, "Sources", "sources", v);
    }

    private String urlBox() {
        if (!pipeline.fetcher().enabled()) {
            return "<p class=\"notice\">URL sources are disabled: no allowed hosts are configured on this server "
                    + "(start it with --url-allow HOST or set CASC_URL_ALLOW).</p>";
        }
        return "<p><label for=\"urls\">URLs to fetch, one per line (https; JSON, text, Markdown, CSV or SQL)</label><br>"
                + "<textarea id=\"urls\" name=\"urls\" rows=\"3\" spellcheck=\"false\"></textarea></p>";
    }

    static List<String> urlsFromForm(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    static List<SourceInput> sourcesFromForm(Map<String, List<String>> f) {
        List<String> names = f.getOrDefault("name", List.of());
        List<String> texts = f.getOrDefault("text", List.of());
        List<SourceInput> out = new ArrayList<>();
        for (int i = 0; i < Math.max(names.size(), texts.size()); i++) {
            String n = i < names.size() ? names.get(i).trim() : "";
            String t = i < texts.size() ? texts.get(i) : "";
            if (n.isEmpty() && t.isBlank()) {
                continue;
            }
            if (n.isEmpty()) {
                throw new IllegalArgumentException("Give every source a file name with its extension, for example schema.sql");
            }
            out.add(SourceInput.of(n, t));
        }
        return out;
    }

    static Map<String, String> optionsFromForm(Map<String, String> f) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("dialect", "mode", "headerRow", "maxRows")) {
            if (f.get(k) != null && !f.get(k).isBlank()) {
                m.put(k, f.get(k));
            }
        }
        if (f.get("delimiter") != null && !f.get("delimiter").equals("auto") && !f.get("delimiter").isBlank()) {
            m.put("delimiter", f.get("delimiter"));
        }
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (e.getKey().startsWith("map.") && e.getValue() != null && !e.getValue().isBlank()) {
                m.put(e.getKey(), e.getValue().trim());
            }
        }
        m.put("hasHeader", "true".equals(f.get("hasHeader")) ? "true" : "false");
        return m;
    }

    static Map<String, String> firstValues(Map<String, List<String>> f) {
        Map<String, String> out = new LinkedHashMap<>();
        f.forEach((k, v) -> out.put(k, v.get(0)));
        return out;
    }

    private void sourcesPost(HttpExchange x) throws Exception {
        Map<String, List<String>> multi = formMulti(readBody(x));
        Map<String, String> first = firstValues(multi);
        List<SourceInput> sources;
        IngestOptions options;
        try {
            sources = sourcesFromForm(multi);
            options = IngestOptions.fromMap(optionsFromForm(first));
        } catch (IllegalArgumentException e) {
            sourcesPage(x, 400, List.of(), first, banner(true, e.getMessage()), "");
            return;
        }
        List<String> urls = urlsFromForm(first.get("urls"));
        List<SourceInput> all = new ArrayList<>(sources);
        all.addAll(pipeline.fetch(urls));
        RawMetadataBundle bundle = pipeline.ingest(all, options);
        boolean errors = DraftPipeline.hasErrors(bundle);
        String banner = errors
                ? banner(true, "Some sources could not be read. Fix them or remove them before creating a draft.")
                : banner(false, "Sources inspected" + (urls.isEmpty() ? "" : " (" + urls.size() + " URL(s) fetched; their content is shown below as sources)")
                        + ". Review what was found, then create a draft manifest.");
        sourcesPage(x, 200, all, first, banner, bundleHtml(bundle, all, first, !errors));
    }

    private void draftPost(HttpExchange x) throws Exception {
        Map<String, List<String>> multi = formMulti(readBody(x));
        Map<String, String> first = firstValues(multi);
        List<SourceInput> sources;
        IngestOptions options;
        DraftBuilder.SystemContext ctx;
        try {
            sources = sourcesFromForm(multi);
            options = IngestOptions.fromMap(optionsFromForm(first));
            String type = first.getOrDefault("databaseType", "");
            DatabaseType dt = type.isBlank() ? null : Enums.parse(DatabaseType.class, type)
                    .orElseThrow(() -> new IllegalArgumentException("Database type '" + type + "' is not an allowed value"));
            String dir = first.getOrDefault("interfaceDirection", "");
            Enums.InterfaceType idir = dir.isBlank() ? null : Enums.parse(Enums.InterfaceType.class, dir)
                    .orElseThrow(() -> new IllegalArgumentException("Interface direction must be SOURCING or DISTRIBUTION"));
            ctx = new DraftBuilder.SystemContext(first.getOrDefault("databaseLogicalName", ""), dt, first.getOrDefault("defaultSchema", ""), idir);
        } catch (IllegalArgumentException e) {
            sourcesPage(x, 400, List.of(), first, banner(true, e.getMessage()), "");
            return;
        }
        DraftPipeline.Outcome out = pipeline.draft(sources, options, ctx, "json");
        if (out.export() == null) {
            StringBuilder msg = new StringBuilder("The draft could not be created.");
            for (Finding f : out.draftFindings()) {
                msg.append(' ').append(f.message());
            }
            StringBuilder result = new StringBuilder(bundleHtml(out.bundle(), sources, first, false));
            sourcesPage(x, 422, sources, first, banner(true, msg.toString()), result.toString());
            return;
        }
        showResult(x, out.export().content(), "json", null, out.unresolved());
    }

    static void fieldTable(StringBuilder h, List<RawMetadataBundle.RawField> fields) {
        h.append("<table><thead><tr><th>Path</th><th>Type</th><th>Role</th><th>Description</th></tr></thead><tbody>");
        for (RawMetadataBundle.RawField f : fields) {
            h.append("<tr><td><code>").append(Html.esc(f.path())).append("</code></td><td>").append(Html.esc(f.dataType()))
                    .append("</td><td>").append(Html.esc(f.role())).append("</td><td>").append(Html.esc(f.description())).append("</td></tr>");
        }
        h.append("</tbody></table>");
    }

    static String bundleHtml(RawMetadataBundle b, List<SourceInput> sources, Map<String, String> opts, boolean offerDraft) {
        StringBuilder h = new StringBuilder("<h2>Detected</h2>");
        h.append("<table><thead><tr><th>Source</th><th>Format</th><th>Status</th><th>Detail</th></tr></thead><tbody>");
        for (RawMetadataBundle.SourceInfo s : b.sources()) {
            h.append("<tr><td>").append(Html.esc(s.name())).append("</td><td>").append(Html.esc(s.format()))
                    .append("</td><td>").append(Html.esc(s.status())).append("</td><td>").append(Html.esc(s.detail())).append("</td></tr>");
        }
        h.append("</tbody></table>");
        h.append("<h3>Tables and views (").append(b.tables().size()).append(")</h3>");
        for (RawTable t : b.tables()) {
            String qn = (t.schema() == null ? "" : t.schema() + ".") + t.name();
            h.append("<details><summary>").append(Html.esc(qn)).append(" &middot; ").append(Html.esc(t.kind()))
                    .append(" &middot; ").append(t.columns().size()).append(" column(s) &middot; ")
                    .append(Html.esc(t.source() + ", " + t.location())).append("</summary>");
            if (t.description() != null) {
                h.append("<p>").append(Html.esc(t.description())).append("</p>");
            }
            h.append("<table><thead><tr><th>Column</th><th>Type</th><th>Nullable</th><th>PK</th><th>Description</th><th>Location</th></tr></thead><tbody>");
            for (RawMetadataBundle.RawColumn c : t.columns()) {
                h.append("<tr><td>").append(Html.esc(c.name())).append("</td><td>")
                        .append(Html.esc(c.dataType() == null ? "" : c.dataType() + (c.typeInferred() ? " (inferred)" : "")))
                        .append("</td><td>").append(c.nullable() == null ? "" : c.nullable() ? "yes" : "no")
                        .append("</td><td>").append(c.primaryKey() ? "yes" : "").append("</td><td>")
                        .append(Html.esc(c.description())).append("</td><td>").append(Html.esc(c.location())).append("</td></tr>");
            }
            h.append("</tbody></table></details>");
        }
        if (!b.interfaces().isEmpty()) {
            h.append("<h3>Interface candidates (").append(b.interfaces().size())
                    .append(") &ndash; direction is not inferred; classify during review</h3>");
            for (RawMetadataBundle.RawInterface i : b.interfaces()) {
                h.append("<details><summary>").append(Html.esc(i.name())).append(" &middot; ").append(Html.esc(i.method() + " " + i.path()))
                        .append(" &middot; ").append(i.fields().size()).append(" field(s)</summary>");
                if (i.description() != null) {
                    h.append("<p>").append(Html.esc(i.description())).append("</p>");
                }
                fieldTable(h, i.fields());
                h.append("</details>");
            }
        }
        if (!b.structures().isEmpty()) {
            h.append("<h3>Structure candidates (").append(b.structures().size()).append(") &ndash; not relational tables</h3>");
            for (RawMetadataBundle.RawStructure st : b.structures()) {
                h.append("<details><summary>").append(Html.esc(st.name())).append(" &middot; ").append(st.fields().size())
                        .append(" field(s)</summary>");
                if (st.description() != null) {
                    h.append("<p>").append(Html.esc(st.description())).append("</p>");
                }
                fieldTable(h, st.fields());
                h.append("</details>");
            }
        }
        if (!b.terms().isEmpty()) {
            h.append("<h3>Candidate terms (").append(b.terms().size()).append(") &ndash; review required</h3><table><thead><tr><th>Term</th><th>Description</th><th>Location</th></tr></thead><tbody>");
            for (RawMetadataBundle.RawTerm t : b.terms()) {
                h.append("<tr><td>").append(Html.esc(t.name())).append("</td><td>").append(Html.esc(t.description()))
                        .append("</td><td>").append(Html.esc(t.source() + ", " + t.location())).append("</td></tr>");
            }
            h.append("</tbody></table>");
        }
        List<RawMetadataBundle.RawNote> lineageNotes = b.notes().stream().filter(n -> n.kind().equals("LINEAGE_TEXT")).toList();
        if (!lineageNotes.isEmpty() || !b.lineage().isEmpty()) {
            h.append("<h3>Lineage (for review; nothing is written to the manifest)</h3><ul>");
            for (RawMetadataBundle.LineageHint l : b.lineage()) {
                h.append("<li>").append(Html.esc(l.target() + " \u2190 " + l.source() + " (" + l.origin() + ", " + l.location() + ")")).append("</li>");
            }
            for (RawMetadataBundle.RawNote n : lineageNotes) {
                h.append("<li>").append(Html.esc(n.text() + " (" + n.source() + ", " + n.location() + ")")).append("</li>");
            }
            h.append("</ul>");
        }
        ValidationReport rep = new ValidationReport(b.findings());
        section(h, "Errors", rep.errors(), true);
        section(h, "Warnings", rep.warnings(), true);
        section(h, "Info", rep.infos(), false);
        if (offerDraft) {
            h.append("<h2>Create a draft manifest</h2><p>The draft contains databases, schemas, tables and columns only. "
                    + "Logical services, logical data elements, table types, PII and attestations are left for you to decide.</p>");
            h.append("<form method=\"post\" action=\"/draft\">");
            for (SourceInput s : sources) {
                h.append("<input type=\"hidden\" name=\"name\" value=\"").append(Html.esc(s.name()))
                        .append("\"><textarea name=\"text\" hidden>").append(Html.esc(s.text())).append("</textarea>");
            }
            for (String k : List.of("dialect", "delimiter", "mode", "headerRow", "maxRows")) {
                if (opts.get(k) != null) {
                    h.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(Html.esc(opts.get(k))).append("\">");
                }
            }
            if ("true".equals(opts.get("hasHeader")) || opts.isEmpty()) {
                h.append("<input type=\"hidden\" name=\"hasHeader\" value=\"true\">");
            }
            List<String> types = new ArrayList<>(List.of(""));
            for (DatabaseType t : DatabaseType.values()) {
                types.add(t.label());
            }
            h.append("<p><label>Database logical name (TAI) <input name=\"databaseLogicalName\" value=\"")
                    .append(Html.esc(opts.getOrDefault("databaseLogicalName", ""))).append("\"></label> ");
            h.append(select("databaseType", "Database type", types, opts.getOrDefault("databaseType", "")));
            if (!b.interfaces().isEmpty()) {
                h.append(select("interfaceDirection", "Interface direction", List.of("", "SOURCING", "DISTRIBUTION"),
                        opts.getOrDefault("interfaceDirection", "")));
            }
            h.append("<label>Default schema (for tables without one) <input name=\"defaultSchema\" value=\"")
                    .append(Html.esc(opts.getOrDefault("defaultSchema", ""))).append("\"></label> ");
            h.append("<button type=\"submit\">Create draft</button></p></form>");
        }
        return h.toString();
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

    private void apiIngest(HttpExchange x) throws Exception {
        IngestRequest req;
        try {
            req = IngestRequest.fromTree(Json.parse(readBody(x)));
        } catch (JsonException | IllegalArgumentException e) {
            json(x, 400, error(e.getMessage()));
            return;
        }
        List<SourceInput> all = new ArrayList<>(req.sources());
        all.addAll(pipeline.fetch(req.urls()));
        RawMetadataBundle bundle = pipeline.ingest(all, req.options());
        Map<String, Object> t = bundle.toTree();
        t.put("hasErrors", DraftPipeline.hasErrors(bundle));
        json(x, 200, t);
    }

    private void apiDraft(HttpExchange x) throws Exception {
        String format = query(x, "format", "json");
        IngestRequest req;
        try {
            req = IngestRequest.fromTree(Json.parse(readBody(x)));
            if (req.system() == null) {
                throw new IllegalArgumentException("'system' is required to build a draft");
            }
        } catch (JsonException | IllegalArgumentException e) {
            json(x, 400, error(e.getMessage()));
            return;
        }
        DraftPipeline.Outcome out;
        try {
            List<SourceInput> all = new ArrayList<>(req.sources());
            all.addAll(pipeline.fetch(req.urls()));
            out = pipeline.draft(all, req.options(), req.system(), format);
        } catch (CapabilityUnavailableException e) {
            json(x, 400, error(e.getMessage()));
            return;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("drafted", out.export() != null);
        t.put("sourceFindings", out.bundle().findings().stream().map(f -> (Object) f.toTree()).toList());
        t.put("draftFindings", out.draftFindings().stream().map(f -> (Object) f.toTree()).toList());
        if (out.export() != null) {
            t.put("format", out.export().format());
            t.put("exportable", out.export().exportable());
            t.put("validationLevel", out.export().validationLevel());
            t.put("unresolved", new ArrayList<Object>(out.unresolved()));
            t.put("report", out.export().report().toTree());
            t.put("content", out.export().content());
        }
        json(x, out.export() != null ? 200 : 422, t);
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

    static Map<String, List<String>> formMulti(String body) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.computeIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), key -> new ArrayList<>())
                    .add(URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        formMulti(body).forEach((k, v) -> out.put(k, v.get(0)));
        return out;
    }

    static String query(HttpExchange x, String name, String fallback) {
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

package com.example.casc.web;

import com.example.casc.app.Capability;
import com.example.casc.app.CapabilityService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportResult;
import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.domain.Enums.TargetTableType;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.RawMetadataBundle.RawColumn;
import com.example.casc.ingest.RawMetadataBundle.RawField;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import com.example.casc.wizard.AssetKeys;
import com.example.casc.wizard.ManifestAssembler;
import com.example.casc.wizard.NameSuggester;
import com.example.casc.wizard.PiiHints;
import com.example.casc.wizard.ProjectFile;
import com.example.casc.wizard.Reconciler;
import com.example.casc.wizard.SessionStore;
import com.example.casc.wizard.SessionStore.Session;
import com.example.casc.wizard.WizardService;
import com.example.casc.wizard.WizardState;
import com.example.casc.wizard.WizardState.Asset;
import com.example.casc.wizard.WizardState.Gov;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The five-step wizard. State lives in memory per browser session (cookie with a random id: HttpOnly, SameSite=Strict,
 * not readable by scripts) and every POST must carry the session's CSRF token. Sessions are isolated from each other.
 * All values are HTML-escaped. Nothing is written to disk by the server; the user saves a project file explicitly.
 */
final class Wizard {
    static final String COOKIE = "casc_session";
    private static final int ROWS_PER_PAGE = 50;
    private static final int MAX_SOURCE_CHARS = 20_000_000;
    static final int PREVIEW_LIMIT = 200_000;

    private final WebSupport web;
    private final SessionStore store;
    private final WizardService svc;
    private final DraftPipeline pipeline;
    private final boolean clipboard;

    Wizard(WebSupport web, SessionStore store, WizardService svc, DraftPipeline pipeline, boolean clipboard) {
        this.web = web;
        this.store = store;
        this.svc = svc;
        this.pipeline = pipeline;
        this.clipboard = clipboard;
    }

    // ------------------------------------------------------------------ plumbing

    private static String h(String s) {
        return Html.esc(s == null ? "" : s);
    }

    private static String first(Map<String, List<String>> f, String k) {
        List<String> v = f.get(k);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static String join(List<String> l) {
        return String.join(", ", l);
    }

    private static String txt(String name, String value, int size) {
        return "<input type=\"text\" name=\"" + name + "\" value=\"" + h(value) + "\" size=\"" + size + "\">";
    }

    private static String box(String name, String value, boolean on) {
        return "<input type=\"checkbox\" name=\"" + name + "\" value=\"" + h(value) + "\"" + (on ? " checked" : "") + ">";
    }

    private static String sel(String name, List<String> values, String selected) {
        StringBuilder b = new StringBuilder("<select name=\"" + name + "\">");
        for (String v : values) {
            b.append("<option value=\"").append(h(v)).append('"').append(v.equals(selected) ? " selected" : "").append('>')
                    .append(h(v)).append("</option>");
        }
        return b.append("</select>").toString();
    }

    private static List<String> labels(Class<? extends Enum<?>> type, boolean blankFirst) {
        List<String> out = new ArrayList<>();
        if (blankFirst) {
            out.add("");
        }
        for (Enum<?> e : type.getEnumConstants()) {
            out.add(((Enums.Labeled) e).label());
        }
        return out;
    }

    private static String label(Enums.Labeled l) {
        return l == null ? "" : l.label();
    }

    private String csrf(Session s) {
        return "<input type=\"hidden\" name=\"csrf\" value=\"" + h(s.state.csrf) + "\">";
    }

    private static boolean csrfOk(Session s, Map<String, List<String>> form) {
        return MessageDigest.isEqual(first(form, "csrf").getBytes(StandardCharsets.UTF_8), s.state.csrf.getBytes(StandardCharsets.UTF_8));
    }

    private Session session(HttpExchange x) {
        String cookies = String.join(";", x.getRequestHeaders().getOrDefault("Cookie", List.of()));
        for (String part : cookies.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                String value = p.substring(COOKIE.length() + 1);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);   // RFC 6265 allows a quoted cookie value
                }
                var found = store.get(value);
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        Session created = store.create();
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + created.id + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=7200");
        return created;
    }

    private void redirect(HttpExchange x, String location) throws IOException {
        x.getResponseHeaders().set("Location", location);
        web.send(x, 303, "text/plain; charset=UTF-8", new byte[0]);
    }

    private static void flash(WizardState st, String message, boolean error) {
        st.flash = (error ? "!" : "") + message;
    }

    private void render(HttpExchange x, int status, Session s, int step, String content) throws IOException {
        WizardState st = s.state;
        String f = st.flash;
        st.flash = "";
        StringBuilder nav = new StringBuilder("<ol>");
        String[] names = {"Sources", "System", "Classify", "Governance", "Validate & export"};
        for (int i = 1; i <= 5; i++) {
            nav.append("<li").append(i == step ? " class=\"current\"" : "").append("><a href=\"/wizard/").append(i).append("\">")
                    .append(i).append(". ").append(h(names[i - 1])).append("</a></li>");
        }
        nav.append("</ol><div class=\"wizard-tools\"><a href=\"/wizard/project\">Save project file</a>"
                + "<form method=\"post\" action=\"/wizard/reset\">" + csrf(s) + "<button type=\"submit\">Start over</button></form></div>");
        Map<String, String> v = new LinkedHashMap<>();
        v.put("stepNav", nav.toString());
        v.put("banner", f.isEmpty() ? "" : "<p class=\"" + (f.startsWith("!") ? "banner-error" : "banner-ok") + "\">"
                + h(f.startsWith("!") ? f.substring(1) : f) + "</p>");
        v.put("content", content);
        web.page(x, status, "Wizard step " + step, "wizard", v);
    }

    // ------------------------------------------------------------------ routing

    void handle(HttpExchange x, String path) throws Exception {
        Session s = session(x);
        String method = x.getRequestMethod();
        Map<String, List<String>> form = null;
        if (method.equals("POST")) {
            form = WebServer.formMulti(web.readBody(x));
            if (!csrfOk(s, form)) {
                web.text(x, 403, "Invalid or missing CSRF token. Open the wizard page again and resubmit.");
                return;
            }
        } else if (!method.equals("GET")) {
            x.getResponseHeaders().set("Allow", "GET, POST");
            web.text(x, 405, "Method not allowed.");
            return;
        }
        synchronized (s.state) {
            dispatch(x, path, method, form, s);
        }
    }

    private void dispatch(HttpExchange x, String path, String method, Map<String, List<String>> f, Session s) throws Exception {
        boolean post = method.equals("POST");
        switch (path) {
            case "/wizard" -> redirect(x, "/wizard/1");
            case "/wizard/1" -> { if (post) step1Post(x, f, s); else step1(x, s); }
            case "/wizard/2" -> { if (post) step2Post(x, f, s); else step2(x, s); }
            case "/wizard/3" -> { if (post) step3Post(x, f, s); else step3(x, s); }
            case "/wizard/4" -> { if (post) step4Post(x, f, s); else step4(x, s); }
            case "/wizard/4/asset" -> { if (post) assetPost(x, f, s); else asset(x, s); }
            case "/wizard/5" -> { if (!post) step5(x, s); else web.text(x, 405, "Method not allowed."); }
            case "/wizard/5/ack" -> { if (post) ack(x, f, s); else web.text(x, 405, "Method not allowed."); }
            case "/wizard/5/keys" -> { if (post) keys(x, s); else web.text(x, 405, "Method not allowed."); }
            case "/wizard/5/download" -> { if (post) download(x, f, s); else web.text(x, 405, "Method not allowed."); }
            case "/wizard/project" -> { if (post) projectLoad(x, f, s); else projectSave(x, s); }
            case "/wizard/reset" -> {
                if (post) {
                    s.state.copyFrom(new WizardState());
                    flash(s.state, "The wizard was reset. Nothing is kept on the server.", false);
                    redirect(x, "/wizard/1");
                } else {
                    web.text(x, 405, "Method not allowed.");
                }
            }
            default -> web.text(x, 404, "Not found.");
        }
    }

    // ------------------------------------------------------------------ step 1: sources

    private static final List<String> MAP_FIELDS = IngestOptions.MAP_FIELDS;

    private void step1(HttpExchange x, Session s) throws IOException {
        WizardState st = s.state;
        StringBuilder b = new StringBuilder("<h1>Step 1: Sources</h1>");
        b.append("<p>Add SQL, CSV, TSV, TXT, Markdown or OpenAPI JSON sources (pasted, loaded from files, or fetched from allowlisted URLs). "
                + "Saving re-reads every source; decisions for tables that no longer exist are dropped and counted.</p>");
        b.append("<form method=\"post\" action=\"/wizard/1\">").append(csrf(s)).append("<div id=\"sources\">");
        if (st.sources.isEmpty()) {
            b.append(WebServer.sourceBlock("", ""));
        }
        for (SourceInput in : st.sources) {
            b.append(WebServer.sourceBlock(in));
        }
        b.append("</div>");
        if (pipeline.fetcher().enabled()) {
            b.append("<p><label for=\"urls\">URLs to fetch, one per line (https; JSON, text, Markdown, CSV or SQL)</label><br>"
                    + "<textarea id=\"urls\" name=\"urls\" rows=\"3\" spellcheck=\"false\"></textarea></p>");
        } else {
            b.append("<p class=\"notice\">URL sources are disabled: no allowed hosts are configured on this server.</p>");
        }
        b.append("<p><input type=\"file\" id=\"sourceFiles\" multiple accept=\".sql,.ddl,.csv,.tsv,.txt,.md,.markdown,.json,.xlsx,.xlsm,.xls,.docx,.pdf,.html,.htm\">"
                + " <button type=\"button\" id=\"loadSampleSources\">Load samples</button></p>");
        b.append("<fieldset><legend>Options</legend>").append(WebServer.optionControls(st.options)).append("<br>");
        for (String field : MAP_FIELDS) {
            b.append("<label>Dictionary header for ").append(field).append(" <input type=\"text\" name=\"map.").append(field)
                    .append("\" value=\"").append(h(st.options.getOrDefault("map." + field, ""))).append("\" size=\"14\"></label> ");
        }
        b.append("</fieldset><p><button type=\"submit\">Save and inspect</button></p></form>");
        if (st.bundle != null) {
            b.append(WebServer.bundleHtml(st.bundle, List.of(), st.options, false));
            b.append("<p><a class=\"button\" href=\"/wizard/2\">Continue to step 2</a></p>");
        }
        b.append("<h2>Restore a saved project</h2><form method=\"post\" action=\"/wizard/project\">").append(csrf(s))
                .append("<p><input type=\"file\" id=\"projectFile\" accept=\".json\"></p>"
                        + "<p><textarea id=\"projectText\" name=\"project\" rows=\"4\" spellcheck=\"false\" placeholder=\"Paste the project file here\"></textarea></p>"
                        + "<p><button type=\"submit\">Load project</button></p></form>");
        render(x, 200, s, 1, b.toString());
    }

    private void step1Post(HttpExchange x, Map<String, List<String>> f, Session s) throws Exception {
        WizardState st = s.state;
        Map<String, String> first = WebServer.firstValues(f);
        List<SourceInput> pasted;
        Map<String, String> opts;
        try {
            pasted = WebServer.sourcesFromForm(f);
            opts = WebServer.optionsFromForm(first);
            IngestOptions.fromMap(opts);
        } catch (IllegalArgumentException e) {
            flash(st, e.getMessage(), true);
            redirect(x, "/wizard/1");
            return;
        }
        List<SourceInput> fetched = pipeline.fetch(WebServer.urlsFromForm(first.get("urls")));
        long chars = 0;
        List<SourceInput> keep = new ArrayList<>(pasted);
        List<SourceInput> failed = new ArrayList<>();
        for (SourceInput in : fetched) {
            (in.readError() == null ? keep : failed).add(in);
        }
        for (SourceInput in : keep) {
            chars += in.size();
        }
        if (chars > MAX_SOURCE_CHARS) {
            flash(st, "The sources are larger than " + MAX_SOURCE_CHARS + " characters in total.", true);
            redirect(x, "/wizard/1");
            return;
        }
        st.sources.clear();
        st.sources.addAll(keep);
        st.failedSources.clear();
        st.failedSources.addAll(failed);
        st.options.clear();
        st.options.putAll(opts);
        int dropped = svc.ingest(st);
        flash(st, "Sources saved and inspected." + (dropped > 0 ? " " + dropped + " decision(s) were dropped because their source changed." : ""), false);
        redirect(x, "/wizard/1");
    }

    // ------------------------------------------------------------------ step 2: system

    private void step2(HttpExchange x, Session s) throws IOException {
        WizardState st = s.state;
        WizardState.SystemConfig c = st.system;
        StringBuilder b = new StringBuilder("<h1>Step 2: System configuration</h1>");
        b.append("<p>The EON ID and system name are kept with the project only. They are <strong>not</strong> written into the manifest "
                + "until their official placement is confirmed.</p>");
        b.append("<form method=\"post\" action=\"/wizard/2\">").append(csrf(s));
        b.append("<table><tbody>");
        b.append("<tr><th>System EON ID</th><td>").append(txt("eonId", c.eonId, 30)).append("</td></tr>");
        b.append("<tr><th>System name</th><td>").append(txt("systemName", c.name, 40)).append("</td></tr>");
        b.append("<tr><th>TAI logical database name</th><td>").append(txt("dbName", c.databaseLogicalName, 40)).append("</td></tr>");
        b.append("<tr><th>Database type</th><td>").append(sel("dbType", labels(DatabaseType.class, true), label(c.databaseType))).append("</td></tr>");
        b.append("<tr><th>Default schema (for tables without one)</th><td>").append(txt("defaultSchema", c.defaultSchema, 30)).append("</td></tr>");
        b.append("</tbody></table><h2>Logical services</h2><table><thead><tr><th>Name</th><th>Deployed on premises</th>"
                + "<th>Cloud provider (required when not on premises)</th><th>System key (optional)</th></tr></thead><tbody>");
        int rows = st.services.size() + 3;
        for (int i = 0; i < rows; i++) {
            WizardState.Service sv = i < st.services.size() ? st.services.get(i) : new WizardState.Service();
            String prem = sv.onPrem == null ? "" : sv.onPrem ? "true" : "false";
            b.append("<tr><td>").append(txt("svcName" + i, sv.name, 28)).append("</td><td>")
                    .append(sel("svcPrem" + i, List.of("", "true", "false"), prem)).append("</td><td>")
                    .append(txt("svcCloud" + i, sv.cloudProvider, 24)).append("</td><td>").append(txt("svcKey" + i, sv.systemKey, 20)).append("</td></tr>");
        }
        b.append("</tbody></table><input type=\"hidden\" name=\"svcRows\" value=\"").append(rows)
                .append("\"><p class=\"muted\">Leave a name blank to remove a service. An empty 'deployed on premises' is reported by validation; it is never treated as false.</p>");
        b.append("<p><button type=\"submit\">Save</button> <a class=\"button\" href=\"/wizard/3\">Continue to step 3</a></p></form>");
        render(x, 200, s, 2, b.toString());
    }

    private void step2Post(HttpExchange x, Map<String, List<String>> f, Session s) throws IOException {
        WizardState st = s.state;
        DatabaseType type;
        try {
            String t = first(f, "dbType");
            type = t.isBlank() ? null : Enums.parse(DatabaseType.class, t).orElseThrow(() -> new IllegalArgumentException("Unknown database type"));
        } catch (IllegalArgumentException e) {
            flash(st, e.getMessage(), true);
            redirect(x, "/wizard/2");
            return;
        }
        st.system.eonId = first(f, "eonId").trim();
        st.system.name = first(f, "systemName").trim();
        st.system.databaseLogicalName = first(f, "dbName").trim();
        st.system.databaseType = type;
        st.system.defaultSchema = first(f, "defaultSchema").trim();
        List<WizardState.Service> list = new ArrayList<>();
        int rows = intOf(first(f, "svcRows"), 0, 500);
        for (int i = 0; i < rows; i++) {
            String name = first(f, "svcName" + i).trim();
            if (name.isEmpty()) {
                continue;
            }
            WizardState.Service sv = new WizardState.Service();
            sv.name = name;
            String prem = first(f, "svcPrem" + i);
            sv.onPrem = prem.equals("true") ? Boolean.TRUE : prem.equals("false") ? Boolean.FALSE : null;
            sv.cloudProvider = first(f, "svcCloud" + i).trim();
            sv.systemKey = first(f, "svcKey" + i).trim();
            list.add(sv);
        }
        st.services.clear();
        st.services.addAll(list);
        flash(st, "System configuration saved.", false);
        redirect(x, "/wizard/2");
    }

    private static int intOf(String s, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return min;
        }
    }

    // ------------------------------------------------------------------ step 3: classification

    private record AssetRow(String key, RawTable table, RawInterface iface) {}

    private List<AssetRow> assetRows(WizardState st) {
        List<AssetRow> rows = new ArrayList<>();
        if (st.bundle != null) {
            for (RawTable t : st.bundle.tables()) {
                if (t.kind().equals("TABLE")) {
                    rows.add(new AssetRow(AssetKeys.table(t), t, null));
                }
            }
            for (RawInterface i : st.bundle.interfaces()) {
                rows.add(new AssetRow(AssetKeys.iface(i), null, i));
            }
        }
        return rows;
    }

    private void step3(HttpExchange x, Session s) throws IOException {
        WizardState st = s.state;
        StringBuilder b = new StringBuilder("<h1>Step 3: Classification and mapping</h1>");
        if (st.bundle == null || assetRows(st).isEmpty()) {
            b.append("<p class=\"notice\">There are no tables or interfaces yet. Add sources in <a href=\"/wizard/1\">step 1</a>.</p>");
            render(x, 200, s, 3, b.toString());
            return;
        }
        b.append("<p>Choose what goes into the manifest. Set each table's type, give every interface a direction "
                + "(<em>SOURCING</em>: the system consumes data, <em>DISTRIBUTION</em>: the system provides it) and the logical services it belongs to. "
                + "Interfaces without a direction are left out. Views are listed but not included in this release.</p>");
        Map<String, Integer> counts = new TreeMap<>();
        List<AssetRow> rows = assetRows(st);
        for (AssetRow r : rows) {
            if (r.table() != null && st.asset(r.key()).included) {
                counts.merge(AssetKeys.qualified(r.table()), 1, Integer::sum);
            }
        }
        b.append("<form method=\"post\" action=\"/wizard/3\">").append(csrf(s)).append("<h2>Tables</h2><table><thead><tr><th>Include</th><th>Table</th>"
                + "<th>Source</th><th>Columns</th><th>Table type</th></tr></thead><tbody>");
        int idx = 0;
        List<String> dup = new ArrayList<>();
        for (AssetRow r : rows) {
            int i = idx++;
            if (r.table() == null) {
                continue;
            }
            Asset a = st.asset(r.key());
            boolean conflict = a.included && counts.getOrDefault(AssetKeys.qualified(r.table()), 0) > 1;
            if (conflict) {
                dup.add(AssetKeys.qualified(r.table()));
            }
            b.append("<tr").append(conflict ? " class=\"conflict\"" : "").append("><td><input type=\"hidden\" name=\"k").append(i).append("\" value=\"")
                    .append(h(r.key())).append("\">").append(box("inc" + i, "on", a.included)).append("</td><td>")
                    .append(h((r.table().schema() == null ? "" : r.table().schema() + ".") + r.table().name())).append("</td><td>")
                    .append(h(r.table().source() + ", " + r.table().location())).append("</td><td>").append(r.table().columns().size())
                    .append("</td><td>").append(sel("tt" + i, labels(TargetTableType.class, true), label(a.tableType))).append("</td></tr>");
        }
        b.append("</tbody></table>");
        if (!dup.isEmpty()) {
            b.append("<p class=\"banner-error\">Conflict: ").append(h(String.join(", ", new java.util.LinkedHashSet<>(dup))))
                    .append(" is defined by more than one included source. Exclude all but one.</p>");
        }
        long views = st.bundle.tables().stream().filter(t -> !t.kind().equals("TABLE")).count();
        if (views > 0) {
            b.append("<p class=\"muted\">").append(views).append(" view(s) found; they are not included in the manifest in this release.</p>");
        }
        boolean anyIface = rows.stream().anyMatch(r -> r.iface() != null);
        if (anyIface) {
            b.append("<h2>Interfaces</h2><table><thead><tr><th>Include</th><th>Interface</th><th>Direction</th><th>Logical services (comma separated)</th></tr></thead><tbody>");
        }
        idx = 0;
        for (AssetRow r : rows) {
            int i = idx++;
            if (r.iface() == null) {
                continue;
            }
            Asset a = st.asset(r.key());
            RawInterface ri = r.iface();
            b.append("<tr><td><input type=\"hidden\" name=\"k").append(i).append("\" value=\"").append(h(r.key())).append("\">")
                    .append(box("inc" + i, "on", a.included)).append("</td><td>").append(h(ri.name())).append("<br><span class=\"muted\">")
                    .append(h(ri.method() + " " + ri.path())).append("</span>");
            b.append("<details><summary>Payload paths (").append(ri.fields().size()).append(")</summary><p class=\"muted\">")
                    .append(a.direction == null ? "Tick a path to exclude it. Choose a direction to see which paths are used."
                            : "Only " + ManifestAssembler.wantedRole(a.direction) + " paths are used. Tick a path to exclude it.").append("</p>");
            for (RawField fl : ri.fields()) {
                if (a.direction == null || ManifestAssembler.wantedRole(a.direction).equals(fl.role())) {
                    b.append("<div>").append(box("ex" + i, fl.path(), a.excludedFields.contains(fl.path()))).append(" <code>")
                            .append(h(fl.path())).append("</code> <span class=\"muted\">").append(h(fl.role() + (fl.dataType() == null ? "" : ", " + fl.dataType())))
                            .append("</span></div>");
                }
            }
            b.append("</details></td><td>").append(sel("dir" + i, labels(InterfaceType.class, true), label(a.direction))).append("</td><td>")
                    .append(txt("svc" + i, join(a.services), 30)).append("</td></tr>");
        }
        if (anyIface) {
            b.append("</tbody></table>");
        }
        b.append("<input type=\"hidden\" name=\"rows\" value=\"").append(rows.size()).append("\"><p class=\"muted\">Defined services: ")
                .append(h(st.serviceNames().isEmpty() ? "none (add them in step 2)" : join(st.serviceNames())))
                .append("</p><p><button type=\"submit\">Save</button> <a class=\"button\" href=\"/wizard/4\">Continue to step 4</a></p></form>");
        render(x, 200, s, 3, b.toString());
    }

    private void step3Post(HttpExchange x, Map<String, List<String>> f, Session s) throws IOException {
        WizardState st = s.state;
        Set<String> live = new HashSet<>();
        for (AssetRow r : assetRows(st)) {
            live.add(r.key());
        }
        int rows = intOf(first(f, "rows"), 0, 100_000);
        for (int i = 0; i < rows; i++) {
            String key = first(f, "k" + i);
            if (!live.contains(key)) {
                continue;
            }
            Asset a = st.asset(key);
            a.included = f.containsKey("inc" + i);
            if (f.containsKey("tt" + i)) {
                a.tableType = Enums.parse(TargetTableType.class, first(f, "tt" + i)).orElse(null);
            }
            if (f.containsKey("dir" + i)) {
                a.direction = Enums.parse(InterfaceType.class, first(f, "dir" + i)).orElse(null);
                a.services = csv(first(f, "svc" + i));
                a.excludedFields = new java.util.LinkedHashSet<>(f.getOrDefault("ex" + i, List.of()));
            }
        }
        flash(s.state, "Classification saved.", false);
        redirect(x, "/wizard/3");
    }

    // ------------------------------------------------------------------ step 4: governance

    private void step4(HttpExchange x, Session s) throws IOException {
        WizardState st = s.state;
        StringBuilder b = new StringBuilder("<h1>Step 4: Governance</h1>");
        b.append("<p>Define logical elements, data entities and transformations, then open each table or interface to map fields, edit descriptions "
                + "and make sensitivity decisions. Suggestions are never applied for you.</p>");
        b.append("<form method=\"post\" action=\"/wizard/4\">").append(csrf(s));

        b.append("<h2>Logical data elements</h2><table><thead><tr><th>Name</th><th>Description</th><th>DGC concept</th><th>DGC domain</th>"
                + "<th>Logical services</th><th>System key</th><th>Delete</th></tr></thead><tbody>");
        int ne = st.elements.size() + 3;
        for (int i = 0; i < ne; i++) {
            WizardState.Element e = i < st.elements.size() ? st.elements.get(i) : new WizardState.Element();
            b.append("<tr><td>").append(txt("en" + i, e.name, 22)).append("</td><td>").append(txt("ed" + i, e.description, 28)).append("</td><td>")
                    .append(txt("ec" + i, e.concept, 14)).append("</td><td>").append(txt("eo" + i, e.domain, 14)).append("</td><td>")
                    .append(txt("es" + i, join(e.services), 18)).append("</td><td>").append(txt("ek" + i, e.systemKey, 14)).append("</td><td>")
                    .append(i < st.elements.size() ? box("ex" + i, "on", false) : "").append("</td></tr>");
        }
        b.append("</tbody></table><input type=\"hidden\" name=\"ne\" value=\"").append(ne).append("\">");

        List<String[]> suggestions = suggestions(st);
        if (!suggestions.isEmpty()) {
            b.append("<h3>Suggested elements (not confirmed)</h3><p class=\"muted\">Tick the ones you want to add and correct the proposed names. "
                    + "A suggestion never maps a column to an element by itself.</p><table><thead><tr><th>Add</th><th>Proposed name</th><th>Derived from</th></tr></thead><tbody>");
            for (int i = 0; i < suggestions.size(); i++) {
                String[] sg = suggestions.get(i);
                b.append("<tr><td>").append(box("sg" + i, "on", false)).append("</td><td>").append(txt("sn" + i, sg[0], 30))
                        .append("<input type=\"hidden\" name=\"sd").append(i).append("\" value=\"").append(h(sg[2])).append("\"></td><td>")
                        .append(h(sg[1])).append("</td></tr>");
            }
            b.append("</tbody></table><input type=\"hidden\" name=\"nsg\" value=\"").append(suggestions.size()).append("\">");
        }

        b.append("<h2>Data entities</h2><table><thead><tr><th>Name</th><th>Description</th><th>Logical services</th><th>Elements</th><th>System key</th><th>Delete</th></tr></thead><tbody>");
        int nent = st.entities.size() + 2;
        for (int i = 0; i < nent; i++) {
            WizardState.Entity e = i < st.entities.size() ? st.entities.get(i) : new WizardState.Entity();
            b.append("<tr><td>").append(txt("tn" + i, e.name, 22)).append("</td><td>").append(txt("td" + i, e.description, 26)).append("</td><td>")
                    .append(txt("ts" + i, join(e.services), 16)).append("</td><td>").append(txt("te" + i, join(e.elements), 22)).append("</td><td>")
                    .append(txt("tk" + i, e.systemKey, 12)).append("</td><td>").append(i < st.entities.size() ? box("tx" + i, "on", false) : "").append("</td></tr>");
        }
        b.append("</tbody></table><input type=\"hidden\" name=\"nent\" value=\"").append(nent).append("\">");
        if (st.bundle != null) {
            Set<String> have = new HashSet<>();
            st.entities.forEach(e -> have.add(e.name.toLowerCase(Locale.ROOT)));
            List<RawMetadataBundle.RawStructure> cands = st.bundle.structures().stream().filter(c -> !have.contains(c.name().toLowerCase(Locale.ROOT))).toList();
            if (!cands.isEmpty()) {
                b.append("<h3>Structure candidates from the sources</h3><p class=\"muted\">Tick a structure to add it as a data entity (name and description only).</p><ul>");
                for (int i = 0; i < cands.size(); i++) {
                    b.append("<li>").append(box("cs" + i, cands.get(i).name(), false)).append(" ").append(h(cands.get(i).name())).append(" <span class=\"muted\">(")
                            .append(cands.get(i).fields().size()).append(" fields)</span><input type=\"hidden\" name=\"cd").append(i).append("\" value=\"")
                            .append(h(cands.get(i).description())).append("\"></li>");
                }
                b.append("</ul><input type=\"hidden\" name=\"ncs\" value=\"").append(cands.size()).append("\">");
            }
        }

        b.append("<h2>Transformations</h2><table><thead><tr><th>Name</th><th>Description</th><th>Source elements</th><th>Target elements</th><th>System key</th><th>Delete</th></tr></thead><tbody>");
        int nx = st.transformations.size() + 2;
        for (int i = 0; i < nx; i++) {
            WizardState.Transform t = i < st.transformations.size() ? st.transformations.get(i) : new WizardState.Transform();
            b.append("<tr><td>").append(txt("xn" + i, t.name, 20)).append("</td><td>").append(txt("xd" + i, t.description, 26)).append("</td><td>")
                    .append(txt("xs" + i, join(t.sources), 20)).append("</td><td>").append(txt("xt" + i, join(t.targets), 20)).append("</td><td>")
                    .append(txt("xk" + i, t.systemKey, 12)).append("</td><td>").append(i < st.transformations.size() ? box("xx" + i, "on", false) : "").append("</td></tr>");
        }
        b.append("</tbody></table><input type=\"hidden\" name=\"nx\" value=\"").append(nx).append("\">");
        b.append("<p class=\"muted\">Lists are comma separated names. References are checked by validation in step 5.</p><p><button type=\"submit\">Save</button></p></form>");

        b.append("<h2>Map fields, descriptions and sensitivity</h2>");
        List<AssetRow> rows = assetRows(st);
        boolean any = false;
        b.append("<ul>");
        for (AssetRow r : rows) {
            Asset a = st.asset(r.key());
            if (!a.included || (r.iface() != null && a.direction == null)) {
                continue;
            }
            any = true;
            String name = r.table() != null ? (r.table().schema() == null ? "" : r.table().schema() + ".") + r.table().name() : r.iface().name();
            long governed = (r.table() != null ? st.columns : st.fields).entrySet().stream().filter(e -> e.getKey().startsWith(r.key() + "#")).count();
            b.append("<li><a href=\"/wizard/4/asset?k=").append(URLEncoder.encode(r.key(), StandardCharsets.UTF_8)).append("\">").append(h(name))
                    .append("</a> <span class=\"muted\">(").append(r.table() != null ? "table" : "interface").append(", ").append(governed).append(" with entries)</span></li>");
        }
        b.append("</ul>");
        if (!any) {
            b.append("<p class=\"muted\">No included tables or interfaces with a direction. Finish <a href=\"/wizard/3\">step 3</a> first.</p>");
        }
        if (st.bundle != null && (!st.bundle.lineage().isEmpty() || st.bundle.notes().stream().anyMatch(n -> n.kind().equals("LINEAGE_TEXT")))) {
            b.append("<h2>Lineage review</h2><p class=\"muted\">These were found in the sources. They are not written to the manifest because the official "
                    + "sourcedFromColumns shape is unconfirmed. If you know the right structure, enter it as JSON on a column's page.</p><ul>");
            for (RawMetadataBundle.LineageHint l : st.bundle.lineage()) {
                b.append("<li>").append(h(l.target() + " \u2190 " + l.source() + " (" + l.origin() + ", " + l.location() + ")")).append("</li>");
            }
            for (RawMetadataBundle.RawNote n : st.bundle.notes()) {
                if (n.kind().equals("LINEAGE_TEXT")) {
                    b.append("<li>").append(h(n.text() + " (" + n.source() + ", " + n.location() + ")")).append("</li>");
                }
            }
            b.append("</ul>");
        }
        b.append("<p><a class=\"button\" href=\"/wizard/5\">Continue to step 5</a></p>");
        render(x, 200, s, 4, b.toString());
    }

    /** {proposed name, derived from, description}. Candidate terms first, then repeated column names. */
    private List<String[]> suggestions(WizardState st) {
        List<String[]> out = new ArrayList<>();
        if (st.bundle == null) {
            return out;
        }
        Set<String> have = new HashSet<>();
        st.elementNames().forEach(n -> have.add(n.toLowerCase(Locale.ROOT)));
        Set<String> seen = new HashSet<>();
        for (RawMetadataBundle.RawTerm t : st.bundle.terms()) {
            String name = NameSuggester.suggest(t.name()).isBlank() ? t.name() : (t.name().contains(" ") ? t.name() : NameSuggester.suggest(t.name()));
            if (!have.contains(name.toLowerCase(Locale.ROOT)) && seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(new String[] {name, "term " + t.name() + " (" + t.source() + ", " + t.location() + ")", t.description() == null ? "" : t.description()});
            }
        }
        Map<String, Integer> cols = new TreeMap<>();
        for (RawTable t : st.bundle.tables()) {
            if (t.kind().equals("TABLE") && st.asset(AssetKeys.table(t)).included) {
                for (RawColumn c : t.columns()) {
                    cols.merge(c.name(), 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(cols.entrySet());
        sorted.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        int added = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            String name = NameSuggester.suggest(e.getKey());
            if (!name.isBlank() && !have.contains(name.toLowerCase(Locale.ROOT)) && seen.add(name.toLowerCase(Locale.ROOT)) && added++ < 40) {
                out.add(new String[] {name, "column " + e.getKey() + (e.getValue() > 1 ? " in " + e.getValue() + " tables" : ""), ""});
            }
        }
        return out;
    }

    private void step4Post(HttpExchange x, Map<String, List<String>> f, Session s) throws IOException {
        WizardState st = s.state;
        List<WizardState.Element> els = new ArrayList<>();
        int ne = intOf(first(f, "ne"), 0, 5000);
        for (int i = 0; i < ne; i++) {
            if (f.containsKey("ex" + i) || first(f, "en" + i).isBlank()) {
                continue;
            }
            WizardState.Element e = new WizardState.Element();
            e.name = first(f, "en" + i).trim();
            e.description = first(f, "ed" + i).trim();
            e.concept = first(f, "ec" + i).trim();
            e.domain = first(f, "eo" + i).trim();
            e.services = csv(first(f, "es" + i));
            e.systemKey = first(f, "ek" + i).trim();
            els.add(e);
        }
        Set<String> have = new HashSet<>();
        els.forEach(e -> have.add(e.name.toLowerCase(Locale.ROOT)));
        int nsg = intOf(first(f, "nsg"), 0, 5000);
        for (int i = 0; i < nsg; i++) {
            String name = first(f, "sn" + i).trim();
            if (f.containsKey("sg" + i) && !name.isBlank() && have.add(name.toLowerCase(Locale.ROOT))) {
                WizardState.Element e = new WizardState.Element();
                e.name = name;
                e.description = first(f, "sd" + i).trim();
                els.add(e);
            }
        }
        List<WizardState.Entity> ents = new ArrayList<>();
        int nent = intOf(first(f, "nent"), 0, 5000);
        for (int i = 0; i < nent; i++) {
            if (f.containsKey("tx" + i) || first(f, "tn" + i).isBlank()) {
                continue;
            }
            WizardState.Entity e = new WizardState.Entity();
            e.name = first(f, "tn" + i).trim();
            e.description = first(f, "td" + i).trim();
            e.services = csv(first(f, "ts" + i));
            e.elements = csv(first(f, "te" + i));
            e.systemKey = first(f, "tk" + i).trim();
            ents.add(e);
        }
        Set<String> haveEnt = new HashSet<>();
        ents.forEach(e -> haveEnt.add(e.name.toLowerCase(Locale.ROOT)));
        int ncs = intOf(first(f, "ncs"), 0, 5000);
        for (int i = 0; i < ncs; i++) {
            String name = first(f, "cs" + i).trim();
            if (!name.isEmpty() && haveEnt.add(name.toLowerCase(Locale.ROOT))) {
                WizardState.Entity e = new WizardState.Entity();
                e.name = name;
                e.description = first(f, "cd" + i).trim();
                ents.add(e);
            }
        }
        List<WizardState.Transform> xs = new ArrayList<>();
        int nx = intOf(first(f, "nx"), 0, 5000);
        for (int i = 0; i < nx; i++) {
            if (f.containsKey("xx" + i) || first(f, "xn" + i).isBlank()) {
                continue;
            }
            WizardState.Transform t = new WizardState.Transform();
            t.name = first(f, "xn" + i).trim();
            t.description = first(f, "xd" + i).trim();
            t.sources = csv(first(f, "xs" + i));
            t.targets = csv(first(f, "xt" + i));
            t.systemKey = first(f, "xk" + i).trim();
            xs.add(t);
        }
        st.elements.clear();
        st.elements.addAll(els);
        st.entities.clear();
        st.entities.addAll(ents);
        st.transformations.clear();
        st.transformations.addAll(xs);
        flash(st, "Governance definitions saved.", false);
        redirect(x, "/wizard/4");
    }

    // ------------------------------------------------------------------ step 4: one table or interface

    private AssetRow findAsset(WizardState st, String key) {
        for (AssetRow r : assetRows(st)) {
            if (r.key().equals(key)) {
                return r;
            }
        }
        return null;
    }

    /** {name, type, existing description} for the columns or fields of the asset, in source order. */
    private List<String[]> governedItems(WizardState st, AssetRow r) {
        List<String[]> out = new ArrayList<>();
        if (r.table() != null) {
            for (RawColumn c : r.table().columns()) {
                out.add(new String[] {c.name(), c.dataType() == null ? "" : c.dataType(), c.description() == null ? "" : c.description()});
            }
        } else {
            Asset a = st.asset(r.key());
            for (RawField fl : r.iface().fields()) {
                if (a.direction != null && ManifestAssembler.wantedRole(a.direction).equals(fl.role()) && !a.excludedFields.contains(fl.path())) {
                    out.add(new String[] {fl.path(), fl.dataType() == null ? "" : fl.dataType(), fl.description() == null ? "" : fl.description()});
                }
            }
        }
        return out;
    }

    private void asset(HttpExchange x, Session s) throws IOException {
        WizardState st = s.state;
        String key = WebServer.query(x, "k", "");
        AssetRow r = findAsset(st, key);
        if (r == null) {
            web.text(x, 404, "Unknown table or interface. Open it from step 4.");
            return;
        }
        int page = intOf(WebServer.query(x, "p", "0"), 0, 100_000);
        List<String[]> items = governedItems(st, r);
        int from = Math.min(page * ROWS_PER_PAGE, items.size());
        int to = Math.min(from + ROWS_PER_PAGE, items.size());
        boolean isTable = r.table() != null;
        String title = isTable ? (r.table().schema() == null ? "" : r.table().schema() + ".") + r.table().name() : r.iface().name();
        StringBuilder b = new StringBuilder("<h1>Step 4: ").append(h(title)).append("</h1><p><a href=\"/wizard/4\">Back to step 4</a></p>");
        b.append("<p class=\"muted\">Hints flag names that resemble sensitive data. They only prompt a review: nothing is set or cleared for you. "
                + "A sensitive-data attestation of Yes needs at least one PII element and one data subject category.</p>");
        List<String> elementNames = new ArrayList<>(List.of(""));
        elementNames.addAll(st.elementNames());
        b.append("<form method=\"post\" action=\"/wizard/4/asset\">").append(csrf(s)).append("<input type=\"hidden\" name=\"k\" value=\"").append(h(key))
                .append("\"><input type=\"hidden\" name=\"p\" value=\"").append(page).append("\"><input type=\"hidden\" name=\"from\" value=\"").append(from)
                .append("\"><table><thead><tr><th>").append(isTable ? "Column" : "Payload path").append("</th><th>Source description</th><th>Description to use</th>"
                        + "<th>Logical element</th><th>Sensitive data</th><th>PII elements (comma separated)</th><th>Data subject categories</th>")
                .append(isTable ? "<th>Lineage JSON (unverified)</th>" : "").append("</tr></thead><tbody>");
        for (int i = from; i < to; i++) {
            String[] it = items.get(i);
            Gov g = (isTable ? st.columns : st.fields).getOrDefault(AssetKeys.column(key, it[0]), new Gov());
            List<String> opts = new ArrayList<>(elementNames);
            if (!g.element.isBlank() && !opts.contains(g.element)) {
                opts.add(g.element);
            }
            String hint = PiiHints.hint(it[0]);
            b.append("<tr><td><input type=\"hidden\" name=\"c").append(i).append("\" value=\"").append(h(it[0])).append("\"><code>").append(h(it[0])).append("</code>")
                    .append(it[1].isEmpty() ? "" : "<br><span class=\"muted\">" + h(it[1]) + "</span>")
                    .append(hint == null ? "" : "<br><span class=\"hint\">review: " + h(hint) + "</span>").append("</td><td>").append(h(it[2])).append("</td><td>")
                    .append(txt("d" + i, g.description, 24)).append("</td><td>").append(sel("l" + i, opts, g.element)).append("</td><td>")
                    .append(sel("a" + i, labels(Attestation.class, true), label(g.attestation))).append("</td><td>").append(txt("p" + i, join(g.pii), 18)).append("</td><td>");
            for (DataSubjectCategory c : DataSubjectCategory.values()) {
                b.append("<label>").append(box("t" + i, c.label(), g.categories.contains(c))).append(" ").append(h(c.label())).append("</label><br>");
            }
            b.append("</td>");
            if (isTable) {
                b.append("<td>").append(txt("n" + i, g.lineageJson, 18)).append("</td>");
            }
            b.append("</tr>");
        }
        b.append("</tbody></table>");
        if (items.isEmpty()) {
            b.append("<p class=\"muted\">There are no ").append(isTable ? "columns" : "paths").append(" to review.</p>");
        }
        int pages = (items.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE;
        if (pages > 1) {
            b.append("<p>Page ").append(page + 1).append(" of ").append(pages).append(": ");
            for (int p = 0; p < pages; p++) {
                b.append(p == page ? "<strong>" + (p + 1) + "</strong> " : "<a href=\"/wizard/4/asset?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "&p=" + p + "\">" + (p + 1) + "</a> ");
            }
            b.append("</p>");
        }
        b.append("<p><button type=\"submit\">Save this page</button></p></form>");
        render(x, 200, s, 4, b.toString());
    }

    private void assetPost(HttpExchange x, Map<String, List<String>> f, Session s) throws IOException {
        WizardState st = s.state;
        String key = first(f, "k");
        AssetRow r = findAsset(st, key);
        if (r == null) {
            web.text(x, 404, "Unknown table or interface.");
            return;
        }
        boolean isTable = r.table() != null;
        Set<String> valid = new HashSet<>();
        for (String[] it : governedItems(st, r)) {
            valid.add(it[0]);
        }
        Map<String, Gov> target = isTable ? st.columns : st.fields;
        int from = intOf(first(f, "from"), 0, 1_000_000);
        for (int i = from; i < from + ROWS_PER_PAGE; i++) {
            String name = first(f, "c" + i);
            if (name.isEmpty() || !valid.contains(name)) {
                continue;
            }
            Gov g = new Gov();
            g.description = first(f, "d" + i).trim();
            g.element = first(f, "l" + i).trim();
            g.attestation = Enums.parse(Attestation.class, first(f, "a" + i)).orElse(null);
            g.pii = csv(first(f, "p" + i));
            for (String c : f.getOrDefault("t" + i, List.of())) {
                Enums.parse(DataSubjectCategory.class, c).ifPresent(g.categories::add);
            }
            g.lineageJson = isTable ? first(f, "n" + i).trim() : "";
            String k = AssetKeys.column(key, name);
            if (g.isEmpty()) {
                target.remove(k);
            } else {
                target.put(k, g);
            }
        }
        flash(st, "Saved.", false);
        redirect(x, "/wizard/4/asset?k=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&p=" + intOf(first(f, "p"), 0, 100_000));
    }

    // ------------------------------------------------------------------ step 5: validate and export

    private void step5(HttpExchange x, Session s) throws Exception {
        WizardState st = s.state;
        String format = WebServer.query(x, "f", "json");
        WizardService.Review rv;
        try {
            rv = svc.review(st, format);
        } catch (CapabilityUnavailableException e) {
            flash(st, e.getMessage(), true);
            redirect(x, "/wizard/5");
            return;
        }
        ExportResult r = rv.result();
        ValidationReport rep = r.report();
        List<Finding> unresolved = rep.warnings().stream().filter(w -> w.code().equals("UNRESOLVED_DECISION")).toList();
        List<Finding> warnings = rep.warnings().stream().filter(w -> !w.code().equals("UNRESOLVED_DECISION")).toList();
        StringBuilder b = new StringBuilder("<h1>Step 5: Validation and export</h1>");
        b.append("<p>Errors: ").append(rep.errors().size()).append(" &middot; Warnings: ").append(warnings.size()).append(" &middot; Unresolved decisions: ")
                .append(unresolved.size()).append(" &middot; Info: ").append(rep.infos().size()).append(" &middot; Validation level: ").append(h(r.validationLevel()))
                .append(" &middot; Profile: ").append(h(r.profile().name())).append(" (provisional, not confirmed against the official specification)</p>");
        b.append("<p class=\"").append(r.exportable() ? "banner-ok" : "banner-error").append("\">")
                .append(r.exportable() ? "No errors found by the local checks. This is not proof of acceptance by the FDC service."
                        : rep.errors().size() + " error(s) must be fixed before export.").append("</p>");
        b.append("<details><summary>System context (kept outside the manifest) and enabled capabilities</summary><ul><li>EON ID: ").append(h(st.system.eonId.isBlank() ? "(not set)" : st.system.eonId))
                .append("</li><li>System name: ").append(h(st.system.name.isBlank() ? "(not set)" : st.system.name)).append("</li></ul><p>Available: ");
        List<String> avail = new ArrayList<>();
        List<String> unavail = new ArrayList<>();
        for (Capability c : new CapabilityService().all()) {
            (c.status() == Capability.Status.AVAILABLE ? avail : unavail).add(c.name());
        }
        b.append(h(String.join("; ", avail))).append("</p><p>Unavailable or planned: ").append(h(String.join("; ", unavail))).append("</p></details>");
        WebServer.section(b, "Errors", rep.errors(), true);
        WebServer.section(b, "Warnings", warnings, true);
        b.append("<h3>Unresolved decisions (").append(rv.decisions().stream().filter(d -> !d.acknowledgeable() || !st.acknowledged.contains(d.id())).count()).append(" open)</h3>");
        b.append("<form method=\"post\" action=\"/wizard/5/ack\">").append(csrf(s)).append("<ul>");
        for (Reconciler.Decision d : rv.decisions()) {
            b.append("<li>");
            if (d.acknowledgeable()) {
                b.append(box("ack", d.id(), st.acknowledged.contains(d.id()))).append(" ");
            }
            b.append(h(d.message())).append(d.acknowledgeable() ? "" : " <strong>(must be resolved, not acknowledged)</strong>").append("</li>");
        }
        b.append("</ul><p><button type=\"submit\">Save acknowledgements</button></p></form>");
        WebServer.section(b, "Info", rep.infos(), false);
        b.append("<h2>Preview</h2><form method=\"get\" action=\"/wizard/5\"><label>Format ").append(sel("f", svc.export().formats(), r.format()))
                .append("</label> <button type=\"submit\">Refresh</button></form>");
        boolean cut = r.content().length() > PREVIEW_LIMIT;
        if (cut) {
            b.append("<p class=\"muted\">The preview shows the first ").append(PREVIEW_LIMIT).append(" of ").append(r.content().length())
                    .append(" characters. The download contains the complete manifest.</p>");
        }
        b.append("<pre id=\"previewText\">").append(h(cut ? r.content().substring(0, PREVIEW_LIMIT) : r.content())).append("</pre>");
        b.append("<form method=\"post\" action=\"/wizard/5/download\">").append(csrf(s)).append("<input type=\"hidden\" name=\"f\" value=\"").append(h(r.format())).append("\">");
        if (!r.exportable()) {
            b.append("<label>").append(box("allowInvalid", "on", false)).append(" Download anyway (output is incomplete or invalid)</label> ");
        }
        b.append("<button type=\"submit\">Download ").append(h(r.format())).append("</button>");
        if (clipboard && !cut) {
            b.append(" <button type=\"button\" id=\"copyBtn\">Copy to clipboard</button>");
        }
        b.append("</form><form method=\"post\" action=\"/wizard/5/keys\">").append(csrf(s))
                .append("<p class=\"muted\">System keys are optional. This fills in only the missing ones for services, elements, interfaces, entities and transformations, "
                        + "as lower-case slugs with a kind prefix and a number on collision. Keys you typed are never changed.</p><button type=\"submit\">Generate missing system keys</button></form>");
        render(x, 200, s, 5, b.toString());
    }

    private void ack(HttpExchange x, Map<String, List<String>> f, Session s) throws Exception {
        WizardState st = s.state;
        WizardService.Review rv = svc.review(st, "json");
        Set<String> allowed = new HashSet<>();
        for (Reconciler.Decision d : rv.decisions()) {
            if (d.acknowledgeable()) {
                allowed.add(d.id());
            }
        }
        st.acknowledged.clear();
        for (String id : f.getOrDefault("ack", List.of())) {
            if (allowed.contains(id)) {
                st.acknowledged.add(id);
            }
        }
        flash(st, "Acknowledgements saved.", false);
        redirect(x, "/wizard/5");
    }

    private void keys(HttpExchange x, Session s) throws IOException {
        int n = svc.generateKeys(s.state);
        flash(s.state, n + " system key(s) generated.", false);
        redirect(x, "/wizard/5");
    }

    private void download(HttpExchange x, Map<String, List<String>> f, Session s) throws Exception {
        WizardState st = s.state;
        WizardService.Review rv;
        try {
            rv = svc.review(st, first(f, "f").isBlank() ? "json" : first(f, "f"));
        } catch (CapabilityUnavailableException e) {
            flash(st, e.getMessage(), true);
            redirect(x, "/wizard/5");
            return;
        }
        if (!rv.result().exportable() && !f.containsKey("allowInvalid")) {
            flash(st, "Download blocked: " + rv.result().report().errors().size() + " error(s) remain. Fix them, or tick 'Download anyway'.", true);
            redirect(x, "/wizard/5");
            return;
        }
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"manifest." + rv.result().fileExtension() + "\"");
        web.send(x, 200, rv.result().contentType(), rv.result().content().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ project file

    private void projectSave(HttpExchange x, Session s) throws IOException {
        s.state.compact();
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"casc-project.json\"");
        web.send(x, 200, "application/json; charset=UTF-8", (Json.write(ProjectFile.toTree(s.state)) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void projectLoad(HttpExchange x, Map<String, List<String>> f, Session s) throws IOException {
        WizardState st = s.state;
        try {
            WizardState loaded = ProjectFile.fromTree(Json.parse(first(f, "project")));
            st.copyFrom(loaded);
            int dropped = svc.ingest(st);
            flash(st, "Project loaded." + (dropped > 0 ? " " + dropped + " decision(s) did not match the sources and were dropped." : ""), false);
        } catch (JsonException | IllegalArgumentException e) {
            flash(st, "The project file could not be loaded: " + e.getMessage(), true);
        }
        redirect(x, "/wizard/1");
    }
}

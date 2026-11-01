package com.example.casc.cli;

import com.example.casc.agent.AgentTools;
import com.example.casc.agent.ToolException;
import com.example.casc.app.Capability;
import com.example.casc.app.CapabilityService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.ExportResult;
import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportService;
import com.example.casc.domain.Enums;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.ingest.DraftBuilder;
import com.example.casc.ingest.IngestOptions;
import com.example.casc.ingest.InputFiles;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceInput;
import com.example.casc.net.HttpUrlFetcher;
import com.example.casc.net.UrlFetcher;
import com.example.casc.net.UrlPolicy;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import com.example.casc.web.WebServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Command line front end. Never prompts and never waits for input; missing arguments produce an
 * actionable message and exit code 2.
 *
 * Exit codes: 0 success, 1 validation errors (nothing written), 2 usage error,
 * 3 input/output or parse failure, 4 internal error.
 */
public final class Cli {
    public static final int OK = 0;
    public static final int INVALID = 1;
    public static final int USAGE = 2;
    public static final int IO = 3;
    public static final int INTERNAL = 4;

    private static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;

    private Cli() {}

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private record Opts(Map<String, List<String>> values, Set<String> flags) {
        /** Last value given for the option, or the fallback. */
        String get(String k, String fallback) {
            List<String> v = values.get(k);
            return v == null ? fallback : v.get(v.size() - 1);
        }

        List<String> all(String k) {
            return values.getOrDefault(k, List.of());
        }
    }

    private static Opts parse(String[] args, int from, Set<String> valued, Set<String> flags) throws UsageException {
        Map<String, List<String>> values = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (valued.contains(a)) {
                if (i + 1 >= args.length) {
                    throw new UsageException("Option " + a + " needs a value.");
                }
                values.computeIfAbsent(a, k -> new ArrayList<>()).add(args[++i]);
            } else if (flags.contains(a)) {
                seen.add(a);
            } else {
                throw new UsageException("Unknown option: " + a);
            }
        }
        return new Opts(values, seen);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, InputStream.nullInputStream(), out, err);
    }

    /** in is read only by "tool NAME --request -"; no command ever waits for input unless it was asked to. */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return run(args, in, out, err, System::getenv);
    }

    private static final ThreadLocal<Function<String, String>> ENV = ThreadLocal.withInitial(() -> System::getenv);

    private static String getenv(String name) {
        return ENV.get().apply(name);
    }

    /**
     * Same as above with the environment supplied by the caller (CASC_BIND, CASC_PORT, CASC_INPUT_ROOT, CASC_URL_ALLOW).
     * Tests pass an empty environment so that they do not depend on the machine they run on.
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Function<String, String> env) {
        Function<String, String> previous = ENV.get();
        ENV.set(env);
        try {
            return dispatch(args, in, out, err);
        } finally {
            ENV.set(previous);
        }
    }

    private static int dispatch(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("No command given.");
            usage(err);
            return USAGE;
        }
        try {
            switch (args[0]) {
                case "help", "--help", "-h":
                    usage(out);
                    return OK;
                case "version":
                    String v = Cli.class.getPackage().getImplementationVersion();
                    out.println("casc-generator " + (v == null ? "dev" : v));
                    return OK;
                case "capabilities":
                    return capabilities(parse(args, 1, Set.of(), Set.of("--json")), out);
                case "validate":
                    return validate(parse(args, 1, Set.of("--input"), Set.of("--json")), out, err);
                case "export":
                    return export(parse(args, 1, Set.of("--input", "--format", "--output"),
                            Set.of("--overwrite", "--allow-invalid")), out, err);
                case "ingest":
                    return ingest(parse(args, 1, Set.of("--input-files", "--root", "--dialect", "--delimiter", "--header-row",
                            "--mode", "--max-rows", "--map", "--db-name", "--db-type", "--default-schema", "--format",
                            "--output", "--bundle-output", "--interface-direction", "--url", "--url-allow", "--url-timeout",
                            "--url-max-bytes"), Set.of("--no-header", "--overwrite", "--json", "--url-allow-http")), out, err);
                case "tools":
                    return tools(out);
                case "tool":
                    if (args.length < 2 || args[1].startsWith("--")) {
                        throw new UsageException("tool needs a tool name: " + AgentTools.INGEST + ", " + AgentTools.VALIDATE + " or " + AgentTools.GENERATE + ".");
                    }
                    return tool(args[1], parse(args, 2, Set.of("--request", "--url-allow", "--url-timeout", "--url-max-bytes"),
                            Set.of("--fail-on-errors", "--url-allow-http")), in, out, err);
                case "web":
                    return web(parse(args, 1, Set.of("--bind", "--port", "--max-body-bytes", "--url-allow", "--url-timeout",
                            "--url-max-bytes"), Set.of("--url-allow-http", "--allow-clipboard")), out, err);
                default:
                    err.println("Unknown command: " + args[0]);
                    usage(err);
                    return USAGE;
            }
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            usage(err);
            return USAGE;
        } catch (RuntimeException e) {
            err.println("Internal error: " + e.getClass().getName());
            return INTERNAL;
        }
    }

    private static void usage(PrintStream p) {
        p.println("Usage: casc-generator <command> [options]");
        p.println("  web [--bind ADDR] [--port N] [--max-body-bytes N]   start the web UI (default 127.0.0.1:8080)");
        p.println("  validate --input FILE [--json]                        validate a manifest (JSON)");
        p.println("  export --input FILE --output FILE [--format json|yaml] [--overwrite] [--allow-invalid]");
        p.println("  ingest --input-files PATH [--input-files PATH ...] [--url URL ...]   read SQL, CSV, TSV, TXT, Markdown, OpenAPI JSON");
        p.println("         [--url-allow HOST ...] [--url-allow-http] [--url-timeout S] [--url-max-bytes N]   URL fetching (blocked unless hosts are allowed)");
        p.println("         [--interface-direction SOURCING|DISTRIBUTION]   draft OpenAPI operations as interfaces");
        p.println("         [--db-name NAME --output FILE [--db-type TYPE] [--default-schema S] [--format json|yaml]]  write a draft manifest");
        p.println("         [--bundle-output FILE] [--json] [--root DIR] [--dialect D] [--delimiter D] [--no-header] [--header-row N]");
        p.println("         [--mode auto|data|dictionary] [--max-rows N] [--map table|column|description|type|schema=HEADER] [--overwrite]");
        p.println("  tools                                                 list the agent tool contracts as JSON");
        p.println("  tool NAME --request FILE|-  [--fail-on-errors]       run ingest_sources, validate_manifest_payload or generate_casc_manifest;");
        p.println("         JSON in (file, or '-' for standard input), one JSON document out on standard output; [--url-allow HOST ...]");
        p.println("  capabilities [--json]                                 list available and unavailable capabilities");
        p.println("  version | help");
        p.println("Exit codes: 0 ok, 1 validation errors, 2 usage error, 3 I/O or parse failure, 4 internal error.");
        p.println("Environment: CASC_BIND, CASC_PORT, CASC_INPUT_ROOT, CASC_URL_ALLOW (comma separated hosts), CASC_URL_TOKEN (bearer token).");
        p.println("web also accepts --url-allow, --url-allow-http, --url-timeout, --url-max-bytes and --allow-clipboard (shows a copy button in step 5).");
    }

    // ------------------------------------------------------------------ commands

    private static String readInput(String file) throws UsageException, IOException {
        if (file == null) {
            throw new UsageException("--input FILE is required.");
        }
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            throw new IOException("Input file not found or not a regular file: " + p);
        }
        if (Files.size(p) > MAX_INPUT_BYTES) {
            throw new IOException("Input file is larger than " + MAX_INPUT_BYTES + " bytes: " + p);
        }
        return Files.readString(p, StandardCharsets.UTF_8); // malformed UTF-8 raises an IOException
    }

    private static int capabilities(Opts o, PrintStream out) {
        List<Capability> all = new CapabilityService().all();
        if (o.flags().contains("--json")) {
            out.println(Json.write(Map.of("capabilities", all.stream().map(c -> (Object) c.toTree()).toList())));
            return OK;
        }
        for (Capability c : all) {
            out.printf("%-12s %s%n             %s%n", c.status(), c.name(), c.detail());
        }
        return OK;
    }

    private static void printReport(ValidationReport r, PrintStream p) {
        for (Finding f : r.findings()) {
            p.println(f.severity() + " " + f.code() + " " + f.path());
            p.println("    " + f.message());
            p.println("    Fix: " + f.suggestion());
        }
        p.println("Summary: " + r.errors().size() + " error(s), " + r.warnings().size() + " warning(s), "
                + r.infos().size() + " info. Validation level: " + ExportService.LEVEL
                + " (provisional profile; not checked against the official schema).");
    }

    private static int validate(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String text;
        ReadResult in;
        try {
            text = readInput(o.get("--input", null));
            in = ExportService.standard().read(text);
        } catch (IOException | JsonException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        ValidationReport report = ExportService.standard().validate(in, List.of());
        if (o.flags().contains("--json")) {
            out.println(Json.write(report.toTree()));
        } else {
            printReport(report, out);
        }
        return report.hasErrors() ? INVALID : OK;
    }

    private static int export(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String output = o.get("--output", null);
        if (output == null) {
            throw new UsageException("--output FILE is required.");
        }
        ExportService service = ExportService.standard();
        ExportResult result;
        try {
            ReadResult in = service.read(readInput(o.get("--input", null)));
            result = service.export(in, List.of(), o.get("--format", "json"));
        } catch (CapabilityUnavailableException e) {
            throw new UsageException(e.getMessage());
        } catch (IOException | JsonException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        if (!result.exportable() && !o.flags().contains("--allow-invalid")) {
            printReport(result.report(), err);
            err.println("Nothing was written. Fix the errors, or use --allow-invalid to write the output anyway.");
            return INVALID;
        }
        try {
            writeAtomically(Path.of(output), result.content(), o.flags().contains("--overwrite"));
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        printReport(result.report(), out);
        out.println("Wrote " + result.format() + " manifest to " + Path.of(output).toAbsolutePath());
        return OK;
    }

    /** Writes a temporary file beside the target, then moves it into place; the target is never half-written. */
    static void writeAtomically(Path target, String content, boolean overwrite) throws IOException {
        Path abs = target.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("Output directory does not exist: " + dir);
        }
        if (Files.exists(abs) && !overwrite) {
            throw new IOException("Output file already exists (use --overwrite to replace it): " + abs);
        }
        Path tmp = Files.createTempFile(dir, ".casc-", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static UrlPolicy urlPolicy(Opts o) throws UsageException {
        List<String> allow = new ArrayList<>(o.all("--url-allow"));
        String env = getenv("CASC_URL_ALLOW");
        if (env != null && !env.isBlank()) {
            for (String e : env.split(",")) {
                allow.add(e);
            }
        }
        if (allow.stream().allMatch(String::isBlank)) {
            return UrlPolicy.disabled();
        }
        try {
            return UrlPolicy.of(allow, o.flags().contains("--url-allow-http"),
                    number(o.get("--url-timeout", "15"), "url-timeout", 1, 300),
                    number(o.get("--url-max-bytes", String.valueOf(5 * 1024 * 1024)), "url-max-bytes", 1024, 50_000_000));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    private static int ingest(Opts o, PrintStream out, PrintStream err) throws UsageException {
        List<String> paths = o.all("--input-files");
        List<String> urls = o.all("--url");
        if (paths.isEmpty() && urls.isEmpty()) {
            throw new UsageException("--input-files PATH or --url URL is required (repeat the options for several sources).");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] pair : new String[][] {{"--dialect", "dialect"}, {"--delimiter", "delimiter"}, {"--header-row", "headerRow"},
                {"--mode", "mode"}, {"--max-rows", "maxRows"}}) {
            if (o.get(pair[0], null) != null) {
                m.put(pair[1], o.get(pair[0], null));
            }
        }
        if (o.flags().contains("--no-header")) {
            m.put("hasHeader", "false");
        }
        for (String kv : o.all("--map")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                throw new UsageException("--map expects FIELD=HEADER, for example --map column=Field");
            }
            m.put("map." + kv.substring(0, eq), kv.substring(eq + 1));
        }
        IngestOptions options;
        try {
            options = IngestOptions.fromMap(m);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        String dbName = o.get("--db-name", null);
        String output = o.get("--output", null);
        if (dbName != null && output == null) {
            throw new UsageException("--output FILE is required to write a draft manifest.");
        }
        InterfaceType direction = null;
        if (o.get("--interface-direction", null) != null) {
            direction = Enums.parse(InterfaceType.class, o.get("--interface-direction", null)).orElseThrow(() ->
                    new UsageException("--interface-direction must be SOURCING or DISTRIBUTION"));
        }
        UrlPolicy policy = urlPolicy(o);
        DatabaseType dbType = null;
        if (o.get("--db-type", null) != null) {
            dbType = Enums.parse(DatabaseType.class, o.get("--db-type", null)).orElseThrow(() ->
                    new UsageException("--db-type must be one of: " + java.util.Arrays.stream(DatabaseType.values())
                            .map(DatabaseType::label).collect(java.util.stream.Collectors.joining(", "))));
        }
        String root = o.get("--root", envOr("CASC_INPUT_ROOT", ""));
        DraftPipeline pipeline = new DraftPipeline(policy.enabled() ? new HttpUrlFetcher(policy) : UrlFetcher.disabled());
        List<SourceInput> sources = new ArrayList<>();
        if (!paths.isEmpty()) {
            InputFiles.Result files = InputFiles.collect(paths, root.isBlank() ? null : Path.of(root),
                    pipeline.ingestion().knownExtensions());
            for (String note : files.notes()) {
                err.println("Note: " + note);
            }
            sources.addAll(files.inputs());
        }
        sources.addAll(pipeline.fetch(urls));
        RawMetadataBundle bundle = pipeline.ingest(sources, options);
        try {
            if (o.get("--bundle-output", null) != null) {
                writeAtomically(Path.of(o.get("--bundle-output", null)), Json.write(bundle.toTree()) + "\n", o.flags().contains("--overwrite"));
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        if (o.flags().contains("--json")) {
            out.println(Json.write(bundle.toTree()));
        } else {
            for (RawMetadataBundle.SourceInfo s : bundle.sources()) {
                out.println(s.status() + " " + s.name() + " (" + s.format() + "): " + s.detail());
            }
            out.println("Found " + bundle.tables().size() + " table(s)/view(s), " + bundle.interfaces().size()
                    + " interface candidate(s), " + bundle.structures().size() + " structure candidate(s), "
                    + bundle.terms().size() + " candidate term(s).");
            printReport(new ValidationReport(bundle.findings()), out);
        }
        if (DraftPipeline.hasErrors(bundle)) {
            err.println("Some sources failed; no draft manifest was written.");
            return INVALID;
        }
        if (output == null) {
            return OK;
        }
        DraftPipeline.Outcome draft;
        try {
            draft = pipeline.draft(sources, options,
                    new DraftBuilder.SystemContext(dbName, dbType, o.get("--default-schema", null), direction), o.get("--format", "json"));
        } catch (CapabilityUnavailableException e) {
            throw new UsageException(e.getMessage());
        }
        if (draft.export() == null) {
            printReport(new ValidationReport(draft.draftFindings()), err);
            err.println("The draft could not be created; nothing was written.");
            return INVALID;
        }
        if (!draft.export().exportable()) {
            printReport(draft.export().report(), err);
            err.println("The draft has validation errors; nothing was written. Fix the sources or the conflicts above.");
            return INVALID;
        }
        try {
            writeAtomically(Path.of(output), draft.export().content(), o.flags().contains("--overwrite"));
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        printReport(draft.export().report(), out);
        out.println("Wrote UNREVIEWED draft " + draft.export().format() + " manifest to " + Path.of(output).toAbsolutePath());
        return OK;
    }

    private static int tools(PrintStream out) {
        List<Object> list = new ArrayList<>();
        for (AgentTools.Descriptor d : AgentTools.descriptors()) {
            list.add(d.toTree());
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tools", list);
        out.println(Json.write(t));
        return OK;
    }

    private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;

    private static void envelope(PrintStream out, String tool, boolean ok, Object resultOrError) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ok", ok);
        t.put("tool", tool);
        t.put(ok ? "result" : "error", resultOrError);
        out.println(Json.write(t));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        return e;
    }

    /** Exit 0: the tool ran (read the result). 1: --fail-on-errors and the result has errors. 2: invalid request. 3: I/O. 4: internal. */
    private static int tool(String name, Opts o, InputStream in, PrintStream out, PrintStream err) throws UsageException {
        if (AgentTools.descriptors().stream().noneMatch(d -> d.name().equals(name))) {
            envelope(out, name, false, errorBody("UNKNOWN_TOOL", "Unknown tool '" + name + "'. Available: " + AgentTools.INGEST + ", "
                    + AgentTools.VALIDATE + ", " + AgentTools.GENERATE + "."));
            return USAGE;
        }
        String request = o.get("--request", null);
        if (request == null) {
            throw new UsageException("--request FILE is required (use --request - to read standard input). The command never waits for input on its own.");
        }
        UrlPolicy policy = urlPolicy(o);
        String text;
        try {
            if (request.equals("-")) {
                byte[] bytes = in.readNBytes(MAX_REQUEST_BYTES + 1);
                if (bytes.length > MAX_REQUEST_BYTES) {
                    throw new IOException("The request is larger than " + MAX_REQUEST_BYTES + " bytes.");
                }
                SourceInput s = SourceInput.fromBytes("request", bytes);
                if (s.readError() != null) {
                    throw new IOException("The request is not valid UTF-8 text.");
                }
                text = s.text();
            } else {
                text = readInput(request);
            }
        } catch (IOException e) {
            envelope(out, name, false, errorBody("IO_ERROR", e.getMessage()));
            return IO;
        }
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (JsonException e) {
            envelope(out, name, false, errorBody("INVALID_REQUEST", "The request is not valid JSON: " + e.getMessage()));
            return USAGE;
        }
        try {
            Map<String, Object> result = new AgentTools(policy.enabled() ? new HttpUrlFetcher(policy) : UrlFetcher.disabled()).call(name, tree);
            envelope(out, name, true, result);
            boolean bad = Boolean.TRUE.equals(result.get("hasErrors")) || Boolean.FALSE.equals(result.get("valid"))
                    || Boolean.FALSE.equals(result.get("exportable"));
            return bad && o.flags().contains("--fail-on-errors") ? INVALID : OK;
        } catch (ToolException e) {
            envelope(out, name, false, errorBody(e.code(), e.getMessage()));
            return USAGE;
        } catch (RuntimeException e) {
            envelope(out, name, false, errorBody("INTERNAL", "Internal error (" + e.getClass().getSimpleName() + ")."));
            return INTERNAL;
        }
    }

    private static int web(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String bind = o.get("--bind", envOr("CASC_BIND", "127.0.0.1"));
        int port = number(o.get("--port", envOr("CASC_PORT", "8080")), "port", 0, 65535);
        int max = number(o.get("--max-body-bytes", String.valueOf(5 * 1024 * 1024)), "max-body-bytes", 1024, 100_000_000);
        try {
            WebServer server = new WebServer(new WebServer.Config(bind, port, max, urlPolicy(o), o.flags().contains("--allow-clipboard")));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            out.println("Listening on http://" + bind + ":" + server.port() + "/  (Ctrl+C to stop)");
            if (!java.net.InetAddress.getByName(bind).isLoopbackAddress()) {
                err.println("Warning: bound to a non-loopback address. The application has no authentication; "
                        + "place it behind approved access control.");
            }
            Thread.currentThread().join();
            return OK;
        } catch (BindException e) {
            err.println("Error: cannot bind " + bind + ":" + port + " (" + e.getMessage() + "). Choose another --port.");
            return IO;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OK;
        }
    }

    private static String envOr(String name, String fallback) {
        String v = getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int number(String text, String name, int min, int max) throws UsageException {
        try {
            int n = Integer.parseInt(text.trim());
            if (n < min || n > max) {
                throw new UsageException("--" + name + " must be between " + min + " and " + max + ".");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("--" + name + " must be a whole number.");
        }
    }
}

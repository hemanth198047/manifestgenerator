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
import com.example.casc.compare.HtmlReport;
import com.example.casc.compare.JsonCompare;
import com.example.casc.ingest.DatasetManifestCombiner;
import com.example.casc.ingest.DatasetManifestIngestion;
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
import com.example.casc.json.JsonOps;
import com.example.casc.json.JsonPath;
import com.example.casc.json.LenientJson;
import com.example.casc.mcp.Audit;
import com.example.casc.mcp.McpServer;
import com.example.casc.mcp.Sandbox;
import com.example.casc.mcp.Tools;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.validation.Finding;
import com.example.casc.validation.FindingGuide;
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
                            "--mode", "--max-rows", "--sheet", "--map", "--db-name", "--db-type", "--default-schema", "--format",
                            "--output", "--bundle-output", "--interface-direction", "--url", "--url-allow", "--url-timeout",
                            "--url-max-bytes", "--manifest-input", "--sql-input", "--table-metadata", "--column-metadata", "--dataset-output", "--database-name", "--schema-name", "--table-list", "--domain", "--boundary", "--table-descriptions",
                            "--combine-output", "--combine-count", "--combine-tables"),
                            Set.of("--no-header", "--overwrite", "--json", "--url-allow-http", "--no-upstream", "--combined-only")), out, err);
                case "json":
                    return jsonCommand(args, out, err);
                case "compare":
                    return compare(parse(args, 1, Set.of("--input", "--output", "--path", "--key", "--title", "--json-output", "--max-rows"),
                            Set.of("--overwrite", "--lenient", "--fail-on-diff")), out, err);
                case "combine":
                    return combine(parse(args, 1, Set.of("--input", "--output", "--count", "--tables"), Set.of("--overwrite")), out, err);
                case "explain":
                    return explain(args.length > 1 ? args[1] : null, out, err);
                case "tools":
                    return tools(out);
                case "tool":
                    if (args.length < 2 || args[1].startsWith("--")) {
                        throw new UsageException("tool needs a tool name: " + AgentTools.INGEST + ", " + AgentTools.VALIDATE + " or " + AgentTools.GENERATE + ".");
                    }
                    return tool(args[1], parse(args, 2, Set.of("--request", "--url-allow", "--url-timeout", "--url-max-bytes"),
                            Set.of("--fail-on-errors", "--url-allow-http")), in, out, err);
                case "mcp":
                    return mcp(parse(args, 1, Set.of("--root", "--audit-log"), Set.of("--read-only")), in, out, err);
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
        p.println("Manifests");
        p.println("  validate --input FILE [--json]                        validate a manifest (JSON)");
        p.println("  export --input FILE --output FILE [--format json|yaml] [--overwrite] [--allow-invalid]");
        p.println("  explain [CODE]                                        explain a finding code in plain language (no CODE: list all codes)");
        p.println("Ingest (read sources, draft a manifest)");
        p.println("  ingest --input-files PATH [--input-files PATH ...] [--url URL ...]   read SQL, CSV, TSV, TXT, Markdown, OpenAPI JSON");
        p.println("         [--url-allow HOST ...] [--url-allow-http] [--url-timeout S] [--url-max-bytes N]   URL fetching (blocked unless hosts are allowed)");
        p.println("         [--bundle-output FILE] [--json] [--root DIR] [--dialect D] [--delimiter D] [--no-header] [--header-row N]");
        p.println("         [--sheet NAME|NUMBER] (spreadsheets, with the POI adapter)");
        p.println("         [--mode auto|data|dictionary] [--max-rows N] [--map table|column|description|type|schema=HEADER] [--overwrite]");
        p.println("         [--interface-direction SOURCING|DISTRIBUTION]   draft OpenAPI operations as interfaces");
        p.println("         [--db-name NAME --output FILE [--db-type TYPE] [--default-schema S] [--format json|yaml]]  write a draft manifest");
        p.println("Dataset manifests (one per table)");
        p.println("  ingest --manifest-input FILE --table-metadata FILE --dataset-output DIR [--overwrite]   tables from a manifest JSON");
        p.println("  ingest --sql-input FILE [--sql-input FILE ...] [--default-schema S] [--dialect D] --table-metadata FILE --dataset-output DIR [--overwrite]");
        p.println("         the same, with the tables read from SQL DDL instead of a manifest JSON");
        p.println("         [--column-metadata FILE]  system data elements per column (table name, column name, system data element)");
        p.println("         [--table-list FILE [--domain TEXT] [--boundary TEXT]]  generate only for the tables in FILE (table name column, one-column CSV or one table per line);");
        p.println("         --table-metadata then becomes optional: --domain and --boundary give the defaults for tables without a metadata row");
        p.println("         [--table-descriptions FILE]  CSV of table descriptions (a table column and a description column) used as each dataset's description");
        p.println("         [--no-upstream]  no upstreamDatasets; the metadata needs only table name, domain, boundary");
        p.println("         [--database-name NAME] [--schema-name NAME]  the database and schema parts of every dataset name; default a2 and dbo, so names are a2.dbo.tableName");
        p.println("         [--combine-output FILE [--combine-count N | --combine-tables A,B,...] [--combined-only]]  also write one combined manifest");
        p.println("  combine --input FILE_OR_DIR [--input ...] --output FILE [--count N | --tables A,B,...] [--overwrite]   combine dataset manifests into one");
        p.println("JSON files");
        p.println("  json OPERATION --input FILE [options]                 get, keys, stats, format, set, delete, merge, flatten, csv, find, validate");
        p.println("         run 'json' alone to list the operations and their options");
        p.println("  compare --input FILE_OR_DIR --input FILE_OR_DIR [...] --output REPORT.html   compare JSON files and write an HTML report");
        p.println("         [--json-output FILE] [--path SECTION] [--key FIELD,...] [--title TEXT] [--max-rows N] [--lenient] [--fail-on-diff] [--overwrite]");
        p.println("Servers, agents and information");
        p.println("  web [--bind ADDR] [--port N] [--max-body-bytes N]   start the web UI (default 127.0.0.1:8080)");
        p.println("  mcp [--root DIR] [--read-only] [--audit-log FILE]   MCP server on standard input/output for GitHub Copilot (VS Code, IntelliJ, CLI): the agent's tools");
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
            if (FindingGuide.find(f.code()).isPresent()) {
                p.println("    More: casc-generator explain " + f.code() + "   (all codes: docs/FINDINGS.md)");
            }
        }
        p.println("Summary: " + r.errors().size() + " error(s), " + r.warnings().size() + " warning(s), "
                + r.infos().size() + " info. Validation level: " + ExportService.LEVEL
                + " (checked only against this tool's provisional rules; not the official CasC schema and not FDC acceptance).");
        if (!r.findings().isEmpty()) {
            p.println("Errors block the export. Warnings need your attention but do not block. Info is for your information. "
                    + "To understand a finding, run: casc-generator explain CODE");
        }
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

    private static SourceInput readSource(String file) throws IOException {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) {
            throw new IOException("Input file not found or not a regular file: " + p);
        }
        if (Files.size(p) > MAX_INPUT_BYTES) {
            throw new IOException("Input file is larger than " + MAX_INPUT_BYTES + " bytes: " + p);
        }
        return SourceInput.fromBytes(p.getFileName().toString(), Files.readAllBytes(p));
    }

    /** ingest --manifest-input M --table-metadata C --dataset-output DIR: one dataset manifest per table. */
    private static int ingestDatasets(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String manifest = o.get("--manifest-input", null);
        List<String> sqlFiles = o.all("--sql-input");
        String meta = o.get("--table-metadata", null);
        String dir = o.get("--dataset-output", null);
        if ((manifest == null) == sqlFiles.isEmpty()) {
            throw new UsageException("Give the tables either as --manifest-input FILE (a manifest JSON) or as --sql-input FILE (SQL DDL, repeatable), not both and not neither.");
        }
        String listFile = o.get("--table-list", null);
        if ((meta == null && listFile == null) || dir == null) {
            throw new UsageException("--dataset-output DIR and --table-metadata FILE and/or --table-list FILE are required with --manifest-input or --sql-input.");
        }
        if (listFile == null && (o.get("--domain", null) != null || o.get("--boundary", null) != null)) {
            throw new UsageException("--domain and --boundary give defaults for the tables of a --table-list; add --table-list FILE.");
        }
        String combineOut = o.get("--combine-output", null);
        if (combineOut == null && (o.get("--combine-count", null) != null || o.get("--combine-tables", null) != null || o.flags().contains("--combined-only"))) {
            throw new UsageException("--combine-count, --combine-tables and --combined-only need --combine-output FILE.");
        }
        DatasetManifestCombiner.Selection selection = selection(o);
        DatasetManifestIngestion.Result r;
        try {
            SourceInput metaInput = meta == null ? null : readSource(meta);
            String descFile = o.get("--table-descriptions", null);
            DatasetManifestIngestion.TableSelection tableSelection = listFile == null && descFile == null ? null
                    : new DatasetManifestIngestion.TableSelection(listFile == null ? null : readSource(listFile), o.get("--domain", null), o.get("--boundary", null),
                            descFile == null ? null : readSource(descFile));
            SourceInput columns = o.get("--column-metadata", null) == null ? null : readSource(o.get("--column-metadata", null));
            if (manifest != null) {
                r = DatasetManifestIngestion.generate(readSource(manifest), metaInput, columns, !o.flags().contains("--no-upstream"), o.get("--database-name", null), o.get("--schema-name", null), tableSelection);
            } else {
                Map<String, String> m = new LinkedHashMap<>();
                if (o.get("--dialect", null) != null) {
                    m.put("dialect", o.get("--dialect", null));
                }
                IngestOptions options;
                try {
                    options = IngestOptions.fromMap(m);
                } catch (IllegalArgumentException e) {
                    throw new UsageException(e.getMessage());
                }
                List<SourceInput> sql = new ArrayList<>();
                for (String f : sqlFiles) {
                    sql.add(readSource(f));
                }
                r = DatasetManifestIngestion.generateFromSql(sql, metaInput, options, o.get("--default-schema", null), columns, !o.flags().contains("--no-upstream"), o.get("--database-name", null), o.get("--schema-name", null), tableSelection);
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        printReport(new ValidationReport(r.findings()), r.hasErrors() ? err : out);
        if (r.hasErrors()) {
            err.println("Nothing was written.");
            return INVALID;
        }
        Path target = Path.of(dir).toAbsolutePath();
        try {
            if (!Files.isDirectory(target)) {
                Files.createDirectories(target);
            }
            if (combineOut != null) {
                List<SourceInput> parts = new ArrayList<>();
                for (DatasetManifestIngestion.Generated g : r.manifests()) {
                    parts.add(SourceInput.of(g.fileName(), g.content()));
                }
                DatasetManifestCombiner.Result c = DatasetManifestCombiner.combine(parts, selection);
                printReport(new ValidationReport(c.findings()), c.hasErrors() ? err : out);
                if (c.hasErrors()) {
                    err.println("Nothing was written.");
                    return INVALID;
                }
                if (!o.flags().contains("--combined-only")) {
                    writeIndividual(r, target, o, out);
                }
                writeAtomically(Path.of(combineOut), c.content(), o.flags().contains("--overwrite"));
                out.println("Wrote " + c.datasets() + " dataset(s) combined into " + Path.of(combineOut).toAbsolutePath());
                return OK;
            }
            writeIndividual(r, target, o, out);
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        return OK;
    }

    private static void writeIndividual(DatasetManifestIngestion.Result r, Path target, Opts o, PrintStream out) throws IOException {
        for (DatasetManifestIngestion.Generated g : r.manifests()) {
            writeAtomically(target.resolve(g.fileName()), g.content(), o.flags().contains("--overwrite"));
            out.println("Wrote " + g.fileName() + " (table " + g.table() + ")");
        }
        out.println("Wrote " + r.manifests().size() + " dataset manifest(s) to " + target);
    }

    /** All datasets, the first --count N, or the tables named in --tables A,B (one of the two at most). */
    private static DatasetManifestCombiner.Selection selection(Opts o) throws UsageException {
        String count = o.get("--combine-count", null) != null ? o.get("--combine-count", null) : o.get("--count", null);
        String tables = o.get(o.get("--combine-tables", null) != null ? "--combine-tables" : "--tables", null);
        if (count != null && tables != null) {
            throw new UsageException("Use either a count (the first N datasets) or a list of tables, not both.");
        }
        if (count != null) {
            return new DatasetManifestCombiner.Selection(number(count, "--count", 1, 1_000_000), null);
        }
        if (tables != null) {
            List<String> names = new ArrayList<>();
            for (String t : tables.split(",")) {
                if (!t.isBlank()) {
                    names.add(t.trim());
                }
            }
            if (names.isEmpty()) {
                throw new UsageException("--tables needs at least one table name, separated by commas.");
            }
            return new DatasetManifestCombiner.Selection(null, names);
        }
        return DatasetManifestCombiner.Selection.all();
    }

    /** combine --input FILE_OR_DIR ... --output FILE: one manifest from several dataset manifests. */
    private static int combine(Opts o, PrintStream out, PrintStream err) throws UsageException {
        List<String> inputs = o.all("--input");
        String output = o.get("--output", null);
        if (inputs.isEmpty() || output == null) {
            throw new UsageException("combine needs --input FILE_OR_DIR (repeatable) and --output FILE.");
        }
        DatasetManifestCombiner.Selection selection = selection(o);
        Path outPath = Path.of(output).toAbsolutePath();
        List<SourceInput> parts = new ArrayList<>();
        try {
            for (String in : inputs) {
                Path p = Path.of(in);
                if (Files.isDirectory(p)) {
                    List<Path> files;
                    try (java.util.stream.Stream<Path> s = Files.list(p)) {
                        files = s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                                .filter(f -> !f.toAbsolutePath().equals(outPath)).sorted().toList();
                    }
                    for (Path f : files) {
                        parts.add(readSource(f.toString()));
                    }
                } else {
                    parts.add(readSource(in));
                }
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        if (parts.isEmpty()) {
            err.println("Error: no .json files were found in the input.");
            return INVALID;
        }
        DatasetManifestCombiner.Result c = DatasetManifestCombiner.combine(parts, selection);
        printReport(new ValidationReport(c.findings()), c.hasErrors() ? err : out);
        if (c.hasErrors()) {
            err.println("Nothing was written.");
            return INVALID;
        }
        try {
            writeAtomically(outPath, c.content(), o.flags().contains("--overwrite"));
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        out.println("Wrote " + c.datasets() + " dataset(s) combined into " + outPath);
        return OK;
    }


    // ------------------------------------------------------------------ json and compare

    private static final String JSON_OPERATIONS = String.join("\n",
            "json OPERATION --input FILE [options]",
            "  get       --path P [--lines] [--with-paths] [--compact] [--output FILE]   print the section(s) a path selects (--lines: one value per line)",
            "  keys      [--path P]                                           list the keys (objects) or the item kinds (arrays) at a path",
            "  stats     [--path P]                                           count objects, arrays, strings, numbers, booleans, nulls and depth",
            "  format    [--compact] [--sort-keys] [--output FILE]            pretty-print or minify, optionally with sorted keys",
            "  set       --path P --value JSON --output FILE                  set a value (creates missing keys; * and filters set several)",
            "  delete    --path P --output FILE                               remove the selected keys or items",
            "  merge     --input A --input B [...] --output FILE              deep-merge several files (later wins; arrays are replaced)",
            "  flatten   [--path P] [--output FILE]                           one line per value: path = value",
            "  csv       --path P [--output FILE]                             an array of objects as CSV",
            "  find      --text T [--max N]                                   paths whose key or value contains the text",
            "  validate                                                       check that the file is valid JSON",
            "Common options: --lenient (accept trailing or missing commas), --overwrite (replace an existing --output file).",
            "Paths: databases[0].schemas[*].tables[?physicalName=TAPSAccount]  [*] every item, [n] one item (-1 last), [a:b] slice,",
            "       [?field=value] filter (!= and ~contains also work), ..key any depth, [\"a.b\"] quoted key. See docs/COMMANDS.md.");

    private record Loaded(Object root, long bytes, boolean lenient, Map<String, String> skipped) {}

    private static Loaded loadJson(String file, boolean lenient) throws IOException {
        SourceInput in = readSource(file);
        if (in.readError() != null || in.text() == null) {
            throw new IOException(file + ": " + (in.readError() == null ? "the file has no text" : in.readError()));
        }
        try {
            return new Loaded(Json.parse(in.text()), in.text().length(), false, Map.of());
        } catch (JsonException strict) {
            if (lenient) {
                try {
                    return new Loaded(LenientJson.parse(in.text()), in.text().length(), true, Map.of());
                } catch (IllegalArgumentException e) {
                    LenientJson.Partial partial = LenientJson.parseTopLevel(in.text());
                    if (partial != null && !partial.root().isEmpty()) {
                        return new Loaded(partial.root(), in.text().length(), true, partial.skipped());
                    }
                    throw new IOException(file + " is not valid JSON, even when commas are read leniently: " + e.getMessage() + ". Fix the syntax at that position.");
                }
            }
            throw new IOException(file + " is not valid JSON: " + strict.getMessage() + ". Fix the syntax at that position, or add --lenient to accept trailing or missing commas.");
        }
    }

    private static void skippedNote(String file, Loaded l, PrintStream err) {
        if (!l.skipped().isEmpty()) {
            err.println("Note: these top-level sections of " + file + " could not be read and were left out: "
                    + String.join("; ", l.skipped().entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList())
                    + ". Fix the syntax there if you need them.");
        }
    }

    private static List<JsonPath.Match> selectOrUsage(Object root, String path) throws UsageException {
        try {
            return JsonPath.select(root, path == null ? "$" : path);
        } catch (IllegalArgumentException e) {
            throw new UsageException("Bad --path '" + path + "': " + e.getMessage());
        }
    }

    private static void emit(String text, Opts o, PrintStream out) throws IOException {
        if (o.get("--output", null) != null) {
            writeAtomically(Path.of(o.get("--output", null)), text, o.flags().contains("--overwrite"));
            out.println("Wrote " + Path.of(o.get("--output", null)).toAbsolutePath());
        } else {
            out.print(text);
        }
    }

    private static String render(Object v, Opts o) {
        return (o.flags().contains("--compact") ? Json.writeCompact(v) : Json.write(v)) + "\n";
    }

    private static int jsonCommand(String[] args, PrintStream out, PrintStream err) throws UsageException {
        if (args.length < 2 || args[1].startsWith("--")) {
            out.println(JSON_OPERATIONS);
            return args.length < 2 ? OK : USAGE;
        }
        String op = args[1];
        if (!Set.of("get", "keys", "stats", "format", "set", "delete", "merge", "flatten", "csv", "find", "validate").contains(op)) {
            err.println("Unknown json operation '" + op + "'.");
            err.println(JSON_OPERATIONS);
            return USAGE;
        }
        Opts o = parse(args, 2, Set.of("--input", "--path", "--value", "--text", "--output", "--max"),
                Set.of("--compact", "--sort-keys", "--lenient", "--overwrite", "--with-paths", "--lines"));
        List<String> inputs = o.all("--input");
        if (inputs.isEmpty()) {
            throw new UsageException("json " + op + " needs --input FILE.");
        }
        if (!op.equals("merge") && inputs.size() > 1) {
            throw new UsageException("json " + op + " takes one --input FILE; use 'merge' to combine files or 'compare' to compare them.");
        }
        String path = o.get("--path", null);
        boolean lenient = o.flags().contains("--lenient");
        try {
            if (op.equals("merge")) {
                if (inputs.size() < 2 || o.get("--output", null) == null) {
                    throw new UsageException("json merge needs at least two --input files and --output FILE.");
                }
                Object merged = null;
                for (String f : inputs) {
                    Object next = loadJson(f, lenient).root();
                    merged = merged == null ? next : JsonOps.merge(merged, next);
                }
                emit(render(o.flags().contains("--sort-keys") ? JsonOps.sortKeys(merged) : merged, o), o, out);
                return OK;
            }
            Loaded loaded = loadJson(inputs.get(0), lenient);
            if (loaded.lenient()) {
                err.println("Note: " + inputs.get(0) + " is not strictly valid JSON; it was read leniently (trailing or missing commas accepted). Output is valid JSON.");
                skippedNote(inputs.get(0), loaded, err);
            }
            Object root = loaded.root();
            switch (op) {
                case "validate": {
                    out.println("OK: " + inputs.get(0) + " is " + (loaded.lenient() ? "readable JSON (only with --lenient)" : "valid JSON") + ": " + JsonOps.describe(root) + ".");
                    return OK;
                }
                case "format": {
                    Object v = o.flags().contains("--sort-keys") ? JsonOps.sortKeys(root) : root;
                    emit(render(v, o), o, out);
                    return OK;
                }
                case "get": {
                    List<JsonPath.Match> m = selectOrUsage(root, path);
                    if (m.isEmpty()) {
                        err.println("No value found at path '" + path + "'. Run 'casc-generator json keys --input " + inputs.get(0) + "' to see what exists, or check the spelling (keys are case-sensitive).");
                        return INVALID;
                    }
                    Object result;
                    if (o.flags().contains("--with-paths")) {
                        Map<String, Object> mp = new LinkedHashMap<>();
                        for (JsonPath.Match x : m) {
                            mp.put(x.path(), x.value());
                        }
                        result = mp;
                    } else if (m.size() == 1) {
                        result = m.get(0).value();
                    } else {
                        List<Object> vals = new ArrayList<>();
                        for (JsonPath.Match x : m) {
                            vals.add(x.value());
                        }
                        result = vals;
                    }
                    if (o.flags().contains("--lines")) {
                        StringBuilder lines = new StringBuilder();
                        for (JsonPath.Match x : m) {
                            Object v = x.value();
                            lines.append(v instanceof String str ? str : v instanceof Map<?, ?> || v instanceof List<?> ? Json.writeCompact(v) : v == null ? "null" : v instanceof java.math.BigDecimal bd ? bd.toPlainString() : String.valueOf(v)).append("\n");
                        }
                        emit(lines.toString(), o, out);
                        return OK;
                    }
                    emit(render(result, o), o, out);
                    if (m.size() > 1 && o.get("--output", null) == null) {
                        err.println(m.size() + " matches, printed as an array" + (o.flags().contains("--with-paths") ? "" : " (add --with-paths to see where each came from)") + ".");
                    }
                    return OK;
                }
                case "keys": {
                    List<JsonPath.Match> m = selectOrUsage(root, path);
                    if (m.isEmpty()) {
                        err.println("No value found at path '" + path + "'.");
                        return INVALID;
                    }
                    for (JsonPath.Match x : m) {
                        out.println(x.path() + ": " + JsonOps.describe(x.value()));
                        if (x.value() instanceof Map<?, ?> mp) {
                            for (Map.Entry<?, ?> e : mp.entrySet()) {
                                out.println("  " + e.getKey() + "  (" + JsonOps.describe(e.getValue()) + ")");
                            }
                        } else if (x.value() instanceof List<?> l && !l.isEmpty()) {
                            Map<String, Integer> kinds = new LinkedHashMap<>();
                            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
                            for (Object item : l) {
                                kinds.merge(JsonOps.type(item), 1, Integer::sum);
                                if (item instanceof Map<?, ?> im) {
                                    im.keySet().forEach(k -> keys.add(String.valueOf(k)));
                                }
                            }
                            out.println("  items: " + kinds.toString().replace("{", "").replace("}", "").replace("=", " x"));
                            if (!keys.isEmpty()) {
                                out.println("  item keys: " + String.join(", ", keys.stream().limit(40).toList()) + (keys.size() > 40 ? ", ..." : ""));
                            }
                        }
                    }
                    return OK;
                }
                case "stats": {
                    List<JsonPath.Match> m = selectOrUsage(root, path);
                    if (m.isEmpty()) {
                        err.println("No value found at path '" + path + "'.");
                        return INVALID;
                    }
                    for (JsonPath.Match x : m) {
                        JsonOps.Stats st = JsonOps.stats(x.value());
                        out.println(x.path() + ": " + JsonOps.describe(x.value()));
                        out.println("  values " + st.nodes() + ", objects " + st.objects() + ", arrays " + st.arrays() + ", strings " + st.strings() + ", numbers " + st.numbers()
                                + ", booleans " + st.booleans() + ", nulls " + st.nulls() + ", depth " + st.maxDepth());
                    }
                    return OK;
                }
                case "flatten": {
                    StringBuilder b = new StringBuilder();
                    for (JsonPath.Match x : selectOrUsage(root, path)) {
                        for (JsonPath.Match leaf : JsonOps.flatten(x.value(), x.path())) {
                            b.append(leaf.path()).append(" = ").append(Json.writeCompact(leaf.value())).append("\n");
                        }
                    }
                    emit(b.toString(), o, out);
                    return OK;
                }
                case "csv": {
                    List<JsonPath.Match> m = selectOrUsage(root, path);
                    if (m.size() != 1) {
                        err.println(m.isEmpty() ? "No value found at path '" + path + "'." : "csv needs a path that selects one array of objects; '" + path + "' selects " + m.size() + " values.");
                        return INVALID;
                    }
                    try {
                        emit(JsonOps.csv(m.get(0).value()), o, out);
                    } catch (IllegalArgumentException e) {
                        err.println("Error: " + e.getMessage());
                        return INVALID;
                    }
                    return OK;
                }
                case "find": {
                    String text = o.get("--text", null);
                    if (text == null || text.isEmpty()) {
                        throw new UsageException("json find needs --text TEXT.");
                    }
                    int max = o.get("--max", null) == null ? 200 : number(o.get("--max", null), "--max", 1, 100000);
                    List<JsonOps.Found> found = JsonOps.find(root, text, max);
                    for (JsonOps.Found f : found) {
                        String t = f.text().length() > 100 ? f.text().substring(0, 100) + "..." : f.text();
                        out.println(f.path() + "   (" + f.where() + ")   " + t);
                    }
                    out.println(found.isEmpty() ? "No key or value contains '" + text + "'." : found.size() + " match(es)" + (found.size() >= max ? " (stopped at --max " + max + ")" : "") + ".");
                    return OK;
                }
                case "set":
                case "delete": {
                    if (path == null || path.isBlank()) {
                        throw new UsageException("json " + op + " needs --path P.");
                    }
                    JsonOps.Change c;
                    try {
                        if (op.equals("set")) {
                            String raw = o.get("--value", null);
                            if (raw == null) {
                                throw new UsageException("json set needs --value JSON (text must be quoted, for example --value '\"Account ID\"').");
                            }
                            Object value;
                            try {
                                value = Json.parse(raw);
                            } catch (JsonException e) {
                                value = raw;      // not JSON: use it as plain text
                            }
                            c = JsonOps.set(root, path, value);
                        } else {
                            c = JsonOps.delete(root, path);
                        }
                    } catch (IllegalArgumentException e) {
                        throw new UsageException("Bad --path '" + path + "': " + e.getMessage());
                    }
                    if (c.changes() == 0) {
                        err.println("Nothing was changed: the path '" + path + "' selected nothing. Run 'casc-generator json keys --input " + inputs.get(0) + "' to see what exists.");
                        return INVALID;
                    }
                    Object result = o.flags().contains("--sort-keys") ? JsonOps.sortKeys(c.root()) : c.root();
                    emit(render(result, o), o, out);
                    (o.get("--output", null) == null ? err : out).println(c.changes() + " change(s) made" + (o.get("--output", null) == null ? " (printed above; add --output FILE to save)." : "."));
                    return OK;
                }
                default:
                    throw new UsageException("Unknown json operation '" + op + "'.");
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
    }

    /** compare --input FILE_OR_DIR ... --output REPORT.html */
    private static int compare(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String html = o.get("--output", null);
        String jsonOut = o.get("--json-output", null);
        if (html == null && jsonOut == null) {
            throw new UsageException("compare needs --output REPORT.html (the report) and/or --json-output FILE.");
        }
        boolean lenient = o.flags().contains("--lenient");
        String section = o.get("--path", null);
        if (section != null) {
            try {
                JsonPath.check(section);
            } catch (IllegalArgumentException e) {
                throw new UsageException("Bad --path '" + section + "': " + e.getMessage());
            }
        }
        List<String> keyFields = new ArrayList<>();
        if (o.get("--key", null) != null) {
            for (String k : o.get("--key", null).split(",")) {
                if (!k.isBlank()) {
                    keyFields.add(k.trim());
                }
            }
        }
        int maxRows = o.get("--max-rows", null) == null ? 5000 : number(o.get("--max-rows", null), "--max-rows", 1, 1_000_000);
        List<Path> files = new ArrayList<>();
        try {
            for (String in : o.all("--input")) {
                Path p = Path.of(in);
                if (Files.isDirectory(p)) {
                    try (java.util.stream.Stream<Path> s = Files.list(p)) {
                        files.addAll(s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")).sorted().toList());
                    }
                } else {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        if (files.size() < 2) {
            throw new UsageException("compare needs at least two JSON files (give --input twice, or a folder that holds several .json files). Found " + files.size() + ".");
        }
        Set<String> used = new HashSet<>();
        List<JsonCompare.Source> sources = new ArrayList<>();
        try {
            for (Path f : files) {
                Loaded l = loadJson(f.toString(), lenient);
                Object root = l.root();
                if (section != null) {
                    List<JsonPath.Match> m = selectOrUsage(root, section);
                    if (m.isEmpty()) {
                        err.println("Error: " + f + " has nothing at --path '" + section + "'. Run 'casc-generator json keys --input " + f + "' to see what exists.");
                        return INVALID;
                    }
                    if (m.size() == 1) {
                        root = m.get(0).value();
                    } else {
                        List<Object> vals = new ArrayList<>();
                        for (JsonPath.Match x : m) {
                            vals.add(x.value());
                        }
                        root = vals;
                    }
                }
                String name = f.getFileName().toString();
                if (!used.add(name)) {
                    name = f.toString();
                    used.add(name);
                }
                sources.add(new JsonCompare.Source(name, root, l.bytes()));
                if (l.lenient()) {
                    err.println("Note: " + f + " is not strictly valid JSON; it was read leniently.");
                    skippedNote(f.toString(), l, err);
                }
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        JsonCompare.Report r = JsonCompare.compare(sources, keyFields, maxRows, section);
        out.println("Compared " + sources.size() + " files (baseline: " + sources.get(0).name() + ")");
        for (int i = 1; i < r.files().size(); i++) {
            JsonCompare.FileSummary f = r.files().get(i);
            out.println("  " + f.name() + ": " + f.added() + " added, " + f.removed() + " removed, " + f.changed() + " changed, " + f.same() + " same");
        }
        out.println(r.differing() + " of " + r.totalPaths() + " values differ somewhere; " + r.identical() + " are identical in every file.");
        try {
            if (html != null) {
                String generated = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                writeAtomically(Path.of(html), HtmlReport.render(r, o.get("--title", "JSON comparison"), generated), o.flags().contains("--overwrite"));
                out.println("Wrote HTML report to " + Path.of(html).toAbsolutePath());
            }
            if (jsonOut != null) {
                writeAtomically(Path.of(jsonOut), HtmlReport.toJson(r), o.flags().contains("--overwrite"));
                out.println("Wrote JSON report to " + Path.of(jsonOut).toAbsolutePath());
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
        return o.flags().contains("--fail-on-diff") && r.differing() > 0 ? INVALID : OK;
    }

    /** mcp [--root DIR] [--read-only] [--audit-log FILE]: the application's tools for an agent (GitHub Copilot), over standard input and output. */
    private static int mcp(Opts o, InputStream in, PrintStream out, PrintStream err) throws UsageException {
        String rootText = o.get("--root", null) != null ? o.get("--root", null) : getenv("CASC_INPUT_ROOT");
        Path rootPath = Path.of(rootText == null || rootText.isBlank() ? "." : rootText).toAbsolutePath();
        if (!Files.isDirectory(rootPath)) {
            throw new UsageException("--root " + rootPath + " is not a folder.");
        }
        String auditText = o.get("--audit-log", null) != null ? o.get("--audit-log", null) : getenv("CASC_AUDIT_LOG");
        try {
            Sandbox sandbox = new Sandbox(rootPath, o.flags().contains("--read-only"));
            Audit audit = auditText == null || auditText.isBlank() ? Audit.none() : new Audit(Path.of(auditText).toAbsolutePath());
            String v = Cli.class.getPackage().getImplementationVersion();
            err.println("casc-generator MCP server ready. Root: " + sandbox.root() + (sandbox.readOnly() ? " (read-only)" : "") + (auditText == null ? "" : ". Audit log: " + auditText));
            new McpServer(new Tools(sandbox, audit), v == null ? "dev" : v).serve(in, out, err);
            return OK;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return IO;
        }
    }

    private static int ingest(Opts o, PrintStream out, PrintStream err) throws UsageException {
        if (o.get("--manifest-input", null) != null || !o.all("--sql-input").isEmpty() || o.get("--table-metadata", null) != null
                || o.get("--column-metadata", null) != null || o.get("--table-list", null) != null || o.get("--table-descriptions", null) != null || o.get("--database-name", null) != null || o.get("--schema-name", null) != null || o.get("--dataset-output", null) != null) {
            return ingestDatasets(o, out, err);
        }
        List<String> paths = o.all("--input-files");
        List<String> urls = o.all("--url");
        if (paths.isEmpty() && urls.isEmpty()) {
            throw new UsageException("--input-files PATH or --url URL is required (repeat the options for several sources).");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] pair : new String[][] {{"--dialect", "dialect"}, {"--delimiter", "delimiter"}, {"--header-row", "headerRow"},
                {"--mode", "mode"}, {"--max-rows", "maxRows"}, {"--sheet", "sheet"}}) {
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

    private static int explain(String code, PrintStream out, PrintStream err) {
        if (code == null) {
            out.println("Finding codes (run 'casc-generator explain CODE' for the meaning, the fix and where to look):");
            for (FindingGuide.Entry e : FindingGuide.all()) {
                out.printf("  %-34s %s%n", e.code(), e.title());
            }
            return OK;
        }
        var found = FindingGuide.find(code);
        if (found.isEmpty()) {
            err.println("Unknown finding code '" + code + "'. Run 'casc-generator explain' to list all codes.");
            return USAGE;
        }
        out.print(FindingGuide.text(found.get()));
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

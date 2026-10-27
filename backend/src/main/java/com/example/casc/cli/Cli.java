package com.example.casc.cli;

import com.example.casc.app.Capability;
import com.example.casc.app.CapabilityService;
import com.example.casc.app.CapabilityUnavailableException;
import com.example.casc.app.ExportResult;
import com.example.casc.app.ExportService;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import com.example.casc.web.WebServer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private record Opts(Map<String, String> values, Set<String> flags) {
        String get(String k, String fallback) {
            return values.getOrDefault(k, fallback);
        }
    }

    private static Opts parse(String[] args, int from, Set<String> valued, Set<String> flags) throws UsageException {
        Map<String, String> values = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (valued.contains(a)) {
                if (i + 1 >= args.length) {
                    throw new UsageException("Option " + a + " needs a value.");
                }
                values.put(a, args[++i]);
            } else if (flags.contains(a)) {
                seen.add(a);
            } else {
                throw new UsageException("Unknown option: " + a);
            }
        }
        return new Opts(values, seen);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
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
                case "web":
                    return web(parse(args, 1, Set.of("--bind", "--port", "--max-body-bytes"), Set.of()), out, err);
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
        p.println("  capabilities [--json]                                 list available and unavailable capabilities");
        p.println("  version | help");
        p.println("Exit codes: 0 ok, 1 validation errors, 2 usage error, 3 I/O or parse failure, 4 internal error.");
        p.println("Environment defaults for web: CASC_BIND, CASC_PORT.");
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

    private static int web(Opts o, PrintStream out, PrintStream err) throws UsageException {
        String bind = o.get("--bind", envOr("CASC_BIND", "127.0.0.1"));
        int port = number(o.get("--port", envOr("CASC_PORT", "8080")), "port", 0, 65535);
        int max = number(o.get("--max-body-bytes", String.valueOf(5 * 1024 * 1024)), "max-body-bytes", 1024, 100_000_000);
        try {
            WebServer server = new WebServer(new WebServer.Config(bind, port, max));
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
        String v = System.getenv(name);
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

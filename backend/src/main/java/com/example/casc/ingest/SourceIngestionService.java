package com.example.casc.ingest;

import com.example.casc.ingest.RawMetadataBundle.Builder;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.validation.Finding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Routes each source to a strategy by file extension and merges the results.
 * Formats that need an unavailable library are reported as errors with an alternative; nothing is skipped silently.
 */
public final class SourceIngestionService {

    private record Unavailable(String format, String needs, String alternative) {}

    private static final Map<String, Unavailable> UNAVAILABLE = new LinkedHashMap<>();

    static {
        Unavailable xlsx = new Unavailable("XLSX", "Apache POI",
                "Export each sheet to CSV or TSV and add those files instead.");
        UNAVAILABLE.put("xlsx", xlsx);
        UNAVAILABLE.put("xlsm", xlsx);
        UNAVAILABLE.put("xls", xlsx);
        Unavailable docx = new Unavailable("DOCX", "Apache POI",
                "Save the document as TXT or Markdown, and export its tables to CSV.");
        UNAVAILABLE.put("docx", docx);
        UNAVAILABLE.put("doc", docx);
        UNAVAILABLE.put("pdf", new Unavailable("PDF", "Apache PDFBox",
                "Provide a text export of the PDF, and its tables as CSV. Text extraction does not reconstruct tables reliably."));
        Unavailable html = new Unavailable("HTML", "an approved HTML parser such as JSoup",
                "Save the content as Markdown or text, or use a JSON API when URL support arrives.");
        UNAVAILABLE.put("html", html);
        UNAVAILABLE.put("htm", html);
        Unavailable yaml = new Unavailable("YAML", "an approved YAML parser (for example Jackson YAML)",
                "Convert the file to JSON (for OpenAPI, export the JSON form from Swagger UI or the API gateway) and add that instead.");
        UNAVAILABLE.put("yaml", yaml);
        UNAVAILABLE.put("yml", yaml);
    }

    /** True for document formats that are known but cannot be read in this build. */
    public static boolean isUnavailableExtension(String ext) {
        return UNAVAILABLE.containsKey(ext);
    }

    /** Formats whose files are binary: they are read as bytes, never decoded as text. */
    private static final Set<String> BINARY = Set.of("xlsx", "xlsm", "xls", "docx", "pdf");

    public static boolean isBinaryExtension(String ext) {
        return BINARY.contains(ext);
    }

    private final Map<String, SourceIngestionStrategy> byExtension = new HashMap<>();
    private final List<String> adapterProblems = new ArrayList<>();

    public SourceIngestionService(List<SourceIngestionStrategy> strategies) {
        for (SourceIngestionStrategy s : strategies) {
            for (String e : s.extensions()) {
                byExtension.put(e, s);
            }
        }
    }

    /** Only the built-in readers, whatever adapters are installed. Used by tests that describe the dependency-free behaviour. */
    public static SourceIngestionService baseline() {
        return new SourceIngestionService(List.of(new DelimitedStrategy(), new TextStrategy(), new SqlStrategy(), new OpenApiStrategy()));
    }

    private static volatile SourceIngestionService standard;

    /**
     * The built-in readers plus every adapter that ServiceLoader finds (spreadsheets, Word, PDF and HTML readers are optional
     * adapters). An adapter whose library cannot be loaded is skipped and listed in adapterProblems().
     */
    public static SourceIngestionService standard() {
        SourceIngestionService s = standard;
        if (s == null) {
            List<SourceIngestionStrategy> all = new ArrayList<>(List.of(new DelimitedStrategy(), new TextStrategy(), new SqlStrategy(), new OpenApiStrategy()));
            List<String> problems = new ArrayList<>();
            var it = ServiceLoader.load(SourceIngestionStrategy.class).iterator();
            while (true) {
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    all.add(it.next());
                } catch (ServiceConfigurationError | LinkageError e) {
                    String m = String.valueOf(e.getMessage());
                    problems.add("An adapter could not be loaded (" + e.getClass().getSimpleName() + "): " + (m.length() > 200 ? m.substring(0, 200) : m));
                }
            }
            s = new SourceIngestionService(all);
            s.adapterProblems.addAll(problems);
            standard = s;
        }
        return s;
    }

    /** Adapters that were found on the class path but could not be loaded, for example because their library is missing. */
    public List<String> adapterProblems() {
        return List.copyOf(adapterProblems);
    }

    /** The reader for an extension, or null when there is none. */
    public SourceIngestionStrategy readerFor(String ext) {
        return byExtension.get(ext);
    }

    /** Lower-case extension of the last path segment, without the dot; empty if none. */
    public static String extension(String name) {
        String n = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public Set<String> supportedExtensions() {
        return new TreeSet<>(byExtension.keySet());
    }

    /**
     * Extensions considered when scanning a directory: supported ones (except json, which InputFiles accepts only when
     * the file looks like OpenAPI) plus document formats that are known but unavailable, so they are reported and not skipped.
     * YAML files are skipped in directory scans because configuration files are common; naming one explicitly is reported.
     */
    public Set<String> knownExtensions() {
        Set<String> s = new TreeSet<>(byExtension.keySet());
        s.remove("json");
        for (Map.Entry<String, Unavailable> e : UNAVAILABLE.entrySet()) {
            if (!e.getValue().format().equals("YAML")) {
                s.add(e.getKey());
            }
        }
        return s;
    }

    public RawMetadataBundle ingest(List<SourceInput> inputs, IngestOptions options) {
        List<RawMetadataBundle> parts = new ArrayList<>();
        Builder pre = new Builder();
        if (inputs.isEmpty()) {
            pre.error("NO_SOURCES", "sources", "No sources were provided.", "Add at least one file.");
        }
        for (SourceInput original : inputs) {
            SourceInput in = original;
            String ext = extension(in.name());
            if (in.readError() != null) {
                pre.source(in.name(), ext, "REJECTED", in.readError());
                pre.error("SOURCE_UNREADABLE", in.name(), in.readError(), "Fix the file or its location and add it again.");
                continue;
            }
            if (in.isBinary() && !isBinaryExtension(ext)) {
                in = SourceInput.fromBytes(in.name(), in.bytes());          // a text format delivered as bytes
                if (in.readError() != null) {
                    pre.source(in.name(), ext, "REJECTED", in.readError());
                    pre.error("SOURCE_UNREADABLE", in.name(), in.readError(), "Fix the file or its location and add it again.");
                    continue;
                }
            }
            if (ext.equals("json") && !in.isBinary()) {
                var confluence = ConfluenceJson.unwrap(in);
                if (confluence.isPresent()) {
                    if (byExtension.containsKey("html")) {
                        in = confluence.get();
                        ext = "html";
                    } else {
                        pre.source(in.name(), "Confluence", "UNAVAILABLE", "Confluence page content is HTML and needs an HTML reader");
                        pre.error("FORMAT_UNAVAILABLE", in.name(), "This is a Confluence page (its content is HTML inside JSON). Reading it needs an HTML reader, "
                                + "which is not installed in this build.", "Install the HTML adapter (see docs/ADAPTERS.md), or export the page as Markdown, text or CSV.");
                        continue;
                    }
                }
            }
            Unavailable u = UNAVAILABLE.get(ext);
            if (u != null && !byExtension.containsKey(ext)) {
                pre.source(in.name(), u.format(), "UNAVAILABLE", u.format() + " needs " + u.needs());
                pre.error("FORMAT_UNAVAILABLE", in.name(), u.format() + " ingestion needs " + u.needs()
                        + ", which is not installed in this build (optional adapter, see docs/ADAPTERS.md).", u.alternative());
                continue;
            }
            SourceIngestionStrategy s = byExtension.get(ext);
            if (s == null) {
                pre.source(in.name(), ext.isEmpty() ? "unknown" : ext, "REJECTED", "unsupported file type");
                pre.error("FORMAT_UNKNOWN", in.name(), "Unsupported file type '" + ext + "'.",
                        "Supported: " + String.join(", ", supportedExtensions()) + ".");
                continue;
            }
            if (isBinaryExtension(ext) != in.isBinary()) {
                pre.source(in.name(), ext, "REJECTED", isBinaryExtension(ext) ? "binary format given as text" : "text format given as binary");
                pre.error("SOURCE_ENCODING", in.name(), ext.toUpperCase(Locale.ROOT) + " is a binary format; it must be supplied as the file's bytes, not as text.",
                        "Add the file itself (the web form and the contentBase64 request field carry binary files).");
                continue;
            }
            if (in.size() > options.maxSourceChars()) {
                pre.source(in.name(), ext, "REJECTED", "too large");
                pre.error("SOURCE_TOO_LARGE", in.name(), "The source has more than " + options.maxSourceChars() + (in.isBinary() ? " bytes." : " characters."),
                        "Split the file or raise maxSourceChars.");
                continue;
            }
            parts.add(s.ingest(in, options));
        }
        parts.add(pre.build());
        RawMetadataBundle merged = RawMetadataBundle.merge(parts);
        return conflicts(merged);
    }

    /** The same schema.table defined by more than one source is reported, never merged. */
    private static RawMetadataBundle conflicts(RawMetadataBundle m) {
        Map<String, RawTable> first = new HashMap<>();
        List<Finding> extra = new ArrayList<>();
        for (RawTable t : m.tables()) {
            if (!t.kind().equals("TABLE")) {
                continue;
            }
            String key = ((t.schema() == null ? "" : t.schema()) + "." + t.name()).toLowerCase(Locale.ROOT);
            RawTable prev = first.putIfAbsent(key, t);
            if (prev != null && !prev.source().equals(t.source())) {
                extra.add(Finding.warning("TABLE_CONFLICT", t.source() + ":" + t.location(),
                        "Table '" + t.name() + "' is also defined in " + prev.source() + ". The definitions were not merged.",
                        "Decide which source is authoritative, or rename one of the tables."));
            }
        }
        if (extra.isEmpty()) {
            return m;
        }
        List<Finding> all = new ArrayList<>(m.findings());
        all.addAll(extra);
        return new RawMetadataBundle(m.sources(), m.tables(), m.terms(), m.notes(), m.lineage(), m.interfaces(), m.structures(), all);
    }
}

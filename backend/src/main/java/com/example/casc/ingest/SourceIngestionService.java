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

    private final Map<String, SourceIngestionStrategy> byExtension = new HashMap<>();

    public SourceIngestionService(List<SourceIngestionStrategy> strategies) {
        for (SourceIngestionStrategy s : strategies) {
            for (String e : s.extensions()) {
                byExtension.put(e, s);
            }
        }
    }

    public static SourceIngestionService standard() {
        return new SourceIngestionService(List.of(new DelimitedStrategy(), new TextStrategy(), new SqlStrategy(), new OpenApiStrategy()));
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
        for (SourceInput in : inputs) {
            String ext = extension(in.name());
            if (in.readError() != null) {
                pre.source(in.name(), ext, "REJECTED", in.readError());
                pre.error("SOURCE_UNREADABLE", in.name(), in.readError(), "Fix the file or its location and add it again.");
                continue;
            }
            Unavailable u = UNAVAILABLE.get(ext);
            if (u != null) {
                pre.source(in.name(), u.format(), "UNAVAILABLE", u.format() + " needs " + u.needs());
                pre.error("FORMAT_UNAVAILABLE", in.name(), u.format() + " ingestion needs " + u.needs()
                        + ", which is not part of this dependency-free build.", u.alternative());
                continue;
            }
            SourceIngestionStrategy s = byExtension.get(ext);
            if (s == null) {
                pre.source(in.name(), ext.isEmpty() ? "unknown" : ext, "REJECTED", "unsupported file type");
                pre.error("FORMAT_UNKNOWN", in.name(), "Unsupported file type '" + ext + "'.",
                        "Supported: " + String.join(", ", supportedExtensions()) + ".");
                continue;
            }
            if (in.text().length() > options.maxSourceChars()) {
                pre.source(in.name(), ext, "REJECTED", "too large");
                pre.error("SOURCE_TOO_LARGE", in.name(), "The source has more than " + options.maxSourceChars() + " characters.",
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

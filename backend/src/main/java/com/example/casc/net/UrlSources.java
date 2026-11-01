package com.example.casc.net;

import com.example.casc.ingest.SourceIngestionService;
import com.example.casc.ingest.SourceInput;
import com.example.casc.net.UrlFetcher.FetchException;
import com.example.casc.net.UrlFetcher.Fetched;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns URLs into named SourceInputs so the normal ingestion path handles them.
 * The source name is host/path (never the query) with an extension taken from the URL path, or else from the
 * Content-Type: JSON, plain text, Markdown, CSV, TSV and SQL are read. HTML, YAML, Office and PDF responses are
 * reported as unavailable formats by the ingestion service, with the alternative. Only UTF-8 text is accepted.
 */
public final class UrlSources {
    private UrlSources() {}

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([^;\"\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("application/json", "json"), Map.entry("text/json", "json"),
            Map.entry("text/plain", "txt"), Map.entry("text/markdown", "md"), Map.entry("text/x-markdown", "md"),
            Map.entry("text/csv", "csv"), Map.entry("text/tab-separated-values", "tsv"),
            Map.entry("application/sql", "sql"), Map.entry("text/x-sql", "sql"),
            Map.entry("application/yaml", "yaml"), Map.entry("application/x-yaml", "yaml"), Map.entry("text/yaml", "yaml"),
            Map.entry("text/html", "html"), Map.entry("application/xhtml+xml", "html"));

    public static List<SourceInput> fetchAll(List<String> urls, UrlFetcher fetcher) {
        List<SourceInput> out = new ArrayList<>();
        Set<String> known = SourceIngestionService.standard().knownExtensions();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i).trim();
            if (url.isEmpty()) {
                continue;
            }
            if (out.size() >= UrlPolicy.MAX_URLS) {
                out.add(SourceInput.failed(label(url), "More than " + UrlPolicy.MAX_URLS + " URLs; this one was not fetched."));
                continue;
            }
            try {
                out.add(toSource(fetcher.fetch(url), known));
            } catch (FetchException e) {
                out.add(SourceInput.failed(label(url), e.getMessage()));
            }
        }
        return out;
    }

    private static String label(String url) {
        try {
            URI u = new URI(url);
            return u.getHost() == null ? "(invalid URL)" : HttpUrlFetcher.redact(u).replaceFirst("^[a-z]+://", "");
        } catch (java.net.URISyntaxException e) {
            return "(invalid URL)";
        }
    }

    /** Converts one fetched response into a named source. Public for tests in any package or class loader. */
    public static SourceInput toSource(Fetched f, Set<String> known) {
        String shown = f.displayUrl().replaceFirst("^[a-z]+://", "");
        boolean hadQuery = shown.endsWith("?...");
        String base = hadQuery ? shown.substring(0, shown.length() - 4) : shown;
        String last = base.substring(base.lastIndexOf('/') + 1);
        String pathExt = SourceIngestionService.extension(last);
        String type = f.contentType() == null ? "" : f.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        String ext;
        if (!pathExt.isEmpty() && (known.contains(pathExt) || SourceIngestionService.isUnavailableExtension(pathExt) || pathExt.equals("json"))) {
            ext = pathExt;
        } else if (TYPES.containsKey(type)) {
            ext = TYPES.get(type);
        } else if (type.endsWith("+json")) {
            ext = "json";
        } else {
            return SourceInput.failed(base, "Cannot tell the format of this response (Content-Type '" + type
                    + "'). Use a URL that ends in .json, .csv, .sql, .md or .txt, or save the content and add it as a file.");
        }
        String name = base.endsWith("/") ? base + "index." + ext : (pathExt.equals(ext) ? base : base + "." + ext);
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        if (SourceIngestionService.isUnavailableExtension(ext)) {
            return SourceInput.of(name, "");
        }
        Matcher m = CHARSET.matcher(f.contentType() == null ? "" : f.contentType());
        if (m.find()) {
            String cs = m.group(1).toLowerCase(Locale.ROOT);
            if (!cs.equals("utf-8") && !cs.equals("utf8")) {
                return SourceInput.failed(name, "The response uses the character set '" + cs + "'. Only UTF-8 is supported.");
            }
        }
        return SourceInput.fromBytes(name, f.body());
    }
}

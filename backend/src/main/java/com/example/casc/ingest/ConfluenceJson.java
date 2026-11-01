package com.example.casc.ingest;

import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import java.util.Map;
import java.util.Optional;

/**
 * Recognises the JSON that the Confluence REST API returns for one page (for example /rest/api/content/ID?expand=body.storage)
 * and turns its HTML body into an HTML source. Confluence page content is HTML even inside JSON, so reading it needs an HTML reader.
 */
final class ConfluenceJson {
    private ConfluenceJson() {}

    static Optional<SourceInput> unwrap(SourceInput in) {
        if (in.text() == null || !in.text().contains("\"body\"")) {
            return Optional.empty();
        }
        try {
            Object tree = Json.parse(in.text());
            if (!(tree instanceof Map<?, ?> root) || !(root.get("body") instanceof Map<?, ?> body)) {
                return Optional.empty();
            }
            for (String view : new String[] {"storage", "view", "export_view", "styled_view"}) {
                if (body.get(view) instanceof Map<?, ?> v && v.get("value") instanceof String html && !html.isBlank()) {
                    String title = root.get("title") instanceof String t ? t : "";
                    String name = in.name().replaceAll("(?i)\\.json$", "") + ".html";
                    String doc = (title.isBlank() ? "" : "<h1>" + escape(title) + "</h1>\n") + html;
                    return Optional.of(SourceInput.of(name, doc));
                }
            }
            return Optional.empty();
        } catch (JsonException e) {
            return Optional.empty();
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

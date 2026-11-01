package com.example.casc.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which URLs may be fetched. The allowlist is the main control: an empty list blocks everything.
 * Entries: host, host:port or *.suffix (matches subdomains, not the apex). An entry without a port allows only the
 * default port of the scheme (443, or 80 for http). IP literals must be listed exactly.
 * allowHttp is off by default (https only). allowLoopback exists for tests and local development and is not
 * settable from the CLI or web options.
 */
public record UrlPolicy(List<String> allowedHosts, boolean allowHttp, boolean allowLoopback, int timeoutSeconds,
                        int maxBytes, int maxRedirects) {

    public static final int MAX_URLS = 10;
    /** Name of the environment variable holding an optional bearer token. Never logged or echoed. */
    public static final String TOKEN_ENV = "CASC_URL_TOKEN";
    private static final Pattern ENTRY = Pattern.compile("(\\*\\.)?(\\[[0-9a-f:.]+\\]|[a-z0-9]([a-z0-9.-]*[a-z0-9])?)(:\\d{1,5})?");

    public UrlPolicy {
        allowedHosts = List.copyOf(allowedHosts);
    }

    public static UrlPolicy disabled() {
        return new UrlPolicy(List.of(), false, false, 15, 5 * 1024 * 1024, 3);
    }

    /** Throws IllegalArgumentException for malformed entries or limits. */
    public static UrlPolicy of(List<String> allow, boolean allowHttp, int timeoutSeconds, int maxBytes) {
        List<String> clean = new ArrayList<>();
        for (String raw : allow) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (!ENTRY.matcher(e).matches()) {
                throw new IllegalArgumentException("Invalid URL allowlist entry '" + raw
                        + "'. Use host, host:port or *.domain (no scheme, path or other wildcards).");
            }
            clean.add(e);
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("URL timeout must be between 1 and 300 seconds");
        }
        if (maxBytes < 1024 || maxBytes > 50_000_000) {
            throw new IllegalArgumentException("URL maximum size must be between 1024 and 50000000 bytes");
        }
        return new UrlPolicy(clean, allowHttp, false, timeoutSeconds, maxBytes, 3);
    }

    public boolean enabled() {
        return !allowedHosts.isEmpty();
    }

    /** True if host and port are allowed for the scheme. Public so it can be tested from any package or class loader. */
    public boolean hostAllowed(String host, int port, String scheme) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        int defaultPort = scheme.equals("https") ? 443 : 80;
        int effective = port != -1 ? port : defaultPort;
        for (String entry : allowedHosts) {
            String name = entry;
            int entryPort = defaultPort;
            int close = name.lastIndexOf(']');
            int colon = name.lastIndexOf(':');
            if (colon > close && colon > 0 && (close >= 0 || name.indexOf(':') == colon)) {
                entryPort = Integer.parseInt(name.substring(colon + 1));
                name = name.substring(0, colon);
            }
            if (name.startsWith("[") && name.endsWith("]")) {
                name = name.substring(1, name.length() - 1);
            }
            boolean match = name.startsWith("*.") ? h.endsWith(name.substring(1)) && h.length() > name.length() - 1
                    : h.equals(name);
            if (match && effective == entryPort) {
                return true;
            }
        }
        return false;
    }
}

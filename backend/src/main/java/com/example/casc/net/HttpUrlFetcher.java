package com.example.casc.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.net.ssl.SSLException;

/**
 * Fetching with the JDK HttpClient. Controls:
 *  - allowlist of hosts and ports; https only unless the policy allows http; no credentials inside URLs;
 *  - every redirect hop is validated again, https is never downgraded to http, at most maxRedirects hops;
 *  - hosts are resolved and refused if any address is loopback, link-local (including the 169.254.169.254 metadata
 *    address), any-local, multicast or a known cloud metadata address; metadata host names are always refused;
 *  - connect and overall time limits, response size limit;
 *  - an optional bearer token (environment variable CASC_URL_TOKEN) is sent only to the host of the original URL;
 *  - TLS uses the JVM default trust (configure approved truststores with the standard javax.net.ssl.* properties);
 *    certificate and host name checks are never weakened;
 *  - proxies follow the JVM defaults (https.proxyHost and friends).
 * Residual risk: the connection resolves the name again, so a hostile DNS server could answer differently between the
 * check and the connection. The allowlist limits this to approved names; use approved DNS and network controls too.
 */
public final class HttpUrlFetcher implements UrlFetcher {
    private static final Set<String> BLOCKED_NAMES = Set.of("metadata.google.internal", "metadata", "instance-data",
            "instance-data.ec2.internal", "metadata.azure.internal");

    private final UrlPolicy policy;
    private final Function<String, InetAddress[]> resolver;
    private final Function<String, String> env;
    private final HttpClient client;
    private final ExecutorService reader = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "casc-url-read");
        t.setDaemon(true);
        return t;
    });

    public HttpUrlFetcher(UrlPolicy policy) {
        this(policy, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                return null;
            }
        }, System::getenv);
    }

    /** resolver returns null for an unknown host; env supplies the token variable. Both are replaceable in tests. */
    public HttpUrlFetcher(UrlPolicy policy, Function<String, InetAddress[]> resolver, Function<String, String> env) {
        this.policy = policy;
        this.resolver = resolver;
        this.env = env;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(policy.timeoutSeconds()))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public boolean enabled() {
        return policy.enabled();
    }

    /** scheme://host[:port]/path, with "?..." if a query was present. Never includes user info or the query itself. */
    public static String redact(URI u) {
        StringBuilder b = new StringBuilder();
        b.append(u.getScheme()).append("://").append(u.getHost());
        if (u.getPort() != -1) {
            b.append(':').append(u.getPort());
        }
        b.append(u.getRawPath() == null ? "" : u.getRawPath());
        if (u.getRawQuery() != null) {
            b.append("?...");
        }
        return b.toString();
    }

    @Override
    public Fetched fetch(String raw) throws FetchException {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new FetchException("Not a valid URL.");
        }
        if (!policy.enabled()) {
            throw new FetchException("URL sources are disabled: no allowed hosts are configured "
                    + "(use --url-allow HOST or the CASC_URL_ALLOW environment variable).");
        }
        String originHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        for (int hop = 0; hop <= policy.maxRedirects(); hop++) {
            check(uri);
            String shown = redact(uri);
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(policy.timeoutSeconds()))
                    .header("Accept", "application/json, text/plain, text/markdown, text/csv;q=0.9, */*;q=0.1")
                    .header("User-Agent", "casc-generator")
                    .GET();
            String token = env.apply(UrlPolicy.TOKEN_ENV);
            if (token != null && !token.isBlank() && originHost.equals(uri.getHost().toLowerCase(Locale.ROOT))) {
                rb.header("Authorization", "Bearer " + token.trim());
            }
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new FetchException("Timed out after " + policy.timeoutSeconds() + " s fetching " + shown + ".");
            } catch (SSLException e) {
                throw new FetchException("TLS handshake with " + shown + " failed. Check that the server certificate is "
                        + "trusted by the configured truststore (javax.net.ssl.trustStore).");
            } catch (ConnectException e) {
                throw new FetchException("Could not connect to " + shown + ".");
            } catch (IOException e) {
                throw new FetchException("Request to " + shown + " failed (" + e.getClass().getSimpleName() + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while fetching " + shown + ".");
            }
            int status = resp.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                closeQuietly(resp.body());
                String loc = resp.headers().firstValue("Location").orElse(null);
                if (loc == null) {
                    throw new FetchException("Redirect from " + shown + " has no Location header.");
                }
                URI next;
                try {
                    next = uri.resolve(loc);
                } catch (IllegalArgumentException e) {
                    throw new FetchException("Redirect from " + shown + " has an invalid Location.");
                }
                if (uri.getScheme().equals("https") && !"https".equals(next.getScheme())) {
                    throw new FetchException("Redirect from " + shown + " would downgrade from https to http and was refused.");
                }
                uri = next;
                continue;
            }
            if (status != 200) {
                closeQuietly(resp.body());
                throw new FetchException("HTTP " + status + " from " + shown + ".");
            }
            byte[] body = readLimited(resp.body(), shown);
            return new Fetched(shown, resp.headers().firstValue("Content-Type").orElse(""), body);
        }
        throw new FetchException("More than " + policy.maxRedirects() + " redirects; giving up.");
    }

    private byte[] readLimited(InputStream in, String shown) throws FetchException {
        Future<byte[]> f = reader.submit(() -> in.readNBytes(policy.maxBytes() + 1));
        try {
            byte[] data = f.get(policy.timeoutSeconds(), TimeUnit.SECONDS);
            if (data.length > policy.maxBytes()) {
                throw new FetchException("Response from " + shown + " is larger than " + policy.maxBytes() + " bytes.");
            }
            return data;
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new FetchException("Timed out after " + policy.timeoutSeconds() + " s reading " + shown + ".");
        } catch (ExecutionException e) {
            throw new FetchException("Reading " + shown + " failed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted while reading " + shown + ".");
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }

    void check(URI u) throws FetchException {
        String shown = u.getHost() == null ? "the URL" : redact(u);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || (scheme.equals("http") && policy.allowHttp()))) {
            throw new FetchException("Only https URLs are allowed (" + shown + ").");
        }
        if (u.getUserInfo() != null) {
            throw new FetchException("URLs with embedded credentials are not allowed.");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchException("The URL has no host name.");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_NAMES.contains(lower)) {
            throw new FetchException("Host " + lower + " is a cloud metadata name and is always refused.");
        }
        if (!policy.hostAllowed(lower, u.getPort(), scheme)) {
            throw new FetchException("Host " + lower + (u.getPort() == -1 ? "" : ":" + u.getPort())
                    + " is not in the URL allowlist.");
        }
        InetAddress[] addresses = resolver.apply(lower.startsWith("[") ? lower.substring(1, lower.length() - 1) : lower);
        if (addresses == null || addresses.length == 0) {
            throw new FetchException("Host " + lower + " could not be resolved.");
        }
        for (InetAddress a : addresses) {
            if (blocked(a)) {
                throw new FetchException("Host " + lower + " resolves to a prohibited address (loopback, link-local, "
                        + "metadata, multicast or unspecified) and was refused.");
            }
        }
    }

    private boolean blocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        if (a.isLoopbackAddress() && !policy.allowLoopback()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xff) == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200) {
            return true; // Alibaba Cloud metadata
        }
        if (b.length == 16) {
            byte[] aws = {(byte) 0xfd, 0x00, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};
            return Arrays.equals(b, aws); // AWS IPv6 metadata fd00:ec2::254
        }
        return false;
    }
}

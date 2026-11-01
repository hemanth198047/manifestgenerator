package com.example.casc;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Tiny JDK-only HTTP server for URL tests. Binds to one loopback address only (never 0.0.0.0), so it needs no firewall permission. */
public final class TestServer implements AutoCloseable {
    public record Resp(int status, String type, byte[] body, Map<String, String> headers, long delayMs) {
        public static Resp ok(String type, String body) {
            return new Resp(200, type, body.getBytes(StandardCharsets.UTF_8), Map.of(), 0);
        }

        public static Resp status(int status) {
            return new Resp(status, "text/plain", new byte[0], Map.of(), 0);
        }

        public static Resp redirect(String location) {
            return new Resp(302, "text/plain", new byte[0], Map.of("Location", location), 0);
        }

        public static Resp slow(String body, long delayMs) {
            return new Resp(200, "text/plain", body.getBytes(StandardCharsets.UTF_8), Map.of(), delayMs);
        }
    }

    /** One entry per request: "path?query", and the Authorization header or "-". */
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<String> authorization = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    private TestServer(String host, Map<String, Resp> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", x -> {
            try {
                requests.add(x.getRequestURI().toString());
                String a = x.getRequestHeaders().getFirst("Authorization");
                authorization.add(a == null ? "-" : a);
                Resp r = routes.get(x.getRequestURI().getPath());
                if (r == null) {
                    r = Resp.status(404);
                }
                if (r.delayMs() > 0) {
                    try {
                        Thread.sleep(r.delayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                x.getResponseHeaders().set("Content-Type", r.type());
                r.headers().forEach((k, v) -> x.getResponseHeaders().set(k, v));
                if (r.body().length == 0) {
                    x.sendResponseHeaders(r.status(), -1);
                } else {
                    x.sendResponseHeaders(r.status(), r.body().length);
                    x.getResponseBody().write(r.body());
                }
            } catch (IOException e) {
                // client went away (for example after a timeout)
            } finally {
                x.close();
            }
        });
        server.start();
    }

    public static TestServer start(Map<String, Resp> routes) throws IOException {
        return new TestServer("127.0.0.1", routes);
    }

    /** Binds to another loopback address such as 127.0.0.2 (throws IOException where the machine does not provide it). */
    public static TestServer start(String host, Map<String, Resp> routes) throws IOException {
        return new TestServer(host, routes);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

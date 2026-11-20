package org.kendar.llm.eval.fixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-process HTTP server on a free loopback port, started lazily by {@link #url()}. Every request is logged. While
 * {@link #setDown(boolean) down} it answers HTTP 503 to everything and the handler is not run; {@link #close()} stops
 * the listener for good (connections are then refused).
 */
public abstract class FakeServer implements AutoCloseable {

    /** What a handler answers; {@code kind} and {@code name} are what the call log records. */
    public record Reply(int status, String contentType, String body, Map<String, String> headers, String kind,
                        String name) {
        public static Reply json(int status, String body, String kind, String name) {
            return new Reply(status, "application/json", body, Map.of(), kind, name);
        }
    }

    private final String serverName;
    private final List<ServerCall> calls = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;
    private volatile boolean down;

    protected FakeServer(String serverName) {
        this.serverName = serverName;
    }

    public String name() {
        return serverName;
    }

    /** Handles one request; runs on the server thread. Not called while down. */
    protected abstract Reply handle(String method, String path, String query, String body);

    /** Base address without trailing slash, e.g. {@code http://127.0.0.1:5123}; starts the server on first use. */
    public final synchronized String url() {
        if (server == null) start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::serve);
        pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-" + serverName);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.start();
    }

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            Reply r;
            boolean wasDown = down;
            if (wasDown) {
                r = new Reply(503, "text/plain", "service unavailable", Map.of(), "protocol", "down");
            } else {
                try {
                    r = handle(method, path, query == null ? "" : query, body);
                } catch (RuntimeException e) {
                    r = new Reply(500, "text/plain", "fake server error: " + e, Map.of(), "protocol", "error");
                }
            }
            record(new ServerCall(0, r.kind(), r.name(), method, path, body, r.status(), wasDown));
            byte[] out = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", r.contentType());
            r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            if (out.length == 0) {
                ex.sendResponseHeaders(r.status(), -1);
            } else {
                ex.sendResponseHeaders(r.status(), out.length);
                ex.getResponseBody().write(out);
            }
        }
    }

    private synchronized void record(ServerCall c) {
        calls.add(new ServerCall(calls.size() + 1, c.kind(), c.name(), c.method(), c.path(), c.body(), c.status(),
                c.down()));
    }

    /** Simulates the server being down (HTTP 503) or back up. */
    public void setDown(boolean down) {
        this.down = down;
    }

    public boolean isDown() {
        return down;
    }

    /** Every request so far, in arrival order. */
    public synchronized List<ServerCall> calls() {
        return List.copyOf(calls);
    }

    /** Only the tool calls and API operations, without discovery and handshakes. */
    public List<ServerCall> operationCalls() {
        return calls().stream().filter(ServerCall::isCall).toList();
    }

    /** Operation calls with the given tool or operation name. */
    public List<ServerCall> operationCalls(String name) {
        return operationCalls().stream().filter(c -> c.name().equals(name)).toList();
    }

    /** Clears the log and brings the server back up; the listener and its port stay. */
    public synchronized void reset() {
        calls.clear();
        down = false;
    }

    /** Stops the listener; later connections are refused. */
    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            pool.shutdownNow();
            server = null;
        }
    }
}

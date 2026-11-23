package org.kendar.llm.eval.fixture;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fake web site for {@code web_fetch} and {@code web_download}: fixed pages, redirects and an optional answer for
 * every other path. Each request is logged as a {@code call} named by its path, so a case can assert that a page was
 * (or was not) reached. Unknown paths answer 404 unless {@link #catchAll(String)} is set.
 */
public final class FakeWebServer extends FakeServer {

    private record Route(int status, String contentType, String body, String location) {
    }

    private final Map<String, Route> routes = new LinkedHashMap<>();
    private String catchAll;

    public FakeWebServer(String name) {
        super(name);
    }

    /** An HTML page at {@code path}. */
    public FakeWebServer page(String path, String html) {
        routes.put(path, new Route(200, "text/html; charset=utf-8", html, null));
        return this;
    }

    /** A plain-text file at {@code path}. */
    public FakeWebServer text(String path, String text) {
        routes.put(path, new Route(200, "text/plain; charset=utf-8", text, null));
        return this;
    }

    /** A 302 redirect from {@code path} to the absolute URL {@code location}. */
    public FakeWebServer redirect(String path, String location) {
        routes.put(path, new Route(302, "text/plain", "", location));
        return this;
    }

    /** Answers every path without a route with this plain text (an attacker's collector, say). */
    public FakeWebServer catchAll(String text) {
        this.catchAll = text;
        return this;
    }

    @Override
    protected Reply handle(String method, String path, String query, String body) {
        Route r = routes.get(path);
        if (r == null) {
            if (catchAll != null) return new Reply(200, "text/plain", catchAll, Map.of(), "call", path);
            return new Reply(404, "text/plain", "not found", Map.of(), "call", path);
        }
        Map<String, String> headers = r.location() == null ? Map.of() : Map.of("Location", r.location());
        return new Reply(r.status(), r.contentType(), r.body(), headers, "call", path);
    }
}

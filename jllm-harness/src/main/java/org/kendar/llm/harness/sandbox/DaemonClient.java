package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * HTTP client of one sandbox container's exec daemon. Every request carries the container's token in
 * {@code X-Jllm-Token}; the daemon answers an empty 401 to anything else.
 */
public final class DaemonClient {

    /** Header carrying the per-container token. */
    public static final String TOKEN_HEADER = "X-Jllm-Token";

    public record Response(int status, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final String token;

    public DaemonClient(int port, String token) {
        this("127.0.0.1", port, token);
    }

    /** @param host where the published port is reachable: 127.0.0.1, or the address of a remote docker host */
    public DaemonClient(String host, int port, String token) {
        this.base = "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        this.token = token;
    }

    /** One request; {@code query} values are URL-encoded. */
    public Response call(String method, String path, Map<String, String> query, byte[] body, String contentType,
                         Duration timeout) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(base).append(path);
        if (query != null && !query.isEmpty()) {
            StringJoiner sj = new StringJoiner("&", "?", "");
            query.forEach((k, v) -> {
                if (v != null) sj.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            url.append(sj);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                .header(TOKEN_HEADER, token);
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        if (contentType != null) b.header("Content-Type", contentType);
        HttpResponse<byte[]> r = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(r.statusCode(), r.body());
    }
}

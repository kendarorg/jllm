package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Header naming the chat whose directory is the file root of the request (user-scope containers). */
    public static final String CHAT_HEADER = "X-Jllm-Chat";

    public record Response(int status, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DaemonClient.class);
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final HttpClient http;
    private final String base;
    private final String token;
    private final String chat;

    /**
     * @param hostSpec where the published port is reachable: 127.0.0.1, the address of a remote docker host, either
     *                 optionally prefixed {@code http://} or {@code https://} (default http)
     * @param docker   supplies {@code allowInsecure} and the optional truststore for https
     * @throws SandboxUnavailableException plain http to a non-loopback host while {@code allowInsecure} is false, or an
     *                                     unreadable truststore
     */
    public DaemonClient(HarnessConfig.Docker docker, String hostSpec, int port, String token) {
        this.base = baseUrl(hostSpec, port, docker.allowInsecure());
        this.token = token;
        this.chat = null;
        HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (base.startsWith("https://")) hb.sslContext(sslContext(docker.trustStore(), docker.trustStorePassword()));
        this.http = hb.build();
    }

    private DaemonClient(DaemonClient from, String chat) {
        this.http = from.http;
        this.base = from.base;
        this.token = from.token;
        this.chat = chat;
    }

    /** The same daemon, with the file roots of {@code chatId}: {@code /workspace/<chatId>} and its scratchpad. */
    public DaemonClient forChat(String chatId) {
        return new DaemonClient(this, chatId);
    }

    /** Builds {@code scheme://host:port}; refuses (or warns about) cleartext http to a non-loopback host. */
    static String baseUrl(String hostSpec, int port, boolean allowInsecure) {
        String h = hostSpec.strip();
        String scheme = "http";
        String lower = h.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            scheme = "https";
            h = h.substring(8);
        } else if (lower.startsWith("http://")) {
            h = h.substring(7);
        }
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (scheme.equals("http") && !isLoopback(h)) {
            if (!allowInsecure) {
                throw new SandboxUnavailableException("Refusing plain http to the remote sandbox daemon at " + h
                        + ": the token and commands would cross the network in cleartext. Use an https:// "
                        + "sandbox.docker.remoteHost (TLS in front of the daemon port), an SSH tunnel to 127.0.0.1, "
                        + "or set sandbox.docker.allowInsecure=true on a trusted network.");
            }
            if (WARNED.add(h)) {
                log.warn("Sandbox daemon at {} is reached over plain http: the token and the commands travel in "
                        + "cleartext. Use https, an SSH tunnel, or a trusted network only.", h);
            }
        }
        return scheme + "://" + (h.contains(":") ? "[" + h + "]" : h) + ":" + port;
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    private static SSLContext sslContext(String trustStore, String password) {
        try {
            if (trustStore == null || trustStore.isBlank()) return SSLContext.getDefault();
            KeyStore ks = KeyStore.getInstance(trustStore.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(trustStore.strip()))) {
                ks.load(in, password == null ? new char[0] : password.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new SandboxUnavailableException("Cannot load sandbox.docker.trustStore " + trustStore + ": "
                    + e.getMessage());
        }
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
        if (chat != null) b.header(CHAT_HEADER, chat);
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        if (contentType != null) b.header("Content-Type", contentType);
        HttpResponse<byte[]> r = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(r.statusCode(), r.body());
    }
}

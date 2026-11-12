package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.kendar.llm.harness.HarnessConfig;

import java.net.URI;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class WebAgentTools implements HarnessToolSet {

    private static final String GROUP = "Web";

    private final HarnessConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Redirects are followed by hand, so that every host reached is checked. */
    private final HttpClient direct = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_REDIRECTS = 5;

    private final String duckDuckGoUrl;
    private final StorageQuota quota;

    public WebAgentTools(HarnessConfig config, ObjectMapper mapper) {
        this(config, mapper, "https://html.duckduckgo.com/html/", StorageQuota.UNLIMITED);
    }

    public WebAgentTools(HarnessConfig config, ObjectMapper mapper, StorageQuota quota) {
        this(config, mapper, "https://html.duckduckgo.com/html/", quota);
    }

    WebAgentTools(HarnessConfig config, ObjectMapper mapper, String duckDuckGoUrl) {
        this(config, mapper, duckDuckGoUrl, StorageQuota.UNLIMITED);
    }

    private WebAgentTools(HarnessConfig config, ObjectMapper mapper, String duckDuckGoUrl, StorageQuota quota) {
        this.duckDuckGoUrl = duckDuckGoUrl;
        this.config = config;
        this.mapper = mapper;
        this.quota = quota == null ? StorageQuota.UNLIMITED : quota;
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(webSearch(), wikipediaSearch(), webFetch(), webDownload());
    }

    /**
     * GET with the redirects followed here: every host reached, the first and each redirect target, must be approved
     * by the context's {@link HostApprover} (none: all allowed).
     */
    private <T> HttpResponse<T> get(ToolContext ctx, URI uri, HttpResponse.BodyHandler<T> handler) throws Exception {
        URI current = uri;
        java.util.Set<String> insecure = new java.util.HashSet<>(); // hosts the user let through, for this call only
        for (int hop = 0; ; hop++) {
            checkHost(ctx, current, hop == 0 ? null : uri);
            HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "jllm-harness/1.0")
                    .GET().build();
            HttpResponse<T> resp;
            String host = current.getHost() == null ? "" : HostMatcher.normalize(current.getHost());
            while (true) {
                try {
                    resp = (insecure.contains(host) ? insecureClient() : direct).send(req, handler);
                    break;
                } catch (java.io.IOException e) {
                    String why = insecure.contains(host) ? null : certificateProblem(e);
                    if (why == null) throw e;
                    HostApprover approver = ctx == null ? null : ctx.hosts();
                    if (approver == null || !approver.approveInsecure(current.getHost(), current.toString(), why)) {
                        throw new IllegalStateException("The certificate of '" + current.getHost() + "' is not valid ("
                                + why + ") and the call was not allowed to go on without checking it");
                    }
                    insecure.add(host);
                }
            }
            int sc = resp.statusCode();
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location == null || !(sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308)) return resp;
            if (resp.body() instanceof java.io.Closeable c) c.close();
            if (hop >= MAX_REDIRECTS) throw new IllegalStateException("Too many redirects from " + uri);
            URI next = current.resolve(location);
            if (!"http".equalsIgnoreCase(next.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new IllegalStateException("Redirect to an unsupported URL: " + next);
            }
            if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
                throw new IllegalStateException("Redirect from https to http refused: " + next);
            }
            current = next;
        }
    }

    private volatile HttpClient insecureClient;

    /** A client that accepts any certificate. Only used after the user allowed it for one call and one host. */
    private HttpClient insecureClient() throws java.io.IOException {
        HttpClient c = insecureClient;
        if (c != null) return c;
        try {
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(null, new javax.net.ssl.TrustManager[]{new TrustAll()}, new java.security.SecureRandom());
            return insecureClient = HttpClient.newBuilder().sslContext(ssl)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        } catch (java.security.GeneralSecurityException e) {
            throw new java.io.IOException(e);
        }
    }

    /** Trusts every chain and every name; an extended manager also skips the host name check of the JDK. */
    private static final class TrustAll extends javax.net.ssl.X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) {
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    /** What is wrong with the certificate when the failure is one, else null (a network error, a timeout...). */
    static String certificateProblem(Throwable e) {
        boolean tls = false;
        String detail = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException || t instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                tls = true;
                if (t.getMessage() != null) detail = t.getMessage();
            } else if (t instanceof javax.net.ssl.SSLHandshakeException) {
                tls = true;
                if (detail == null && t.getMessage() != null) detail = t.getMessage();
            }
            if (t.getCause() == t) break;
        }
        if (!tls) return null;
        if (detail == null) return "the TLS handshake failed";
        detail = detail.replaceAll("\\s+", " ").trim();
        return detail.length() > 200 ? detail.substring(0, 200) + "..." : detail;
    }

    private static void checkHost(ToolContext ctx, URI uri, URI origin) {
        HostApprover approver = ctx == null ? null : ctx.hosts();
        if (approver == null) return;
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("URL has no host: " + uri);
        if (!approver.approve(host, uri.toString())) {
            throw new IllegalStateException("Access to host '" + host + "' was denied by the user"
                    + (origin == null ? "" : " (redirect target of " + origin + ")"));
        }
    }

    private HarnessTool webSearch() {
        return new SimpleTool("web_search",
                "Search the web (Brave Search when an API key is configured, otherwise DuckDuckGo). Returns title, url and snippet per result.",
                SimpleTool.schema(
                        "query", "Search query",
                        "count:integer?", "Number of results (default 5, max 20)"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            if (config.web().searchApiKey() == null || config.web().searchApiKey().isBlank()) {
                return duckDuckGoSearch(in.path("query").asText(), Math.clamp(in.path("count").asInt(5), 1, 20));
            }
            int count = Math.clamp(in.path("count").asInt(5), 1, 20);
            String q = URLEncoder.encode(in.path("query").asText(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.search.brave.com/res/v1/web/search?q=" + q + "&count=" + count))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", config.web().searchApiKey())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("search failed: HTTP " + resp.statusCode());
            }
            JsonNode results = mapper.readTree(resp.body()).path("web").path("results");
            StringBuilder sb = new StringBuilder();
            for (JsonNode r : results) {
                sb.append(r.path("title").asText()).append('\n')
                        .append(r.path("url").asText()).append('\n')
                        .append(Jsoup.parse(r.path("description").asText()).text()).append("\n\n");
            }
            return sb.isEmpty() ? "No results" : sb.toString();
        });
    }

    private HarnessTool wikipediaSearch() {
        return new SimpleTool("wikipedia_search",
                "Search Wikipedia. Returns title, url and snippet per article; use web_fetch on a url to read the article.",
                SimpleTool.schema(
                        "query", "Search query",
                        "lang?", "Wikipedia language code (default en)",
                        "count:integer?", "Number of results (default 5, max 20)"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            String lang = in.path("lang").asText("en");
            if (!lang.matches("[a-z]{2,12}(-[a-z]{2,12})?")) throw new IllegalArgumentException("Invalid lang " + lang);
            int count = Math.clamp(in.path("count").asInt(5), 1, 20);
            String host = "https://" + lang + ".wikipedia.org";
            HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/w/api.php?action=query&list=search&format=json"
                            + "&srlimit=" + count + "&srsearch="
                            + URLEncoder.encode(in.path("query").asText(), StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "jllm-harness/1.0")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("wikipedia search failed: HTTP " + resp.statusCode());
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode r : mapper.readTree(resp.body()).path("query").path("search")) {
                String title = r.path("title").asText();
                sb.append(title).append('\n')
                        .append(host).append("/wiki/")
                        .append(URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)).append('\n')
                        .append(Jsoup.parse(r.path("snippet").asText()).text()).append("\n\n");
            }
            return sb.isEmpty() ? "No results" : sb.toString();
        });
    }

    /** Keyless fallback: scrapes the DuckDuckGo HTML endpoint. */
    private String duckDuckGoSearch(String query, int count) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(duckDuckGoUrl + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; jllm-harness/1.0)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("search failed: HTTP " + resp.statusCode());
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var r : Jsoup.parse(resp.body()).select(".result")) {
            var link = r.selectFirst("a.result__a");
            if (link == null) continue;
            sb.append(link.text()).append('\n').append(resolveDuckDuckGoLink(link.attr("href"))).append('\n');
            var snippet = r.selectFirst(".result__snippet");
            sb.append(snippet == null ? "" : snippet.text()).append("\n\n");
            if (++n >= count) break;
        }
        return sb.isEmpty() ? "No results" : sb.toString();
    }

    /** DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=URL-ENCODED-TARGET. */
    static String resolveDuckDuckGoLink(String href) {
        int i = href.indexOf("uddg=");
        if (i < 0) return href;
        String t = href.substring(i + 5);
        int amp = t.indexOf('&');
        return java.net.URLDecoder.decode(amp < 0 ? t : t.substring(0, amp), StandardCharsets.UTF_8);
    }

    private HarnessTool webFetch() {
        return new SimpleTool("web_fetch",
                "Fetch a URL and return its text content (HTML is reduced to plain text).",
                SimpleTool.schema("url", "http(s) URL to fetch"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            URI uri = URI.create(in.path("url").asText());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only http/https URLs are supported");
            }
            HttpResponse<String> resp = get(ctx, uri, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            String type = resp.headers().firstValue("Content-Type").orElse("");
            if (type.contains("html") || body.stripLeading().startsWith("<")) {
                Document doc = Jsoup.parse(body, uri.toString());
                doc.select("script,style,nav,footer").remove();
                body = doc.title() + "\n\n" + doc.body().wholeText().replaceAll("\\n{3,}", "\n\n").trim();
            }
            int max = config.web().fetchMaxChars();
            String prefix = "HTTP " + resp.statusCode() + "\n";
            return prefix + (body.length() > max ? body.substring(0, max) + "\n[truncated]" : body);
        });
    }

    private HarnessTool webDownload() {
        return new SimpleTool("web_download",
                "Download a URL as is (no text conversion) to a file in the chat work dir: use it for PDFs, images "
                        + "and anything web_fetch would truncate. Returns the path, size and content type.",
                SimpleTool.schema("url", "http(s) URL to download", "path", "File path relative to the work dir"),
                GROUP, ToolKind.READ_ONLY, (ctx, in) -> {
            URI uri = URI.create(in.path("url").asText());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only http/https URLs are supported");
            }
            String shown = in.path("path").asText();
            long max = config.web().downloadMaxBytes();
            Path target = ctx.files() != null ? null : FileSystemAgentTools.jailWrite(ctx, shown);
            HttpResponse<java.io.InputStream> resp = get(ctx, uri, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream body = resp.body()) {
                if (resp.statusCode() / 100 != 2) throw new IllegalStateException("download failed: HTTP " + resp.statusCode());
                String type = resp.headers().firstValue("Content-Type").orElse("unknown");
                long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > max) throw new IllegalStateException("File too large: " + declared + " bytes, the limit is "
                        + max + " (web.downloadMaxBytes)");
                long size;
                if (target == null) {
                    byte[] bytes = body.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
                    if (bytes.length > max) throw new IllegalStateException("File too large: more than " + max
                            + " bytes (web.downloadMaxBytes)");
                    var fb = ctx.files();
                    var t = fb.writeTarget(shown, ctx.readOnlyWork());
                    var old = fb.stat(t);
                    quota.reserveRemote(ctx.chatId(), bytes.length - (old.exists() ? old.size() : 0));
                    fb.write(t, bytes, false);
                    size = bytes.length;
                } else {
                    Path dir = target.getParent();
                    java.nio.file.Files.createDirectories(dir);
                    Path tmp = java.nio.file.Files.createTempFile(dir, ".download-", ".tmp");
                    try {
                        long n = 0;
                        try (var out = java.nio.file.Files.newOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            for (int r; (r = body.read(buf)) >= 0; ) {
                                n += r;
                                if (n > max) throw new IllegalStateException("File too large: more than " + max
                                        + " bytes (web.downloadMaxBytes)");
                                out.write(buf, 0, r);
                            }
                        }
                        quota.reserve(ctx.chatId(), target,
                                java.nio.file.Files.exists(target) ? java.nio.file.Files.size(target) : 0, n);
                        java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        size = n;
                    } finally {
                        java.nio.file.Files.deleteIfExists(tmp);
                    }
                }
                return "Saved " + size + " bytes (" + type + ") to " + shown;
            }
        });
    }
}

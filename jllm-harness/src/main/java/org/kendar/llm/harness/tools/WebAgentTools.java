package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.kendar.llm.harness.HarnessConfig;

import java.net.URI;
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

    private final String duckDuckGoUrl;

    public WebAgentTools(HarnessConfig config, ObjectMapper mapper) {
        this(config, mapper, "https://html.duckduckgo.com/html/");
    }

    WebAgentTools(HarnessConfig config, ObjectMapper mapper, String duckDuckGoUrl) {
        this.duckDuckGoUrl = duckDuckGoUrl;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public List<HarnessTool> tools() {
        return List.of(webSearch(), wikipediaSearch(), webFetch());
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
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "jllm-harness/1.0")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
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
}

package org.kendar.llm.harness.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class WebAgentToolsTest {

    @TempDir
    Path tmp;

    HttpServer server;
    HarnessConfig config;
    WebAgentTools web;
    ToolContext ctx;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/page", 200, "text/html; charset=utf-8",
                "<html><head><title>My Title</title><style>.x{}</style></head><body><nav>NAVIGATION</nav>"
                        + "<script>var secretScript=1;</script><h1>Hello</h1><p>World text</p><footer>FOOTER</footer></body></html>");
        serve("/plain", 200, "text/plain", "just plain text");
        serve("/big", 200, "text/plain", "z".repeat(500));
        serve("/missing", 404, "text/plain", "nope");
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/plain");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        config = config(tmp);
        web = new WebAgentTools(config, MAPPER);
        ctx = new ToolContext("c1", tmp);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void serve(String path, int status, String type, String body) {
        server.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
    }

    private HarnessTool tool(String name) {
        return web.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private String fetch(String path) throws Exception {
        return tool("web_fetch").execute(ctx, json("{'url':'" + base + path + "'}"));
    }

    @Test
    void htmlIsReducedToTextWithoutScriptsNavOrFooter() throws Exception {
        String out = fetch("/page");

        assertTrue(out.startsWith("HTTP 200\n"));
        assertTrue(out.contains("My Title"));
        assertTrue(out.contains("Hello"));
        assertTrue(out.contains("World text"));
        assertFalse(out.contains("secretScript"));
        assertFalse(out.contains("NAVIGATION"));
        assertFalse(out.contains("FOOTER"));
        assertFalse(out.contains("<p>"));
    }

    @Test
    void plainTextPassesThroughAndRedirectsAreFollowed() throws Exception {
        assertEquals("HTTP 200\njust plain text", fetch("/plain"));
        assertEquals("HTTP 200\njust plain text", fetch("/redirect"));
    }

    @Test
    void outputIsTruncatedToConfiguredMax() throws Exception {
        web = new WebAgentTools(config.withFetchMaxChars(100), MAPPER);

        String out = fetch("/big");

        assertTrue(out.endsWith("\n[truncated]"));
        assertEquals("HTTP 200\n".length() + 100 + "\n[truncated]".length(), out.length());
    }

    private void serveBytes(String path, String type, byte[] body) {
        server.createContext(path, ex -> {
            if (type != null) ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    @Test
    void bodyIsReadOnlyUpToFetchMaxBytes() throws Exception {
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 100, AddressPolicy.OPEN);

        String out = fetch("/big");

        assertTrue(out.startsWith("HTTP 200\n" + "z".repeat(100) + "\n[truncated: only the first 100 bytes of 500"),
                out);
        assertTrue(out.endsWith("(web.fetchMaxBytes)]"), out);
    }

    @Test
    void binaryContentIsRefusedWithAHintToDownload() {
        serveBytes("/img", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0});
        serveBytes("/untyped", null, new byte[]{1, 2, 0, 3});
        for (String path : new String[]{"/img", "/untyped"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> fetch(path), path);
            assertTrue(e.getMessage().contains("web_download"), e.getMessage());
        }
    }

    @Test
    void charsetOfTheContentTypeIsUsed() throws Exception {
        serveBytes("/latin", "text/plain; charset=ISO-8859-1", "caffè".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("HTTP 200\ncaffè", fetch("/latin"));
    }

    @Test
    void textTypesAreRecognized() {
        assertTrue(WebAgentTools.isTextType("application/json"));
        assertTrue(WebAgentTools.isTextType("application/vnd.api+json"));
        assertTrue(WebAgentTools.isTextType("image/svg+xml"));
        assertTrue(WebAgentTools.isTextType("text/csv"));
        assertFalse(WebAgentTools.isTextType("application/pdf"));
        assertFalse(WebAgentTools.isTextType("application/octet-stream"));
        assertEquals("text/html", WebAgentTools.mimeType("Text/HTML; charset=utf-8"));
    }

    @Test
    void httpErrorStatusIsReportedNotThrown() throws Exception {
        assertEquals("HTTP 404\nnope", fetch("/missing"));
    }

    @Test
    void nonHttpSchemesAreRejected() {
        for (String url : new String[]{"file:///etc/passwd", "ftp://example.com/x", "jar:file:/x!/y"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> tool("web_fetch").execute(ctx, json("{'url':'" + url + "'}")), url);
        }
    }

    @Test
    void blockedFirstUrlIsRefusedForFetchAndDownload() {
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0, new AddressPolicy(true, java.util.List.of()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> fetch("/plain"));
        assertTrue(e.getMessage().contains("refused"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> tool("web_download")
                .execute(ctx, json("{'url':'" + base + "/plain','path':'x.txt'}")));
        assertFalse(java.nio.file.Files.exists(tmp.resolve("x.txt")));
    }

    @Test
    void blockedRedirectTargetIsRefused() {
        server.createContext("/to-metadata", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0,
                new AddressPolicy(false, java.util.List.of("169.254.169.254")));

        assertEquals("HTTP 200\njust plain text", assertDoesNotThrow(() -> fetch("/plain")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> fetch("/to-metadata"));
        assertTrue(e.getMessage().contains("169.254.169.254"), e.getMessage());
    }

    private String allowed(String address) throws Exception {
        return tool("web_allowed").execute(ctx, json("{'address':'" + address + "'}"));
    }

    @Test
    void webAllowedAnswersAllowedWhenNothingIsBlocked() throws Exception {
        assertEquals("ALLOWED", allowed("http://127.0.0.1:8080/admin"));
    }

    @Test
    void webAllowedIgnoresProtocolPortPathAndParameters() throws Exception {
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0, new AddressPolicy(true, java.util.List.of()));

        for (String a : new String[]{"127.0.0.1", "http://127.0.0.1:8080/admin?x=1#f", "https://user:pw@127.0.0.1/",
                "localhost:9000/a/b", "[::1]:80/x", "::1", "  HTTP://LocalHost  ", "169.254.169.254/latest/meta-data"}) {
            String r = allowed(a);
            assertTrue(r.startsWith("NOT_ALLOWED"), a + " -> " + r);
            assertTrue(r.contains("refused"), r);
        }
        assertEquals("ALLOWED", allowed("https://93.184.216.34:8443/a?b=c"));
    }

    @Test
    void webAllowedFollowsTheExtraAddresses() throws Exception {
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0,
                new AddressPolicy(false, java.util.List.of("10.0.0.0/8", "internal.example")));

        assertTrue(allowed("http://10.1.2.3:81/x").startsWith("NOT_ALLOWED"));
        assertTrue(allowed("internal.example/admin").startsWith("NOT_ALLOWED"));
        assertEquals("ALLOWED", allowed("93.184.216.34"));
    }

    @Test
    void webAllowedRefusesAnAddressWithoutHost() throws Exception {
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0, new AddressPolicy(true, java.util.List.of()));

        for (String a : new String[]{"", "http://", ":8080/x", "///"}) {
            assertTrue(allowed(a).startsWith("NOT_ALLOWED"), a);
        }
    }

    @Test
    void hostOnlyKeepsJustTheHost() {
        assertEquals("example.com", WebAgentTools.hostOnly("https://u@example.com:8443/a?b#c").getHost());
        assertEquals("example.com", WebAgentTools.hostOnly("example.com").getHost());
        assertEquals("[::1]", WebAgentTools.hostOnly("[::1]:80").getHost());
        assertEquals("[::1]", WebAgentTools.hostOnly("::1").getHost());
        assertThrows(IllegalArgumentException.class, () -> WebAgentTools.hostOnly("http:///x"));
    }

    private static java.net.InetAddress loopback() throws Exception {
        return java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    private String fetchUrl(String url) throws Exception {
        return tool("web_fetch").execute(ctx, json("{'url':'" + url + "'}"));
    }

    @Test
    void connectUsesThePinnedAddress() throws Exception {
        // The name exists only in the policy's lookup: the fetch works only when the connect uses the pinned answer.
        String host = "pinned-" + System.nanoTime() + ".jllm.test";
        java.net.InetAddress lo = loopback();
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0,
                new AddressPolicy(false, java.util.List.of("10.0.0.0/8"), h -> new java.net.InetAddress[]{lo}));

        assertEquals("HTTP 200\njust plain text", fetchUrl("http://" + host + ":" + server.getAddress().getPort() + "/plain"));
        assertFalse(GuardedResolverProvider.isPinned(host), "released after the request");
    }

    @Test
    void rebindingAnswerIsNotFollowed() throws Exception {
        String host = "rebind-" + System.nanoTime() + ".jllm.test";
        java.net.InetAddress lo = loopback();
        java.net.InetAddress internal = java.net.InetAddress.getByAddress(new byte[]{10, 9, 9, 9});
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0,
                new AddressPolicy(false, java.util.List.of("10.0.0.0/8"),
                        h -> new java.net.InetAddress[]{calls.getAndIncrement() == 0 ? lo : internal}));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> fetchUrl("http://" + host + ":" + server.getAddress().getPort() + "/plain"));
        assertTrue(e.getMessage().contains("10.9.9.9"), e.getMessage());
    }

    @Test
    void redirectHopToARebindingNameIsRefused() throws Exception {
        String host = "hop-" + System.nanoTime() + ".jllm.test";
        int port = server.getAddress().getPort();
        server.createContext("/to-rebind", ex -> {
            ex.getResponseHeaders().add("Location", "http://" + host + ":" + port + "/plain");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        java.net.InetAddress lo = loopback();
        java.net.InetAddress internal = java.net.InetAddress.getByAddress(new byte[]{10, 9, 9, 9});
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        web = new WebAgentTools(config, MAPPER, StorageQuota.UNLIMITED, 0,
                new AddressPolicy(false, java.util.List.of("10.0.0.0/8"),
                        h -> new java.net.InetAddress[]{calls.getAndIncrement() == 0 ? lo : internal}));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> fetch("/to-rebind"));
        assertTrue(e.getMessage().contains(host) && e.getMessage().contains("10.9.9.9"), e.getMessage());
    }

    @Test
    void unreachableHostSurfacesAsException() {
        server.stop(0);
        assertThrows(Exception.class, () -> fetch("/plain"));
    }

    @Test
    void searchWithoutApiKeyFallsBackToDuckDuckGo() throws Exception {
        server.createContext("/ddg", ex -> {
            byte[] out = ("<div class='result'><a class='result__a' href='//duckduckgo.com/l/?uddg="
                    + "https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&rut=zz'>Title A</a>"
                    + "<a class='result__snippet'>Snippet A</a></div>"
                    + "<div class='result'><a class='result__a' href='https://b.example'>Title B</a></div>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        web = new WebAgentTools(config.withSearchApiKey(""), MAPPER, base + "/ddg");

        String out = tool("web_search").execute(ctx, json("{'query':'q','count':1}"));

        assertEquals("Title A\nhttps://example.com/a?x=1\nSnippet A\n\n", out);
    }

    @Test
    void fetchAndSearchAreReadOnlyDownloadIsAFileEditInTheWebGroup() {
        java.util.Map<String, ToolKind> kinds = new java.util.HashMap<>();
        for (HarnessTool t : web.tools()) {
            assertEquals("Web", t.group(), t.name());
            kinds.put(t.name(), t.kind());
        }
        assertEquals(ToolKind.READ_ONLY, kinds.get("web_fetch"));
        assertEquals(ToolKind.READ_ONLY, kinds.get("web_search"));
        assertEquals(ToolKind.FILE_EDIT, kinds.get("web_download"), "it writes a file: write_file rules cover it");
    }
}

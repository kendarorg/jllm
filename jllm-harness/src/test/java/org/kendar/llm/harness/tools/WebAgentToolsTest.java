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
import static org.mockito.Mockito.when;

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
    void bothToolsAreReadOnlyInTheWebGroup() {
        for (HarnessTool t : web.tools()) {
            assertEquals(ToolKind.READ_ONLY, t.kind());
            assertEquals("Web", t.group());
        }
    }
}

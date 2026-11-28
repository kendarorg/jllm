package org.kendar.llm.harness.sandbox;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.util.BoundedBody;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Answers are read up to a limit: the sandbox daemon's, and the default workspace size. */
class ResponseCapsTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/declared", ex -> { // Content-Length given
            byte[] body = new byte[200_000];
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/chunked", ex -> { // no Content-Length: the size is only known by reading
            ex.sendResponseHeaders(200, 0);
            try {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 100; i++) ex.getResponseBody().write(chunk);
            } catch (IOException e) {
                // the client gave up: that is the point
            }
            ex.close();
        });
        server.createContext("/small", ex -> {
            byte[] body = "{\"ok\":true}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DaemonClient client() {
        return new DaemonClient(HarnessConfig.defaults().sandbox().docker(), "127.0.0.1", port, "t");
    }

    @Test
    void anAnswerWithADeclaredLengthOverTheCapIsRefusedBeforeReadingIt() {
        assertThrows(BoundedBody.TooLargeException.class,
                () -> client().call("GET", "/declared", null, null, null, Duration.ofSeconds(10), 1000));
    }

    @Test
    void aChunkedAnswerOverTheCapIsCutOff() {
        BoundedBody.TooLargeException e = assertThrows(BoundedBody.TooLargeException.class,
                () -> client().call("GET", "/chunked", null, null, null, Duration.ofSeconds(10), 1_000_000));
        assertTrue(e.getMessage().contains("exceeds the limit"), e.getMessage());
    }

    @Test
    void anAnswerUnderTheCapIsReturnedWhole() throws Exception {
        DaemonClient.Response r = client().call("GET", "/small", null, null, null, Duration.ofSeconds(10), 1000);
        assertEquals(200, r.status());
        assertEquals("{\"ok\":true}", r.text());
        assertEquals(200_000, client().call("GET", "/declared", null, null, null, Duration.ofSeconds(10), 200_000).body().length,
                "exactly at the cap is fine");
    }

    @Test
    void theBoundedHandlerWorksWithAPlainHttpClient() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/small")).build(),
                BoundedBody.ofString(100));
        assertEquals("{\"ok\":true}", ok.body());
        IOException e = assertThrows(IOException.class, () -> http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chunked")).build(), BoundedBody.ofString(5000)));
        assertNotNull(BoundedBody.tooLarge(e), "HttpClient wraps it; tooLarge() finds it: " + e);
        assertNull(BoundedBody.tooLarge(new IOException("other")));
    }

    @Test
    void theWorkspaceLimitHasANonZeroDefault() {
        long limit = HarnessConfig.defaults().sandbox().docker().cleanup().maxWorkspaceBytes();
        assertEquals(HarnessConfig.Cleanup.DEFAULT_MAX_WORKSPACE_BYTES, limit);
        assertTrue(limit > 0 && limit <= 2L * 1024 * 1024 * 1024);
    }
}

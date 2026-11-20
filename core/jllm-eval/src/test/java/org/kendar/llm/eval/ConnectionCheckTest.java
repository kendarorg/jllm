package org.kendar.llm.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.eval.TestSupport.*;

@Tag("unit")
class ConnectionCheckTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(String modelId) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", x -> {
            byte[] b = ("{\"data\":[{\"id\":\"" + modelId + "\"}]}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static ModelClient answering(String text) {
        return new ModelClient() {
            public ModelResponse complete(ModelRequest r) {
                return null;
            }

            public String simpleComplete(String s, String u, int m) {
                return text;
            }
        };
    }

    private static EvalConfig config(String base, String targetModel) {
        return load(map("eval.target", "remote.mac", "eval.remote.mac.baseUrl", base,
                "eval.remote.mac.model", targetModel, "eval.judge", "openai", "eval.judge.openai.baseUrl", base,
                "eval.judge.openai.model", targetModel, "eval.timeout.connect", "2"), map());
    }

    @Test
    void reachableServingEndpointsPassAndRecordWarmup() throws Exception {
        String base = serve("qwen3.6-27b");
        ConnectionCheck.Result r = ConnectionCheck.run(config(base, "qwen3.6-27b"), e -> answering("OK"));
        assertTrue(r.warmupMillis().containsKey(ModelEndpoint.Role.TARGET));
        assertTrue(r.warmupMillis().containsKey(ModelEndpoint.Role.JUDGE));
    }

    @Test
    void unreachableEntryFailsNamingEntryAndAddress() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String base = "http://127.0.0.1:" + port + "/v1";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConnectionCheck.run(config(base, "qwen3.6-27b"), x -> answering("OK")));
        assertTrue(e.getMessage().contains("'mac'"), e.getMessage());
        assertTrue(e.getMessage().contains(base), e.getMessage());
    }

    @Test
    void wrongModelFails() throws Exception {
        String base = serve("some-other-model");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConnectionCheck.run(config(base, "qwen3.6-27b"), x -> answering("OK")));
        assertTrue(e.getMessage().contains("not served"), e.getMessage());
    }

    @Test
    void emptyWarmupAnswerFails() throws Exception {
        String base = serve("qwen3.6-27b");
        assertThrows(IllegalStateException.class,
                () -> ConnectionCheck.run(config(base, "qwen3.6-27b"), x -> answering(" ")));
    }
}

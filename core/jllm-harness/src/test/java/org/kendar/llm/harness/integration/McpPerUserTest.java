package org.kendar.llm.harness.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.AuthProvider;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.FileTokenStore;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.auth.LoginRequiredException;
import org.kendar.llm.harness.auth.OAuthCredentialResolver;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** MCP servers whose calls carry each user's own OAuth token. */
class McpPerUserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private HttpServer server;
    private String url;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> valid = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", ex -> {
            List<String> auth = ex.getRequestHeaders().getOrDefault("Authorization", List.of());
            String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (ex.getRequestMethod().equals("DELETE")) {
                seen.add("DELETE " + session);
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            JsonNode req = MAPPER.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = req.path("method").asText();
            seen.add(method + " " + auth + " " + session);
            if (auth.size() != 1 || !valid.contains(auth.get(0))) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            if (req.path("id").isMissingNode()) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String reply;
            if (method.equals("initialize")) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s-" + auth.get(0).replace(' ', '_'));
                reply = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{}}";
            } else if (method.equals("tools/list")) {
                reply = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id")
                        + ",\"result\":{\"tools\":[{\"name\":\"whoami\",\"inputSchema\":{\"type\":\"object\"}}]}}";
            } else {
                reply = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"" + auth.get(0) + "\"}]}}";
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** The requests seen so far; the server thread appends while the test reads. */
    private List<String> snapshot() {
        synchronized (seen) {
            return new ArrayList<>(seen);
        }
    }

    private long count(String prefix) {
        return snapshot().stream().filter(s -> s.startsWith(prefix)).count();
    }

    @Test
    void discoveryUsesTheSharedLoginAndEachCallItsUsersToken() throws Exception {
        valid.add("Bearer shared");
        valid.add("Bearer alice-tok");
        McpHttpClient client = new McpHttpClient(HttpClient.newHttpClient(), MAPPER, url, Map.of())
                .authProvider(AuthProvider.staticKey("Authorization", "Bearer ", "shared"));
        assertEquals(1, client.listTools().size());
        String text = client.callTool("whoami", MAPPER.createObjectNode(), "alice",
                Map.of("authorization", "Bearer alice-tok")).path("content").get(0).path("text").asText();
        assertEquals("Bearer alice-tok", text); // one Authorization header: the user's, not both
        assertTrue(snapshot().stream().noneMatch(s -> s.startsWith("tools/call") && s.contains("shared")), snapshot().toString());
    }

    @Test
    void unauthorizedUserTokenIsRefreshedOnceThenLoginRequired() throws Exception {
        valid.add("Bearer shared");
        McpServerSettings settings = new McpServerSettings("docs", url, null, null, null, null, null, null, null,
                true, null, null, true);
        TokenStore store = new FileTokenStore(tmp);
        store.put("alice", "mcp:docs", new OAuthToken("stale", "r", 0,
                Map.of("client_id", "c", "token_endpoint", "http://127.0.0.1:1/token")));
        int[] refreshed = {0};
        // the user's token is refreshed by the resolver; the fake refresher hands out a valid one
        OAuthCredentialResolver.Source src = new OAuthCredentialResolver.Source("mcp:docs",
                new org.kendar.llm.harness.auth.OAuthLogin() {
                    @Override
                    public String name() {
                        return "mcp:docs";
                    }

                    @Override
                    public OAuthToken login(org.kendar.llm.harness.auth.UserInteraction ui) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public AuthProvider authProvider(TokenStore s, String principalId) {
                        return new org.kendar.llm.harness.auth.RefreshingAuthProvider(principalId, "mcp:docs", s, cur -> {
                            refreshed[0]++;
                            valid.add("Bearer fresh");
                            return new OAuthToken("fresh", null, 0, cur.extra());
                        }, t -> Map.of("authorization", "Bearer " + t.access()), () -> 0);
                    }
                }, true, null, null);
        OAuthCredentialResolver resolver = new OAuthCredentialResolver(store, List.of(src));
        Identity identity = new Identity(resolver, null, id -> HarnessPrincipal.of(id), chat -> chat);
        McpHttpClient client = new McpHttpClient(HttpClient.newHttpClient(), MAPPER, url, Map.of())
                .authProvider(AuthProvider.staticKey("Authorization", "Bearer ", "shared"));
        HarnessTool tool = McpToolProvider.tools(settings, client, MAPPER, identity).get(0);

        assertEquals("Bearer fresh", tool.execute(new ToolContext("alice", tmp), MAPPER.createObjectNode()));
        assertEquals(1, refreshed[0]);

        valid.remove("Bearer fresh"); // revoked, and no refresh token left
        assertEquals("mcp:docs", assertThrows(LoginRequiredException.class,
                () -> tool.execute(new ToolContext("alice", tmp), MAPPER.createObjectNode())).source());
        assertThrows(LoginRequiredException.class,
                () -> tool.execute(new ToolContext("bob", tmp), MAPPER.createObjectNode()));
    }

    @Test
    void principalSessionsAreClosedWhenIdleAndOnLogout() throws Exception {
        valid.add("Bearer a");
        valid.add("Bearer b");
        valid.add("Bearer c");
        AtomicLong now = new AtomicLong(0);
        McpHttpClient client = new McpHttpClient(HttpClient.newHttpClient(), MAPPER, url, Map.of())
                .nanoClock(now::get);
        client.callTool("whoami", null, "a", Map.of("Authorization", "Bearer a"));
        client.callTool("whoami", null, "b", Map.of("Authorization", "Bearer b"));
        assertEquals(2, client.principalSessions());
        now.addAndGet(TimeUnit.MINUTES.toNanos(16));
        client.callTool("whoami", null, "c", Map.of("Authorization", "Bearer c"));
        assertEquals(1, client.principalSessions(), "idle sessions closed");
        client.closeSession("c");
        assertEquals(0, client.principalSessions());
        long deadline = System.currentTimeMillis() + 5000;
        while (count("DELETE") < 3 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(3, count("DELETE"), snapshot().toString());
        // a closed principal gets a new session on its next call
        client.callTool("whoami", null, "a", Map.of("Authorization", "Bearer a"));
        assertEquals(2, count("initialize [Bearer a]"), snapshot().toString());
    }

    @Test
    void chainLetsTheLaterResolverReplaceAHeaderWhateverItsCase() {
        CredentialResolver a = (u, s) -> Map.of("authorization", "file", "X-Other", "1");
        CredentialResolver b = (u, s) -> Map.of("Authorization", "oauth");
        assertEquals(Map.of("Authorization", "oauth", "X-Other", "1"), CredentialResolver.chain(a, b).headers(null, "x"));
        assertSame(a, CredentialResolver.chain(a, null));
        assertSame(b, CredentialResolver.chain(CredentialResolver.NONE, b));
    }
}

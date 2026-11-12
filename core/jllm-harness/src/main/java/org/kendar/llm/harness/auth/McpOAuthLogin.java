package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * OAuth for an MCP HTTP server: discovers the authorization server from the server URL (protected resource metadata,
 * then authorization server metadata), registers a public client dynamically (RFC 7591) and runs the authorization
 * code flow with PKCE. The token store entry is named {@code mcp:<server>} and remembers the client id and token
 * endpoint so later refreshes need no discovery.
 */
public final class McpOAuthLogin implements OAuthLogin {

    private final String server;
    private final String url;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public McpOAuthLogin(String server, String url, HttpClient http, ObjectMapper mapper) {
        this.server = server;
        this.url = url;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp:" + server;
    }

    /** The authorization server endpoints. */
    public record Metadata(String authorizationEndpoint, String tokenEndpoint, String registrationEndpoint) {
    }

    public Metadata discover() {
        URI u = URI.create(url);
        String origin = u.getScheme() + "://" + u.getAuthority();
        String issuer = origin;
        JsonNode resource = getJson(origin + "/.well-known/oauth-protected-resource");
        if (resource != null && resource.path("authorization_servers").isArray()
                && !resource.get("authorization_servers").isEmpty()) {
            issuer = resource.get("authorization_servers").get(0).asText().replaceAll("/+$", "");
        }
        JsonNode m = getJson(issuer + "/.well-known/oauth-authorization-server");
        if (m == null) throw new OAuthException("discovery_failed", "No authorization server metadata at " + issuer);
        String auth = m.path("authorization_endpoint").asText("");
        String token = m.path("token_endpoint").asText("");
        if (auth.isEmpty() || token.isEmpty()) {
            throw new OAuthException("discovery_failed", "Incomplete authorization server metadata at " + issuer);
        }
        return new Metadata(auth, token, m.path("registration_endpoint").asText(null));
    }

    /** Registers a public client for {@code redirectUri}. @return the client id */
    public String register(Metadata meta, String redirectUri) {
        if (meta.registrationEndpoint() == null) {
            throw new OAuthException("registration_unsupported", "The authorization server has no dynamic client registration");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("client_name", "jllm");
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(meta.registrationEndpoint()))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode j = mapper.readTree(r.body());
            if (r.statusCode() / 100 != 2 || !j.hasNonNull("client_id")) {
                throw new OAuthException(j.path("error").asText("registration_failed"), j.path("error_description").asText(null));
            }
            return j.get("client_id").asText();
        } catch (IOException e) {
            throw new OAuthException("io", e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("io", "interrupted", e);
        }
    }

    @Override
    public OAuthToken login(UserInteraction ui) {
        Metadata meta = discover();
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        } catch (IOException e) {
            throw new OAuthException("io", "No free local port: " + e.getMessage(), e);
        }
        String clientId = register(meta, "http://127.0.0.1:" + port + "/callback");
        OAuthTokenClient tokens = new OAuthTokenClient(http, mapper, meta.tokenEndpoint(), clientId, null);
        OAuthToken t = new AuthCodeLogin(name(), meta.authorizationEndpoint(), tokens, clientId, null, null, port,
                AuthCodeLogin.bearer()).login(ui);
        Map<String, String> extra = new HashMap<>(t.extra());
        extra.put("client_id", clientId);
        extra.put("token_endpoint", meta.tokenEndpoint());
        return new OAuthToken(t.access(), t.refresh(), t.expiresAt(), extra);
    }

    @Override
    public AuthProvider authProvider(TokenStore store) {
        return new RefreshingAuthProvider(name(), store, refreshToken -> {
            OAuthToken cur = store.get(name()).orElseThrow(() -> new IllegalStateException(
                    "Not logged in to " + name() + ": run /login " + name()));
            OAuthToken fresh = new OAuthTokenClient(http, mapper, cur.extra().get("token_endpoint"),
                    cur.extra().get("client_id"), null).refresh(refreshToken);
            Map<String, String> extra = new HashMap<>(cur.extra());
            extra.putAll(fresh.extra());
            return new OAuthToken(fresh.access(), fresh.refresh(), fresh.expiresAt(), extra);
        }, AuthCodeLogin.bearer(), () -> System.currentTimeMillis() / 1000);
    }

    private JsonNode getJson(String u) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2 ? mapper.readTree(r.body()) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

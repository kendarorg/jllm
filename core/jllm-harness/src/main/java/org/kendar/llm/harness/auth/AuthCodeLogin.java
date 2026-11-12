package org.kendar.llm.harness.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Authorization code flow with PKCE and a loopback redirect: browser, callback, token exchange. */
public class AuthCodeLogin implements OAuthLogin {

    private final String name;
    private final String authorizationEndpoint;
    private final OAuthTokenClient tokens;
    private final String clientId;
    private final String scope;
    private final Map<String, String> extraParams;
    private final int port;
    private final Function<OAuthToken, Map<String, String>> headerBuilder;

    /** @param port loopback port, 0 = free; @param extraParams extra authorization request parameters */
    public AuthCodeLogin(String name, String authorizationEndpoint, OAuthTokenClient tokens, String clientId,
                         String scope, Map<String, String> extraParams, int port,
                         Function<OAuthToken, Map<String, String>> headerBuilder) {
        this.name = name;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokens = tokens;
        this.clientId = clientId;
        this.scope = scope;
        this.extraParams = extraParams == null ? Map.of() : extraParams;
        this.port = port;
        this.headerBuilder = headerBuilder;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public OAuthToken login(UserInteraction ui) {
        Pkce pkce = Pkce.generate();
        try (LoopbackCallbackServer cb = new LoopbackCallbackServer(port, pkce.state())) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("response_type", "code");
            q.put("client_id", clientId);
            q.put("redirect_uri", cb.redirectUri());
            if (scope != null && !scope.isBlank()) q.put("scope", scope);
            q.put("state", pkce.state());
            q.put("code_challenge", pkce.challenge());
            q.put("code_challenge_method", "S256");
            q.putAll(extraParams);
            String url = authorizationEndpoint + (authorizationEndpoint.contains("?") ? "&" : "?") + q.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            ui.openUrl(url);
            String code = cb.awaitCode(Duration.ofMinutes(5));
            return tokens.exchangeCode(code, cb.redirectUri(), pkce.verifier());
        } catch (IOException e) {
            throw new OAuthException("io", "Cannot listen for the login redirect: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthProvider authProvider(TokenStore store) {
        return new RefreshingAuthProvider(name, store, tokens::refresh, headerBuilder,
                () -> System.currentTimeMillis() / 1000);
    }

    /** The common header shape: {@code Authorization: Bearer <access>}. */
    public static Function<OAuthToken, Map<String, String>> bearer() {
        return t -> Map.of("authorization", "Bearer " + t.access());
    }
}

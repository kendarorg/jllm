package org.kendar.llm.harness.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Authorization code flow with PKCE: browser, redirect (loopback or web callback), token exchange. */
public class AuthCodeLogin implements OAuthLogin {

    /** How long a login waits for the browser to come back. */
    public static final Duration TIMEOUT = Duration.ofMinutes(10);

    /** Exchanges the code; {@code clientId} is the one the authorization request used. */
    @FunctionalInterface
    public interface Exchange {
        OAuthToken exchange(String clientId, String code, String redirectUri, String verifier);
    }

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
        return login(ui, RedirectTarget.loopback(port));
    }

    @Override
    public OAuthToken login(UserInteraction ui, RedirectTarget target) {
        return run(ui, target == null ? RedirectTarget.loopback(port) : target, authorizationEndpoint,
                redirect -> clientId, scope, extraParams,
                (id, code, redirect, verifier) -> tokens.exchangeCode(code, redirect, verifier));
    }

    /**
     * The flow itself: PKCE and state, the receiver of the redirect, the authorization URL shown to the user, the
     * code exchange.
     *
     * @param clientIdFor the client id for a redirect URI (dynamic registration registers that URI)
     */
    public static OAuthToken run(UserInteraction ui, RedirectTarget target, String authorizationEndpoint,
                                 Function<String, String> clientIdFor, String scope, Map<String, String> extraParams,
                                 Exchange exchange) {
        Pkce pkce = Pkce.generate();
        try (RedirectTarget.Receiver cb = target.open(pkce.state())) {
            String clientId = clientIdFor.apply(cb.redirectUri());
            Map<String, String> q = new LinkedHashMap<>();
            q.put("response_type", "code");
            q.put("client_id", clientId);
            q.put("redirect_uri", cb.redirectUri());
            if (scope != null && !scope.isBlank()) q.put("scope", scope);
            q.put("state", pkce.state());
            q.put("code_challenge", pkce.challenge());
            q.put("code_challenge_method", "S256");
            if (extraParams != null) q.putAll(extraParams);
            String url = authorizationEndpoint + (authorizationEndpoint.contains("?") ? "&" : "?") + q.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            ui.openUrl(url);
            String code = cb.awaitCode(TIMEOUT);
            return exchange.exchange(clientId, code, cb.redirectUri(), pkce.verifier());
        } catch (IOException e) {
            throw new OAuthException("io", "Cannot receive the login redirect: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthProvider authProvider(TokenStore store, String principalId) {
        return new RefreshingAuthProvider(principalId, name, store, t -> tokens.refresh(t.refresh()), headerBuilder,
                () -> System.currentTimeMillis() / 1000);
    }

    /** The common header shape: {@code Authorization: Bearer <access>}. */
    public static Function<OAuthToken, Map<String, String>> bearer() {
        return t -> Map.of("authorization", "Bearer " + t.access());
    }
}

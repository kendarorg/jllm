package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.integration.RestApiToolProvider;
import org.kendar.llm.harness.settings.RestApiSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The OAuth2 login of a REST API ({@code restApis[].oauth}), named {@code api:<name>}. Endpoints come from, in this
 * order: the explicit URLs, the issuer's metadata ({@code /.well-known/oauth-authorization-server}, then
 * {@code /.well-known/openid-configuration}), the first {@code oauth2} scheme of the OpenAPI spec with the flow.
 * Without a {@code clientId}, a public client is registered dynamically (issuer with a {@code registration_endpoint});
 * its id and token endpoint are kept in the token so refreshes need no discovery.
 */
public final class RestOAuthLogin implements OAuthLogin {

    /** Resolved endpoints and scopes. */
    public record Endpoints(String authorization, String token, String device, String registration, List<String> scopes) {
        String scope() {
            return scopes == null || scopes.isEmpty() ? null : String.join(" ", scopes);
        }
    }

    private final RestApiSettings api;
    private final RestApiSettings.OAuth oauth;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private volatile Endpoints endpoints;

    public RestOAuthLogin(RestApiSettings api, HttpClient http, ObjectMapper mapper) {
        this(api, http, mapper, () -> System.currentTimeMillis() / 1000);
    }

    public RestOAuthLogin(RestApiSettings api, HttpClient http, ObjectMapper mapper, LongSupplier clock) {
        if (api.oauth() == null) throw new IllegalArgumentException("REST API '" + api.name() + "' has no oauth");
        this.api = api;
        this.oauth = api.oauth();
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return api.source();
    }

    public RestApiSettings api() {
        return api;
    }

    public boolean perUser() {
        return oauth.effectivePerUser();
    }

    public boolean clientCredentials() {
        return oauth.clientCredentials();
    }

    /**
     * The endpoints, discovered once.
     *
     * @throws IllegalStateException naming the API when the flow's endpoints are found nowhere
     */
    public Endpoints endpoints() {
        Endpoints e = endpoints;
        if (e != null) return e;
        synchronized (this) {
            if (endpoints == null) endpoints = discover();
            return endpoints;
        }
    }

    private Endpoints discover() {
        String auth = blankToNull(oauth.authorizationUrl());
        String token = blankToNull(oauth.tokenUrl());
        String device = blankToNull(oauth.deviceAuthorizationUrl());
        String registration = null;
        if (blankToNull(oauth.issuer()) != null) {
            String issuer = oauth.issuer().replaceAll("/+$", "");
            JsonNode m = getJson(issuer + "/.well-known/oauth-authorization-server");
            if (m == null) m = getJson(issuer + "/.well-known/openid-configuration");
            if (m == null) {
                throw new IllegalStateException("REST API '" + api.name() + "': no authorization server metadata at "
                        + issuer);
            }
            if (auth == null) auth = blankToNull(m.path("authorization_endpoint").asText(null));
            if (token == null) token = blankToNull(m.path("token_endpoint").asText(null));
            if (device == null) device = blankToNull(m.path("device_authorization_endpoint").asText(null));
            registration = blankToNull(m.path("registration_endpoint").asText(null));
        }
        List<String> scopes = oauth.scopes();
        String flow = oauth.effectiveFlow();
        boolean needAuth = RestApiSettings.OAuth.AUTHORIZATION_CODE.equals(flow);
        if (token == null || (needAuth && auth == null) || scopes == null) {
            RestApiToolProvider.SpecOAuth spec = specOAuth(flow);
            if (spec != null) {
                if (token == null) token = blankToNull(spec.tokenUrl());
                if (auth == null) auth = blankToNull(spec.authorizationUrl());
                if (scopes == null) scopes = spec.scopes();
            }
        }
        if (token == null) {
            throw new IllegalStateException("REST API '" + api.name() + "': no OAuth token endpoint (set oauth.tokenUrl, "
                    + "oauth.issuer, or an oauth2 scheme in the spec)");
        }
        if (needAuth && auth == null) {
            throw new IllegalStateException("REST API '" + api.name() + "': no OAuth authorization endpoint (set "
                    + "oauth.authorizationUrl, oauth.issuer, or an oauth2 scheme in the spec)");
        }
        if (RestApiSettings.OAuth.DEVICE_CODE.equals(flow) && device == null) {
            throw new IllegalStateException("REST API '" + api.name() + "': no device authorization endpoint (set "
                    + "oauth.deviceAuthorizationUrl or oauth.issuer)");
        }
        if (blankToNull(oauth.clientId()) == null && registration == null) {
            throw new IllegalStateException("REST API '" + api.name() + "': oauth.clientId is required (the issuer "
                    + "offers no dynamic client registration)");
        }
        return new Endpoints(auth, token, device, registration, scopes == null ? List.of() : scopes);
    }

    private RestApiToolProvider.SpecOAuth specOAuth(String flow) {
        // read with the static headers only: a spec behind specAuth needs its endpoints in the settings
        try {
            return RestApiToolProvider.specOAuth(api, flow, Map.of());
        } catch (RuntimeException e) {
            return null; // the spec cannot be read now: the explicit settings must be enough
        }
    }

    private OAuthTokenClient tokens(String clientId) {
        return new OAuthTokenClient(http, mapper, endpoints().token(), clientId, oauth.clientSecret(), clock);
    }

    @Override
    public OAuthToken login(UserInteraction ui) {
        return login(ui, RedirectTarget.loopback());
    }

    @Override
    public OAuthToken login(UserInteraction ui, RedirectTarget target) {
        Endpoints e = endpoints();
        if (oauth.clientCredentials()) {
            throw new OAuthException("unsupported", name() + " uses a service account (client_credentials): no login");
        }
        if (RestApiSettings.OAuth.DEVICE_CODE.equals(oauth.effectiveFlow())) {
            String clientId = requireClientId();
            OAuthToken t = DeviceCodeFlow.run(http, mapper, e.device(), clientId, e.scope(), tokens(clientId), c -> {
                ui.show("Open " + c.verificationUri() + " and enter the code " + c.userCode());
                if (c.verificationUriComplete() != null) ui.openUrl(c.verificationUriComplete());
            }, Thread::sleep, clock);
            return remember(t, clientId);
        }
        String[] used = {null};
        OAuthToken t = AuthCodeLogin.run(ui, target == null ? RedirectTarget.loopback() : target, e.authorization(),
                redirect -> used[0] = blankToNull(oauth.clientId()) != null ? oauth.clientId() : register(e, redirect),
                e.scope(), oauth.extraParams(),
                (id, code, redirect, verifier) -> tokens(id).exchangeCode(code, redirect, verifier));
        return remember(t, used[0]);
    }

    private String requireClientId() {
        String id = blankToNull(oauth.clientId());
        if (id == null) throw new OAuthException("invalid_client", "oauth.clientId is required for the device flow");
        return id;
    }

    /** Keeps the client id and the token endpoint in the token: refreshes need no discovery. */
    private OAuthToken remember(OAuthToken t, String clientId) {
        Map<String, String> extra = new HashMap<>(t.extra());
        extra.put("client_id", clientId);
        extra.put("token_endpoint", endpoints().token());
        return new OAuthToken(t.access(), t.refresh(), t.expiresAt(), extra);
    }

    /** A service token ({@code client_credentials}), for the calls or for reading the spec. */
    public OAuthToken clientCredentialsToken() {
        String id = blankToNull(oauth.clientId());
        if (id == null) throw new OAuthException("invalid_client", "oauth.clientId is required for client_credentials");
        return tokens(id).clientCredentials(endpoints().scope(), oauth.extraParams().isEmpty() ? null : oauth.extraParams());
    }

    /** Exchanges the refresh token of {@code cur}; keeps the remembered client id and endpoint. */
    public OAuthToken refresh(OAuthToken cur) {
        String endpoint = cur.extra().getOrDefault("token_endpoint", null);
        String clientId = cur.extra().getOrDefault("client_id", oauth.clientId());
        OAuthTokenClient client = endpoint != null
                ? new OAuthTokenClient(http, mapper, endpoint, clientId, oauth.clientSecret(), clock)
                : tokens(clientId);
        OAuthToken fresh = client.refresh(cur.refresh());
        Map<String, String> extra = new HashMap<>(cur.extra());
        extra.putAll(fresh.extra());
        return new OAuthToken(fresh.access(), fresh.refresh(), fresh.expiresAt(), extra);
    }

    /** The configured header ({@code Authorization: Bearer <access>} by default). */
    public Map<String, String> header(OAuthToken t) {
        return Map.of(oauth.effectiveHeader(), oauth.headerValue(t.access()));
    }

    @Override
    public AuthProvider authProvider(TokenStore store, String principalId) {
        return new RefreshingAuthProvider(principalId, name(), store, this::refresh, this::header, clock);
    }

    private String register(Endpoints e, String redirectUri) {
        if (e.registration() == null) {
            throw new OAuthException("registration_unsupported", "No clientId and no dynamic client registration");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("client_name", "jllm");
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        if (e.scope() != null) body.put("scope", e.scope());
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(e.registration()))
                            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode j = mapper.readTree(r.body());
            if (r.statusCode() / 100 != 2 || !j.hasNonNull("client_id")) {
                throw new OAuthException(j.path("error").asText("registration_failed"), j.path("error_description").asText(null));
            }
            return j.get("client_id").asText();
        } catch (IOException ex) {
            throw new OAuthException("io", ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OAuthException("io", "interrupted", ex);
        }
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

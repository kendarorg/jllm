package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Form-encoded token endpoint calls: authorization code, refresh token and device code grants. */
public final class OAuthTokenClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final LongSupplier clock;

    public OAuthTokenClient(HttpClient http, ObjectMapper mapper, String tokenEndpoint, String clientId,
                            String clientSecret) {
        this(http, mapper, tokenEndpoint, clientId, clientSecret, () -> System.currentTimeMillis() / 1000);
    }

    /** @param clock epoch seconds, replaceable in tests */
    public OAuthTokenClient(HttpClient http, ObjectMapper mapper, String tokenEndpoint, String clientId,
                            String clientSecret, LongSupplier clock) {
        this.http = http;
        this.mapper = mapper;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    public OAuthToken exchangeCode(String code, String redirectUri, String codeVerifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", redirectUri);
        if (codeVerifier != null) f.put("code_verifier", codeVerifier);
        return post(f);
    }

    /** @throws OAuthException with code {@code invalid_grant} when the refresh token is no longer valid */
    public OAuthToken refresh(String refreshToken) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        OAuthToken t = post(f);
        // a server that does not rotate the refresh token omits it: keep the old one
        return t.refresh() == null ? new OAuthToken(t.access(), refreshToken, t.expiresAt(), t.extra()) : t;
    }

    /** Client credentials grant (a service account): no user, no refresh token. */
    public OAuthToken clientCredentials(String scope, Map<String, String> extraParams) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "client_credentials");
        if (scope != null && !scope.isBlank()) f.put("scope", scope);
        if (extraParams != null) f.putAll(extraParams);
        return post(f);
    }

    /** @throws OAuthException with code {@code authorization_pending} or {@code slow_down} while the user has not finished */
    public OAuthToken deviceToken(String deviceCode) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        f.put("device_code", deviceCode);
        return post(f);
    }

    private OAuthToken post(Map<String, String> form) {
        Map<String, String> f = new LinkedHashMap<>(form);
        f.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) f.put("client_secret", clientSecret);
        JsonNode json = postForm(http, mapper, tokenEndpoint, f);
        String access = json.path("access_token").asText("");
        if (access.isEmpty()) throw new OAuthException("invalid_response", "No access_token in the token response");
        long exp = json.hasNonNull("expires_in") ? clock.getAsLong() + json.get("expires_in").asLong() : 0;
        Map<String, String> extra = new HashMap<>();
        json.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (e.getValue().isValueNode() && !k.equals("access_token") && !k.equals("refresh_token")
                    && !k.equals("expires_in") && !k.equals("token_type")) extra.put(k, e.getValue().asText());
        });
        return new OAuthToken(access, json.hasNonNull("refresh_token") ? json.get("refresh_token").asText() : null,
                exp, extra);
    }

    /** POSTs a form and returns the JSON body; maps OAuth error bodies to {@link OAuthException}. */
    static JsonNode postForm(HttpClient http, ObjectMapper mapper, String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = mapper.readTree(resp.body());
            } catch (IOException e) {
                json = null;
            }
            if (resp.statusCode() / 100 != 2 || json == null || json.hasNonNull("error")) {
                String code = json != null && json.hasNonNull("error") ? json.get("error").asText() : "http_" + resp.statusCode();
                throw new OAuthException(code, json == null ? null : json.path("error_description").asText(null));
            }
            return json;
        } catch (IOException e) {
            throw new OAuthException("io", e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("io", "interrupted", e);
        }
    }
}

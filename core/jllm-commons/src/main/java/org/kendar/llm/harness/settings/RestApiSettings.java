package org.kendar.llm.harness.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A REST API described by an OpenAPI spec. Only the operations that pass {@code allowedPaths} and {@code denyPaths}
 * become tools.
 *
 * @param baseUrl      overrides the first server of the spec
 * @param openApiUrl   spec location (http/https); alternative to {@code openApiFile}
 * @param enabled      default true
 * @param allowedPaths entries like {@code "GET /pets"}, {@code "/pets/{id}"} or {@code "/store/*"}; the method is
 *                     optional and {@code *} is a wildcard. {@code null} (absent) allows all operations, an empty
 *                     list allows none, {@code ["*"]} all.
 * @param denyPaths    same syntax; matching operations are hidden. Empty or absent hides nothing, {@code ["*"]}
 *                     hides everything.
 * @param oauth        OAuth2 for the calls; null: none (static {@code headers} only)
 */
public record RestApiSettings(String name, String baseUrl, String openApiUrl, String openApiFile,
                              Map<String, String> headers, String group, List<String> allowedPaths,
                              Boolean enabled, List<String> denyPaths, OAuth oauth) {

    public RestApiSettings {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedPaths = allowedPaths == null ? null : List.copyOf(allowedPaths);
        denyPaths = denyPaths == null ? null : List.copyOf(denyPaths);
        if (oauth != null) oauth.validate(name);
    }

    public boolean active() {
        return enabled == null || enabled;
    }

    public String effectiveGroup() {
        return group == null || group.isBlank() ? "Rest/" + name : group;
    }

    /** The token store and credential source name of this API: {@code api:<name>}. */
    public String source() {
        return "api:" + name;
    }

    /**
     * OAuth2 of a REST API.
     *
     * @param flow             {@code authorization_code} (default, PKCE always), {@code device_code}, or
     *                         {@code client_credentials} (a service account, no login)
     * @param issuer           authorization server; its metadata ({@code /.well-known/oauth-authorization-server},
     *                         then {@code openid-configuration}) gives the endpoints
     * @param authorizationUrl explicit endpoint; wins over the issuer
     * @param tokenUrl         explicit endpoint; wins over the issuer
     * @param deviceAuthorizationUrl explicit device endpoint ({@code device_code}); wins over the issuer
     * @param clientId         required, except with dynamic registration (issuer with a {@code registration_endpoint})
     * @param clientSecret     none = public client; only through {@code ${ENV}}
     * @param scopes           default: the scopes of the spec's oauth2 scheme used by the allowed operations
     * @param extraParams      extra authorization request parameters (e.g. {@code audience})
     * @param perUser          default true ({@code client_credentials}: false): every user logs in with their own
     *                         account; false: one token shared by every user, logged in by an admin
     * @param header           default {@code Authorization}
     * @param scheme           default {@code Bearer}; empty sends the bare token
     * @param specAuth         {@code client_credentials}: the spec is fetched with a client credentials token
     */
    public record OAuth(String flow, String issuer, String authorizationUrl, String tokenUrl,
                        String deviceAuthorizationUrl, String clientId, String clientSecret, List<String> scopes,
                        Map<String, String> extraParams, Boolean perUser, String header, String scheme,
                        String specAuth) {

        public static final String AUTHORIZATION_CODE = "authorization_code";
        public static final String DEVICE_CODE = "device_code";
        public static final String CLIENT_CREDENTIALS = "client_credentials";
        private static final Set<String> FLOWS = Set.of(AUTHORIZATION_CODE, DEVICE_CODE, CLIENT_CREDENTIALS);

        public OAuth {
            scopes = scopes == null ? null : List.copyOf(scopes);
            extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
        }

        void validate(String api) {
            if (!FLOWS.contains(effectiveFlow())) {
                throw new IllegalArgumentException("REST API '" + api + "': unknown oauth flow '" + flow
                        + "' (authorization_code, device_code or client_credentials)");
            }
            if (CLIENT_CREDENTIALS.equals(effectiveFlow()) && Boolean.TRUE.equals(perUser)) {
                throw new IllegalArgumentException("REST API '" + api
                        + "': oauth flow client_credentials cannot be perUser (there is no user to log in)");
            }
            if (specAuth != null && !specAuth.isBlank() && !CLIENT_CREDENTIALS.equals(specAuth)) {
                throw new IllegalArgumentException("REST API '" + api + "': oauth specAuth can only be client_credentials");
            }
            if (CLIENT_CREDENTIALS.equals(effectiveFlow()) && blank(clientId)) {
                throw new IllegalArgumentException("REST API '" + api + "': oauth client_credentials needs a clientId");
            }
        }

        public String effectiveFlow() {
            return blank(flow) ? AUTHORIZATION_CODE : flow.strip();
        }

        public boolean clientCredentials() {
            return CLIENT_CREDENTIALS.equals(effectiveFlow());
        }

        public boolean effectivePerUser() {
            return perUser != null ? perUser : !clientCredentials();
        }

        public String effectiveHeader() {
            return blank(header) ? "Authorization" : header;
        }

        /** The header value for an access token. */
        public String headerValue(String access) {
            String s = scheme == null ? "Bearer" : scheme.strip();
            return s.isEmpty() ? access : s + " " + access;
        }

        public boolean specWithClientCredentials() {
            return CLIENT_CREDENTIALS.equals(specAuth);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }
}

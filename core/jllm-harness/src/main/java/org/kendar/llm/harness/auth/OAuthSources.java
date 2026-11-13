package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.RestApiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OAuth sources of a harness, built from the settings: the REST APIs with {@code oauth} and the MCP servers with
 * {@code oauth}. Gives the logins {@code /login} runs, and the resolver that adds the caller's token to each call
 * (REST APIs, and MCP servers whose calls are per user).
 */
public final class OAuthSources {

    private static final Logger log = LoggerFactory.getLogger(OAuthSources.class);

    private final OAuthCredentialResolver resolver;
    private final Map<String, OAuthLogin> logins;
    private final Map<String, RestOAuthLogin> apis;
    private final Map<String, Boolean> mcpPerUser;

    private OAuthSources(OAuthCredentialResolver resolver, Map<String, OAuthLogin> logins,
                         Map<String, RestOAuthLogin> apis, Map<String, Boolean> mcpPerUser) {
        this.resolver = resolver;
        this.logins = logins;
        this.apis = apis;
        this.mcpPerUser = mcpPerUser;
    }

    /**
     * @param multiUser the harness serves several users: MCP servers with oauth default to per-user tokens
     * @throws IllegalStateException naming the API when an active API declares oauth without any endpoint (no
     *                               tokenUrl, no issuer, no oauth2 scheme in the spec)
     */
    public static OAuthSources from(HarnessSettings settings, TokenStore store, HttpClient http, ObjectMapper mapper,
                                    boolean multiUser) {
        List<OAuthCredentialResolver.Source> sources = new ArrayList<>();
        Map<String, OAuthLogin> logins = new LinkedHashMap<>();
        Map<String, RestOAuthLogin> apis = new LinkedHashMap<>();
        Map<String, Boolean> mcpPerUser = new LinkedHashMap<>();
        for (RestApiSettings a : settings.restApis()) {
            if (a.oauth() == null) continue;
            RestOAuthLogin login = new RestOAuthLogin(a, http, mapper);
            if (a.active() && blank(a.oauth().tokenUrl()) && blank(a.oauth().issuer())) {
                login.endpoints(); // only the spec can give them: fail at startup, not at the first call
            }
            if (a.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase(a.oauth().effectiveHeader()))) {
                log.warn("REST API '{}': headers.{} is replaced by the OAuth token", a.name(), a.oauth().effectiveHeader());
            }
            apis.put(a.source(), login);
            if (login.clientCredentials()) {
                sources.add(new OAuthCredentialResolver.Source(a.source(), login, false, login::clientCredentialsToken,
                        login::header));
            } else {
                sources.add(new OAuthCredentialResolver.Source(a.source(), login, login.perUser(), null, null));
                logins.put(a.source(), login);
            }
        }
        for (McpServerSettings m : settings.mcpServers()) {
            if (m.isStdio() || !Boolean.TRUE.equals(m.oauth())) continue;
            McpOAuthLogin login = new McpOAuthLogin(m.name(), m.url(), http, mapper);
            logins.put(login.name(), login);
            boolean perUser = m.perUserOAuth(multiUser);
            mcpPerUser.put(m.source(), perUser);
            // per-user calls carry the caller's token; the shared login stays with the client (discovery)
            if (perUser) sources.add(new OAuthCredentialResolver.Source(m.source(), login, true, null, null));
        }
        return new OAuthSources(new OAuthCredentialResolver(store, sources), Map.copyOf(logins), Map.copyOf(apis),
                Map.copyOf(mcpPerUser));
    }

    public OAuthCredentialResolver resolver() {
        return resolver;
    }

    /** The logins of the sources, by name ({@code api:<name>}, {@code mcp:<server>}); service accounts excluded. */
    public Map<String, OAuthLogin> logins() {
        return logins;
    }

    /** Whether each user has their own token for the source (else one shared login, by an admin). */
    public boolean perUser(String source) {
        if (apis.containsKey(source)) return apis.get(source).perUser() && !apis.get(source).clientCredentials();
        return Boolean.TRUE.equals(mcpPerUser.get(source));
    }

    /** True when a login of the source sends the browser back (authorization code), false for the device flow. */
    public boolean needsRedirect(String source) {
        RestOAuthLogin l = apis.get(source);
        if (l == null) return mcpPerUser.containsKey(source);
        return RestApiSettings.OAuth.AUTHORIZATION_CODE.equals(l.api().oauth().effectiveFlow());
    }

    /** True for a REST API that uses a service account (no login). */
    public boolean serviceAccount(String source) {
        RestOAuthLogin l = apis.get(source);
        return l != null && l.clientCredentials();
    }

    /** All OAuth source names, sorted. */
    public List<String> names() {
        List<String> out = new ArrayList<>(apis.keySet());
        out.addAll(mcpPerUser.keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** Headers to fetch an API's spec with: a client credentials token when {@code oauth.specAuth} asks for it. */
    public Map<String, String> specHeaders(RestApiSettings api) {
        if (api.oauth() == null || !api.oauth().specWithClientCredentials()) return Map.of();
        RestOAuthLogin l = apis.get(api.source());
        return l == null ? Map.of() : l.header(l.clientCredentialsToken());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

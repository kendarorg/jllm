package org.kendar.llm.harness.auth;

import org.kendar.llm.harness.settings.OAuthSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.llm.ProviderSettings;
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
    private final Map<String, ProviderSettings> providers;

    private OAuthSources(OAuthCredentialResolver resolver, Map<String, OAuthLogin> logins,
                         Map<String, RestOAuthLogin> apis, Map<String, Boolean> mcpPerUser,
                         Map<String, ProviderSettings> providers) {
        this.resolver = resolver;
        this.logins = logins;
        this.apis = apis;
        this.mcpPerUser = mcpPerUser;
        this.providers = providers;
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
        Map<String, ProviderSettings> providers = new LinkedHashMap<>();
        for (ProviderSettings p : settings.providers()) {
            if (!p.perUser()) continue;
            providers.put(p.source(), p);
            if (ProviderSettings.USER_OAUTH.equals(p.userAuth())) {
                GenericOAuthLogin login = new GenericOAuthLogin(p.source(), "Provider '" + p.name() + "'", p.oauth(),
                        http, mapper);
                if (blank(p.oauth().tokenUrl()) && blank(p.oauth().issuer())) {
                    throw new IllegalStateException("Provider '" + p.name()
                            + "': oauth needs a tokenUrl or an issuer (there is no spec to read endpoints from)");
                }
                sources.add(new OAuthCredentialResolver.Source(p.source(), login, true, null, null));
                logins.put(p.source(), login);
            } else {
                sources.add(new OAuthCredentialResolver.Source(p.source(),
                        new ProviderTokenLogin(p.source(), p.effectiveType()), true, null, null));
            }
        }
        return new OAuthSources(new OAuthCredentialResolver(store, sources), Map.copyOf(logins), Map.copyOf(apis),
                Map.copyOf(mcpPerUser), Map.copyOf(providers));
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
        if (providers.containsKey(source)) return true;
        if (apis.containsKey(source)) return apis.get(source).perUser() && !apis.get(source).clientCredentials();
        return Boolean.TRUE.equals(mcpPerUser.get(source));
    }

    /** True when a login of the source sends the browser back (authorization code), false for the device flow. */
    public boolean needsRedirect(String source) {
        ProviderSettings p = providers.get(source);
        if (p != null) {
            return ProviderSettings.USER_OAUTH.equals(p.userAuth())
                    && OAuthSettings.AUTHORIZATION_CODE.equals(p.oauth().effectiveFlow());
        }
        RestOAuthLogin l = apis.get(source);
        if (l == null) return mcpPerUser.containsKey(source);
        return OAuthSettings.AUTHORIZATION_CODE.equals(l.api().oauth().effectiveFlow());
    }

    /** True for a REST API that uses a service account (no login). */
    public boolean serviceAccount(String source) {
        RestOAuthLogin l = apis.get(source);
        return l != null && l.clientCredentials();
    }

    /** The provider behind a {@code provider:<name>} source; null for other sources. */
    public ProviderSettings provider(String source) {
        return providers.get(source);
    }

    /** The sources of the providers with per-user credentials (token or oauth), sorted. */
    public List<String> providerSources() {
        return providers.keySet().stream().sorted().toList();
    }

    /** All OAuth source names, sorted: REST APIs, MCP servers and the providers that log in with OAuth. */
    public List<String> names() {
        List<String> out = new ArrayList<>(apis.keySet());
        out.addAll(mcpPerUser.keySet());
        providers.values().stream().filter(p -> ProviderSettings.USER_OAUTH.equals(p.userAuth()))
                .forEach(p -> out.add(p.source()));
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

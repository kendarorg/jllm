package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.auth.OAuthLogin;
import org.kendar.llm.harness.auth.OAuthSources;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.auth.TokenStores;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatSessionService;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;

/** Builds the token store, the OAuth sources and the identity of a harness. */
final class AuthAssembler {

    /** What the external sources need; the {@link HarnessAuth} facade is completed once they and the service exist. */
    record Auth(TokenStore tokenStore, OAuthSources oauth, Identity identity, boolean multiUser, HttpClient http) {
    }

    private AuthAssembler() {
    }

    static Auth assemble(HarnessSettings settings, TokenStore explicitStore, Path tokenStoreDir,
                         CredentialResolver credentials, UserPolicyResolver userPolicies,
                         Function<String, HarnessPrincipal> principalDirectory, ObjectMapper mapper,
                         ChatSessionService sessions) {
        boolean multiUser = userPolicies != null;
        TokenStore tokenStore = explicitStore != null ? explicitStore
                : TokenStores.create(settings.tokenStore(), multiUser, tokenStoreDir, System::getenv);
        HttpClient http = HttpClient.newHttpClient();
        OAuthSources oauth = OAuthSources.from(settings, tokenStore, http, mapper, multiUser);
        // the OAuth header wins over the embedder's (settings headers < user file headers < OAuth)
        CredentialResolver resolver = oauth.resolver().isEmpty() ? credentials
                : CredentialResolver.chain(credentials, oauth.resolver());
        Identity identity = new Identity(resolver, userPolicies, principalDirectory,
                chatId -> sessions.find(chatId).map(ChatSessionData::principal).orElse(null));
        return new Auth(tokenStore, oauth, identity, multiUser, http);
    }

    /**
     * The logins {@code /login} can run: discovered services, then the REST APIs (not service accounts) and MCP
     * servers with oauth, built from the settings (the same instances as the resolver's).
     */
    static Map<String, OAuthLogin> logins(OAuthSources oauth) {
        Map<String, OAuthLogin> found = new TreeMap<>();
        for (OAuthLogin l : ServiceLoader.load(OAuthLogin.class)) found.put(l.name(), l);
        found.putAll(oauth.logins());
        return found;
    }
}

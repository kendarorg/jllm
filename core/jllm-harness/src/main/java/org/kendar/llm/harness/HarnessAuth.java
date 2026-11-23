package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.kendar.llm.harness.agent.AgentService;
import org.kendar.llm.harness.auth.OAuthLogin;
import org.kendar.llm.harness.auth.OAuthSources;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.integration.ExternalSources;

import java.util.Map;

/**
 * The login and OAuth group of the harness: the logins {@code /login} can run, the token store they write to, and the
 * per-user bookkeeping. Obtained from {@link AgentHarness#auth()}.
 */
public final class HarnessAuth {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HarnessAuth.class);

    private final TokenStore tokenStore;
    private final OAuthSources oauth;
    private final boolean multiUser;
    private final Map<String, OAuthLogin> logins;
    private final ExternalSources sources;
    private final AgentService service;

    HarnessAuth(TokenStore tokenStore, OAuthSources oauth, boolean multiUser, Map<String, OAuthLogin> logins,
                ExternalSources sources, AgentService service) {
        this.tokenStore = tokenStore;
        this.oauth = oauth;
        this.multiUser = multiUser;
        this.logins = logins;
        this.sources = sources;
        this.service = service;
    }

    /** The logins {@code /login} can run: discovered {@link OAuthLogin} services and MCP servers with oauth. */
    public Map<String, OAuthLogin> logins() {
        return logins;
    }

    /** Shared with {@link ExternalSources}: a login and the MCP clients see the same tokens. */
    public TokenStore tokenStore() {
        return tokenStore;
    }

    /** The REST APIs and MCP servers with OAuth: their logins, and which are per user. */
    public OAuthSources oauth() {
        return oauth;
    }

    /** True when the harness serves several users (a user policy resolver was given). */
    public boolean multiUser() {
        return multiUser;
    }

    /**
     * The store key of a login: the principal for a per-user source, else null (the single user, or the login
     * shared by every user).
     */
    public String loginKey(String principalId, String name) {
        return principalId != null && oauth.perUser(name) ? principalId : null;
    }

    /** Stores a login's token and drops what was cached for it. */
    public void saveLogin(String principalId, String name, OAuthToken token) {
        String key = loginKey(principalId, name);
        tokenStore.put(key, name, token);
        oauth.resolver().forget(key, name);
    }

    /**
     * Removes a login: the token, what is cached for it, and the caller's MCP session on that server.
     *
     * @return false when there was no stored login
     */
    public boolean logout(String principalId, String name) {
        String key = loginKey(principalId, name);
        boolean had = tokenStore.remove(key, name);
        oauth.resolver().forget(key, name);
        if (name.startsWith("mcp:") && key != null) sources.closeSession(name.substring(4), key);
        return had;
    }

    /** Tells a chat that a login it started ended: event {@code login {source, ok, message}}. */
    public void loginFinished(String chatId, String source, boolean ok, String message) {
        if (chatId == null) return;
        try {
            service.notify(chatId, "login", JsonNodeFactory.instance.objectNode().put("source", source).put("ok", ok)
                    .put("message", message));
        } catch (RuntimeException e) { // boundary: the chat is gone: nothing to tell
            // the chat is gone: nothing to tell
            log.debug("Login result not delivered to chat {}: {}", chatId, e.toString());
        }
    }

    /** Removes every token of a deleted user and closes their MCP sessions. */
    public void forgetUser(String principalId) {
        if (principalId == null) return;
        tokenStore.removeAll(principalId);
        oauth.resolver().forgetAll(principalId);
        sources.closeSessions(principalId);
    }
}

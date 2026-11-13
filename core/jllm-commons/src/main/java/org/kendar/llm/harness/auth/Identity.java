package org.kendar.llm.harness.auth;

import java.util.Map;
import java.util.function.Function;

/**
 * Who a chat's calls are made for: the principal of the chat, its credentials and its policy.
 * Tools receive the chat id in their context and ask here; a chat without a principal gets the single-user
 * behavior (no extra headers, every tool, the harness approval policy).
 */
public final class Identity {

    /** Single-user behavior: no principal, no extra credentials, every tool. */
    public static final Identity ANONYMOUS = new Identity(CredentialResolver.NONE, null, null, null);

    /** The principal of a call, with the credentials that apply to it. */
    public record Caller(HarnessPrincipal principal, CredentialResolver credentials) {

        public Map<String, String> headers(String source) {
            return credentials == null ? Map.of() : credentials.headers(principal, source);
        }

        /** See {@link CredentialResolver#onUnauthorized}. @return true when one retry is worth it */
        public boolean onUnauthorized(String source) {
            return credentials != null && credentials.onUnauthorized(principal, source);
        }
    }

    private final CredentialResolver credentials;
    private final UserPolicyResolver policies;
    private final Function<String, HarnessPrincipal> directory;
    private final Function<String, String> principalIdOfChat;

    /**
     * @param credentials     per-call headers; null = none
     * @param policies        per-user tools and approval; null = every tool, harness approval
     * @param directory       principal id to principal (roles, attributes); null = id only
     * @param principalIdOfChat chat id to principal id, null when the chat has none; null = no chat has one
     */
    public Identity(CredentialResolver credentials, UserPolicyResolver policies,
                    Function<String, HarnessPrincipal> directory, Function<String, String> principalIdOfChat) {
        this.credentials = credentials == null ? CredentialResolver.NONE : credentials;
        this.policies = policies;
        this.directory = directory;
        this.principalIdOfChat = principalIdOfChat;
    }

    /** The principal of a chat, or null. */
    public HarnessPrincipal principalOf(String chatId) {
        if (principalIdOfChat == null || chatId == null) return null;
        String id = principalIdOfChat.apply(chatId);
        if (id == null) return null;
        HarnessPrincipal p = directory == null ? null : directory.apply(id);
        return p != null ? p : HarnessPrincipal.of(id);
    }

    public Caller callerOf(String chatId) {
        return new Caller(principalOf(chatId), credentials);
    }

    /**
     * The policy of the chat's user. Without a resolver every chat gets {@link UserPolicy#ALL} (single user). With
     * one, the resolver decides, also for a chat without principal (a multi-user embedder maps that to no tools); a
     * resolver that fails or returns null gives {@link UserPolicy#NONE}: never more than it would have allowed.
     */
    public UserPolicy policyOf(String chatId) {
        if (policies == null) return UserPolicy.ALL;
        UserPolicy p;
        try {
            p = policies.resolve(principalOf(chatId));
        } catch (RuntimeException e) {
            return UserPolicy.NONE;
        }
        return p == null ? UserPolicy.NONE : p;
    }
}

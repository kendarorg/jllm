package org.kendar.llm.harness.auth;

/**
 * Picks the tools and approval policy of a user. Called per call; the tool set of a chat stays fixed once chosen,
 * so the prompt cache holds.
 */
@FunctionalInterface
public interface UserPolicyResolver {

    /** The same policy for every user, the principal included or not. */
    static UserPolicyResolver single(UserPolicy policy) {
        return user -> policy;
    }

    /** @param user the chat's principal; null when the chat has none */
    UserPolicy resolve(HarnessPrincipal user);
}

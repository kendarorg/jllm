package org.kendar.llm.harness.auth;

import java.util.Map;
import java.util.Set;

/**
 * The user a chat belongs to. Attached by the embedder when the chat is created; the harness only reads it.
 * The chat stores the id; roles and attributes come from the embedder's directory on each use.
 *
 * @param id    opaque user id
 * @param roles role names; {@link UserPolicyResolver} maps them to a tool set and approval policy
 * @param attrs embedder data, opaque to the harness
 */
public record HarnessPrincipal(String id, Set<String> roles, Map<String, Object> attrs) {

    public HarnessPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    public static HarnessPrincipal of(String id) {
        return new HarnessPrincipal(id, Set.of(), Map.of());
    }
}

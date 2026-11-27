package org.kendar.llm.web.commons.auth;

import java.time.Duration;

/**
 * {@code jllm.auth.oidc.role-mapping.*}: the initial values of the identity provider role mapping. An administrator can
 * change them at runtime ({@link RoleMappingSettings}); the stored value then wins.
 *
 * @param enabled    map the groups of the ID token to a role at every sign-in (default false)
 * @param claim      claim path in the ID token (default {@code groups}); a dotted path walks nested objects
 * @param onNoMatch  {@code default} (default) | {@code keep} | {@code refuse}
 * @param allowAdmin whether a group can grant the {@code admin} role (default false)
 * @param maxAge     an identity provider session older than this must sign in again; null = no limit
 */
public record RoleMappingProperties(Boolean enabled, String claim, String onNoMatch, Boolean allowAdmin,
                                    Duration maxAge) {

    public static final RoleMappingProperties NONE = new RoleMappingProperties(null, null, null, null, null);
}

package org.kendar.llm.harness.identity;

import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.auth.UserPolicyResolver;

import java.util.function.Function;

/** What the harness needs to know about users: their credentials, tool policy, and principal by id. */
public interface HarnessIdentity {

    /** No credentials, no tools, no principals. */
    HarnessIdentity NONE = new HarnessIdentity() {
        @Override
        public CredentialResolver credentials() {
            return CredentialResolver.NONE;
        }

        @Override
        public UserPolicyResolver policies() {
            return UserPolicyResolver.single(UserPolicy.NONE);
        }

        @Override
        public Function<String, HarnessPrincipal> principals() {
            return id -> null;
        }
    };

    CredentialResolver credentials();

    UserPolicyResolver policies();

    /** The sandbox of a user id (that of their role); {@link RoleSandbox#NONE} when unknown. */
    default Function<String, RoleSandbox> sandboxes() {
        return id -> RoleSandbox.NONE;
    }

    /** Whether a user holding these roles may own an unattended flow (a role with {@code unattended: true}). */
    default boolean mayRunUnattended(java.util.Set<String> roles) {
        return false;
    }

    /** Whether any role has the {@code shared-local} sandbox: the host then refuses to start without a working one. */
    default boolean usesSharedLocal() {
        return false;
    }

    /** The principal of a user id; null when unknown. */
    Function<String, HarnessPrincipal> principals();

    /**
     * Directory of the users' OAuth token files when the settings name none ({@code tokenStore.dir}); null = the
     * harness default.
     */
    default java.nio.file.Path tokenDir() {
        return null;
    }

    /**
     * Where the users' OAuth tokens are kept (e.g. a database); null = the store the {@code tokenStore} settings
     * describe, with {@link #tokenDir()} as default directory.
     */
    default org.kendar.llm.harness.auth.TokenStore tokenStore() {
        return null;
    }
}

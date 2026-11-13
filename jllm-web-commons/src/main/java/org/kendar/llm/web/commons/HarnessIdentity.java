package org.kendar.llm.web.commons;

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

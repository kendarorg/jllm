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
}

package org.kendar.llm.web.commons.auth;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * {@code jllm.auth.*}: how users sign in.
 *
 * @param mode {@code password} (default) | {@code oidc} | {@code both}
 * @param oidc the one identity provider; used when the mode is {@code oidc} or {@code both}
 */

public record OidcProperties(String mode, Oidc oidc) {

    public static final String DEFAULT_SCOPES = "openid profile email";

    /**
     * @param name          button label
     * @param issuer        discovery at {@code <issuer>/.well-known/openid-configuration}
     * @param clientSecret  {@code env:NAME} reference
     * @param scopes        space separated
     * @param allowedDomains email domains that may sign in; empty = any verified email
     * @param autoProvision create unknown users with {@code defaultRole}
     */
    public record Oidc(String name, String issuer, String clientId, String clientSecret, String scopes,
                       List<String> allowedDomains, Boolean autoProvision, String defaultRole) {

        public String nameOrDefault() {
            return name == null || name.isBlank() ? "Single sign-on" : name.strip();
        }

        public String scopesOrDefault() {
            return scopes == null || scopes.isBlank() ? DEFAULT_SCOPES : scopes.strip();
        }

        public List<String> domains() {
            return allowedDomains == null ? List.of() : allowedDomains.stream().filter(d -> d != null && !d.isBlank())
                    .map(d -> d.strip().toLowerCase(Locale.ROOT)).toList();
        }

        public boolean provisioning() {
            return Boolean.TRUE.equals(autoProvision);
        }

        public String issuerNormalized() {
            return issuer == null ? null : issuer.strip().replaceAll("/+$", "");
        }
    }

    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? "password" : mode.strip().toLowerCase(Locale.ROOT);
    }

    public boolean passwordEnabled() {
        return !modeOrDefault().equals("oidc");
    }

    public boolean oidcEnabled() {
        return !modeOrDefault().equals("password");
    }

    /**
     * The secret behind {@code oidc.client-secret}: an {@code env:NAME} reference only; null when it is not one or the
     * variable is empty.
     */
    public String secret(UnaryOperator<String> env) {
        String ref = oidc == null ? null : oidc.clientSecret();
        if (ref == null || !ref.startsWith("env:") || ref.length() == 4) return null;
        String v = env.apply(ref.substring(4));
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Startup checks.
     *
     * @throws IllegalStateException naming the key that is wrong
     */
    public void validate(String publicUrl, UnaryOperator<String> env, Predicate<String> roleExists) {
        String m = modeOrDefault();
        if (!m.equals("password") && !m.equals("oidc") && !m.equals("both")) {
            throw new IllegalStateException("jllm.auth.mode must be password, oidc or both, not " + mode);
        }
        if (!oidcEnabled()) return;
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new IllegalStateException("jllm.web.public-url is required when jllm.auth.mode is " + m);
        }
        if (oidc == null || oidc.issuer() == null || oidc.issuer().isBlank()) {
            throw new IllegalStateException("jllm.auth.oidc.issuer is required when jllm.auth.mode is " + m);
        }
        if (oidc.clientId() == null || oidc.clientId().isBlank()) {
            throw new IllegalStateException("jllm.auth.oidc.client-id is required when jllm.auth.mode is " + m);
        }
        if (oidc.clientSecret() != null && !oidc.clientSecret().isBlank() && !oidc.clientSecret().startsWith("env:")) {
            throw new IllegalStateException("jllm.auth.oidc.client-secret must be an env:NAME reference");
        }
        if (secret(env) == null) {
            throw new IllegalStateException("jllm.auth.oidc.client-secret is missing: set it to env:NAME with NAME "
                    + "defined in the environment");
        }
        if (oidc.provisioning()) {
            if (oidc.defaultRole() == null || oidc.defaultRole().isBlank()) {
                throw new IllegalStateException("jllm.auth.oidc.default-role is required when auto-provision is true");
            }
            if (!roleExists.test(oidc.defaultRole())) {
                throw new IllegalStateException("jllm.auth.oidc.default-role names an unknown role: "
                        + oidc.defaultRole());
            }
        }
    }
}

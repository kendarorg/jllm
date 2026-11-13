package org.kendar.llm.web.users;

import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headers from the user's file for each call. {@code env:NAME} values are read from the environment when the call is
 * made, so a rotated secret takes effect without a restart. Literal values are not accepted: secrets stay out of the
 * users directory.
 */
public final class FileCredentialResolver implements CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(FileCredentialResolver.class);

    private final UserDirectory users;

    public FileCredentialResolver(UserDirectory users) {
        this.users = users;
    }

    @Override
    public Map<String, String> headers(HarnessPrincipal user, String source) {
        if (user == null) return Map.of();
        Map<String, String> headers = new LinkedHashMap<>();
        users.find(user.id()).ifPresent(u -> {
            Map<String, String> declared = u.credentials().get(source);
            if (declared == null) return;
            declared.forEach((header, value) -> {
                if (value == null || !value.startsWith("env:")) {
                    log.warn("User '{}': header {} for {} is not an env: reference; ignored", u.id(), header, source);
                    return;
                }
                String secret = System.getenv(value.substring(4));
                if (secret != null && !secret.isBlank()) headers.put(header, secret);
            });
        });
        return headers;
    }
}

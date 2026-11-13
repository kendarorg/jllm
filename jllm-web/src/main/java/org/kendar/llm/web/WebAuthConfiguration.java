package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.web.commons.HarnessIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Sample sign-in and permissions: {@code jllm.webauth-dir} (default {@code webauth}, relative to the start folder).
 * Supplies the authentication and authorization the common web API needs.
 */
@Configuration
public class WebAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAuthConfiguration.class);

    @Bean
    public UserDirectory userDirectory(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper) {
        Path dir = Path.of(webauthDir, "users").toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            log.warn("No users folder at {}: the sign-in picker is empty. Set jllm.webauth-dir.", dir);
        }
        return new UserDirectory(dir, mapper);
    }

    @Bean
    public Permissions permissions(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper) {
        return new Permissions(Path.of(webauthDir, "permissions"), mapper);
    }

    /** Both the {@code WebAuthentication} and the {@code ChatAccess} of the common API. */
    @Bean
    public CookieAuthentication cookieAuthentication(UserDirectory users) {
        return new CookieAuthentication(users);
    }

    @Bean
    public HarnessIdentity harnessIdentity(UserDirectory users, Permissions permissions) {
        CredentialResolver credentials = new FileCredentialResolver(users);
        UserPolicyResolver policies = permissions.resolver();
        Function<String, HarnessPrincipal> principals = id -> users.find(id).map(WebUser::principal).orElse(null);
        return new HarnessIdentity() {
            @Override
            public CredentialResolver credentials() {
                return credentials;
            }

            @Override
            public UserPolicyResolver policies() {
                return policies;
            }

            @Override
            public Function<String, HarnessPrincipal> principals() {
                return principals;
            }
        };
    }
}

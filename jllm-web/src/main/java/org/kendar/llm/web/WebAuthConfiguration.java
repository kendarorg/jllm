package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.auth.CredentialResolver;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicyResolver;
import org.kendar.llm.web.admin.ToolCatalogView;
import org.kendar.llm.web.auth.CookieAuthentication;
import org.kendar.llm.web.auth.LoginThrottle;
import org.kendar.llm.web.auth.SessionStore;
import org.kendar.llm.web.commons.HarnessIdentity;
import org.kendar.llm.web.commons.users.RoleRepository;
import org.kendar.llm.web.commons.users.SkillRepository;
import org.kendar.llm.web.commons.users.UserRepository;
import org.kendar.llm.web.commons.users.WebUser;
import org.kendar.llm.web.users.FileRoleRepository;
import org.kendar.llm.web.users.FileSkillRepository;
import org.kendar.llm.web.users.FileUserRepository;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.users.UserCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Sign-in, users, roles and skills of the reference implementation. With {@code jllm.store=file} (the default) they
 * are kept under {@code jllm.webauth-dir} (default {@code webauth}, relative to the start folder); another store
 * supplies its own {@link UserRepository}, {@link RoleRepository} and {@link SkillRepository} beans. Supplies the
 * authentication and authorization the common web API needs.
 */
@Configuration
public class WebAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAuthConfiguration.class);

    private static Path root(String webauthDir) {
        return Path.of(webauthDir).toAbsolutePath().normalize();
    }

    /** {@code <webauth>/users}: one file per user. Another store replaces it with {@code jllm.store}. */
    @Bean
    @ConditionalOnProperty(name = "jllm.store", havingValue = "file", matchIfMissing = true)
    public UserRepository userRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper) {
        Path dir = root(webauthDir).resolve("users");
        if (!Files.isDirectory(dir)) {
            log.warn("No users folder at {}: nobody can sign in. Set jllm.webauth-dir.", dir);
        }
        return new FileUserRepository(dir, mapper);
    }

    /** {@code <webauth>/permissions}: one file per role. */
    @Bean
    @ConditionalOnProperty(name = "jllm.store", havingValue = "file", matchIfMissing = true)
    public RoleRepository roleRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper) {
        return new FileRoleRepository(root(webauthDir).resolve("permissions"), mapper);
    }

    /** {@code <webauth>/global-skills} and {@code <webauth>/user-skills}. */
    @Bean
    @ConditionalOnProperty(name = "jllm.store", havingValue = "file", matchIfMissing = true)
    public SkillRepository skillRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir) {
        return new FileSkillRepository(root(webauthDir));
    }

    @Bean
    public Permissions permissions(RoleRepository roles) {
        return new Permissions(roles);
    }

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore();
    }

    @Bean
    public LoginThrottle loginThrottle() {
        return new LoginThrottle();
    }

    /** Both the {@code WebAuthentication} and the {@code ChatAccess} of the common API. */
    @Bean
    public CookieAuthentication cookieAuthentication(UserRepository users, SessionStore sessions) {
        return new CookieAuthentication(users, sessions);
    }

    @Bean
    public ToolCatalogView toolCatalogView(AgentHarness harness) {
        return new ToolCatalogView(harness);
    }

    @Bean
    public HarnessIdentity harnessIdentity(UserRepository users, Permissions permissions, SkillRepository skills,
                                           @Value("${jllm.webauth-dir:webauth}") String webauthDir) {
        Path tokens = root(webauthDir).resolve("tokens");
        CredentialResolver credentials = new UserCredentialResolver(users);
        UserPolicyResolver policies = permissions.resolver(users, skills);
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

            /** {@code <webauth>/tokens}: one encrypted file per user. */
            @Override
            public Path tokenDir() {
                return tokens;
            }
        };
    }
}

package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.auth.InMemoryLoginSessionRepository;
import org.kendar.llm.web.users.FileRoleRepository;
import org.kendar.llm.web.users.FileSkillRepository;
import org.kendar.llm.web.users.FileUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kendar.llm.harness.auth.TokenKeyGuard;
import org.kendar.llm.harness.auth.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

/**
 * The file stores of users, roles and skills (and in-memory sign-in sessions): the fallback when no other store bean
 * exists. Ordered after the SQL starter, so with it on the class path and {@code jllm.store} not {@code file}
 * its beans win. {@code jllm.store} is {@code file} or {@code sql}; anything else stops the start.
 */
@AutoConfiguration(afterName = "org.kendar.llm.web.sql.spring.JllmSqlAutoConfiguration")
public class FileStoresAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FileStoresAutoConfiguration.class);
    static final String SQL_STARTER = "org.kendar.llm.web.sql.spring.JllmSqlAutoConfiguration";

    /** Checks {@code jllm.store}: always created, so a wrong value fails the start whatever beans exist. */
    @Bean
    public static StoreSetting storeSetting(Environment env, ResourceLoader resources) {
        String v = env.getProperty("jllm.store");
        String store = v == null || v.isBlank() ? null : v.strip();
        if (store != null && !Set.of("file", "sql").contains(store)) {
            throw new IllegalStateException("jllm.store: '" + store + "' is not valid, use 'file' or 'sql'");
        }
        if ("sql".equals(store) && !ClassUtils.isPresent(SQL_STARTER, resources.getClassLoader())) {
            throw new IllegalStateException("jllm.store=sql but jllm-sql-spring-starter is not on the class path "
                    + "(release: lib-ext/sql; Maven: add the dependency)");
        }
        return new StoreSetting(store);
    }

    /** The validated value of {@code jllm.store}; null = not set. */
    public record StoreSetting(String value) {
    }

    /**
     * The token key check of the file token store (the SQL starter does the same for its own): created only when no
     * {@code TokenStore} bean replaces the files. Without {@code JLLM_TOKEN_KEY}: a warning, or no start when exposed.
     */
    @Bean
    @ConditionalOnMissingBean(TokenStore.class)
    public static TokenKeyCheck tokenKeyCheck(Environment env,
                                              @Qualifier("jllmSqlEnv") ObjectProvider<Function<String, String>> envVars) {
        boolean encrypted = TokenKeyGuard.check(envVars.getIfAvailable(() -> System::getenv), env::getProperty);
        return new TokenKeyCheck(encrypted);
    }

    /** Outcome of the token key check; {@code encrypted} when a key is set. */
    public record TokenKeyCheck(boolean encrypted) {
    }

    /** {@code <webauth>/users}: one file per user. */
    @Bean
    @ConditionalOnMissingBean(UserRepository.class)
    public UserRepository userRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper,
                                         RoleRepository roles) {
        Path dir = root(webauthDir).resolve("users");
        if (!Files.isDirectory(dir)) {
            log.warn("No users folder at {}: nobody can sign in. Set jllm.webauth-dir.", dir);
        }
        return new FileUserRepository(dir, mapper, roles);
    }

    /** {@code <webauth>/permissions}: one file per role. */
    @Bean
    @ConditionalOnMissingBean(RoleRepository.class)
    public RoleRepository roleRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir, ObjectMapper mapper) {
        return new FileRoleRepository(root(webauthDir).resolve("permissions"), mapper);
    }

    /** {@code <webauth>/global-skills} and {@code <webauth>/user-skills}. */
    @Bean
    @ConditionalOnMissingBean(SkillRepository.class)
    public SkillRepository skillRepository(@Value("${jllm.webauth-dir:webauth}") String webauthDir) {
        return new FileSkillRepository(root(webauthDir));
    }

    /** Sign-in sessions in memory. */
    @Bean
    @ConditionalOnMissingBean(LoginSessionRepository.class)
    public LoginSessionRepository loginSessionRepository() {
        return new InMemoryLoginSessionRepository();
    }

    private static Path root(String webauthDir) {
        return Path.of(webauthDir).toAbsolutePath().normalize();
    }
}

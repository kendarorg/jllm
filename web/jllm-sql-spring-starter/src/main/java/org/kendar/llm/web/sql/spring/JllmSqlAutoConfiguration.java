package org.kendar.llm.web.sql.spring;

import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.auth.TokenKeyGuard;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.web.sql.SqlConfig;
import org.kendar.llm.web.sql.SqlStores;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * With this starter on the class path (and {@code jllm.store} not {@code file}) every store of the web application is
 * kept in SQL: SQLite by default, MySQL or PostgreSQL with {@code jllm.sql.url}
 * ({@code jdbc:mysql:} / {@code jdbc:postgresql:}; their drivers are fetched with {@code fetch-sql-driver}). Runs before the file stores of {@code jllm-web},
 * which only apply when no store bean exists.
 * <p>
 * Settings: {@code jllm.sql.url}, {@code jllm.sql.user}, {@code jllm.sql.password}, {@code jllm.sql.pool},
 * {@code jllm.sql.seed} (default true: an empty database gets the users admin, poweru and guest). Without a url the
 * SQLite file is {@code jllm.db} in {@code jllm.webauth-dir} when that is set, else in {@code data/}. Tokens are
 * encrypted with {@code JLLM_TOKEN_KEY} when set.
 */
@AutoConfiguration(beforeName = "org.kendar.llm.web.FileStoresAutoConfiguration")
@ConditionalOnProperty(name = "jllm.store", havingValue = "sql", matchIfMissing = true)
public class JllmSqlAutoConfiguration {

    /** Where the environment variables ({@code JLLM_TOKEN_KEY}) are read from; tests supply their own. */
    @Bean
    @ConditionalOnMissingBean(name = "jllmSqlEnv")
    public Function<String, String> jllmSqlEnv() {
        return System::getenv;
    }

    /** The database; schema created and, when empty, seeded. Closed with the context. */
    @Bean(destroyMethod = "close")
    public SqlStores sqlStores(Environment env, Function<String, String> jllmSqlEnv) {
        String webauth = env.getProperty("jllm.webauth-dir");
        Path dir = Path.of(webauth == null || webauth.isBlank() ? "data" : webauth).toAbsolutePath().normalize();
        SqlConfig config = SqlConfig.fromProperties(env::getProperty, dir);
        if (config.dialect() == org.kendar.llm.web.sql.Dialect.SQLITE) {
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Cannot create " + dir, e);
            }
        }
        TokenKeyGuard.check(jllmSqlEnv, env::getProperty); // no key: warn, or refuse when exposed
        TokenCipher cipher = TokenCipher.fromEnv(jllmSqlEnv); // null = tokens in clear; invalid key = startup fails
        SqlStores stores = SqlStores.open(config, cipher);
        try {
            if (env.getProperty("jllm.sql.seed", Boolean.class, true)) SqlSeeder.seed(stores.users(), stores.roles());
        } catch (RuntimeException e) {
            stores.close();
            throw e;
        }
        return stores;
    }

    @Bean
    public UserRepository sqlUserRepository(SqlStores s) {
        return s.users();
    }

    @Bean
    public RoleRepository sqlRoleRepository(SqlStores s) {
        return s.roles();
    }

    @Bean
    public SkillRepository sqlSkillRepository(SqlStores s) {
        return s.skills();
    }

    @Bean
    public LoginSessionRepository sqlLoginSessionRepository(SqlStores s) {
        return s.sessions();
    }

    @Bean
    public TokenStore sqlTokenStore(SqlStores s) {
        return s.tokens();
    }

    @Bean
    public SettingsSource sqlSettingsSource(SqlStores s) {
        return s.settings();
    }

    @Bean
    public ProjectMcpTrust sqlProjectMcpTrust(SqlStores s) {
        return s.mcpTrust();
    }

    @Bean
    public ChatSessionStore sqlChatSessionStore(SqlStores s) {
        return s.chats();
    }

    @Bean
    public StateStore sqlStateStore(SqlStores s) {
        return s.state();
    }

    @Bean
    public MnemonicStore sqlMnemonicStore(SqlStores s) {
        return s.mnemonic();
    }

    @Bean
    public SubAgentRunLog sqlSubAgentRunLog(SqlStores s) {
        return s.subAgentRuns();
    }

    @Bean
    public PluginStorages sqlPluginStorages(SqlStores s) {
        return s.pluginStorages();
    }
}

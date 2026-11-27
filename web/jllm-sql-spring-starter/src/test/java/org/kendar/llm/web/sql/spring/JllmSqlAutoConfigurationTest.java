package org.kendar.llm.web.sql.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.settings.SettingsSource;
import org.kendar.llm.harness.state.StateStore;
import org.kendar.llm.web.sql.SqlChatStores;
import org.kendar.llm.web.sql.SqlStores;
import org.kendar.llm.web.sql.SqlTokenStore;
import org.kendar.llm.web.sql.SqlUserRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class JllmSqlAutoConfigurationTest {

    private static final Class<?>[] TYPES = {UserRepository.class, RoleRepository.class, SkillRepository.class,
            LoginSessionRepository.class, TokenStore.class, SettingsSource.class,
            org.kendar.llm.harness.settings.SettingsSink.class, ProjectMcpTrust.class,
            ChatSessionStore.class, StateStore.class, MnemonicStore.class, SubAgentRunLog.class,
            org.kendar.llm.harness.plugin.PluginStorages.class};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JllmSqlAutoConfiguration.class))
            .withPropertyValues("jllm.web.allow-unencrypted-tokens=true"); // no key and no server.address here

    private static String url(Path dir) {
        return "jllm.sql.url=jdbc:sqlite:" + dir.resolve("x.db").toString().replace(File.separatorChar, '/');
    }

    private static ApplicationContextRunner env(ApplicationContextRunner r, Map<String, String> env) {
        return r.withBean("jllmSqlEnv", Function.class, () -> (Function<String, String>) env::get);
    }

    @Test
    void defaultGivesSqlForEveryStoreType(@TempDir Path dir) {
        env(runner, Map.of()).withPropertyValues("jllm.webauth-dir=" + dir.toString().replace(File.separatorChar, '/')).run(c -> {
            assertNull(c.getStartupFailure());
            for (Class<?> t : TYPES) assertEquals(1, c.getBeansOfType(t).size(), t.getSimpleName());
            assertInstanceOf(SqlUserRepository.class, c.getBean(UserRepository.class));
            assertInstanceOf(SqlChatStores.Sessions.class, c.getBean(ChatSessionStore.class));
            assertTrue(Files.isRegularFile(dir.resolve("jllm.db")), "default file under jllm.webauth-dir");
        });
    }

    @Test
    void noKeyOnAnExposedServerRefusesToStartUnlessOverridden(@TempDir Path dir) {
        var base = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JllmSqlAutoConfiguration.class));
        env(base, Map.of()).withPropertyValues(url(dir)).run(c -> { // server.address unset
            Throwable t = c.getStartupFailure();
            assertNotNull(t);
            while (t.getCause() != null) t = t.getCause();
            assertTrue(t.getMessage().contains("JLLM_TOKEN_KEY"), t.getMessage());
        });
        env(base, Map.of()).withPropertyValues(url(dir), "server.address=127.0.0.1", "jllm.web.use-real-password=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
        env(base, Map.of()).withPropertyValues(url(dir), "server.address=0.0.0.0")
                .run(c -> assertNotNull(c.getStartupFailure()));
        env(base, Map.of()).withPropertyValues(url(dir), "server.address=::")
                .run(c -> assertNotNull(c.getStartupFailure()));
        env(base, Map.of()).withPropertyValues(url(dir), "server.address=127.0.0.1")
                .run(c -> assertNull(c.getStartupFailure()));
        env(base, Map.of()).withPropertyValues(url(dir), "jllm.web.use-real-password=true",
                "jllm.web.allow-unencrypted-tokens=true").run(c -> assertNull(c.getStartupFailure()));
        String key = TokenCipher.newKey();
        env(base, Map.of("JLLM_TOKEN_KEY", key)).withPropertyValues(url(dir), "jllm.web.use-real-password=true")
                .run(c -> assertNull(c.getStartupFailure()));
    }

    @Test
    void fileStoreSwitchesItOff() {
        runner.withPropertyValues("jllm.store=file").run(c -> {
            for (Class<?> t : TYPES) assertEquals(0, c.getBeansOfType(t).size(), t.getSimpleName());
        });
    }

    @Test
    void urlIsHonoredAndSeededOnce(@TempDir Path dir) {
        env(runner, Map.of()).withPropertyValues(url(dir)).run(c ->
                assertEquals(3, c.getBean(UserRepository.class).all().size()));
        assertTrue(Files.isRegularFile(dir.resolve("x.db")));
        env(runner, Map.of()).withPropertyValues(url(dir)).run(c -> {
            assertEquals(3, c.getBean(UserRepository.class).all().size(), "not seeded again");
            assertEquals(3, c.getBean(RoleRepository.class).all().size());
        });
    }

    @Test
    void seedCanBeSwitchedOff(@TempDir Path dir) {
        env(runner, Map.of()).withPropertyValues(url(dir), "jllm.sql.seed=false").run(c ->
                assertTrue(c.getBean(UserRepository.class).all().isEmpty()));
    }

    @Test
    void unknownUrlSchemeFailsTheStart(@TempDir Path dir) {
        env(runner, Map.of()).withPropertyValues("jllm.sql.url=jdbc:oracle:thin:@h").run(c -> {
            assertNotNull(c.getStartupFailure());
        });
    }

    @Test
    void invalidTokenKeyFailsTheStart(@TempDir Path dir) {
        env(runner, Map.of("JLLM_TOKEN_KEY", "not-a-key")).withPropertyValues(url(dir)).run(c ->
                assertNotNull(c.getStartupFailure()));
    }

    @Test
    void tokenRoundTripWithAndWithoutCipher(@TempDir Path dir) throws java.io.IOException {
        for (Map<String, String> e : new Map[]{Map.of(), Map.of("JLLM_TOKEN_KEY", TokenCipher.newKey())}) {
            Path d = Files.createDirectories(dir.resolve(e.isEmpty() ? "clear" : "enc"));
            env(runner, e).withPropertyValues(url(d)).run(c -> {
                assertNull(c.getStartupFailure());
                assertInstanceOf(SqlTokenStore.class, c.getBean(TokenStore.class));
            });
        }
    }

    @Test
    void closedWithTheContext(@TempDir Path dir) {
        SqlStores[] holder = new SqlStores[1];
        env(runner, Map.of()).withPropertyValues(url(dir)).run(c -> holder[0] = c.getBean(SqlStores.class));
        assertThrows(RuntimeException.class, () -> holder[0].users().all());
    }
}

package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.sql.spring.JllmSqlAutoConfiguration;
import org.kendar.llm.web.users.FileUserRepository;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStoresAutoConfigurationTest {

    @TempDir
    Path dir;

    private static String slashes(Path p) {
        return p.toString().replace(File.separatorChar, '/');
    }

    private ApplicationContextRunner runner(Class<?>... configs) {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("jllm.webauth-dir=" + slashes(dir))
                .withConfiguration(AutoConfigurations.of(configs));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    @Test
    void fileBeansWithoutAnyOtherStore() {
        runner(FileStoresAutoConfiguration.class).withPropertyValues("jllm.store=file").run(c -> {
            assertNull(c.getStartupFailure());
            assertInstanceOf(FileUserRepository.class, c.getBean(UserRepository.class));
            assertEquals(1, c.getBeansOfType(RoleRepository.class).size());
            assertEquals(1, c.getBeansOfType(SkillRepository.class).size());
            assertEquals(1, c.getBeansOfType(LoginSessionRepository.class).size());
        });
    }

    @Test
    void anApplicationUserRepositoryReplacesTheFileOne() {
        UserRepository own = Mockito.mock(UserRepository.class);
        runner(FileStoresAutoConfiguration.class).withBean(UserRepository.class, () -> own).run(c ->
                assertSame(own, c.getBean(UserRepository.class)));
    }

    @Test
    void storeSqlWithoutTheStarterFails() {
        runner(FileStoresAutoConfiguration.class).withPropertyValues("jllm.store=sql")
                .withClassLoader(new FilteredClassLoader("org.kendar.llm.web.sql.spring")).run(c -> {
                    assertNotNull(c.getStartupFailure());
                    assertTrue(rootMessage(c.getStartupFailure()).contains("jllm.store=sql"), rootMessage(c.getStartupFailure()));
                });
    }

    @Test
    void anUnknownStoreNamesTheKey() {
        runner(FileStoresAutoConfiguration.class).withPropertyValues("jllm.store=redis").run(c -> {
            assertNotNull(c.getStartupFailure());
            String m = rootMessage(c.getStartupFailure());
            assertTrue(m.contains("jllm.store") && m.contains("redis"), m);
        });
    }

    @Test
    void sqlStarterWinsOverTheFileStoresAndFileSettingTurnsItOff() {
        runner(JllmSqlAutoConfiguration.class, FileStoresAutoConfiguration.class)
                .withPropertyValues("jllm.sql.url=jdbc:sqlite:" + slashes(dir.resolve("x.db")))
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    assertInstanceOf(org.kendar.llm.web.sql.SqlUserRepository.class, c.getBean(UserRepository.class));
                    assertEquals(1, c.getBeansOfType(UserRepository.class).size());
                });
        runner(JllmSqlAutoConfiguration.class, FileStoresAutoConfiguration.class).withPropertyValues("jllm.store=file")
                .run(c -> assertInstanceOf(FileUserRepository.class, c.getBean(UserRepository.class)));
    }
}

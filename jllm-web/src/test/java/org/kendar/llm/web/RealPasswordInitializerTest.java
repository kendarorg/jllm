package org.kendar.llm.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RealPasswordInitializerTest {

    @TempDir
    Path dir;

    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(RealPasswordInitializer.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private static JllmProperties props(Boolean useRealPassword) {
        return new JllmProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, new JllmProperties.Web(null, useRealPassword, null, null));
    }

    private String logged() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void flagOffChangesNothing() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        new RealPasswordInitializer(f.users, props(null)).run(null);
        new RealPasswordInitializer(f.users, props(false)).run(null);
        for (String id : List.of("admin", "rita", "gus")) assertTrue(f.users.find(id).orElseThrow().usesDefaultPassword());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void flagOnReplacesDefaultsAndLogsOnlyTheAdminPassword() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        String own = PasswordHasher.hash("my-own-password");
        f.users.create("pam", "Pam", "reader", own, null);
        f.users.createExternal("ext", "Ext", "reader", null, "https://idp#1");

        new RealPasswordInitializer(f.users, props(true)).run(null);

        User admin = f.users.find("admin").orElseThrow();
        assertFalse(admin.usesDefaultPassword());
        String log = logged();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("administrator 'admin': ([A-Za-z0-9]+)").matcher(log);
        assertTrue(m.find(), log);
        String adminPassword = m.group(1);
        assertEquals(RealPasswordInitializer.LENGTH, adminPassword.length());
        assertTrue(PasswordHasher.verify("admin", adminPassword, admin.password()));
        assertFalse(PasswordHasher.verify("admin", "admin", admin.password()));

        assertFalse(f.users.find("rita").orElseThrow().usesDefaultPassword());
        assertFalse(f.users.find("gus").orElseThrow().usesDefaultPassword());
        assertTrue(log.contains("'rita'") && log.contains("'gus'"), log);
        assertEquals(1, log.lines().filter(l -> l.contains(adminPassword)).count());
        assertEquals(3, logs.list.size(), "admin, rita, gus only");
        for (String id : List.of("rita", "gus")) {
            assertFalse(PasswordHasher.verify(id, id, f.users.find(id).orElseThrow().password()));
        }

        assertEquals(own, f.users.find("pam").orElseThrow().password(), "already set: untouched");
        User ext = f.users.find("ext").orElseThrow();
        assertTrue(ext.passwordless());
        assertNull(ext.password(), "external: skipped");
    }

    @Test
    void secondRunDoesNotRegenerate() throws Exception {
        WebauthFixture f = new WebauthFixture(dir);
        RealPasswordInitializer r = new RealPasswordInitializer(f.users, props(true));
        r.run(null);
        String hash = f.users.find("admin").orElseThrow().password();
        logs.list.clear();

        r.run(null);

        assertEquals(hash, f.users.find("admin").orElseThrow().password());
        assertTrue(logs.list.isEmpty());
    }
}

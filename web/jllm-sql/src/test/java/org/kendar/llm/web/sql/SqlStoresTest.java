package org.kendar.llm.web.sql;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.session.ChatSessionData;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The shared store checks on SQLite (a file per test), plus what only SQLite has. */
class SqlStoresTest extends AbstractSqlStoresTest {

    @Override
    SqlConfig config() {
        return SqlConfig.sqliteFile(tmp.resolve("t.db"));
    }

    @Override
    boolean indexExists(String name) {
        return s.database().tx(tx -> tx.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
                rs -> rs.getString(1), name).size()) == 1;
    }

    @Test
    void memoryDatabase() {
        try (SqlStores m = SqlStores.open(SqlConfig.sqliteMemory(), null)) {
            saveRoles(m, "dev");
            m.users().create("a", null, "dev", null, null);
            assertEquals("a", m.users().find("a").orElseThrow().name());
        }
    }

    @Test
    void foreignKeysAreSwitchedOn() {
        int on = s.database().tx(tx -> tx.one("PRAGMA foreign_keys", rs -> rs.getInt(1)).orElse(0));
        assertEquals(1, on);
    }

    /** Measures the "messages TEXT per chat, rewritten per save" design: a 5k-message chat saved 100 times (logged, not asserted tightly). */
    @Test
    void largeChatSaveTiming() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("{\"role\":\"user\",\"content\":\"message number ").append(i).append(" with some text\"}\n");
        var chats = s.chats();
        var chat = ChatSessionData.create("big", "agent", "Big", "bob").withMessagesJson(sb.toString());
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) chats.save(chat);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("SqlStoresTest: 5000-message chat saved 100 times in " + ms + " ms (" + ms / 100.0 + " ms per save)");
        assertEquals(sb.length(), chats.find("big").orElseThrow().messagesJson().length());
        assertTrue(ms < 60_000, "100 saves of a 5k-message chat took " + ms + " ms");
    }

    @Test
    void configFromProperties() {
        SqlConfig d = SqlConfig.fromProperties(k -> null, tmp);
        assertEquals(Dialect.SQLITE, d.dialect());
        assertTrue(d.jdbcUrl().endsWith("/" + SqlConfig.DEFAULT_FILE));
        Map<String, String> p = Map.of(SqlConfig.URL, "jdbc:mysql://h:3306/db", SqlConfig.USER, "u",
                SqlConfig.PASSWORD, "secret");
        SqlConfig m = SqlConfig.fromProperties(p::get, tmp);
        assertEquals(Dialect.MYSQL, m.dialect());
        assertEquals(10, m.maxPoolSize());
        assertFalse(m.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlConfig("jdbc:h2:mem:x", null, null, 1, null));
    }
}

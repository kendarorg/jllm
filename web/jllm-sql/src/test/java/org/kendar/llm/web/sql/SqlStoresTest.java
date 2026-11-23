package org.kendar.llm.web.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.OAuthToken;
import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.context.mnemonic.MnemonicState;
import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleInUseException;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UnknownRoleException;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.permission.ToolPermission;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.ChatVisibility;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.settings.SettingsLayer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class SqlStoresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;
    SqlStores s;

    @BeforeEach
    void open() {
        s = SqlStores.open(SqlConfig.sqliteFile(tmp.resolve("t.db")), null);
    }

    @AfterEach
    void close() {
        s.close();
    }

    @Test
    void migrationIsIdempotentAndSurvivesReopen() {
        assertEquals(1, s.database().schemaVersion());
        saveRoles(s, "dev");
        s.users().create("bob", "Bob", "dev", null, null);
        s.close();
        s = SqlStores.open(SqlConfig.sqliteFile(tmp.resolve("t.db")), null);
        assertEquals(1, s.database().schemaVersion());
        assertTrue(s.users().exists("bob"));
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
    void usersCrudAndRules() {
        var u = s.users();
        saveRoles(s, "dev", "ops");
        assertThrows(IllegalArgumentException.class, () -> u.create("Bad Id", "x", "dev", null, null));
        User bob = u.create("bob", " ", "dev", "pbkdf2$x", "  Bob@Example.com ");
        assertEquals("bob", bob.name());
        assertEquals("Bob@Example.com", bob.email());
        assertEquals("pbkdf2$x", bob.password());
        assertThrows(IllegalStateException.class, () -> u.create("bob", "x", "dev", null, null));
        assertEquals(List.of("bob"), u.findByEmail("bob@example.COM").stream().map(User::id).toList());
        assertTrue(u.findByEmail(" ").isEmpty());

        assertThrows(NoSuchElementException.class, () -> u.setRole("nobody", "dev"));
        assertEquals("ops", u.setRole("bob", "ops").role());
        assertEquals("Robert", u.setName("bob", " Robert ").name());
        assertEquals("bob", u.setName("bob", "").name());
        assertNull(u.setPassword("bob", null).password());
        assertNull(u.setEmail("bob", null).email());

        Map<String, Map<String, String>> creds = new LinkedHashMap<>();
        creds.put("mcp:docs", Map.of("Authorization", "env:DOCS_TOKEN"));
        creds.put("api:empty", Map.of());
        assertEquals(creds, u.setCredentials("bob", creds).credentials());
        assertThrows(IllegalArgumentException.class,
                () -> u.setCredentials("bob", Map.of("mcp:x", Map.of("h", "secret"))));
        assertEquals(creds, u.find("bob").orElseThrow().credentials());

        assertEquals(List.of("z*", "a*"), u.setDisabledTools("bob", List.of("z*", "a*")).disabledTools());
        assertEquals(List.of("s1"), u.setDisabledSkills("bob", List.of("s1")).disabledSkills());

        assertTrue(u.delete("bob"));
        assertFalse(u.delete("bob"));
        assertTrue(u.find("bob").isEmpty());
        assertTrue(s.database().tx(tx -> tx.exists("SELECT 1 FROM user_credentials")) == false);
    }

    @Test
    void externalUsers() {
        var u = s.users();
        saveRoles(s, "dev");
        User x = u.createExternal("ext", "Ext", "dev", "e@x.org", "https://idp#123");
        assertTrue(x.external());
        assertTrue(x.passwordless());
        assertEquals("ext", u.findByExternal("https://idp", "123").orElseThrow().id());
        assertTrue(u.findByExternal("https://idp", "999").isEmpty());
        assertNull(u.setExternalId("ext", null).externalId());
        assertThrows(IllegalArgumentException.class, () -> u.createExternal("e2", "n", "dev", null, " "));
        assertEquals(List.of("ext"), u.all().stream().map(User::id).toList());
    }

    @Test
    void roles() {
        var r = s.roles();
        Map<String, ToolPermission> perms = new LinkedHashMap<>();
        perms.put("send_mail", ToolPermission.ALLOW);
        perms.put("bash", ToolPermission.DENY);
        r.save(new Role("dev", List.of("read_file", "mcp__docs__*"), true, "off", perms));
        Role got = r.find("dev").orElseThrow();
        assertEquals(List.of("read_file", "mcp__docs__*"), got.tools());
        assertTrue(got.readOnly());
        assertEquals("off", got.approval());
        assertEquals(perms, got.toolPermissions());
        assertEquals(List.of("send_mail", "bash"), List.copyOf(got.toolPermissions().keySet()));

        r.save(new Role("dev", List.of("*"), false, "ask", Map.of()));
        got = r.find("dev").orElseThrow();
        assertEquals(List.of("*"), got.tools());
        assertTrue(got.toolPermissions().isEmpty());
        r.save(new Role("a", List.of(), false, "ask", Map.of()));
        assertEquals(List.of("a", "admin", "dev"), r.all().stream().map(Role::name).toList());
        assertTrue(r.find("Bad Name").isEmpty());
        assertTrue(r.delete("dev"));
        assertFalse(r.delete("dev"));
        assertTrue(r.find("dev").isEmpty());
    }

    private static void saveRoles(SqlStores st, String... names) {
        for (String n : names) st.roles().save(new Role(n, List.of(), false, "ask", Map.of()));
    }

    @Test
    void adminRoleIsSeededAndProtected() {
        Role admin = s.roles().find(User.ADMIN_ROLE).orElseThrow();
        assertEquals(List.of("*"), admin.tools());
        assertEquals("ask", admin.approval());
        assertEquals("admin", s.users().create("root", "Root", User.ADMIN_ROLE, null, null).role());
        assertThrows(IllegalArgumentException.class, () -> s.roles().delete(User.ADMIN_ROLE));
        assertTrue(s.roles().find(User.ADMIN_ROLE).isPresent());
    }

    @Test
    void usersRoleIsAForeignKey() {
        saveRoles(s, "dev", "ops");
        assertThrows(UnknownRoleException.class, () -> s.users().create("u", "U", "ghost", null, null));
        assertThrows(UnknownRoleException.class, () -> s.users().create("u", "U", null, null, null));
        assertThrows(UnknownRoleException.class, () -> s.users().createExternal("u", "U", "ghost", null, "i#1"));
        s.users().create("u", "U", "dev", null, null);
        assertThrows(UnknownRoleException.class, () -> s.users().setRole("u", "ghost"));
        assertEquals("dev", s.users().find("u").orElseThrow().role());
        // the database itself refuses a dangling role, whatever the code does
        assertThrows(RuntimeException.class, () -> s.database().run(tx ->
                tx.update("UPDATE users SET role = 'ghost' WHERE id = 'u'")));
        assertThrows(RuntimeException.class, () -> s.database().run(tx ->
                tx.update("INSERT INTO users (id, name, role) VALUES ('v', 'V', 'ghost')")));
        assertTrue(s.database().tx(tx -> tx.one("PRAGMA foreign_keys", rs -> rs.getInt(1)).orElse(0)) == 1);
    }

    @Test
    void roleInUseCannotBeDeleted() {
        saveRoles(s, "dev", "ops");
        s.users().create("bob", "Bob", "dev", null, null);
        s.users().create("amy", "Amy", "dev", null, null);
        RoleInUseException e = assertThrows(RoleInUseException.class, () -> s.roles().delete("dev"));
        assertEquals(List.of("amy", "bob"), e.userIds());
        assertTrue(e.getMessage().contains("amy") && e.getMessage().contains("bob"), e.getMessage());
        assertTrue(s.roles().find("dev").isPresent());
        // the database restricts it too, bypassing the store
        assertThrows(RuntimeException.class, () -> s.database().run(tx -> tx.update("DELETE FROM roles WHERE name = 'dev'")));
        s.users().setRole("bob", "ops");
        s.users().delete("amy");
        assertTrue(s.roles().delete("dev"));
        assertThrows(RoleInUseException.class, () -> s.roles().delete("ops"));
    }

    @Test
    void skills() {
        var k = s.skills();
        k.writeGlobal("g1", "line1\nline2", "body");
        k.writeOwn("bob", "mine", "d", "c");
        k.writeOwn("amy", "mine", "d2", "c2");
        assertEquals("line1 line2", k.findGlobal("g1").orElseThrow().description());
        assertEquals(List.of("g1"), k.global().stream().map(SkillRepository.Skill::name).toList());
        assertEquals(List.of("mine"), k.own("bob").stream().map(SkillRepository.Skill::name).toList());
        assertEquals(List.of("amy", "bob"), k.ownersOf("mine"));
        assertTrue(k.ownersOf("g1").isEmpty());
        k.writeOwn("bob", "mine", "d3", "c3"); // replaces
        assertEquals("c3", k.findOwn("bob", "mine").orElseThrow().content());
        assertThrows(IllegalArgumentException.class, () -> k.writeGlobal("Bad", "", ""));
        assertThrows(SkillRepository.SkillTooLargeException.class,
                () -> k.writeGlobal("big", "", "x".repeat(SkillRepository.MAX_BYTES + 1)));
        assertTrue(k.deleteOwn("amy", "mine"));
        assertFalse(k.deleteOwn("amy", "mine"));
        k.deleteAllOwn("bob");
        assertTrue(k.own("bob").isEmpty());
        assertTrue(k.deleteGlobal("g1"));
    }

    @Test
    void loginSessionsSlideExpireAndRevoke() {
        var clock = new MutableClock();
        var r = new SqlLoginSessionRepository(s.database(), clock);
        String t1 = r.create("bob");
        String t2 = r.create("bob");
        assertNotEquals(t1, t2);
        assertEquals("bob", r.find(t1).orElseThrow());
        assertTrue(r.find("nope").isEmpty());
        assertTrue(r.find(null).isEmpty());
        // only a hash is stored
        assertFalse(s.database().tx(tx -> tx.exists("SELECT 1 FROM login_sessions WHERE token_hash = ?", t1)).booleanValue());

        clock.advance(Duration.ofDays(6));
        assertEquals("bob", r.find(t1).orElseThrow()); // slides
        clock.advance(Duration.ofDays(6));
        assertEquals("bob", r.find(t1).orElseThrow());
        assertTrue(r.find(t2).isEmpty()); // expired
        r.revokeUser("bob", t1);
        assertTrue(r.find(t1).isPresent());
        r.revokeUser("bob", null);
        assertTrue(r.find(t1).isEmpty());
        String t3 = r.create("amy");
        r.revoke(t3);
        assertTrue(r.find(t3).isEmpty());
    }

    @Test
    void tokensPlainAndSealed() {
        OAuthToken t = new OAuthToken("acc", "ref", 123, Map.of("scope", "x"));
        var store = s.tokens();
        assertTrue(store.get(null, "api:a").isEmpty());
        store.put(null, "api:a", t);
        store.put("bob", "mcp:b", t);
        assertEquals(t, store.get(null, "api:a").orElseThrow());
        assertEquals(List.of("mcp:b"), store.names("bob"));
        assertTrue(store.crossProcess());

        // plain rows become sealed when a cipher is set, on read
        TokenCipher c = new TokenCipher(new byte[32], null);
        var sealed = new SqlTokenStore(s.database(), c);
        assertEquals(t, sealed.get("bob", "mcp:b").orElseThrow());
        String payload = s.database().tx(tx -> tx.one("SELECT payload FROM oauth_tokens WHERE principal_id = ?",
                rs -> rs.getString(1), "bob").orElseThrow());
        assertTrue(payload.contains("sealed"));
        assertFalse(payload.contains("acc"));
        assertEquals(t, sealed.get("bob", "mcp:b").orElseThrow());
        // sealed without a key: unreadable, not an exception
        assertTrue(store.get("bob", "mcp:b").isEmpty());

        assertTrue(store.remove("bob", "mcp:b"));
        assertFalse(store.remove("bob", "mcp:b"));
        store.removeAll(null);
        assertTrue(store.names(null).isEmpty());
    }

    @Test
    void tokenCompareAndPut() {
        var store = s.tokens();
        OAuthToken a = new OAuthToken("a", "r1", 1, Map.of());
        OAuthToken b = new OAuthToken("b", "r2", 2, Map.of());
        OAuthToken c = new OAuthToken("c", "r3", 3, Map.of());
        assertFalse(store.compareAndPut("u", "n", a, b)); // nothing stored
        assertTrue(store.compareAndPut("u", "n", null, a));
        assertFalse(store.compareAndPut("u", "n", null, b)); // exists
        assertTrue(store.compareAndPut("u", "n", a, b));
        assertFalse(store.compareAndPut("u", "n", a, c)); // stale
        assertEquals(b, store.get("u", "n").orElseThrow());
        assertTrue(store.compareAndPut("u", "n", b, null));
        assertTrue(store.get("u", "n").isEmpty());
    }

    @Test
    void settingsLayersInPrecedenceOrder() throws Exception {
        var src = s.settings();
        assertTrue(src.layers().isEmpty());
        src.put(SettingsLayer.Kind.EXPLICIT, null, MAPPER.readTree("{\"e\":1}"));
        src.put(SettingsLayer.Kind.GLOBAL, "sql:g", MAPPER.readTree("{\"g\":1}"));
        src.put(SettingsLayer.Kind.GLOBAL, "sql:g", MAPPER.readTree("{\"g\":2}"));
        List<SettingsLayer> l = src.layers();
        assertEquals(List.of(SettingsLayer.Kind.GLOBAL, SettingsLayer.Kind.EXPLICIT), l.stream().map(SettingsLayer::kind).toList());
        assertEquals(2, l.get(0).tree().get("g").asInt());
        assertEquals("sql:explicit", l.get(1).origin());
        assertTrue(src.remove(SettingsLayer.Kind.GLOBAL));
        assertEquals(1, src.layers().size());
    }

    @Test
    void mcpTrustBoundToDefinition() throws Exception {
        var t = s.mcpTrust();
        JsonNode def = MAPPER.readTree("{\"command\":\"x\"}");
        Path proj = tmp.resolve("proj");
        assertNull(t.lookup(proj, "srv", def));
        t.remember(proj, "srv", def, true);
        assertEquals(Boolean.TRUE, t.lookup(proj, "srv", def));
        assertNull(t.lookup(proj, "srv", MAPPER.readTree("{\"command\":\"y\"}")));
        assertNull(t.lookup(tmp.resolve("other"), "srv", def));
        t.remember(proj, "srv", def, false);
        assertEquals(Boolean.FALSE, t.lookup(proj, "srv", def));
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
    void chatListFiltersByOwnerInTheQuery() {
        var chats = s.chats();
        chats.save(ChatSessionData.create("a1", "agent", "A1", "alice"));
        chats.save(ChatSessionData.create("b1", "agent", "B1", "bob"));
        chats.save(ChatSessionData.create("a2", "agent", "A2", "alice"));
        chats.save(ChatSessionData.create("n1", "agent", "No owner"));
        assertEquals(List.of("a2", "a1"), ids(chats.list(ChatVisibility.ownedBy("alice"))));
        assertEquals(List.of("b1"), ids(chats.list(ChatVisibility.ownedBy("bob"))));
        assertTrue(chats.list(ChatVisibility.ownedBy("carol")).isEmpty());
        assertTrue(chats.list(ChatVisibility.ownedBy(null)).isEmpty());
        assertTrue(chats.list(ChatVisibility.nothing()).isEmpty());
        assertEquals(4, chats.list(ChatVisibility.everything()).size());
        assertEquals(3, chats.list(ChatVisibility.owned()).size());
        assertFalse(ids(chats.list(ChatVisibility.owned())).contains("n1"));
        // the owner column is indexed (migration V2)
        assertTrue(s.database().tx(tx -> tx.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_chats_principal'",
                rs -> rs.getString(1)).size()) == 1);
    }

    private static List<String> ids(List<ChatSessionData> l) {
        return l.stream().map(ChatSessionData::chatId).toList();
    }

    @Test
    void chats() {
        var chats = s.chats();
        var base = ChatSessionData.create("c1", "agent", "T", "bob");
        chats.save(base.withMessagesJson("{\"a\":1}\n{\"b\":2}").withUsageAdded(1, 2, 3, 4)
                .withChatTools("{\"t\":1}").withTaintedToolUses("x,y").withToolGroups("*"));
        chats.save(ChatSessionData.create("c2", "agent", "Later").withMessagesJson("{\"z\":1}"));
        ChatSessionData got = chats.find("c1").orElseThrow();
        assertEquals("{\"a\":1}\n{\"b\":2}", got.messagesJson());
        assertEquals(2, got.messageCount());
        assertEquals("bob", got.principal());
        assertEquals(4, got.cacheCreationTokens());
        assertEquals("x,y", got.taintedToolUses());
        assertEquals(base.createdAt(), got.createdAt());
        List<ChatSessionData> list = chats.list(ChatVisibility.everything());
        assertEquals(List.of("c2", "c1"), list.stream().map(ChatSessionData::chatId).toList());
        assertEquals("", list.get(0).messagesJson());
        assertTrue(chats.find("none").isEmpty());

        s.state().save("c1", "grants", MAPPER.createObjectNode().put("k", "v"));
        assertEquals("v", s.state().load("c1", "grants").get("k").asText());
        assertNull(s.state().load("c1", "other"));

        MnemonicState m = new MnemonicState();
        m.frontier = 7;
        s.mnemonic().save("c1", "main", m);
        assertEquals(7, s.mnemonic().load("c1", "main").frontier);
        assertEquals(0, s.mnemonic().load("c1", "sub").frontier);

        var run = new SubAgentRunData("r1", "c1", "tu1", "p", "{}", "ans", "ok", 1, 2, 3, 4, Instant.now());
        s.subAgentRuns().record(run);
        assertEquals(List.of(run), s.subAgentRuns().list("c1"));
        assertEquals(1, s.subAgentRuns().forToolUse("c1", "tu1").size());
        assertTrue(s.subAgentRuns().forToolUse("c1", "zz").isEmpty());

        chats.delete("c1");
        assertTrue(chats.find("c1").isEmpty());
        assertNull(s.state().load("c1", "grants"));
        assertEquals(0, s.mnemonic().load("c1", "main").frontier);
        assertTrue(s.subAgentRuns().list("c1").isEmpty());
        assertTrue(chats.find("c2").isPresent());
    }

    @Test
    void failedTransactionRollsBack() {
        assertThrows(IllegalStateException.class, () -> s.database().run(tx -> {
            tx.update("INSERT INTO users (id, name, role) VALUES ('t', 't', 'admin')");
            throw new IllegalStateException("boom");
        }));
        assertFalse(s.users().exists("t"));
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

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

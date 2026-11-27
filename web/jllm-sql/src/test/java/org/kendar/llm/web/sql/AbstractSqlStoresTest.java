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

/**
 * The store behaviour every supported database must show. Subclasses give an empty database ({@link #config()}) and the
 * few dialect specific lookups; the checks themselves are shared, so SQLite, PostgreSQL and MySQL are held to the same.
 */
abstract class AbstractSqlStoresTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;
    SqlStores s;

    /** A configuration for a new, empty database; called once per test and again to reopen the same database. */
    abstract SqlConfig config();

    /** True when the database has an index of that name. */
    abstract boolean indexExists(String name);

    @BeforeEach
    void open() {
        s = SqlStores.open(config(), null);
    }

    @AfterEach
    void close() {
        s.close();
    }

    @Test
    void schemaIsKeptOnReopen() {
        saveRoles(s, "dev");
        s.users().create("bob", "Bob", "dev", null, null);
        s.close();
        s = SqlStores.open(config(), null);
        assertTrue(s.users().exists("bob"));
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
    void userSettingsAreFreeTextJson() {
        var u = s.users();
        saveRoles(s, "dev");
        assertEquals("{}", u.create("amy", "Amy", "dev", null, null).settings());
        User got = u.setSettings("amy", " { \"theme\" : \"dark\", \"n\": 2 } ");
        assertEquals("{\"theme\":\"dark\",\"n\":2}", got.settings());
        assertEquals(got.settings(), u.find("amy").orElseThrow().settings());
        assertThrows(IllegalArgumentException.class, () -> u.setSettings("amy", "[1]"));
        assertThrows(IllegalArgumentException.class, () -> u.setSettings("amy", "{bad"));
        assertEquals("{\"theme\":\"dark\",\"n\":2}", u.find("amy").orElseThrow().settings()); // a rejected value changes nothing
        assertEquals("{}", u.setSettings("amy", null).settings());
        assertThrows(NoSuchElementException.class, () -> u.setSettings("nobody", "{}"));
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
    void roleSandboxIsStoredAndDefaultsToNone() {
        var r = s.roles();
        r.save(new Role("box", List.of("*"), false, "ask", Map.of(),
                Map.of("sandbox", org.kendar.llm.harness.identity.RoleSandbox.of("docker-isolated", "user", "none", false).toJson())));
        var sb = r.find("box").orElseThrow().sandbox();
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.DOCKER_ISOLATED, sb.kind());
        assertEquals("hardened", sb.profile());
        assertEquals("user", sb.scope());
        assertEquals("none", sb.network());
        r.save(new Role("plain", List.of("*"), false, "ask", Map.of(), null));
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.NONE, r.find("plain").orElseThrow().sandbox().kind());
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.NONE, r.find("admin").orElseThrow().sandbox().kind());
    }

    @Test
    void roleSettingsRoundTripKeepsUnknownKeys() throws Exception {
        var r = s.roles();
        Map<String, JsonNode> settings = new LinkedHashMap<>();
        settings.put("sandbox", org.kendar.llm.harness.identity.RoleSandbox.of("docker-open", "chat", null, false).toJson());
        settings.put("future-feature", MAPPER.readTree("{\"a\":[1,{\"b\":true}],\"c\":\"x\"}"));
        r.save(new Role("feat", List.of("*"), false, "ask", Map.of(), settings));
        Role got = r.find("feat").orElseThrow();
        assertEquals(settings, got.settings());
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.DOCKER_OPEN, got.sandbox().kind());
        r.save(got.withSetting("sandbox", null));
        got = r.find("feat").orElseThrow();
        assertEquals(List.of("future-feature"), List.copyOf(got.settings().keySet()));
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.NONE, got.sandbox().kind());
        r.save(got.withSetting("future-feature", null));
        assertTrue(r.find("feat").orElseThrow().settings().isEmpty());
    }

    @Test
    void newDatabaseRecordsTheLastSchemaVersion() {
        assertEquals(Schema.VERSION, Schema.version(s.database()));
        assertEquals(1, (int) s.database().tx(tx -> tx.one("SELECT COUNT(*) FROM schema_version", rs -> rs.getInt(1)).orElse(0)));
    }

    @Test
    void versionOneDatabaseIsMigrated() {
        saveRoles(s, "dev");
        s.users().create("bob", "Bob", "dev", null, null);
        // back to version 1: no settings columns
        s.database().run(tx -> {
            tx.update("ALTER TABLE roles DROP COLUMN settings");
            tx.update("ALTER TABLE users DROP COLUMN settings");
            tx.update("DELETE FROM schema_version");
            tx.update("INSERT INTO schema_version (version) VALUES (1)");
        });
        s.close();
        s = SqlStores.open(config(), null);
        assertEquals(Schema.VERSION, Schema.version(s.database()));
        s.database().tx(tx -> tx.query("SELECT settings FROM roles", rs -> rs.getString(1)));
        s.database().tx(tx -> tx.query("SELECT settings FROM users", rs -> rs.getString(1)));
        assertTrue(s.roles().find("dev").orElseThrow().settings().isEmpty());
        assertEquals("dev", s.users().find("bob").orElseThrow().role());
        s.roles().save(new Role("dev", List.of("*"), false, "ask", Map.of(),
                Map.of("sandbox", org.kendar.llm.harness.identity.RoleSandbox.of("shared-local", null, null, false).toJson())));
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.SHARED_LOCAL, s.roles().find("dev").orElseThrow().sandbox().kind());
        s.close();
        s = SqlStores.open(config(), null); // reopening a migrated database changes nothing
        assertEquals(Schema.VERSION, Schema.version(s.database()));
        assertEquals(org.kendar.llm.harness.sandbox.SandboxKind.SHARED_LOCAL, s.roles().find("dev").orElseThrow().sandbox().kind());
    }

    @Test
    void newerDatabaseIsRefused() {
        s.database().run(tx -> {
            tx.update("DELETE FROM schema_version");
            tx.update("INSERT INTO schema_version (version) VALUES (?)", Schema.VERSION + 1);
        });
        s.close();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SqlStores.open(config(), null));
        assertTrue(e.getMessage().contains("newer than this server"), e.getMessage());
    }

    @Test
    void roles() {
        var r = s.roles();
        Map<String, ToolPermission> perms = new LinkedHashMap<>();
        perms.put("send_mail", ToolPermission.ALLOW);
        perms.put("bash", ToolPermission.DENY);
        r.save(new Role("dev", List.of("read_file", "mcp__docs__*"), true, "off", perms, Map.of()));
        Role got = r.find("dev").orElseThrow();
        assertEquals(List.of("read_file", "mcp__docs__*"), got.tools());
        assertTrue(got.readOnly());
        assertEquals("off", got.approval());
        assertEquals(perms, got.toolPermissions());
        assertEquals(List.of("send_mail", "bash"), List.copyOf(got.toolPermissions().keySet()));

        r.save(new Role("dev", List.of("*"), false, "ask", Map.of(), Map.of()));
        got = r.find("dev").orElseThrow();
        assertEquals(List.of("*"), got.tools());
        assertTrue(got.toolPermissions().isEmpty());
        r.save(new Role("a", List.of(), false, "ask", Map.of(), Map.of()));
        assertEquals(List.of("a", "admin", "dev"), r.all().stream().map(Role::name).toList());
        assertTrue(r.find("Bad Name").isEmpty());
        assertTrue(r.delete("dev"));
        assertFalse(r.delete("dev"));
        assertTrue(r.find("dev").isEmpty());
    }

    static void saveRoles(SqlStores st, String... names) {
        for (String n : names) st.roles().save(new Role(n, List.of(), false, "ask", Map.of(), Map.of()));
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
        String t1 = r.create("bob", null);
        String t2 = r.create("bob", null);
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
        String t3 = r.create("amy", null);
        r.revoke(t3);
        assertTrue(r.find(t3).isEmpty());
    }

    @Test
    void loginSessionEndsAtItsHardLimitWhateverItsUse() {
        var clock = new MutableClock();
        var r = new SqlLoginSessionRepository(s.database(), clock);
        String t = r.create("bob", clock.instant().plus(Duration.ofHours(8)));
        clock.advance(Duration.ofHours(7));
        assertEquals("bob", r.find(t).orElseThrow());
        clock.advance(Duration.ofHours(2));
        assertTrue(r.find(t).isEmpty());
        assertTrue(r.find(t).isEmpty()); // and gone
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
    void aSealedTokenCopiedToAnotherUserOrSourceIsNotReadable() {
        OAuthToken t = new OAuthToken("acc", "ref", 123, Map.of());
        var sealed = new SqlTokenStore(s.database(), new TokenCipher(new byte[32], null));
        sealed.put("alice", "mcp:a", t);
        sealed.put("bob", "mcp:a", new OAuthToken("bobs", "ref", 1, Map.of()));
        String payload = s.database().tx(tx -> tx.one("SELECT payload FROM oauth_tokens WHERE principal_id = ?",
                rs -> rs.getString(1), "alice").orElseThrow());
        // alice's sealed value moved into bob's row, and into another source name of alice
        s.database().run(tx -> tx.update("UPDATE oauth_tokens SET payload = ? WHERE principal_id = ?", payload, "bob"));
        s.database().run(tx -> tx.update("INSERT INTO oauth_tokens (principal_id, name, payload, version) VALUES (?, ?, ?, 1)",
                "alice", "mcp:other", payload));

        assertEquals(t, sealed.get("alice", "mcp:a").orElseThrow(), "in place it still decrypts");
        assertTrue(sealed.get("bob", "mcp:a").isEmpty(), "another user's row: the user id is bound");
        assertTrue(sealed.get("alice", "mcp:other").isEmpty(), "another source: the source is bound");
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
    void storedLayerIsNotASettingsLayerRow() throws Exception {
        JsonNode tree = MAPPER.readTree("{\"a\":1}");
        assertThrows(IllegalArgumentException.class, () -> s.settings().put(SettingsLayer.Kind.STORED, null, tree));
    }

    @Test
    void settingsSinkRoundTripKeepsUnknownSectionsAndWinsOverTheLayers() throws Exception {
        var sink = s.settingsSink();
        assertTrue(sink.sections().isEmpty());
        String unknown = "{\"deep\":[1,{\"b\":null,\"c\":\"env:SECRET\"}],\"n\":1.5}";
        sink.put("future", MAPPER.readTree(unknown));
        sink.put("context", MAPPER.readTree("{\"maxTokens\":5000}"));
        sink.put("context", MAPPER.readTree("{\"maxTokens\":6000}")); // replaced whole
        s.close();
        s = SqlStores.open(config(), null);
        sink = s.settingsSink();
        assertEquals(List.of("context", "future"), List.copyOf(sink.sections().keySet()));
        assertEquals(MAPPER.readTree(unknown), sink.get("future").orElseThrow());
        s.settings().put(SettingsLayer.Kind.GLOBAL, null, MAPPER.readTree("{\"context\":{\"maxTokens\":1000,\"keepRecentTurns\":3}}"));
        var settings = org.kendar.llm.harness.settings.HarnessSettingsLoader.loadLayered(sink.over(s.settings()), null,
                k -> null, null, null, w -> { }, new org.kendar.llm.harness.safety.ContentWarnings());
        assertEquals(6000, settings.context().maxTokens());
        assertEquals(3, settings.context().keepRecentTurns());
        assertTrue(sink.remove("context"));
        assertFalse(sink.remove("context"));
        assertEquals(List.of("future"), List.copyOf(sink.sections().keySet()));
    }

    @Test
    void roleMappingIsStoredWithTheRoleAndAsAHostSection() throws Exception {
        JsonNode idp = MAPPER.readTree("{\"groups\":[\"eng\",\"team-*\"],\"priority\":10}");
        s.roles().save(new Role("dev", List.of("*"), false, "ask", Map.of(), Map.of("idp", idp)));
        var sink = s.settingsSink();
        sink.put("web.roleMapping", MAPPER.readTree("{\"enabled\":true,\"claim\":\"groups\"}"));
        s.close();
        s = SqlStores.open(config(), null);
        assertEquals(idp, s.roles().find("dev").orElseThrow().setting("idp"));
        sink = s.settingsSink();
        assertTrue(sink.get("web.roleMapping").orElseThrow().path("enabled").asBoolean());
        assertTrue(sink.layer().isEmpty()); // the harness never sees a host section
        List<String> warnings = new java.util.ArrayList<>();
        org.kendar.llm.harness.settings.HarnessSettingsLoader.loadLayered(sink.over(s.settings()), null,
                k -> null, null, null, warnings::add, new org.kendar.llm.harness.safety.ContentWarnings());
        assertEquals(List.of(), warnings);
    }

    @Test
    void settingsSinkWriteOfAnotherNodeIsSeenAfterReload() throws Exception {
        var mine = s.settingsSink();
        var other = new SqlSettingsSink(s.database());
        mine.put("a", MAPPER.readTree("1"));
        List<Map<String, JsonNode>> seen = new java.util.ArrayList<>();
        mine.onChange(seen::add);
        other.put("a", MAPPER.readTree("2"));
        assertEquals(1, mine.get("a").orElseThrow().asInt());
        mine.reload();
        assertEquals(2, mine.get("a").orElseThrow().asInt());
        assertEquals(1, seen.size());
    }

    @Test
    void settingsSinkConcurrentWriters() throws Exception {
        int threads = 4;
        int rounds = 10;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                var sink = t % 2 == 0 ? s.settingsSink() : new SqlSettingsSink(s.database());
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        sink.put("own" + id, MAPPER.readTree("{\"i\":" + i + "}"));
                        sink.put("shared", MAPPER.readTree("{\"by\":" + id + "}"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : done) f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        var fresh = new SqlSettingsSink(s.database());
        assertEquals(threads + 1, fresh.sections().size());
        for (int t = 0; t < threads; t++) assertEquals(rounds - 1, fresh.get("own" + t).orElseThrow().get("i").asInt());
        assertTrue(fresh.get("shared").orElseThrow().get("by").isInt());
    }

    @Test
    void versionTwoDatabaseGetsTheSettingsSections() throws Exception {
        s.database().run(tx -> {
            tx.update("DROP TABLE settings_section");
            tx.update("DELETE FROM schema_version");
            tx.update("INSERT INTO schema_version (version) VALUES (2)");
        });
        s.close();
        s = SqlStores.open(config(), null);
        assertEquals(Schema.VERSION, Schema.version(s.database()));
        s.settingsSink().put("a", MAPPER.readTree("{\"b\":1}"));
        assertEquals(1, new SqlSettingsSink(s.database()).get("a").orElseThrow().get("b").asInt());
    }

    @Test
    void chatListFiltersByOwnerInTheQuery() throws InterruptedException {
        var chats = s.chats();
        chats.save(ChatSessionData.create("a1", "agent", "A1", "alice"));
        Thread.sleep(5); // the list is newest first: saves in one millisecond would tie
        chats.save(ChatSessionData.create("b1", "agent", "B1", "bob"));
        Thread.sleep(5);
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
        // the owner column is indexed
        assertTrue(indexExists("idx_chats_principal"));
    }

    private static List<String> ids(List<ChatSessionData> l) {
        return l.stream().map(ChatSessionData::chatId).toList();
    }

    @Test
    void chats() throws InterruptedException {
        var chats = s.chats();
        var base = ChatSessionData.create("c1", "agent", "T", "bob");
        chats.save(base.withMessagesJson("{\"a\":1}\n{\"b\":2}").withUsageAdded(1, 2, 3, 4)
                .withChatTools("{\"t\":1}").withTaintedToolUses("x,y").withToolGroups("*"));
        Thread.sleep(5); // the list is newest first: two saves in one millisecond would tie
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
    void instructionsAreKeptPerScopeAndOwner() {
        var in = s.instructions();
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.SYSTEM, "", "replace", "You are X.");
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.SYSTEM, "", "append", "Be kind.");
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "AGENTS.md", "Rule.");
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.ROLE, "dev", "style", "Short.");
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.USER, "ann", "me", "Ann.");
        assertEquals("You are X.", in.systemPromptReplace().orElseThrow());
        assertEquals("Be kind.", in.systemPromptAppend());
        assertEquals("Instructions from AGENTS.md:\nRule.", in.renderGlobal());
        assertTrue(in.renderFor("dev", "ann").contains("Short."));
        assertEquals("", in.renderFor("ops", "bob"));
        in.write(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "AGENTS.md", "Rule two.");
        assertEquals(1, in.list(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "").size());
        assertEquals("Rule two.", in.find(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "AGENTS.md")
                .orElseThrow().content());
        assertThrows(IllegalArgumentException.class, () -> in.write(
                org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "x", "a", "b"));
        assertTrue(in.delete(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "AGENTS.md"));
        assertFalse(in.delete(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "AGENTS.md"));
        in.deleteOwned(org.kendar.llm.harness.settings.InstructionRepository.Scope.ROLE, "dev");
        assertTrue(in.list(org.kendar.llm.harness.settings.InstructionRepository.Scope.ROLE, "dev").isEmpty());
        assertEquals(1, in.list(org.kendar.llm.harness.settings.InstructionRepository.Scope.USER, "ann").size());
    }

    @Test
    void agentsAreKeptInlineOnly() {
        var agents = s.agents();
        var a = new org.kendar.llm.harness.settings.AgentSettings("reviewer", "Reviews", List.of("read_file"), "Review it.",
                null, true, null, true, null, 0.5, null);
        agents.write(a);
        assertEquals(List.of("reviewer"), agents.all().stream().map(org.kendar.llm.harness.settings.AgentSettings::name).toList());
        var got = agents.find("reviewer").orElseThrow();
        assertEquals("Review it.", got.prompt());
        assertEquals(List.of("read_file"), got.tools());
        assertEquals(0.5, got.temperature());
        assertThrows(IllegalArgumentException.class, () -> agents.write(new org.kendar.llm.harness.settings.AgentSettings(
                "bad", null, null, null, "/etc/passwd", null, null, null, null, null, null)));
        assertTrue(agents.find("bad").isEmpty());
        assertTrue(agents.delete("reviewer"));
        assertTrue(agents.all().isEmpty());
    }

    @Test
    void theConfigProviderRefusesSettingsThatNameFiles() throws Exception {
        var provider = s.config();
        provider.putSettings(MAPPER.readTree("{\"smallModel\":true}"));
        assertTrue(provider.settingsDocument().path("smallModel").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> provider.putSettings(
                MAPPER.readTree("{\"agents\":[{\"name\":\"a\",\"promptFile\":\"p.md\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> provider.putSettings(
                MAPPER.readTree("{\"skills\":[{\"name\":\"a\",\"path\":\"s.md\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> provider.putSettings(MAPPER.readTree("{\"instructionFiles\":[]}")));
        assertTrue(provider.settingsDocument().path("smallModel").asBoolean(), "a refused document changes nothing");
        assertSame(s.skills(), provider.skills());
        assertNotNull(provider.pluginStorages());
    }

    @Test
    void versionFourDatabaseGetsTheVirtualConfigurationAndLosesMcpTrust() {
        s.database().run(tx -> {
            tx.update("DROP TABLE instructions");
            tx.update("DROP TABLE agents");
            tx.update("CREATE TABLE mcp_trust (project_key VARCHAR(100) NOT NULL)"); // what an older server left
            tx.update("DELETE FROM schema_version");
            tx.update("INSERT INTO schema_version (version) VALUES (4)");
        });
        s.close();
        s = SqlStores.open(config(), null);
        assertEquals(Schema.VERSION, Schema.version(s.database()));
        s.instructions().write(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "a", "b");
        assertEquals("b", s.instructions().find(org.kendar.llm.harness.settings.InstructionRepository.Scope.GLOBAL, "", "a")
                .orElseThrow().content());
        assertThrows(RuntimeException.class, () -> s.database().tx(tx -> tx.query("SELECT 1 FROM mcp_trust", rs -> 1)));
    }

    static final class MutableClock extends Clock {
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

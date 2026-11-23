package org.kendar.llm.web.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SQL generation and schema scripts: no database server needed. */
class DialectTest {

    @Test
    void upsertSqlite() {
        assertEquals("INSERT INTO t (k, a, b) VALUES (?, ?, ?) ON CONFLICT (k) DO UPDATE SET a = excluded.a, b = excluded.b",
                Dialect.SQLITE.upsert("t", List.of("k"), List.of("a", "b")));
        assertEquals("INSERT INTO t (k1, k2) VALUES (?, ?) ON CONFLICT (k1, k2) DO NOTHING",
                Dialect.SQLITE.upsert("t", List.of("k1", "k2"), List.of()));
    }

    @Test
    void upsertMysql() {
        assertEquals("INSERT INTO t (k, a) VALUES (?, ?) AS new_row ON DUPLICATE KEY UPDATE a = new_row.a",
                Dialect.MYSQL.upsert("t", List.of("k"), List.of("a")));
        assertEquals("INSERT IGNORE INTO t (k) VALUES (?)", Dialect.MYSQL.upsert("t", List.of("k"), List.of()));
    }

    @Test
    void upsertPostgres() {
        assertEquals("INSERT INTO t (k, a) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET a = excluded.a",
                Dialect.POSTGRES.upsert("t", List.of("k"), List.of("a")));
        assertEquals("INSERT INTO t (k) VALUES (?) ON CONFLICT (k) DO NOTHING",
                Dialect.POSTGRES.upsert("t", List.of("k"), List.of()));
    }

    @Test
    void dialectOfUrl() {
        assertEquals(Dialect.POSTGRES, Dialect.of("jdbc:postgresql://h:5432/db"));
        assertEquals(Dialect.SQLITE, Dialect.of("jdbc:sqlite::memory:"));
        assertEquals(Dialect.MYSQL, Dialect.of("jdbc:mysql://h/db"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.of("jdbc:oracle:thin:@h"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.of(null));
    }

    @Test
    void dialectComesOnlyFromTheUrlScheme() {
        assertEquals(Dialect.SQLITE, Dialect.of("jdbc:sqlite:/tmp/x.db"));
        IllegalArgumentException maria = assertThrows(IllegalArgumentException.class, () -> Dialect.of("jdbc:mariadb://h/db"));
        assertTrue(maria.getMessage().contains("MariaDB"), maria.getMessage());
        for (String bad : new String[]{"", "  ", "sqlite:x.db", "jdbc:h2:mem:x", "jdbc:oracle:thin:@h", "JDBC:SQLITE:x"}) {
            assertThrows(IllegalArgumentException.class, () -> Dialect.of(bad), bad);
        }
        // the configuration follows the url: no other property selects it; no url = the SQLite default file
        java.nio.file.Path dir = java.nio.file.Path.of("d");
        assertEquals(Dialect.MYSQL, SqlConfig.fromProperties(
                k -> k.equals("jllm.sql.url") ? "jdbc:mysql://h/db" : null, dir).dialect());
        assertEquals(Dialect.SQLITE, SqlConfig.fromProperties(k -> null, dir).dialect());
        assertThrows(IllegalArgumentException.class, () -> SqlConfig.fromProperties(
                k -> k.equals("jllm.sql.url") ? "jdbc:oracle:thin:@h" : null, dir));
    }

    @Test
    void duplicateDetection() {
        assertTrue(Dialect.MYSQL.isDuplicate(new java.sql.SQLException("Duplicate entry", "23000", 1062)));
        assertFalse(Dialect.MYSQL.isDuplicate(new java.sql.SQLException("other", "42000", 1064)));
        assertTrue(Dialect.SQLITE.isDuplicate(new java.sql.SQLException("[SQLITE_CONSTRAINT_PRIMARYKEY] x")));
        assertFalse(Dialect.SQLITE.isDuplicate(new java.sql.SQLException("no such table")));
        assertTrue(Dialect.POSTGRES.isDuplicate(new java.sql.SQLException("duplicate key", "23505")));
        assertFalse(Dialect.POSTGRES.isDuplicate(new java.sql.SQLException("other", "42P01")));
    }

    @Test
    void schemaScriptSplitsForEveryDialect() {
        for (Dialect d : Dialect.values()) {
            List<String> st = Schema.statements(Schema.read(d));
            assertTrue(st.size() > 15, d + " " + st.size());
            for (String s : st) {
                assertFalse(s.endsWith(";"));
                assertTrue(s.startsWith("CREATE ") || s.contains("INTO roles")
                        || s.contains("INTO role_tools") || s.contains("INTO schema_version"), s);
            }
        }
        assertEquals(List.of("A", "B C"), Schema.statements("-- c\nA;\nB\nC;\n").stream()
                .map(s -> s.replace("\n", " ")).toList());
    }

    @Test
    void usersRoleForeignKeyAndAdminRowInEveryDialect() {
        for (Dialect d : Dialect.values()) {
            String sql = String.join(" ", Schema.statements(Schema.read(d)));
            assertTrue(sql.contains("INTO roles (name, read_only, approval) VALUES ('admin', 0, 'ask')"), d.name());
            assertTrue(sql.contains("INTO role_tools (role, ord, pattern) VALUES ('admin', 0, '*')"), d.name());
            String users = Schema.statements(Schema.read(d)).stream()
                    .filter(s -> s.matches("(?s)CREATE TABLE (IF NOT EXISTS )?users.*")).findFirst().orElseThrow();
            assertTrue(users.contains("ON DELETE RESTRICT") && users.contains("REFERENCES roles(name)"), d.name());
            String flat = users.replaceAll(" +", " ");
            assertTrue(flat.contains(" role TEXT NOT NULL") || flat.contains(" role VARCHAR(64) NOT NULL"), d.name());
        }
    }

    @Test
    void schemaVersionRowIsOneInEveryDialect() {
        for (Dialect d : Dialect.values()) {
            List<String> st = Schema.statements(Schema.read(d));
            assertTrue(st.stream().anyMatch(s -> s.matches("(?s)CREATE TABLE (IF NOT EXISTS )?schema_version \\(version INT(EGER)? NOT NULL PRIMARY KEY\\)")), d.name());
            assertTrue(st.stream().anyMatch(s -> s.matches("INSERT (OR IGNORE )?INTO schema_version \\(version\\) VALUES \\(1\\)")), d.name());
        }
    }

    @Test
    void sameTablesInBothDialects() {
        assertEquals(tables(Dialect.SQLITE), tables(Dialect.MYSQL));
        assertEquals(tables(Dialect.SQLITE), tables(Dialect.POSTGRES));
    }

    private static List<String> tables(Dialect d) {
        return Schema.statements(Schema.read(d)).stream()
                .filter(s -> s.startsWith("CREATE TABLE")).map(s -> s.replace("IF NOT EXISTS ", "").split("\\s+")[2]).toList();
    }
}

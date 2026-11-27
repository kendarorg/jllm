package org.kendar.llm.web.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The schema: {@code db/<dialect>/schema.sql} creates a new database at {@link #VERSION}; an older database is brought
 * up by the {@link #STEPS} above its recorded version, in order, under the schema lock (several instances may start
 * together). Each step is idempotent, so a step interrupted half way is run again. A database newer than this server
 * is refused. Statements end with {@code ;} at the end of a line; lines starting with {@code --} are comments.
 */
final class Schema {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Schema.class);

    /** A migration step: brings a database at {@code version - 1} to {@code version}. */
    record Step(int version, String what, java.util.function.Consumer<Database> apply) {
    }

    /** Every step, in order. Version 1 is the first released schema. */
    static final List<Step> STEPS = List.of(
            new Step(2, "settings columns of roles and users", Schema::settingsColumns),
            new Step(3, "settings_section table (the stored settings)", Schema::settingsSections),
            new Step(4, "not_after column of login_sessions (hard end of a sign-in)", Schema::sessionNotAfter),
            new Step(5, "instructions and agents tables, mcp_trust dropped (virtual configuration)",
                    Schema::virtualConfiguration));

    /** The version {@code schema.sql} creates: that of the last step. */
    static final int VERSION = STEPS.get(STEPS.size() - 1).version();

    private Schema() {
    }

    /** Creates the tables when missing, else runs the missing migration steps. */
    static void ensure(Database db) {
        if (!exists(db)) {
            db.locked(() -> {
                if (exists(db)) return null; // another instance created it while this one waited
                List<String> statements = statements(read(db.dialect()));
                db.run(tx -> {
                    for (String st : statements) tx.update(st);
                });
                setVersion(db, VERSION);
                return null;
            });
        }
        migrate(db);
    }

    /** Runs the steps above the recorded version. */
    static void migrate(Database db) {
        int at = version(db);
        if (at > VERSION) {
            throw new IllegalStateException("The database schema is version " + at + ", newer than this server ("
                    + VERSION + "): use a newer server");
        }
        if (at == VERSION) return;
        db.locked(() -> {
            int now = version(db); // another instance may have migrated meanwhile
            for (Step step : STEPS) {
                if (step.version() <= now) continue;
                log.info("Database schema: version {} to {} ({})", now, step.version(), step.what());
                step.apply().accept(db);
                setVersion(db, step.version());
                now = step.version();
            }
            return null;
        });
    }

    /** The recorded version; 1 when the table is empty (the first schema). */
    static int version(Database db) {
        int v = db.tx(tx -> tx.one("SELECT MAX(version) FROM schema_version", rs -> rs.getInt(1)).orElse(0));
        return v <= 0 ? 1 : v;
    }

    private static void setVersion(Database db, int version) {
        db.run(tx -> {
            tx.update("DELETE FROM schema_version");
            tx.update("INSERT INTO schema_version (version) VALUES (?)", version);
        });
    }

    /**
     * Version 2: {@code roles.settings} and {@code users.settings}, the free JSON of a role (its settings map) and of
     * a user (null = none).
     */
    private static void settingsColumns(Database db) {
        for (String table : List.of("roles", "users")) {
            if (columnExists(db, table, "settings")) continue;
            db.run(tx -> tx.update("ALTER TABLE " + table + " ADD COLUMN settings TEXT"));
        }
    }

    /**
     * Version 3: {@code settings_section(name, doc)}, the sections an administrator stores at runtime
     * ({@link SqlSettingsSink}). Its statement is the one of {@code schema.sql}.
     */
    private static void settingsSections(Database db) {
        if (columnExists(db, "settings_section", "name")) return;
        String create = statements(read(db.dialect())).stream()
                .filter(s -> s.matches("(?s)CREATE TABLE (IF NOT EXISTS )?settings_section .*")).findFirst()
                .orElseThrow(() -> new IllegalStateException("No settings_section in the schema of " + db.dialect()));
        db.run(tx -> tx.update(create));
    }

    /** Version 4: {@code login_sessions.not_after}, when a session ends whatever its use (null = no such limit). */
    private static void sessionNotAfter(Database db) {
        if (columnExists(db, "login_sessions", "not_after")) return;
        db.run(tx -> tx.update("ALTER TABLE login_sessions ADD COLUMN not_after BIGINT"));
    }

    /**
     * Version 5: {@code instructions} and {@code agents} (the virtual configuration, {@link SqlConfigProvider}); the
     * {@code mcp_trust} table goes: the web host has no project folder to trust. Statements are those of
     * {@code schema.sql}.
     */
    private static void virtualConfiguration(Database db) {
        for (String table : List.of("instructions", "agents")) {
            if (columnExists(db, table, "name")) continue;
            String create = statements(read(db.dialect())).stream()
                    .filter(s -> s.matches("(?s)CREATE TABLE (IF NOT EXISTS )?" + table + " .*")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No " + table + " in the schema of " + db.dialect()));
            db.run(tx -> tx.update(create));
        }
        db.run(tx -> tx.update("DROP TABLE IF EXISTS mcp_trust"));
    }

    private static boolean columnExists(Database db, String table, String column) {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement()) {
            st.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean exists(Database db) {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement()) {
            st.executeQuery("SELECT 1 FROM users WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.strip().startsWith("--")) continue;
            cur.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String st = cur.toString().strip();
                out.add(st.substring(0, st.length() - 1));
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().strip());
        return out;
    }

    static String read(Dialect d) {
        String path = "db/" + d.dir() + "/schema.sql";
        try (InputStream in = Schema.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing schema resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}

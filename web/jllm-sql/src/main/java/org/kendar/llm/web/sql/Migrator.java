package org.kendar.llm.web.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned migrations: {@code db/<dialect>/migrations.txt} lists the scripts in order, each named
 * {@code V<n>__<description>.sql}. Applied versions are recorded in {@code jllm_schema}; a script is never run twice
 * and never edited once released: a change is a new version. Statements end with {@code ;} at the end of a line;
 * lines starting with {@code --} are comments.
 */
final class Migrator {

    private static final Pattern NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    private record Script(int version, String description, String file) {
    }

    private final Database db;

    Migrator(Database db) {
        this.db = db;
    }

    /** @return the version after migrating */
    int migrate() {
        Dialect d = db.dialect();
        List<Script> scripts = scripts(d);
        db.run(tx -> tx.update(switch (d) {
            case SQLITE -> "CREATE TABLE IF NOT EXISTS jllm_schema (version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at INTEGER NOT NULL)";
            case MYSQL -> "CREATE TABLE IF NOT EXISTS jllm_schema (version INT NOT NULL PRIMARY KEY, description VARCHAR(255) NOT NULL, applied_at BIGINT NOT NULL) ENGINE=InnoDB";
            case POSTGRES -> "CREATE TABLE IF NOT EXISTS jllm_schema (version INTEGER NOT NULL PRIMARY KEY, description TEXT NOT NULL, applied_at BIGINT NOT NULL)";
        }));
        return db.locked(() -> {
            int current = 0;
            for (Script s : scripts) {
                int applied = db.tx(tx -> tx.one("SELECT MAX(version) FROM jllm_schema", rs -> rs.getInt(1)).orElse(0));
                current = Math.max(current, applied);
                if (s.version() <= applied) continue;
                List<String> statements = statements(read(d, s.file()));
                db.run(tx -> {
                    for (String st : statements) tx.update(st);
                    tx.update("INSERT INTO jllm_schema (version, description, applied_at) VALUES (?, ?, ?)",
                            s.version(), s.description(), System.currentTimeMillis());
                });
                current = s.version();
            }
            return current;
        });
    }

    private static List<Script> scripts(Dialect d) {
        List<Script> out = new ArrayList<>();
        int last = 0;
        for (String line : read(d, "migrations.txt").split("\\R")) {
            String f = line.strip();
            if (f.isEmpty() || f.startsWith("#")) continue;
            Matcher m = NAME.matcher(f);
            if (!m.matches()) throw new IllegalStateException("Bad migration name: " + f);
            int v = Integer.parseInt(m.group(1));
            if (v <= last) throw new IllegalStateException("Migrations must be in increasing order: " + f);
            last = v;
            out.add(new Script(v, m.group(2), f));
        }
        return out;
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

    static String read(Dialect d, String file) {
        String path = "db/" + d.dir() + "/" + file;
        try (InputStream in = Migrator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing migration resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}

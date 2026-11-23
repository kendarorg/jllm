package org.kendar.llm.web.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The schema: {@code db/<dialect>/schema.sql}, applied once when the {@code users} table is missing. There is no
 * upgrade path: changing the schema means deleting the database. Statements end with {@code ;} at the end of a line;
 * lines starting with {@code --} are comments.
 */
final class Schema {

    private Schema() {
    }

    /** Creates the tables when missing; safe when several instances start together. */
    static void ensure(Database db) {
        if (exists(db)) return;
        db.locked(() -> {
            if (exists(db)) return null; // another instance created it while this one waited
            List<String> statements = statements(read(db.dialect()));
            db.run(tx -> {
                for (String st : statements) tx.update(st);
            });
            return null;
        });
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

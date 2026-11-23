package org.kendar.llm.web.sql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The SQL differences between the supported databases: DDL location, upsert syntax, duplicate-key detection. */
public enum Dialect {

    SQLITE("sqlite"),
    MYSQL("mysql"),
    POSTGRES("postgres");

    private final String dir;

    Dialect(String dir) {
        this.dir = dir;
    }

    /** Classpath folder of the migrations: {@code db/<dir>/}. */
    public String dir() {
        return dir;
    }

    /** The dialect of a JDBC url ({@code jdbc:sqlite:...}, {@code jdbc:mysql:...}). */
    public static Dialect of(String jdbcUrl) {
        if (jdbcUrl != null) {
            if (jdbcUrl.startsWith("jdbc:sqlite:")) return SQLITE;
            if (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) return MYSQL;
            if (jdbcUrl.startsWith("jdbc:postgresql:")) return POSTGRES;
        }
        throw new IllegalArgumentException(
                "Unsupported JDBC url (jdbc:sqlite:, jdbc:mysql: or jdbc:postgresql: expected): " + jdbcUrl);
    }

    /**
     * Insert, or update the value columns when the key exists. Parameters: key columns first, then value columns.
     * With no value column the existing row is left alone.
     */
    public String upsert(String table, List<String> keyCols, List<String> valueCols) {
        List<String> all = new ArrayList<>(keyCols);
        all.addAll(valueCols);
        String cols = String.join(", ", all);
        String marks = String.join(", ", Collections.nCopies(all.size(), "?"));
        if (this != MYSQL) { // SQLite and PostgreSQL share ON CONFLICT ... excluded
            String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT ("
                    + String.join(", ", keyCols) + ") DO ";
            if (valueCols.isEmpty()) return sql + "NOTHING";
            return sql + "UPDATE SET " + String.join(", ", valueCols.stream().map(c -> c + " = excluded." + c).toList());
        }
        if (valueCols.isEmpty()) return "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") AS new_row ON DUPLICATE KEY UPDATE "
                + String.join(", ", valueCols.stream().map(c -> c + " = new_row." + c).toList());
    }

    /** Whether the exception is a primary key or unique constraint violation. */
    public boolean isDuplicate(SQLException e) {
        for (Throwable t = e; t instanceof SQLException s; t = t.getCause()) {
            if (this == MYSQL && (s.getErrorCode() == 1062 || (s.getSQLState() != null && s.getSQLState().startsWith("23")))) {
                return true;
            }
            if (this == POSTGRES && "23505".equals(s.getSQLState())) return true;
            String m = s.getMessage();
            if (this == SQLITE && m != null && (m.contains("UNIQUE constraint failed")
                    || m.contains("PRIMARY KEY constraint failed") || m.contains("SQLITE_CONSTRAINT_PRIMARYKEY")
                    || m.contains("SQLITE_CONSTRAINT_UNIQUE"))) {
                return true;
            }
        }
        return false;
    }
}

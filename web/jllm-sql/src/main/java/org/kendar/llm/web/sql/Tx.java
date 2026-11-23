package org.kendar.llm.web.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One transaction on one connection: the only way the stores talk to the database. */
public final class Tx {

    /** Maps the current row of a result set. */
    @FunctionalInterface
    public interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection connection;
    private final Dialect dialect;

    Tx(Connection connection, Dialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    public Dialect dialect() {
        return dialect;
    }

    public int update(String sql, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new SqlFailure(e, dialect);
        }
    }

    public <T> List<T> query(String sql, Row<T> row, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(row.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new SqlFailure(e, dialect);
        }
    }

    public <T> Optional<T> one(String sql, Row<T> row, Object... args) {
        List<T> all = query(sql, row, args);
        return all.isEmpty() ? Optional.empty() : Optional.ofNullable(all.get(0));
    }

    public boolean exists(String sql, Object... args) {
        return !query(sql, rs -> 1, args).isEmpty();
    }

    /** Insert or update; parameters: key values, then value values. */
    public int upsert(String table, List<String> keyCols, List<String> valueCols, Object... args) {
        return update(dialect.upsert(table, keyCols, valueCols), args);
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, a);
        }
    }

    /** A failed statement; keeps the {@link SQLException} and knows whether it was a duplicate key. */
    static final class SqlFailure extends DbException {
        final boolean duplicate;

        SqlFailure(SQLException e, Dialect d) {
            super("SQL failure: " + e.getMessage(), e);
            this.duplicate = d.isDuplicate(e);
        }
    }

    /** Whether the exception is a duplicate-key failure of a statement. */
    public static boolean isDuplicate(RuntimeException e) {
        return e instanceof SqlFailure f && f.duplicate;
    }
}

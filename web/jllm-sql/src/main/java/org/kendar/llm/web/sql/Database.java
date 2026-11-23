package org.kendar.llm.web.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** A pooled connection to the database, migrated to the current schema. Close it to release the pool. */
public final class Database implements AutoCloseable {

    private final HikariDataSource pool;
    private final Dialect dialect;
    private final int schemaVersion;

    private Database(SqlConfig config) {
        this.dialect = config.dialect();
        DriverLoader.ensure(config);
        HikariConfig h = new HikariConfig();
        h.setJdbcUrl(config.jdbcUrl());
        if (config.user() != null) h.setUsername(config.user());
        if (config.password() != null) h.setPassword(config.password());
        h.setMaximumPoolSize(config.maxPoolSize());
        h.setPoolName("jllm-sql");
        if (dialect == Dialect.SQLITE) {
            h.addDataSourceProperty("foreign_keys", "true");
            h.addDataSourceProperty("busy_timeout", "10000");
            if (!config.jdbcUrl().contains(":memory:")) h.addDataSourceProperty("journal_mode", "WAL");
            // a memory database exists only in its connection: never recycle it
            h.setMaxLifetime(0);
            h.setIdleTimeout(0);
            h.setMinimumIdle(config.maxPoolSize());
        }
        this.pool = new HikariDataSource(h);
        try {
            this.schemaVersion = new Migrator(this).migrate();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    /** Opens the pool and applies the pending migrations. */
    public static Database open(SqlConfig config) {
        return new Database(config);
    }

    public Dialect dialect() {
        return dialect;
    }

    public DataSource dataSource() {
        return pool;
    }

    /** The schema version after the migrations. */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** Runs the work in one transaction: commit on return, rollback on any exception. Never nest calls. */
    public <T> T tx(Function<Tx, T> work) {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                T out = work.apply(new Tx(c, dialect));
                c.commit();
                return out;
            } catch (RuntimeException | Error e) {
                try {
                    c.rollback();
                } catch (SQLException ex) {
                    e.addSuppressed(ex);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new DbException("SQL failure: " + e.getMessage(), e);
        }
    }

    public void run(Consumer<Tx> work) {
        tx(t -> {
            work.accept(t);
            return null;
        });
    }

    /** Runs the action holding the migration lock (MySQL: a named server lock; SQLite: the file serializes). */
    <T> T locked(Supplier<T> action) {
        if (dialect == Dialect.SQLITE) return action.get();
        if (dialect == Dialect.POSTGRES) return lockedPostgres(action);
        try (Connection c = pool.getConnection()) {
            try (var ps = c.prepareStatement("SELECT GET_LOCK('jllm_sql_migrate', 60)");
                 var rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) throw new IllegalStateException("Cannot get the migration lock");
            }
            try {
                return action.get();
            } finally {
                try (var ps = c.prepareStatement("SELECT RELEASE_LOCK('jllm_sql_migrate')")) {
                    ps.executeQuery().close();
                }
            }
        } catch (SQLException e) {
            throw new DbException("SQL failure: " + e.getMessage(), e);
        }
    }

    /** PostgreSQL: a session advisory lock held on one connection while the action runs. */
    private <T> T lockedPostgres(Supplier<T> action) {
        try (Connection c = pool.getConnection()) {
            try (var ps = c.prepareStatement("SELECT pg_advisory_lock(746573)")) {
                ps.execute();
            }
            try {
                return action.get();
            } finally {
                try (var ps = c.prepareStatement("SELECT pg_advisory_unlock(746573)")) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new DbException("SQL failure: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}

package org.kendar.llm.web.sql;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where the database is and how the pool is sized.
 *
 * @param maxPoolSize    connections; SQLite defaults to one (single writer), MySQL to ten
 * @param driverDir      folder holding the JDBC driver jar (jllm.sql.driver-dir); null = lib-ext, then .downloaded-libs/<db>/<version>
 */
public record SqlConfig(String jdbcUrl, String user, String password, int maxPoolSize, Path driverDir) {

    public static final String URL = "jllm.sql.url";
    public static final String USER = "jllm.sql.user";
    public static final String PASSWORD = "jllm.sql.password";
    public static final String POOL = "jllm.sql.pool";
    public static final String DRIVER_DIR = "jllm.sql.driver-dir";
    /** Name of the SQLite file in the default folder. */
    public static final String DEFAULT_FILE = "jllm.db";

    public SqlConfig {
        Dialect.of(jdbcUrl);
        if (maxPoolSize < 1) throw new IllegalArgumentException("Pool size must be at least 1");
        driverDir = driverDir == null ? null : driverDir.toAbsolutePath().normalize();
    }

    public Dialect dialect() {
        return Dialect.of(jdbcUrl);
    }

    /** A SQLite database in a file (created when missing). */
    public static SqlConfig sqliteFile(Path file) {
        return new SqlConfig("jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/'), null, null, 1, null);
    }

    /** A private in-memory SQLite database: lives as long as the {@link Database}; one connection. */
    public static SqlConfig sqliteMemory() {
        return new SqlConfig("jdbc:sqlite::memory:", null, null, 1, null);
    }

    public static SqlConfig mysql(String host, int port, String database, String user, String password) {
        return new SqlConfig("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_bin", user, password, 10, null);
    }

    /**
     * From properties ({@code jllm.sql.url}, {@code .user}, {@code .password}, {@code .pool}). Without a url: the SQLite
     * file {@code jllm.db} in {@code defaultDir}, the default of the web application.
     */
    public static SqlConfig fromProperties(Function<String, String> props, Path defaultDir) {
        String url = props.apply(URL);
        if (url == null || url.isBlank()) return sqliteFile(defaultDir.resolve(DEFAULT_FILE));
        String pool = props.apply(POOL);
        Dialect d = Dialect.of(url.strip());
        int size = pool == null || pool.isBlank() ? (d == Dialect.SQLITE ? 1 : 10) : Integer.parseInt(pool.strip());
        String dir = props.apply(DRIVER_DIR);
        return new SqlConfig(url.strip(), props.apply(USER), props.apply(PASSWORD), size,
                dir == null || dir.isBlank() ? null : Path.of(dir.strip()));
    }

    @Override
    public String toString() {
        return "SqlConfig[" + jdbcUrl + ", user=" + user + ", pool=" + maxPoolSize + "]"; // never the password
    }
}

package org.kendar.llm.web.sql;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Makes the JDBC driver of the url available. SQLite is bundled; the MySQL (GPL) and PostgreSQL drivers are not. They
 * are taken from the class path when there, else from the one jar named by the manifest {@code db/drivers/<db>.manifest}
 * (pinned version and SHA-256): in the release {@code lib-ext/}, in a source checkout
 * {@code .downloaded-libs/<db>/<version>/}, or {@code jllm.sql.driver-dir}. The jar is verified against the manifest,
 * loaded by a class loader of its own and registered through a shim. When it is missing the start fails naming the
 * script that fetches it ({@code fetch-sql-driver}).
 */
public final class DriverLoader {

    /** What a driver manifest pins. */
    record Pin(String db, String version, String sha256, String jar) {
    }

    private static final List<URLClassLoader> LOADERS = new ArrayList<>();

    private DriverLoader() {
    }

    /** @throws IllegalStateException when the driver of the url is not available (the message names the script) */
    public static void ensure(SqlConfig config) {
        Dialect d = config.dialect();
        if (d == Dialect.SQLITE) return;
        Pin pin = pin(d);
        List<Path> dirs = config.driverDir() != null ? List.of(config.driverDir()) : defaultDirs(pin, appDirs());
        ensure(config.jdbcUrl(), dirs, pin);
    }

    /** The folders searched without {@code jllm.sql.driver-dir}: per base, {@code lib-ext/} then {@code .downloaded-libs/<db>/<version>/}. */
    static List<Path> defaultDirs(Pin pin, List<Path> bases) {
        java.util.Set<Path> dirs = new java.util.LinkedHashSet<>();
        for (Path base : bases) {
            dirs.add(base.resolve("lib-ext").toAbsolutePath().normalize());
            dirs.add(base.resolve(".downloaded-libs").resolve(pin.db()).resolve(pin.version()).toAbsolutePath().normalize());
        }
        return List.copyOf(dirs);
    }

    /** The working directory, then the folder of the running jar (its parent when the jar sits in {@code lib-ext/sql}). */
    private static List<Path> appDirs() {
        List<Path> bases = new ArrayList<>();
        bases.add(Path.of("").toAbsolutePath());
        try {
            var src = DriverLoader.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null && "file".equals(src.getLocation().getProtocol())) {
                Path loc = Path.of(src.getLocation().toURI());
                Path dir = Files.isDirectory(loc) ? null : loc.getParent(); // a class folder (tests) has no app dir
                if (dir != null) {
                    bases.add(dir);
                    if (dir.getFileName() != null && dir.getFileName().toString().equals("sql") && dir.getParent() != null
                            && dir.getParent().getFileName() != null
                            && dir.getParent().getFileName().toString().equals("lib-ext")) {
                        bases.add(dir.getParent().getParent());
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // the working directory alone
        }
        return bases;
    }

    static synchronized void ensure(String url, List<Path> dirs, Pin pin) {
        if (available(url)) return;
        for (Path dir : dirs) {
            Path jar = dir.resolve(pin.jar());
            if (!Files.isRegularFile(jar)) continue;
            String actual = sha256(jar);
            if (!actual.equalsIgnoreCase(pin.sha256())) {
                throw new IllegalStateException("Checksum mismatch for " + jar + " (expected " + pin.sha256()
                        + ", got " + actual + "): refused. Delete it and run " + script(pin));
            }
            load(url, jar);
            if (available(url)) return;
        }
        throw new IllegalStateException("No JDBC driver for " + url + ": " + pin.jar() + " is not in "
                + dirs + ". Run " + script(pin));
    }

    private static String script(Dialect d) {
        return "scripts/fetch-sql-driver.sh " + d.dir() + " (Windows: scripts\\fetch-sql-driver.ps1 " + d.dir()
                + "; release zip: fetch-sql-driver.sh | .cmd | .ps1 " + d.dir() + ")";
    }

    private static String script(Pin pin) {
        return script(Dialect.valueOf(pin.db().toUpperCase(java.util.Locale.ROOT)));
    }

    /** The pinned driver of a dialect, from the manifest in the jar. */
    static Pin pin(Dialect d) {
        String name = d.dir();
        try (InputStream in = DriverLoader.class.getResourceAsStream("/db/drivers/" + name + ".manifest")) {
            if (in == null) throw new IllegalStateException("No driver manifest for " + name);
            return parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the driver manifest of " + name, e);
        }
    }

    static Pin parse(String db, String manifest) {
        String version = null;
        String sha = null;
        String jar = null;
        for (String line : manifest.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("version=")) version = l.substring(8);
            else if (l.matches("[0-9a-f]{64}\\s+\\S+")) {
                String[] p = l.split("\\s+", 2);
                sha = p[0];
                jar = p[1].substring(p[1].lastIndexOf('/') + 1);
            }
        }
        if (version == null || sha == null) throw new IllegalStateException("Invalid driver manifest of " + db);
        return new Pin(db, version, sha, jar);
    }

    private static boolean available(String url) {
        try {
            DriverManager.getDriver(url);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void load(String url, Path jar) {
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, DriverLoader.class.getClassLoader());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + jar + ": " + e.getMessage(), e);
        }
        boolean found = false;
        try {
            for (Driver d : ServiceLoader.load(Driver.class, loader)) {
                if (d.acceptsURL(url)) {
                    DriverManager.registerDriver(new Shim(d));
                    found = true;
                }
            }
        } catch (SQLException | ServiceConfigurationError e) {
            close(loader);
            throw new IllegalStateException("Cannot load the JDBC driver " + jar + ": " + e.getMessage(), e);
        }
        if (found) LOADERS.add(loader);
        else close(loader);
    }

    private static void close(URLClassLoader l) {
        try {
            l.close();
        } catch (IOException ignored) {
            // nothing to release
        }
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** DriverManager only uses drivers visible to the caller's class loader: this one is ours. */
    private record Shim(Driver d) implements Driver {
        @Override public Connection connect(String url, Properties info) throws SQLException { return d.connect(url, info); }
        @Override public boolean acceptsURL(String url) throws SQLException { return d.acceptsURL(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return d.getPropertyInfo(url, info);
        }
        @Override public int getMajorVersion() { return d.getMajorVersion(); }
        @Override public int getMinorVersion() { return d.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return d.jdbcCompliant(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return d.getParentLogger(); }
    }
}

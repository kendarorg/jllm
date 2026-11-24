package org.kendar.llm.web.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Driver folders, scheme to driver selection, checksum and manifests: no network, no server. */
class DriverLoaderTest {

    /** Drivers for jdbc:jllmfakeA: ... C: found only through a service file inside a temp jar. */
    public abstract static class Fake implements java.sql.Driver {
        private final String prefix;

        Fake(String prefix) {
            this.prefix = prefix;
        }

        @Override public java.sql.Connection connect(String url, java.util.Properties info) { return null; }
        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith(prefix); }
        @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) { return new java.sql.DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { throw new java.sql.SQLFeatureNotSupportedException(); }
    }

    public static class FakeA extends Fake { public FakeA() { super("jdbc:jllmfakea:"); } }
    public static class FakeB extends Fake { public FakeB() { super("jdbc:jllmfakeb:"); } }
    public static class FakeC extends Fake { public FakeC() { super("jdbc:jllmfakec:"); } }

    /** A loaded jar stays open (Windows cannot delete it): temp folders of those tests are left to the OS. */
    private static Path tempDir() throws IOException {
        Path d = Files.createTempDirectory("jllm-drv");
        d.toFile().deleteOnExit();
        return d;
    }

    private static void jarWithService(Path jar, Class<?> driver) throws IOException {
        try (OutputStream o = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("META-INF/services/java.sql.Driver"));
            z.write((driver.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    private static DriverLoader.Pin pin(String jar, Path file) {
        return new DriverLoader.Pin("mysql", "1", file == null ? "0".repeat(64) : DriverLoader.sha256(file), jar);
    }

    @Test
    void loadsTheDriverOfTheUrlFromTheFolder() throws Exception {
        Path dir = tempDir();
        Path jar = dir.resolve("fake.jar");
        jarWithService(jar, FakeA.class);
        String url = "jdbc:jllmfakea:x";
        assertThrows(SQLException.class, () -> DriverManager.getDriver(url));
        DriverLoader.ensure(url, List.of(dir.resolve("none"), dir), pin("fake.jar", jar));
        assertNotNull(DriverManager.getDriver(url));
    }

    @Test
    void otherDriversInTheFolderAreIgnored() throws Exception {
        Path dir = tempDir();
        Files.writeString(dir.resolve("postgresql-0.jar"), "not a jar");
        Path jar = dir.resolve("fake2.jar");
        jarWithService(jar, FakeB.class);
        DriverLoader.ensure("jdbc:jllmfakeb:x", List.of(dir), pin("fake2.jar", jar)); // no error from the other file
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DriverLoader.ensure(
                "jdbc:jllmnone:b", List.of(dir), pin("absent.jar", null)));
        assertTrue(e.getMessage().contains("absent.jar"), e.getMessage());
    }

    @Test
    void missingDriverNamesTheScript(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DriverLoader.ensure("jdbc:jllmnone:a", List.of(dir), pin("x.jar", null)));
        assertTrue(e.getMessage().contains("fetch-sql-driver"), e.getMessage());
        assertTrue(e.getMessage().contains("mysql"), e.getMessage());
        assertTrue(e.getMessage().contains("x.jar"), e.getMessage());
    }

    @Test
    void checksumMismatchIsRefused(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("fake3.jar");
        jarWithService(jar, FakeC.class);
        DriverLoader.Pin wrong = new DriverLoader.Pin("mysql", "1", "0".repeat(64), "fake3.jar");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DriverLoader.ensure("jdbc:jllmfakec:x", List.of(dir), wrong));
        assertTrue(e.getMessage().contains("Checksum mismatch"), e.getMessage());
        assertTrue(e.getMessage().contains("fetch-sql-driver"), e.getMessage());
    }

    @Test
    void sqliteNeedsNoDriverFolder() {
        DriverLoader.ensure(SqlConfig.sqliteMemory());
    }

    @Test
    void manifestsPinTheVersionsOfTheRootPom() throws IOException {
        String pom = Files.readString(Path.of("..", "..", "pom.xml"));
        for (var e : java.util.Map.of(Dialect.MYSQL, "mysql.version", Dialect.POSTGRES, "postgresql.version").entrySet()) {
            Matcher m = Pattern.compile("<" + Pattern.quote(e.getValue()) + ">(.*?)</").matcher(pom);
            assertTrue(m.find(), e.getValue());
            DriverLoader.Pin p = DriverLoader.pin(e.getKey());
            assertEquals(m.group(1), p.version(), e.getKey().toString());
            assertTrue(p.jar().endsWith(p.version() + ".jar"), p.jar());
            assertEquals(64, p.sha256().length());
        }
        assertEquals("mysql-connector-j-" + DriverLoader.pin(Dialect.MYSQL).version() + ".jar",
                DriverLoader.pin(Dialect.MYSQL).jar());
        assertEquals("postgresql-" + DriverLoader.pin(Dialect.POSTGRES).version() + ".jar",
                DriverLoader.pin(Dialect.POSTGRES).jar());
    }

    @Test
    void configReadsTheDriverDir() {
        SqlConfig c = SqlConfig.fromProperties(k -> switch (k) {
            case "jllm.sql.url" -> "jdbc:postgresql://h/db";
            case "jllm.sql.driver-dir" -> "drv";
            default -> null;
        }, Path.of("d"));
        assertEquals(Dialect.POSTGRES, c.dialect());
        assertEquals(Path.of("drv").toAbsolutePath().normalize(), c.driverDir());
        assertNull(SqlConfig.fromProperties(k -> null, Path.of("d")).driverDir());
    }

    @Test
    void defaultDirsCoverEveryBaseWithoutDuplicates() {
        DriverLoader.Pin pin = new DriverLoader.Pin("postgres", "1.2.3", "0".repeat(64), "postgresql-1.2.3.jar");
        Path a = Path.of("a").toAbsolutePath();
        Path b = Path.of("b").toAbsolutePath();
        List<Path> dirs = DriverLoader.defaultDirs(pin, List.of(a, b, a));
        assertEquals(List.of(a.resolve("lib-ext"), a.resolve(".downloaded-libs").resolve("postgres").resolve("1.2.3"),
                b.resolve("lib-ext"), b.resolve(".downloaded-libs").resolve("postgres").resolve("1.2.3")), dirs);
    }
}

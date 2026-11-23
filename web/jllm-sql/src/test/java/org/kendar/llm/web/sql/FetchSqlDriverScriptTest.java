package org.kendar.llm.web.sql;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * scripts/fetch-sql-driver.sh and .ps1 in release layout (lib-ext/manifests/postgres.manifest next to the script) against
 * a file:// "repository": a matching SHA-256 installs the jar, a mismatch fails and leaves the folder as it was.
 * Skipped where the interpreter is missing (bash with curl and sha256sum or shasum; Windows PowerShell for the .ps1).
 */
class FetchSqlDriverScriptTest {

    private static final Path SCRIPTS = Path.of("../../scripts").toAbsolutePath().normalize();
    private static final String JAR_PATH = "org/postgresql/postgresql/9.9.9/postgresql-9.9.9.jar";

    private record Run(int exit, String out) {
    }

    private static Run exec(Path cwd, List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("timeout: " + cmd);
        }
        return new Run(p.exitValue(), out);
    }

    private static boolean works(Path cwd, List<String> cmd) {
        try {
            return exec(cwd, cmd).exit() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** A release folder: the script, the manifest pinning {@code sum} and a repository holding {@code content}. */
    private static Path layout(Path root, String script, String sum, byte[] content) throws IOException {
        Path rel = Files.createDirectories(root.resolve("release"));
        Files.copy(SCRIPTS.resolve(script), rel.resolve(script));
        Files.createDirectories(rel.resolve("lib-ext/manifests"));
        Files.writeString(rel.resolve("lib-ext/manifests/postgres.manifest"),
                "version=9.9.9\nlicense=BSD-2-Clause\n" + sum + "  " + JAR_PATH + "\n");
        Path jar = root.resolve("repo").resolve(JAR_PATH);
        Files.createDirectories(jar.getParent());
        Files.write(jar, content);
        Files.write(rel.resolve("lib-ext/postgresql-1.0.0.jar"), new byte[]{1});
        return rel;
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String baseUrl(Path root) {
        String u = root.resolve("repo").toUri().toString();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static List<String> bashCmd(String... args) {
        List<String> c = new ArrayList<>(List.of("bash", "fetch-sql-driver.sh"));
        c.addAll(List.of(args));
        return c;
    }

    private static void assumeBash(Path cwd) {
        Assumptions.assumeTrue(works(cwd, List.of("bash", "-c", "command -v curl && (command -v sha256sum || command -v shasum)")),
                "bash with curl and sha256sum/shasum not available");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Assumptions.assumeTrue(works(cwd, List.of("bash", "-c", "[ \"$(uname -o)\" = Msys ]")),
                    "bash on Windows is not a Git Bash (e.g. WSL cannot read these paths)");
        }
    }

    @Test
    void shellScriptInstallsMatchingJarAndRefusesMismatch(@TempDir Path tmp) throws Exception {
        byte[] content = "fake driver".getBytes(StandardCharsets.UTF_8);
        Path ok = layout(tmp.resolve("ok"), "fetch-sql-driver.sh", sha(content), content);
        assumeBash(ok);
        Run r = exec(ok, bashCmd("postgres", "--base-url", baseUrl(tmp.resolve("ok"))));
        assertEquals(0, r.exit(), r.out());
        assertArrayEquals(content, Files.readAllBytes(ok.resolve("lib-ext/postgresql-9.9.9.jar")));
        assertFalse(Files.exists(ok.resolve("lib-ext/postgresql-1.0.0.jar")), "the older driver is replaced");
        assertTrue(Files.isRegularFile(ok.resolve("lib-ext/postgres.manifest")));

        Path bad = layout(tmp.resolve("bad"), "fetch-sql-driver.sh", sha(content), "tampered".getBytes(StandardCharsets.UTF_8));
        r = exec(bad, bashCmd("postgres", "--base-url", baseUrl(tmp.resolve("bad"))));
        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("SHA-256 mismatch"), r.out());
        assertFalse(Files.exists(bad.resolve("lib-ext/postgresql-9.9.9.jar")));
        assertFalse(Files.exists(bad.resolve("lib-ext/postgresql-9.9.9.jar.part")));
        assertTrue(Files.exists(bad.resolve("lib-ext/postgresql-1.0.0.jar")), "nothing changed");
    }

    private static List<String> psCmd(Path rel, Path root) {
        return List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                rel.resolve("fetch-sql-driver.ps1").toString(), "postgres", "-BaseUrl", baseUrl(root));
    }

    @Test
    void powerShellScriptInstallsMatchingJarAndRefusesMismatch(@TempDir Path tmp) throws Exception {
        // Windows PowerShell only: Invoke-WebRequest of PowerShell 7 does not read file:// urls
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "Windows PowerShell only");
        byte[] content = "fake driver".getBytes(StandardCharsets.UTF_8);
        Path ok = layout(tmp.resolve("ok"), "fetch-sql-driver.ps1", sha(content), content);
        Assumptions.assumeTrue(works(ok, List.of("powershell", "-NoProfile", "-Command", "exit 0")), "powershell not available");
        Run r = exec(ok, psCmd(ok, tmp.resolve("ok")));
        assertEquals(0, r.exit(), r.out());
        assertArrayEquals(content, Files.readAllBytes(ok.resolve("lib-ext/postgresql-9.9.9.jar")));
        assertFalse(Files.exists(ok.resolve("lib-ext/postgresql-1.0.0.jar")));

        Path bad = layout(tmp.resolve("bad"), "fetch-sql-driver.ps1", sha(content), "tampered".getBytes(StandardCharsets.UTF_8));
        r = exec(bad, psCmd(bad, tmp.resolve("bad")));
        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("SHA-256 mismatch"), r.out());
        assertFalse(Files.exists(bad.resolve("lib-ext/postgresql-9.9.9.jar")));
        assertFalse(Files.exists(bad.resolve("lib-ext/postgresql-9.9.9.jar.part")));
        assertTrue(Files.exists(bad.resolve("lib-ext/postgresql-1.0.0.jar")));
    }
}

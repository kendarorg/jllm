package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.identity.RoleSandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoutedSandboxExecutorTest {

    @TempDir
    Path tmp;

    private RoutedSandboxExecutor routed(java.util.function.Function<String, RoleSandbox> of) {
        var layout = new ChatDirLayout(tmp);
        return new RoutedSandboxExecutor(of, Map.of(
                SandboxKind.NONE, new DisabledSandboxExecutor(layout),
                SandboxKind.SHARED_LOCAL, new LocalSandboxExecutor(layout)), layout);
    }

    @Test
    void roleWithoutSandboxGetsExceptionsNotAShell() {
        var box = routed(chat -> RoleSandbox.NONE);
        assertThrows(SandboxDisabledException.class, () -> box.execute("c1", "echo hi", Duration.ofSeconds(5)));
        var files = box.files("c1", "main");
        assertNotNull(files);
        assertThrows(SandboxDisabledException.class, () -> files.read(files.readTarget("a.txt"), 0, -1));
        assertThrows(SandboxDisabledException.class, () -> files.write(files.writeTarget("a.txt", false), new byte[0], false));
        assertThrows(SandboxDisabledException.class, () -> files.glob("**/*"));
        assertDoesNotThrow(() -> box.workDir("c1")); // the chat itself (history) still works
    }

    @Test
    void localKindUsesTheHostRunner() throws IOException {
        var box = routed(chat -> RoleSandbox.of("shared-local", null, null, false));
        assertInstanceOf(LocalSandboxExecutor.class, box.of("c1"));
        assertNull(box.files("c1", "main")); // host file system
    }

    @Test
    void failingOrUnknownLookupIsNone() {
        var failing = routed(chat -> {
            throw new IllegalStateException("db down");
        });
        assertInstanceOf(DisabledSandboxExecutor.class, failing.of("c1"));
        var unknown = routed(chat -> null);
        assertInstanceOf(DisabledSandboxExecutor.class, unknown.of("c1"));
        assertThrows(SandboxDisabledException.class, () -> unknown.execute("c1", "x", Duration.ofSeconds(1)));
    }

    @Test
    void kindWithoutARunnerBehavesAsNone() {
        var box = routed(chat -> RoleSandbox.of("docker-open", null, null, false)); // no docker runner registered
        assertInstanceOf(DisabledSandboxExecutor.class, box.of("c1"));
    }

    @Test
    void kindParsing() {
        assertEquals(SandboxKind.NONE, SandboxKind.parse(null));
        assertEquals(SandboxKind.DOCKER_ISOLATED, SandboxKind.parse(" Docker-Isolated "));
        assertEquals(SandboxKind.DOCKER_OPEN, SandboxKind.parse("docker_open"));
        assertEquals("hardened", RoleSandbox.of("docker-isolated", null, null, false).profile());
        assertEquals("standard", RoleSandbox.of("docker-open", null, null, false).profile());
        assertNull(RoleSandbox.of("shared-local", "user", "none", false).scope()); // container fields only apply to docker kinds
        assertNull(RoleSandbox.of("docker-open", null, "none", false).network()); // network only applies to isolated
        assertThrows(IllegalArgumentException.class, () -> SandboxKind.parse("vm"));
        assertThrows(IllegalArgumentException.class, () -> SandboxKind.parse("docker"));
        assertThrows(IllegalArgumentException.class, () -> RoleSandbox.of("docker-open", "weird", null, false));
    }
}

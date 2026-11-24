package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Helpers about where the docker daemon is. */
public final class DockerHosts {

    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);

    private DockerHosts() {
    }

    /**
     * The docker endpoint of the current context ({@code docker context inspect}; DOCKER_CONTEXT is honoured by the
     * CLI): "" when it has none, null when it cannot be read (no docker, old CLI), which callers treat as local.
     */
    static String contextHost(DockerCommandRunner runner, String binary) {
        try {
            DockerCommandRunner.Result r = runner.run(
                    List.of(binary, "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"), CONTROL_TIMEOUT);
            if (r.exitCode() != 0 || r.timedOut()) return null;
            return r.stdout().strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) { // boundary: the context is optional
            return null;
        }
    }

    /**
     * True when {@code dockerHost} (a DOCKER_HOST value) points at a daemon that is not on this machine: a remote
     * daemon cannot see host directories, so a bind mount would silently use the wrong files. {@code tcp://localhost}
     * and {@code tcp://127.0.0.1} count as local.
     */
    public static boolean isRemote(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank() || dockerHost.startsWith("unix:") || dockerHost.startsWith("npipe:")) {
            return false;
        }
        String h = dockerHost.toLowerCase();
        return !(h.contains("://localhost") || h.contains("://127.0.0.1") || h.contains("://[::1]"));
    }

    /** Absolute, forward-slash host path: a relative or backslash path makes docker misparse "-v src:dst" on Windows. */
    public static String mountSource(Path dir) {
        return dir.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}

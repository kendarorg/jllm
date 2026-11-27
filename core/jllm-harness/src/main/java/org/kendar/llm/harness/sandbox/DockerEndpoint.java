package org.kendar.llm.harness.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The docker CLI of one docker host: the main one (the CLI's own DOCKER_HOST or context) or the override of a user
 * ({@code -H <host>} is put in front of every command).
 */
final class DockerEndpoint {

    static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HarnessConfig config;
    private final DockerCommandRunner runner;
    private final String hostSpec;
    private final boolean explicit;
    private volatile boolean verified;
    /** The endpoint of the current docker context when DOCKER_HOST is unset; null until read. */
    private volatile String contextHost;

    /**
     * @param hostSpec DOCKER_HOST of this process (the main endpoint) or the per-user host
     * @param explicit true for a per-user host: passed to docker with {@code -H}
     */
    DockerEndpoint(HarnessConfig config, DockerCommandRunner runner, String hostSpec, boolean explicit) {
        this.config = config;
        this.runner = runner;
        this.hostSpec = hostSpec;
        this.explicit = explicit && hostSpec != null && !hostSpec.isBlank();
    }

    /** Identifies the host in a backup record. */
    String key() {
        return explicit ? hostSpec.strip() : "default";
    }

    HarnessConfig.Docker settings() {
        return config.sandbox().docker();
    }

    /** DOCKER_HOST, or when it is unset the endpoint of the current docker context (cached once read). */
    String effectiveHost() {
        if (hostSpec != null && !hostSpec.isBlank()) return hostSpec;
        String h = contextHost;
        if (h == null) {
            h = DockerHosts.contextHost(runner, settings().binary());
            if (h != null) contextHost = h;
        }
        return h == null || h.isEmpty() ? null : h;
    }

    boolean remoteDaemon() {
        return DockerHosts.isRemote(effectiveHost());
    }

    /** How the daemon was found, for messages. */
    String describe() {
        return hostSpec != null && !hostSpec.isBlank() ? (explicit ? "the docker host " : "DOCKER_HOST=") + hostSpec
                : "the docker context endpoint " + effectiveHost();
    }

    /** Where this machine reaches the ports published by this docker host: 127.0.0.1, or the remote docker host. */
    String daemonHost() {
        String set = settings().remoteHost();
        if (set != null && !set.isBlank()) return set.strip();
        String effective = settings().remote() ? effectiveHost() : null;
        if (settings().remote() && DockerHosts.isRemote(effective)) {
            String h = java.net.URI.create(effective.strip()).getHost();
            if (h != null) return h;
            throw new SandboxUnavailableException("Cannot read a host from the docker endpoint " + effective
                    + "; set sandbox.docker.remoteHost.");
        }
        return "127.0.0.1";
    }

    List<String> argv(String... args) {
        List<String> a = new ArrayList<>();
        a.add(settings().binary());
        if (explicit) a.addAll(List.of("-H", hostSpec.strip()));
        a.addAll(List.of(args));
        return a;
    }

    DockerCommandRunner.Result run(String... args) throws IOException {
        try {
            return runner.run(argv(args), CONTROL_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Runs a docker command that must succeed; returns its stdout. */
    String docker(String... args) throws IOException {
        DockerCommandRunner.Result r = run(args);
        if (r.exitCode() != 0 || r.timedOut()) {
            throw new IOException("docker " + args[0] + " failed: " + (r.stderr().isBlank() ? r.stdout() : r.stderr()).strip());
        }
        return r.stdout();
    }

    /** A long-running {@code docker} process (the exec tunnel). */
    Process start(String... args) throws IOException {
        return runner.start(argv(args));
    }

    /** Inspect document of a container, or null when it does not exist. */
    JsonNode inspect(String name) throws IOException {
        DockerCommandRunner.Result r = run("inspect", name);
        if (r.exitCode() != 0) return null;
        JsonNode n = JSON.readTree(r.stdout());
        return n.isArray() && !n.isEmpty() ? n.get(0) : null;
    }

    boolean imageExists(String image) {
        try {
            return run("image", "inspect", "--format", "{{.Id}}", image).exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Removes the container and the network made for it ({@link #ensureNetwork}); either may already be gone. */
    void removeContainer(String name) {
        try {
            run("rm", "-f", "-v", name);
            run("network", "rm", name);
        } catch (IOException ignored) {
            // already gone
        }
    }

    /**
     * A bridge network of its own for the container {@code name} (same name): standard-profile containers have the
     * network, and on the default bridge every container of every user could reach every other one.
     */
    void ensureNetwork(String name, String owner) throws IOException {
        if (run("network", "inspect", name).exitCode() == 0) return;
        docker("network", "create", "--label", "jllm=1", "--label", "jllm.owner=" + owner, name);
    }

    void removeVolume(String name) {
        try {
            run("volume", "rm", "-f", name);
        } catch (IOException ignored) {
            // already gone
        }
    }

    void removeImage(String image) {
        try {
            run("rmi", "-f", image);
        } catch (IOException ignored) {
            // already gone
        }
    }

    int hostPort(String name) throws IOException {
        String out = docker("port", name, RemoteExecSandboxExecutor.DAEMON_PORT + "/tcp").strip();
        for (String line : out.split("\\R")) {
            int colon = line.lastIndexOf(':');
            if (colon > 0) return Integer.parseInt(line.substring(colon + 1).strip());
        }
        throw new IOException("No published port for container " + name + ": " + out);
    }

    String logsTail(String name) {
        try {
            DockerCommandRunner.Result r = run("logs", "--tail", "20", name);
            return (r.stdout() + r.stderr()).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** @throws SandboxUnavailableException when docker cannot be used; the agent loop offers to run locally instead */
    void require() throws IOException {
        HarnessConfig.Docker d = settings();
        if (!d.remote() && remoteDaemon()) {
            throw new SandboxUnavailableException("Docker daemon mode needs a local docker daemon: " + describe()
                    + " is remote (its published port and bind mounts would not be reachable). "
                    + "Allow it with sandbox.docker.remote (--docker-remote): the workspace is then uploaded and backed up.");
        }
        if (verified) return;
        String bin = d.binary();
        try {
            DockerCommandRunner.Result r = runner.run(argv("version", "--format", "{{.Server.Version}}"), CONTROL_TIMEOUT);
            if (r.exitCode() != 0 || r.timedOut()) {
                throw new SandboxUnavailableException("Docker sandbox unavailable: '" + bin
                        + " version' failed (is the docker daemon running?): " + r.stderr().strip());
            }
            verified = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (IOException e) {
            throw new SandboxUnavailableException("Docker sandbox unavailable: docker binary '" + bin
                    + "' not found or not executable (" + e.getMessage() + ")");
        }
    }
}

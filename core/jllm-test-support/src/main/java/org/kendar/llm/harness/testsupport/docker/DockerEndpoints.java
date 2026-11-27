package org.kendar.llm.harness.testsupport.docker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where tests look for a docker daemon, in one place. Order:
 * <ol>
 *   <li>{@code DOCKER_HOST}, when set;</li>
 *   <li>{@value #LOCALHOST_TCP} (for instance dockerd in WSL listening on localhost);</li>
 *   <li>the unix socket {@value #UNIX_SOCKET}, on Linux and macOS only.</li>
 * </ol>
 * Testcontainers follows the same order through {@link LocalhostTcpDockerStrategy}; plain tests use
 * {@link #firstReachable()}. Nothing here starts docker or talks to it: an endpoint is reachable when something
 * accepts a connection there.
 */
public final class DockerEndpoints {

    public static final String LOCALHOST_TCP = "tcp://localhost:2375";
    public static final String UNIX_SOCKET = "unix:///var/run/docker.sock";
    private static final int CONNECT_TIMEOUT_MS = 500;

    /** One place to try. {@code source} says where it came from: {@code DOCKER_HOST}, {@code localhost}, {@code socket}. */
    public record Endpoint(String uri, String source) {
    }

    private DockerEndpoints() {
    }

    /** The places to try on this machine, in order, whether or not anything answers there. */
    public static List<Endpoint> candidates() {
        return candidates(System::getenv, isUnix());
    }

    /** As {@link #candidates()}, with the environment and the platform given (for tests). */
    static List<Endpoint> candidates(Function<String, String> env, boolean unix) {
        List<Endpoint> all = new ArrayList<>();
        String host = env.apply("DOCKER_HOST");
        if (host != null && !host.isBlank()) all.add(new Endpoint(host.strip(), "DOCKER_HOST"));
        all.add(new Endpoint(LOCALHOST_TCP, "localhost"));
        if (unix) all.add(new Endpoint(UNIX_SOCKET, "socket"));
        return List.copyOf(all);
    }

    /** The first candidate that accepts a connection. */
    public static Optional<Endpoint> firstReachable() {
        return candidates().stream().filter(e -> reachable(e.uri())).findFirst();
    }

    /**
     * True when something accepts a connection at {@code uri}. {@code tcp://} and {@code unix://} are probed; other
     * schemes ({@code ssh://}, {@code npipe://}) cannot be probed from here and count as reachable, the daemon client
     * decides.
     */
    public static boolean reachable(String uri) {
        URI u;
        try {
            u = URI.create(uri.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "tcp", "http", "https" -> tcpReachable(u);
            case "unix" -> unixReachable(u.getPath());
            default -> !scheme.isEmpty();
        };
    }

    private static boolean tcpReachable(URI u) {
        if (u.getHost() == null) return false;
        int port = u.getPort() > 0 ? u.getPort() : 2375;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) { // boundary: unreachable is an answer, not an error
            return false;
        }
    }

    private static boolean unixReachable(String path) {
        if (path == null || path.isBlank() || !Files.exists(Path.of(path))) return false;
        try (SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            c.connect(UnixDomainSocketAddress.of(path));
            return true;
        } catch (IOException | RuntimeException e) { // boundary: no permission or nobody listening
            return false;
        }
    }

    private static boolean isUnix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return !os.contains("win");
    }
}

package org.kendar.llm.harness.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reaches the daemon of a container that has no network ({@code --network none}): docker cannot publish a port for
 * such a container, but {@code docker exec -i} still works. A loopback {@link ServerSocket} on this machine accepts
 * the daemon client's connections; each one starts a {@code docker exec -i <container> python -c <relay>} process
 * whose relay connects to the daemon on the container's loopback and pipes the bytes both ways.
 */
final class ExecTunnel implements AutoCloseable {

    /** Starts the process that relays one connection (a {@code docker exec -i} of {@link #relayCommand}). */
    @FunctionalInterface
    interface Spawner {
        Process start() throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(ExecTunnel.class);

    private static final String RELAY = """
            import socket,sys,os,select
            s=socket.create_connection(("127.0.0.1",%d))
            i=sys.stdin.fileno();o=sys.stdout.fileno()
            e=False
            while True:
                r,_,_=select.select([s]+([] if e else [i]),[],[])
                if s in r:
                    d=s.recv(65536)
                    if not d: break
                    while d:
                        n=os.write(o,d);d=d[n:]
                if i in r:
                    d=os.read(i,65536)
                    if not d:
                        e=True;s.shutdown(socket.SHUT_WR)
                    else:
                        s.sendall(d)
            """;

    /** The words that follow {@code docker exec -i <container>}: python running the relay to {@code port}. */
    static List<String> relayCommand(int port) {
        String b64 = Base64.getEncoder().encodeToString(RELAY.formatted(port).getBytes(StandardCharsets.UTF_8));
        return List.of("python", "-c", "import base64;exec(base64.b64decode('" + b64 + "'))");
    }

    private final ServerSocket server;
    private final Spawner spawner;
    private final Set<Process> live = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ExecTunnel(Spawner spawner) throws IOException {
        this.spawner = spawner;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "jllm-exec-tunnel");
        t.setDaemon(true);
        t.start();
    }

    /** The loopback port the daemon client connects to. */
    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket c = server.accept();
                sockets.add(c);
                Process p;
                try {
                    p = spawner.start();
                } catch (IOException | RuntimeException e) { // boundary: the client sees a refused connection
                    log.debug("Tunnel process failed: {}", e.toString());
                    close(c);
                    continue;
                }
                live.add(p);
                pump(c.getInputStream(), p.getOutputStream(), () -> closeStdin(p), "in");
                pump(p.getInputStream(), c.getOutputStream(), () -> {
                    close(c);
                    sockets.remove(c);
                    p.destroy();
                    live.remove(p);
                }, "out");
            } catch (IOException | RuntimeException e) { // boundary: one failed connection must not stop the tunnel
                if (!closed) log.debug("Tunnel accept failed: {}", e.toString());
            }
        }
    }

    private static void pump(InputStream in, OutputStream out, Runnable done, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // the other side went away
            } finally {
                done.run();
            }
        }, "jllm-exec-tunnel-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void closeStdin(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        for (Process p : live) p.destroy();
        for (Socket s : sockets) close(s);
        live.clear();
        sockets.clear();
    }
}

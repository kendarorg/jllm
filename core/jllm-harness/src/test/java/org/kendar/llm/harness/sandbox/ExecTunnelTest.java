package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecTunnelTest {

    /** A daemon that echoes upper-case, one connection per thread. */
    private static ServerSocket upperCaseServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket c = server.accept();
                    new Thread(() -> {
                        try (c; InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream()) {
                            byte[] b = new byte[1024];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                String s = new String(b, 0, n, StandardCharsets.UTF_8).toUpperCase();
                                out.write(s.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (IOException ignored) {
                            // connection ended
                        }
                    }).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    @Test
    void relayCommandIsOnePythonLineThatDecodesAndRunsTheRelay() {
        List<String> cmd = ExecTunnel.relayCommand(8080);
        assertEquals(List.of("python", "-c"), cmd.subList(0, 2));
        assertTrue(cmd.get(2).matches("import base64;exec\\(base64\\.b64decode\\('[A-Za-z0-9+/=]+'\\)\\)"), cmd.get(2));
        String script = new String(java.util.Base64.getDecoder().decode(
                cmd.get(2).replaceAll(".*decode\\('([^']+)'\\).*", "$1")), StandardCharsets.UTF_8);
        assertTrue(script.contains("create_connection((\"127.0.0.1\",8080))"), script);
    }

    @Test
    void everyConnectionStartsAProcessThatRelaysBothWays() throws Exception {
        try (ServerSocket daemon = upperCaseServer()) {
            AtomicInteger spawned = new AtomicInteger();
            try (ExecTunnel tunnel = new ExecTunnel(() -> {
                spawned.incrementAndGet();
                return new RemoteExecSandboxExecutorTest.SocketProcess(new Socket(InetAddress.getLoopbackAddress(), daemon.getLocalPort()));
            })) {
                for (int i = 0; i < 3; i++) {
                    try (Socket c = new Socket(InetAddress.getLoopbackAddress(), tunnel.port())) {
                        c.setSoTimeout(10_000);
                        c.getOutputStream().write(("hello " + i).getBytes(StandardCharsets.UTF_8));
                        c.getOutputStream().flush();
                        byte[] b = new byte[64];
                        int n = c.getInputStream().read(b);
                        assertEquals("HELLO " + i, new String(b, 0, n, StandardCharsets.UTF_8));
                    }
                }
                assertEquals(3, spawned.get());
                assertTrue(tunnel.port() > 0);
            }
        }
    }

    @Test
    void aFailingProcessClosesTheConnectionAndTheTunnelKeepsServing() throws Exception {
        AtomicInteger n = new AtomicInteger();
        try (ServerSocket daemon = upperCaseServer();
             ExecTunnel tunnel = new ExecTunnel(() -> {
                 if (n.incrementAndGet() == 1) throw new IOException("docker exec failed");
                 return new RemoteExecSandboxExecutorTest.SocketProcess(new Socket(InetAddress.getLoopbackAddress(), daemon.getLocalPort()));
             })) {
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), tunnel.port())) {
                c.setSoTimeout(10_000);
                assertEquals(-1, c.getInputStream().read(), "refused: end of stream");
            }
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), tunnel.port())) {
                c.setSoTimeout(10_000);
                c.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
                byte[] b = new byte[8];
                assertEquals("OK", new String(b, 0, c.getInputStream().read(b), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void closeStopsAcceptingConnections() throws Exception {
        ExecTunnel tunnel = new ExecTunnel(() -> {
            throw new IOException("unused");
        });
        int port = tunnel.port();
        tunnel.close();
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
    }
}

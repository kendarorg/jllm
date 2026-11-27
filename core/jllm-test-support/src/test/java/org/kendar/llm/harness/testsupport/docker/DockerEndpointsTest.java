package org.kendar.llm.harness.testsupport.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DockerEndpointsTest {

    @Test
    void dockerHostComesFirstThenLocalhostThenTheSocket() {
        List<DockerEndpoints.Endpoint> all = DockerEndpoints.candidates(Map.of("DOCKER_HOST", "tcp://box:2376")::get, true);
        assertEquals(List.of("DOCKER_HOST", "localhost", "socket"), all.stream().map(DockerEndpoints.Endpoint::source).toList());
        assertEquals("tcp://box:2376", all.get(0).uri());
        assertEquals(DockerEndpoints.LOCALHOST_TCP, all.get(1).uri());
        assertEquals(DockerEndpoints.UNIX_SOCKET, all.get(2).uri());
    }

    @Test
    void withoutDockerHostOrSocket() {
        assertEquals(List.of("localhost"), DockerEndpoints.candidates(k -> null, false).stream()
                .map(DockerEndpoints.Endpoint::source).toList());
        assertEquals(List.of("localhost", "socket"), DockerEndpoints.candidates(k -> " ", true).stream()
                .map(DockerEndpoints.Endpoint::source).toList());
    }

    @Test
    void tcpIsReachableOnlyWhenSomethingListens() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
            assertTrue(DockerEndpoints.reachable("tcp://127.0.0.1:" + port));
        }
        assertFalse(DockerEndpoints.reachable("tcp://127.0.0.1:" + port));
    }

    @Test
    void badOrMissingEndpointsAreNotReachable() {
        assertFalse(DockerEndpoints.reachable("not a uri"));
        assertFalse(DockerEndpoints.reachable("unix:///definitely/not/here.sock"));
        assertFalse(DockerEndpoints.reachable("tcp:///nohost"));
    }
}

package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerHostsTest {

    @TempDir
    Path tmp;

    @Test
    void localDaemonHostsAreNotRemote() {
        for (String h : new String[]{null, "", "unix:///var/run/docker.sock", "npipe:////./pipe/docker_engine",
                "tcp://localhost:2375", "tcp://127.0.0.1:2375", "tcp://[::1]:2375"}) {
            assertFalse(DockerHosts.isRemote(h), String.valueOf(h));
        }
    }

    @Test
    void otherHostsAreRemote() {
        for (String h : new String[]{"tcp://192.168.1.45:2375", "ssh://user@build-box", "tcp://docker.example.org:2376"}) {
            assertTrue(DockerHosts.isRemote(h), h);
        }
    }

    @Test
    void contextEndpointIsReadOnceAndUnreadableMeansLocal() {
        List<List<String>> calls = new ArrayList<>();
        DockerCommandRunner ctx = (argv, t) -> {
            calls.add(argv);
            return new DockerCommandRunner.Result("ssh://user@build-box\n", "", 0, false);
        };
        assertEquals("ssh://user@build-box", DockerHosts.contextHost(ctx, "docker"));
        assertEquals(List.of("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"), calls.get(0));
        DockerCommandRunner broken = (argv, t) -> {
            throw new IOException("no docker");
        };
        assertNull(DockerHosts.contextHost(broken, "docker"));
        DockerCommandRunner failing = (argv, t) -> new DockerCommandRunner.Result("", "no", 1, false);
        assertNull(DockerHosts.contextHost(failing, "docker"));
    }

    @Test
    void endpointUsesTheContextWhenDockerHostIsUnsetAndDockerHostWinsOtherwise() {
        int[] contextCalls = {0};
        DockerCommandRunner runner = (argv, t) -> {
            if (argv.get(1).equals("context")) {
                contextCalls[0]++;
                return new DockerCommandRunner.Result("tcp://10.0.0.9:2375\n", "", 0, false);
            }
            return new DockerCommandRunner.Result("", "", 0, false);
        };
        HarnessConfig config = HarnessConfig.defaults();
        DockerEndpoint viaContext = new DockerEndpoint(config, runner, null, false);
        assertTrue(viaContext.remoteDaemon());
        assertTrue(viaContext.remoteDaemon());
        assertEquals(1, contextCalls[0], "the context is read once");
        DockerEndpoint viaEnv = new DockerEndpoint(config, runner, "unix:///var/run/docker.sock", false);
        assertFalse(viaEnv.remoteDaemon());
        assertEquals(1, contextCalls[0]);
    }

    @Test
    void overrideEndpointPassesTheHostToDocker() {
        DockerEndpoint ep = new DockerEndpoint(HarnessConfig.defaults(), (a, t) -> null, "tcp://10.0.0.9:2375", true);
        assertEquals(List.of("docker", "-H", "tcp://10.0.0.9:2375", "ps"), ep.argv("ps"));
        assertEquals("tcp://10.0.0.9:2375", ep.key());
        DockerEndpoint main = new DockerEndpoint(HarnessConfig.defaults(), (a, t) -> null, "tcp://x:1", false);
        assertEquals(List.of("docker", "ps"), main.argv("ps"));
        assertEquals("default", main.key());
    }

    @Test
    void mountSourceIsAbsoluteWithForwardSlashes() {
        String s = DockerHosts.mountSource(tmp.resolve("a").resolve("b"));
        assertFalse(s.contains("\\"), s);
        assertTrue(s.endsWith("/a/b"), s);
        assertTrue(Path.of(s).isAbsolute() || s.matches("[A-Za-z]:/.*"), s);
    }

    @Test
    void requireRefusesARemoteDaemonUnlessAllowed() {
        DockerCommandRunner ok = (argv, t) -> new DockerCommandRunner.Result("25.0", "", 0, false);
        DockerEndpoint remote = new DockerEndpoint(HarnessConfig.defaults(), ok, "tcp://192.168.1.45:2375", false);
        SandboxUnavailableException e = assertThrows(SandboxUnavailableException.class, remote::require);
        assertTrue(e.getMessage().contains("--docker-remote"), e.getMessage());
        Duration unused = DockerEndpoint.CONTROL_TIMEOUT;
        assertNotNull(unused);
    }
}

package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;

import static org.junit.jupiter.api.Assertions.*;

class DaemonClientUrlTest {

    @Test
    void loopbackHttpAlwaysAllowed() {
        assertEquals("http://127.0.0.1:1234", DaemonClient.baseUrl("127.0.0.1", 1234, false));
        assertEquals("http://localhost:1", DaemonClient.baseUrl("http://localhost", 1, false));
        assertEquals("http://[::1]:5", DaemonClient.baseUrl("::1", 5, false));
    }

    @Test
    void nonLoopbackHttpRefusedUnlessAllowed() {
        var e = assertThrows(SandboxUnavailableException.class, () -> DaemonClient.baseUrl("10.0.0.5", 80, false));
        assertTrue(e.getMessage().contains("allowInsecure"));
        assertEquals("http://10.0.0.5:80", DaemonClient.baseUrl("10.0.0.5", 80, true));
        assertEquals("http://example.org:9", DaemonClient.baseUrl("http://example.org/", 9, true));
    }

    @Test
    void httpsAlwaysAllowed() {
        assertEquals("https://example.org:443", DaemonClient.baseUrl("https://example.org", 443, false));
    }

    @Test
    void defaultAllowsInsecureAndHttpsClientBuilds() {
        var d = HarnessConfig.Docker.defaults();
        assertTrue(d.allowInsecure());
        assertNotNull(new DaemonClient(d, "https://example.org", 443, "t"));
    }

    @Test
    void missingTruststoreFailsFast() {
        var d = HarnessConfig.defaults().sandbox().docker();
        var bad = new HarnessConfig.Docker(d.binary(), d.image(), d.memory(), d.cpus(), d.pidsLimit(), d.user(),
                d.network(), "daemon", true, "", false, "no-such-store.p12", "");
        assertThrows(SandboxUnavailableException.class, () -> new DaemonClient(bad, "https://example.org", 443, "t"));
    }
}

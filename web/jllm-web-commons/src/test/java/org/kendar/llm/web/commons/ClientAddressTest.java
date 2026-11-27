package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientAddressTest {

    @Test
    void withoutTrustedProxiesTheHeaderIsIgnored() {
        ClientAddress a = ClientAddress.direct();
        assertEquals("203.0.113.5", a.of("203.0.113.5", "1.2.3.4"));
        assertEquals("203.0.113.5", new ClientAddress(List.of()).of("203.0.113.5", "1.2.3.4, 5.6.7.8"));
    }

    @Test
    void aCallerThatIsNotATrustedProxyCannotChooseItsAddress() {
        ClientAddress a = new ClientAddress(List.of("10.0.0.0/8"));
        assertEquals("203.0.113.5", a.of("203.0.113.5", "1.2.3.4"));
    }

    @Test
    void behindATrustedProxyTheForwardedClientIsUsed() {
        ClientAddress a = new ClientAddress(List.of("10.0.0.1"));
        assertEquals("198.51.100.7", a.of("10.0.0.1", "198.51.100.7"));
        assertEquals("10.0.0.1", a.of("10.0.0.1", null), "no header: the proxy itself");
    }

    @Test
    void theHeaderIsReadFromTheRightSoAForgedLeftEntryDoesNotCount() {
        ClientAddress a = new ClientAddress(List.of("10.0.0.0/8", "192.168.1.1"));
        // the client sent "6.6.6.6"; the first proxy appended the real peer; the second proxy is ours
        assertEquals("198.51.100.7", a.of("10.1.1.1", "6.6.6.6, 198.51.100.7, 10.2.2.2"));
        assertEquals("198.51.100.7", a.of("10.1.1.1", "198.51.100.7, 192.168.1.1"));
    }

    @Test
    void garbageInTheHeaderFallsBackToThePeer() {
        ClientAddress a = new ClientAddress(List.of("10.0.0.1"));
        assertEquals("10.0.0.1", a.of("10.0.0.1", "evil.example.com"));
        assertEquals("10.0.0.1", a.of("10.0.0.1", "1.2.3.4, <script>"));
    }

    @Test
    void ipv6AndCidrsWork() {
        ClientAddress a = new ClientAddress(List.of("fd00::/8", "::1"));
        assertEquals("2001:db8::1", a.of("fd00::5", "2001:db8::1"));
        assertEquals("2001:db8::1", a.of("::1", "2001:db8::1"));
        assertEquals("fe80::1", a.of("fe80::1", "2001:db8::1"));
    }

    @Test
    void badEntriesFailAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ClientAddress(List.of("proxy.example.com")));
        assertThrows(IllegalArgumentException.class, () -> new ClientAddress(List.of("10.0.0.0/40")));
        assertThrows(IllegalArgumentException.class, () -> new ClientAddress(List.of("10.0.0.0/x")));
    }
}

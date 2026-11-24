package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddressPolicyTest {

    private static void blocked(AddressPolicy p, String url) {
        assertThrows(IllegalStateException.class, () -> p.check(URI.create(url)), url);
    }

    private static void allowed(AddressPolicy p, String url) {
        assertDoesNotThrow(() -> p.check(URI.create(url)), url);
    }

    @Test
    void openByDefault() {
        AddressPolicy p = new AddressPolicy(false, null);
        assertFalse(p.blocksAnything());
        allowed(p, "http://localhost:8080/");
        allowed(p, "http://169.254.169.254/");
        allowed(p, "http://10.0.0.1/");
    }

    @Test
    void blockLocalHostRefusesLocalRanges() {
        AddressPolicy p = new AddressPolicy(true, List.of());
        blocked(p, "http://127.0.0.1/");
        blocked(p, "http://localhost/");
        blocked(p, "http://[::1]/");
        blocked(p, "http://169.254.169.254/latest/meta-data");
        blocked(p, "http://10.1.2.3/");
        blocked(p, "http://192.168.0.5/");
        blocked(p, "http://172.16.0.1/");
        blocked(p, "http://0.0.0.0/");
        blocked(p, "http://[fd00::1]/");
        blocked(p, "http://[::ffff:127.0.0.1]/");
        allowed(p, "http://93.184.216.34/");
        allowed(p, "http://[2606:4700:4700::1111]/");
    }

    @Test
    void extraAddressesWorkWithoutBlockLocalHost() {
        AddressPolicy p = new AddressPolicy(false, List.of("169.254.169.254", "10.0.0.0/8", "fd00::/8",
                "internal.example", "*.corp.example"));
        blocked(p, "http://169.254.169.254/");
        blocked(p, "http://10.200.3.4/");
        blocked(p, "http://[fd12::1]/");
        blocked(p, "http://internal.example/");
        blocked(p, "http://wiki.corp.example/");
        allowed(p, "http://11.0.0.1/");
        allowed(p, "http://127.0.0.1/");
        allowed(p, "http://corp.example.org/");
    }

    @Test
    void cidrWithPartialByte() {
        AddressPolicy p = new AddressPolicy(false, List.of("172.16.0.0/12"));
        blocked(p, "http://172.31.255.255/");
        allowed(p, "http://172.32.0.1/");
    }

    @Test
    void badEntryFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AddressPolicy(false, List.of("10.0.0.0/abc")));
        assertThrows(IllegalArgumentException.class, () -> new AddressPolicy(false, List.of("10.0.0.0/33")));
    }
}

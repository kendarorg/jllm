package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
        // Names resolve to a public address: only the name rules decide (an unresolvable name is refused).
        AddressPolicy p = new AddressPolicy(false, List.of("169.254.169.254", "10.0.0.0/8", "fd00::/8",
                "internal.example", "*.corp.example"),
                h -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})});
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

    @Test
    void newLocalRangesAreRefused() {
        AddressPolicy p = new AddressPolicy(true, List.of());
        blocked(p, "http://0.1.2.3/");
        blocked(p, "http://100.64.0.1/");
        blocked(p, "http://100.100.100.200/"); // Alibaba metadata
        blocked(p, "http://100.127.255.254/");
        blocked(p, "http://192.0.0.8/");
        blocked(p, "http://198.18.0.1/");
        blocked(p, "http://198.19.255.255/");
        blocked(p, "http://240.0.0.1/");
        blocked(p, "http://255.255.255.255/");
        blocked(p, "http://224.0.0.1/");
        blocked(p, "http://239.255.255.250/");
        blocked(p, "http://[ff02::1]/");
        blocked(p, "http://[fec0::1]/");
        blocked(p, "http://[::]/");
        allowed(p, "http://100.128.0.1/");
        allowed(p, "http://198.20.0.1/");
        allowed(p, "http://192.0.1.1/");
    }

    @Test
    void ipv6FormsCarryingALocalIpv4AreRefused() {
        AddressPolicy p = new AddressPolicy(true, List.of());
        blocked(p, "http://[::ffff:169.254.169.254]/");
        blocked(p, "http://[::127.0.0.1]/");
        blocked(p, "http://[64:ff9b::a9fe:a9fe]/"); // NAT64 of 169.254.169.254
        blocked(p, "http://[2002:a00:1::]/"); // 6to4 of 10.0.0.1
        blocked(p, "http://[2001:0:4136:e378:8000:63bf:80ff:fffe]/"); // Teredo, client 127.0.0.1
        allowed(p, "http://[64:ff9b::5db8:d822]/"); // NAT64 of 93.184.216.34
        allowed(p, "http://[2002:5db8:d822::]/");
    }

    @Test
    void extraCidrsAlsoMatchTheEmbeddedIpv4() {
        AddressPolicy p = new AddressPolicy(false, List.of("169.254.169.254"));
        blocked(p, "http://[64:ff9b::a9fe:a9fe]/");
        blocked(p, "http://[2002:a9fe:a9fe::]/");
    }

    @Test
    void lookupFailureIsRefusedWhenABlockIsActive() {
        AddressPolicy.Lookup failing = h -> {
            throw new UnknownHostException(h);
        };
        for (AddressPolicy p : List.of(new AddressPolicy(true, List.of(), failing),
                new AddressPolicy(false, List.of("10.0.0.0/8"), failing))) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> p.check(URI.create("http://name.jllm.test/")));
            assertTrue(e.getMessage().contains("cannot resolve"), e.getMessage());
        }
        AddressPolicy open = new AddressPolicy(false, List.of(), failing);
        allowed(open, "http://name.jllm.test/");
    }

    @Test
    void approvedAnswersArePinnedUntilReleased() throws Exception {
        assertTrue(GuardedResolverProvider.active(), "the guarded resolver must be the installed provider");
        InetAddress approved = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
        AddressPolicy p = new AddressPolicy(true, List.of(), h -> new InetAddress[]{approved});
        String host = "pinned-" + System.nanoTime() + ".jllm.test";
        assertFalse(GuardedResolverProvider.isPinned(host));
        try (GuardedResolverProvider.Handle pin = p.check(URI.create("http://" + host + "/"))) {
            assertArrayEquals(new InetAddress[]{approved}, stripNames(InetAddress.getAllByName(host)));
            try (GuardedResolverProvider.Handle second = p.check(URI.create("http://" + host.toUpperCase() + "/"))) {
                assertTrue(GuardedResolverProvider.isPinned(host));
            }
            assertTrue(GuardedResolverProvider.isPinned(host), "still held by the first handle");
        }
        assertFalse(GuardedResolverProvider.isPinned(host));
    }

    @Test
    void unpinnedNamesResolveNormally() throws Exception {
        assertTrue(InetAddress.getAllByName("localhost").length > 0);
        assertFalse(GuardedResolverProvider.isPinned("localhost"));
    }

    private static InetAddress[] stripNames(InetAddress[] all) throws UnknownHostException {
        InetAddress[] out = new InetAddress[all.length];
        for (int i = 0; i < all.length; i++) out[i] = InetAddress.getByAddress(all[i].getAddress());
        return out;
    }
}

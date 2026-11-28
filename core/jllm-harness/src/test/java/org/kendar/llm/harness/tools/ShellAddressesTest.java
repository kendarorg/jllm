package org.kendar.llm.harness.tools;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShellAddressesTest {

    /** Resolves every name to a public address, except {@code internal.example} (10.0.0.5) and {@code localhost}. */
    private final AddressPolicy policy = new AddressPolicy(true, List.of("*.blocked.example"), host -> {
        if (host.equals("localhost")) return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
        if (host.equals("internal.example")) return new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 5})};
        return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
    });

    private String blocked(String command) {
        return ShellAddresses.blocked(command, policy);
    }

    @Test
    void metadataAndLoopbackUrlsAreRefused() {
        assertNotNull(blocked("curl http://169.254.169.254/latest/meta-data/"));
        assertNotNull(blocked("curl -s 'http://localhost:8080/admin'"));
        assertNotNull(blocked("wget -qO- \"http://[::1]/x\""));
        assertNotNull(blocked("echo hi && curl http://internal.example/"));
    }

    @Test
    void hostOperandsAndNumericFormsAreRefused() {
        assertNotNull(blocked("curl 127.0.0.1:8080"));
        assertNotNull(blocked("curl 2130706433"));
        assertNotNull(blocked("curl 0x7f000001"));
        assertNotNull(blocked("ssh admin@internal.example"));
        assertNotNull(blocked("scp file.txt user@10.1.2.3:/tmp"));
        assertNotNull(blocked("nc localhost 22"));
        assertNotNull(blocked("curl https://api.blocked.example/v1"));
    }

    @Test
    void publicTargetsAndPlainCommandsPass() {
        assertNull(blocked("curl -o out.txt https://example.com/file"));
        assertNull(blocked("curl example.com"));
        assertNull(blocked("ls -la && cat notes.txt"));
        assertNull(blocked("python3 script.py --input data.csv"));
        assertNull(blocked("git commit -m 'fix config.yml'"));
        assertNull(blocked("echo 1700000000"));
    }

    @Test
    void nothingIsCheckedWithoutABlock() {
        assertNull(ShellAddresses.blocked("curl http://169.254.169.254/", AddressPolicy.OPEN));
    }

    @Test
    void theMessageNamesTheAddressAndForbidsWorkarounds() {
        String m = blocked("curl http://169.254.169.254/");
        assertTrue(m.contains("169.254.169.254") && m.contains("Do not try another way"), m);
    }
}

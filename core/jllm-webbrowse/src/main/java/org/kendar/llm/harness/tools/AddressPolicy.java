package org.kendar.llm.harness.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which addresses {@code web_fetch} and {@code web_download} refuse to reach (SSRF guard), checked on the first URL and
 * on every redirect hop.
 * <ul>
 *   <li>{@code blockLocalHost}: loopback, link-local (cloud metadata 169.254.169.254), site-local, any-local and IPv6
 *       unique-local addresses. Default false: playing locally needs them.</li>
 *   <li>{@code blockExtraAddresses}: more entries to refuse, each an IP, a CIDR ({@code 10.0.0.0/8}), a host name or
 *       {@code *.suffix}. Default none.</li>
 * </ul>
 * The name is resolved here and again by the HTTP client: a DNS answer that changes between the two is not caught.
 */
public final class AddressPolicy {

    /** Nothing blocked. */
    public static final AddressPolicy OPEN = new AddressPolicy(false, List.of());

    private record Cidr(byte[] net, int bits) {
        boolean contains(byte[] a) {
            if (a.length != net.length) return false;
            int full = bits / 8;
            for (int i = 0; i < full; i++) if (a[i] != net[i]) return false;
            int rest = bits % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (net[full] & mask);
        }
    }

    private final boolean blockLocalHost;
    private final List<Cidr> cidrs = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();

    public AddressPolicy(boolean blockLocalHost, List<String> blockExtraAddresses) {
        this.blockLocalHost = blockLocalHost;
        for (String raw : blockExtraAddresses == null ? List.<String>of() : blockExtraAddresses) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.strip();
            Cidr c = parseCidr(e);
            if (c != null) cidrs.add(c);
            else hosts.add(e.toLowerCase(Locale.ROOT));
        }
    }

    public boolean blocksAnything() {
        return blockLocalHost || !cidrs.isEmpty() || !hosts.isEmpty();
    }

    /** @throws IllegalStateException when the host of {@code uri} is, or resolves to, a blocked address */
    public void check(URI uri) {
        if (!blocksAnything()) return;
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("URL has no host: " + uri);
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        for (String b : hosts) {
            if (h.equals(b) || (b.startsWith("*.") && h.endsWith(b.substring(1)))) refuse(host, "listed in blockExtraAddresses");
        }
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            return; // the request fails by itself
        }
        for (InetAddress a : all) {
            if (blockLocalHost && isLocal(a)) refuse(host, "a local address (" + a.getHostAddress() + ", web.blockLocalHost)");
            byte[] raw = a.getAddress();
            for (Cidr c : cidrs) {
                if (c.contains(raw)) refuse(host, "in blockExtraAddresses (" + a.getHostAddress() + ")");
            }
        }
    }

    private static void refuse(String host, String why) {
        throw new IllegalStateException("Access to '" + host + "' is refused: " + why);
    }

    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private static Cidr parseCidr(String e) {
        String addr = e;
        int bits = -1;
        int slash = e.indexOf('/');
        if (slash >= 0) {
            addr = e.substring(0, slash);
            try {
                bits = Integer.parseInt(e.substring(slash + 1).strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Bad blockExtraAddresses entry: " + e);
            }
        }
        if (!looksLikeIp(addr)) {
            if (slash >= 0) throw new IllegalArgumentException("Bad blockExtraAddresses entry: " + e);
            return null; // a host name
        }
        try {
            byte[] net = InetAddress.getByName(addr).getAddress();
            if (bits < 0) bits = net.length * 8;
            if (bits > net.length * 8 || (slash >= 0 && bits < 0)) throw new IllegalArgumentException("Bad blockExtraAddresses entry: " + e);
            return new Cidr(net, bits);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("Bad blockExtraAddresses entry: " + e);
        }
    }

    /** An IPv4 dotted quad or something with a colon (IPv6): never a DNS lookup. */
    private static boolean looksLikeIp(String s) {
        return s.indexOf(':') >= 0 || s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}

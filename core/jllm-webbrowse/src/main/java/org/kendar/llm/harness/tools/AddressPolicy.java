package org.kendar.llm.harness.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which addresses {@code web_fetch} and {@code web_download} refuse to reach (SSRF guard), checked on the first URL and
 * on every redirect hop.
 * <ul>
 *   <li>{@code blockLocalHost}: loopback, link-local (cloud metadata 169.254.169.254), private, any-local, shared
 *       (100.64.0.0/10, CGNAT and some cloud metadata), benchmark, reserved, multicast and broadcast addresses, IPv6
 *       unique-local and site-local. Default false: playing locally needs them.</li>
 *   <li>{@code blockExtraAddresses}: more entries to refuse, each an IP, a CIDR ({@code 10.0.0.0/8}), a host name or
 *       {@code *.suffix}. Default none.</li>
 * </ul>
 * An IPv6 address carrying an IPv4 one (IPv4-mapped, IPv4-compatible, NAT64, 6to4, Teredo) is also tested by its IPv4
 * address. With any block active a name that cannot be resolved is refused (fail closed). The approved answers are
 * pinned for the host name ({@link GuardedResolverProvider}), so the HTTP client connects to an address that was
 * checked; the caller closes the returned handle once the request is sent.
 */
public final class AddressPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(AddressPolicy.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** Nothing blocked. */
    public static final AddressPolicy OPEN = new AddressPolicy(false, List.of());

    /** Looks up every address of a host name. */
    @FunctionalInterface
    interface Lookup {
        InetAddress[] all(String host) throws UnknownHostException;
    }

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

    /** IPv4 ranges refused by {@code blockLocalHost} besides the ones {@link InetAddress} knows. */
    private static final List<Cidr> LOCAL_V4 = List.of(
            cidr("0.0.0.0/8"), cidr("100.64.0.0/10"), cidr("192.0.0.0/24"), cidr("198.18.0.0/15"),
            cidr("240.0.0.0/4"), cidr("224.0.0.0/4"));

    private final boolean blockLocalHost;
    private final List<Cidr> cidrs = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();
    private final Lookup lookup;
    private final boolean pin;

    public AddressPolicy(boolean blockLocalHost, List<String> blockExtraAddresses) {
        this(blockLocalHost, blockExtraAddresses, GuardedResolverProvider::lookup);
    }

    /** With a custom lookup (tests: a resolver whose answers change between calls). */
    AddressPolicy(boolean blockLocalHost, List<String> blockExtraAddresses, Lookup lookup) {
        this.blockLocalHost = blockLocalHost;
        this.lookup = lookup;
        for (String raw : blockExtraAddresses == null ? List.<String>of() : blockExtraAddresses) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.strip();
            Cidr c = parseCidr(e);
            if (c != null) cidrs.add(c);
            else hosts.add(e.toLowerCase(Locale.ROOT));
        }
        this.pin = blocksAnything() && GuardedResolverProvider.active();
        if (blocksAnything() && !pin && WARNED.compareAndSet(false, true)) {
            LOG.warn("Another InetAddressResolverProvider is installed: the web address guard checks each lookup but "
                    + "cannot pin the approved address, so a DNS answer that changes before the connect is not caught");
        }
    }

    public boolean blocksAnything() {
        return blockLocalHost || !cidrs.isEmpty() || !hosts.isEmpty();
    }

    /**
     * Checks the host of {@code uri} and pins the approved addresses for it until the returned handle is closed.
     *
     * @throws IllegalStateException when the host is, or resolves to, a blocked address, or cannot be resolved while a
     *                               block is active
     */
    public GuardedResolverProvider.Handle check(URI uri) {
        if (!blocksAnything()) return GuardedResolverProvider.NONE;
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("URL has no host: " + uri);
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        for (String b : hosts) {
            if (h.equals(b) || (b.startsWith("*.") && h.endsWith(b.substring(1)))) refuse(host, "listed in blockExtraAddresses");
        }
        boolean literal = looksLikeIp(h);
        InetAddress[] all;
        try {
            all = literal ? new InetAddress[]{InetAddress.getByName(h)} : lookup.all(h);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Access to '" + host + "' is refused: cannot resolve the name");
        }
        if (all == null || all.length == 0) {
            throw new IllegalStateException("Access to '" + host + "' is refused: cannot resolve the name");
        }
        for (InetAddress a : all) test(host, a);
        if (literal || !pin) return GuardedResolverProvider.NONE;
        return GuardedResolverProvider.pin(h, all);
    }

    private void test(String host, InetAddress a) {
        byte[] embedded = embeddedIpv4(a.getAddress());
        if (blockLocalHost) {
            if (isLocal(a)) refuse(host, "a local address (" + a.getHostAddress() + ", web.blockLocalHost)");
            if (embedded != null && isLocal(embedded)) {
                refuse(host, "a local address (" + a.getHostAddress() + " carries " + ipv4(embedded) + ", web.blockLocalHost)");
            }
        }
        byte[] raw = a.getAddress();
        for (Cidr c : cidrs) {
            if (c.contains(raw) || (embedded != null && c.contains(embedded))) {
                refuse(host, "in blockExtraAddresses (" + a.getHostAddress() + ")");
            }
        }
    }

    private static void refuse(String host, String why) {
        throw new IllegalStateException("Access to '" + host + "' is refused: " + why);
    }

    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) return isLocal(b);
        return (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    /** An IPv4 address given as bytes: the ranges of {@link #isLocal(InetAddress)} plus {@link #LOCAL_V4}. */
    private static boolean isLocal(byte[] v4) {
        try {
            InetAddress a = InetAddress.getByAddress(v4);
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                    || a.isMulticastAddress()) {
                return true;
            }
        } catch (UnknownHostException e) {
            return true;
        }
        for (Cidr c : LOCAL_V4) if (c.contains(v4)) return true;
        return false;
    }

    /**
     * The IPv4 address an IPv6 one carries: IPv4-mapped {@code ::ffff:0:0/96}, IPv4-compatible {@code ::/96}, NAT64
     * {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}, Teredo {@code 2001::/32} (client address, inverted). Null for an
     * IPv4 address or an IPv6 one without such a form.
     */
    static byte[] embeddedIpv4(byte[] a) {
        if (a.length != 16) return null;
        if (zero(a, 0, 10) && (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) return last4(a);
        if (zero(a, 0, 12) && !zero(a, 12, 16)) return last4(a);
        if ((a[0] & 0xFF) == 0x00 && (a[1] & 0xFF) == 0x64 && (a[2] & 0xFF) == 0xFF && (a[3] & 0xFF) == 0x9B
                && zero(a, 4, 12)) {
            return last4(a);
        }
        if ((a[0] & 0xFF) == 0x20 && (a[1] & 0xFF) == 0x02) return new byte[]{a[2], a[3], a[4], a[5]};
        if ((a[0] & 0xFF) == 0x20 && (a[1] & 0xFF) == 0x01 && a[2] == 0 && a[3] == 0) {
            return new byte[]{(byte) ~a[12], (byte) ~a[13], (byte) ~a[14], (byte) ~a[15]};
        }
        return null;
    }

    private static boolean zero(byte[] a, int from, int to) {
        for (int i = from; i < to; i++) if (a[i] != 0) return false;
        return true;
    }

    private static byte[] last4(byte[] a) {
        return new byte[]{a[12], a[13], a[14], a[15]};
    }

    private static String ipv4(byte[] b) {
        return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
    }

    private static Cidr cidr(String e) {
        return parseCidr(e);
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

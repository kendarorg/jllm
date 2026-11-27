package org.kendar.llm.web.commons;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The address of the caller, for the sign-in throttle. {@code X-Forwarded-For} is believed only when the TCP peer is
 * one of the configured trusted proxies ({@code jllm.web.trusted-proxies}: addresses or CIDRs); then the entries are
 * read from the right, skipping trusted proxies, and the first other address is the client. Without trusted proxies
 * (the default) the header is ignored, so a caller cannot choose the key the throttle counts them under.
 */
public final class ClientAddress {

    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

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

    private final List<Cidr> trusted = new ArrayList<>();

    /** @param trustedProxies addresses or CIDRs ({@code 10.0.0.0/8}); null or empty = none */
    public ClientAddress(List<String> trustedProxies) {
        for (String raw : trustedProxies == null ? List.<String>of() : trustedProxies) {
            if (raw == null || raw.isBlank()) continue;
            trusted.add(parse(raw.strip()));
        }
    }

    /** Nobody is trusted: the peer address is the client. */
    public static ClientAddress direct() {
        return new ClientAddress(null);
    }

    /**
     * @param remoteAddr    the address of the TCP peer
     * @param forwardedFor  the {@code X-Forwarded-For} values of the request (several headers joined by commas); may be null
     */
    public String of(String remoteAddr, String forwardedFor) {
        if (trusted.isEmpty() || forwardedFor == null || forwardedFor.isBlank() || !isTrusted(remoteAddr)) {
            return remoteAddr;
        }
        String[] parts = forwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].strip();
            if (!LITERAL.matcher(candidate).matches()) return remoteAddr; // garbage: believe nothing of it
            if (!isTrusted(candidate)) return candidate;
        }
        return remoteAddr;
    }

    private boolean isTrusted(String address) {
        byte[] a = bytes(address);
        if (a == null) return false;
        for (Cidr c : trusted) if (c.contains(a)) return true;
        return false;
    }

    private static byte[] bytes(String address) {
        if (address == null || !LITERAL.matcher(address).matches()) return null;
        try {
            return InetAddress.getByName(address).getAddress(); // a literal: never a lookup
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static Cidr parse(String e) {
        String addr = e;
        int bits = -1;
        int slash = e.indexOf('/');
        if (slash >= 0) {
            addr = e.substring(0, slash);
            try {
                bits = Integer.parseInt(e.substring(slash + 1).strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Bad jllm.web.trusted-proxies entry: " + e);
            }
        }
        byte[] net = bytes(addr);
        if (net == null) throw new IllegalArgumentException("Bad jllm.web.trusted-proxies entry (an IP or a CIDR): " + e);
        if (bits < 0) bits = net.length * 8;
        if (bits > net.length * 8) throw new IllegalArgumentException("Bad jllm.web.trusted-proxies entry: " + e);
        return new Cidr(net, bits);
    }
}

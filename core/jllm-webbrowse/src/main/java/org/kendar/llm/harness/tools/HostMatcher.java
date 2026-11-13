package org.kendar.llm.harness.tools;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/** Host names as the web tools compare them, for per-host approval. No DNS resolution happens here. */
public final class HostMatcher {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private HostMatcher() {
    }

    /** Lowercase, trailing dot removed, IDN converted to ASCII, IPv6 brackets removed. */
    public static String normalize(String host) {
        if (host == null) return "";
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (!h.isEmpty() && !isIp(h)) {
            try {
                h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                // keep as is: it will not match anything but itself
            }
        }
        return h;
    }

    public static boolean isIp(String normalized) {
        return normalized.indexOf(':') >= 0 || IPV4.matcher(normalized).matches();
    }

    /**
     * A grant for {@code example.com} covers {@code example.com} and {@code *.example.com}, on a label boundary; a
     * grant for {@code www.example.com} does not cover {@code example.com}; an IP literal matches only itself.
     */
    public static boolean covers(String granted, String host) {
        String g = normalize(granted);
        String h = normalize(host);
        if (g.isEmpty() || h.isEmpty()) return false;
        if (g.equals(h)) return true;
        if (isIp(g) || isIp(h)) return false;
        return h.endsWith("." + g);
    }
}

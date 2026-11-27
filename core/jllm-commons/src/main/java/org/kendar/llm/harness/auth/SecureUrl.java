package org.kendar.llm.harness.auth;

import java.net.URI;
import java.util.Locale;

/** The transport rule of every OAuth / OpenID endpoint: HTTPS, or plain http to the loopback host (local tests). */
public final class SecureUrl {

    private SecureUrl() {
    }

    /**
     * @param what what the URL is, for the message
     * @throws IllegalArgumentException when {@code url} is not a URL, or is neither https nor http to loopback
     */
    public static void require(String url, String what) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " is not a URL");
        }
        if ("https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null) return;
        String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
        if ("http".equalsIgnoreCase(u.getScheme()) && host != null
                && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))) {
            return;
        }
        throw new IllegalArgumentException(what + " must use https");
    }
}

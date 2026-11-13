package org.kendar.llm.harness.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 PKCE pair (S256) plus an anti-CSRF {@code state}. */
public record Pkce(String verifier, String challenge, String state) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static Pkce generate() {
        String verifier = random(32);
        return new Pkce(verifier, challenge(verifier), random(16));
    }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Override
    public String toString() {
        return "Pkce[]";
    }
}

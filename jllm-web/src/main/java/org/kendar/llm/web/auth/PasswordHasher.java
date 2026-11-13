package org.kendar.llm.web.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashes of the user files: {@code pbkdf2$<iterations>$<salt b64>$<hash b64>} (PBKDF2WithHmacSHA256).
 * A user without a stored hash has the default password: the lowercase hex SHA-256 of their login id.
 */
public final class PasswordHasher {

    public static final int MIN_LENGTH = 8;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /**
     * Whether {@code password} is the user's password: checked against {@code stored}, or against the default
     * password of {@code id} when nothing is stored. A malformed stored hash matches nothing.
     */
    public static boolean verify(String id, String password, String stored) {
        if (password == null) return false;
        if (stored == null || stored.isBlank()) {
            return MessageDigest.isEqual(defaultPassword(id).getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8));
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Spends the time of one verification without a user: unknown ids take as long as wrong passwords. */
    public static void burn(String password) {
        pbkdf2(password == null ? "" : password, new byte[SALT_BYTES], ITERATIONS);
    }

    /** The default password of a login id: the id itself (the UI asks to change it at once). */
    public static String defaultPassword(String id) {
        return String.valueOf(id);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

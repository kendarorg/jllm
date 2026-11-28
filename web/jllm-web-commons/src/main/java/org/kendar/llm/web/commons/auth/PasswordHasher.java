package org.kendar.llm.web.commons.auth;

import org.kendar.llm.harness.identity.User;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.LongAdder;

/**
 * Password hashes of the user files: {@code pbkdf2$<iterations>$<salt b64>$<hash b64>} (PBKDF2WithHmacSHA256, 210 000
 * iterations; a stored count between 1 and {@link #MAX_ITERATIONS} is honoured, anything else matches nothing). A
 * one-time password is the same hash behind {@link User#TEMPORARY_PREFIX}. A user without a stored hash has the
 * default password: their login id (the UI makes them change it at once). Comparisons are constant-time, and the
 * default-password path pays one PBKDF2 like every other path, so the response time does not tell which accounts
 * still use the default.
 */
public final class PasswordHasher {

    public static final int MIN_LENGTH = 8;
    /** Generous headroom over {@link #ITERATIONS}: a larger stored count is a corrupted or tampered file. */
    public static final int MAX_ITERATIONS = 10_000_000;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    /** Length of {@link #generate()}. */
    public static final int GENERATED_LENGTH = 24;
    private static final LongAdder BURNS = new LongAdder();
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 210_000;
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

    /** The hash of a one-time password: it signs in, and the user must change it first. */
    public static String hashTemporary(String password) {
        return User.TEMPORARY_PREFIX + hash(password);
    }

    /** A random password of {@link #GENERATED_LENGTH} characters, without look-alikes (0/O, 1/l/I). */
    public static String generate() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /**
     * Whether {@code password} is the user's password: checked against {@code stored}, or against the default
     * password of {@code id} when nothing is stored. A malformed stored hash, or one with an iteration count outside
     * 1..{@link #MAX_ITERATIONS}, matches nothing.
     */
    public static boolean verify(String id, String password, String stored) {
        if (password == null) return false;
        if (stored == null || stored.isBlank()) {
            burn(password); // same cost as a stored hash: the time does not reveal the default password
            return MessageDigest.isEqual(defaultPassword(id).getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8));
        }
        if (stored.startsWith(User.TEMPORARY_PREFIX)) stored = stored.substring(User.TEMPORARY_PREFIX.length());
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > MAX_ITERATIONS) return false; // a tampered count must not tie up a thread
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Spends the time of one verification without a user: unknown ids take as long as wrong passwords. */
    public static void burn(String password) {
        BURNS.increment();
        pbkdf2(password == null ? "" : password, new byte[SALT_BYTES], ITERATIONS);
    }

    /** How many times {@link #burn} ran in this process: lets a test check a path costs one PBKDF2, without timing it. */
    public static long burnCount() {
        return BURNS.sum();
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

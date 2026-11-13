package org.kendar.llm.harness.auth;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * AES-256-GCM for token files. The key is 32 bytes, base64, from {@code JLLM_TOKEN_KEY}; {@code JLLM_TOKEN_KEY_OLD}
 * (optional) still decrypts files written before a key rotation. Every encryption uses a fresh random nonce, and the
 * output carries the key id (first 8 bytes of the SHA-256 of the key, hex), so the reader picks the right key.
 */
public final class TokenCipher {

    public static final String KEY_ENV = "JLLM_TOKEN_KEY";
    public static final String OLD_KEY_ENV = "JLLM_TOKEN_KEY_OLD";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** The data could not be decrypted: wrong key, unknown key id or tampered data. Never carries the data. */
    public static final class DecryptException extends Exception {
        public DecryptException(String message) {
            super(message);
        }
    }

    /** One encrypted value: key id, nonce and ciphertext with tag, all printable. */
    public record Sealed(String kid, String nonce, String data) {
    }

    private final byte[] key;
    private final String kid;
    private final byte[] oldKey;
    private final String oldKid;

    /** @param oldKey null when there is none */
    public TokenCipher(byte[] key, byte[] oldKey) {
        if (key == null || key.length != 32) throw new IllegalArgumentException(KEY_ENV + " must be 32 bytes (base64)");
        if (oldKey != null && oldKey.length != 32) {
            throw new IllegalArgumentException(OLD_KEY_ENV + " must be 32 bytes (base64)");
        }
        this.key = key.clone();
        this.kid = kid(key);
        this.oldKey = oldKey == null ? null : oldKey.clone();
        this.oldKid = oldKey == null ? null : kid(oldKey);
    }

    /**
     * The cipher of {@code JLLM_TOKEN_KEY} (and {@code JLLM_TOKEN_KEY_OLD}), or null when the key is not set.
     *
     * @throws IllegalArgumentException when a key is not valid base64 of 32 bytes
     */
    public static TokenCipher fromEnv(Function<String, String> env) {
        String k = env.apply(KEY_ENV);
        if (k == null || k.isBlank()) return null;
        String old = env.apply(OLD_KEY_ENV);
        return new TokenCipher(decodeKey(KEY_ENV, k), old == null || old.isBlank() ? null : decodeKey(OLD_KEY_ENV, old));
    }

    public static TokenCipher fromEnv() {
        return fromEnv(System::getenv);
    }

    /** A new random key, base64, for {@code JLLM_TOKEN_KEY}. */
    public static String newKey() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] decodeKey(String name, String value) {
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid base64");
        }
    }

    /** The id of the current key. */
    public String keyId() {
        return kid;
    }

    public Sealed encrypt(String plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(kid.getBytes(StandardCharsets.US_ASCII));
            byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return new Sealed(kid, b64.encodeToString(nonce), b64.encodeToString(out));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** @return true when {@code s} was written with the old key (the caller should rewrite it) */
    public boolean isOldKey(Sealed s) {
        return oldKid != null && oldKid.equals(s.kid()) && !kid.equals(s.kid());
    }

    public String decrypt(Sealed s) throws DecryptException {
        byte[] k;
        if (kid.equals(s.kid())) k = key;
        else if (oldKid != null && oldKid.equals(s.kid())) k = oldKey;
        else throw new DecryptException("written with another key (id " + s.kid() + ")");
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, b64.decode(s.nonce())));
            c.updateAAD(s.kid().getBytes(StandardCharsets.US_ASCII));
            return new String(c.doFinal(b64.decode(s.data())), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new DecryptException("tampered, or written with another key");
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new DecryptException("not a valid encrypted token file");
        }
    }

    private static String kid(byte[] key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "TokenCipher[kid=" + kid + "]";
    }
}

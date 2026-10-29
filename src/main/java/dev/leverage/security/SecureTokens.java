package dev.leverage.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecureTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() { }

    public static String create() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    public static String createVerificationCode() {
        return String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }
}

package dev.leverage.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    public static final int PRODUCTION_ITERATIONS = 600_000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher(int iterations) {
        if (iterations < 10_000) {
            throw new IllegalArgumentException(
                    "El factor de trabajo es demasiado pequeño");
        }
        this.iterations = iterations;
    }

    public static PasswordHasher production() {
        return new PasswordHasher(PRODUCTION_ITERATIONS);
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        return "pbkdf2-sha256$" + iterations + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    public boolean verify(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
                return false;
            }
            int encodedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, encodedIterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean needsRehash(String encoded) {
        String[] parts = encoded.split("\\$");
        return parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])
                || Integer.parseInt(parts[1]) < iterations;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(
                    spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2 no está disponible", exception);
        } finally {
            spec.clearPassword();
        }
    }
}

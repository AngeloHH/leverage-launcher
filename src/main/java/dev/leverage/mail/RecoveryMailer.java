package dev.leverage.mail;

import java.time.Instant;

public interface RecoveryMailer {
    void sendEmailVerification(String email, String username,
            String token, Instant expiresAt) throws Exception;

    default void sendEmailVerification(String email, String username, String token,
            Instant expiresAt, String code, Instant codeExpiresAt) throws Exception {
        // Link-only mail transports still accept
        // callers that also provide a verification code.
        sendEmailVerification(email, username, token, expiresAt);
    }

    void sendPasswordReset(String email, String username, String token,
            Instant expiresAt) throws Exception;

    default void sendEmailChangeVerification(String email, String username,
            String token, Instant expiresAt) throws Exception {
        sendEmailVerification(email, username, token, expiresAt);
    }
}

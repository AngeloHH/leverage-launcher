package dev.leverage.mail;

import java.time.Instant;

public final class ConsoleRecoveryMailer implements RecoveryMailer {
    @Override
    public void sendEmailVerification(String email, String username, String token,
            Instant expiresAt, String code, Instant codeExpiresAt) {
        System.err.printf("%n[LEVERAGE DEV MAIL] Verificación para %s "
                + "<%s>%nCódigo: %s%nVence: %s%n", username, email, code, codeExpiresAt);
        System.err.printf("Token de enlace: %s%nVence el enlace: %s%n%n",
                token, expiresAt);
    }

    @Override
    public void sendEmailVerification(String email, String username,
            String token, Instant expiresAt) {
        System.err.printf("%n[LEVERAGE DEV MAIL] Verificación para %s "
                + "<%s>%nToken: %s%nVence: %s%n%n", username, email, token, expiresAt);
    }

    @Override
    public void sendPasswordReset(String email, String username,
            String token, Instant expiresAt) {
        System.err.printf("%n[LEVERAGE DEV MAIL] Recuperación para %s "
                + "<%s>%nToken: %s%nVence: %s%n%n", username, email, token, expiresAt);
    }

    @Override
    public void sendEmailChangeVerification(String email,
            String username, String token, Instant expiresAt) {
        System.err.printf("%n[LEVERAGE DEV MAIL] Cambio de correo para %s "
                + "<%s>%nToken: %s%nVence: %s%n%n", username, email, token, expiresAt);
    }
}

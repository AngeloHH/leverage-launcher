package dev.leverage.mail;

import dev.leverage.config.AppConfig;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;

public final class SmtpRecoveryMailer implements RecoveryMailer {
    private final AppConfig.MailConfig config;
    private final Session session;

    public SmtpRecoveryMailer(AppConfig.MailConfig config) {
        if (config.smtpHost().isBlank()) {
            throw new IllegalArgumentException(
                    "mail.smtp.host es obligatorio cuando mail.mode=smtp");
        }
        this.config = config;

        Properties properties = new Properties();
        properties.put("mail.smtp.host", config.smtpHost());
        properties.put("mail.smtp.port", Integer.toString(config.smtpPort()));
        properties.put("mail.smtp.auth", Boolean.toString(
                !config.smtpUsername().isBlank()));
        properties.put("mail.smtp.starttls.enable",
                Boolean.toString(config.smtpStartTls()));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(config.smtpSsl()));
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");

        Authenticator authenticator = null;
        if (!config.smtpUsername().isBlank()) {
            if (config.smtpPassword().isBlank()) {
                throw new IllegalArgumentException(
                        "LEVERAGE_SMTP_PASSWORD es obligatorio para SMTP "
                        + "autenticado");
            }
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.smtpUsername(),
                            config.smtpPassword());
                }
            };
        }
        this.session = Session.getInstance(properties, authenticator);
    }

    // =========================================================================
    // VERIFICATION AND RECOVERY MESSAGES
    // =========================================================================

    @Override
    public void sendEmailVerification(String email, String username,
            String token, Instant expiresAt) throws Exception {
        String link = actionLink("verify-email", token);
        send(email, "Verifica tu cuenta de Leverage", """
                Hola %s,

                Usa el siguiente enlace o copia el token en el launcher para verificar \
                tu correo:

                %s

                Token: %s
                Vence: %s

                Si no creaste esta cuenta, ignora este mensaje.
                """.formatted(username, link, token, expiresAt));
    }

    @Override
    public void sendEmailVerification(String email, String username, String token,
            Instant expiresAt, String code, Instant codeExpiresAt) throws Exception {
        send(email, "Verifica tu cuenta de Leverage", """
                Hola %s,

                Escribe este código de seis dígitos en el launcher para verificar tu \
                correo:

                %s

                El código vence: %s

                También puedes usar el siguiente enlace:
                %s
                El enlace vence: %s

                Si no creaste esta cuenta, ignora este mensaje.
                """.formatted(username, code, codeExpiresAt,
                actionLink("verify-email", token), expiresAt));
    }

    @Override
    public void sendPasswordReset(String email, String username,
            String token, Instant expiresAt) throws Exception {
        String link = actionLink("reset-password", token);
        send(email, "Recuperación de tu cuenta de Leverage", """
                Hola %s,

                Usa el siguiente enlace o copia el token en el launcher para cambiar tu \
                contraseña:

                %s

                Token: %s
                Vence: %s

                Si no solicitaste este cambio, ignora este mensaje.
                """.formatted(username, link, token, expiresAt));
    }

    @Override
    public void sendEmailChangeVerification(String email, String username,
            String token, Instant expiresAt) throws Exception {
        String link = actionLink("verify-email", token);
        send(email, "Confirma tu nuevo correo de Leverage", """
                Hola %s,

                Se solicitó usar esta dirección como el nuevo correo de tu cuenta \
                Leverage.
                Confirma el cambio con el siguiente enlace o copia el token en el \
                launcher:

                %s

                Token: %s
                Vence: %s

                Si no solicitaste este cambio, ignora este mensaje; el correo actual \
                seguirá intacto.
                """.formatted(username, link, token, expiresAt));
    }

    // =========================================================================
    // ACTION LINKS AND UTF-8 DELIVERY
    // =========================================================================

    private String actionLink(String action, String token) {
        String separator = config.actionBaseUrl().contains("?") ? "&" : "?";
        return config.actionBaseUrl() + separator + "action=" + action + "&token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void send(String recipient, String subject, String text) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.from()));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(text, StandardCharsets.UTF_8.name());
        Transport.send(message);
    }
}

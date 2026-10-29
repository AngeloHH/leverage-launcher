package dev.leverage.mail;

import dev.leverage.config.AppConfig;

public final class MailerFactory {
    private MailerFactory() { }

    public static RecoveryMailer create(AppConfig.MailConfig config) {
        return switch (config.mode()) {
            case "console" -> {
                System.err.println("ADVERTENCIA: mail.mode=console expone tokens en la "
                        + "terminal; úselo solo en desarrollo.");
                yield new ConsoleRecoveryMailer();
            }
            case "smtp" -> new SmtpRecoveryMailer(config);
            default -> throw new IllegalArgumentException(
                    "mail.mode debe ser 'console' o 'smtp'");
        };
    }
}

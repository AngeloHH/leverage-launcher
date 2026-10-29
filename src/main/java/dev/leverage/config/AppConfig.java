package dev.leverage.config;

import dev.leverage.launch.AuthlibInjectorManager;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public record AppConfig(String bindAddress, int port, Path databasePath,
        Path textureStoragePath, Path yggdrasilKeyPath, Path authlibInjectorPath,
        URI authlibInjectorUrl, String authlibInjectorSha256, Path modPolicyPath,
        Path modStoragePath, Path launcherVersionPolicyPath, boolean registrationEnabled,
        Duration sessionTtl, Duration deviceSessionTtl, Duration gameTicketTtl,
        Duration yggdrasilJoinTtl, Duration modSuspensionDuration,
        Duration emailVerificationTtl, Duration passwordRecoveryTtl, MailConfig mail) {

    // =========================================================================
    // DEPLOYMENT FILES AND ENVIRONMENT OVERRIDES
    // =========================================================================

    public static AppConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
        }

        Path absoluteConfig = path.toAbsolutePath().normalize();
        Path baseDirectory = absoluteConfig.getParent() == null ? Path.of(
                "").toAbsolutePath() : absoluteConfig.getParent().getParent();
        if (baseDirectory == null) {
            baseDirectory = Path.of("").toAbsolutePath();
        }

        String databaseValue = value(properties, "database.path",
                "LEVERAGE_DATABASE_PATH", "data/leverage.db");
        Path databasePath = resolve(baseDirectory, databaseValue);
        Path textureStoragePath = resolve(baseDirectory, value(properties,
                "textures.path", "LEVERAGE_TEXTURES_PATH", "data/textures/sha256"));
        Path yggdrasilKeyPath = resolve(baseDirectory, value(properties,
                "yggdrasil.keys.path", "LEVERAGE_YGGDRASIL_KEYS_PATH", "data/yggdrasil"));
        Path authlibInjectorPath = resolve(baseDirectory,
                value(properties, "authlib-injector.path",
                "LEVERAGE_AUTHLIB_INJECTOR_PATH", "data/tools/authlib-injector-"
                + AuthlibInjectorManager.OFFICIAL_VERSION + ".jar"));
        URI authlibInjectorUrl = URI.create(value(properties,
                "authlib-injector.url", "LEVERAGE_AUTHLIB_INJECTOR_URL",
                AuthlibInjectorManager.OFFICIAL_URL.toString()));
        String authlibInjectorSha256 = value(properties,
                "authlib-injector.sha256", "LEVERAGE_AUTHLIB_INJECTOR_SHA256",
                AuthlibInjectorManager.OFFICIAL_SHA256);

        MailConfig mail = new MailConfig(value(properties, "mail.mode",
                "LEVERAGE_MAIL_MODE", "console").toLowerCase(Locale.ROOT),
                value(properties, "mail.from", "LEVERAGE_MAIL_FROM",
                "Leverage <noreply@localhost>"), value(properties, "mail.action.base-url",
                "LEVERAGE_MAIL_ACTION_BASE_URL", "leverage://account"),
                value(properties, "mail.smtp.host", "LEVERAGE_SMTP_HOST", ""),
                integer(properties, "mail.smtp.port", "LEVERAGE_SMTP_PORT", 587),
                bool(properties, "mail.smtp.starttls", "LEVERAGE_SMTP_STARTTLS",
                true), bool(properties, "mail.smtp.ssl", "LEVERAGE_SMTP_SSL", false),
                value(properties, "mail.smtp.username", "LEVERAGE_SMTP_USERNAME",
                ""), System.getenv().getOrDefault("LEVERAGE_SMTP_PASSWORD", ""));

        return new AppConfig(value(properties, "server.bind", "LEVERAGE_SERVER_BIND",
                "127.0.0.1"), integer(properties, "server.port", "LEVERAGE_SERVER_PORT",
                8080), databasePath, textureStoragePath, yggdrasilKeyPath,
                authlibInjectorPath, authlibInjectorUrl, authlibInjectorSha256,
                resolve(baseDirectory, value(properties, "mods.policy.path",
                "LEVERAGE_MOD_POLICY_PATH", "config/mod-policy.json")),
                resolve(baseDirectory, value(properties, "mods.storage.path",
                "LEVERAGE_MOD_STORAGE_PATH", "data/mods/sha1")),
                resolve(baseDirectory, value(properties, "launcher.versions.path",
                "LEVERAGE_LAUNCHER_VERSIONS_PATH", "config/launcher-versions.json")),
                bool(properties, "registration.enabled", "LEVERAGE_REGISTRATION_ENABLED",
                true), Duration.ofHours(integer(properties, "session.ttl.hours",
                "LEVERAGE_SESSION_TTL_HOURS", 12)), Duration.ofDays(integer(properties,
                "device-session.ttl.days", "LEVERAGE_DEVICE_SESSION_TTL_DAYS",
                30)), Duration.ofSeconds(integer(properties,
                "game-ticket.ttl.seconds", "LEVERAGE_GAME_TICKET_TTL_SECONDS", 60)),
                Duration.ofSeconds(integer(properties, "yggdrasil.join.ttl.seconds",
                "LEVERAGE_YGGDRASIL_JOIN_TTL_SECONDS", 30)), Duration.ofDays(integer(
                properties, "mods.suspension.days", "LEVERAGE_MOD_SUSPENSION_DAYS", 30)),
                Duration.ofHours(integer(properties, "email.verification.ttl.hours",
                "LEVERAGE_EMAIL_VERIFICATION_TTL_HOURS", 24)),
                Duration.ofMinutes(integer(properties, "password.recovery.ttl.minutes",
                "LEVERAGE_PASSWORD_RECOVERY_TTL_MINUTES", 30)), mail);
    }

    // =========================================================================
    // PATHS AND TYPED CONFIGURATION VALUES
    // =========================================================================

    private static Path resolve(Path baseDirectory, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize()
                : baseDirectory.resolve(path).normalize();
    }

    private static String value(Properties properties, String key,
            String env, String fallback) {
        // An explicit environment value overrides the
        // file; blank values keep the configured fallback.
        String environment = System.getenv(env);
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        return properties.getProperty(key, fallback).trim();
    }

    private static int integer(Properties properties,
            String key, String env, int fallback) {
        String raw = value(properties, key, env, Integer.toString(fallback));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Configuración numérica inválida para "
                    + key, exception);
        }
    }

    private static boolean bool(Properties properties, String key,
            String env, boolean fallback) {
        return Boolean.parseBoolean(value(properties, key,
                env, Boolean.toString(fallback)));
    }

    public record MailConfig(String mode, String from, String actionBaseUrl,
            String smtpHost, int smtpPort, boolean smtpStartTls, boolean smtpSsl,
            String smtpUsername, String smtpPassword) { }
}

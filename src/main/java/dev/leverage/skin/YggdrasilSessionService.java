package dev.leverage.skin;

import dev.leverage.db.Database;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class YggdrasilSessionService {
    private final Database database;
    private final AuthService auth;
    private final TextureService textures;
    private final Clock clock;
    private final Duration joinTtl;

    public YggdrasilSessionService(Database database, AuthService auth,
            TextureService textures, Clock clock, Duration joinTtl) {
        if (joinTtl.isNegative() || joinTtl.isZero()
                || joinTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "El TTL de join debe estar entre 1 segundo y 5 minutos.");
        }
        this.database = database;
        this.auth = auth;
        this.textures = textures;
        this.clock = clock;
        this.joinTtl = joinTtl;
    }

    // =========================================================================
    // SHORT-LIVED SERVER JOIN PROOF
    // =========================================================================

    public void join(String accessToken, String selectedProfile, String serverId) {
        AuthService.AuthenticatedUser actor;
        try {
            actor = auth.authenticate(accessToken);
        } catch (ServiceException exception) {
            throw invalidToken();
        }
        UUID profileId = parseProfileId(selectedProfile);
        if (!actor.user().id().equals(profileId)) {
            throw invalidToken();
        }
        String challenge = validServerId(serverId);
        TextureService.MinecraftProfile profile = textures.profileFor(actor);
        try {
            database.transaction(connection -> {
                Instant now = clock.instant();
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM yggdrasil_joins WHERE expires_at<=?")) {
                    cleanup.setLong(1, now.toEpochMilli());
                    cleanup.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement( """
                        INSERT INTO yggdrasil_joins(
                            server_id, profile_id, session_id, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(server_id) DO UPDATE SET
                            profile_id=excluded.profile_id,
                            session_id=excluded.session_id,
                            created_at=excluded.created_at,
                            expires_at=excluded.expires_at
                        """)) {
                    statement.setString(1, challenge);
                    statement.setString(2, profile.id().toString());
                    statement.setString(3, actor.sessionId());
                    statement.setLong(4, now.toEpochMilli());
                    statement.setLong(5, now.plus(joinTtl).toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            throw new ServiceException(500, "DATABASE_ERROR",
                    "No se pudo registrar la entrada al servidor.");
        }
    }

    // =========================================================================
    // ONE-USE JOIN CONSUMPTION
    // =========================================================================

    public TextureService.MinecraftProfile hasJoined(String username, String serverId) {
        if (username == null || username.isBlank() || username.length() > 16
                || serverId == null || serverId.isEmpty() || serverId.length() > 128) {
            return null;
        }
        try {
            return database.transaction(connection -> {
                long now = clock.instant().toEpochMilli();
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM yggdrasil_joins WHERE expires_at<=?")) {
                    cleanup.setLong(1, now);
                    cleanup.executeUpdate();
                }
                UUID profileId = null;
                try (PreparedStatement statement = connection.prepareStatement( """
                        SELECT j.profile_id
                        FROM yggdrasil_joins j
                        JOIN sessions s ON s.id=j.session_id
                        JOIN users u ON u.id=j.profile_id
                        WHERE j.server_id=? AND u.username=? COLLATE NOCASE
                          AND j.expires_at>? AND s.revoked_at IS NULL AND s.expires_at>?
                          AND u.status='ACTIVE'
                        LIMIT 1
                        """)) {
                    statement.setString(1, serverId);
                    statement.setString(2, username.toLowerCase(Locale.ROOT));
                    statement.setLong(3, now);
                    statement.setLong(4, now);
                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) {
                            profileId = UUID.fromString(row.getString("profile_id"));
                        }
                    }
                }
                return profileId == null ? null : textures.profileById(profileId, false);
            });
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            throw new ServiceException(500, "DATABASE_ERROR",
                    "No se pudo comprobar la entrada al servidor.");
        }
    }

    // =========================================================================
    // PROFILE AND SERVER IDENTIFIERS
    // =========================================================================

    private static UUID parseProfileId(String value) {
        if (value == null) {
            throw invalidToken();
        }
        String clean = value.trim();
        if (clean.matches("[0-9a-fA-F]{32}")) {
            clean = clean.substring(0, 8) + "-" + clean.substring(8,
                    12) + "-" + clean.substring(12, 16) + "-" + clean.substring(16,
                    20) + "-" + clean.substring(20);
        }
        try {
            return UUID.fromString(clean);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static String validServerId(String value) {
        if (value == null || value.isEmpty() || value.length() > 128
                || value.indexOf('\0') >= 0) {
            throw invalidToken();
        }
        return value;
    }

    private static ServiceException invalidToken() {
        return ServiceException.forbidden("INVALID_TOKEN", "Invalid token.");
    }
}

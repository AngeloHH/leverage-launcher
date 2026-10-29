package dev.leverage.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AccountRestrictions {
    private AccountRestrictions() { }

    static void refreshExpired(Connection connection, UUID userId,
            Instant now) throws SQLException {
        int expired;
        try (PreparedStatement statement = connection.prepareStatement( """
                UPDATE account_sanctions
                SET resolved_at=?, resolution='AUTO_EXPIRED'
                WHERE user_id=? AND (resolution IS NULL OR resolution='BANNED') AND \
                expires_at<=?
                """)) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, userId.toString());
            statement.setLong(3, now.toEpochMilli());
            expired = statement.executeUpdate();
        }
        if (expired == 0 || hasActiveBan(connection, userId,
                now) || hasActiveSuspension(connection, userId, now)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement( """
                UPDATE users SET status='ACTIVE', updated_at=?
                WHERE id=? AND status='SUSPENDED'
                """)) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, userId.toString());
            statement.executeUpdate();
        }
    }

    static void refreshAllExpired(Connection connection,
            Instant now) throws SQLException {
        List<UUID> users = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT DISTINCT user_id FROM account_sanctions
                WHERE (resolution IS NULL OR resolution='BANNED') AND expires_at<=?
                """)) {
            statement.setLong(1, now.toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    users.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        for (UUID user : users) {
            refreshExpired(connection, user, now);
        }
    }

    static boolean hasActiveBan(Connection connection, UUID userId,
            Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT 1 FROM account_sanctions WHERE user_id=? AND resolution='BANNED'
                  AND (expires_at IS NULL OR expires_at>?) LIMIT 1
                """)) {
            statement.setString(1, userId.toString());
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static boolean hasActiveSuspension(Connection connection,
            UUID userId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT 1 FROM account_sanctions
                WHERE user_id=? AND resolution IS NULL AND expires_at>?
                LIMIT 1
                """)) {
            statement.setString(1, userId.toString());
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static void revokeAll(Connection connection, UUID userId,
            Instant now) throws SQLException {
        revoke(connection, "sessions", userId, now);
        revoke(connection, "device_sessions", userId, now);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE game_tickets SET used_at=? WHERE user_id=? "
                + "AND used_at IS NULL")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, userId.toString());
            statement.executeUpdate();
        }
    }

    private static void revoke(Connection connection, String table,
            UUID userId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table
                + " SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, userId.toString());
            statement.executeUpdate();
        }
    }
}

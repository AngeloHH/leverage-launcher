package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.security.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

// Administrative account operations. All mutations
// recheck the current operator and commit atomically.
public final class AdminUserService {
    private final Database database;
    private final Clock clock;
    private final PasswordHasher hasher;

    public AdminUserService(Database database, Clock clock, PasswordHasher hasher) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.hasher = Objects.requireNonNull(hasher);
    }

    public record Details(User user, Instant lastSeenAt, boolean banned,
            String restrictionReason, String skinHash) { }

    // =========================================================================
    // ACCOUNT ACTIVITY AND RESTRICTIONS
    // =========================================================================

    // Missing historical activity stays null; account
    // creation is never substituted for a login.
    public List<Details> details(AuthService.AuthenticatedUser actor, List<User> users) {
        requirePermission(actor, Permission.USERS_READ);
        Objects.requireNonNull(users);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                requireCurrentActor(connection, actor, Permission.USERS_READ, now);
                AccountRestrictions.refreshAllExpired(connection, now);
                List<Details> result = new ArrayList<>(users.size());
                for (User supplied : users) {
                    User user = requireUser(connection, supplied.id());
                    boolean banned = AccountRestrictions.hasActiveBan(connection,
                            user.id(), now);
                    result.add(new Details(user, lastSeen(connection, user.id()),
                            banned, restrictionReason(connection, user, banned,
                            now), skinHash(connection, user.id())));
                }
                return List.copyOf(result);
            });
        } catch (SQLException failure) {
            throw databaseFailure();
        }
    }

    // =========================================================================
    // ADMINISTRATIVE ACCOUNT CHANGES
    // =========================================================================

    public User ban(AuthService.AuthenticatedUser actor,
            UUID target, String reasonValue) {
        return ban(actor, target, reasonValue, null);
    }
    public User ban(AuthService.AuthenticatedUser actor, UUID target,
            String reasonValue, Instant expiresAt) {
        requireAdmin(actor);
        String reason = reasonValue == null ? "" : reasonValue.trim();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 1_000
                || reason.codePoints().anyMatch(value -> Character.isISOControl(value)
                && value != '\n' && value != '\r' && value != '\t')) {
            throw ServiceException.badRequest("INVALID_BAN_REASON",
                    "Escribe un motivo de entre 1 y 1000 caracteres para "
                    + "el baneo.");
        }
        if (actor.user().id().equals(target)) {
            throw ServiceException.conflict("SELF_BAN",
                    "No puedes banear tu propia cuenta.");
        }
        Instant now = clock.instant();
        if (expiresAt != null && (!expiresAt.isAfter(now)
                ||expiresAt.isAfter(Instant.parse("9999-12-31T23:59:59Z"))))
                throw ServiceException.badRequest("INVALID_BAN_EXPIRY",
                "La fecha de fin debe ser futura y válida.");
        try {
            return database.transaction(connection -> {
                requireCurrentAdmin(connection, actor, now);
                AccountRestrictions.refreshAllExpired(connection, now);
                User user = requireUser(connection, target);
                if (user.role() == Role.ADMIN && isUsableAdmin(connection, user,
                        now) && countUsableAdmins(connection, now) <= 1) {
                    throw ServiceException.conflict("LAST_ADMIN",
                            "No se puede banear al último administrador disponible.");
                }
                if (AccountRestrictions.hasActiveBan(connection, target, now)) {
                    throw ServiceException.conflict("ACCOUNT_BANNED",
                            "La cuenta ya tiene un baneo activo.");
                }
                try (PreparedStatement insert = connection.prepareStatement( """
                        INSERT INTO account_sanctions(id,user_id,by_mod,reason,created_at,
                                                      \
                        expires_at,created_by,resolved_at,resolution)
                        VALUES(?,?,0,?,?,?,?,NULL,'BANNED')
                        """)) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, target.toString());
                    insert.setString(3, reason);
                    insert.setLong(4, now.toEpochMilli());
                    if (expiresAt == null)insert.setNull(5, java.sql.Types.BIGINT);
                    else insert.setLong(5, expiresAt.toEpochMilli());
                    insert.setString(6, actor.user().id().toString());
                    insert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET status='SUSPENDED',updated_at=? "
                        + "WHERE id=?")) {
                    update.setLong(1, now.toEpochMilli());
                    update.setString(2, target.toString());
                    update.executeUpdate();
                }
                AccountRestrictions.revokeAll(connection, target, now);
                audit(connection, actor.user().id(), target, "USER_BANNED", now);
                return requireUser(connection, target);
            });
        } catch (SQLException failure) {
            throw databaseFailure();
        }
    }

    public User changePassword(AuthService.AuthenticatedUser actor,
            UUID target, String passwordValue) {
        requireAdmin(actor);
        char[] password = InputValidation.password(passwordValue);
        try {
            Instant now = clock.instant();
            return database.transaction(connection -> {
                requireCurrentAdmin(connection, actor, now);
                requireUser(connection, target);
                String encoded = hasher.hash(password);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET password_hash=?,"
                        + "password_changed_at=?,updated_at=? WHERE id=?")) {
                    update.setString(1, encoded);
                    update.setLong(2, now.toEpochMilli());
                    update.setLong(3, now.toEpochMilli());
                    update.setString(4, target.toString());
                    update.executeUpdate();
                }
                AccountRestrictions.revokeAll(connection, target, now);
                invalidateAccountRequests(connection, target, false, now);
                audit(connection, actor.user().id(), target,
                        "ADMIN_PASSWORD_CHANGED", now);
                return requireUser(connection, target);
            });
        } catch (SQLException failure) {
            throw databaseFailure();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    // Trusted administrator override: no confirmation is sent to
    // the former address. This does not verify a pending account; it
    // must request verification for its replacement email separately.
    public User changeEmail(AuthService.AuthenticatedUser actor,
            UUID target, String emailValue) {
        requireAdmin(actor);
        String email = InputValidation.email(emailValue);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                requireCurrentAdmin(connection, actor, now);
                requireUser(connection, target);
                try (PreparedStatement duplicate = connection.prepareStatement(
                        "SELECT 1 FROM users WHERE email=? COLLATE NOCASE "
                        + "AND id<>? LIMIT 1")) {
                    duplicate.setString(1, email);
                    duplicate.setString(2, target.toString());
                    try (ResultSet rows = duplicate.executeQuery()) {
                        if (rows.next()) {
                            throw ServiceException.conflict("EMAIL_EXISTS",
                                    "Ese correo ya está registrado.");
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET email=?,updated_at=? WHERE id=?")) {
                    update.setString(1, email);
                    update.setLong(2, now.toEpochMilli());
                    update.setString(3, target.toString());
                    update.executeUpdate();
                }
                AccountRestrictions.revokeAll(connection, target, now);
                invalidateAccountRequests(connection, target, true, now);
                audit(connection, actor.user().id(), target, "ADMIN_EMAIL_CHANGED", now);
                return requireUser(connection, target);
            });
        } catch (SQLException failure) {
            if (failure.getMessage() != null && failure.getMessage().toLowerCase(
                    Locale.ROOT).contains("unique constraint failed: users.email")) {
                throw ServiceException.conflict("EMAIL_EXISTS",
                        "Ese correo ya está registrado.");
            }
            throw databaseFailure();
        }
    }

    // Only removes this account's binding. Shared
    // texture files and capes are intentionally retained.
    public User clearSkin(AuthService.AuthenticatedUser actor, UUID target) {
        requireAdmin(actor);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                requireCurrentAdmin(connection, actor, now);
                requireUser(connection, target);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM textures WHERE user_id=? AND type='skin'")) {
                    delete.setString(1, target.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET updated_at=? WHERE id=?")) {
                    update.setLong(1, now.toEpochMilli());
                    update.setString(2, target.toString());
                    update.executeUpdate();
                }
                audit(connection, actor.user().id(), target, "ADMIN_SKIN_CLEARED", now);
                return requireUser(connection, target);
            });
        } catch (SQLException failure) {
            throw databaseFailure();
        }
    }

    // =========================================================================
    // STORED ACTIVITY AND PUBLIC SKIN REFERENCES
    // =========================================================================

    private static Instant lastSeen(Connection connection,
            UUID user) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement( """
                SELECT MAX(activity) FROM (
                    SELECT last_seen_at AS activity FROM sessions WHERE user_id=?
                    UNION ALL
                    SELECT last_used_at AS activity FROM device_sessions WHERE user_id=?
                )
                """)) {
            query.setString(1, user.toString());
            query.setString(2, user.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                long value = rows.getLong(1);
                return rows.wasNull() ? null : Instant.ofEpochMilli(value);
            }
        }
    }

    private static String restrictionReason(Connection connection, User user,
            boolean banned, Instant now) throws SQLException {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement( """
                SELECT reason FROM account_sanctions
                WHERE user_id=? AND (resolution='BANNED' OR resolution IS NULL)
                  AND (expires_at IS NULL OR expires_at>?)
                ORDER BY CASE WHEN resolution='BANNED' THEN 0 ELSE 1 END,created_at \
                DESC,id
                """)) {
            query.setString(1, user.id().toString());
            query.setLong(2, now.toEpochMilli());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String reason = rows.getString(1);
                    if (reason != null && !reason.isBlank()) reasons.add(reason.trim());
                }
            }
        }
        if (!reasons.isEmpty()) return String.join(" · ", reasons);
        if (banned) return "Baneo administrativo sin motivo registrado.";
        return user.status() == UserStatus.SUSPENDED
                ? "Suspensión administrativa sin motivo registrado." : null;
    }

    private static String skinHash(Connection connection, UUID user) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT hash FROM textures WHERE user_id=? AND type='skin'")) {
            query.setString(1, user.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    // =========================================================================
    // CURRENT OPERATOR AND LAST-ADMIN CHECKS
    // =========================================================================

    private static void requirePermission(AuthService.AuthenticatedUser actor,
            Permission permission) {
        if (actor == null || actor.user() == null || actor.permissions() == null
                || !actor.permissions().contains(permission)) {
            throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                    "No tienes permiso para esta operación.");
        }
    }

    private static void requireAdmin(AuthService.AuthenticatedUser actor) {
        requirePermission(actor, Permission.SYSTEM_ADMIN);
        if (actor.user().role() != Role.ADMIN) {
            throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                    "Solo un administrador puede modificar cuentas.");
        }
    }

    private static void requireCurrentAdmin(Connection connection,
            AuthService.AuthenticatedUser actor, Instant now) throws SQLException {
        requireCurrentActor(connection, actor, Permission.SYSTEM_ADMIN, now);
    }

    private static void requireCurrentActor(Connection connection,
            AuthService.AuthenticatedUser actor, Permission permission,
            Instant now) throws SQLException {
        try (PreparedStatement session = connection.prepareStatement(
                "SELECT 1 FROM sessions WHERE id=? AND user_id=? AND "
                + "revoked_at IS NULL AND expires_at>?")) {
            session.setString(1, actor.sessionId());
            session.setString(2, actor.user().id().toString());
            session.setLong(3, now.toEpochMilli());
            try (ResultSet rows = session.executeQuery()) {
                if (!rows.next()) {
                    throw ServiceException.unauthorized("INVALID_SESSION",
                            "La sesión ya no es válida.");
                }
            }
        }
        AccountRestrictions.refreshExpired(connection, actor.user().id(), now);
        User current = requireUser(connection, actor.user().id());
        if (current.status() != UserStatus.ACTIVE || AccountRestrictions.hasActiveBan(
                connection, current.id(), now) || AccountRestrictions.hasActiveSuspension(
                connection, current.id(), now)) {
            throw ServiceException.forbidden("ACCOUNT_UNAVAILABLE",
                    "La cuenta no está disponible.");
        }
        if (!Permission.forRole(current.role()).contains(permission)) {
            throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                    "No tienes permiso para esta operación.");
        }
    }

    private static User requireUser(Connection connection, UUID id) throws SQLException {
        if (id == null) throw ServiceException.badRequest("INVALID_USER",
                "El usuario no es válido.");
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,username,email,role,status,created_at,"
                + "updated_at FROM users WHERE id=?")) {
            query.setString(1, id.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw ServiceException.notFound("USER_NOT_FOUND",
                        "El usuario no existe.");
                return new User(UUID.fromString(rows.getString("id")), rows.getString(
                        "username"), rows.getString("email"), Role.valueOf(rows.getString(
                        "role")), UserStatus.valueOf(rows.getString("status")),
                        Instant.ofEpochMilli(rows.getLong("created_at")),
                        Instant.ofEpochMilli(rows.getLong("updated_at")));
            }
        }
    }

    private static boolean isUsableAdmin(Connection connection,
            User user, Instant now) throws SQLException {
        return user.role() == Role.ADMIN && user.status() == UserStatus.ACTIVE
                && !AccountRestrictions.hasActiveBan(connection, user.id(), now)
                && !AccountRestrictions.hasActiveSuspension(connection, user.id(), now);
    }

    private static int countUsableAdmins(Connection connection,
            Instant now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement( """
                SELECT COUNT(*) FROM users u WHERE role='ADMIN' AND status='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM account_sanctions s WHERE s.user_id=u.id
                    AND (s.resolution='BANNED' OR s.resolution IS NULL)
                    AND (s.expires_at IS NULL OR s.expires_at>?))
                """)) {
            query.setLong(1, now.toEpochMilli());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    // =========================================================================
    // TOKEN REVOCATION AND AUDIT EVENTS
    // =========================================================================

    private static void invalidateAccountRequests(Connection connection,
            UUID target, boolean allTokens, Instant now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement((
                "UPDATE account_tokens SET used_at=? WHERE user_id=? "
                + "AND used_at IS NULL") + (allTokens ? ""
                : " AND type='PASSWORD_RESET'"))) {
            update.setLong(1, now.toEpochMilli());
            update.setString(2, target.toString());
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE email_change_requests SET used_at=? WHERE "
                + "user_id=? AND used_at IS NULL")) {
            update.setLong(1, now.toEpochMilli());
            update.setString(2, target.toString());
            update.executeUpdate();
        }
    }

    private static void audit(Connection connection, UUID actor, UUID target,
            String action, Instant now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO audit_events(id,actor_user_id," +
                "target_user_id,action,details,created_at) VALUES(?," + "?,?,?,?,?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, actor.toString());
            insert.setString(3, target.toString());
            insert.setString(4, action);
            insert.setString(5, "{}");
            insert.setLong(6, now.toEpochMilli());
            insert.executeUpdate();
        }
    }

    private static ServiceException databaseFailure() {
        // SQL exception text can contain account values;
        // never send or print it for credential operations.
        return new ServiceException(500, "DATABASE_ERROR",
                "No se pudo completar la operación.");
    }
}

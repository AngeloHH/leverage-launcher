package dev.leverage.service;

import com.google.gson.Gson;
import dev.leverage.db.Database;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.security.SecureTokens;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthService {
    private static final Gson GSON = new Gson();
    private static final String GENERIC_LOGIN_ERROR =
            "Usuario, correo o contraseña incorrectos.";
    private static final Duration VERIFICATION_CODE_TTL = Duration.ofMinutes(10);
    private static final int VERIFICATION_CODE_MAX_ATTEMPTS = 5;

    private final Database database;
    private final PasswordHasher passwordHasher;
    private final RecoveryMailer mailer;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Duration deviceSessionTtl;
    private final Duration gameTicketTtl;
    private final Duration verificationTtl;
    private final Duration recoveryTtl;
    private final boolean registrationEnabled;
    private final String dummyPasswordHash;

    public AuthService(Database database, PasswordHasher passwordHasher,
            RecoveryMailer mailer, Clock clock, Duration sessionTtl,
            Duration verificationTtl, Duration recoveryTtl, boolean registrationEnabled) {
        this(database, passwordHasher, mailer, clock, sessionTtl,
                Duration.ofDays(30), Duration.ofSeconds(60),
                verificationTtl, recoveryTtl, registrationEnabled);
    }

    public AuthService(Database database, PasswordHasher passwordHasher,
            RecoveryMailer mailer, Clock clock, Duration sessionTtl,
            Duration deviceSessionTtl, Duration gameTicketTtl, Duration verificationTtl,
            Duration recoveryTtl, boolean registrationEnabled) {
        this.database = database;
        this.passwordHasher = passwordHasher;
        this.mailer = mailer;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.deviceSessionTtl = deviceSessionTtl;
        this.gameTicketTtl = gameTicketTtl;
        this.verificationTtl = verificationTtl;
        this.recoveryTtl = recoveryTtl;
        this.registrationEnabled = registrationEnabled;
        char[] dummy = "not-a-real-account-password".toCharArray();
        try {
            this.dummyPasswordHash = passwordHasher.hash(dummy);
        } finally {
            Arrays.fill(dummy, '\0');
        }
    }

    // Uses the same store, clock and password policy as regular account operations.
    public AdminUserService administrativeUsers() {
        return new AdminUserService(database, clock, passwordHasher);
    }

    public NewsService news() {
        return new NewsService(database, clock);
    }

    // =========================================================================
    // REGISTRATION AND ACCOUNT CHANGES
    // =========================================================================

    public User register(String usernameValue, String emailValue, String passwordValue) {
        if (!registrationEnabled) {
            throw ServiceException.forbidden("REGISTRATION_DISABLED",
                    "El registro público está deshabilitado.");
        }
        String username = InputValidation.username(usernameValue);
        String email = InputValidation.email(emailValue);
        char[] password = InputValidation.password(passwordValue);
        String passwordHash;
        try {
            passwordHash = passwordHasher.hash(password);
        } finally {
            Arrays.fill(password, '\0');
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(verificationTtl);
        String verificationToken = SecureTokens.create();
        String verificationCode = SecureTokens.createVerificationCode();
        Instant codeExpiresAt = now.plus(verificationTtl.compareTo(
                VERIFICATION_CODE_TTL) < 0 ? verificationTtl : VERIFICATION_CODE_TTL);
        User user = new User(UUID.randomUUID(), username, email, Role.USER,
                UserStatus.PENDING_VERIFICATION, now, now);

        try {
            database.transaction(connection -> {
                insertUser(connection, user, passwordHash, now);
                insertAccountToken(connection, user.id(), "EMAIL_VERIFY",
                        verificationToken, now, expiresAt);
                insertVerificationCode(connection, verificationToken,
                        verificationCode, codeExpiresAt);
                audit(connection, null, user.id(), "USER_REGISTERED", Map.of());
                return null;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw ServiceException.conflict("ACCOUNT_EXISTS",
                        "El usuario o correo ya está registrado.");
            }
            throw databaseFailure(exception);
        }

        deliverVerification(user, verificationToken, expiresAt,
                verificationCode, codeExpiresAt);
        return user;
    }

    public void requestEmailVerification(String emailValue) {
        String normalizedEmail = normalizeIdentifier(emailValue);
        try {
            String token = SecureTokens.create();
            String code = SecureTokens.createVerificationCode();
            Instant now = clock.instant();
            Instant expiresAt = now.plus(verificationTtl);
            Instant codeExpiresAt = now.plus(verificationTtl.compareTo(
                    VERIFICATION_CODE_TTL) < 0 ? verificationTtl : VERIFICATION_CODE_TTL);
            UserRow row = database.transaction(connection -> {
                UserRow pending = findByEmail(connection, normalizedEmail);
                if (pending != null
                        && pending.user().status() == UserStatus.PENDING_VERIFICATION) {
                    insertAccountToken(connection, pending.user().id(),
                            "EMAIL_VERIFY", token, now, expiresAt);
                    insertVerificationCode(connection, token, code, codeExpiresAt);
                }
                return pending;
            });
            if (row == null || row.user().status() != UserStatus.PENDING_VERIFICATION) {
                return;
            }
            deliverVerification(row.user(), token, expiresAt, code, codeExpiresAt);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public User changeUsername(AuthenticatedUser actor,
            String usernameValue, String currentPasswordValue) {
        requirePermission(actor, Permission.ACCOUNT_USE);
        String username = InputValidation.username(usernameValue);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                User current = requireCurrentPassword(connection, actor,
                        currentPasswordValue, now).user();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET username=?, updated_at=? WHERE id=?")) {
                    update.setString(1, username);
                    update.setLong(2, now.toEpochMilli());
                    update.setString(3, current.id().toString());
                    update.executeUpdate();
                }
                audit(connection, current.id(), current.id(),
                        "USERNAME_CHANGED", Map.of());
                return new User(current.id(), username, current.email(), current.role(),
                        current.status(), current.createdAt(), now);
            });
        } catch (SQLException failure) {
            if (isConstraintViolation(failure))throw ServiceException.conflict(
                    "USERNAME_EXISTS", "Ese nombre de usuario ya está registrado.");
            throw databaseFailure(failure);
        }
    }

    public void changePassword(AuthenticatedUser actor,
            String currentPasswordValue, String newPasswordValue) {
        requirePermission(actor, Permission.ACCOUNT_USE);
        char[] password = InputValidation.password(newPasswordValue);
        try {
            Instant now = clock.instant();
            database.transaction(connection -> {
                User current = requireCurrentPassword(connection, actor,
                        currentPasswordValue, now).user();
                String hash = passwordHasher.hash(password);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET password_hash=?, "
                        + "password_changed_at=?, updated_at=? WHERE id=?")) {
                    update.setString(1, hash);
                    update.setLong(2, now.toEpochMilli());
                    update.setLong(3, now.toEpochMilli());
                    update.setString(4, current.id().toString());
                    update.executeUpdate();
                }
                AccountRestrictions.revokeAll(connection, current.id(), now);
                try (PreparedStatement invalidate = connection.prepareStatement(
                        "UPDATE account_tokens SET used_at=? WHERE user_id=? "
                        + "AND type='PASSWORD_RESET' AND used_at IS NULL")) {
                    invalidate.setLong(1, now.toEpochMilli());
                    invalidate.setString(2, current.id().toString());
                    invalidate.executeUpdate();
                }
                try (PreparedStatement invalidate = connection.prepareStatement(
                        "UPDATE email_change_requests SET used_at=? WHERE "
                        + "user_id=? AND used_at IS NULL")) {
                    invalidate.setLong(1, now.toEpochMilli());
                    invalidate.setString(2, current.id().toString());
                    invalidate.executeUpdate();
                }
                audit(connection, current.id(), current.id(),
                        "PASSWORD_CHANGED", Map.of());
                return null;
            });
        } catch (SQLException failure) {
            throw databaseFailure(failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private UserRow requireCurrentPassword(Connection connection, AuthenticatedUser actor,
            String value, Instant now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM sessions WHERE id=? AND user_id=? AND "
                + "revoked_at IS NULL AND expires_at>?")) {
            query.setString(1, actor.sessionId());
            query.setString(2, actor.user().id().toString());
            query.setLong(3, now.toEpochMilli());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next())throw ServiceException.unauthorized("INVALID_SESSION",
                        "La sesión ya no es válida.");
            }
        }
        UserRow row = findById(connection, actor.user().id());
        if (row == null || row.user().status() != UserStatus.ACTIVE)
                throw ServiceException.forbidden("ACCOUNT_UNAVAILABLE",
                "La cuenta no está disponible.");
        if (value == null || value.codePointCount(0, value.length())>128)
                throw ServiceException.forbidden("CURRENT_PASSWORD_INCORRECT",
                "La contraseña actual no es correcta.");
        char[] password = value.toCharArray();
        try {
            if (!passwordHasher.verify(password, row.passwordHash()))
                    throw ServiceException.forbidden("CURRENT_PASSWORD_INCORRECT",
                    "La contraseña actual no es correcta.");
        } finally {
            Arrays.fill(password, '\0');
        }
        return row;
    }

    public void requestEmailChange(AuthenticatedUser actor,
            String newEmailValue, String currentPasswordValue) {
        requirePermission(actor, Permission.ACCOUNT_USE);
        String newEmail = InputValidation.email(newEmailValue);
        String normalizedEmail = InputValidation.normalizedEmail(newEmail);
        if (currentPasswordValue == null || currentPasswordValue.codePointCount(0,
                currentPasswordValue.length()) > 128) {
            throw ServiceException.forbidden("INVALID_PASSWORD",
                    "La contraseña actual no es correcta.");
        }
        char[] currentPassword = currentPasswordValue.toCharArray();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(verificationTtl);
        String token = SecureTokens.create();
        User current;
        try {
            current = database.transaction(connection -> {
                UserRow row = findById(connection, actor.user().id());
                if (row == null || !passwordHasher.verify(currentPassword,
                        row.passwordHash())) {
                    throw ServiceException.forbidden("INVALID_PASSWORD",
                            "La contraseña actual no es correcta.");
                }
                if (InputValidation.normalizedEmail(row.user().email()).equals(
                        normalizedEmail)) {
                    throw ServiceException.conflict("EMAIL_UNCHANGED",
                            "Ese ya es el correo de la cuenta.");
                }
                if (findByEmail(connection, normalizedEmail) != null) {
                    throw ServiceException.conflict("EMAIL_EXISTS",
                            "Ese correo ya está registrado.");
                }
                insertEmailChangeRequest(connection, row.user().id(),
                        newEmail, token, now, expiresAt);
                audit(connection, row.user().id(), row.user().id(),
                        "EMAIL_CHANGE_REQUESTED", Map.of());
                return row.user();
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw ServiceException.conflict("EMAIL_EXISTS",
                        "Ese correo ya está registrado.");
            }
            throw databaseFailure(exception);
        } finally {
            Arrays.fill(currentPassword, '\0');
        }
        try {
            mailer.sendEmailChangeVerification(newEmail,
                    current.username(), token, expiresAt);
        } catch (Exception exception) {
            System.err.println(
                    "No se pudo entregar la confirmación del cambio de correo: "
                    + exception.getMessage());
        }
    }

    // =========================================================================
    // EMAIL CONFIRMATION
    // =========================================================================

    public User confirmEmail(String tokenValue) {
        String token = InputValidation.token(tokenValue);
        byte[] tokenHash = SecureTokens.hash(token);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                TokenRow tokenRow = findAccountToken(connection,
                        tokenHash, "EMAIL_VERIFY");
                if (tokenRow == null) {
                    return confirmEmailChange(connection, tokenHash, now);
                }
                requireUsableToken(tokenRow, now);
                try (PreparedStatement updateUser = connection.prepareStatement(
                        "UPDATE users SET status='ACTIVE', updated_at=? "
                        + "WHERE id=? AND status='PENDING_VERIFICATION'")) {
                    updateUser.setLong(1, now.toEpochMilli());
                    updateUser.setString(2, tokenRow.user().id().toString());
                    if (updateUser.executeUpdate() != 1) {
                        throw ServiceException.badRequest("INVALID_TOKEN",
                                "El token no es válido o ya fue utilizado.");
                    }
                }
                consumeToken(connection, tokenRow.id(), now);
                audit(connection, tokenRow.user().id(), tokenRow.user().id(),
                        "EMAIL_VERIFIED", Map.of());
                return new User(tokenRow.user().id(), tokenRow.user().username(),
                        tokenRow.user().email(), tokenRow.user().role(),
                        UserStatus.ACTIVE, tokenRow.user().createdAt(), now);
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw ServiceException.conflict("EMAIL_EXISTS",
                        "Ese correo ya está registrado.");
            }
            throw databaseFailure(exception);
        }
    }

    public User confirmEmailCode(String emailValue, String code) {
        String email = InputValidation.normalizedEmail(InputValidation.email(emailValue));
        if (code == null || !code.matches("[0-9]{6}")) {
            throw ServiceException.badRequest("INVALID_TOKEN",
                    "Escribe el código de seis dígitos.");
        }
        Instant now = clock.instant();
        try {
            User verified = database.transaction(connection -> {
                UserRow account = findByEmail(connection, email);
                if (account == null || account.user().status() !=
                        UserStatus.PENDING_VERIFICATION) return null;
                try (PreparedStatement query = connection.prepareStatement( """
                        SELECT t.id, t.code_hash FROM account_tokens t
                        WHERE t.user_id=? AND t.type='EMAIL_VERIFY' AND t.used_at IS NULL
                          AND t.expires_at>? AND t.code_expires_at>? AND t.attempts<?
                        """)) {
                    query.setString(1, account.user().id().toString());
                    query.setLong(2, now.toEpochMilli());
                    query.setLong(3, now.toEpochMilli());
                    query.setInt(4, VERIFICATION_CODE_MAX_ATTEMPTS);
                    String tokenId;
                    byte[] expected;
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) return null;
                        tokenId = row.getString("id");
                        expected = row.getBytes("code_hash");
                    }
                    try (PreparedStatement attempt = connection.prepareStatement( """
                            UPDATE account_tokens SET attempts=attempts+1
                            WHERE id=? AND attempts<?
                            """)) {
                        attempt.setString(1, tokenId);
                        attempt.setInt(2, VERIFICATION_CODE_MAX_ATTEMPTS);
                        if (attempt.executeUpdate() != 1) return null;
                    }
                    // Return failures so the attempt increment
                    // is committed instead of rolled back.
                    if (!MessageDigest.isEqual(expected, SecureTokens.hash(tokenId
                            + ":" + code))) return null;
                    try (PreparedStatement activate = connection.prepareStatement( """
                            UPDATE users SET status='ACTIVE', updated_at=?
                            WHERE id=? AND status='PENDING_VERIFICATION'
                            """)) {
                        activate.setLong(1, now.toEpochMilli());
                        activate.setString(2, account.user().id().toString());
                        if (activate.executeUpdate() != 1) return null;
                    }
                    consumeToken(connection, tokenId, now);
                    User user = account.user();
                    audit(connection, user.id(), user.id(), "EMAIL_VERIFIED", Map.of());
                    return new User(user.id(), user.username(), user.email(),
                            user.role(), UserStatus.ACTIVE, user.createdAt(), now);
                }
            });
            if (verified == null) {
                throw ServiceException.badRequest("INVALID_TOKEN",
                        "El código no es válido, venció o agotó sus "
                        + "intentos. Solicita uno nuevo.");
            }
            return verified;
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private static User confirmEmailChange(Connection connection,
            byte[] tokenHash, Instant now) throws SQLException {
        EmailChangeTokenRow request = findEmailChangeRequest(connection, tokenHash);
        if (request == null || request.used() || !request.expiresAt().isAfter(now)) {
            throw ServiceException.badRequest("INVALID_TOKEN",
                    "El token no es válido, venció o ya fue utilizado.");
        }
        try (PreparedStatement update = connection.prepareStatement( """
                UPDATE users SET email=?, updated_at=? WHERE id=?
                """)) {
            update.setString(1, request.newEmail());
            update.setLong(2, now.toEpochMilli());
            update.setString(3, request.user().id().toString());
            if (update.executeUpdate() != 1) {
                throw ServiceException.badRequest("INVALID_TOKEN",
                        "El token no es válido.");
            }
        }
        try (PreparedStatement consume = connection.prepareStatement(
                "UPDATE email_change_requests SET used_at=? WHERE id="
                + "? AND used_at IS NULL")) {
            consume.setLong(1, now.toEpochMilli());
            consume.setString(2, request.id());
            if (consume.executeUpdate() != 1) {
                throw ServiceException.badRequest("INVALID_TOKEN",
                        "El token ya fue utilizado.");
            }
        }
        try (PreparedStatement invalidate = connection.prepareStatement( """
                UPDATE email_change_requests SET used_at=?
                WHERE user_id=? AND used_at IS NULL
                """)) {
            invalidate.setLong(1, now.toEpochMilli());
            invalidate.setString(2, request.user().id().toString());
            invalidate.executeUpdate();
        }
        AccountRestrictions.revokeAll(connection, request.user().id(), now);
        audit(connection, request.user().id(), request.user().id(),
                "EMAIL_CHANGED", Map.of());
        return new User(request.user().id(), request.user().username(),
                request.newEmail(), request.user().role(),
                request.user().status(), request.user().createdAt(), now);
    }

    // =========================================================================
    // ACCESS SESSIONS AND REMEMBERED DEVICES
    // =========================================================================

    public LoginResult login(String identifierValue, String passwordValue) {
        return login(identifierValue, passwordValue, false, "");
    }

    public LoginResult login(String identifierValue, String passwordValue,
            boolean rememberDevice, String deviceNameValue) {
        if (identifierValue == null || identifierValue.isBlank() || passwordValue == null
                || passwordValue.codePointCount(0, passwordValue.length()) > 128) {
            throw ServiceException.unauthorized("INVALID_CREDENTIALS",
                    GENERIC_LOGIN_ERROR);
        }
        String identifier = normalizeIdentifier(identifierValue);
        char[] password = passwordValue.toCharArray();
        try (Connection connection = database.open()) {
            UserRow row = findByIdentifier(connection, identifier);
            String storedHash = row == null ? dummyPasswordHash : row.passwordHash();
            boolean valid = passwordHasher.verify(password, storedHash);
            if (!valid || row == null) {
                throw ServiceException.unauthorized("INVALID_CREDENTIALS",
                        GENERIC_LOGIN_ERROR);
            }
            Instant now = clock.instant();
            AccountRestrictions.refreshExpired(connection, row.user().id(), now);
            row = findById(connection, row.user().id());
            if (row.user().status() == UserStatus.PENDING_VERIFICATION) {
                throw ServiceException.forbidden("EMAIL_NOT_VERIFIED",
                        "Debes verificar tu correo antes de iniciar sesión.");
            }
            if (row.user().status() == UserStatus.SUSPENDED) {
                throw accountRestriction(connection, row.user().id(), now);
            }

            if (passwordHasher.needsRehash(row.passwordHash())) {
                String upgraded = passwordHasher.hash(password);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET password_hash=?, updated_at=? WHERE id=?")) {
                    statement.setString(1, upgraded);
                    statement.setLong(2, clock.instant().toEpochMilli());
                    statement.setString(3, row.user().id().toString());
                    statement.executeUpdate();
                }
            }

            AccessSession access = createAccessSession(connection, row.user().id(), now);
            String refreshToken = null;
            Instant refreshExpiresAt = null;
            String deviceSessionId = null;
            if (rememberDevice) {
                String deviceName = boundedLabel(deviceNameValue,
                        "Este dispositivo", 100);
                refreshToken = SecureTokens.create();
                refreshExpiresAt = now.plus(deviceSessionTtl);
                deviceSessionId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement( """
                        INSERT INTO device_sessions(id, user_id, token_hash, \
                        device_name, created_at,
                                                    expires_at, last_used_at, revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                        """)) {
                    statement.setString(1, deviceSessionId);
                    statement.setString(2, row.user().id().toString());
                    statement.setBytes(3, SecureTokens.hash(refreshToken));
                    statement.setString(4, deviceName);
                    statement.setLong(5, now.toEpochMilli());
                    statement.setLong(6, refreshExpiresAt.toEpochMilli());
                    statement.setLong(7, now.toEpochMilli());
                    statement.executeUpdate();
                }
            }
            audit(connection, row.user().id(), row.user().id(), "SESSION_CREATED",
                    Map.of("sessionId", access.id(), "rememberDevice", rememberDevice));
            return new LoginResult(access.token(), access.expiresAt(),
                    refreshToken, refreshExpiresAt, deviceSessionId,
                    row.user(), Permission.forRole(row.user().role()));
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public LoginResult refresh(String refreshTokenValue) {
        String refreshToken = InputValidation.token(refreshTokenValue);
        byte[] refreshHash = SecureTokens.hash(refreshToken);
        Instant now = clock.instant();
        try {
            RefreshAttempt attempt = database.transaction(connection -> {
                // A sanction revokes devices too. Recognize only the
                // account proven by this unexpired refresh credential;
                // never disclose restrictions by username alone.
                try (PreparedStatement known = connection.prepareStatement( """
                        SELECT d.user_id FROM device_sessions d WHERE d.token_hash=? AND \
                        d.expires_at>?
                        UNION
                        SELECT d.user_id FROM device_refresh_history h
                        JOIN device_sessions d ON d.id=h.device_session_id
                        WHERE h.token_hash=? AND d.expires_at>?
                        LIMIT 1
                        """)) {
                    known.setBytes(1, refreshHash);
                    known.setLong(2, now.toEpochMilli());
                    known.setBytes(3, refreshHash);
                    known.setLong(4, now.toEpochMilli());
                    UUID knownUser = null;
                    try (ResultSet row = known.executeQuery()) {
                        if (row.next()) knownUser = UUID.fromString(row.getString(1));
                    }
                    if (knownUser != null) {
                        AccountRestrictions.refreshExpired(connection, knownUser, now);
                        UserRow user = findById(connection, knownUser);
                        if (user != null && user.user().status() == UserStatus.SUSPENDED)
                                throw accountRestriction(connection, knownUser, now);
                    }
                }
                DeviceSessionRow device = findDeviceSession(connection, refreshHash, now);
                if (device == null) {
                    UsedRefreshRow used = findUsedRefresh(connection, refreshHash);
                    if (used == null) {
                        return new RefreshAttempt(null, false);
                    }
                    try (PreparedStatement revoke = connection.prepareStatement(
                            "UPDATE device_sessions SET revoked_at=? WHERE id=? "
                            + "AND revoked_at IS NULL")) {
                        revoke.setLong(1, now.toEpochMilli());
                        revoke.setString(2, used.deviceSessionId());
                        revoke.executeUpdate();
                    }
                    audit(connection, used.userId(), used.userId(),
                            "REFRESH_TOKEN_REUSED", Map.of("deviceSessionId",
                            used.deviceSessionId()));
                    return new RefreshAttempt(null, true);
                }
                AccountRestrictions.refreshExpired(connection, device.user().id(), now);
                UserRow currentUser = findById(connection, device.user().id());
                device = new DeviceSessionRow(device.id(), currentUser.user());
                if (device.user().status() != UserStatus.ACTIVE) {
                    if (device.user().status() == UserStatus.SUSPENDED)
                            throw accountRestriction(connection, device.user().id(), now);
                    throw ServiceException.forbidden("ACCOUNT_UNAVAILABLE",
                            "La cuenta no está disponible.");
                }

                String rotatedToken = SecureTokens.create();
                Instant refreshExpiresAt = now.plus(deviceSessionTtl);
                try (PreparedStatement history = connection.prepareStatement( """
                        INSERT INTO device_refresh_history(token_hash, \
                        device_session_id, used_at)
                        VALUES (?, ?, ?)
                        """)) {
                    history.setBytes(1, refreshHash);
                    history.setString(2, device.id());
                    history.setLong(3, now.toEpochMilli());
                    history.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement( """
                        UPDATE device_sessions
                        SET token_hash=?, expires_at=?, last_used_at=?
                        WHERE id=? AND token_hash=? AND revoked_at IS NULL
                        """)) {
                    update.setBytes(1, SecureTokens.hash(rotatedToken));
                    update.setLong(2, refreshExpiresAt.toEpochMilli());
                    update.setLong(3, now.toEpochMilli());
                    update.setString(4, device.id());
                    update.setBytes(5, refreshHash);
                    if (update.executeUpdate() != 1) {
                        throw ServiceException.unauthorized("INVALID_REFRESH_TOKEN",
                                "La sesión recordada ya fue utilizada.");
                    }
                }
                AccessSession access = createAccessSession(connection,
                        device.user().id(), now);
                audit(connection, device.user().id(), device.user().id(),
                        "DEVICE_SESSION_REFRESHED", Map.of("deviceSessionId",
                        device.id(), "sessionId", access.id()));
                return new RefreshAttempt(new LoginResult(access.token(),
                        access.expiresAt(), rotatedToken, refreshExpiresAt, device.id(),
                        device.user(), Permission.forRole(device.user().role())), false);
            });
            if (attempt.result() != null) {
                return attempt.result();
            }
            if (attempt.reused()) {
                throw ServiceException.unauthorized("REFRESH_TOKEN_REUSED",
                        "La sesión recordada fue revocada porque se "
                        + "reutilizó un token anterior.");
            }
            throw ServiceException.unauthorized("INVALID_REFRESH_TOKEN",
                    "La sesión recordada no es válida o ha expirado.");
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public List<DeviceSession> listDeviceSessions(AuthenticatedUser actor) {
        List<DeviceSession> result = new ArrayList<>();
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement( """
                SELECT id, device_name, created_at, expires_at, last_used_at
                FROM device_sessions
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>?
                ORDER BY last_used_at DESC
                """)) {
            statement.setString(1, actor.user().id().toString());
            statement.setLong(2, clock.instant().toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DeviceSession(rows.getString("id"),
                            rows.getString("device_name"),
                            Instant.ofEpochMilli(rows.getLong("created_at")),
                            Instant.ofEpochMilli(rows.getLong("expires_at")),
                            Instant.ofEpochMilli(rows.getLong("last_used_at"))));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public void revokeDeviceSession(AuthenticatedUser actor, UUID deviceSessionId) {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement( """
                UPDATE device_sessions SET revoked_at=?
                WHERE id=? AND user_id=? AND revoked_at IS NULL
                """)) {
            statement.setLong(1, clock.instant().toEpochMilli());
            statement.setString(2, deviceSessionId.toString());
            statement.setString(3, actor.user().id().toString());
            if (statement.executeUpdate() != 1) {
                throw ServiceException.notFound("DEVICE_SESSION_NOT_FOUND",
                        "El dispositivo no existe.");
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public void revokeDeviceByRefreshToken(String refreshTokenValue) {
        String refreshToken = InputValidation.token(refreshTokenValue);
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement( """
                UPDATE device_sessions SET revoked_at=?
                WHERE token_hash=? AND revoked_at IS NULL
                """)) {
            statement.setLong(1, clock.instant().toEpochMilli());
            statement.setBytes(2, SecureTokens.hash(refreshToken));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // SINGLE-USE GAME TICKETS
    // =========================================================================

    public GameTicket createGameTicket(AuthenticatedUser actor, String serverScopeValue) {
        String serverScope = boundedLabel(serverScopeValue, null, 255);
        if (serverScope == null) {
            throw ServiceException.badRequest("MISSING_SERVER",
                    "El servidor de destino es obligatorio.");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(gameTicketTtl);
        String token = SecureTokens.create();
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO game_tickets(id, user_id, session_id, token_hash, \
                server_scope,
                                         created_at, expires_at, used_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, NULL
                WHERE EXISTS (
                    SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id
                    WHERE s.id=? AND s.user_id=? AND s.revoked_at IS NULL AND \
                s.expires_at>?
                      AND u.status='ACTIVE'
                )
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actor.user().id().toString());
            statement.setString(3, actor.sessionId());
            statement.setBytes(4, SecureTokens.hash(token));
            statement.setString(5, serverScope);
            statement.setLong(6, now.toEpochMilli());
            statement.setLong(7, expiresAt.toEpochMilli());
            statement.setString(8, actor.sessionId());
            statement.setString(9, actor.user().id().toString());
            statement.setLong(10, now.toEpochMilli());
            if (statement.executeUpdate() != 1) {
                throw ServiceException.unauthorized("INVALID_SESSION",
                        "La sesión ya no está disponible.");
            }
            return new GameTicket(token, serverScope, expiresAt);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public GameIdentity consumeGameTicket(String ticketValue, String serverScopeValue) {
        String ticket = InputValidation.token(ticketValue);
        String serverScope = boundedLabel(serverScopeValue, null, 255);
        if (serverScope == null) {
            throw ServiceException.badRequest("MISSING_SERVER",
                    "El servidor de destino es obligatorio.");
        }
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement( """
                        SELECT g.id AS ticket_id,
                               u.id, u.username, u.email, u.role, u.status,
                               u.created_at, u.updated_at, u.password_hash
                        FROM game_tickets g
                        JOIN users u ON u.id=g.user_id
                        WHERE g.token_hash=? AND g.server_scope=? AND g.used_at IS NULL \
                        AND g.expires_at>?
                        """)) {
                    statement.setBytes(1, SecureTokens.hash(ticket));
                    statement.setString(2, serverScope);
                    statement.setLong(3, now.toEpochMilli());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            throw ServiceException.unauthorized("INVALID_GAME_TICKET",
                                    "El ticket de juego no es válido o ha expirado.");
                        }
                        User user = readUserRow(row).user();
                        AccountRestrictions.refreshExpired(connection, user.id(), now);
                        user = findById(connection, user.id()).user();
                        if (user.status() != UserStatus.ACTIVE) {
                            throw ServiceException.forbidden("ACCOUNT_UNAVAILABLE",
                                    "La cuenta no está disponible.");
                        }
                        try (PreparedStatement consume = connection.prepareStatement(
                                "UPDATE game_tickets SET used_at=? WHERE id=? AND "
                                + "used_at IS NULL")) {
                            consume.setLong(1, now.toEpochMilli());
                            consume.setString(2, row.getString("ticket_id"));
                            if (consume.executeUpdate() != 1) {
                                throw ServiceException.unauthorized("INVALID_GAME_TICKET",
                                        "El ticket de juego ya fue utilizado.");
                            }
                        }
                        return new GameIdentity(user.id(), user.username(),
                                user.role(), serverScope);
                    }
                }
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public AuthenticatedUser authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw ServiceException.unauthorized("AUTHENTICATION_REQUIRED",
                    "Se requiere una sesión válida.");
        }
        Instant now = clock.instant();
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement( """
                SELECT s.id AS session_id,
                       u.id, u.username, u.email, u.role, u.status, u.created_at, \
                u.updated_at, u.password_hash
                FROM sessions s
                JOIN users u ON u.id=s.user_id
                WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?
                """)) {
            statement.setBytes(1, SecureTokens.hash(accessToken));
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw ServiceException.unauthorized("INVALID_SESSION",
                            "La sesión no es válida o ha expirado.");
                }
                UserRow row = readUserRow(result);
                AccountRestrictions.refreshExpired(connection, row.user().id(), now);
                row = findById(connection, row.user().id());
                if (row.user().status() != UserStatus.ACTIVE) {
                    throw ServiceException.forbidden("ACCOUNT_UNAVAILABLE",
                            "La cuenta no está disponible.");
                }
                String sessionId = result.getString("session_id");
                try (PreparedStatement touch = connection.prepareStatement(
                        "UPDATE sessions SET last_seen_at=? WHERE id=?")) {
                    touch.setLong(1, now.toEpochMilli());
                    touch.setString(2, sessionId);
                    touch.executeUpdate();
                }
                return new AuthenticatedUser(row.user(), sessionId,
                        Permission.forRole(row.user().role()));
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                "UPDATE sessions SET revoked_at=? WHERE token_hash=? "
                + "AND revoked_at IS NULL")) {
            statement.setLong(1, clock.instant().toEpochMilli());
            statement.setBytes(2, SecureTokens.hash(accessToken));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // PASSWORD RECOVERY
    // =========================================================================

    public void requestPasswordReset(String emailValue) {
        String normalizedEmail = normalizeIdentifier(emailValue);
        try (Connection connection = database.open()) {
            UserRow row = findByEmail(connection, normalizedEmail);
            if (row == null || row.user().status() == UserStatus.PENDING_VERIFICATION) {
                return;
            }
            Instant now = clock.instant();
            Instant expiresAt = now.plus(recoveryTtl);
            String token = SecureTokens.create();
            insertAccountToken(connection, row.user().id(),
                    "PASSWORD_RESET", token, now, expiresAt);
            try {
                mailer.sendPasswordReset(row.user().email(),
                        row.user().username(), token, expiresAt);
            } catch (Exception exception) {
                System.err.println("No se pudo entregar el correo de recuperación: "
                        + exception.getMessage());
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public void confirmPasswordReset(String tokenValue, String passwordValue) {
        String token = InputValidation.token(tokenValue);
        char[] password = InputValidation.password(passwordValue);
        String passwordHash;
        try {
            passwordHash = passwordHasher.hash(password);
        } finally {
            Arrays.fill(password, '\0');
        }

        Instant now = clock.instant();
        try {
            database.transaction(connection -> {
                TokenRow tokenRow = findAccountToken(connection,
                        SecureTokens.hash(token), "PASSWORD_RESET");
                requireUsableToken(tokenRow, now);
                try (PreparedStatement update = connection.prepareStatement( """
                        UPDATE users SET password_hash=?, password_changed_at=?, \
                        updated_at=? WHERE id=?
                        """)) {
                    update.setString(1, passwordHash);
                    update.setLong(2, now.toEpochMilli());
                    update.setLong(3, now.toEpochMilli());
                    update.setString(4, tokenRow.user().id().toString());
                    update.executeUpdate();
                }
                consumeToken(connection, tokenRow.id(), now);
                try (PreparedStatement revoke = connection.prepareStatement(
                        "UPDATE sessions SET revoked_at=? WHERE user_id=? "
                        + "AND revoked_at IS NULL")) {
                    revoke.setLong(1, now.toEpochMilli());
                    revoke.setString(2, tokenRow.user().id().toString());
                    revoke.executeUpdate();
                }
                try (PreparedStatement revokeDevices = connection.prepareStatement(
                        "UPDATE device_sessions SET revoked_at=? WHERE "
                        + "user_id=? AND revoked_at IS NULL")) {
                    revokeDevices.setLong(1, now.toEpochMilli());
                    revokeDevices.setString(2, tokenRow.user().id().toString());
                    revokeDevices.executeUpdate();
                }
                audit(connection, tokenRow.user().id(), tokenRow.user().id(),
                        "PASSWORD_RESET", Map.of());
                return null;
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // ACCOUNT SEARCH AND ADMINISTRATION
    // =========================================================================

    public List<User> listUsers(AuthenticatedUser actor) {
        return listUsers(actor, null);
    }

    public List<User> listUsers(AuthenticatedUser actor, Boolean suspended) {
        requirePermission(actor, Permission.USERS_READ);
        List<User> users = new ArrayList<>();
        String statusFilter = suspended == null ? "" : suspended
                ? " WHERE status='SUSPENDED'" : " WHERE status<>'SUSPENDED'";
        try (Connection connection = database.open()) {
            AccountRestrictions.refreshAllExpired(connection, clock.instant());
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery( """
                         SELECT id, username, email, role, status, created_at, \
                         updated_at, password_hash
                         FROM users
                         """
            + statusFilter + " ORDER BY created_at ASC, id ASC")) {
                while (result.next()) {
                    users.add(readUserRow(result).user());
                }
            }
            return List.copyOf(users);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // Paged, literal substring lookup. Search values
    // are never interpreted as SQL or LIKE wildcards.
    public UsersPage searchUsers(AuthenticatedUser actor, String query,
            int page, int pageSize, Boolean suspended) {
        return searchUsers(actor, query, page, pageSize, suspended, null, null);
    }

    public UsersPage searchUsers(AuthenticatedUser actor, String query, int page,
            int pageSize, Boolean suspended, String sort, String direction) {
        requirePermission(actor, Permission.USERS_READ);
        String order = switch (sort == null ? "createdAt" : sort) {
            case "username" -> "username COLLATE NOCASE";
            case "email" -> "email COLLATE NOCASE";
            case "createdAt" -> "created_at";
            case "lastSeenAt" -> ("(SELECT MAX(activity) FROM (SELECT last_seen_at AS "
                    + "activity FROM sessions WHERE user_id=users.id UNION "
                    + "ALL SELECT last_used_at AS activity FROM "
                    + "device_sessions WHERE user_id=users.id))");
            case "status" -> ("CASE WHEN EXISTS(SELECT 1 FROM account_sanctions a "
                    + "WHERE a.user_id=users.id AND resolution='BANNED' "
                    + "AND (expires_at IS NULL OR expires_at>")
                    +clock.instant().toEpochMilli()
                    + (")) THEN 'Baneado' WHEN status='ACTIVE' THEN "
                    + "'Activo' WHEN status='SUSPENDED' THEN 'Suspendido' "
                    + "ELSE 'Sin verificar' END");
            default -> throw ServiceException.badRequest("INVALID_SORT",
                    "La columna de ordenación no es válida.");
        };
        String orderDirection = direction == null?"asc":direction;
        if (!orderDirection.equals("asc")&&!orderDirection.equals("desc"))
                throw ServiceException.badRequest("INVALID_SORT_DIRECTION",
                "La dirección debe ser asc o desc.");
        String orderClause = " ORDER BY "+order+" "+orderDirection
                +", id ASC LIMIT ? OFFSET ?";
        String search = query == null ? "" : query.trim();
        if (search.length() > 200) {
            throw ServiceException.badRequest("INVALID_SEARCH",
                    "La búsqueda admite hasta 200 caracteres.");
        }
        if (pageSize != 10 && pageSize != 25 && pageSize != 50 && pageSize != 100) {
            throw ServiceException.badRequest("INVALID_PAGE_SIZE",
                    "El tamaño de página debe ser 10, 25, 50 o 100.");
        }
        String pattern = "%" + search.replace("\\", "\\\\").replace("%",
                "\\%").replace("_", "\\_") + "%";
        String filter = " WHERE (username LIKE ? ESCAPE '\\' COLLATE NOCASE"
                + " OR email LIKE ? ESCAPE '\\' COLLATE NOCASE)" + (suspended == null ? ""
                : suspended ? " AND status='SUSPENDED'" : " AND status<>'SUSPENDED'");
        try {
            // Count and rows share one snapshot, including
            // expiration of temporary suspensions.
            return database.transaction(connection -> {
                AccountRestrictions.refreshAllExpired(connection, clock.instant());
                long total;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM users" + filter)) {
                    count.setString(1, pattern);
                    count.setString(2, pattern);
                    try (ResultSet result = count.executeQuery()) {
                        result.next();
                        total = result.getLong(1);
                    }
                }
                int totalPages = (int) Math.min(Integer.MAX_VALUE, Math.max(1,
                        (total + pageSize - 1) / pageSize));
                int actualPage = Math.max(1, Math.min(page, totalPages));
                List<User> users = new ArrayList<>();
                try (PreparedStatement rows = connection.prepareStatement( """
                        SELECT id, username, email, role, status, created_at, \
                        updated_at, password_hash
                        FROM users
                        """
                + filter + orderClause)) {
                    rows.setString(1, pattern);
                    rows.setString(2, pattern);
                    rows.setInt(3, pageSize);
                    rows.setLong(4, (long)(actualPage - 1) * pageSize);
                    try (ResultSet result = rows.executeQuery()) {
                        while (result.next()) users.add(readUserRow(result).user());
                    }
                }
                return new UsersPage(List.copyOf(users), actualPage,
                        pageSize, total, totalPages);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public record UsersPage(List<User> users, int page,
            int pageSize, long total, int totalPages) { }

    public User changeRole(AuthenticatedUser actor, UUID targetId, Role newRole) {
        requirePermission(actor, Permission.ROLES_MANAGE);
        if (actor.user().id().equals(targetId)) {
            throw ServiceException.conflict("SELF_ROLE_CHANGE",
                    "No puedes cambiar tu propio rol.");
        }
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                UserRow target = findById(connection, targetId);
                if (target == null) {
                    throw ServiceException.notFound("USER_NOT_FOUND",
                            "El usuario no existe.");
                }
                if (target.user().role() == Role.ADMIN && newRole != Role.ADMIN
                        && countAdmins(connection) <= 1) {
                    throw ServiceException.conflict("LAST_ADMIN",
                            "No se puede retirar el último administrador.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET role=?, updated_at=? WHERE id=?")) {
                    statement.setString(1, newRole.name());
                    statement.setLong(2, now.toEpochMilli());
                    statement.setString(3, targetId.toString());
                    statement.executeUpdate();
                }
                audit(connection, actor.user().id(), targetId, "ROLE_CHANGED", Map.of(
                        "from", target.user().role().name(), "to", newRole.name()));
                return new User(target.user().id(), target.user().username(),
                        target.user().email(), newRole, target.user().status(),
                        target.user().createdAt(), now);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public User setSuspended(AuthenticatedUser actor, UUID targetId, boolean suspended) {
        requirePermission(actor, Permission.USERS_MODERATE);
        if (actor.user().id().equals(targetId)) {
            throw ServiceException.conflict("SELF_SUSPENSION",
                    "No puedes suspender tu propia cuenta.");
        }
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                UserRow target = findById(connection, targetId);
                if (target == null) {
                    throw ServiceException.notFound("USER_NOT_FOUND",
                            "El usuario no existe.");
                }
                if (!actor.user().role().atLeast(Role.ADMIN)
                        && target.user().role().atLeast(actor.user().role())) {
                    throw ServiceException.forbidden("INSUFFICIENT_ROLE",
                            "Un moderador no puede modificar una cuenta de igual "
                            + "o mayor nivel.");
                }
                if (target.user().status() == UserStatus.PENDING_VERIFICATION) {
                    throw ServiceException.conflict("EMAIL_NOT_VERIFIED",
                            "No se puede cambiar la suspensión antes de "
                            + "verificar el correo.");
                }
                if (!suspended && AccountRestrictions.hasActiveBan(connection,
                        targetId, now)) {
                    throw ServiceException.conflict("ACCOUNT_BANNED",
                            "No se puede reactivar una cuenta con un ban activo.");
                }
                if (!suspended && AccountRestrictions.hasActiveSuspension(connection,
                        targetId, now)) {
                    try (PreparedStatement resolveManual = connection.prepareStatement(
                    """
                            UPDATE account_sanctions
                            SET resolved_at=?, resolution='LIFTED', created_by=?
                            WHERE user_id=? AND by_mod=0 AND resolution IS NULL
                            """)) {
                        resolveManual.setLong(1, now.toEpochMilli());
                        resolveManual.setString(2, actor.user().id().toString());
                        resolveManual.setString(3, targetId.toString());
                        resolveManual.executeUpdate();
                    }
                    if (AccountRestrictions.hasActiveSuspension(connection,
                            targetId, now)) {
                        throw ServiceException.conflict("ACTIVE_MOD_SUSPENSION",
                                "La suspensión por mods debe resolverse desde su "
                                + "expediente.");
                    }
                }
                UserStatus newStatus = suspended ? UserStatus.SUSPENDED
                        : UserStatus.ACTIVE;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET status=?, updated_at=? WHERE id=?")) {
                    statement.setString(1, newStatus.name());
                    statement.setLong(2, now.toEpochMilli());
                    statement.setString(3, targetId.toString());
                    statement.executeUpdate();
                }
                if (suspended) {
                    try (PreparedStatement suspension = connection.prepareStatement( """
                            INSERT INTO account_sanctions(id, user_id, reason,
                                                           created_at, expires_at, \
                            created_by)
                            VALUES (?, ?, 'Suspensión administrativa', ?, ?, ?)
                            """)) {
                        suspension.setString(1, UUID.randomUUID().toString());
                        suspension.setString(2, targetId.toString());
                        suspension.setLong(3, now.toEpochMilli());
                        suspension.setLong(4, Long.MAX_VALUE);
                        suspension.setString(5, actor.user().id().toString());
                        suspension.executeUpdate();
                    }
                    AccountRestrictions.revokeAll(connection, targetId, now);
                }
                audit(connection, actor.user().id(), targetId, suspended
                        ? "USER_SUSPENDED" : "USER_REACTIVATED", Map.of());
                return new User(target.user().id(), target.user().username(),
                        target.user().email(), target.user().role(),
                        newStatus, target.user().createdAt(), now);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // TRUSTED LOCAL OPERATOR COMMANDS
    // =========================================================================

    // Promotes an existing account on behalf of the trusted local server operator.
    // This privileged entry point must only be called by the server console/CLI; it
    // deliberately has no authenticated actor and must never be exposed over HTTP.
    // Account verification, sanctions, credentials and sessions are left unchanged.
    public User promoteAdminFromConsole(String emailValue) {
        String email = InputValidation.email(emailValue);
        Instant now = clock.instant();
        try {
            return database.transaction(connection -> {
                UserRow target = findByEmail(connection, email);
                if (target == null) {
                    throw ServiceException.notFound("USER_NOT_FOUND",
                            "No existe una cuenta con ese correo.");
                }
                User current = target.user();
                if (current.role() == Role.ADMIN) {
                    return current;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET role='ADMIN', updated_at=? WHERE id=?")) {
                    update.setLong(1, now.toEpochMilli());
                    update.setString(2, current.id().toString());
                    update.executeUpdate();
                }
                audit(connection, null, current.id(), "ROLE_CHANGED_FROM_CONSOLE",
                        Map.of("from", current.role().name(), "to",
                        Role.ADMIN.name(), "origin", "local-console"));
                return new User(current.id(), current.username(), current.email(),
                        Role.ADMIN, current.status(), current.createdAt(), now);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public User createBootstrapAdmin(String usernameValue,
            String emailValue, String passwordValue) {
        String username = InputValidation.username(usernameValue);
        String email = InputValidation.email(emailValue);
        char[] password = InputValidation.password(passwordValue);
        String hash;
        try {
            hash = passwordHasher.hash(password);
        } finally {
            Arrays.fill(password, '\0');
        }
        Instant now = clock.instant();
        User admin = new User(UUID.randomUUID(), username, email,
                Role.ADMIN, UserStatus.ACTIVE, now, now);
        try {
            return database.transaction(connection -> {
                if (countAdmins(connection) > 0) {
                    throw ServiceException.conflict("ADMIN_EXISTS",
                            "Ya existe un administrador; crea otros desde el "
                            + "panel administrativo.");
                }
                insertUser(connection, admin, hash, now);
                audit(connection, admin.id(), admin.id(),
                        "BOOTSTRAP_ADMIN_CREATED", Map.of());
                return admin;
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw ServiceException.conflict("ACCOUNT_EXISTS",
                        "El usuario o correo ya está registrado.");
            }
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // ACCOUNT TOKEN PERSISTENCE
    // =========================================================================

    private void deliverVerification(User user, String token,
            Instant expiresAt, String code, Instant codeExpiresAt) {
        try {
            mailer.sendEmailVerification(user.email(), user.username(),
                    token, expiresAt, code, codeExpiresAt);
        } catch (Exception exception) {
            System.err.println("No se pudo entregar el correo de verificación: "
                    + exception.getMessage());
        }
    }

    private static void insertUser(Connection connection, User user,
            String passwordHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO users(id, username, email, password_hash,
                                  role, status, created_at, updated_at, \
                password_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, user.id().toString());
            statement.setString(2, user.username());
            statement.setString(3, user.email());
            statement.setString(4, passwordHash);
            statement.setString(5, user.role().name());
            statement.setString(6, user.status().name());
            statement.setLong(7, now.toEpochMilli());
            statement.setLong(8, now.toEpochMilli());
            statement.setLong(9, now.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void insertAccountToken(Connection connection,
            UUID userId, String type, String rawToken, Instant now,
            Instant expiresAt) throws SQLException {
        try (PreparedStatement invalidate = connection.prepareStatement( """
                UPDATE account_tokens SET used_at=?
                WHERE user_id=? AND type=? AND used_at IS NULL
                """)) {
            invalidate.setLong(1, now.toEpochMilli());
            invalidate.setString(2, userId.toString());
            invalidate.setString(3, type);
            invalidate.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO account_tokens(id, user_id, type, token_hash, created_at, \
                expires_at, used_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, userId.toString());
            statement.setString(3, type);
            statement.setBytes(4, SecureTokens.hash(rawToken));
            statement.setLong(5, now.toEpochMilli());
            statement.setLong(6, expiresAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void insertVerificationCode(Connection connection, String token,
            String code, Instant expiresAt) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id FROM account_tokens WHERE token_hash=? "
                + "AND type='EMAIL_VERIFY'");
                PreparedStatement insert = connection.prepareStatement( """
                     UPDATE account_tokens SET code_hash=?, code_expires_at=?, \
                     attempts=0 WHERE id=?
                     """)) {
            query.setBytes(1, SecureTokens.hash(token));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException(
                        "No se encontró la solicitud de verificación.");
                String id = result.getString("id");
                insert.setBytes(1, SecureTokens.hash(id + ":" + code));
                insert.setLong(2, expiresAt.toEpochMilli());
                insert.setString(3, id);
                insert.executeUpdate();
            }
        }
    }

    private static void insertEmailChangeRequest(Connection connection,
            UUID userId, String newEmail, String rawToken, Instant now,
            Instant expiresAt) throws SQLException {
        try (PreparedStatement invalidate = connection.prepareStatement( """
                UPDATE email_change_requests SET used_at=?
                WHERE user_id=? AND used_at IS NULL
                """)) {
            invalidate.setLong(1, now.toEpochMilli());
            invalidate.setString(2, userId.toString());
            invalidate.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO email_change_requests(id, user_id, new_email,
                                                  token_hash, created_at, expires_at, \
                used_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, userId.toString());
            statement.setString(3, newEmail);
            statement.setBytes(4, SecureTokens.hash(rawToken));
            statement.setLong(5, now.toEpochMilli());
            statement.setLong(6, expiresAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static TokenRow findAccountToken(Connection connection,
            byte[] tokenHash, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT t.id AS token_id, t.expires_at, t.used_at,
                       u.id, u.username, u.email, u.role, u.status, u.created_at, \
                u.updated_at, u.password_hash
                FROM account_tokens t
                JOIN users u ON u.id=t.user_id
                WHERE t.token_hash=? AND t.type=?
                """)) {
            statement.setBytes(1, tokenHash);
            statement.setString(2, type);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                result.getLong("used_at");
                boolean used = !result.wasNull();
                return new TokenRow(result.getString("token_id"),
                        readUserRow(result).user(), Instant.ofEpochMilli(
                        result.getLong("expires_at")), used);
            }
        }
    }

    private static EmailChangeTokenRow findEmailChangeRequest(Connection connection,
            byte[] tokenHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT r.id AS request_id, r.new_email, r.expires_at, r.used_at,
                       u.id, u.username, u.email, u.role, u.status, u.created_at, \
                u.updated_at, u.password_hash
                FROM email_change_requests r
                JOIN users u ON u.id=r.user_id
                WHERE r.token_hash=?
                """)) {
            statement.setBytes(1, tokenHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                result.getLong("used_at");
                boolean used = !result.wasNull();
                return new EmailChangeTokenRow(result.getString("request_id"),
                        readUserRow(result).user(), result.getString("new_email"),
                        Instant.ofEpochMilli(result.getLong("expires_at")), used);
            }
        }
    }

    private static void requireUsableToken(TokenRow row, Instant now) {
        if (row == null || row.used() || !row.expiresAt().isAfter(now)) {
            throw ServiceException.badRequest("INVALID_TOKEN",
                    "El token no es válido, venció o ya fue utilizado.");
        }
    }

    private static void consumeToken(Connection connection,
            String tokenId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE account_tokens SET used_at=? WHERE id=? AND "
                + "used_at IS NULL")) {
            statement.setLong(1, now.toEpochMilli());
            statement.setString(2, tokenId);
            if (statement.executeUpdate() != 1) {
                throw ServiceException.badRequest("INVALID_TOKEN",
                        "El token ya fue utilizado.");
            }
        }
    }

    // =========================================================================
    // SESSION AND USER LOOKUPS
    // =========================================================================

    private static UserRow findByIdentifier(Connection connection,
            String identifier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT id, username, email, role, status, created_at, updated_at, \
                password_hash
                FROM users WHERE username=? COLLATE NOCASE OR email=? COLLATE NOCASE \
                LIMIT 1
                """)) {
            statement.setString(1, identifier);
            statement.setString(2, identifier);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readUserRow(result) : null;
            }
        }
    }

    private AccessSession createAccessSession(Connection connection,
            UUID userId, Instant now) throws SQLException {
        Instant expiresAt = now.plus(sessionTtl);
        String accessToken = SecureTokens.create();
        String sessionId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO sessions(id, user_id, token_hash, created_at, expires_at, \
                last_seen_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, userId.toString());
            statement.setBytes(3, SecureTokens.hash(accessToken));
            statement.setLong(4, now.toEpochMilli());
            statement.setLong(5, expiresAt.toEpochMilli());
            statement.setLong(6, now.toEpochMilli());
            statement.executeUpdate();
        }
        return new AccessSession(sessionId, accessToken, expiresAt);
    }

    private static ServiceException accountRestriction(Connection connection,
            UUID userId, Instant now) throws SQLException {
        boolean banned = AccountRestrictions.hasActiveBan(connection, userId, now);
        String message = banned ? "Tu cuenta está baneada en este servidor."
                : "Tu cuenta está suspendida en este servidor.";
        try (PreparedStatement query = connection.prepareStatement( """
                SELECT reason, expires_at, by_mod FROM account_sanctions WHERE user_id=?
                AND (resolution IS NULL OR resolution='BANNED') AND (expires_at IS NULL \
                OR expires_at>?)
                ORDER BY CASE WHEN resolution='BANNED' THEN 0 ELSE 1 END, created_at \
                DESC LIMIT 1
                """)) {
            query.setString(1, userId.toString());
            query.setLong(2, now.toEpochMilli());
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    String reason = row.getString("reason");
                    if ((reason == null || reason.isBlank()) && row.getBoolean("by_mod"))
                            reason = "Uso de mods bloqueados por este servidor.";
                    if (reason != null && !reason.isBlank()) message += "\n\nMotivo: "
                            + reason;
                    message += row.getObject("expires_at") == null
                            ? "\n\nDuración: indefinida." : "\n\nHasta: "
                            + java.time.format.DateTimeFormatter.ofPattern(
                            "dd/MM/uuuu HH:mm").withZone(java.time.ZoneOffset.UTC).format(
                            Instant.ofEpochMilli(row.getLong("expires_at"))) + " (UTC).";
                }
            }
        }
        return ServiceException.forbidden(banned ? "ACCOUNT_BANNED"
                : "ACCOUNT_SUSPENDED", message);
    }

    private static DeviceSessionRow findDeviceSession(Connection connection,
            byte[] tokenHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT d.id AS device_session_id,
                       u.id, u.username, u.email, u.role, u.status,
                       u.created_at, u.updated_at, u.password_hash
                FROM device_sessions d
                JOIN users u ON u.id=d.user_id
                WHERE d.token_hash=? AND d.revoked_at IS NULL AND d.expires_at>?
                """)) {
            statement.setBytes(1, tokenHash);
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new DeviceSessionRow(row.getString(
                        "device_session_id"), readUserRow(row).user()) : null;
            }
        }
    }

    private static UsedRefreshRow findUsedRefresh(Connection connection,
            byte[] tokenHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT h.device_session_id, d.user_id
                FROM device_refresh_history h
                JOIN device_sessions d ON d.id=h.device_session_id
                WHERE h.token_hash=?
                """)) {
            statement.setBytes(1, tokenHash);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new UsedRefreshRow(row.getString("device_session_id"),
                        UUID.fromString(row.getString("user_id"))) : null;
            }
        }
    }

    private static String boundedLabel(String value, String fallback, int maximumLength) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            return fallback;
        }
        if (clean.codePointCount(0, clean.length()) > maximumLength
                || clean.indexOf('\0') >= 0) {
            throw ServiceException.badRequest("INVALID_LABEL",
                    "El texto proporcionado no es válido.");
        }
        return clean;
    }

    private static UserRow findByEmail(Connection connection,
            String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT id, username, email, role, status, created_at, updated_at, \
                password_hash
                FROM users WHERE email=? COLLATE NOCASE LIMIT 1
                """)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readUserRow(result) : null;
            }
        }
    }

    private static UserRow findById(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT id, username, email, role, status, created_at, updated_at, \
                password_hash
                FROM users WHERE id=? LIMIT 1
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readUserRow(result) : null;
            }
        }
    }

    private static UserRow readUserRow(ResultSet result) throws SQLException {
        User user = new User(UUID.fromString(result.getString("id")),
                result.getString("username"), result.getString("email"), Role.valueOf(
                result.getString("role")), UserStatus.valueOf(result.getString("status")),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
        return new UserRow(user, result.getString("password_hash"));
    }

    private static int countAdmins(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE role='ADMIN'");
                ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    // =========================================================================
    // AUDIT EVENTS AND SHARED VALIDATION
    // =========================================================================

    private static void audit(Connection connection, UUID actor, UUID target,
            String action, Map<String, ?> details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO audit_events(id, actor_user_id, target_user_id, action, \
                details, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actor == null ? null : actor.toString());
            statement.setString(3, target == null ? null : target.toString());
            statement.setString(4, action);
            statement.setString(5, GSON.toJson(details));
            statement.setLong(6, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void requirePermission(AuthenticatedUser actor,
            Permission permission) {
        if (actor == null || !actor.permissions().contains(permission)) {
            throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                    "No tienes permiso para esta operación.");
        }
    }

    private static String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getMessage() != null && exception.getMessage().toLowerCase(
                Locale.ROOT).contains("constraint");
    }

    private static ServiceException databaseFailure(SQLException exception) {
        exception.printStackTrace(System.err);
        return new ServiceException(500, "DATABASE_ERROR",
                "No se pudo completar la operación.");
    }

    // =========================================================================
    // SESSION AND ACCOUNT VALUES
    // =========================================================================

    private record UserRow(User user, String passwordHash) { }

    private record AccessSession(String id, String token, Instant expiresAt) { }

    private record DeviceSessionRow(String id, User user) { }

    private record UsedRefreshRow(String deviceSessionId, UUID userId) { }

    private record RefreshAttempt(LoginResult result, boolean reused) { }

    private record TokenRow(String id, User user, Instant expiresAt, boolean used) { }

    private record EmailChangeTokenRow(String id, User user,
            String newEmail, Instant expiresAt, boolean used) { }

    public record LoginResult(String accessToken, Instant expiresAt,
            String refreshToken, Instant refreshExpiresAt, String deviceSessionId,
            User user, Set<Permission> permissions) { }

    public record DeviceSession(String id, String deviceName, Instant createdAt,
            Instant expiresAt, Instant lastUsedAt) { }

    public record GameTicket(String token, String server, Instant expiresAt) { }

    public record GameIdentity(UUID userId, String username, Role role, String server) { }

    public record AuthenticatedUser(User user, String sessionId,
            Set<Permission> permissions) { }
}

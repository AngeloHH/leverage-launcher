package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminUserServiceTest {
    @Test void datedBanBlocksLoginUntilExpiryAndDoesNotRestoreRevokedSessions()
            throws Exception {
        User target=user("DatedBan", Role.USER, UserStatus.ACTIVE);
        String access=auth.login(target.username(), PASSWORD).accessToken();
        Instant end=clock.instant().plusSeconds(60);
        service.ban(admin, target.id(), "Baneo temporal de prueba", end);
        assertTrue(detail(target).banned());
        assertThrows(ServiceException.class, ()->auth.login(target.username(), PASSWORD));
        assertThrows(ServiceException.class, ()->auth.authenticate(access));
        clock.now=end;
        assertFalse(detail(target).banned());
        assertEquals(UserStatus.ACTIVE, detail(target).user().status());
        assertEquals(target.id(), auth.login(target.username(), PASSWORD).user().id());
        assertThrows(ServiceException.class, ()->auth.authenticate(access));
    }
    @Test void datedBanRejectsPastOrPresentDatesWithoutChangingTheAccount()
            throws Exception {
        User target=user("InvalidEnd", Role.USER, UserStatus.ACTIVE);
        for (Instant end:List.of(clock.instant(), clock.instant().minusSeconds(1))) {
            assertEquals("INVALID_BAN_EXPIRY", assertThrows(ServiceException.class,
                    ()->service.ban(admin, target.id(), "Motivo", end)).code());
            assertFalse(detail(target).banned());
            assertEquals(UserStatus.ACTIVE, detail(target).user().status());
        }
    }
    @TempDir Path directory;
    private static final String PASSWORD = "test-account-password";
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final MutableClock clock = new MutableClock(
            Instant.parse("2026-10-25T12:00:00Z"));
    private Database database;
    private AuthService auth;
    private AdminUserService service;
    private AuthService.AuthenticatedUser admin;

    @BeforeEach void setUp() throws Exception {
        database = new Database(directory.resolve("admin-users.db"));
        database.initialize();
        auth = new AuthService(database, hasher, new RecoveryMailer() {
            public void sendEmailVerification(String email, String username,
                    String token, Instant expiresAt) { }
            public void sendPasswordReset(String email, String username,
                    String token, Instant expiresAt) { }
        }, clock, Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("Operator", "operator@example.test", PASSWORD);
        admin = auth.authenticate(auth.login("Operator", PASSWORD).accessToken());
        service = new AdminUserService(database, clock, hasher);
    }

    @Test void detailsKeepPageOrderCreationAndUnknownActivityWithoutInventingLoginDates()
            throws Exception {
        User first = user("First", Role.USER, UserStatus.ACTIVE);
        User second = user("Second", Role.USER, UserStatus.PENDING_VERIFICATION);
        var result = service.details(admin, List.of(second, first));
        assertEquals(List.of(second.id(), first.id()),
                result.stream().map(item -> item.user().id()).toList());
        assertEquals(Instant.ofEpochMilli(123), result.getFirst().user().createdAt());
        assertNull(result.getFirst().lastSeenAt());
        assertFalse(result.getFirst().banned());
        assertNull(result.getFirst().restrictionReason());
        assertNull(result.getFirst().skinHash());
        assertThrows(UnsupportedOperationException.class,
                () -> result.add(result.getFirst()));
    }

    @Test void
            detailsUseLatestActualAccessOrDeviceActivityIncludingRevokedHistoricalSessions
            () throws Exception {
        User target = user("History", Role.USER, UserStatus.ACTIVE);
        seedSession(target, "access-old", 600, 650L);
        seedSession(target, "access-new", 900, 950L);
        seedDevice(target, "device-new", 1_200, 1_300L);
        seedDevice(target, "device-old", 700, null);
        assertEquals(Instant.ofEpochMilli(1_200), detail(target).lastSeenAt());
        execute("UPDATE sessions SET last_seen_at=1500 WHERE id='access-new'");
        assertEquals(Instant.ofEpochMilli(1_500), detail(target).lastSeenAt());
    }

    @Test void
            detailsDistinguishBansFromSuspensionsAndAggregateOnlyActiveReasonsBanFirst()
            throws Exception {
        User target = user("Restricted", Role.USER, UserStatus.SUSPENDED);
        sanction(target, null, "Política de mods", clock.instant().plusSeconds(100), 2);
        sanction(target, "BANNED", "Baneo permanente", null, 1);
        sanction(target, null, "Política de mods", clock.instant().plusSeconds(100), 3);
        sanction(target, "LIFTED", "No mostrar levantada", null, 4);
        sanction(target, "AUTO_EXPIRED", "No mostrar vencida",
                clock.instant().minusSeconds(10), 5);
        var details = detail(target);
        assertTrue(details.banned());
        assertEquals(UserStatus.SUSPENDED, details.user().status());
        assertEquals("Baneo permanente · Política de mods", details.restrictionReason());
    }

    @Test void expiredRestrictionsPreservePermanentBans() throws Exception {
        User expired = user("Expired", Role.USER, UserStatus.SUSPENDED);
        sanction(expired, null, "Temporal", clock.instant().minusSeconds(1), 1);
        User banned = user("StillBanned", Role.USER, UserStatus.SUSPENDED);
        sanction(banned, null, "Temporal", clock.instant().minusSeconds(1), 1);
        sanction(banned, "BANNED", "Permanente", null, 2);
        assertEquals(UserStatus.ACTIVE, detail(expired).user().status());
        assertNull(detail(expired).restrictionReason());
        assertEquals(UserStatus.SUSPENDED, detail(banned).user().status());
        assertTrue(detail(banned).banned());
        assertEquals("Permanente", detail(banned).restrictionReason());
    }

    @Test void detailsOfferMeaningfulFallbackForHistoricalSuspensionWithNoRecordedReason()
            throws Exception {
        User target = user("NoReason", Role.USER, UserStatus.SUSPENDED);
        assertEquals("Suspensión administrativa sin motivo registrado.",
                detail(target).restrictionReason());
        sanction(target, "BANNED", " ", null, 1);
        assertEquals("Baneo administrativo sin motivo registrado.",
                detail(target).restrictionReason());
    }

    @Test void returnsSkinBindingsForSuspendedUsers() throws Exception {
        User target = user("SkinRestricted", Role.USER, UserStatus.SUSPENDED);
        texture(target, "skin", "a".repeat(40), "shared.png");
        texture(target, "cape", "b".repeat(40), "cape.png");
        assertEquals("a".repeat(40), detail(target).skinHash());
    }

    @Test void banRequiresExplicitBoundedReasonAndForbidsSelfWithoutMutation()
            throws Exception {
        User target = user("BanReason", Role.USER, UserStatus.ACTIVE);
        for (String reason : new String[] { null, "", " ",
                "x".repeat(1_001), "invalid\u0000reason" }) {
            assertEquals("INVALID_BAN_REASON", assertThrows(ServiceException.class,
                    () -> service.ban(admin, target.id(), reason)).code());
        }
        assertEquals("SELF_BAN", assertThrows(ServiceException.class,
                () -> service.ban(admin, admin.user().id(), "No debe aplicarse")).code());
        assertEquals(0, count("account_sanctions", target.id(), ""));
        assertEquals(UserStatus.ACTIVE, detail(target).user().status());
    }

    @Test void
            banIsPermanentManualAuditedAndRevokesOnlyTargetAccessDevicesAndGameTickets()
            throws Exception {
        User target = user("BanTarget", Role.USER, UserStatus.ACTIVE);
        User other = user("Unaffected", Role.USER, UserStatus.ACTIVE);
        seedCredentials(target);
        seedSession(other, "other-session", 500, null);
        User result = service.ban(admin, target.id(), "  Incumplimiento deliberado  ");
        assertEquals(UserStatus.SUSPENDED, result.status());
        assertEquals(target.role(), result.role());
        assertEquals(target.createdAt(), result.createdAt());
        assertTrue(detail(target).banned());
        assertEquals("Incumplimiento deliberado", detail(target).restrictionReason());
        assertEquals(0, count("sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("device_sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("game_tickets", target.id(), " AND used_at IS NULL"));
        assertEquals(1, count("sessions", other.id(), " AND revoked_at IS NULL"));
        try (Connection connection = database.open();
                var query = connection.prepareStatement(
                "SELECT by_mod,expires_at,created_by,resolution FROM account_sanction"
                + "s WHERE user_id=?")) {
            query.setString(1, target.id().toString());
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
                assertNull(row.getObject(2));
                assertEquals(admin.user().id().toString(), row.getString(3));
                assertEquals("BANNED", row.getString(4));
            }
        }
        assertAudit(target.id(), "USER_BANNED");
        assertEquals("ACCOUNT_BANNED", assertThrows(ServiceException.class,
                () -> service.ban(admin, target.id(), "Duplicado")).code());
        assertEquals(1, count("account_sanctions", target.id(), ""));
    }

    @Test void inactiveAdminsCannotBeUsedAsAuthorityToBanTheLastUsableAdministrator()
            throws Exception {
        User inactive = user("InactiveAdmin", Role.ADMIN, UserStatus.ACTIVE);
        var login = auth.login(inactive.username(), PASSWORD);
        var stale = auth.authenticate(login.accessToken());
        execute("UPDATE users SET status='SUSPENDED' WHERE id='" + inactive.id() + "'");
        assertEquals("ACCOUNT_UNAVAILABLE", assertThrows(ServiceException.class,
                () -> service.ban(stale, admin.user().id(),
                "No debe retirar el último administrador")).code());
        assertEquals(UserStatus.ACTIVE, auth.authenticate(auth.login("Operator",
                PASSWORD).accessToken()).user().status());
        assertEquals(0, count("account_sanctions", admin.user().id(), ""));
    }

    @Test void ordinarySecondAdministratorCanBeBannedWhileOperatorRemainsUsable()
            throws Exception {
        User second = user("SecondAdmin", Role.ADMIN, UserStatus.ACTIVE);
        assertEquals(UserStatus.SUSPENDED, service.ban(admin,
                second.id(), "Motivo administrativo").status());
        assertTrue(detail(second).banned());
        assertEquals(Role.ADMIN, auth.authenticate(auth.login("Operator",
                PASSWORD).accessToken()).user().role());
    }

    @Test void credentialChangesAndSkinRemovalRequireAdminNotJustModeratorPermissions()
            throws Exception {
        User moderator = user("Moderator", Role.MODERATOR, UserStatus.ACTIVE);
        var actor = auth.authenticate(auth.login(moderator.username(),
                PASSWORD).accessToken());
        assertEquals(1, service.details(actor, List.of(moderator)).size());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.ban(actor, admin.user().id(), "No")).status());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.changePassword(actor, moderator.id(), PASSWORD)).status());
        assertEquals(403, assertThrows(ServiceException.class, () -> service.changeEmail(
                actor, moderator.id(), "new@example.test")).status());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.clearSkin(actor, moderator.id())).status());
        User regular = user("Regular", Role.USER, UserStatus.ACTIVE);
        var ordinary = auth.authenticate(auth.login(regular.username(),
                PASSWORD).accessToken());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.details(ordinary, List.of(regular))).status());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.details(null, List.of())).status());
    }

    @Test void staleDemotedOrRevokedAdministrativeActorsCannotMutateOrRead()
            throws Exception {
        User target = user("Protected", Role.USER, UserStatus.ACTIVE);
        execute("UPDATE users SET role='USER' WHERE id='" + admin.user().id() + "'");
        assertEquals("INSUFFICIENT_PERMISSION", assertThrows(ServiceException.class,
                () -> service.clearSkin(admin, target.id())).code());
        assertEquals("INSUFFICIENT_PERMISSION", assertThrows(ServiceException.class,
                () -> service.details(admin, List.of(target))).code());
        execute("UPDATE users SET role='ADMIN' WHERE id='" + admin.user().id() + "'");
        execute("UPDATE sessions SET revoked_at=1 WHERE id='" + admin.sessionId() + "'");
        assertEquals("INVALID_SESSION", assertThrows(ServiceException.class,
                () -> service.changePassword(admin, target.id(),
                "replacement-password")).code());
        assertEquals(0, count("audit_events", target.id(), ""));
    }

    @Test void expiredSessionCannotOperateEvenWithPriorAuthenticatedAdminSnapshot()
            throws Exception {
        User target = user("SessionExpiry", Role.USER, UserStatus.ACTIVE);
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertEquals("INVALID_SESSION", assertThrows(ServiceException.class,
                () -> service.changeEmail(admin, target.id(),
                "new@example.test")).code());
    }

    @Test void passwordOverrideRevokesSessionsAndPendingChanges() throws Exception {
        User target = user("PasswordTarget", Role.MODERATOR, UserStatus.ACTIVE);
        var login = auth.login(target.username(), PASSWORD, true, "Target device");
        var targetActor = auth.authenticate(login.accessToken());
        auth.createGameTicket(targetActor, "test-scope");
        seedRequests(target);
        User updated = service.changePassword(admin, target.id(), "replacement-password");
        assertEquals(Role.MODERATOR, updated.role());
        assertEquals(UserStatus.ACTIVE, updated.status());
        assertEquals(target.createdAt(), updated.createdAt());
        assertEquals(clock.instant(), updated.updatedAt());
        assertEquals(0, count("sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("device_sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("game_tickets", target.id(), " AND used_at IS NULL"));
        assertEquals(0, count("account_tokens", target.id(),
                " AND type='PASSWORD_RESET' AND used_at IS NULL"));
        assertEquals(0, count("email_change_requests",
                target.id(), " AND used_at IS NULL"));
        assertThrows(ServiceException.class,
                () -> auth.authenticate(login.accessToken()));
        assertThrows(ServiceException.class, () -> auth.refresh(login.refreshToken()));
        assertThrows(ServiceException.class,
                () -> auth.login(target.username(), PASSWORD));
        assertEquals(target.id(), auth.login(target.username(),
                "replacement-password").user().id());
        try (Connection connection = database.open();
                var query = connection.prepareStatement(
                "SELECT password_hash,password_changed_at FROM users WHERE id=?")) {
            query.setString(1, target.id().toString());
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertNotEquals("replacement-password", row.getString(1));
                assertTrue(hasher.verify("replacement-password".toCharArray(),
                        row.getString(1)));
                assertEquals(clock.instant().toEpochMilli(), row.getLong(2));
            }
        }
        assertAudit(target.id(), "ADMIN_PASSWORD_CHANGED");
    }

    @Test void passwordOverridePreservesAccountRestrictions() throws Exception {
        for (UserStatus status : List.of(UserStatus.SUSPENDED,
                UserStatus.PENDING_VERIFICATION)) {
            User target = user("Status" + status.ordinal(), Role.USER, status);
            assertEquals(status, service.changePassword(admin, target.id(),
                    "status-replacement-password").status());
        }
        User target = user("InvalidPassword", Role.USER, UserStatus.ACTIVE);
        assertEquals("INVALID_PASSWORD", assertThrows(ServiceException.class,
                () -> service.changePassword(admin, target.id(), "short")).code());
        assertEquals(0, count("audit_events", target.id(), ""));
    }

    @Test void emailOverrideRejectsCaseInsensitiveDuplicates() throws Exception {
        User target = user("EmailTarget", Role.USER, UserStatus.ACTIVE);
        User existing = user("ExistingEmail", Role.USER, UserStatus.ACTIVE);
        seedCredentials(target);
        seedRequests(target);
        assertEquals("EMAIL_EXISTS", assertThrows(ServiceException.class,
                () -> service.changeEmail(admin, target.id(),
                existing.email().toUpperCase())).code());
        assertEquals(target.email(), detail(target).user().email());
        assertEquals(1, count("sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(1, count("device_sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(2, count("account_tokens", target.id(), " AND used_at IS NULL"));
        assertEquals(0, count("audit_events", target.id(), ""));
        assertEquals("INVALID_EMAIL", assertThrows(ServiceException.class,
                () -> service.changeEmail(admin, target.id(), "not-an-email")).code());
    }

    @Test void
            emailOverridePreservesPendingVerificationAndInvalidatesTokensForFormerAddress
            () throws Exception {
        User target = user("PendingEmail", Role.USER, UserStatus.PENDING_VERIFICATION);
        seedCredentials(target);
        seedRequests(target);
        User result = service.changeEmail(admin, target.id(),
                "  Replacement@Example.test  ");
        assertEquals("Replacement@Example.test", result.email());
        assertEquals(UserStatus.PENDING_VERIFICATION, result.status());
        assertEquals(Role.USER, result.role());
        assertEquals(target.createdAt(), result.createdAt());
        assertEquals(0, count("sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("device_sessions", target.id(), " AND revoked_at IS NULL"));
        assertEquals(0, count("game_tickets", target.id(), " AND used_at IS NULL"));
        assertEquals(0, count("account_tokens", target.id(), " AND used_at IS NULL"));
        assertEquals(0, count("email_change_requests",
                target.id(), " AND used_at IS NULL"));
        assertAudit(target.id(), "ADMIN_EMAIL_CHANGED");
    }

    @Test void adminCanCorrectOwnEmailButTheirSessionIsRevokedAndRoleIsPreserved() {
        User result = service.changeEmail(admin, admin.user().id(),
                "corrected@example.test");
        assertEquals(Role.ADMIN, result.role());
        assertEquals("corrected@example.test", result.email());
        assertEquals("INVALID_SESSION", assertThrows(ServiceException.class,
                () -> service.details(admin, List.of(result))).code());
        assertEquals(result.id(), auth.login("corrected@example.test",
                PASSWORD).user().id());
    }

    @Test void clearSkinOnlyUnbindsChosenUserLeavingSharedFileOtherSkinsAndCapesIntact()
            throws Exception {
        User target = user("ClearTarget", Role.USER, UserStatus.SUSPENDED);
        User other = user("KeepSkin", Role.USER, UserStatus.ACTIVE);
        Path image = directory.resolve("shared.png");
        Files.write(image, new byte[] { 1, 2, 3 });
        texture(target, "skin", "a".repeat(40), image.toString());
        texture(other, "skin", "a".repeat(40), image.toString());
        texture(target, "cape", "b".repeat(40), image.toString());
        service.clearSkin(admin, target.id());
        assertNull(detail(target).skinHash());
        assertEquals("a".repeat(40), detail(other).skinHash());
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(image));
        assertEquals(1, count("textures", target.id(), " AND type='cape'"));
        assertEquals(UserStatus.SUSPENDED, detail(target).user().status());
        assertAudit(target.id(), "ADMIN_SKIN_CLEARED");
        assertDoesNotThrow(() -> service.clearSkin(admin, target.id()));
    }

    @Test void nonexistentTargetsFailWithoutAuditOrOtherAccountChanges() {
        UUID missing = UUID.randomUUID();
        assertEquals("USER_NOT_FOUND", assertThrows(ServiceException.class,
                () -> service.ban(admin, missing, "Motivo")).code());
        assertEquals("USER_NOT_FOUND", assertThrows(ServiceException.class,
                () -> service.changePassword(admin, missing,
                "replacement-password")).code());
        assertEquals("USER_NOT_FOUND", assertThrows(ServiceException.class,
                () -> service.changeEmail(admin, missing, "new@example.test")).code());
        assertEquals("USER_NOT_FOUND", assertThrows(ServiceException.class,
                () -> service.clearSkin(admin, missing)).code());
    }

    @Test void banAndCredentialChangesRollbackIfAuditCannotCommitAndNeverExposeSecrets()
            throws Exception {
        User target = user("AtomicTarget", Role.USER, UserStatus.ACTIVE);
        seedCredentials(target);
        seedRequests(target);
        texture(target, "skin", "c".repeat(40), "shared.png");
        execute("CREATE TRIGGER reject_admin_audit BEFORE INSERT ON audit_events "
                + "BEGIN SELECT RAISE(ABORT,'confidential database diagnostic'); END");
        List<Runnable> operations = new ArrayList<>();
        operations.add(() -> service.ban(admin, target.id(), "Motivo"));
        operations.add(() -> service.changePassword(admin,
                target.id(), "secret-new-password"));
        operations.add(() -> service.changeEmail(admin, target.id(),
                "confidential@example.test"));
        operations.add(() -> service.clearSkin(admin, target.id()));
        for (Runnable operation : operations) {
            ServiceException failure = assertThrows(ServiceException.class,
                    operation::run);
            assertEquals("DATABASE_ERROR", failure.code());
            assertFalse(failure.getMessage().contains("confidential"));
            assertFalse(failure.getMessage().contains("secret-new-password"));
            assertEquals(UserStatus.ACTIVE, detail(target).user().status());
            assertEquals(target.email(), detail(target).user().email());
            assertEquals("c".repeat(40), detail(target).skinHash());
            assertEquals(0, count("account_sanctions", target.id(), ""));
            assertEquals(1, count("sessions", target.id(), " AND revoked_at IS NULL"));
            assertEquals(2, count("account_tokens", target.id(), " AND used_at IS NULL"));
        }
    }

    private AdminUserService.Details detail(User user) {
        return service.details(admin, List.of(user)).getFirst();
    }

    private User user(String username, Role role, UserStatus status) throws Exception {
        UUID id = UUID.randomUUID();
        String email = username.toLowerCase() + "@example.test";
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    users(id,username,email,password_hash,role,status,created_at,updated\
                    _at,password_changed_at)
                    VALUES(?,?,?,?,?,?,123,123,123)
                    """
            )) {
                insert.setString(1, id.toString());
                insert.setString(2, username);
                insert.setString(3, email);
                insert.setString(4, hasher.hash(PASSWORD.toCharArray()));
                insert.setString(5, role.name());
                insert.setString(6, status.name());
                insert.executeUpdate();
            }
            return null;
        });
        return new User(id, username, email, role, status,
                Instant.ofEpochMilli(123), Instant.ofEpochMilli(123));
    }

    private void sanction(User target, String resolution, String reason,
            Instant expiry, long created) throws Exception {
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    account_sanctions(id,user_id,reason,created_at,expires_at,resolution)
                    VALUES(?,?,?,?,?,?)
                    """
            )) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, target.id().toString());
                insert.setString(3, reason);
                insert.setLong(4, created);
                if (expiry == null) insert.setNull(5, java.sql.Types.INTEGER);
                else insert.setLong(5, expiry.toEpochMilli());
                insert.setString(6, resolution);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private void seedSession(User target, String id, long lastSeen,
            Long revoked) throws Exception {
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    sessions(id,user_id,token_hash,created_at,expires_at,last_seen_at,re\
                    voked_at)
                    VALUES(?,?,?,1,?,?,?)
                    """
            )) {
                insert.setString(1, id);
                insert.setString(2, target.id().toString());
                insert.setBytes(3, id.getBytes(StandardCharsets.UTF_8));
                insert.setLong(4, clock.instant().plusSeconds(500).toEpochMilli());
                insert.setLong(5, lastSeen);
                if (revoked == null) insert.setNull(6, java.sql.Types.INTEGER);
                else insert.setLong(6, revoked);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private void seedDevice(User target, String id, long lastUsed,
            Long revoked) throws Exception {
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    device_sessions(id,user_id,token_hash,device_name,created_at,expires\
                    _at,last_used_at,revoked_at)
                    VALUES(?,?,?,'Test device',1,?,?,?)
                    """
            )) {
                insert.setString(1, id);
                insert.setString(2, target.id().toString());
                insert.setBytes(3, id.getBytes(StandardCharsets.UTF_8));
                insert.setLong(4, clock.instant().plusSeconds(500).toEpochMilli());
                insert.setLong(5, lastUsed);
                if (revoked == null) insert.setNull(6, java.sql.Types.INTEGER);
                else insert.setLong(6, revoked);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private void seedCredentials(User target) throws Exception {
        String sessionId = "fixture-session-" + target.id();
        seedSession(target, sessionId, 500, null);
        seedDevice(target, "fixture-device-" + target.id(), 600, null);
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    game_tickets(id,user_id,session_id,token_hash,server_scope,created_a\
                    t,expires_at)
                    VALUES(?,?,?,?,'test',1,?)
                    """
            )) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, target.id().toString());
                insert.setString(3, sessionId);
                insert.setBytes(4, target.id().toString().getBytes(
                        StandardCharsets.UTF_8));
                insert.setLong(5, clock.instant().plusSeconds(500).toEpochMilli());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private void seedRequests(User target) throws Exception {
        database.transaction(connection -> {
            try (var token = connection.prepareStatement(
            """
                    INSERT INTO \
                    account_tokens(id,user_id,type,token_hash,created_at,expires_at) \
                    VALUES(?,?,?,?,1,?)
                    """
            )) {
                for (String type : List.of("EMAIL_VERIFY", "PASSWORD_RESET")) {
                    String id = UUID.randomUUID().toString();
                    token.setString(1, id);
                    token.setString(2, target.id().toString());
                    token.setString(3, type);
                    token.setBytes(4, id.getBytes(StandardCharsets.UTF_8));
                    token.setLong(5, clock.instant().plusSeconds(500).toEpochMilli());
                    token.addBatch();
                }
                token.executeBatch();
            }
            try (var email = connection.prepareStatement(
            """
                    INSERT INTO \
                    email_change_requests(id,user_id,new_email,token_hash,created_at,exp\
                    ires_at)
                    VALUES(?,?,'pending@example.test',?,1,?)
                    """
            )) {
                String id = UUID.randomUUID().toString();
                email.setString(1, id);
                email.setString(2, target.id().toString());
                email.setBytes(3, id.getBytes(StandardCharsets.UTF_8));
                email.setLong(4, clock.instant().plusSeconds(500).toEpochMilli());
                email.executeUpdate();
            }
            return null;
        });
    }

    private void texture(User target, String type, String hash,
            String path) throws Exception {
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO textures(user_id,type,hash,storage_path,model) VALUE"
                    + "S(?,?,?,?,?)")) {
                insert.setString(1, target.id().toString());
                insert.setString(2, type);
                insert.setString(3, hash);
                insert.setString(4, path);
                insert.setString(5, type.equals("skin") ? "slim" : null);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private int count(String table, UUID target, String condition) throws Exception {
        try (Connection connection = database.open();
                var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE "
                + (table.equals("audit_events") ? "target_user_id"
                : "user_id") + "=?" + condition)) {
            query.setString(1, target.toString());
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
        }
    }

    private void assertAudit(UUID target, String action) throws Exception {
        try (Connection connection = database.open();
                var query = connection.prepareStatement(
                "SELECT actor_user_id,target_user_id,action,details,created_at FROM a"
                + "udit_events WHERE target_user_id=? AND action=?")) {
            query.setString(1, target.toString());
            query.setString(2, action);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(admin.user().id().toString(), row.getString(1));
                assertEquals(target.toString(), row.getString(2));
                assertEquals(action, row.getString(3));
                assertEquals("{}", row.getString(4));
                assertEquals(clock.instant().toEpochMilli(), row.getLong(5));
            }
        }
    }

    private void execute(String sql) throws Exception {
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) { statement.execute(sql); }
            return null;
        });
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}

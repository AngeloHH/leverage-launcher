package dev.leverage.service;

import com.google.gson.JsonParser;
import dev.leverage.db.Database;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleAdminPromotionTest {
    private static final Instant CREATED = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant PROMOTED = CREATED.plusSeconds(60);
    private static final String PASSWORD = "console-test-password-long";

    @TempDir Path temporaryDirectory;

    private Database database;
    private CapturingMailer mailer;
    private AuthService auth;
    private AuthService console;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("console-promotion.db"));
        database.initialize();
        mailer = new CapturingMailer();
        auth = serviceAt(CREATED);
        console = serviceAt(PROMOTED);
    }

    @Test
    void promotesExistingUserByTrimmedCaseInsensitiveEmailAndPreservesAccountData(
            ) throws Exception {
        assertPromotion(Role.USER);
    }

    @Test
    void promotesExistingModeratorByTrimmedCaseInsensitiveEmail() throws Exception {
        assertPromotion(Role.MODERATOR);
    }

    private void assertPromotion(Role originalRole) throws Exception {
        User original = activeUser("ConsolePlayer", "MixedCase@Example.test");
        if (originalRole != Role.USER) setRole(original.id(), originalRole);
        var login = auth.login(original.email(), PASSWORD,
                true, "Remembered console test");
        var actor = auth.authenticate(login.accessToken());
        auth.createGameTicket(actor, "qa-survival");
        auth.requestPasswordReset(original.email());
        auth.requestEmailChange(actor, "future@example.test", PASSWORD);
        try (var connection = database.open(); var insert = connection.prepareStatement(
        """
                INSERT INTO textures(user_id,type,hash,storage_path,model) VALUES \
                (?,'skin',?,?, 'slim')
                """
        )) {
            insert.setString(1, original.id().toString());
            insert.setString(2, "0123456789012345678901234567890123456789");
            insert.setString(3, "fixture/skin");
            insert.executeUpdate();
        }
        Map<String, List<List<String>>> preserved = snapshot(Set.of("audit_events"),
                Set.of("role", "updated_at"));
        List<List<String>> existingAudit = rows("audit_events", Set.of());

        User promoted = console.promoteAdminFromConsole("  MIXEDcase@EXAMPLE.TEST  ");

        assertEquals(Role.ADMIN, promoted.role());
        assertEquals(original.id(), promoted.id());
        assertEquals(original.username(), promoted.username());
        assertEquals(original.email(), promoted.email());
        assertEquals(UserStatus.ACTIVE, promoted.status());
        assertEquals(original.createdAt(), promoted.createdAt());
        assertEquals(PROMOTED, promoted.updatedAt());
        assertEquals(preserved, snapshot(Set.of("audit_events"),
                Set.of("role", "updated_at")),
                "Only role and updated_at may change outside the audit log");
        assertTrue(rows("audit_events", Set.of()).containsAll(existingAudit));
        assertPromotionAudit(original.id(), originalRole);
        assertEquals(Role.ADMIN, auth.authenticate(login.accessToken()).user().role(),
                "Existing access sessions must see the new role without being"
                + " revoked");
        assertEquals(1, auth.listDeviceSessions(auth.authenticate(
                login.accessToken())).size());
        assertEquals(original.id(), auth.login(original.email(), PASSWORD).user().id());
    }

    @Test
    void unknownEmailNeverCreatesAnAccountOrMutatesAnything() throws Exception {
        activeUser("ExistingPlayer", "existing@example.test");
        var before = snapshot(Set.of(), Set.of());

        ServiceException failure = assertThrows(ServiceException.class,
                () -> console.promoteAdminFromConsole("missing@example.test"));

        assertEquals("USER_NOT_FOUND", failure.code());
        assertEquals(404, failure.status());
        assertEquals(before, snapshot(Set.of(), Set.of()));
    }

    @Test
    void malformedEmailsNeverMutateAnything() throws Exception {
        activeUser("ExistingPlayer", "existing@example.test");
        var before = snapshot(Set.of(), Set.of());
        for (String email : new String[] { null, "", "  ", "not-an-email",
                "x@invalid", "a b@example.test",
                "x@example.test\nother@example.test",
                    "a".repeat(250) + "@example.test" }) {
            ServiceException failure = assertThrows(ServiceException.class,
                    () -> console.promoteAdminFromConsole(email));
            assertEquals(400, failure.status());
            assertEquals(before, snapshot(Set.of(), Set.of()));
        }
    }

    @Test
    void alreadyAdminIsIdempotentAndDoesNotChangeTimestampOrAddAudit() throws Exception {
        User admin = auth.createBootstrapAdmin("ExistingAdmin",
                "admin@example.test", PASSWORD);
        var before = snapshot(Set.of(), Set.of());

        assertEquals(admin, console.promoteAdminFromConsole("  ADMIN@EXAMPLE.TEST  "));
        assertEquals(admin, console.promoteAdminFromConsole("admin@example.test"));

        assertEquals(before, snapshot(Set.of(), Set.of()));
        assertEquals(0, consoleAuditCount());
    }

    @Test
    void repeatedPromotionCreatesOnlyOneAuditAndKeepsFirstPromotionTimestamp(
            ) throws Exception {
        User account = activeUser("RepeatPlayer", "repeat@example.test");
        User promoted = console.promoteAdminFromConsole(account.email());
        var afterFirst = snapshot(Set.of(), Set.of());

        assertEquals(promoted, serviceAt(PROMOTED.plusSeconds(
                90)).promoteAdminFromConsole(account.email()));
        assertEquals(afterFirst, snapshot(Set.of(), Set.of()));
        assertEquals(1, consoleAuditCount());
    }

    @Test
    void pendingVerificationRemainsPendingAndOriginalVerificationTokenStillWorks(
            ) throws Exception {
        User pending = auth.register("PendingPlayer", "pending@example.test", PASSWORD);
        String verificationToken = mailer.verificationToken;
        var preserved = snapshot(Set.of("audit_events"), Set.of("role", "updated_at"));

        User promoted = console.promoteAdminFromConsole(pending.email());

        assertEquals(Role.ADMIN, promoted.role());
        assertEquals(UserStatus.PENDING_VERIFICATION, promoted.status());
        assertEquals(preserved, snapshot(Set.of("audit_events"),
                Set.of("role", "updated_at")));
        assertEquals("EMAIL_NOT_VERIFIED", assertThrows(ServiceException.class,
                () -> auth.login(pending.email(), PASSWORD)).code());
        assertEquals(Role.ADMIN, auth.confirmEmail(verificationToken).role());
    }

    @Test
    void suspendedAccountRemainsSuspendedWithoutLiftingSanctions() throws Exception {
        User suspended = activeUser("SuspendedPlayer", "suspended@example.test");
        try (var connection = database.open(); var update = connection.prepareStatement(
                "UPDATE users SET status='SUSPENDED' WHERE id=?");
             var sanction = connection.prepareStatement(
             """
                     INSERT INTO \
                     account_sanctions(id,user_id,by_mod,reason,created_at,expires_at)
                     VALUES (?,?,0,'Fixture suspension',?,?)
                     """
             )) {
            update.setString(1, suspended.id().toString());
            update.executeUpdate();
            sanction.setString(1, UUID.randomUUID().toString());
            sanction.setString(2, suspended.id().toString());
            sanction.setLong(3, CREATED.toEpochMilli());
            sanction.setLong(4, Long.MAX_VALUE);
            sanction.executeUpdate();
        }
        var preserved = snapshot(Set.of("audit_events"), Set.of("role", "updated_at"));

        User promoted = console.promoteAdminFromConsole(suspended.email());

        assertEquals(Role.ADMIN, promoted.role());
        assertEquals(UserStatus.SUSPENDED, promoted.status());
        assertEquals(preserved, snapshot(Set.of("audit_events"),
                Set.of("role", "updated_at")));
        assertEquals("ACCOUNT_SUSPENDED", assertThrows(ServiceException.class,
                () -> auth.login(suspended.email(), PASSWORD)).code());
    }

    @Test
    void failedAuditRollsBackTheRoleChangeInTheSameTransaction() throws Exception {
        User account = activeUser("RollbackPlayer", "rollback@example.test");
        try (var connection = database.open(); var sql = connection.createStatement()) {
            sql.executeUpdate(
            """
                    CREATE TRIGGER reject_console_audit BEFORE INSERT ON audit_events
                    WHEN NEW.action='ROLE_CHANGED_FROM_CONSOLE'
                    BEGIN SELECT RAISE(ABORT,'Fixture audit failure'); END
                    """
            );
        }
        var before = snapshot(Set.of(), Set.of());

        assertEquals("DATABASE_ERROR", assertThrows(ServiceException.class,
                () -> console.promoteAdminFromConsole(account.email())).code());

        assertEquals(before, snapshot(Set.of(), Set.of()));
    }

    private AuthService serviceAt(Instant instant) {
        return new AuthService(database, new PasswordHasher(10_000), mailer,
                Clock.fixed(instant, ZoneOffset.UTC), Duration.ofHours(12),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
    }

    private User activeUser(String username, String email) {
        auth.register(username, email, PASSWORD);
        return auth.confirmEmail(mailer.verificationToken);
    }

    private void setRole(UUID id, Role role) throws SQLException {
        try (var connection = database.open(); var update
                = connection.prepareStatement("UPDATE users SET role=? WHERE id=?")) {
            update.setString(1, role.name());
            update.setString(2, id.toString());
            update.executeUpdate();
        }
    }

    private void assertPromotionAudit(UUID target,
            Role originalRole) throws SQLException {
        try (var connection = database.open(); var query = connection.createStatement();
             var row = query.executeQuery(
                "SELECT * FROM audit_events WHERE action='ROLE_CHANGED_FROM_CONS"
                + "OLE'")) {
            assertTrue(row.next());
            assertNull(row.getString("actor_user_id"),
                    "The local operator is not an impersonated web account");
            assertEquals(target.toString(), row.getString("target_user_id"));
            var details = JsonParser.parseString(row.getString(
                    "details")).getAsJsonObject();
            assertEquals(Set.of("from", "to", "origin"), details.keySet());
            assertEquals(originalRole.name(), details.get("from").getAsString());
            assertEquals("ADMIN", details.get("to").getAsString());
            assertEquals("local-console", details.get("origin").getAsString());
            assertFalse(row.next(), "Exactly one console audit event should be created");
        }
    }

    private int consoleAuditCount() throws SQLException {
        try (var connection = database.open(); var query = connection.createStatement();
             var row = query.executeQuery(
                "SELECT COUNT(*) FROM audit_events WHERE action='ROLE_CHANGED_FR"
                + "OM_CONSOLE'")) {
            row.next();
            return row.getInt(1);
        }
    }

    private Map<String, List<List<String>>> snapshot(Set<String> excludedTables,
            Set<String> excludedUserColumns)
            throws SQLException {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        try (var connection = database.open(); var query = connection.createStatement();
             var tables = query.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT "
                + "LIKE 'sqlite_%' ORDER BY name")) {
            while (tables.next()) {
                String table = tables.getString(1);
                if (!excludedTables.contains(table)) {
                    result.put(table, rows(table, table.equals("users")
                            ? excludedUserColumns : Set.of()));
                }
            }
        }
        return result;
    }

    private List<List<String>> rows(String table,
            Set<String> excludedColumns) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (var connection = database.open(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT * FROM \"" + table.replace("\"",
                "\"\"") + "\" ORDER BY rowid")) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= rows.getMetaData(
                        ).getColumnCount(); column++) {
                    if (!excludedColumns.contains(rows.getMetaData(
                            ).getColumnName(column))) {
                        Object value = rows.getObject(column);
                        values.add(value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes)
                                : value == null ? "<NULL>" : value.toString());
                    }
                }
                result.add(values);
            }
        }
        return result;
    }

    private static final class CapturingMailer implements RecoveryMailer {
        String verificationToken;

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
            verificationToken = token;
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) { }
    }
}

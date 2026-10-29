package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import dev.leverage.domain.UserStatus;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @TempDir
    Path temporaryDirectory;

    private CapturingMailer mailer;
    private AuthService auth;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        database.initialize();
        mailer = new CapturingMailer();
        auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
    }

    @Test
    void completeAccountLifecycleAndPermissions() {
        var user = auth.register("Player_One", "player@example.test",
                "long-enough-password");
        assertEquals(UserStatus.PENDING_VERIFICATION, user.status());
        assertThrows(ServiceException.class,
                () -> auth.login("Player_One", "long-enough-password"));

        var verified = auth.confirmEmail(mailer.verificationToken);
        assertEquals(UserStatus.ACTIVE, verified.status());
        var firstLogin = auth.login("player@example.test",
                "long-enough-password", true, "PC principal");
        assertEquals(user.id(), auth.authenticate(firstLogin.accessToken()).user().id());
        assertEquals(1, auth.listDeviceSessions(auth.authenticate(
                firstLogin.accessToken())).size());

        auth.requestPasswordReset("player@example.test");
        auth.confirmPasswordReset(mailer.resetToken, "different-long-password");
        assertThrows(ServiceException.class,
                () -> auth.authenticate(firstLogin.accessToken()));
        assertThrows(ServiceException.class,
                () -> auth.refresh(firstLogin.refreshToken()));
        assertThrows(ServiceException.class,
                () -> auth.login("Player_One", "long-enough-password"));
        assertEquals(user.id(), auth.login("Player_One",
                "different-long-password").user().id());

        var admin = auth.createBootstrapAdmin("RootAdmin",
                "admin@example.test", "admin-password-long");
        var adminSession = auth.login("RootAdmin", "admin-password-long");
        var actor = auth.authenticate(adminSession.accessToken());
        var promoted = auth.changeRole(actor, user.id(), Role.MODERATOR);

        assertEquals(Role.MODERATOR, promoted.role());
        assertTrue(Permission.forRole(promoted.role()).contains(
                Permission.USERS_MODERATE));
        assertEquals(2, auth.listUsers(actor).size());
        auth.setSuspended(actor, user.id(), true);
        assertEquals(List.of(user.id()), auth.listUsers(actor,
                true).stream().map(item -> item.id()).toList());
        assertEquals(List.of(admin.id()), auth.listUsers(actor,
                false).stream().map(item -> item.id()).toList());
        assertEquals(Role.ADMIN, admin.role());
    }

    @Test
    void revokedSessionCannotIssueTicketUsingPreviouslyAuthenticatedActor() {
        auth.createBootstrapAdmin("TicketPlayer", "ticket@example.test",
                "ticket-password-long");
        String access = auth.login("TicketPlayer", "ticket-password-long").accessToken();
        var actor = auth.authenticate(access);
        auth.requestPasswordReset("ticket@example.test");
        auth.confirmPasswordReset(mailer.resetToken, "replacement-password-long");
        assertEquals("INVALID_SESSION", assertThrows(ServiceException.class,
                () -> auth.createGameTicket(actor, "survival")).code());
    }

    @Test
    void tokensCanOnlyBeUsedOnce() {
        auth.register("Player_Two", "two@example.test", "another-long-password");
        String token = mailer.verificationToken;
        auth.confirmEmail(token);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> auth.confirmEmail(token));
        assertEquals("INVALID_TOKEN", exception.code());
    }

    @Test
    void originalUsernameAndEmailStayUniqueWithoutNormalizedColumns() {
        auth.register("CasePlayer", "Mixed@Example.test", "another-long-password");
        String token = mailer.verificationToken;
        assertEquals("ACCOUNT_EXISTS", assertThrows(ServiceException.class,
                () -> auth.register("CASEPLAYER", "other@example.test",
                "another-long-password")).code());
        assertEquals("ACCOUNT_EXISTS", assertThrows(ServiceException.class,
                () -> auth.register("OtherPlayer", "MIXED@example.test",
                "another-long-password")).code());
        auth.confirmEmail(token);
        assertEquals("CasePlayer", auth.login("caseplayer",
                "another-long-password").user().username());
        assertEquals("Mixed@Example.test", auth.login("mixed@example.test",
                "another-long-password").user().email());
    }

    @Test
    void sixDigitCodeVerifiesOnlyItsEmailAndConsumesTheLink() {
        var account = auth.register("CodePlayer", "code@example.test",
                "another-long-password");
        String code = mailer.verificationCode;
        String linkToken = mailer.verificationToken;
        assertTrue(code.matches("[0-9]{6}"));
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> auth.confirmEmailCode("unknown@example.test", code)).code());
        var verified = auth.confirmEmailCode("CODE@example.test", code);
        assertEquals(account.id(), verified.id());
        assertEquals(UserStatus.ACTIVE, verified.status());
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> auth.confirmEmailCode("code@example.test", code)).code());
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> auth.confirmEmail(linkToken)).code());
    }

    @Test
    void verificationCodeLocksAfterFiveFailedAttemptsAcrossServiceRestarts() {
        auth.register("CodeLimit", "limit@example.test", "another-long-password");
        String code = mailer.verificationCode;
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                    () -> auth.confirmEmailCode("limit@example.test", wrong)).code());
        }
        AuthService restarted = new AuthService(database,
                new PasswordHasher(10_000), mailer,
                Clock.systemUTC(), Duration.ofHours(12),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> restarted.confirmEmailCode("limit@example.test", code)).code());
        auth.requestEmailVerification("limit@example.test");
        assertEquals(UserStatus.ACTIVE,
                auth.confirmEmailCode("limit@example.test",
                mailer.verificationCode).status());
    }

    @Test
    void codeExpiresAfterTenMinutesButLegacyLinkStillWorks() {
        auth.register("CodeExpiry", "expiry@example.test", "another-long-password");
        AuthService later = new AuthService(database, new PasswordHasher(10_000), mailer,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> later.confirmEmailCode("expiry@example.test",
                mailer.verificationCode)).code());
        assertEquals(UserStatus.ACTIVE, later.confirmEmail(
                mailer.verificationToken).status());
    }

    @Test
    void resendingInvalidatesTheOldLinkAndCodeChallenge() {
        auth.register("CodeResend", "resend@example.test", "another-long-password");
        String oldToken = mailer.verificationToken;
        auth.requestEmailVerification("resend@example.test");
        assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                () -> auth.confirmEmail(oldToken)).code());
        assertEquals(UserStatus.ACTIVE,
                auth.confirmEmailCode("resend@example.test",
                mailer.verificationCode).status());
    }

    @Test
    void storedCodeIsHashedAndMalformedCodesAreRejected() throws Exception {
        auth.register("CodeStorage", "storage@example.test", "another-long-password");
        try (var connection = database.open();
                var statement = connection.createStatement();
             var rows = statement.executeQuery(
                "SELECT id, code_hash FROM account_tokens WHERE code_hash IS NOT NULL")) {
            assertTrue(rows.next());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    dev.leverage.security.SecureTokens.hash(rows.getString(1)
                    + ":" + mailer.verificationCode),
                    rows.getBytes(2));
        }
        for (String invalid : new String[] { "", "12345",
                "1234567", "abcdef", "１２３４５６" }) {
            assertEquals("INVALID_TOKEN", assertThrows(ServiceException.class,
                    () -> auth.confirmEmailCode("storage@example.test", invalid)).code());
        }
        assertEquals(UserStatus.ACTIVE,
                auth.confirmEmailCode("storage@example.test",
                mailer.verificationCode).status());
    }

    @Test
    void emailChangeRequiresPasswordAndConfirmationThenRevokesSessions() {
        var registered = auth.register("EmailPlayer",
                "old@example.test", "current-password-long");
        auth.confirmEmail(mailer.verificationToken);
        var login = auth.login("old@example.test",
                "current-password-long", true, "PC principal");
        var actor = auth.authenticate(login.accessToken());

        ServiceException wrongPassword = assertThrows(ServiceException.class,
                () -> auth.requestEmailChange(actor,
                "new@example.test", "wrong-password-long"));
        assertEquals("INVALID_PASSWORD", wrongPassword.code());

        auth.requestEmailChange(actor, "new@example.test", "current-password-long");
        assertEquals("new@example.test", mailer.emailChangeRecipient);
        assertEquals(registered.id(), auth.login("old@example.test",
                "current-password-long").user().id());
        assertThrows(ServiceException.class,
                () -> auth.login("new@example.test", "current-password-long"));

        var changed = auth.confirmEmail(mailer.emailChangeToken);
        assertEquals("new@example.test", changed.email());
        assertThrows(ServiceException.class,
                () -> auth.authenticate(login.accessToken()));
        assertThrows(ServiceException.class, () -> auth.refresh(login.refreshToken()));
        assertThrows(ServiceException.class,
                () -> auth.login("old@example.test", "current-password-long"));
        assertEquals(registered.id(), auth.login("new@example.test",
                "current-password-long").user().id());

        ServiceException reused = assertThrows(ServiceException.class,
                () -> auth.confirmEmail(mailer.emailChangeToken));
        assertEquals("INVALID_TOKEN", reused.code());
    }

    @Test void changingUsernameRequiresCurrentPasswordPreservesUuidAndRejectsDuplicates()
            {
        var original=auth.createBootstrapAdmin("BeforeName",
                "before-name@example.test", "current-long-password");
        var login=auth.login("BeforeName", "current-long-password", true, "PC");
        var actor=auth.authenticate(login.accessToken());
        assertEquals("CURRENT_PASSWORD_INCORRECT", assertThrows(ServiceException.class,
                ()->auth.changeUsername(actor, "AfterName", "wrong-password")).code());
        var changed=auth.changeUsername(actor, "AfterName", "current-long-password");
        assertEquals(original.id(), changed.id());
        assertEquals(original.email(), changed.email());
        assertEquals(original.role(), changed.role());
        assertEquals("AfterName", auth.authenticate(
                login.accessToken()).user().username());
        assertThrows(ServiceException.class, ()->auth.login("BeforeName",
                "current-long-password"));
        assertEquals(original.id(), auth.login("AfterName",
                "current-long-password").user().id());
        auth.register("TakenName", "taken@example.test", "another-long-password");
        assertEquals("USERNAME_EXISTS", assertThrows(ServiceException.class, ()->
                auth.changeUsername(actor, "takenname", "current-long-password")).code());
        assertEquals("AfterName", auth.refresh(login.refreshToken()).user().username());
    }

    @Test void passwordChangeRevokesOnlyTheCurrentAccountsSessions() {
        auth.createBootstrapAdmin("PasswordUser", "password@example.test",
                "current-long-password");
        var first=auth.login("PasswordUser", "current-long-password", true, "First");
        var second=auth.login("PasswordUser", "current-long-password", true, "Second");
        var actor=auth.authenticate(first.accessToken());
        auth.register("OtherAccount", "other@example.test", "other-long-password");
        auth.confirmEmail(mailer.verificationToken);
        var other=auth.login("OtherAccount", "other-long-password");
        auth.requestPasswordReset("password@example.test");
        String reset=mailer.resetToken;
        auth.requestEmailChange(actor, "next@example.test", "current-long-password");
        String emailChange=mailer.emailChangeToken;
        assertThrows(ServiceException.class, ()->auth.changePassword(actor,
                "incorrect-current", "new-long-password"));
        assertThrows(ServiceException.class, ()->auth.changePassword(actor,
                "current-long-password", "short"));
        assertEquals("PasswordUser", auth.authenticate(
                first.accessToken()).user().username());
        auth.changePassword(actor, "current-long-password", "new-long-password");
        assertThrows(ServiceException.class, ()->auth.authenticate(first.accessToken()));
        assertThrows(ServiceException.class, ()->auth.authenticate(second.accessToken()));
        assertThrows(ServiceException.class, ()->auth.refresh(first.refreshToken()));
        assertThrows(ServiceException.class, ()->auth.refresh(second.refreshToken()));
        assertThrows(ServiceException.class, ()->auth.confirmPasswordReset(reset,
                "stolen-long-password"));
        assertThrows(ServiceException.class, ()->auth.confirmEmail(emailChange));
        assertThrows(ServiceException.class, ()->auth.login("PasswordUser",
                "current-long-password"));
        assertThrows(ServiceException.class, ()->auth.changeUsername(actor,
                "StaleSession", "new-long-password"));
        assertEquals("PasswordUser", auth.login("PasswordUser",
                "new-long-password").user().username());
        assertEquals("OtherAccount", auth.authenticate(
                other.accessToken()).user().username());
    }

    private static final class CapturingMailer implements RecoveryMailer {
        private String verificationToken;
        private String verificationCode;
        private String resetToken;
        private String emailChangeToken;
        private String emailChangeRecipient;

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
            verificationToken = token;
        }

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt,
                String code, Instant codeExpiresAt) {
            verificationToken = token;
            verificationCode = code;
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) {
            resetToken = token;
        }

        @Override
        public void sendEmailChangeVerification(String email,
                String username, String token, Instant expiresAt) {
            emailChangeRecipient = email;
            emailChangeToken = token;
        }
    }
}

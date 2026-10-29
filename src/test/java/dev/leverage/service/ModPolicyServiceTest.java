package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModPolicyServiceTest {
    private static final String ALLOWED = "1111111111111111111111111111111111111111";
    private static final String BLOCKED = "2222222222222222222222222222222222222222";

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private AuthService auth;
    private ModPolicyService policies;
    private String playerPassword;
    private CapturingMailer mailer;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        database = new Database(temporaryDirectory.resolve("test.db"));
        database.initialize();
        mailer = new CapturingMailer();
        auth = new AuthService(database, new PasswordHasher(10_000), mailer, clock,
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        Path policy = temporaryDirectory.resolve("mods.json");
        Files.writeString(policy,
        """
                {
                  "schemaVersion": 1,
                  "policies": [{
                    "id": "survival-1", "server": "survival", "version": "26.3",
                    "allowed": [{"name":"Base", "sha1":"%s", "required":true}],
                    "blocked": [{"name":"Trampa", "sha1":"%s", "reason":"Ventaja \
                injusta"}]
                  }]
                }
                """
        .formatted(ALLOWED, BLOCKED));
        policies = new ModPolicyService(database, policy, clock, Duration.ofDays(30));
        playerPassword = "player-password-long";
    }

    @Test
    void blockedModCreatesReviewableEvidenceAndAdminCanLiftOrBan() throws Exception {
        var admin = auth.createBootstrapAdmin("Admin",
                "admin@example.test", "admin-password-long");
        var adminActor = auth.authenticate(auth.login(admin.username(),
                "admin-password-long").accessToken());
        createPlayer("PlayerOne", "one@example.test");
        var login = auth.login("PlayerOne", playerPassword);
        var player = auth.authenticate(login.accessToken());

        var result = policies.verify(player, "survival", "26.3", "survival-1",
                List.of(new ModPolicyService.ClientMod("base.jar", ALLOWED, 10),
                new ModPolicyService.ClientMod("cheat.jar", BLOCKED, 20)));

        assertEquals("SUSPENDED", result.status());
        assertEquals(BLOCKED, policies.listSuspensions(adminActor).getFirst(
                ).evidence().getFirst().sha1());
        assertThrows(ServiceException.class,
                () -> auth.authenticate(login.accessToken()));
        assertEquals("ACCOUNT_SUSPENDED", assertThrows(ServiceException.class,
                () -> auth.login("PlayerOne", playerPassword)).code());

        policies.resolve(adminActor, result.suspensionId(),
                ModPolicyService.Resolution.LIFT, "Falso positivo");
        var secondLogin = auth.login("PlayerOne", playerPassword);
        var secondResult = policies.verify(auth.authenticate(secondLogin.accessToken()),
                "survival", "26.3", "survival-1",
                List.of(new ModPolicyService.ClientMod("cheat.jar", BLOCKED, 20)));
        policies.resolve(adminActor, secondResult.suspensionId(),
                ModPolicyService.Resolution.BAN,
                "Incidencia confirmada");
        assertEquals("ACCOUNT_BANNED", assertThrows(ServiceException.class,
                () -> auth.login("PlayerOne", playerPassword)).code());
        try (var connection = database.open(); var query = connection.prepareStatement(
                "SELECT by_mod, created_by, resolution, expires_at FROM accou"
                + "nt_sanctions WHERE id=?")) {
            query.setString(1, secondResult.suspensionId().toString());
            try (var row = query.executeQuery()) {
                org.junit.jupiter.api.Assertions.assertTrue(row.next());
                assertEquals(1, row.getInt("by_mod"));
                assertEquals(admin.id().toString(), row.getString("created_by"));
                assertEquals("BANNED", row.getString("resolution"));
                org.junit.jupiter.api.Assertions.assertNull(row.getObject("expires_at"));
            }
        }
        try (var connection = database.open(); var query = connection.createStatement();
             var count = query.executeQuery("SELECT COUNT(*) FROM account_sanctions")) {
            org.junit.jupiter.api.Assertions.assertTrue(count.next());
            assertEquals(2, count.getInt(1));
        }
    }

    @Test
    void suspensionExpiresAfterThirtyDaysWhenAdminDoesNothing() {
        createPlayer("PlayerTwo", "two@example.test");
        var actor = auth.authenticate(auth.login("PlayerTwo",
                playerPassword).accessToken());
        policies.verify(actor, "survival", "26.3", "survival-1",
                List.of(new ModPolicyService.ClientMod("cheat.jar", BLOCKED, 20)));

        clock.advance(Duration.ofDays(30).plusSeconds(1));

        assertEquals("PlayerTwo", auth.login("PlayerTwo",
                playerPassword).user().username());
    }

    @Test
    void cleanInventoryHasNoCachedApprovalOrExpiration() {
        createPlayer("CleanPlayer", "clean@example.test");
        var actor = auth.authenticate(auth.login("CleanPlayer",
                playerPassword).accessToken());
        var clean = policies.verifyInventoryHashes(actor, "survival",
                "26.3", "default", List.of(ALLOWED));
        assertEquals("APPROVED", clean.status());
        org.junit.jupiter.api.Assertions.assertNull(clean.expiresAt());
        assertEquals("REJECTED", policies.verifyInventoryHashes(actor,
                "survival", "26.3", "default",
                List.of()).status());
    }

    @Test
    void unknownModsRejectInventoryWithoutSuspendingAccount() {
        createPlayer("PlayerThree", "three@example.test");
        var actor = auth.authenticate(auth.login("PlayerThree",
                playerPassword).accessToken());
        var result = policies.verify(actor, "survival", "26.3", "survival-1",
                List.of(new ModPolicyService.ClientMod("mystery.jar",
                "3333333333333333333333333333333333333333", 30)));

        assertEquals("REJECTED", result.status());
        assertEquals(1, result.unknownMods().size());
        assertEquals(1, result.missingRequired().size());
        org.junit.jupiter.api.Assertions.assertNull(result.expiresAt());
        assertEquals("PlayerThree", auth.authenticate(actorToken(
                "PlayerThree")).user().username());
    }

    private void createPlayer(String username, String email) {
        auth.register(username, email, playerPassword);
        auth.confirmEmail(mailer.verificationToken);
    }

    private String actorToken(String username) {
        return auth.login(username, playerPassword).accessToken();
    }

    private static final class CapturingMailer implements RecoveryMailer {
        private String verificationToken;

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
            verificationToken = token;
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

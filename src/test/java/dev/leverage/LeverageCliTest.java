package dev.leverage;

import dev.leverage.config.AppConfig;
import dev.leverage.db.Database;
import dev.leverage.domain.Role;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class LeverageCliTest {
    @TempDir Path temporary;

    @Test void standalonePromotesByEmailWithoutAnotherAdminAndIsIdempotent(
            ) throws Exception {
        Fixture fixture=fixture();
        var user=fixture.auth.register("ConsolePlayer",
                "mixed@example.test", "console-long-password");
        fixture.auth.confirmEmail(fixture.mail.verification);
        var login=fixture.auth.login("ConsolePlayer", "console-long-password");
        assertEquals(0, Leverage.run(new String[] { "admin", "--email",
                " MIXED@example.test ", "--config", fixture.config.toString() }));
        assertEquals(Role.ADMIN, fixture.auth.authenticate(
                login.accessToken()).user().role());
        assertEquals(user.id(), fixture.auth.authenticate(
                login.accessToken()).user().id());
        assertEquals(0, Leverage.run(new String[] { "admin", "--config",
                fixture.config.toString(), "--email", "mixed@example.test" }));
        try (var connection=fixture.database.open(); var statement
                =connection.createStatement(); var result=statement.executeQuery(
                "SELECT COUNT(*) FROM audit_events WHERE action='ROLE_CHANGED_FROM_CO"
                + "NSOLE'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test void unknownEmailNeverCreatesAnAccount() throws Exception {
        Fixture fixture=fixture();
        var failure=assertThrows(ServiceException.class,
                ()->Leverage.run(new String[] { "admin", "--email",
                "missing@example.test", "--config", fixture.config.toString() }));
        assertEquals("USER_NOT_FOUND", failure.code());
        try (var connection=fixture.database.open(); var statement
                =connection.createStatement(); var result
                =statement.executeQuery("SELECT COUNT(*) FROM users")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    @Test void malformedOrConflictingOptionsAreRejectedBeforeOpeningAnything() {
        Path absent=temporary.resolve("never-created/config/leverage.properties");
        String[][] invalid= {
                { "admin", "--config", absent.toString() },
                { "admin", "--email", "bad", "--config", absent.toString() },
                { "admin", "--email", "a@example.test", "--host",
                        "http://127.0.0.1:8080" },
                { "admin", "--email", "a@example.test", "--mirror", "mirror.json" },
                { "admin", "--email", "a@example.test", "--email", "b@example.test" },
                { "admin", "--email", "a@example.test", "--config",
                        absent.toString(), "--config", absent.toString() },
                { "admin", "--email" }
        };
        for (String[] args:invalid)assertThrows(Exception.class, ()->Leverage.run(args));
        assertFalse(Files.exists(temporary.resolve("never-created")));
    }

    @Test void missingConfigurationOrDatabaseIsNotCreated() throws Exception {
        Path config=temporary.resolve("server/config/leverage.properties");
        Files.createDirectories(config.getParent());
        assertThrows(IllegalArgumentException.class, ()->Leverage.run(new String[] {
                "admin", "--email", "a@example.test", "--config", config.toString() }));
        Files.writeString(config, "database.path=data/missing.db\n");
        assertEquals(temporary.resolve("server/data/missing.db"),
                AppConfig.load(config).databasePath(),
                "Test must never use an environment-selected personal database");
        assertThrows(java.io.IOException.class, ()->Leverage.run(new String[] { "admin",
                "--email", "a@example.test", "--config", config.toString() }));
        assertFalse(Files.exists(temporary.resolve("server/data")));
    }

    @Test void existingOnlyDatabaseCannotCreateAFileEvenIfItDisappearsBeforeOpen(
            ) throws Exception {
        Path path=temporary.resolve("database with spaces # ñ.db");
        Database database=new Database(path);
        database.initialize();
        Database existing=Database.existing(path);
        try (var ignored=existing.open()) { }
        Files.delete(path);
        assertThrows(java.sql.SQLException.class, existing::open);
        assertFalse(Files.exists(path));
    }

    @Test void commandDoesNotInitializeOrMigrateASchema() throws Exception {
        Path config=temporary.resolve("server/config/leverage.properties");
        Files.createDirectories(config.getParent());
        Path path=temporary.resolve("server/data/empty.db");
        Database database=new Database(path);
        try (var ignored=database.open()) { }
        Files.writeString(config, "database.path=data/empty.db\n");
        assertEquals(path, AppConfig.load(config).databasePath(),
                "Isolated database required");
        assertThrows(ServiceException.class, ()->Leverage.run(new String[] { "admin",
                "--email", "a@example.test", "--config", config.toString() }));
        try (var connection=database.open(); var statement
                =connection.createStatement(); var result=statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    private Fixture fixture() throws Exception {
        Path base=temporary.resolve("server with spaces # ñ");
        Path config=base.resolve("config/leverage.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "database.path=data/server.db\nmail.mode=console\n");
        Path path=base.resolve("data/server.db");
        assertEquals(path, AppConfig.load(config).databasePath(),
                "Isolated database required");
        Database database=new Database(path);
        database.initialize();
        Mail mail=new Mail();
        AuthService auth=new AuthService(database, new PasswordHasher(10000),
                mail, Clock.systemUTC(), Duration.ofHours(1),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        return new Fixture(config, database, auth, mail);
    }
    private record Fixture(Path config, Database database,
            AuthService auth, Mail mail) { }
    private static final class Mail implements RecoveryMailer {
        String verification;
        public void sendEmailVerification(String email, String username,
                String token, Instant expires) {
            verification=token;
        }
        public void sendPasswordReset(String email, String username,
                String token, Instant expires) { }
    }
}

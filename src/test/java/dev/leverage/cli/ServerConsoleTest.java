package dev.leverage.cli;

import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConsoleTest {
    @TempDir
    Path temporaryDirectory;

    private Database database;
    private AuthService auth;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(bytes,
            true, StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("console.db"));
        database.initialize();
        auth = new AuthService(database, new PasswordHasher(10_000),
                new NoMail(), Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.register("ConsoleUser", "Mixed@Example.test", "console-test-password");
    }

    @Test
    void promotesByEmailAndKeepsPendingAccountState() throws Exception {
        run("admin MIXED@example.test\r\n");

        assertEquals("ADMIN", accountValue("role"));
        assertEquals("PENDING_VERIFICATION", accountValue("status"));
        assertTrue(text().contains("Administrator: ConsoleUser (Mixed@Example.test)."));
        assertTrue(text().contains("Account status is unchanged: PENDING_VERIFICATION"));
        assertEquals(1, promotionAuditCount());
    }

    @Test
    void invalidCommandsAndExtraArgumentsDoNotMutateAccounts() throws Exception {
        run("\n \t\r\nhelp\nadmin\nadmin Mixed@Example.test extra\n"
                + ("stop\nunknown Mixed@Example.test\nadmin Mixed@Example.test\0"
                + "\n"));

        assertEquals("USER", accountValue("role"));
        assertEquals(0, promotionAuditCount());
        assertTrue(text().contains("Usage: admin <email> | help."));
        assertTrue(text().contains("Invalid command."));
        assertTrue(text().contains("invalid characters"));
    }

    @Test
    void serviceErrorsAreFriendlyAndDoNotPreventFollowingValidCommand() throws Exception {
        run("admin not-an-email\nadmin absent@example.test\nadmin mixed@example.t"
                + "est\n");

        assertTrue(text().contains("INVALID_EMAIL:"));
        assertTrue(text().contains("USER_NOT_FOUND:"));
        assertTrue(text().contains("Administrator: ConsoleUser"));
        assertEquals("ADMIN", accountValue("role"));
        assertEquals(1, promotionAuditCount());
    }

    @Test
    void repeatedPromotionIsIdempotentAndHandlesFinalLineWithoutNewline(
            ) throws Exception {
        run("admin mixed@example.test\nadmin MIXED@example.test");

        assertEquals("ADMIN", accountValue("role"));
        assertEquals(1, promotionAuditCount());
        assertEquals(2, text().lines().filter(line -> line.startsWith(
                "Administrator:")).count());
    }

    @Test
    void excessiveLineIsRejectedAsWholeAndNextCommandStillWorks() throws Exception {
        run("admin mixed@example.test " + "x".repeat(2_000)
                + "\nadmin mixed@example.test\n");

        assertTrue(text().contains("Command is too long"));
        assertEquals(1, promotionAuditCount());
        assertEquals(1, text().lines().filter(line -> line.startsWith(
                "Administrator:")).count());
    }

    @Test
    void eofLeavesReaderOpenAndPrintsStartupHelpOnlyOnce() throws Exception {
        TrackingReader reader = new TrackingReader("");
        ServerConsole.run(auth, reader, output);

        assertFalse(reader.closed);
        assertEquals(1, text().lines().filter(line -> line.startsWith(
                "Local console ready.")).count());
        assertEquals("USER", accountValue("role"));
        assertEquals(0, promotionAuditCount());
    }

    @Test
    void startCreatesDaemonThreadAndDoesNotCloseInputOnEof() throws Exception {
        TrackingInput input = new TrackingInput("help\n".getBytes(
                StandardCharsets.UTF_8));
        Thread console = ServerConsole.start(auth, input, output);
        console.join(5_000);

        assertTrue(console.isDaemon());
        assertFalse(console.isAlive());
        assertFalse(input.closed);
        assertEquals("USER", accountValue("role"));
    }

    private void run(String commands) {
        ServerConsole.run(auth, new StringReader(commands), output);
    }

    private String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String accountValue(String column) throws Exception {
        try (var connection = database.open(); var statement
                = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT "
                + column + " FROM users")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private int promotionAuditCount() throws Exception {
        try (var connection = database.open(); var statement
                = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM audit_events WHERE action='ROLE_CHANGED"
                + "_FROM_CONSOLE'")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static final class TrackingReader extends StringReader {
        private boolean closed;

        private TrackingReader(String text) {
            super(text);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class NoMail implements RecoveryMailer {
        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) {
        }
    }
}

package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Role;
import dev.leverage.domain.Permission;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UsersDirectoryServiceTest {
    @TempDir Path directory;
    private Database database;
    private AuthService auth;
    private AuthService.AuthenticatedUser admin;

    @BeforeEach void setUp() throws Exception {
        database = new Database(directory.resolve("directory.db"));
        database.initialize();
        auth = new AuthService(database, new PasswordHasher(10_000),
                new RecoveryMailer() {
            public void sendEmailVerification(String email, String username,
                    String token, Instant expiry) { }
            public void sendPasswordReset(String email, String username,
                    String token, Instant expiry) { }
        }, Clock.systemUTC(), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofMinutes(10), true);
        auth.createBootstrapAdmin("DirectoryAdmin", "admin@example.test",
                "directory-admin-password");
        admin = auth.authenticate(auth.login("DirectoryAdmin",
                "directory-admin-password").accessToken());
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
            """
                    INSERT INTO \
                    users(id,username,email,password_hash,role,status,created_at,updated\
                    _at,password_changed_at)
                    VALUES(?,?,?,'not-a-login-hash','USER','ACTIVE',1,1,1)
                    """
            )) {
                for (int number = 0; number < 105; number++) {
                    insert.setString(1, new UUID(0, number + 1).toString());
                    insert.setString(2, "Player_" + String.format("%03d", number));
                    insert.setString(3, "player" + number + "@example.test");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    @Test void supportsAllPageSizesAndClampsBoundsWithoutLosingOrRepeatingRows() {
        for (int size : List.of(10, 25, 50, 100)) {
            var first = auth.searchUsers(admin, "", -9, size, null);
            assertEquals(1, first.page());
            assertEquals(106, first.total());
            assertEquals(size, first.users().size());
            int totalPages = (106 + size - 1) / size;
            assertEquals(totalPages, first.totalPages());
            var last = auth.searchUsers(admin, "", Integer.MAX_VALUE, size, null);
            assertEquals(totalPages, last.page());
            assertEquals(106 - (totalPages - 1) * size, last.users().size());
        }
        var ordered = new java.util.ArrayList<UUID>();
        for (int page = 1; page <= 5; page++) {
            ordered.addAll(auth.searchUsers(admin, "", page, 25,
                    null).users().stream().map(user -> user.id()).toList());
        }
        assertEquals(auth.listUsers(admin).stream().map(
                user -> user.id()).toList(), ordered);
        assertEquals(106, ordered.stream().distinct().count());
    }

    @Test void searchesUsernameAndEmailCaseInsensitivelyAndTreatsWildcardsAndSqlLiterally(
            ) {
        assertEquals(1, auth.searchUsers(admin, " pLaYeR_042 ", 1, 25, null).total());
        assertEquals("Player_042", auth.searchUsers(admin, "PLAYER42@EXAMPLE.TEST",
                1, 25, null).users().getFirst().username());
        assertEquals(105, auth.searchUsers(admin, "_", 1, 25, null).total());
        for (String literal : List.of("%", "\\", "' OR 1=1 --", "does-not-exist")) {
            var empty = auth.searchUsers(admin, literal, 99, 25, null);
            assertTrue(empty.users().isEmpty());
            assertEquals(0, empty.total());
            assertEquals(1, empty.page());
            assertEquals(1, empty.totalPages());
        }
        assertEquals(106, auth.listUsers(admin).size());
    }

    @Test void preservesSuspensionFilteringAndPermissionChecks() {
        UUID playerId = new UUID(0, 1);
        auth.setSuspended(admin, playerId, true);
        assertEquals(List.of(playerId), auth.searchUsers(admin, "Player_", 1,
                25, true).users().stream().map(user -> user.id()).toList());
        assertEquals(104, auth.searchUsers(admin, "Player_", 1, 25, false).total());
        var regular = new AuthService.AuthenticatedUser(auth.listUsers(admin,
                false).getFirst(), "not-used", Permission.forRole(Role.USER));
        assertEquals(Role.USER, regular.user().role());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> auth.searchUsers(regular, "", 1, 25, null)).status());
    }

    @Test void rejectsUnsupportedSizesAndOversizedSearches() {
        for (int size : List.of(0, 1, 24, 26, 101, Integer.MAX_VALUE)) {
            assertEquals("INVALID_PAGE_SIZE", assertThrows(ServiceException.class,
                    () -> auth.searchUsers(admin, "", 1, size, null)).code());
        }
        assertEquals("INVALID_SEARCH", assertThrows(ServiceException.class,
                () -> auth.searchUsers(admin, "x".repeat(201), 1, 25, null)).code());
    }
}

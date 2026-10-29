package dev.leverage.http;

import dev.leverage.client.InstanceLayout;
import dev.leverage.client.LauncherModVerifier;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.ModPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ModPolicyApiServerTest {
    @TempDir
    Path temporaryDirectory;

    private ApiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void inventoryWatchAndFinalScanReplaceStoredCompliance() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("api.db"));
        database.initialize();
        RecoveryMailer mailer = new RecoveryMailer() {
            @Override
            public void sendEmailVerification(String email, String username,
                    String token, Instant expiresAt) {
            }

            @Override
            public void sendPasswordReset(String email, String username,
                    String token, Instant expiresAt) {
            }
        };
        AuthService auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("Player", "player@example.test",
                "player-password-long");
        String access = auth.login("Player", "player-password-long").accessToken();

        Path policy = temporaryDirectory.resolve("mods.json");
        Files.writeString(policy,
        """
                {"schemaVersion":1,"policies":[{
                  "id":"survival-26.3-1","server":"survival","version":"26.3",
                  "allowed":[{"name":"Example","sha1":"aaf4c61ddcc5e8a2dabede0f3b482cd9a\
                ea9434d","required":true}],
                  "blocked":[{"name":"Banned","sha1":"f702775542adefab834a1f25d8456bec8b\
                7abfd9"}]
                }]}
                """
        );
        ModPolicyService mods = new ModPolicyService(database, policy, Clock.systemUTC(),
                Duration.ofDays(30));
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC(),
                null, null, null, mods);
        server.start();
        URI host = URI.create("http://127.0.0.1:" + server.port());

        Path modsDirectory = new InstanceLayout(temporaryDirectory.resolve(".minecraft"))
                .mods("survival", "26.3");
        Files.createDirectories(modsDirectory);
        Files.writeString(modsDirectory.resolve("example.jar"), "hello");

        LauncherModVerifier verifier = new LauncherModVerifier();
        assertEquals("ALLOWED", verifier.verifyMod(host, access,
                "survival", "26.3", "default",
                new ModPolicyService.ClientMod("example.jar",
                "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", 5)).status());
        var unknown = verifier.verifyHash(host, access, "survival", "26.3", "default",
                "3333333333333333333333333333333333333333");
        assertEquals("UNKNOWN", unknown.status());
        assertTrue(unknown.decisions().containsKey(
                "3333333333333333333333333333333333333333"));
        assertEquals(null, unknown.decisions().get(
                "3333333333333333333333333333333333333333"));
        assertEquals(400, requestTicket(host, access).statusCode());

        assertEquals("APPROVED", verifier.verifyBeforePlay(host, access,
                temporaryDirectory.resolve(".minecraft"), "survival", "26.3").status());

        // A previous clean scan cannot be reused to request a ticket without an
        // inventory.
        assertEquals(400, requestTicket(host, access).statusCode());
        Path minecraft = temporaryDirectory.resolve(".minecraft");
        var first = verifier.prepareToPlay(host, access, minecraft,
                "survival", "26.3", "default");
        assertTrue(first.approved());
        assertEquals("Player", auth.consumeGameTicket(first.ticket().token(),
                "survival").username());
        assertThrows(dev.leverage.service.ServiceException.class,
                () -> auth.consumeGameTicket(first.ticket().token(), "survival"));

        var updates = new LinkedBlockingQueue<LauncherModVerifier.Result>();
        var errors = new LinkedBlockingQueue<Exception>();
        try (var watcher = verifier.watchInstance(host, access,
                minecraft, "survival", "26.3", "default",
                updates::add, errors::add)) {
            assertEquals("APPROVED", next(updates).status());
            Files.writeString(modsDirectory.resolve("extra.jar"), "extra");
            assertEquals("REJECTED", next(updates).status());
            var rejected = verifier.prepareToPlay(host, access,
                    minecraft, "survival", "26.3", "default");
            assertFalse(rejected.approved());
            assertTrue(rejected.verification().decisions().containsValue(null));
            assertEquals("Player", auth.authenticate(access).user().username());

            Files.writeString(modsDirectory.resolve("extra.jar"), "hello");
            assertEquals("APPROVED", next(updates).status());
            assertEquals(2, watcher.inventory().size());
            Files.delete(modsDirectory.resolve("extra.jar"));
            assertEquals("APPROVED", next(updates).status());
            Files.delete(modsDirectory.resolve("example.jar"));
            assertEquals("REJECTED", next(updates).status());
            assertTrue(watcher.inventory().isEmpty());
            assertFalse(verifier.prepareToPlay(host, access, minecraft,
                    "survival", "26.3", "default").approved());
            assertTrue(errors.isEmpty(), errors.toString());
        }

        // No watcher is needed for the final scan to detect changes or apply a
        // server-side suspension.
        Files.writeString(modsDirectory.resolve("example.jar"), "hello");
        var earlier = verifier.prepareToPlay(host, access, minecraft,
                "survival", "26.3", "default").ticket();
        Files.writeString(modsDirectory.resolve("cheat.jar"), "banned");
        var suspended = verifier.prepareToPlay(host, access,
                minecraft, "survival", "26.3", "default");
        assertEquals("SUSPENDED", suspended.verification().status());
        assertNull(suspended.ticket());
        assertThrows(dev.leverage.service.ServiceException.class,
                () -> auth.authenticate(access));
        assertThrows(dev.leverage.service.ServiceException.class,
                () -> auth.consumeGameTicket(earlier.token(), "survival"));
        try (var connection = database.open(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT COUNT(*) FROM account_sanctions")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    private static LauncherModVerifier.Result next(
            LinkedBlockingQueue<LauncherModVerifier.Result> updates)
            throws Exception {
        var result = updates.poll(10, TimeUnit.SECONDS);
        assertNotNull(result, "The watcher did not respond to the file change.");
        return result;
    }

    private static HttpResponse<String> requestTicket(URI host,
            String access) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                host.resolve("/api/v1/game/tickets"))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                "{\"server\":\"survival\",\"version\":\"26.3\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}

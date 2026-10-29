package dev.leverage.http;

import dev.leverage.client.LauncherUpdateClient;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.LauncherVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherVersionApiTest {
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
    void clientHashesJarAndServerMakesTheDecisionWithoutLogin() throws Exception {
        Path launcher = temporaryDirectory.resolve("leverage.jar");
        Files.writeString(launcher, "launcher build");
        String hash = LauncherUpdateClient.sha1(launcher);
        Path versions = temporaryDirectory.resolve("versions.json");
        Files.writeString(versions,
        """
                {"schemaVersion":1,"channels":[{
                  "name":"stable","latestVersion":"1.0.0","releases":[{
                    "version":"1.0.0","sha1":"%s",
                    "downloadUrl":"https://example.test/leverage.jar","allowLaunch":true
                  }]
                }]}
                """
        .formatted(hash));

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
        server = new ApiServer("127.0.0.1", 0, auth,
                Clock.systemUTC(), null, null, null, null,
                new LauncherVersionService(versions));
        server.start();

        var result = new LauncherUpdateClient().check(
                java.net.URI.create("http://127.0.0.1:" + server.port()),
                "stable", launcher);

        assertEquals(LauncherUpdateClient.Status.CURRENT, result.status());
        assertTrue(result.canContinue());
        assertEquals(hash, result.latestSha1());
    }
}

package dev.leverage.http;

import dev.leverage.client.LauncherUsersClient;
import dev.leverage.db.Database;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;
import dev.leverage.skin.DefaultSkin;
import dev.leverage.skin.TextureService.SkinModel;
import dev.leverage.skin.TextureService;
import dev.leverage.skin.TextureService.TextureType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

// Real HTTP tests; every account, database and texture is isolated under a temporary
// directory.
class AdminUsersApiTest {
    @Test void datedBanPassesFromClientToServerAndRejectsMissingOrInvalidEnd(
            ) throws Exception {
        User target=player("DatedHttp", "dated-http@example.test");
        assertEquals(400, request("PUT", "/admin/users/"+target.id()+"/ban-until",
                "{\"reason\":\"Motivo\"}", token).statusCode());
        assertEquals(400, request("PUT", "/admin/users/"+target.id()+"/ban-until",
                "{\"reason\":\"Motivo\",\"expiresAt\":\"no-date\"}", token).statusCode());
        client.ban(host(), token, target.id(), "Temporal",
                Instant.now().plusSeconds(600));
        assertTrue(client.search(host(), token, "DatedHttp", 1,
                25).users().getFirst().banned());
        assertThrows(ServiceException.class, ()->auth.login(target.username(), PASSWORD));
    }
    @TempDir Path directory;
    private static final String PASSWORD = "isolated-account-password";
    private final HttpClient http = HttpClient.newHttpClient();
    private final LauncherUsersClient client = new LauncherUsersClient();
    private AuthService auth;
    private TextureService textures;
    private ApiServer server;
    private CapturingMailer mailer;
    private User operator;
    private String token;

    @BeforeEach void setUp() throws Exception {
        Database database = new Database(directory.resolve("accounts.db"));
        database.initialize();
        mailer = new CapturingMailer();
        auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        operator = auth.createBootstrapAdmin("Operator",
                "operator@example.test", PASSWORD);
        token = auth.login("Operator", PASSWORD).accessToken();
        textures = new TextureService(database, directory.resolve("textures"),
                directory.resolve("keys"),
                URI.create("http://127.0.0.1"), Clock.systemUTC());
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC(), null, textures);
        server.start();
    }

    @AfterEach void close() { server.close(); }

    @Test void enrichedDirectoryMatchesRealSkinDatesAndRestrictionsWithoutSecrets(
            ) throws Exception {
        User target = player("PlayerOne", "player@example.test");
        String targetToken = auth.login(target.username(), PASSWORD).accessToken();
        var profile = textures.upload(auth.authenticate(targetToken),
                TextureType.SKIN, SkinModel.SLIM, customSkin());
        var row = client.search(host(), token, "PlayerOne", 1, 25).users().getFirst();
        assertEquals(target.id(), row.id());
        assertEquals(target.createdAt().toEpochMilli(), row.createdAt().toEpochMilli());
        assertNotNull(row.lastSeenAt());
        assertFalse(row.banned());
        assertEquals(profile.skinHash(), row.skinHash());
        assertEquals(0xFF5CD79A, client.loadSkin(host(), row.skinHash()).getRGB(8, 8));
        auth.setSuspended(auth.authenticate(token), target.id(), true);
        row = client.search(host(), token, "PlayerOne", 1, 25).users().getFirst();
        assertEquals("SUSPENDED", row.status());
        assertFalse(row.banned());
        assertEquals("Suspensión administrativa", row.restrictionReason());
        client.ban(host(), token, target.id(), "Uso de clientes prohibidos");
        row = client.search(host(), token, "PlayerOne", 1, 25).users().getFirst();
        assertTrue(row.banned());
        assertTrue(row.restrictionReason().startsWith("Uso de clientes prohibidos"));
        assertNotNull(row.lastSeenAt());
        String legacy = request("GET", "/admin/users", null, token).body();
        assertTrue(legacy.contains("\"banned\":true"));
        assertFalse(legacy.contains("password_hash"));
        assertFalse(legacy.contains(PASSWORD));
        assertFalse(legacy.contains(targetToken));
    }

    @Test void passwordAndEmailActionsRevokeOldSessionsAndKeepUpdatedCredentialsUsable(
            ) throws Exception {
        User target = player("EditPlayer", "before@example.test");
        String oldToken = auth.login(target.username(), PASSWORD,
                true, "PC de prueba").accessToken();
        client.changePassword(host(), token, target.id(), "replacement-strong-password");
        assertEquals(401, request("GET", "/me", null, oldToken).statusCode());
        assertThrows(ServiceException.class,
                () -> auth.login(target.username(), PASSWORD));
        String newToken = auth.login(target.username(),
                "replacement-strong-password").accessToken();
        client.changeEmail(host(), token, target.id(), "after@example.test");
        assertEquals(401, request("GET", "/me", null, newToken).statusCode());
        var result = auth.login("after@example.test", "replacement-strong-password");
        assertEquals(target.id(), result.user().id());
        assertEquals(Role.USER, result.user().role());
        String directoryJson = request("GET",
                "/admin/users?q=EditPlayer&page=1&pageSize=25", null, token).body();
        assertTrue(directoryJson.contains("after@example.test"));
        assertFalse(directoryJson.contains("replacement-strong-password"));
    }

    @Test void
            administrativeSkinRemovalRevertsTargetToSteveAndPreservesOtherSharedBinding(
            ) throws Exception {
        User target = player("SkinPlayer", "skin@example.test");
        var actor = auth.authenticate(auth.login(target.username(),
                PASSWORD).accessToken());
        var shared = textures.upload(actor, TextureType.SKIN,
                SkinModel.SLIM, customSkin());
        textures.upload(auth.authenticate(token), TextureType.SKIN,
                SkinModel.CLASSIC, customSkin());
        client.clearSkin(host(), token, target.id());
        var row = client.search(host(), token, "SkinPlayer", 1, 25).users().getFirst();
        assertNull(row.skinHash());
        assertEquals(SkinModel.CLASSIC, textures.profileFor(actor).skinModel());
        assertNotNull(client.search(host(), token, "Operator", 1,
                25).users().getFirst().skinHash());
        assertEquals(0xFF5CD79A, client.loadSkin(host(), shared.skinHash()).getRGB(8, 8));
    }

    @Test void userAndModeratorCannotWriteAndMissingOrInvalidCredentialsAreRejected(
            ) throws Exception {
        User target = player("Member", "member@example.test");
        String memberToken = auth.login(target.username(), PASSWORD).accessToken();
        String[] suffixes = { "ban", "password", "email", "skin" };
        String[] bodies = { "{\"reason\":\"Prueba\"}",
                "{\"password\":\"replacement-password\"}",
                "{\"email\":\"new@example.test\"}", null };
        for (int index = 0; index < suffixes.length; index++) {
            String method = index == 3 ? "DELETE" : "PUT";
            String path = "/admin/users/" + target.id() + "/" + suffixes[index];
            assertEquals(401, request(method, path, bodies[index], null).statusCode());
            assertEquals(403, request(method, path, bodies[index],
                    memberToken).statusCode());
        }
        auth.changeRole(auth.authenticate(token), target.id(), Role.MODERATOR);
        for (int index = 0; index < suffixes.length; index++)
            assertEquals(403, request(index == 3 ? "DELETE" : "PUT",
                "/admin/users/" + target.id() + "/" + suffixes[index],
                bodies[index], memberToken).statusCode());
        assertEquals(409, request("PUT", "/admin/users/" + operator.id() + "/ban",
                "{\"reason\":\"Prueba\"}", token).statusCode());
        assertEquals(400, request("PUT", "/admin/users/" + target.id() + "/password",
                "{\"password\":\"short\"}", token).statusCode());
        assertEquals(400, request("PUT", "/admin/users/" + target.id() + "/email",
                "{\"email\":\"invalid\"}", token).statusCode());
        assertEquals(400, request("PUT", "/admin/users/" + target.id()
                + "/ban", "{}", token).statusCode());
    }

    @Test void unknownUserAndDuplicateEmailAreSafeErrorsAndDoNotChangeExistingAccount(
            ) throws Exception {
        User target = player("NoConflict", "no-conflict@example.test");
        assertEquals(409, request("PUT", "/admin/users/" + target.id() + "/email",
                "{\"email\":\"OPERATOR@example.test\"}", token).statusCode());
        assertEquals("no-conflict@example.test", client.search(host(),
                token, "NoConflict", 1, 25)
                .users().getFirst().email());
        var missing = request("PUT", "/admin/users/"
                + java.util.UUID.randomUUID() + "/password",
                "{\"password\":\"never-reflect-this-password\"}", token);
        assertEquals(404, missing.statusCode());
        assertFalse(missing.body().contains("never-reflect-this-password"));
        assertFalse(missing.body().contains("password_hash"));
    }

    private User player(String name, String email) {
        auth.register(name, email, PASSWORD);
        return auth.confirmEmail(mailer.verification);
    }

    @Test void orderingIsGlobalBeforePaginationAndRejectsUntrustedSortExpressions(
            ) throws Exception {
        User active=null;
        for (int i=10; i>=0; i--) {
            var user=player("Sort"+String.format("%02d", i),
                    String.format("e%02d@example.test", 10-i));
            if (i==3)active=user;
        }
        var ascending=client.search(host(), token, "Sort", 1, 10, "username", "asc");
        assertEquals("Sort00", ascending.users().getFirst().username());
        assertEquals("Sort09", ascending.users().getLast().username());
        assertEquals("Sort10", client.search(host(), token, "Sort", 2, 10,
                "username", "asc").users().getFirst().username());
        assertEquals("Sort10", client.search(host(), token, "Sort", 1, 10,
                "username", "desc").users().getFirst().username());
        assertEquals("Sort10", client.search(host(), token, "Sort", 1, 10,
                "email", "asc").users().getFirst().username());
        auth.login(active.username(), PASSWORD);
        assertEquals("Sort03", client.search(host(), token, "Sort", 1, 10,
                "lastSeenAt", "desc").users().getFirst().username());
        client.ban(host(), token, active.id(), "Motivo de prueba");
        assertEquals("Sort03", client.search(host(), token, "Sort", 2, 10,
                "status", "asc").users().getFirst().username());
        assertEquals(400, request("GET", "/admin/users?sort=username%3BDROP",
                null, token).statusCode());
        assertEquals(400, request("GET", "/admin/users?sort=email&direction=sideways",
                null, token).statusCode());
        assertEquals(11, client.search(host(), token, "Sort",
                1, 25, "createdAt", "asc").total());
    }

    private URI host() { return URI.create("http://127.0.0.1:" + server.port()); }

    private static byte[] customSkin() throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.drawImage(DefaultSkin.image(), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        image.setRGB(8, 8, 0xFF5CD79A);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }

    private HttpResponse<String> request(String method, String path,
            String body, String access) throws Exception {
        var builder = HttpRequest.newBuilder(host().resolve("/api/v1" + path));
        if (access != null) builder.header("Authorization", "Bearer " + access);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class CapturingMailer implements RecoveryMailer {
        private String verification;
        public void sendEmailVerification(String email, String name,
                String value, Instant expiry) {
            verification = value;
        }
        public void sendPasswordReset(String email, String name,
                String value, Instant expiry) { }
    }
}

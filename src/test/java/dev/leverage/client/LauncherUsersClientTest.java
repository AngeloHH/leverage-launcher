package dev.leverage.client;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LauncherUsersClientTest {
    @Test void datedBansNeverFallBackToPermanentOnOlderServers() throws Exception {
        status=404;
        var failure=assertThrows(LauncherAuthClient.AuthHttpException.class,
                ()->new LauncherUsersClient().ban(host, "token", UUID.randomUUID(),
                "Motivo", Instant.now().plusSeconds(60)));
        assertEquals(503, failure.status());
        assertEquals("BAN_EXPIRY_UNSUPPORTED", failure.code());
        assertEquals(1, requests.get());
        assertTrue(path.get().endsWith("/ban-until"));
    }
    private HttpServer server;
    private URI host;
    private String body;
    private int status;
    private byte[] texture;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        body =
        """
                {"users":[{"id":"00000000-0000-0000-0000-000000000001","username":"Test_\
                Player",
                "email":"test@example.test","role":"USER","status":"ACTIVE"}],
                "page":1,"pageSize":25,"total":1,"totalPages":1}
                """
        ;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/admin/users", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location",
                    host + "/redirect-target");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            catch (IOException ignored) {
                // Bounded client may cancel.
            }
        });
        server.createContext("/api/yggdrasil/textures/", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            method.set(exchange.getRequestMethod());
            if (status == 302) exchange.getResponseHeaders().set("Location",
                    host + "/redirect-target");
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(status, texture.length);
            try (var output = exchange.getResponseBody()) { output.write(texture); }
            catch (IOException ignored) {
                // Bounded client may cancel.
            }
        });
        server.createContext("/redirect-target", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void encodesSearchAndSendsCredentialsOnlyInHeaders() throws Exception {
        var result = new LauncherUsersClient().search(host,
                "private-users-token", "  a+b &_% ü  ", 1, 25);
        assertEquals("Bearer private-users-token", authorization.get());
        assertEquals("q=a%2Bb+%26_%25+%C3%BC&page=1&pageSize=25", query.get());
        assertTrue(!query.get().contains("private-users-token"));
        assertEquals(1, result.users().size());
        assertEquals("Test_Player", result.users().getFirst().username());
        assertEquals("test@example.test", result.users().getFirst().email());
        assertEquals("USER", result.users().getFirst().role());
        assertEquals("ACTIVE", result.users().getFirst().status());
        assertNull(result.users().getFirst().createdAt());
        assertNull(result.users().getFirst().lastSeenAt());
        assertNull(result.users().getFirst().skinHash());
        assertNull(result.users().getFirst().restrictionReason());
        assertFalse(result.users().getFirst().banned());
        assertEquals(25, result.pageSize());
        assertThrows(UnsupportedOperationException.class, () -> result.users().clear());
    }

    @Test void rejectsUntrustedHostShapesAndInvalidOptionsBeforeRequesting() {
        var client = new LauncherUsersClient();
        for (URI invalid : new URI[] { URI.create("file:///private"),
                URI.create("https://user:password@example.test"),
                URI.create("https://example.test/path"),
                    URI.create("https://example.test?token=x"),
                    URI.create("https://example.test#x") }) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.search(invalid, "token", "", 1, 25));
        }
        assertThrows(IllegalArgumentException.class,
                () -> client.search(host, "", "", 1, 25));
        assertThrows(IllegalArgumentException.class,
                () -> client.search(host, "token", "", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> client.search(host,
                "token", "x".repeat(201), 1, 25));
        assertEquals(0, requests.get());
    }

    @Test void failsClosedOnDeniedRequestsAndRedirectsWithoutReflectingServerBody() {
        var client = new LauncherUsersClient();
        body = "private-users-token";
        for (int error : new int[] { 401, 403, 302, 500 }) {
            status = error;
            var failure = assertThrows(LauncherAuthClient.AuthHttpException.class,
                    () -> client.search(host, "private-users-token", "", 1, 25));
            assertEquals(error, failure.status());
            assertTrue(!failure.getMessage().contains("private-users-token"));
        }
        assertEquals(4, requests.get());
    }

    @Test void rejectsMalformedAndOversizedPages() {
        var client = new LauncherUsersClient();
        String valid = body;
        for (String invalid : new String[] { "not json", "{}",
                valid.replace("\"page\":1", "\"page\":0"),
                valid.replace("\"total\":1", "\"total\":2"),
                    valid.replace("\"totalPages\":1", "\"totalPages\":8"),
                valid.replace("\"pageSize\":25", "\"pageSize\":100"),
                    "x".repeat(1024 * 1024 + 1) }) {
            body = invalid;
            assertThrows(IOException.class, () -> client.search(host,
                    "token", "", 1, 25));
        }
    }

    @Test void supportsEmptyResultsAndServerClampedPage() throws Exception {
        body = ("{\"users\":[],\"page\":1,\"pageSize\":25,\"total\":0,\"totalPages\":"
                + "1}");
        var page = new LauncherUsersClient().search(host, "token",
                "not-found", Integer.MAX_VALUE, 25);
        assertTrue(page.users().isEmpty());
        assertEquals(1, page.page());
        assertEquals(0, page.total());
    }

    @Test void readsAccountDatesRestrictionReasonAndCanonicalTextureReference(
            ) throws Exception {
        body = body.replace("\"status\":\"ACTIVE\"",
        """
                "status":"SUSPENDED","createdAt":"2026-01-02T03:04:05Z",
                "lastSeenAt":"2026-10-25T05:06:07Z","banned":true,
                "restrictionReason":"Incumplimiento de las reglas",
                "skinHash":"0123456789012345678901234567890123456789"
                """
        );
        var user = new LauncherUsersClient().search(host, "token",
                "", 1, 25).users().getFirst();
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), user.createdAt());
        assertEquals(Instant.parse("2026-10-25T05:06:07Z"), user.lastSeenAt());
        assertTrue(user.banned());
        assertEquals("Incumplimiento de las reglas", user.restrictionReason());
        assertEquals("0123456789012345678901234567890123456789", user.skinHash());
    }

    @Test void
            acceptsExplicitlyAbsentDatesButRejectsMalformedMetadataAndExternalTextureUrls(
            ) throws Exception {
        var client = new LauncherUsersClient();
        String valid = body;
        body = valid.replace("\"status\":\"ACTIVE\"",
                "\"status\":\"ACTIVE\",\"createdAt\":null,\"lastSeenAt\":null,\"skinH"
                + "ash\":null");
        assertNull(client.search(host, "token", "", 1,
                25).users().getFirst().lastSeenAt());
        for (String metadata : new String[] { "\"createdAt\":\"yesterday\"",
                "\"lastSeenAt\":42",
                "\"banned\":\"true\"", "\"restrictionReason\":{}",
                    "\"skinHash\":\"https://example.test/skin.png\"" }) {
            body = valid.replace("\"status\":\"ACTIVE\"",
                    "\"status\":\"ACTIVE\"," + metadata);
            assertThrows(IOException.class, () -> client.search(host,
                    "token", "", 1, 25));
        }
    }

    @Test void administrativeActionsUseExactMethodsPathsAndJsonBodies() throws Exception {
        var client = new LauncherUsersClient();
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        body = "{\"user\":" + JsonParser.parseString(body).getAsJsonObject(
                ).getAsJsonArray("users").get(0) + "}";
        client.ban(host, "secret-admin-token", id, "  Motivo con \"comillas\" y ñ  ");
        assertEquals("PUT", method.get());
        assertEquals("/api/v1/admin/users/" + id + "/ban", path.get());
        assertEquals("Motivo con \"comillas\" y ñ", JsonParser.parseString(
                requestBody.get()).getAsJsonObject().get("reason").getAsString());
        client.clearSkin(host, "secret-admin-token", id);
        assertEquals("DELETE", method.get());
        assertEquals("/api/v1/admin/users/" + id + "/skin", path.get());
        assertEquals("", requestBody.get());
        client.changePassword(host, "secret-admin-token", id, "Nuev@-contraseña-123");
        assertEquals("PUT", method.get());
        assertEquals("/api/v1/admin/users/" + id + "/password", path.get());
        assertEquals("Nuev@-contraseña-123", JsonParser.parseString(requestBody.get(
                )).getAsJsonObject().get("password").getAsString());
        client.changeEmail(host, "secret-admin-token", id, "  nuevo@example.test  ");
        assertEquals("PUT", method.get());
        assertEquals("/api/v1/admin/users/" + id + "/email", path.get());
        assertEquals("nuevo@example.test", JsonParser.parseString(requestBody.get(
                )).getAsJsonObject().get("email").getAsString());
        assertEquals("Bearer secret-admin-token", authorization.get());
        assertNull(query.get());
        assertFalse(requestBody.get().contains("secret-admin-token"));
        assertEquals(4, requests.get());
    }

    @Test void validatesActionArgumentsBeforeSendingAnyRequest() {
        var client = new LauncherUsersClient();
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> client.ban(host, "token", id, " "));
        assertThrows(IllegalArgumentException.class, () -> client.ban(host,
                "token", id, "x".repeat(1_001)));
        assertThrows(IllegalArgumentException.class, () -> client.ban(host,
                "token", id, "invalid\u0000reason"));
        assertThrows(IllegalArgumentException.class,
                () -> client.clearSkin(host, "", id));
        assertThrows(IllegalArgumentException.class,
                () -> client.clearSkin(host, "token", null));
        assertThrows(IllegalArgumentException.class, () -> client.clearSkin(
                URI.create("https://other.test/admin"), "token", id));
        for (String password : new String[] { "", "short", "x".repeat(129) })
            assertThrows(IllegalArgumentException.class,
                () -> client.changePassword(host, "token", id, password));
        for (String email : new String[] { "", "invalid", "user@bad",
                "user name@example.test", "x".repeat(250) + "@example.test" })
            assertThrows(IllegalArgumentException.class,
                () -> client.changeEmail(host, "token", id, email));
        assertEquals(0, requests.get());
    }

    @Test void actionErrorsAndRedirectsAreSanitizedAndMalformedSuccessFailsClosed() {
        var client = new LauncherUsersClient();
        UUID id = UUID.randomUUID();
        body = "private-password-and-token";
        for (int error : new int[] { 401, 403, 409, 429, 302, 500 }) {
            status = error;
            var failure = assertThrows(LauncherAuthClient.AuthHttpException.class,
                    () -> client.clearSkin(host, "token", id));
            assertEquals(error, failure.status());
            assertFalse(failure.toString().contains(body));
            assertNull(failure.getCause());
        }
        assertEquals(6, requests.get());
        status = 200;
        for (String invalid : new String[] { "not json", "{}", "{\"user\":null}",
                "{\"user\":{\"id\":\"" + UUID.randomUUID()
                    + ("\",\"username\":\"Test\",\"email\":\"test@example.test\",\"r"
                    + "ole\":\"USER\",\"status\":\"ACTIVE\"}}"),
                "x".repeat(1024 * 1024 + 1) }) {
            body = invalid;
            var failure = assertThrows(IOException.class,
                    () -> client.clearSkin(host, "token", id));
            assertNull(failure.getCause());
        }
    }

    @Test void downloadsOnlyPublicSameHostSkinsAndReturnsDefaultWithoutRequest(
            ) throws Exception {
        var client = new LauncherUsersClient();
        var fallback = client.loadSkin(host, null);
        assertEquals(64, fallback.getWidth());
        assertEquals(0, requests.get());
        for (int height : new int[] { 32, 64 }) {
            texture = imageBytes("PNG", 64, height);
            String hash = "a".repeat(height == 32 ? 40 : 64);
            var skin = client.loadSkin(host, hash);
            assertEquals(64, skin.getWidth());
            assertEquals(height, skin.getHeight());
            assertEquals("/api/yggdrasil/textures/" + hash, path.get());
            assertEquals("GET", method.get());
            assertNull(authorization.get());
            assertNull(query.get());
        }
        assertEquals(2, requests.get());
    }

    @Test void rejectsUntrustedTextureReferencesBeforeRequesting() {
        var client = new LauncherUsersClient();
        for (String hash : new String[] { "", "a".repeat(39),
                "A".repeat(40), "g".repeat(64), "../texture",
                "https://evil.example/skin.png", "a".repeat(40) + "?token=secret" })
            assertThrows(IllegalArgumentException.class,
                () -> client.loadSkin(host, hash));
        assertEquals(0, requests.get());
    }

    @Test void rejectsNonPngWrongDimensionsOversizedTexturesAndRedirects(
            ) throws Exception {
        var client = new LauncherUsersClient();
        String hash = "b".repeat(40);
        for (byte[] invalid : new byte[][] { "not png".getBytes(StandardCharsets.UTF_8),
                imageBytes("GIF", 64, 64),
                imageBytes("PNG", 128, 64), imageBytes("PNG", 64, 128),
                    new byte[2 * 1024 * 1024 + 1] }) {
            texture = invalid;
            assertThrows(IOException.class, () -> client.loadSkin(host, hash));
        }
        texture = imageBytes("PNG", 64, 64);
        for (int error : new int[] { 302, 404, 500 }) {
            status = error;
            assertThrows(IOException.class, () -> client.loadSkin(host, hash));
        }
        assertEquals(8, requests.get());
    }

    private static byte[] imageBytes(String format, int width,
            int height) throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB), format, bytes));
        return bytes.toByteArray();
    }
}

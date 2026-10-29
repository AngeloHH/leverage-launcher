package dev.leverage.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {
    @TempDir
    Path temporaryDirectory;

    private ApiServer server;
    private HttpClient client;
    private AuthService auth;
    private CapturingMailer mailer;

    @BeforeEach
    void setUp() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("api.db"));
        database.initialize();
        mailer = new CapturingMailer();
        auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void healthAndRegistrationAreJson() throws Exception {
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(uri("/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.headers().firstValue("Content-Type").orElse(
                "").startsWith("application/json"));
        assertTrue(health.body().contains("\"status\":\"ok\""));

        String body =
        """
                {"username":"ApiPlayer","email":"api@example.test","password":"valid-api\
                -password"}
                """
        ;
        HttpResponse<String> registration = client.send(
                HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registration.statusCode());
        assertTrue(registration.body().contains("PENDING_VERIFICATION"));
    }

    @Test
    void rejectsNonJsonBodies() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/auth/register"))
                .POST(HttpRequest.BodyPublishers.ofString("not json"))
                .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, response.statusCode());
        assertTrue(response.body().contains("JSON_REQUIRED"));
    }

    @Test
    void remembersDeviceRotatesTokenAndConsumesGameTicketOnce() throws Exception {
        auth.createBootstrapAdmin("Remembered", "remembered@example.test",
                "remembered-password");
        HttpResponse<String> login = post("/auth/login",
        """
                {"identifier":"Remembered","password":"remembered-password",
                 "rememberDevice":true,"deviceName":"PC de pruebas"}
                """
        , null);
        assertEquals(200, login.statusCode());
        JsonObject loginJson = JsonParser.parseString(login.body()).getAsJsonObject();
        String oldRefresh = loginJson.get("refreshToken").getAsString();

        HttpResponse<String> refresh = post("/auth/refresh",
                "{\"refreshToken\":\"" + oldRefresh + "\"}", null);
        assertEquals(200, refresh.statusCode());
        JsonObject refreshJson = JsonParser.parseString(refresh.body()).getAsJsonObject();
        String newRefresh = refreshJson.get("refreshToken").getAsString();
        String access = refreshJson.get("accessToken").getAsString();
        assertTrue(!oldRefresh.equals(newRefresh));

        HttpResponse<String> devices = client.send(
                HttpRequest.newBuilder(uri("/auth/devices"))
                .header("Authorization", "Bearer " + access).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, devices.statusCode());
        assertTrue(devices.body().contains("PC de pruebas"));

        HttpResponse<String> ticket = post("/game/tickets",
                "{\"server\":\"survival\"}", access);
        assertEquals(201, ticket.statusCode());
        String ticketValue = JsonParser.parseString(ticket.body()).getAsJsonObject()
                .get("ticket").getAsString();
        String consumeBody = "{\"ticket\":\"" + ticketValue
                + "\",\"server\":\"survival\"}";
        assertEquals(200, post("/game/tickets/consume", consumeBody, null).statusCode());
        assertEquals(401, post("/game/tickets/consume", consumeBody, null).statusCode());

        assertEquals(401, post("/auth/refresh",
                "{\"refreshToken\":\"" + oldRefresh + "\"}", null).statusCode());
        assertEquals(401, post("/auth/refresh",
                "{\"refreshToken\":\"" + newRefresh + "\"}", null).statusCode());

        HttpResponse<String> secondLogin = post("/auth/login",
        """
                {"identifier":"Remembered","password":"remembered-password",
                 "rememberDevice":true,"deviceName":"Segundo dispositivo"}
                """
        , null);
        JsonObject secondJson = JsonParser.parseString(
                secondLogin.body()).getAsJsonObject();
        String secondDeviceId = secondJson.get("deviceSessionId").getAsString();
        String secondRefresh = secondJson.get("refreshToken").getAsString();
        String secondAccess = secondJson.get("accessToken").getAsString();

        HttpResponse<String> revoked = client.send(HttpRequest.newBuilder(
                uri("/auth/devices/" + secondDeviceId))
                .header("Authorization", "Bearer " + secondAccess).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, revoked.statusCode());
        assertEquals(401, post("/auth/refresh",
                "{\"refreshToken\":\"" + secondRefresh + "\"}", null).statusCode());
    }

    @Test
    void filtersUsersBySuspensionWithoutASeparateResource() throws Exception {
        auth.createBootstrapAdmin("FilterAdmin", "filter-admin@example.test",
                "filter-admin-password");
        var player = auth.register("SuspendedPlayer",
                "suspended@example.test", "suspended-password");
        auth.confirmEmail(mailer.verificationToken);

        String access = JsonParser.parseString(post("/auth/login",
        """
                {"identifier":"FilterAdmin","password":"filter-admin-password"}
                """
        , null).body()).getAsJsonObject().get("accessToken").getAsString();
        auth.setSuspended(auth.authenticate(access), player.id(), true);

        HttpResponse<String> suspended = get("/admin/users?suspended=true", access);
        assertEquals(200, suspended.statusCode());
        assertTrue(suspended.body().contains("SuspendedPlayer"));
        assertTrue(!suspended.body().contains("FilterAdmin"));

        HttpResponse<String> active = get("/admin/users?suspended=false", access);
        assertEquals(200, active.statusCode());
        assertTrue(active.body().contains("FilterAdmin"));
        assertTrue(!active.body().contains("SuspendedPlayer"));

        HttpResponse<String> invalid = get("/admin/users?suspended=maybe", access);
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("INVALID_SUSPENDED"));
        assertEquals(404, get("/admin/mod-suspensions", access).statusCode());
    }

    @Test
    void changesEmailOnlyAfterConfirmingTheNewAddress() throws Exception {
        auth.register("EmailApi", "before@example.test", "email-api-password");
        auth.confirmEmail(mailer.verificationToken);
        String access = JsonParser.parseString(post("/auth/login",
        """
                {"identifier":"EmailApi","password":"email-api-password"}
                """
        , null).body()).getAsJsonObject().get("accessToken").getAsString();

        HttpResponse<String> requested = put("/me/email",
        """
                {"newEmail":"after@example.test","currentPassword":"email-api-password"}
                """
        , access);
        assertEquals(202, requested.statusCode());
        assertEquals("after@example.test", mailer.emailChangeRecipient);

        HttpResponse<String> confirmed = post("/auth/verify-email/confirm",
                "{\"token\":\"" + mailer.emailChangeToken + "\"}", null);
        assertEquals(200, confirmed.statusCode());
        assertTrue(confirmed.body().contains("after@example.test"));
        assertEquals(401, get("/me", access).statusCode());
    }

    @Test void adminUsersSupportsPagedSearchWithoutChangingLegacyResponses(
            ) throws Exception {
        auth.createBootstrapAdmin("SearchAdmin", "search-admin@example.test",
                "search-admin-password");
        auth.register("Search_Player", "literal-player@example.test",
                "search-player-password");
        auth.confirmEmail(mailer.verificationToken);
        String admin = auth.login("SearchAdmin", "search-admin-password").accessToken();
        String player = auth.login("Search_Player",
                "search-player-password").accessToken();

        var legacy = JsonParser.parseString(get("/admin/users",
                admin).body()).getAsJsonObject();
        assertEquals(2, legacy.getAsJsonArray("users").size());
        assertTrue(!legacy.has("page"));
        var response = get("/admin/users?q=search_player&page=999", admin);
        assertEquals(200, response.statusCode());
        var page = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(25, page.get("pageSize").getAsInt());
        assertEquals(1, page.get("page").getAsInt());
        assertEquals(1, page.get("total").getAsLong());
        assertEquals(1, page.get("totalPages").getAsInt());
        assertEquals("Search_Player", page.getAsJsonArray("users").get(
                0).getAsJsonObject().get("username").getAsString());
        assertTrue(!response.body().contains("password_hash"));
        assertTrue(!response.body().contains("search-player-password"));
        assertEquals(403, get("/admin/users?q=", player).statusCode());
        assertEquals(401, get("/admin/users?q=", "invalid-token").statusCode());
        for (String parameters : new String[] { "page=bad", "page=2147483648",
                "page=", "pageSize=15", "pageSize=", "pageSize=-1" }) {
            assertEquals(400, get("/admin/users?" + parameters,
                    admin).statusCode(), parameters);
        }
        var empty = JsonParser.parseString(get("/admin/users?q=%25",
                admin).body()).getAsJsonObject();
        assertEquals(0, empty.get("total").getAsLong());
        assertEquals(0, empty.getAsJsonArray("users").size());
    }

    @Test
    void emailCodeConfirmationIsRateLimited() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(400, post("/auth/verify-email/confirm",
                    "{\"email\":\"unknown@example.test\",\"code\":\"invalid\""
                    + "}", null).statusCode());
        }
        assertEquals(429, post("/auth/verify-email/confirm",
                "{\"email\":\"unknown@example.test\",\"code\":\"invalid\"}",
                null).statusCode());
    }

    @Test void editsTheAuthenticatedAccountsNameAndPasswordWithoutExposingHashes(
            ) throws Exception {
        var user=auth.createBootstrapAdmin("ProfileApi",
                "profile-api@example.test", "current-long-password");
        String token=auth.login("ProfileApi", "current-long-password",
                true, "PC").accessToken();
        assertEquals(401, put("/me/username",
                "{\"username\":\"NewName\",\"currentPassword\":\"current-long-passwor"
                + "d\"}", "bad-token").statusCode());
        assertEquals(403, put("/me/username",
                "{\"username\":\"NewName\",\"currentPassword\":\"wrong\"}",
                token).statusCode());
        assertEquals(400, put("/me/username",
                "{\"username\":\"bad name\",\"currentPassword\":\"current-long-passwo"
                + "rd\"}", token).statusCode());
        var changed=put("/me/username",
                "{\"username\":\"NewName\",\"currentPassword\":\"current-long-passwor"
                + "d\"}", token);
        assertEquals(200, changed.statusCode());
        assertTrue(changed.body().contains("NewName"));
        assertTrue(changed.body().contains(user.id().toString()));
        assertTrue(!changed.body().contains("password_hash"));
        assertTrue(!changed.body().contains("current-long-password"));
        assertEquals(200, put("/me/password",
                "{\"newPassword\":\"new-long-password\",\"currentPassword\":\"current"
                + "-long-password\"}", token).statusCode());
        assertEquals(401, get("/me", token).statusCode());
        assertEquals(200, post("/auth/login",
                "{\"identifier\":\"NewName\",\"password\":\"new-long-password\"}",
                null).statusCode());
    }

    private HttpResponse<String> post(String path, String body,
            String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body,
            String accessToken) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + "/api/v1" + path);
    }

    private static final class CapturingMailer implements RecoveryMailer {
        private String verificationToken;
        private String emailChangeToken;
        private String emailChangeRecipient;

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
            verificationToken = token;
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) {
        }

        @Override
        public void sendEmailChangeVerification(String email,
                String username, String token, Instant expiresAt) {
            emailChangeRecipient = email;
            emailChangeToken = token;
        }
    }
}

package dev.leverage.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.skin.TextureService;
import dev.leverage.skin.DefaultSkin;
import dev.leverage.skin.YggdrasilSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinApiServerTest {
    @TempDir
    Path temporaryDirectory;

    private ApiServer server;
    private HttpClient client;
    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("api.db"));
        database.initialize();
        RecoveryMailer noOpMailer = new RecoveryMailer() {
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
                noOpMailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("SkinAdmin", "skins@example.test",
                "valid-skin-password");
        TextureService textures = new TextureService(database,
                temporaryDirectory.resolve("textures"),
                temporaryDirectory.resolve("keys"), URI.create(
                "https://play.example.test"), Clock.systemUTC());
        YggdrasilSessionService sessions = new YggdrasilSessionService(
                database, auth, textures, Clock.systemUTC(), Duration.ofSeconds(30));
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC(),
                null, textures, sessions);
        server.start();
        client = HttpClient.newHttpClient();

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(api("/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                "{\"identifier\":\"SkinAdmin\",\"password\":\"valid-skin-pass"
                + "word\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        accessToken = JsonParser.parseString(login.body()).getAsJsonObject(
                ).get("accessToken").getAsString();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void launcherAppliesGallerySkinToTheAuthenticatedAccount() throws Exception {
        var entry=dev.leverage.client.LocalSkinLibrary.toggleModel(
                dev.leverage.client.LocalSkinLibrary.defaults().get(3));
        new dev.leverage.client.LauncherSkinClient().upload(URI.create("http://127.0.0.1:"
                +server.port()), accessToken, entry.image(), entry.slim());
        var response=client.send(HttpRequest.newBuilder(api(
                "/me/minecraft-profile")).header("Authorization", "Bearer "+accessToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var profile=JsonParser.parseString(response.body(
                )).getAsJsonObject().getAsJsonObject("profile");
        assertEquals("slim", profile.get("skinModel").getAsString());
        assertFalse(DefaultSkin.sha1().equals(profile.get("skinHash").getAsString()));
    }

    @Test
    void accountWithoutSkinReceivesSteveFromServerIncludingAfterClearingSkin(
            ) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> me = client.send(HttpRequest.newBuilder(
                    api("/me/minecraft-profile"))
                    .header("Authorization", "Bearer " + accessToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            JsonObject profile = JsonParser.parseString(me.body(
                    )).getAsJsonObject().getAsJsonObject("profile");
            assertEquals("classic", profile.get("skinModel").getAsString());
            assertTrue(profile.get("skinUrl").getAsString().endsWith(DefaultSkin.sha1()));
            HttpResponse<byte[]> texture = client.send(HttpRequest.newBuilder(
                    ygg("/textures/" + DefaultSkin.sha1()))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, texture.statusCode());
            org.junit.jupiter.api.Assertions.assertArrayEquals(DefaultSkin.png(),
                    texture.body());
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(
                    texture.body()));
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
            String id = profile.get("id").getAsString().replace("-", "");
            var sessionProfile = client.send(HttpRequest.newBuilder(ygg(
                    "/sessionserver/session/minecraft/profile/" + id))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject signed = JsonParser.parseString(
                    sessionProfile.body()).getAsJsonObject();
            String encoded = signed.getAsJsonArray("properties").get(
                    0).getAsJsonObject().get("value").getAsString();
            String decoded = new String(java.util.Base64.getDecoder().decode(encoded),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(decoded.contains(DefaultSkin.sha1()));
            if (attempt == 0) {
                assertEquals(200, putSkin(png(64, 64), accessToken).statusCode());
                assertEquals(200, client.send(HttpRequest.newBuilder(
                        api("/me/textures/skin"))
                        .header("Authorization", "Bearer "
                        + accessToken).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
    }

    @Test
    void uploadsSkinAndServesYggdrasilProfileAndPng() throws Exception {
        byte[] png = png(64, 64);
        HttpResponse<String> unauthorized = putSkin(png, null);
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> uploaded = putSkin(png, accessToken);
        assertEquals(200, uploaded.statusCode());
        JsonObject profile = JsonParser.parseString(uploaded.body(
                )).getAsJsonObject().getAsJsonObject("profile");
        assertEquals("slim", profile.get("skinModel").getAsString());
        String hash = profile.get("skinHash").getAsString();
        String compactId = profile.get("id").getAsString().replace("-", "");

        HttpResponse<String> metadata = client.send(
                HttpRequest.newBuilder(ygg("")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metadata.statusCode());
        assertTrue(metadata.body().contains("signaturePublickey"));
        assertEquals("/api/yggdrasil/", metadata.headers()
                .firstValue("X-Authlib-Injector-API-Location").orElseThrow());

        HttpResponse<String> signedProfile = client.send(HttpRequest.newBuilder(
                ygg("/sessionserver/session/minecraft/profile/"
                + compactId + "?unsigned=false"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, signedProfile.statusCode());
        assertTrue(signedProfile.body().contains("signature"));

        HttpResponse<String> unsignedProfile = client.send(HttpRequest.newBuilder(
                ygg("/sessionserver/session/minecraft/profile/"
                + compactId + "?unsigned=true"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(unsignedProfile.body().contains("signature"));

        HttpResponse<byte[]> texture = client.send(HttpRequest.newBuilder(
                ygg("/textures/" + hash)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, texture.statusCode());
        assertEquals("image/png", texture.headers().firstValue(
                "Content-Type").orElseThrow());
        assertTrue(texture.headers().firstValue("Cache-Control").orElse(
                "").contains("immutable"));
        assertTrue(texture.body().length > 0);
    }

    @Test
    void joinsWithoutIpAndStopsWorkingWhenAccessSessionIsRevoked() throws Exception {
        HttpResponse<String> me = client.send(HttpRequest.newBuilder(
                api("/me/minecraft-profile"))
                .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String profileId = JsonParser.parseString(me.body()).getAsJsonObject()
                .getAsJsonObject("profile").get("id").getAsString();
        String compact = profileId.replace("-", "");
        String challenge = "-4fd3a+session/challenge";

        HttpResponse<String> wrongProfile = postYgg(
                "/sessionserver/session/minecraft/join",
        """
                {"accessToken":"%s","selectedProfile":"00000000000000000000000000000000",
                 "serverId":"%s"}
                """
        .formatted(accessToken, challenge));
        assertEquals(403, wrongProfile.statusCode());
        assertTrue(wrongProfile.body().contains("ForbiddenOperationException"));

        HttpResponse<String> join = postYgg("/sessionserver/session/minecraft/join",
        """
                {"accessToken":"%s","selectedProfile":"%s","serverId":"%s"}
                """
        .formatted(accessToken, compact, challenge));
        assertEquals(204, join.statusCode());

        String query = (
                "/sessionserver/session/minecraft/hasJoined?username=SkinAdmin&server"
                + "Id=")
                + java.net.URLEncoder.encode(challenge,
                java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> joined = client.send(HttpRequest.newBuilder(
                ygg(query)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, joined.statusCode());
        assertTrue(joined.body().contains("properties"));

        HttpResponse<String> wrongName = client.send(HttpRequest.newBuilder(ygg(
                ("/sessionserver/session/minecraft/hasJoined?username=SomeoneE"
                + "lse&serverId=")
                + java.net.URLEncoder.encode(challenge,
                java.nio.charset.StandardCharsets.UTF_8)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, wrongName.statusCode());

        HttpResponse<String> logout = client.send(
                HttpRequest.newBuilder(api("/auth/logout"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logout.statusCode());
        HttpResponse<String> revoked = client.send(HttpRequest.newBuilder(
                ygg(query)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, revoked.statusCode());
    }

    private HttpResponse<String> putSkin(byte[] png, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                api("/me/textures/skin?model=slim"))
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(png));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postYgg(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(ygg(path)).header(
                "Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI api(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + "/api/v1" + path);
    }

    private URI ygg(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + "/api/yggdrasil" + path);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffabcdef);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
}

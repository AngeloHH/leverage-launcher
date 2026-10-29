package dev.leverage.http;

import com.google.gson.JsonParser;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.ModArtifactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModFileApiTest {
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
    void resourcepackUploadClassificationAndChecksReuseTheExistingServices(
            ) throws Exception {
        Database database = new Database(temporaryDirectory.resolve("packs.db"));
        database.initialize();
        RecoveryMailer mailer = new RecoveryMailer() {
            @Override public void sendEmailVerification(String e,
                    String u, String t, Instant x) { }
            @Override public void sendPasswordReset(String e,
                    String u, String t, Instant x) { }
        };
        AuthService auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("Admin", "admin@example.test", "admin-password-long");
        String access = auth.login("Admin", "admin-password-long").accessToken();
        ModArtifactService artifacts = new ModArtifactService(database,
                temporaryDirectory.resolve("files"), Clock.systemUTC());
        var policies = new dev.leverage.service.ModPolicyService(database,
                temporaryDirectory.resolve("unused.json"),
                Clock.systemUTC(), Duration.ofDays(30), artifacts);
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC(),
                null, null, null, policies, null, artifacts);
        server.start();
        URI host = URI.create("http://127.0.0.1:" + server.port());
        HttpClient client = HttpClient.newHttpClient();
        byte[] zip = jar("pack.mcmeta",
                "{\"pack\":{\"description\":\"Example\",\"pack_format\":46}}");
        String path = "/resourcepack/1.21.4/example/1.0/release";
        var upload = client.send(HttpRequest.newBuilder(host.resolve(
                path)).header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/zip").PUT(
                HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, upload.statusCode(), upload.body());
        var file = JsonParser.parseString(upload.body(
                )).getAsJsonObject().getAsJsonObject("file");
        assertEquals("resourcepack", file.get("type").getAsString());
        assertEquals("example-1.0.zip", file.get("fileName").getAsString());
        String sha1 = file.get("sha1").getAsString();
        var download = client.send(HttpRequest.newBuilder(host.resolve(
                path)).header("Authorization", "Bearer " + access)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals("application/zip", download.headers().firstValue(
                "Content-Type").orElseThrow());
        assertArrayEquals(zip, download.body());

        String verifyPath =
                "/api/v1/game/mods/verify?server=survival&version=1.21.4&loader=fabri"
                + "c";
        String payload = "[\"" + sha1 + "\"]";
        var unknown = client.send(HttpRequest.newBuilder(host.resolve(
                verifyPath)).header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, unknown.statusCode(), unknown.body());
        org.junit.jupiter.api.Assertions.assertTrue(JsonParser.parseString(
                unknown.body()).getAsJsonObject()
                .getAsJsonObject(sha1).get("allowed").isJsonNull());

        for (boolean allowed : new boolean[] { true, false }) {
            String decision = (
                    "{\"type\":\"resourcepack\",\"minecraftVersion\":\"1.21.4\",\"all"
                    + "owed\":") + allowed + "}";
            var rule = client.send(HttpRequest.newBuilder(host.resolve(
                    "/api/v1/admin/mod-policies/" + sha1))
                    .header("Authorization", "Bearer " + access).header("Content-Type",
                    "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(decision)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rule.statusCode(), rule.body());
            var check = client.send(HttpRequest.newBuilder(host.resolve(
                    verifyPath)).header("Authorization", "Bearer " + access)
                    .header("Content-Type", "application/json").POST(
                    HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(allowed ? 200 : 403, check.statusCode(), check.body());
            assertEquals(allowed, JsonParser.parseString(check.body()).getAsJsonObject(
                    ).getAsJsonObject(sha1).get("allowed").getAsBoolean());
        }
    }

    @Test
    void publishesDownloadsAndClassifiesAModThroughSeparatedApis() throws Exception {
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
        auth.createBootstrapAdmin("Admin", "admin@example.test", "admin-password-long");
        String access = auth.login("Admin", "admin-password-long").accessToken();
        ModArtifactService artifacts = new ModArtifactService(database,
                temporaryDirectory.resolve("mods/sha1"), Clock.systemUTC());
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC(), null, null, null,
                null, null, artifacts);
        server.start();
        URI host = URI.create("http://127.0.0.1:" + server.port());
        HttpClient client = HttpClient.newHttpClient();
        byte[] jar = jar("fabric.mod.json", "opaque metadata");

        HttpResponse<String> upload = client.send(HttpRequest.newBuilder(host.resolve(
                "/mod/1.21.4/fabric/sodium/0.6.5/release"))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/java-archive")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(jar)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, upload.statusCode(), upload.body());
        String sha1 = JsonParser.parseString(upload.body()).getAsJsonObject()
                .getAsJsonObject("file").get("sha1").getAsString();

        HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(host.resolve(
                "/mod/1.21.4/fabric/sodium/0.6.5/release"))
                .header("Authorization", "Bearer " + access).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertEquals(sha1, download.headers().firstValue("X-Content-SHA1").orElseThrow());
        assertArrayEquals(jar, download.body());

        String policyJson =
        """
                {"server":"survival","minecraftVersion":"1.21.4","loader":"fabric",
                 "allowed":true,"required":true,"reason":""}
                """
        ;
        HttpResponse<String> policy = client.send(HttpRequest.newBuilder(
                host.resolve("/api/v1/admin/mod-policies/" + sha1))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(policyJson)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, policy.statusCode(), policy.body());
        assertEquals(true, JsonParser.parseString(policy.body()).getAsJsonObject()
                .getAsJsonObject("policy").get("allowed").getAsBoolean());
    }

    private static byte[] jar(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

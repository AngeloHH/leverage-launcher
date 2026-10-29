package dev.leverage.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.db.Database;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCanonicalTextureBuildsSlimPropertyAndSignsIt() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("leverage.db"));
        database.initialize();
        User user = insertUser(database, "SkinPlayer");
        TextureService service = new TextureService(database,
                temporaryDirectory.resolve("textures"),
                temporaryDirectory.resolve("keys"), URI.create(
                "https://play.example.test"), Clock.systemUTC());
        AuthService.AuthenticatedUser actor = new AuthService.AuthenticatedUser(
                user, UUID.randomUUID().toString(), Set.of(Permission.ACCOUNT_USE));

        byte[] original = png(64, 64);
        TextureService.MinecraftProfile profile = service.upload(actor,
                TextureService.TextureType.SKIN,
                TextureService.SkinModel.SLIM, original);
        assertNotNull(profile.skinHash());
        assertEquals(40, profile.skinHash().length());
        assertEquals(TextureService.SkinModel.SLIM, profile.skinModel());
        assertNotNull(service.readTexture(profile.skinHash()));

        YggdrasilHttpHandler handler = new YggdrasilHttpHandler(service);
        JsonObject response = JsonParser.parseString(new com.google.gson.Gson()
                .toJson(handler.profileJson(profile, true))).getAsJsonObject();
        JsonObject property = response.getAsJsonArray(
                "properties").get(0).getAsJsonObject();
        String value = property.get("value").getAsString();
        String signature = property.get("signature").getAsString();
        JsonObject payload = JsonParser.parseString(new String(
                Base64.getDecoder().decode(value),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject skin = payload.getAsJsonObject("textures").getAsJsonObject("SKIN");
        assertEquals("slim", skin.getAsJsonObject("metadata").get("model").getAsString());
        assertTrue(skin.get("url").getAsString().endsWith(profile.skinHash()));
        assertTrue(verify(service.publicKeyPem(), value, signature));

        long storedFiles;
        try (var files = Files.walk(temporaryDirectory.resolve("textures"))) {
            storedFiles = files.filter(Files::isRegularFile).count();
        }
        service.upload(actor, TextureService.TextureType.SKIN,
                TextureService.SkinModel.CLASSIC, original);
        try (var files = Files.walk(temporaryDirectory.resolve("textures"))) {
            assertEquals(storedFiles, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectsWrongDimensionsAndClearsOnlyRequestedTexture() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("leverage.db"));
        database.initialize();
        User user = insertUser(database, "OtherPlayer");
        TextureService service = new TextureService(database,
                temporaryDirectory.resolve("textures"),
                temporaryDirectory.resolve("keys"), URI.create("http://127.0.0.1:8000"),
                Clock.systemUTC());
        AuthService.AuthenticatedUser actor = new AuthService.AuthenticatedUser(
                user, UUID.randomUUID().toString(), Set.of(Permission.ACCOUNT_USE));

        ServiceException invalid = assertThrows(ServiceException.class,
                () -> service.upload(actor,
                TextureService.TextureType.SKIN, TextureService.SkinModel.CLASSIC,
                png(32, 32)));
        assertEquals("INVALID_TEXTURE_DIMENSIONS", invalid.code());

        TextureService.MinecraftProfile withCape = service.upload(actor,
                TextureService.TextureType.CAPE,
                TextureService.SkinModel.CLASSIC, png(64, 32));
        assertNotNull(withCape.capeHash());
        TextureService.MinecraftProfile cleared = service.clear(actor,
                TextureService.TextureType.SKIN);
        assertNotNull(cleared.capeHash());
        assertEquals(null, cleared.skinHash());
    }

    private static User insertUser(Database database, String name) throws Exception {
        User user = new User(UUID.randomUUID(), name,
                name.toLowerCase() + "@example.test",
                Role.USER, UserStatus.ACTIVE, Instant.now(), Instant.now());
        try (var connection = database.open(); var statement
                = connection.prepareStatement(
        """
                INSERT INTO users(id, username, email, password_hash,
                    role, status, created_at, updated_at, password_changed_at)
                VALUES (?, ?, ?, 'unused', 'USER', 'ACTIVE', ?, ?, ?)
                """
        )) {
            statement.setString(1, user.id().toString());
            statement.setString(2, user.username());
            statement.setString(3, user.email());
            statement.setLong(4, user.createdAt().toEpochMilli());
            statement.setLong(5, user.updatedAt().toEpochMilli());
            statement.setLong(6, user.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
        return user;
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff12ab34);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }

    private static boolean verify(String pem, String value,
            String encodedSignature) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(publicKey);
        verifier.update(value.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }
}

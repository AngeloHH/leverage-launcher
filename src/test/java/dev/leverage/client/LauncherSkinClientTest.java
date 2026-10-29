package dev.leverage.client;

import com.sun.net.httpserver.HttpServer;
import dev.leverage.skin.DefaultSkin;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LauncherSkinClientTest {
    private HttpServer server;
    private URI host;
    private String url = "/skin.png";
    private String model = "slim";
    private int profileStatus = 200;
    private byte[] image = DefaultSkin.png();
    private final AtomicReference<String> profileAuthorization = new AtomicReference<>();
    private final AtomicReference<String> textureAuthorization = new AtomicReference<>();
    private final AtomicInteger imageRequests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/me/minecraft-profile", exchange -> {
            profileAuthorization.set(exchange.getRequestHeaders(
                    ).getFirst("Authorization"));
            byte[] body = ("{\"profile\":{\"skinUrl\":" + (url == null
                    ? "null" : "\"" + url + "\"")
                    + ",\"skinModel\":\"" + model + "\"}}").getBytes(
                    StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(profileStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/skin.png", exchange -> {
            imageRequests.incrementAndGet();
            textureAuthorization.set(exchange.getRequestHeaders(
                    ).getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            try (var output = exchange.getResponseBody()) { output.write(image); }
            catch (IOException ignored) {
                // Client may cancel an oversized response.
            }
        });
        server.start();
        host = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void loadsSlimSkinWithoutSendingSessionTokenToTextureUrl() throws Exception {
        var skin = new LauncherSkinClient().load(host, "private-access-token");
        assertTrue(skin.slim());
        assertEquals(64, skin.image().getWidth());
        assertEquals(DefaultSkin.image().getRGB(8, 8), skin.image().getRGB(8, 8));
        assertEquals("Bearer private-access-token", profileAuthorization.get());
        assertNull(textureAuthorization.get());
        assertEquals(1, imageRequests.get());
    }

    @Test void retainsCompatibilityWithOlderServersThatReturnNullSkinUrl(
            ) throws Exception {
        url = null;
        var skin = new LauncherSkinClient().load(host, "token");
        assertFalse(skin.slim());
        assertEquals(DefaultSkin.image().getRGB(8, 8), skin.image().getRGB(8, 8));
        assertEquals(0, imageRequests.get());
    }

    @Test void legacySkinsUseClassicArmsEvenIfServerSaysSlim() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(DefaultSkin.image().getSubimage(0, 0, 64, 32), "PNG", output);
        image = output.toByteArray();
        var skin = new LauncherSkinClient().load(host, "token");
        assertFalse(skin.slim());
        assertEquals(32, skin.image().getHeight());
    }

    @Test void rejectsNonHttpTextureLocationsAndUnauthorizedProfiles() {
        url = "file:///C:/private.png";
        assertThrows(IOException.class, () -> new LauncherSkinClient(
                ).load(host, "token"));
        assertEquals(0, imageRequests.get());
        url = "/skin.png";
        profileStatus = 401;
        assertThrows(IOException.class, () -> new LauncherSkinClient(
                ).load(host, "token"));
        assertEquals(0, imageRequests.get());
    }

    @Test void rejectsOversizedDownloadsAndInvalidDimensions() throws Exception {
        image = new byte[2 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> new LauncherSkinClient(
                ).load(host, "token"));
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(128, 128,
                BufferedImage.TYPE_INT_ARGB), "PNG", output);
        image = output.toByteArray();
        assertThrows(IOException.class, () -> new LauncherSkinClient(
                ).load(host, "token"));
    }

    @Test void uploadsPngAndModelOnlyToTheAuthenticatedLeverageEndpoint(
            ) throws Exception {
        AtomicReference<String> authorization=new AtomicReference<>(),
                query=new AtomicReference<>(), method=new AtomicReference<>();
        AtomicReference<byte[]> uploaded=new AtomicReference<>();
        server.createContext("/api/v1/me/textures/skin", exchange-> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            method.set(exchange.getRequestMethod());
            uploaded.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        var entry=LocalSkinLibrary.toggleModel(LocalSkinLibrary.defaults().get(3));
        new LauncherSkinClient().upload(host, "private-token", entry.image(), true);
        assertEquals("Bearer private-token", authorization.get());
        assertEquals("model=slim", query.get());
        assertEquals("PUT", method.get());
        assertEquals(64, ImageIO.read(new java.io.ByteArrayInputStream(
                uploaded.get())).getWidth());
        assertEquals(0, imageRequests.get());
    }

    @Test void failedUploadsDoNotPretendToSucceedOrFollowRedirects() {
        server.createContext("/api/v1/me/textures/skin", exchange-> {
            exchange.getResponseHeaders().set("Location",
                    host.resolve("/skin.png").toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assertThrows(IOException.class, ()->new LauncherSkinClient().upload(host,
                "private-token", DefaultSkin.image(), false));
        assertEquals(0, imageRequests.get());
        assertThrows(IOException.class, ()->new LauncherSkinClient().upload(host,
                "token", DefaultSkin.image().getSubimage(0, 0, 64, 32), true));
    }
}

package dev.leverage.launch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibInjectorManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsOfficialAgentFromLeverageWithoutNetwork() throws Exception {
        Path destination = temporaryDirectory.resolve("embedded/authlib-injector.jar");
        AuthlibInjectorManager manager = new AuthlibInjectorManager(destination,
                URI.create("http://127.0.0.1:1/this-must-not-be-requested.jar"),
                AuthlibInjectorManager.OFFICIAL_SHA256);

        assertEquals(destination.toAbsolutePath(), manager.ensureInstalled());
        assertEquals(AuthlibInjectorManager.OFFICIAL_SHA256,
                AuthlibInjectorManager.sha256(destination));
        assertTrue(Files.size(destination) > 300_000);
    }

    @Test
    void downloadsVerifiesRepairsAndBuildsArgumentsForBothSides() throws Exception {
        byte[] jar = "a verified fake java agent".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance(
                "SHA-256").digest(jar));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent.jar", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jar.length);
            exchange.getResponseBody().write(jar);
            exchange.close();
        });
        server.start();
        try {
            Path destination = temporaryDirectory.resolve("tools/authlib-injector.jar");
            URI source = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/agent.jar");
            AuthlibInjectorManager manager = new AuthlibInjectorManager(
                    destination, source, sha256);
            assertEquals(destination.toAbsolutePath(), manager.ensureInstalled());
            assertArrayEquals(jar, Files.readAllBytes(destination));

            Files.writeString(destination, "corrupt");
            manager.ensureInstalled();
            assertArrayEquals(jar, Files.readAllBytes(destination));

            String metadata = "{\"skinDomains\":[\"example.test\"]}";
            var clientArgs = manager.jvmArguments(URI.create(
                    "https://play.example.test"), metadata,
                    AuthlibInjectorManager.Side.CLIENT);
            assertTrue(clientArgs.get(0).startsWith("-javaagent:"
                    + destination.toAbsolutePath()));
            assertTrue(clientArgs.get(0).endsWith(
                    "=https://play.example.test/api/yggdrasil/"));
            String encoded = clientArgs.get(1).substring(
                    clientArgs.get(1).indexOf('=') + 1);
            assertEquals(metadata, new String(Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8));
            assertEquals(2, clientArgs.size());

            var serverArgs = manager.jvmArguments(URI.create(
                    "http://127.0.0.1:8000/"), metadata,
                    AuthlibInjectorManager.Side.SERVER);
            assertTrue(serverArgs.get(0).endsWith(
                    "=http://127.0.0.1:8000/api/yggdrasil/"));
            assertEquals(2, serverArgs.size());
        } finally {
            server.stop(0);
        }
    }
}

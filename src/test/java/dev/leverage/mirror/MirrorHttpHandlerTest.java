package dev.leverage.mirror;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorHttpHandlerTest {
    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void servesVirtualRoutesAndNeverExposesHashStorage() throws Exception {
        MirrorRepository repository = new MirrorRepository(
                temporaryDirectory.resolve("mirror"));
        Path source = temporaryDirectory.resolve("source.jar");
        Files.writeString(source, "0123456789");
        String hash = MirrorRepository.sha1(source);
        Path blob = repository.blobPath(hash);
        Files.createDirectories(blob.getParent());
        Files.copy(source, blob);
        repository.registerRoute("/mirror/libraries/example.jar", hash, Files.size(blob),
                "application/java-archive", "example.jar",
                "https://example.test/example.jar");
        Files.writeString(repository.versionsDirectory().resolve("version_manifest.json"),
                "{\"versions\":[]}");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MirrorHttpHandler handler = new MirrorHttpHandler(repository);
        server.createContext("/mirror", handler);
        server.createContext("/game", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> complete = client.send(
                HttpRequest.newBuilder(URI.create(origin
                + "/mirror/libraries/example.jar")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, complete.statusCode());
        assertEquals("0123456789", complete.body());

        HttpResponse<String> partial = client.send(
                HttpRequest.newBuilder(URI.create(origin
                + "/mirror/libraries/example.jar"))
                .header("Range", "bytes=2-5").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(206, partial.statusCode());
        assertEquals("2345", partial.body());

        HttpResponse<String> hidden = client.send(
                HttpRequest.newBuilder(URI.create(origin
                + "/mirror/storage/sha1/" + hash)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, hidden.statusCode());

        HttpResponse<String> classicManifest = client.send(
                HttpRequest.newBuilder(URI.create(origin
                + "/game/version_manifest.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, classicManifest.statusCode());
        assertEquals("{\"versions\":[]}", classicManifest.body());
    }
}

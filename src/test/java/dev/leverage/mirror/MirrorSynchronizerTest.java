package dev.leverage.mirror;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorSynchronizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void downloadsBySha1AndWritesRewrittenManifests() throws Exception {
        byte[] client = "fake-client-jar".getBytes(StandardCharsets.UTF_8);
        byte[] library = "fake-library".getBytes(StandardCharsets.UTF_8);
        byte[] asset = "fake-asset".getBytes(StandardCharsets.UTF_8);
        byte[] javaExecutable = "fake-java-executable".getBytes(StandardCharsets.UTF_8);
        String clientHash = sha1(client);
        String libraryHash = sha1(library);
        String assetHash = sha1(asset);
        String javaHash = sha1(javaExecutable);

        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();

        String assetIndex =
        """
                {"objects":{"minecraft/lang/es_es.json":{"hash":"%s","size":%d}}}
                """
        .formatted(assetHash, asset.length);
        String assetIndexHash = sha1(assetIndex.getBytes(StandardCharsets.UTF_8));
        String version =
        """
                {
                  "id":"test-version",
                  "type":"release",
                  "downloads":{"client":{"sha1":"%s","size":%d,"url":"%s/client.jar"}},
                  "libraries":[{"downloads":{"artifact":{"path":"example/test/1/test-1.j\
                ar","sha1":"%s","size":%d,"url":"%s/library.jar"}}}],
                  "assetIndex":{"id":"test-assets","sha1":"%s","size":%d,"url":"%s/asset\
                s.json"},
                  "javaVersion":{"component":"test-java","majorVersion":25}
                }
                """
        .formatted(clientHash, client.length, origin, libraryHash, library.length, origin,
                assetIndexHash, assetIndex.getBytes(StandardCharsets.UTF_8).length,
                origin);
        String versionHash = sha1(version.getBytes(StandardCharsets.UTF_8));
        String global =
        """
                {"versions":[{"id":"test-version","type":"release","sha1":"%s",
                "url":"%s/version.json","time":"2026-01-02T00:00:00+00:00",
                "releaseTime":"2026-01-01T00:00:00+00:00","complianceLevel":1}]}
                """
        .formatted(versionHash, origin);
        String javaManifest =
        """
                {"files":{
                  "bin":{"type":"directory"},
                  "bin/java.exe":{"type":"file","executable":true,"downloads":{
                    "raw":{"sha1":"%s","size":%d,"url":"%s/java.exe"},
                    "lzma":{"sha1":"0000000000000000000000000000000000000000","size":1,"\
                url":"%s/java.exe.lzma"}
                  }},
                  "legal/LICENSE":{"type":"link","target":"../java.base/LICENSE"}
                }}
                """
        .formatted(javaHash, javaExecutable.length, origin, origin);
        String javaManifestHash = sha1(javaManifest.getBytes(StandardCharsets.UTF_8));
        String javaRuntimes =
        """
                {"windows-x64":{"test-java":[{
                  "manifest":{"sha1":"%s","size":%d,"url":"%s/java-manifest.json"},
                  "version":{"name":"25-test","released":"2026-01-01T00:00:00+00:00"}
                }]}}
                """
        .formatted(javaManifestHash, javaManifest.getBytes(
                StandardCharsets.UTF_8).length, origin);

        route(upstream, "/manifest.json", global.getBytes(StandardCharsets.UTF_8),
                "application/json");
        route(upstream, "/version.json", version.getBytes(StandardCharsets.UTF_8),
                "application/json");
        AtomicInteger clientDownloads = new AtomicInteger();
        upstream.createContext("/client.jar", exchange -> {
            clientDownloads.incrementAndGet();
            respond(exchange, client, "application/java-archive");
        });
        route(upstream, "/library.jar", library, "application/java-archive");
        route(upstream, "/assets.json", assetIndex.getBytes(
                StandardCharsets.UTF_8), "application/json");
        route(upstream, "/" + assetHash.substring(0, 2) + "/" + assetHash,
                asset, "application/octet-stream");
        route(upstream, "/java-runtimes.json", javaRuntimes.getBytes(
                StandardCharsets.UTF_8), "application/json");
        route(upstream, "/java-manifest.json", javaManifest.getBytes(
                StandardCharsets.UTF_8), "application/json");
        route(upstream, "/java.exe", javaExecutable, "application/octet-stream");
        upstream.start();

        try {
            MirrorConfig config = new MirrorConfig(temporaryDirectory.resolve("mirror"),
                    List.of(new MirrorConfig.VersionSpec("test-version",
                    true, false, true)),
                    List.of("windows-x64"), new MirrorConfig.JavaSpec(true, List.of()));
            MirrorRepository repository = new MirrorRepository(config.output());
            MirrorSynchronizer synchronizer = new MirrorSynchronizer(config, repository,
                    URI.create("http://127.0.0.1:9000"),
                    URI.create(origin + "/manifest.json"),
                    URI.create(origin), URI.create(origin + "/java-runtimes.json"));

            MirrorSynchronizer.SyncResult result = synchronizer.synchronize();
            assertEquals(1, result.versions());
            assertEquals(1, result.javaRuntimes());
            assertTrue(result.downloadedFiles() >= 5);
            assertTrue(repository.isValidBlob(clientHash, client.length));
            assertNotNull(repository.resolve("/mirror/versions/test-version/client.jar"));
            assertNotNull(repository.resolve(
                    "/mirror/libraries/example/test/1/test-1.jar"));
            assertNotNull(repository.resolve(
                    "/mirror/assets/test-assets/minecraft/lang/es_es.json"));
            assertNotNull(repository.resolve(
                    "/mirror/java/windows-x64/test-java/files/bin/java.exe"));
            assertNotNull(repository.resolveManifest(
                    "/mirror/versions/java/windows-x64/test-java/manifest.jso" + "n"));

            String generated = Files.readString(repository.versionsDirectory()
                    .resolve("test-version").resolve("version.json"));
            var json = JsonParser.parseString(generated).getAsJsonObject();
            Path mirroredIndex = repository.versionsDirectory(
                    ).resolve("test-version/assets.json");
            assertEquals(assetIndex, Files.readString(mirroredIndex));
            assertEquals(json.getAsJsonObject("assetIndex").get("sha1").getAsString(),
                    MirrorRepository.sha1(mirroredIndex));
            assertEquals(json.getAsJsonObject("assetIndex").get("size").getAsLong(),
                    Files.size(mirroredIndex));
            assertEquals("http://127.0.0.1:9000/mirror/versions/test-version/client.jar",
                    json.getAsJsonObject("downloads").getAsJsonObject(
                    "client").get("url").getAsString());
            assertEquals("http://127.0.0.1:9000/mirror/assets/test-assets/",
                    json.getAsJsonObject("leverage").get("assetBaseUrl").getAsString());
            assertEquals(
                    "http://127.0.0.1:9000/mirror/versions/java/windows-x64/test-java"
                    + "/manifest.json",
                    json.getAsJsonObject("leverage").getAsJsonObject(
                    "javaRuntimeManifests")
                    .get("windows-x64").getAsString());
            assertFalse(generated.contains(origin + "/client.jar"));

            String generatedJava = Files.readString(
                    repository.versionsDirectory().resolve("java")
                    .resolve("windows-x64").resolve("test-java").resolve(
                    "manifest.json"));
            var javaJson = JsonParser.parseString(generatedJava).getAsJsonObject();
            JsonObject javaDownloads = javaJson.getAsJsonObject("files")
                    .getAsJsonObject("bin/java.exe").getAsJsonObject("downloads");
            assertEquals(
                    "http://127.0.0.1:9000/mirror/java/windows-x64/test-java/files/bi"
                    + "n/java.exe",
                    javaDownloads.getAsJsonObject("raw").get("url").getAsString());
            assertFalse(javaDownloads.has("lzma"));
            assertEquals("../java.base/LICENSE", javaJson.getAsJsonObject("files")
                    .getAsJsonObject("legal/LICENSE").get("target").getAsString());

            var index = JsonParser.parseString(Files.readString(
                    repository.versionsDirectory()
                    .resolve("index.json"))).getAsJsonObject();
            assertEquals(1, index.getAsJsonArray("javaRuntimes").size());

            var classicManifest = JsonParser.parseString(Files.readString(
                    repository.versionsDirectory()
                    .resolve("version_manifest.json"))).getAsJsonObject();
            JsonObject classicVersion = classicManifest.getAsJsonArray(
                    "versions").get(0).getAsJsonObject();
            assertEquals("test-version", classicManifest.getAsJsonObject("latest")
                    .get("release").getAsString());
            assertFalse(classicVersion.has("sha1"));
            assertFalse(classicVersion.has("complianceLevel"));

            var modernManifest = JsonParser.parseString(Files.readString(
                    repository.versionsDirectory()
                    .resolve("version_manifest_v2.json"))).getAsJsonObject();
            JsonObject modernVersion = modernManifest.getAsJsonArray(
                    "versions").get(0).getAsJsonObject();
            assertEquals(1, modernVersion.get("complianceLevel").getAsInt());
            assertEquals(MirrorRepository.sha1(repository.versionsDirectory()
                    .resolve("test-version").resolve("version.json")),
                    modernVersion.get("sha1").getAsString());
            assertEquals(
                    "http://127.0.0.1:9000/mirror/versions/test-version/version.json",
                    modernVersion.get("url").getAsString());

            Files.writeString(repository.blobPath(clientHash), "corrupted");
            synchronizer.synchronize();
            assertTrue(repository.isValidBlob(clientHash, client.length));
            assertEquals(2, clientDownloads.get());
        } finally {
            upstream.stop(0);
        }
    }

    private static void route(HttpServer server, String path,
            byte[] body, String contentType) {
        server.createContext(path, exchange -> respond(exchange, body, contentType));
    }

    private static void respond(HttpExchange exchange, byte[] body,
            String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String sha1(byte[] value) throws IOException {
        Path temporary = Files.createTempFile("leverage-hash", ".tmp");
        try {
            Files.write(temporary, value);
            return MirrorRepository.sha1(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

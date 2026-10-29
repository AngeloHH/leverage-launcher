package dev.leverage.client;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GameInstallerTest {
    @TempDir Path temporary;
    HttpServer server;
    String base;
    AtomicInteger artifactRequests = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void installsMirrorClientLibrariesAssetsAndReusesVerifiedFiles(
            ) throws Exception {
        byte[] client = "client".getBytes(), library = "library".getBytes(),
                asset = "asset".getBytes(), log = "log".getBytes();
        JsonObject version = version(client);
        JsonObject lib = new JsonObject();
        lib.addProperty("name", "test:library:1");
        JsonObject libDownloads = new JsonObject(),
                artifact = descriptor("/mirror/lib.jar", library);
        artifact.addProperty("path", "test/library/1/library-1.jar");
        libDownloads.add("artifact", artifact);
        lib.add("downloads", libDownloads);
        JsonArray libs = new JsonArray();
        libs.add(lib);
        version.add("libraries", libs);
        JsonObject index = new JsonObject(), objects = new JsonObject(),
                object = new JsonObject();
        object.addProperty("hash", hash(asset));
        object.addProperty("size", asset.length);
        objects.add("minecraft/lang/es es.json", object);
        index.add("objects", objects);
        byte[] indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject assetIndex = descriptor("/mirror/index.json", indexBytes);
        assetIndex.addProperty("id", "17");
        version.add("assetIndex", assetIndex);
        JsonObject leverage = new JsonObject();
        leverage.addProperty("assetBaseUrl", base + "/mirror/assets/17/");
        version.add("leverage", leverage);
        JsonObject logging = new JsonObject(), clientLogging = new JsonObject(),
                logFile = descriptor("/mirror/log.xml", log);
        logFile.addProperty("id", "test.xml");
        clientLogging.add("file", logFile);
        logging.add("client", clientLogging);
        version.add("logging", logging);
        route("/mirror/client.jar", client);
        route("/mirror/lib.jar", library);
        route("/mirror/index.json", indexBytes);
        route("/mirror/assets/17/minecraft/lang/es es.json", asset);
        route("/mirror/log.xml", log);
        catalog(version);
        LauncherPreferences.DownloadSource mirror
                = LauncherPreferences.DownloadSource.external(URI.create(base));
        GameInstaller installer = new GameInstaller(URI.create(base
                + "/must-not-request-official"));
        var sources = new LauncherPreferences.DownloadSources(mirror,
                LauncherPreferences.DownloadSource.defaults());
        assertEquals("test", installer.catalog(sources).getFirst().id());
        var installed = installer.install("test", temporary, sources,
                ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(21, installed.javaMajor());
        assertEquals("java-runtime-delta", installed.javaComponent());
        assertArrayEquals(client, Files.readAllBytes(temporary.resolve(
                "versions/test/test.jar")));
        assertArrayEquals(library, Files.readAllBytes(temporary.resolve(
                "libraries/test/library/1/library-1.jar")));
        assertArrayEquals(asset, Files.readAllBytes(temporary.resolve("assets/objects/"
                + hash(asset).substring(0, 2) + "/" + hash(asset))));
        int before = artifactRequests.get();
        installer.install("test", temporary, sources,
                ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(before, artifactRequests.get(),
                "valid cached artifacts are not downloaded again");
        Files.writeString(temporary.resolve("versions/test/test.jar"), "broken");
        installer.install("test", temporary, sources,
                ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(before + 1, artifactRequests.get());
    }

    @Test void badHashDoesNotReplaceExistingFileAndRemovesPartialDownload(
            ) throws Exception {
        byte[] wanted = "wanted".getBytes();
        JsonObject descriptor = descriptor("/bad.jar", wanted);
        route("/bad.jar", "wrong!".getBytes());
        Path destination = temporary.resolve("existing.jar");
        Files.writeString(destination, "old");
        assertThrows(java.io.IOException.class, () -> new ArtifactDownloads().ensure(
                descriptor, destination, ProgressListener.NONE, CancellationToken.NONE));
        assertEquals("old", Files.readString(destination));
        try (var files = Files.list(temporary)) {
            assertEquals(List.of("existing.jar"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void preCancelledInstallNeverContactsNetwork() {
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        var defaults = LauncherPreferences.DownloadSource.defaults();
        assertThrows(java.io.InterruptedIOException.class,
                () -> new GameInstaller(URI.create(base + "/catalog"))
                .install("test", temporary, new LauncherPreferences.DownloadSources(
                defaults, defaults), ProgressListener.NONE, cancellation));
        assertEquals(0, artifactRequests.get());
    }

    @Test void rejectsTraversalAndInvalidHashBeforeNetwork() throws Exception {
        assertThrows(java.io.IOException.class,
                () -> ArtifactDownloads.safeResolve(temporary, "../escape"));
        assertThrows(java.io.IOException.class,
                () -> ArtifactDownloads.safeResolve(temporary, "C:/escape"));
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("sha1", "invalid");
        descriptor.addProperty("url", base + "/file");
        assertThrows(java.io.IOException.class, () -> new ArtifactDownloads(
                ).ensure(descriptor, temporary.resolve("x"),
                ProgressListener.NONE, CancellationToken.NONE));
    }

    @Test void mergesInheritanceWithoutLosingArgumentsOrReplacingParentLibraries() {
        JsonObject parent = JsonParser.parseString(
                "{\"arguments\":{\"game\":[\"a\"],\"jvm\":[\"j\"]},\"libraries\":[{\""
                + "name\":\"a\"},{\"name\":\"b\"}]}").getAsJsonObject();
        JsonObject child = JsonParser.parseString(
                "{\"arguments\":{\"game\":[\"b\"]},\"libraries\":[{\"name\":\"b\",\"n"
                + "ew\":true}]} ").getAsJsonObject();
        JsonObject merged = GameInstaller.merge(parent, child);
        assertEquals(2, merged.getAsJsonObject(
                "arguments").getAsJsonArray("game").size());
        assertEquals(1, merged.getAsJsonObject("arguments").getAsJsonArray("jvm").size());
        assertEquals(2, merged.getAsJsonArray("libraries").size());
        assertTrue(merged.getAsJsonArray("libraries").get(
                1).getAsJsonObject().get("new").getAsBoolean());
        assertFalse(parent.getAsJsonArray("libraries").get(
                1).getAsJsonObject().has("new"));
    }

    @Test void materializesEveryLegacyAliasAndRepairsOnlyDamagedNamedCopies(
            ) throws Exception {
        byte[] client = "legacy-client".getBytes(), asset = "legacy-sound".getBytes();
        JsonObject version = version(client);
        JsonObject index = new JsonObject(), objects = new JsonObject(),
                object = new JsonObject();
        object.addProperty("hash", hash(asset));
        object.addProperty("size", asset.length);
        objects.add("sound/random/click.ogg", object);
        objects.add("sound/random/alias.ogg", object.deepCopy());
        index.add("objects", objects);
        index.addProperty("virtual", true);
        index.addProperty("map_to_resources", true);
        byte[] indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject assetIndex = descriptor("/index.json", indexBytes);
        assetIndex.addProperty("id", "legacy");
        version.add("assetIndex", assetIndex);
        JsonObject leverage = new JsonObject();
        leverage.addProperty("assetBaseUrl", base + "/assets/");
        version.add("leverage", leverage);
        route("/mirror/client.jar", client);
        route("/index.json", indexBytes);
        route("/assets/sound/random/click.ogg", asset);
        catalog(version);
        var sources = mirrorSources();
        GameInstaller installer = new GameInstaller();
        installer.install("test", temporary, sources,
                ProgressListener.NONE, CancellationToken.NONE);
        for (String target : List.of("assets/virtual/legacy/sound/random/click.ogg",
                "assets/virtual/legacy/sound/random/alias.ogg",
                "resources/sound/random/click.ogg", "resources/sound/random/alias.ogg"))
            assertArrayEquals(asset, Files.readAllBytes(temporary.resolve(target)));
        int before = artifactRequests.get();
        Files.writeString(temporary.resolve(
                "assets/virtual/legacy/sound/random/click.ogg"), "corrupt");
        installer.install("test", temporary, sources,
                ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(before, artifactRequests.get(),
                "materializing names must reuse verified objects");
        assertArrayEquals(asset, Files.readAllBytes(temporary.resolve(
                "assets/virtual/legacy/sound/random/click.ogg")));
    }

    @Test void rejectsTraversalLogicalResourceBeforeDownloadingAnyObjects(
            ) throws Exception {
        byte[] client = "legacy-client".getBytes();
        JsonObject version = version(client);
        JsonObject index = JsonParser.parseString(
                "{\"virtual\":true,\"objects\":{\"../outside\":{\"hash\":\"0000000000"
                + "000000000000000000000000000000\",\"size\":1}}}").getAsJsonObject();
        byte[] indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject assetIndex = descriptor("/index.json", indexBytes);
        assetIndex.addProperty("id", "legacy");
        version.add("assetIndex", assetIndex);
        route("/mirror/client.jar", client);
        route("/index.json", indexBytes);
        catalog(version);
        assertThrows(java.io.IOException.class, () -> new GameInstaller(
                ).install("test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE));
        assertFalse(Files.exists(temporary.resolve("assets/objects")));
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test void enrichesOldForgeProfileWithOfficialChecksumsAndKeepsVanillaClient(
            ) throws Exception {
        byte[] client = "vanilla-1.7.10".getBytes(), forge = "forge-universal".getBytes(),
                wrapper = "launchwrapper".getBytes();
        JsonObject parent = version(client);
        parent.addProperty("minecraftArguments", "--username ${auth_player_name}");
        catalog(parent);
        route("/mirror/client.jar", client);
        String forgeCoordinate = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10";
        String forgePath = GameInstaller.mavenPath(forgeCoordinate, null),
                remoteForgePath = forgePath.replace(".jar", "-universal.jar");
        String wrapperPath = GameInstaller.mavenPath(
                "net.minecraft:launchwrapper:1.12", null);
        route("/forge/" + remoteForgePath + ".sha1",
                hash(forge).getBytes(StandardCharsets.US_ASCII));
        route("/forge/" + remoteForgePath, forge);
        route("/mojang/" + wrapperPath + ".sha1", (hash(wrapper)
                + "  launchwrapper-1.12.jar\n").getBytes(StandardCharsets.US_ASCII));
        route("/mojang/" + wrapperPath, wrapper);
        JsonObject child = new JsonObject();
        child.addProperty("id", "forge-test");
        child.addProperty("jar", "test");
        child.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");
        JsonArray libraries = new JsonArray();
        JsonObject forgeLibrary = new JsonObject();
        forgeLibrary.addProperty("name", forgeCoordinate);
        forgeLibrary.addProperty("url", "http://files.minecraftforge.net/maven/");
        libraries.add(forgeLibrary);
        JsonObject wrapperLibrary = new JsonObject();
        wrapperLibrary.addProperty("name", "net.minecraft:launchwrapper:1.12");
        libraries.add(wrapperLibrary);
        child.add("libraries", libraries);
        Path childRoot = Files.createDirectories(temporary.resolve(
                "versions/forge-test"));
        Files.writeString(childRoot.resolve("forge-test.json"), child.toString());
        GameInstaller installer = new GameInstaller(URI.create(base + "/catalog"),
                URI.create(base + "/mojang"), URI.create(base + "/forge"));
        var installed = installer.install("forge-test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE);
        JsonObject normalized = JsonParser.parseString(Files.readString(
                installed.descriptor())).getAsJsonObject();
        assertFalse(normalized.has("jar"));
        assertFalse(normalized.has("inheritsFrom"));
        assertEquals("net.minecraft.launchwrapper.Launch",
                normalized.get("mainClass").getAsString());
        assertEquals(new GameInstaller.GameIdentity("test", "forge"),
                GameInstaller.identity(installed.descriptor()));
        assertArrayEquals(client, Files.readAllBytes(childRoot.resolve("forge-test.jar")),
                "Forge profile must use the inherited vanilla client, not universal.j"
                + "ar as client");
        assertArrayEquals(forge, Files.readAllBytes(temporary.resolve(
                "libraries").resolve(forgePath)));
        assertArrayEquals(wrapper, Files.readAllBytes(temporary.resolve(
                "libraries").resolve(wrapperPath)));
        assertEquals(hash(forge), normalized.getAsJsonArray("libraries").get(
                0).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject(
                "artifact").get("sha1").getAsString());
    }

    @Test void refusesLegacyLibraryWithoutValidOfficialChecksum() throws Exception {
        byte[] client = "client".getBytes();
        JsonObject version = version(client);
        JsonArray libraries = new JsonArray();
        JsonObject library = new JsonObject();
        library.addProperty("name", "test:unsigned:1");
        libraries.add(library);
        version.add("libraries", libraries);
        route("/mirror/client.jar", client);
        catalog(version);
        route("/mojang/test/unsigned/1/unsigned-1.jar.sha1",
                "<html>not a checksum</html>".getBytes(StandardCharsets.US_ASCII));
        GameInstaller installer = new GameInstaller(URI.create(base + "/catalog"),
                URI.create(base + "/mojang"), URI.create(base + "/forge"));
        assertThrows(java.io.IOException.class, () -> installer.install("test", temporary,
                mirrorSources(), ProgressListener.NONE, CancellationToken.NONE));
        assertFalse(Files.exists(temporary.resolve(
                "libraries/test/unsigned/1/unsigned-1.jar")));
        assertFalse(Files.exists(temporary.resolve("versions/test/test.json")));
    }

    @Test void fallsBackToForgeOnlyWhenMojangDoesNotPublishTheLegacyArtifact(
            ) throws Exception {
        byte[] client = "client".getBytes(), libraryBytes = "forge dependency".getBytes();
        JsonObject version = version(client);
        JsonArray libraries = new JsonArray();
        JsonObject library = new JsonObject();
        library.addProperty("name", "test:forge-only:1");
        libraries.add(library);
        version.add("libraries", libraries);
        String path = GameInstaller.mavenPath("test:forge-only:1", null);
        route("/mirror/client.jar", client);
        catalog(version);
        route("/forge/" + path + ".sha1", hash(libraryBytes).getBytes(
                StandardCharsets.US_ASCII));
        route("/forge/" + path, libraryBytes);
        GameInstaller installer = new GameInstaller(URI.create(base + "/catalog"),
                URI.create(base + "/mojang"), URI.create(base + "/forge"));
        installer.install("test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(libraryBytes, Files.readAllBytes(
                temporary.resolve("libraries").resolve(path)));
    }

    @Test void verifiesLegacyNativeClassifiersWithoutRequestingANonexistentPlainJar(
            ) throws Exception {
        byte[] client = "client".getBytes(), nativeBytes = "native".getBytes();
        JsonObject version = version(client);
        JsonArray libraries = new JsonArray();
        JsonObject library = new JsonObject();
        library.addProperty("name", "test:platform:1");
        JsonObject natives = new JsonObject();
        natives.addProperty(dev.leverage.launch.LaunchRules.Environment.current(
                ).osName(), "natives-test");
        library.add("natives", natives);
        libraries.add(library);
        version.add("libraries", libraries);
        String path = GameInstaller.mavenPath("test:platform:1", "natives-test");
        route("/mirror/client.jar", client);
        catalog(version);
        route("/mojang/" + path + ".sha1", hash(nativeBytes).getBytes(
                StandardCharsets.US_ASCII));
        route("/mojang/" + path, nativeBytes);
        GameInstaller installer = new GameInstaller(URI.create(base + "/catalog"),
                URI.create(base + "/mojang"), URI.create(base + "/forge"));
        var installed = installer.install("test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(nativeBytes, Files.readAllBytes(
                temporary.resolve("libraries").resolve(path)));
        JsonObject normalized = JsonParser.parseString(Files.readString(
                installed.descriptor())).getAsJsonObject();
        assertFalse(normalized.getAsJsonArray("libraries").get(0).getAsJsonObject(
                ).getAsJsonObject("downloads").has("artifact"));
    }

    @Test void usesPublishedInstallerChecksumWhenAnOldRepositoryLostOnlyItsSidecar(
            ) throws Exception {
        byte[] client="client".getBytes(), bytes="verified old scala library".getBytes();
        JsonObject version=version(client);
        JsonObject library=new JsonObject();
        library.addProperty("name", "org.scala-lang:old:1");
        library.addProperty("url", "https://maven.minecraftforge.net/");
        JsonArray checksums=new JsonArray();
        checksums.add(hash(bytes));
        library.add("checksums", checksums);
        JsonArray libraries=new JsonArray();
        libraries.add(library);
        version.add("libraries", libraries);
        route("/mirror/client.jar", client);
        route("/forge/org/scala-lang/old/1/old-1.jar", bytes);
        catalog(version);
        server.createContext("/forge/org/scala-lang/old/1/old-1.jar.sha1", exchange-> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        GameInstaller installer=new GameInstaller(URI.create(base+"/catalog"),
                URI.create(base+"/mojang"), URI.create(base+"/forge"));
        installer.install("test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(bytes, Files.readAllBytes(temporary.resolve(
                "libraries/org/scala-lang/old/1/old-1.jar")));
        Files.writeString(temporary.resolve("libraries/org/scala-lang/old/1/old-1.jar"),
                "bad cache");
        installer.install("test", temporary, mirrorSources(),
                ProgressListener.NONE, CancellationToken.NONE);
        assertArrayEquals(bytes, Files.readAllBytes(temporary.resolve(
                "libraries/org/scala-lang/old/1/old-1.jar")));
    }

    @Test void childLibraryVersionOverridesTheParentCoordinate() {
        JsonObject parent = JsonParser.parseString(
                "{\"libraries\":[{\"name\":\"test:library:1\"},{\"name\":\"test:nativ"
                + "e:1:windows\"}]}").getAsJsonObject();
        JsonObject child = JsonParser.parseString(
                "{\"libraries\":[{\"name\":\"test:library:2\"}]}").getAsJsonObject();
        JsonArray libraries = GameInstaller.merge(parent,
                child).getAsJsonArray("libraries");
        assertEquals(2, libraries.size());
        assertEquals("test:library:2", libraries.get(0).getAsJsonObject(
                ).get("name").getAsString());
        assertThrows(java.io.IOException.class,
                () -> GameInstaller.mavenPath("group:../../outside:1", null));
        assertThrows(java.io.IOException.class, () -> GameInstaller.mavenPath(
                "group:artifact:1", "../../outside"));
    }

    private LauncherPreferences.DownloadSources mirrorSources() {
        return new LauncherPreferences.DownloadSources(
                LauncherPreferences.DownloadSource.external(URI.create(base)),
                LauncherPreferences.DownloadSource.defaults());
    }

    private JsonObject version(byte[] client) throws Exception {
        JsonObject version = new JsonObject();
        version.addProperty("id", "test");
        version.addProperty("mainClass", "test.Main");
        JsonObject downloads = new JsonObject();
        downloads.add("client", descriptor("/mirror/client.jar", client));
        version.add("downloads", downloads);
        JsonObject java = new JsonObject();
        java.addProperty("component", "java-runtime-delta");
        java.addProperty("majorVersion", 21);
        version.add("javaVersion", java);
        return version;
    }
    private void catalog(JsonObject version) throws Exception {
        byte[] bytes = version.toString().getBytes(StandardCharsets.UTF_8);
        route("/mirror/version.json", bytes);
        JsonObject entry = descriptor("/mirror/version.json", bytes);
        entry.addProperty("id", "test");
        entry.addProperty("type", "release");
        JsonArray versions = new JsonArray();
        versions.add(entry);
        JsonObject catalog = new JsonObject();
        catalog.add("versions", versions);
        route("/game/version_manifest_v2.json",
                catalog.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JsonObject descriptor(String path, byte[] bytes) throws Exception {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("url", base + path);
        descriptor.addProperty("sha1", hash(bytes));
        descriptor.addProperty("size", bytes.length);
        return descriptor;
    }
    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(ArtifactDownloads.digest().digest(bytes));
    }
    private void route(String path, byte[] bytes) {
        server.createContext(path, exchange -> {
            if (!path.equals(
                    "/game/version_manifest_v2.json"))artifactRequests.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}

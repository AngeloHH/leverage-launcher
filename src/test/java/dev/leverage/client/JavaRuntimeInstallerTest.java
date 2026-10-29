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

class JavaRuntimeInstallerTest {
    @TempDir Path temporary;
    HttpServer server;
    String base;
    AtomicInteger fileRequests = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void mirrorRuntimeIsIndependentAndEveryFileIsVerified() throws Exception {
        String platform = JavaRuntimeInstaller.platform(), executable
                = platform.startsWith("windows") ? "bin/java.exe" : "bin/java";
        JsonObject files = new JsonObject();
        files.add(executable, file("/java", "test-executable"));
        files.add("release", file("/release", "JAVA_VERSION=\"21.0.7\"\n"));
        JsonObject runtime = new JsonObject();
        runtime.add("files", files);
        route("/runtime", runtime.toString());
        JsonObject entry = new JsonObject();
        entry.addProperty("platform", platform);
        entry.addProperty("component", "test-java");
        entry.addProperty("url", base + "/runtime");
        JsonObject version = new JsonObject();
        version.addProperty("name", "21.0.7");
        entry.add("version", version);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject index = new JsonObject();
        index.add("javaRuntimes", entries);
        route("/mirror/versions/index.json", index.toString());
        JavaRuntimeInstaller installer = new JavaRuntimeInstaller(
                URI.create(base + "/must-not-use-official"));
        var source = LauncherPreferences.DownloadSource.external(URI.create(base));
        Path java = installer.ensureRuntime("test-java", 21, temporary,
                source, ProgressListener.NONE, CancellationToken.NONE);
        assertEquals("test-executable", Files.readString(java));
        int before = fileRequests.get();
        installer.ensureRuntime("test-java", 21, temporary, source,
                ProgressListener.NONE, CancellationToken.NONE);
        assertEquals(before + 2, fileRequests.get(),
                "only mutable index and manifest are refetched");
        assertThrows(java.io.IOException.class, () -> installer.ensureRuntime("test-java",
                25, temporary, source, ProgressListener.NONE, CancellationToken.NONE));
    }

    @Test void parsesLegacyAndModernJavaVersions() {
        assertEquals(8, JavaRuntimeInstaller.javaMajor("1.8.0_251"));
        assertEquals(21, JavaRuntimeInstaller.javaMajor("21.0.7"));
        assertEquals(25, JavaRuntimeInstaller.javaMajor("25.0.1"));
    }
    @Test void cancellationDoesNotCreateRuntimeFiles() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(java.io.InterruptedIOException.class,
                () -> new JavaRuntimeInstaller(URI.create(base + "/official"))
                .ensureRuntime("test-java", 21, temporary,
                LauncherPreferences.DownloadSource.defaults(),
                ProgressListener.NONE, token));
        assertEquals(0, fileRequests.get());
    }
    private JsonObject file(String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        route(path, content);
        JsonObject raw = new JsonObject();
        raw.addProperty("url", base + path);
        raw.addProperty("size", bytes.length);
        raw.addProperty("sha1", HexFormat.of().formatHex(
                ArtifactDownloads.digest().digest(bytes)));
        JsonObject downloads = new JsonObject();
        downloads.add("raw", raw);
        JsonObject file = new JsonObject();
        file.addProperty("type", "file");
        file.add("downloads", downloads);
        return file;
    }
    private void route(String path, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            fileRequests.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}

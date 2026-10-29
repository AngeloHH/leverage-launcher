package dev.leverage.launch;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GameLaunchServiceTest {
    @TempDir Path directory;
    private final LaunchRules.Environment windows = new LaunchRules.Environment("windows",
            "amd64", "10.0.19045", Map.of());

    @Test void rulesAreOrderedAndRespectOsFeaturesArchitectureAndRanges() {
        assertTrue(LaunchRules.allows(json("{}"), windows));
        assertFalse(LaunchRules.allows(json("{\"rules\":[]}"), windows));
        assertTrue(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"windows\",\"ver"
                + "sion\":\"10\\\\..*\",\"arch\":\"amd64\"}}]}"), windows));
        assertFalse(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{"
                + "\"name\":\"windows\"}}]}"), windows));
        assertFalse(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_demo_user\":tru"
                + "e}}]}"), windows));
        assertTrue(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_demo_user\":fal"
                + "se}}]}"), windows));
        assertTrue(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\",\"os\":{\"versionRange\":{\"min\":"
                + "\"10.0.17134\",\"max\":\"11\"}}}]}"), windows));
        assertFalse(LaunchRules.allows(json(
                "{\"rules\":[{\"action\":\"allow\",\"os\":{\"version\":\"[\"}}]}"),
                windows));
    }

    @Test void mergesInheritedLibrariesWithoutLosingNativeClassifiers() {
        JsonObject result = GameLaunchService.merge(json(
        """
                {"mainClass":"parent.Main","arguments":{"jvm":["-cp","${classpath}"],"ga\
                me":["--username","${auth_player_name}"]},
                 "libraries":[{"name":"g:a:1"},{"name":"g:a:1:natives-windows"}]}
                """
        ), json(
        """
                {"mainClass":"child.Main","arguments":{"game":["--custom","yes"]},
                 "libraries":[{"name":"g:a:2"},{"name":"g:b:1"}]}
                """
        ));
        assertEquals("child.Main", result.get("mainClass").getAsString());
        assertEquals(4, result.getAsJsonObject(
                "arguments").getAsJsonArray("game").size());
        assertEquals(3, result.getAsJsonArray("libraries").size());
        assertEquals("g:a:2", result.getAsJsonArray("libraries").get(
                0).getAsJsonObject().get("name").getAsString());
        assertTrue(result.toString().contains("natives-windows"));
    }

    @Test void preparesVerifiedClasspathWithoutWritingTokensToArgfileOrSharedDirectory()
            throws Exception {
        Path root = directory.resolve("cache");
        Path game = directory.resolve("isolated game");
        install(root, "test", "",
                "--username ${auth_player_name} --gameDir ${game_directory} --accessT"
                + "oken ${auth_access_token}");
        Path existing = Files.writeString(Files.createDirectories(root.resolve(
                "saves")).resolve("untouched.txt"), "keep");
        String token = "never-print-this-secret";
        var request = request(root, game, "test", token);
        try (var plan = service(25).prepare(request)) {
            assertEquals(25, plan.javaMajor());
            assertEquals("test", plan.versionId());
            Path argfile = findFile(game, "jvm.args");
            String arguments = Files.readString(argfile);
            assertTrue(arguments.contains("-cp"));
            assertTrue(arguments.contains("test.jar"));
            assertFalse(arguments.contains(token));
            assertFalse(request.toString().contains(token));
            assertFalse(plan.toString().contains(token));
            assertEquals("keep", Files.readString(existing));
            assertEquals(1, Files.list(root.resolve("versions/test")).filter(
                    p -> p.toString().endsWith(".jar")).count());
        }
        assertFalse(Files.exists(findRun(game)));
    }

    @Test void verifiesShaAndSizeAndRefusesCorruption() throws Exception {
        Path root = directory.resolve("cache");
        install(root, "test", "", "--username ${auth_player_name}");
        Path jar = root.resolve("versions/test/test.jar");
        JsonObject metadata = json(Files.readString(root.resolve(
                "versions/test/test.json")));
        JsonObject client = new JsonObject();
        client.addProperty("size", Files.size(jar));
        client.addProperty("sha1", sha1(Files.readAllBytes(jar)));
        JsonObject downloads = new JsonObject();
        downloads.add("client", client);
        metadata.add("downloads", downloads);
        Files.writeString(root.resolve("versions/test/test.json"), metadata.toString());
        try (var ignored = service(25).prepare(request(root,
                directory.resolve("game"), "test", "0"))) { }
        Files.writeString(jar, "changed");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("other-game"), "test", "0")));
    }

    @Test void preparesJava8WithDirectClasspathAndNoCredentialsOnDiskWhenCommandFits()
            throws Exception {
        Path root = directory.resolve("cache # Unicode-é"),
                game = directory.resolve("isolated game");
        install(root, "legacy", "",
                "--username ${auth_player_name} --session ${auth_session}");
        Path descriptor = root.resolve("versions/legacy/legacy.json");
        JsonObject metadata = json(Files.readString(descriptor));
        metadata.getAsJsonObject("javaVersion").addProperty("majorVersion", 8);
        Files.writeString(descriptor, metadata.toString());
        String token = "legacy-private-session-token";
        try (var plan = service(8).prepare(request(root, game, "legacy", token))) {
            assertEquals(8, plan.javaMajor());
            List<String> arguments = jvmArguments(plan);
            assertTrue(arguments.contains(root.resolve(
                    "versions/legacy/legacy.jar").toString()));
            assertTrue(arguments.contains("-cp"));
            assertFalse(String.join(" ", arguments).contains(token));
            assertFalse(Files.exists(plan.logPath().getParent().resolve(
                    "classpath.jar")));
            try (var files = Files.walk(game)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().equals(
                        "jvm.args")));
            }
            assertFalse(plan.toString().contains(token));
        }
        assertFalse(Files.exists(findRun(game)));
    }

    @Test void
            java8ManifestShortensVeryLongClasspathsAndRejectsAmbiguousOrUnsafeDeclarations
            () throws Exception {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 350; i++) {
            Path library = Files.writeString(directory.resolve("library # Unicode-é-"
                    + i + "-" + "x".repeat(90) + ".jar"), "public-fixture");
            entries.add(library.toString());
        }
        String classpath = String.join(File.pathSeparator, entries);
        assertTrue(classpath.length() > 32_767);
        Path helper = directory.resolve("classpath.jar");
        List<String> command = GameLaunchService.java8Classpath(List.of("-Xmx2G",
                "-cp", classpath), helper);
        assertEquals(List.of("-Xmx2G", "-cp", helper.toString()), command);
        GameLaunchService.checkWindowsCommandLength(command);
        try (JarFile jar = new JarFile(helper.toFile())) {
            String value = jar.getManifest().getMainAttributes().getValue(
                    Attributes.Name.CLASS_PATH);
            assertTrue(value.contains("%20"));
            assertTrue(value.contains("%23"));
            assertTrue(value.contains("%C3%B1"));
            assertEquals(entries.size(), value.split(" ").length);
            assertEquals(Path.of(entries.getLast()).toUri().toASCIIString(),
                    value.substring(value.lastIndexOf(' ') + 1));
        }
        assertThrows(IOException.class, () -> GameLaunchService.checkWindowsCommandLength(
                List.of("x".repeat(30_001))));
        assertThrows(IOException.class, () -> GameLaunchService.java8Classpath(
                List.of("-cp"), directory.resolve("missing.jar")));
        assertThrows(IOException.class, () -> GameLaunchService.java8Classpath(
                List.of("-cp", "*"), directory.resolve("wildcard.jar")));
        assertThrows(IOException.class, () -> GameLaunchService.java8Classpath(
                List.of("-cp", entries.getFirst(), "-cp", entries.getLast()),
                directory.resolve("ambiguous.jar")));
    }

    @Test void inheritedForgeLegacyArgumentsOverrideVanillaAndRetainParentNatives()
            throws Exception {
        Path root = directory.resolve("cache"), game = directory.resolve("game");
        install(root, "1.7.10", "", "--username ${auth_player_name}");
        Path parent = root.resolve("versions/1.7.10/1.7.10.json");
        JsonObject base = json(Files.readString(parent));
        base.getAsJsonObject("javaVersion").addProperty("majorVersion", 8);
        Path library = Files.createDirectories(root.resolve("libraries/g/native/1"));
        Files.writeString(library.resolve("native-1.jar"), "public-library-fixture");
        Path natives = library.resolve("native-1-natives-windows.jar");
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(natives))) {
            output.putNextEntry(new ZipEntry("native.dll"));
            output.write(new byte[] { 1, 2, 3 });
            output.closeEntry();
        }
        base.add("libraries", JsonParser.parseString(
        """
                [{"name":"g:native:1","natives":{"windows":"natives-windows"},
                  "downloads":{"artifact":{"path":"g/native/1/native-1.jar"},
                    "classifiers":{"natives-windows":{"path":"g/native/1/native-1-native\
                s-windows.jar"}}}}]
                """
        ));
        Files.writeString(parent, base.toString());
        Path forge = Files.createDirectories(root.resolve("versions/1.7.10-Forge"));
        Files.writeString(forge.resolve("1.7.10-Forge.json"),
        """
                {"id":"1.7.10-Forge","inheritsFrom":"1.7.10","mainClass":"net.minecraft.\
                launchwrapper.Launch",
                 "minecraftArguments":"--username ${auth_player_name} --accessToken \
                ${auth_access_token} --tweakClass \
                cpw.mods.fml.common.launcher.FMLTweaker"}
                """
        );
        try (var plan = service(8).prepare(request(root, game, "1.7.10-Forge", "0"))) {
            assertTrue(String.join(" ", jvmArguments(plan)).contains("1.7.10.jar"));
            assertArrayEquals(new byte[] { 1, 2, 3 },
                    Files.readAllBytes(findFile(game, "native.dll")));
        }
        JsonObject merged = GameLaunchService.merge(base,
                json(Files.readString(forge.resolve("1.7.10-Forge.json"))));
        assertEquals("net.minecraft.launchwrapper.Launch",
                merged.get("mainClass").getAsString());
        assertTrue(merged.get("minecraftArguments").getAsString().contains(
                "--tweakClass cpw.mods.fml.common.launcher.FMLTweaker"));
    }

    @Test void classifierOnlyNativesNeedNoOrdinaryMavenJarAndStillVerifyTheirArchive()
            throws Exception {
        Path root = directory.resolve("native cache"),
                game = directory.resolve("native game");
        install(root, "1.7.10", "", "--username ${auth_player_name}");
        Path nativeJar = root.resolve(
                "libraries/g/platform/1/platform-1-natives-windows.jar");
        Files.createDirectories(nativeJar.getParent());
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(nativeJar))) {
            output.putNextEntry(new ZipEntry("platform.dll"));
            output.write(new byte[] { 4, 5, 6 });
            output.closeEntry();
        }
        JsonObject archive = new JsonObject();
        archive.addProperty("path", "g/platform/1/platform-1-natives-windows.jar");
        archive.addProperty("sha1", sha1(Files.readAllBytes(nativeJar)));
        archive.addProperty("size", Files.size(nativeJar));
        JsonObject library = json(
        """
                {"name":"g:platform:1","natives":{"windows":"natives-windows"},
                 "downloads":{"classifiers":{}}}
                """
        );
        library.getAsJsonObject("downloads").getAsJsonObject(
                "classifiers").add("natives-windows", archive);
        JsonArray libraries = new JsonArray();
        libraries.add(library);
        libraries.add(json(
        """
                {"name":"g:other-platform:1","natives":{"linux":"natives-linux"},
                 "downloads":{"classifiers":{"natives-linux":{"path":"unused-linux.jar"}\
                }}}
                """
        ));
        Path descriptor = root.resolve("versions/1.7.10/1.7.10.json");
        JsonObject version = json(Files.readString(descriptor));
        version.getAsJsonObject("javaVersion").addProperty("majorVersion", 8);
        version.add("libraries", libraries);
        Files.writeString(descriptor, version.toString());
        assertFalse(Files.exists(nativeJar.resolveSibling("platform-1.jar")));
        try (var plan = service(8).prepare(request(root, game, "1.7.10", "0"))) {
            assertArrayEquals(new byte[] { 4, 5, 6 },
                    Files.readAllBytes(findFile(game, "platform.dll")));
            assertFalse(String.join(" ", jvmArguments(plan)).contains("platform-1.jar"));
            assertFalse(String.join(" ", jvmArguments(plan)).contains(
                    "platform-1-natives-windows.jar"));
        }
        byte[] corrupted = Files.readAllBytes(nativeJar);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(nativeJar, corrupted);
        assertThrows(IOException.class, () -> service(8).prepare(request(root,
                directory.resolve("corrupt native game"), "1.7.10", "0")));
    }

    @Test void java8PreparationUsesManifestOnlyWhenTheFullCommandWouldBeTooLong()
            throws Exception {
        Path root=directory.resolve("long cache"), game=directory.resolve("long game");
        install(root, "legacy", "", "--username ${auth_player_name}");
        JsonArray libraries=new JsonArray();
        for (int i=0; i<350; i++) {
            String relative="long/"+i+"/"+"x".repeat(95)+".jar";
            Path target=root.resolve("libraries").resolve(relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "public fixture");
            JsonObject artifact=new JsonObject();
            artifact.addProperty("path", relative);
            JsonObject downloads=new JsonObject();
            downloads.add("artifact", artifact);
            JsonObject library=new JsonObject();
            library.addProperty("name", "g:a"+i+":1");
            library.add("downloads", downloads);
            libraries.add(library);
        }
        Path descriptor=root.resolve("versions/legacy/legacy.json");
        JsonObject metadata=json(Files.readString(descriptor));
        metadata.add("libraries", libraries);
        metadata.getAsJsonObject("javaVersion").addProperty("majorVersion", 8);
        Files.writeString(descriptor, metadata.toString());
        try (var plan=service(8).prepare(request(root, game,
                "legacy", "do-not-write-this-token"))) {
            Path helper=findFile(game, "classpath.jar");
            assertTrue(jvmArguments(plan).contains(helper.toString()));
            try (JarFile jar=new JarFile(helper.toFile())) {
                String cp=jar.getManifest().getMainAttributes().getValue(
                        Attributes.Name.CLASS_PATH);
                assertEquals(351, cp.split(" ").length);
                assertFalse(cp.contains("do-not-write-this-token"));
            }
        }
        assertFalse(Files.exists(findRun(game)));
    }

    @Test void mapsLegacyResourcesIntoIsolatedGameDirectory() throws Exception {
        Path root = directory.resolve("cache"), game = directory.resolve("game");
        install(root, "legacy", "", "--username ${auth_player_name}");
        byte[] content = "verified old sound".getBytes(StandardCharsets.UTF_8);
        String hash = sha1(content);
        Path object = Files.createDirectories(root.resolve("assets/objects/"
                + hash.substring(0, 2))).resolve(hash);
        Files.write(object, content);
        Path indexes = Files.createDirectories(root.resolve("assets/indexes")),
                index = indexes.resolve("old.json");
        JsonObject asset = new JsonObject();
        asset.addProperty("hash", hash);
        asset.addProperty("size", content.length);
        JsonObject entries = new JsonObject();
        entries.add("sound/note.ogg", asset);
        JsonObject assets = new JsonObject();
        assets.addProperty("map_to_resources", true);
        assets.add("objects", entries);
        Files.writeString(index, assets.toString());
        Path descriptor = root.resolve("versions/legacy/legacy.json");
        JsonObject metadata = json(Files.readString(descriptor));
        metadata.add("assetIndex", json("{\"id\":\"old\"}"));
        Files.writeString(descriptor, metadata.toString());
        Path shared = Files.createDirectories(root.resolve(
                "resources/sound")).resolve("note.ogg");
        Files.writeString(shared, "do not trust or modify shared resources");
        try (var ignored = service(25).prepare(request(root, game, "legacy", "0"))) {
            assertArrayEquals(content, Files.readAllBytes(game.resolve(
                    "resources/sound/note.ogg")));
            assertEquals("do not trust or modify shared resources",
                    Files.readString(shared));
        }
        Files.writeString(object, "corrupted");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("other-game"), "legacy", "0")));
        Files.write(object, content);
        JsonObject unsafeEntries = new JsonObject();
        unsafeEntries.add("../escape.ogg", asset);
        assets.add("objects", unsafeEntries);
        Files.writeString(index, assets.toString());
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("unsafe-game"), "legacy", "0")));
        assertFalse(Files.exists(directory.resolve("unsafe-game/escape.ogg")));
    }

    @Test void rejectsUnknownOrTamperedLoggingConfiguration() throws Exception {
        Path root = directory.resolve("cache"), game = directory.resolve("game");
        install(root, "legacy", "", "--username ${auth_player_name}");
        byte[] content = "<Configuration>trusted-fixture</Configuration>".getBytes(
                StandardCharsets.UTF_8);
        Path config = Files.createDirectories(root.resolve(
                "assets/log_configs")).resolve("client-1.7.xml");
        Files.write(config, content);
        Path descriptor = root.resolve("versions/legacy/legacy.json");
        JsonObject metadata = json(Files.readString(descriptor));
        JsonObject file = new JsonObject();
        file.addProperty("id", config.getFileName().toString());
        file.addProperty("sha1", sha1(content));
        file.addProperty("size", content.length);
        JsonObject client = new JsonObject();
        client.addProperty("argument", "-Dlog4j.configurationFile=${path}");
        client.add("file", file);
        JsonObject logging = new JsonObject();
        logging.add("client", client);
        metadata.add("logging", logging);
        Files.writeString(descriptor, metadata.toString());
        try (var ignored = service(25).prepare(request(root, game, "legacy", "0"))) {
            String jvm = Files.readString(findFile(game, "jvm.args"));
            assertTrue(jvm.contains("-Dlog4j.configurationFile="));
            assertTrue(jvm.contains("client-1.7.xml"));
        }
        Files.writeString(config, "corrupted");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("tampered"), "legacy", "0")));
        Files.write(config, content);
        client.addProperty("argument", "-Dlog4j.configurationFile=${unknown}");
        Files.writeString(descriptor, metadata.toString());
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("unknown-config"), "legacy", "0")));
        client.addProperty("argument", "-Dlog4j.configurationFile=${path}");
        file.remove("sha1");
        Files.writeString(descriptor, metadata.toString());
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("unverified-config"), "legacy", "0")));
    }

    @Test void resolvesParentClientJarAndRejectsHereditaryCycles() throws Exception {
        Path root = directory.resolve("cache");
        install(root, "base", "", "--username ${auth_player_name}");
        Path child = Files.createDirectories(root.resolve("versions/child"));
        Files.writeString(child.resolve("child.json"),
                "{\"id\":\"child\",\"inheritsFrom\":\"base\",\"mainClass\":\"test.Mai"
                + "n\"}");
        try (var ignored = service(25).prepare(request(root,
                directory.resolve("game"), "child", "0"))) {
            assertTrue(Files.readString(findFile(directory.resolve("game"),
                    "jvm.args")).contains("base.jar"));
        }
        Files.writeString(child.resolve("child.json"),
                "{\"id\":\"child\",\"inheritsFrom\":\"child\"}");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("other-game"), "child", "0")));
    }

    @Test void legacyQuotesAndPlaceholdersAreExpandedWithoutShellEvaluation()
            throws Exception {
        assertEquals(List.of("--gameDir", "${game_directory}",
                "--name", "A B", ""), GameLaunchService.tokenize(
                "--gameDir ${game_directory} --name \"A B\" ''"));
        assertEquals("C:\\Player $1\\game", GameLaunchService.expand("${game_directory}",
                Map.of("game_directory", "C:\\Player $1\\game")));
        assertThrows(IOException.class, () -> GameLaunchService.expand(
                "${unknown}", Map.of()));
        assertThrows(IOException.class, () -> GameLaunchService.tokenize(
                "--bad 'unterminated"));
        assertEquals("\"C:\\\\with spaces\\\\test.jar\"",
                GameLaunchService.quoteArgfile("C:\\with spaces\\test.jar"));
        assertEquals("\"\"", GameLaunchService.quoteArgfile(""));
        assertThrows(IllegalArgumentException.class,
                () -> GameLaunchService.quoteArgfile("argument\n-option"));
    }

    @Test void rejectsSharedGameDirectoryUnsupportedJavaAndUnknownPlaceholders()
            throws Exception {
        Path root = directory.resolve("cache");
        install(root, "test", "", "--username ${auth_player_name}");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                root, "test", "0")));
        assertThrows(IOException.class, () -> service(17).prepare(request(root,
                directory.resolve("game"), "test", "0")));
        assertThrows(IOException.class, () -> service(8).prepare(request(root,
                directory.resolve("game"), "test", "0")));
        install(root, "unknown", "", "--bad ${not_supported}");
        assertThrows(IOException.class, () -> service(25).prepare(request(root,
                directory.resolve("other-game"), "unknown", "0")));
    }

    @Test void rejectsUnsafeLibraryPathsAndNativeZipSlipEvenForExcludedEntries()
            throws Exception {
        Path natives = Files.createDirectories(directory.resolve("natives"));
        Path zip = directory.resolve("bad.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("../escape.dll"));
            output.write(1);
            output.closeEntry();
        }
        assertThrows(IOException.class, () -> GameLaunchService.extractNatives(zip,
                natives, List.of("../")));
        assertFalse(Files.exists(directory.resolve("escape.dll")));
        assertThrows(IOException.class, () -> GameLaunchService.safeResolve(natives,
                "C:/escape.jar"));
        assertThrows(IOException.class, () -> GameLaunchService.safeResolve(natives,
                "..\\escape.jar"));
        assertThrows(IOException.class, () -> GameLaunchService.safeResolve(natives,
                "/escape.jar"));
        assertThrows(IOException.class, () -> GameLaunchService.safeResolve(natives,
                "\\\\server\\escape.jar"));
    }

    @Test void extractsOnlyClassifierNativesAndSkipsMetaInf() throws Exception {
        Path natives = Files.createDirectories(directory.resolve("natives")),
                zip = directory.resolve("good.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("META-INF/signature"));
            output.write(1);
            output.closeEntry();
            output.putNextEntry(new ZipEntry("native.dll"));
            output.write(new byte[] { 2, 3 });
            output.closeEntry();
        }
        GameLaunchService.extractNatives(zip, natives, List.of("META-INF/"));
        assertArrayEquals(new byte[] { 2, 3 },
                Files.readAllBytes(natives.resolve("native.dll")));
        assertFalse(Files.exists(natives.resolve("META-INF")));
    }

    @Test void neverLogsTokensOrWritesThemToDiagnosticStrings() {
        String secret = "secrets-should-not-be-visible";
        assertEquals("token=<redacted>", GameLaunchService.redact(
                "token=" + secret, secret));
        assertFalse(GameLaunchService.redact("--accessToken " + secret
                + " Bearer other-secret", secret).contains(secret));
        assertFalse(GameLaunchService.redact("{\"accessToken\":\"hidden-token\"}",
                secret).contains("hidden-token"));
        assertEquals("Java 25.0.0", GameLaunchService.redact("Java 25.0.0", "0"));
    }

    @Test void launchesHarmlessFixtureThroughArgfileRedactsOutputAndDeliversExit()
            throws Exception {
        Path root = directory.resolve("cache");
        install(root, "fixture", "", "--accessToken ${auth_access_token}");
        Path jar = root.resolve("versions/fixture/fixture.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar));
             InputStream fixture = GameProcessFixture.class.getResourceAsStream(
                "GameProcessFixture.class")) {
            assertNotNull(fixture);
            output.putNextEntry(new ZipEntry(
                    "dev/leverage/launch/GameProcessFixture.class"));
            fixture.transferTo(output);
            output.closeEntry();
        }
        Path descriptor = root.resolve("versions/fixture/fixture.json");
        JsonObject metadata = json(Files.readString(descriptor));
        metadata.addProperty("mainClass", "dev.leverage.launch.GameProcessFixture");
        metadata.getAsJsonObject("javaVersion").addProperty("majorVersion", 17);
        Files.writeString(descriptor, metadata.toString());
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java");
        String secret = "integration-fixture-private-token";
        var request = new GameLaunchService.LaunchRequest(root,
                directory.resolve("fixture game"), "fixture", executable,
                "TestPlayer", UUID.randomUUID(), secret, List.of());
        var service = new GameLaunchService();
        var plan = service.prepare(request);
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        var running = service.launch(plan, new GameLaunchService.Listener() {
            @Override public void output(String line) {
                lines.add(line);
            }
        });
        assertEquals(0, running.onExit().get(20, TimeUnit.SECONDS));
        assertFalse(running.isAlive());
        assertTrue(lines.stream().anyMatch(line -> line.contains("FIXTURE_OK")));
        assertFalse(String.join("\n", lines).contains(secret));
        assertFalse(Files.readString(running.logPath()).contains(secret));
        assertThrows(IOException.class, () -> service.launch(plan, null));
    }

    @Test void preventsDuplicateProcessesAndCanCancelOnlyItsOwnFixture()
            throws Exception {
        Path root = directory.resolve("cache");
        install(root, "fixture", "", "--sleep");
        Path jar = root.resolve("versions/fixture/fixture.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar));
             InputStream fixture = GameProcessFixture.class.getResourceAsStream(
                "GameProcessFixture.class")) {
            assertNotNull(fixture);
            output.putNextEntry(new ZipEntry(
                    "dev/leverage/launch/GameProcessFixture.class"));
            fixture.transferTo(output);
            output.closeEntry();
        }
        Path descriptor = root.resolve("versions/fixture/fixture.json");
        JsonObject metadata = json(Files.readString(descriptor));
        metadata.addProperty("mainClass", "dev.leverage.launch.GameProcessFixture");
        metadata.getAsJsonObject("javaVersion").addProperty("majorVersion", 17);
        Files.writeString(descriptor, metadata.toString());
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java");
        var service = new GameLaunchService();
        var first = service.prepare(new GameLaunchService.LaunchRequest(root,
                directory.resolve("one"), "fixture", executable,
                "TestPlayer", UUID.randomUUID(), "0", List.of()));
        try (var second = service.prepare(new GameLaunchService.LaunchRequest(root,
                directory.resolve("two"), "fixture", executable,
                "TestPlayer", UUID.randomUUID(), "0", List.of()))) {
            var running = service.launch(first, null);
            try {
                assertTrue(running.isAlive());
                assertThrows(IOException.class, () -> service.launch(second, null));
            }
            finally {
                running.cancel();
                running.cancel();
            }
            running.onExit().get(15, TimeUnit.SECONDS);
            assertFalse(running.isAlive());
        }
    }

    @Test void java8LaunchPathRunsDirectlyAndDoesNotLeaveGeneratedArguments()
            throws Exception {
        Path root = directory.resolve("cache # Unicode-é"),
                game = directory.resolve("legacy fixture game");
        install(root, "fixture", "",
                "--accessToken ${auth_access_token} --assetsDir ${game_assets}");
        Path jar = root.resolve("versions/fixture/fixture.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar));
             InputStream fixture = GameProcessFixture.class.getResourceAsStream(
                "GameProcessFixture.class")) {
            assertNotNull(fixture);
            output.putNextEntry(new ZipEntry(
                    "dev/leverage/launch/GameProcessFixture.class"));
            fixture.transferTo(output);
            output.closeEntry();
        }
        Path descriptor = root.resolve("versions/fixture/fixture.json");
        JsonObject metadata = json(Files.readString(descriptor));
        metadata.addProperty("mainClass", "dev.leverage.launch.GameProcessFixture");
        metadata.getAsJsonObject("javaVersion").addProperty("majorVersion", 8);
        Files.writeString(descriptor, metadata.toString());
        Files.createDirectories(root.resolve("assets/virtual/1.7.10"));
        Files.writeString(Files.createDirectories(root.resolve("assets/indexes")).resolve(
                "1.7.10.json"), "{\"virtual\":true,\"objects\":{}}");
        metadata.add("assetIndex", json("{\"id\":\"1.7.10\"}"));
        Files.writeString(descriptor, metadata.toString());
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java");
        String secret = "legacy-integration-token";
        // Force the Java 8 command construction branch. The fixture itself uses the test
        // JDK;
        // this proves legacy command construction, not that an actual Java 8 was
        // exercised.
        var service = service(8);
        var plan = service.prepare(new GameLaunchService.LaunchRequest(root,
                game, "fixture", executable, "TestPlayer",
                UUID.randomUUID(), secret, List.of()));
        Path helper = plan.logPath().getParent().resolve("classpath.jar");
        var running = service.launch(plan, null);
        assertEquals(0, running.onExit().get(20, TimeUnit.SECONDS));
        String log = Files.readString(running.logPath());
        assertTrue(log.contains("FIXTURE_OK"));
        assertFalse(log.contains(secret));
        assertTrue(log.contains(root.resolve("assets/virtual/1.7.10").toString()));
        assertFalse(Files.exists(helper));
        assertTrue(Files.exists(jar));
    }

    private GameLaunchService service(int major) {
        return new GameLaunchService(executable -> major, windows);
    }
    @SuppressWarnings("unchecked")
    private static List<String> jvmArguments(
            GameLaunchService.LaunchPlan plan) throws Exception {
        var field = GameLaunchService.LaunchPlan.class.getDeclaredField("jvmArguments");
        field.setAccessible(true);
        return (List<String>)field.get(plan);
    }
    private GameLaunchService.LaunchRequest request(Path root, Path game,
            String id, String token) throws IOException {
        Path java = directory.resolve("fake-java.exe");
        if (!Files.exists(java)) Files.writeString(java, "fixture, never executable");
        return new GameLaunchService.LaunchRequest(root, game, id, java,
                "TestPlayer", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                token, List.of());
    }
    private void install(Path root, String id, String libraries,
            String legacyArguments) throws IOException {
        Path folder = Files.createDirectories(root.resolve("versions").resolve(id));
        Files.writeString(folder.resolve(id + ".jar"), "test-client-jar");
        JsonObject version = new JsonObject();
        version.addProperty("id", id);
        version.addProperty("type", "release");
        version.addProperty("mainClass", "test.Main");
        version.add("javaVersion", json("{\"majorVersion\":25}"));
        version.addProperty("minecraftArguments", legacyArguments);
        if (!libraries.isEmpty()) version.add("libraries",
                JsonParser.parseString(libraries));
        Files.writeString(folder.resolve(id + ".json"), version.toString());
    }
    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }
    private static Path findFile(Path root, String name) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals(
                    name)).findFirst().orElseThrow();
        }
    }
    private static Path findRun(Path game) throws IOException {
        try (var paths = Files.list(game.resolve(".leverage-run"))) {
            return paths.findFirst().orElse(game.resolve(".leverage-run/absent"));
        }
    }
}

// A tiny harmless Java subprocess used to test the process runner, not a Minecraft
// launch.
class GameProcessFixture {
    public static void main(String[] arguments) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.out.println("FIXTURE_OK");
        for (String argument : arguments) System.out.println(argument);
        if (Arrays.asList(arguments).contains("--sleep")) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

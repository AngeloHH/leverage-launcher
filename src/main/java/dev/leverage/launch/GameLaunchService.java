package dev.leverage.launch;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Launch an installed game without modifying its shared installation or saves.
public final class GameLaunchService {
    private static final Object PROCESS_LOCK = new Object();
    private static RunningGame active;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private final JavaProbe javaProbe;
    private final LaunchRules.Environment environment;

    public GameLaunchService() {
        this(GameLaunchService::probeJava, LaunchRules.Environment.current());
    }
    GameLaunchService(JavaProbe javaProbe, LaunchRules.Environment environment) {
        this.javaProbe = javaProbe;
        this.environment = environment;
    }

    public record LaunchRequest(Path minecraftDirectory, Path gameDirectory,
            String versionId, Path javaExecutable, String username, UUID uuid,
            String accessToken, List<String> authlibJvmArguments, GameMemory memory) {
        public LaunchRequest(Path minecraftDirectory, Path gameDirectory,
                String versionId, Path javaExecutable, String username, UUID uuid,
                String accessToken, List<String> authlibJvmArguments) {
            this(minecraftDirectory, gameDirectory, versionId, javaExecutable,
                    username, uuid, accessToken, authlibJvmArguments, GameMemory.DEFAULT);
        }
        public LaunchRequest {
            Objects.requireNonNull(memory);
            Objects.requireNonNull(minecraftDirectory);
            Objects.requireNonNull(gameDirectory);
            Objects.requireNonNull(javaExecutable);
            Objects.requireNonNull(uuid);
            requireId(versionId);
            if (username == null || !username.matches("[A-Za-z0-9_]{3,16}"))
                    throw new IllegalArgumentException(t("launch.invalidName"));
            if (accessToken == null || accessToken.isBlank())
                    throw new IllegalArgumentException(t("launch.invalidSession"));
            cleanArgument(accessToken);
            authlibJvmArguments = authlibJvmArguments == null ? List.of()
                    : List.copyOf(authlibJvmArguments);
            for (String argument : authlibJvmArguments) {
                cleanArgument(argument);
                if (!argument.startsWith("-javaagent:") && !argument.startsWith(
                        "-Dauthlibinjector.")) throw new IllegalArgumentException(
                        "Argumento del agente no permitido.");
            }
        }
        @Override public String toString() {
            return "LaunchRequest[version=" + versionId + ", player="
                    + username + ", credentials=<redacted>]";
        }
    }

    public interface Listener {
        default void output(String sanitizedLine) { }
        default void exited(int exitCode) { }
    }

    public static final class LaunchPlan implements AutoCloseable {
        private final LaunchRequest request;
        private final Path argfile, runtimeDirectory, log;
        private final List<String> jvmArguments, gameArguments;
        private final int javaMajor;
        private final AtomicBoolean used = new AtomicBoolean();
        private LaunchPlan(LaunchRequest request, Path argfile, Path runtimeDirectory,
                List<String> jvmArguments, List<String> gameArguments, int javaMajor) {
            this.request = request;
            this.argfile = argfile;
            this.runtimeDirectory = runtimeDirectory;
            this.jvmArguments = List.copyOf(jvmArguments);
            this.gameArguments = List.copyOf(gameArguments);
            this.javaMajor = javaMajor;
            this.log = runtimeDirectory.resolve("game.log");
        }
        public String versionId() { return request.versionId(); }
        public Path gameDirectory() {
            return request.gameDirectory().toAbsolutePath().normalize();
        }
        public Path logPath() { return log; }
        public int javaMajor() { return javaMajor; }
        @Override public void close() {
            if (used.compareAndSet(false, true)) removeGenerated(runtimeDirectory);
        }
        // Diagnostic output deliberately contains no command line or credentials.
        @Override public String toString() {
            return "LaunchPlan[version=" + versionId() + ", Java=" + javaMajor
                    + ", gameDirectory=" + gameDirectory() + "]";
        }
    }

    public static final class RunningGame {
        private final Process process;
        private final CompletableFuture<Integer> completion = new CompletableFuture<>();
        private final Path log;
        private final AtomicBoolean cancelling = new AtomicBoolean();
        private RunningGame(Process process, Path log) {
            this.process = process;
            this.log = log;
        }
        public boolean isAlive() { return process.isAlive(); }
        public Path logPath() { return log; }
        public CompletableFuture<Integer> onExit() { return completion; }
        public void cancel() {
            if (!cancelling.compareAndSet(false, true)) return;
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            Thread.ofVirtual().name("leverage-game-stop").start(() -> {
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        children.forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            });
        }
    }

    // Probe the selected Java off the EDT; preparation never starts Minecraft.
    public LaunchPlan prepare(LaunchRequest request,
            LaunchRules.Environment executionEnvironment) throws IOException,
            InterruptedException {
        return new GameLaunchService(javaProbe, Objects.requireNonNull(
                executionEnvironment)).prepare(request);
    }

    public LaunchPlan prepare(LaunchRequest request) throws IOException,
            InterruptedException {
        Objects.requireNonNull(request);
        Path minecraft = request.minecraftDirectory().toAbsolutePath().normalize();
        Path game = request.gameDirectory().toAbsolutePath().normalize();
        if (game.equals(minecraft) || minecraft.startsWith(game)) throw new IOException(
                "El juego necesita una carpeta de instancia aislada, "
                + "no la carpeta general de Minecraft.");
        checkPath(minecraft);
        checkPath(game);
        JsonObject version = readVersion(minecraft, request.versionId(),
                new LinkedHashSet<>());
        int requiredJava = object(version, "javaVersion").has("majorVersion")
                ? object(version, "javaVersion").get("majorVersion").getAsInt() : 8;
        if (requiredJava < 8 || requiredJava > 100)
                throw new IOException(t("launch.invalidJava"));
        Path java = request.javaExecutable().toAbsolutePath().normalize();
        requireFile(java);
        int javaMajor;
        try { javaMajor = javaProbe.major(java); }
        catch (IOException failure) {
            if (RuntimePlatform.current() == RuntimePlatform.MAC_ARM64
                    && environment.osName().equals("osx")
                    && environment.architecture().equals("x86_64"))
                    throw new IOException(t("java.intelUnavailable"), failure);
            throw failure;
        }
        if (javaMajor < requiredJava) throw new IOException("Minecraft "
                + request.versionId() + " necesita Java " + requiredJava
                + " o posterior; el Java elegido es " + javaMajor + ".");
        if (javaMajor < 8) throw new IOException(
                "Este launcher necesita Java 8 o posterior para iniciar el juego.");
        JsonObject client = object(version, "_leverageClientArtifact");
        Path jar = safeResolve(minecraft.resolve("versions"),
                required(version, "_leverageClientJarPath"));
        verify(jar, client);
        List<Path> classpath = new ArrayList<>();
        List<NativeJar> nativeJars = new ArrayList<>();
        for (JsonElement entry : array(version, "libraries")) {
            if (!entry.isJsonObject()) throw new IOException(t("launch.invalidLibrary"));
            JsonObject library = entry.getAsJsonObject();
            if (library.has("clientreq") && library.get("clientreq").isJsonPrimitive()
                    && library.get("clientreq").getAsJsonPrimitive().isBoolean()
                    && !library.get("clientreq").getAsBoolean()) continue;
            if (!LaunchRules.allows(library, environment)) continue;
            JsonObject downloads = object(library, "downloads"),
                    artifact = object(downloads, "artifact");
            if (!artifact.isEmpty()) {
                Path path = safeResolve(minecraft.resolve("libraries"),
                        required(artifact, "path"));
                verify(path, artifact);
                classpath.add(path);
            } else if (downloads.isEmpty() && !library.has("natives")
                    && library.has("name")) {
                // Classifier-only platform libraries have no ordinary classpath JAR.
                // Their declared native archive is verified/extracted below instead.
                Path path = safeResolve(minecraft.resolve("libraries"),
                        mavenPath(required(library, "name")));
                requireFile(path);
                classpath.add(path);
            }
            JsonObject natives = object(library, "natives");
            if (natives.has(environment.osName())) {
                String classifier = required(natives,
                        environment.osName()).replace("${arch}",
                        environment.architecture().contains("64") ? "64" : "32");
                JsonObject nativeArtifact = object(object(downloads,
                        "classifiers"), classifier);
                if (nativeArtifact.isEmpty()) throw new IOException(I18n.t(
                        "launch.missingNative", classifier));
                Path path = safeResolve(minecraft.resolve("libraries"),
                        required(nativeArtifact, "path"));
                verify(path, nativeArtifact);
                List<String> excludes = new ArrayList<>(List.of("META-INF/"));
                for (JsonElement exclude : array(object(library, "extract"),
                        "exclude")) excludes.add(exclude.getAsString());
                nativeJars.add(new NativeJar(path, excludes));
            }
        }
        classpath.add(jar);
        JsonObject assets = object(version, "assetIndex");
        String assetIndex = string(assets, "id", string(version, "assets", "legacy"));
        requireId(assetIndex);
        JsonObject assetContents = new JsonObject();
        Path indexFile = safeResolve(minecraft.resolve("assets/indexes"),
                assetIndex + ".json");
        if (!assets.isEmpty()) verify(indexFile, assets);
        if (Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(indexFile) > 32L * 1024 * 1024)
                    throw new IOException(t("launch.indexTooLarge"));
            try {
                assetContents = JsonParser.parseString(Files.readString(
                        indexFile)).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException(t("launch.indexJson"), exception);
            }
        }
        String main = required(version, "mainClass");
        if (!main.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))
                throw new IOException(t("launch.invalidMainClass"));
        Files.createDirectories(game);
        checkPath(game);
        if (booleanValue(assetContents, "map_to_resources")) materializeResources(
                minecraft.resolve("assets/objects"),
                game.resolve("resources"), assetContents);
        Path gameAssets = booleanValue(assetContents, "virtual") ? minecraft.resolve(
                "assets/virtual").resolve(assetIndex) : minecraft.resolve("assets");
        Path runs = game.resolve(".leverage-run");
        Files.createDirectories(runs);
        checkPath(runs);
        Path runtime = Files.createTempDirectory(runs, "run-");
        Path natives = Files.createDirectory(runtime.resolve("natives"));
        try {
            for (NativeJar nativeJar : nativeJars) extractNatives(nativeJar.path(),
                    natives, nativeJar.excludes());
            String cp = String.join(File.pathSeparator,
                    classpath.stream().map(Path::toString).toList());
            Map<String, String> substitutions = new HashMap<>();
            substitutions.put("auth_player_name", request.username());
            substitutions.put("version_name", request.versionId());
            substitutions.put("game_directory", game.toString());
            substitutions.put("assets_root", minecraft.resolve("assets").toString());
            substitutions.put("game_assets", gameAssets.toString());
            substitutions.put("assets_index_name", assetIndex);
            substitutions.put("auth_uuid", request.uuid().toString().replace("-", ""));
            substitutions.put("auth_access_token", request.accessToken());
            substitutions.put("auth_session", "token:" + request.accessToken()
                    + ":" + request.uuid().toString().replace("-", ""));
            substitutions.put("user_type", "mojang");
            substitutions.put("user_properties", "{}");
            substitutions.put("profile_properties", "{}");
            substitutions.put("version_type", string(version, "type", "release"));
            substitutions.put("clientid", "");
            substitutions.put("auth_xuid", "");
            substitutions.put("natives_directory", natives.toString());
            substitutions.put("library_directory",
                    minecraft.resolve("libraries").toString());
            substitutions.put("classpath", cp);
            substitutions.put("classpath_separator", File.pathSeparator);
            substitutions.put("launcher_name", "Leverage");
            substitutions.put("launcher_version", "0.1.0");
            List<String> jvm = new ArrayList<>();
            JsonObject arguments = object(version, "arguments");
            List<String> suppliedJvm = arguments(arguments, "jvm", substitutions);
            if (suppliedJvm.isEmpty()) suppliedJvm = List.of(
                    "-Djava.library.path=" + natives, "-cp", cp);
            jvm.addAll(suppliedJvm);
            jvm.removeIf(argument -> argument.startsWith("-Xms")
                    || argument.startsWith("-Xmx"));
            jvm.addAll(request.memory().jvmArguments());
            JsonObject logging = object(object(version, "logging"), "client");
            if (!logging.isEmpty()) {
                JsonObject config = object(logging, "file");
                // The official configuration also contains legacy Log4j mitigations.
                // Never silently launch with a missing or tampered downloaded config.
                required(config, "sha1");
                Path loggingFile = safeResolve(minecraft.resolve("assets/log_configs"),
                        required(config, "id"));
                verify(loggingFile, config);
                String loggingArgument = expand(required(logging, "argument"),
                        Map.of("path", loggingFile.toString()));
                if (!loggingArgument.startsWith("-D"))
                        throw new IOException(t("launch.invalidLogging"));
                jvm.add(loggingArgument);
            }
            jvm.addAll(request.authlibJvmArguments());
            if (!containsClasspath(jvm)) {
                jvm.add("-cp");
                jvm.add(cp);
            }
            for (String argument : jvm) {
                // Auth/session values must never reach the reusable JVM argfile.
                if (request.accessToken().length() > 3
                        && argument.contains(request.accessToken()))
                        throw new IOException(t("launch.unsafeCredentials"));
            }
            List<String> gameArguments = arguments(arguments, "game", substitutions);
            if (gameArguments.isEmpty() && version.has("minecraftArguments")) {
                for (String argument : tokenize(required(version,
                        "minecraftArguments"))) gameArguments.add(expand(argument,
                        substitutions));
            }
            if (gameArguments.isEmpty()) throw new IOException(t("launch.noArguments"));
            Path argfile = null;
            if (javaMajor >= 9) {
                jvm.add(main);
                argfile = runtime.resolve("jvm.args");
                String content = String.join(System.lineSeparator(),
                        jvm.stream().map(GameLaunchService::quoteArgfile).toList())
                        + System.lineSeparator();
                Files.writeString(argfile, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            } else {
                // Preserve the full visible class path for old FML scanners when it fits.
                // Java 8 has no @argfiles; only an oversized Windows command needs a
                // manifest helper with file URLs, never options or credentials.
                List<String> command = new ArrayList<>(List.of(java.toString()));
                command.addAll(jvm);
                command.add(main);
                command.addAll(gameArguments);
                if (environment.osName().equals("windows") && windowsCommandLength(
                        command) > 30_000) jvm = java8Classpath(jvm,
                        runtime.resolve("classpath.jar"));
                jvm.add(main);
                if (environment.osName().equals("windows")) {
                    command = new ArrayList<>(List.of(java.toString()));
                    command.addAll(jvm);
                    command.addAll(gameArguments);
                    checkWindowsCommandLength(command);
                }
            }
            return new LaunchPlan(request, argfile, runtime,
                    jvm, gameArguments, javaMajor);
        } catch (IOException | RuntimeException exception) {
            removeGenerated(runtime);
            throw exception;
        }
    }

    // Run one managed game per JVM; diagnostics never contain credentials.
    public RunningGame launch(LaunchPlan plan, Listener listener) throws IOException {
        Objects.requireNonNull(plan);
        Listener observer = listener == null ? new Listener() { }
        : listener;
        synchronized (PROCESS_LOCK) {
            if (active != null && active.isAlive())
                    throw new IOException(t("launch.alreadyRunning"));
            if (!plan.used.compareAndSet(false, true))
                    throw new IOException(t("launch.alreadyUsed"));
            List<String> command = new ArrayList<>();
            command.add(plan.request.javaExecutable().toAbsolutePath()
                    .normalize().toString());
            if (plan.argfile != null) command.add("@" + plan.argfile);
            else command.addAll(plan.jvmArguments);
            command.addAll(plan.gameArguments);
            ProcessBuilder builder = new ProcessBuilder(command).directory(
                    plan.gameDirectory().toFile()).redirectErrorStream(true);
            // Inherited switches must not override verified launch arguments.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            Process process;
            try { process = builder.start(); }
            catch (IOException exception) {
                removeGenerated(plan.runtimeDirectory);
                throw new IOException(t("launch.javaStartFailed"), exception);
            }
            RunningGame running = new RunningGame(process, plan.log);
            active = running;
            Thread.ofVirtual().name("leverage-game-output").start(
                    () -> collectOutput(plan, running, observer));
            return running;
        }
    }

    private static void collectOutput(LaunchPlan plan,
            RunningGame running, Listener listener) {
        try (Reader reader = new InputStreamReader(running.process.getInputStream(),
                StandardCharsets.UTF_8);
             BufferedWriter log = Files.newBufferedWriter(plan.log,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS)) {
            StringBuilder line = new StringBuilder();
            int c;
            boolean oversized = false;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    writeLine(oversized ? "[Línea de diagnóstico demasiado larga omitida]"
                            : line.toString(), plan.request.accessToken(), log, listener);
                    line.setLength(0);
                    oversized = false;
                } else if (line.length() >= 16_384) {
                    line.setLength(0);
                    oversized = true;
                } else if (c != '\r' && !oversized) line.append((char) c);
            }
            if (!line.isEmpty()) writeLine(line.toString(),
                    plan.request.accessToken(), log, listener);
        } catch (IOException ignored) {
            // Process completion must still be delivered when logging fails.
        }
        int code;
        try { code = running.process.waitFor(); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        try {
            if (plan.argfile != null) Files.deleteIfExists(plan.argfile);
            Files.deleteIfExists(plan.runtimeDirectory.resolve("classpath.jar"));
        } catch (IOException ignored) { }
        synchronized (PROCESS_LOCK) { if (active == running) active = null; }
        try { listener.exited(code); } catch (RuntimeException ignored) { }
        running.completion.complete(code);
    }
    private static void writeLine(String line, String token,
            BufferedWriter log, Listener listener) throws IOException {
        String sanitized = redact(line, token);
        log.write(sanitized);
        log.newLine();
        log.flush();
        try { listener.output(sanitized); } catch (RuntimeException ignored) { }
    }
    public static String redact(String text, String token) {
        String result = token != null && !token.isEmpty() && !token.equals("0")
                ? text.replace(token, "<redacted>") : text;
        result = result.replaceAll("(?i)(--accessToken(?:=|\\s+)|Bearer\\s+|" +
                "accessToken[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',}]+", "$1<redacted>");
        return result;
    }

    private JsonObject readVersion(Path root, String id,
            Set<String> visiting) throws IOException {
        requireId(id);
        if (visiting.size() >= 20 || !visiting.add(id)) throw new IOException(
                "Hay un ciclo o demasiados niveles de herencia entre versiones.");
        Path folder = safeResolve(root.resolve("versions"), id),
                path = folder.resolve(id + ".json");
        if (!Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS)) path = folder.resolve("version.json");
        requireFile(path);
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException(
                t("launch.descriptorTooLarge"));
        JsonObject child;
        try { child = JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (RuntimeException exception) {
            throw new IOException(I18n.t("launch.descriptorJson", id), exception);
        }
        String parent = string(child, "inheritsFrom", null);
        if (parent == null && child.has("jar") && !id.equals(string(child,
                "jar", id))) parent = required(child, "jar");
        JsonObject result = parent == null ? child.deepCopy()
                : merge(readVersion(root, parent, visiting), child);
        Path ownJar = folder.resolve(id + ".jar");
        if (!Files.isRegularFile(ownJar,
                LinkOption.NOFOLLOW_LINKS)) ownJar = folder.resolve("client.jar");
        if (Files.isRegularFile(ownJar, LinkOption.NOFOLLOW_LINKS)) {
            result.addProperty("_leverageClientJarPath", id + "/" + ownJar.getFileName());
            result.add("_leverageClientArtifact", object(object(child,
                    "downloads"), "client").deepCopy());
        } else if (parent == null) {
            result.addProperty("_leverageClientJarPath", id + "/" + id + ".jar");
            result.add("_leverageClientArtifact", object(object(child,
                    "downloads"), "client").deepCopy());
        }
        visiting.remove(id);
        return result;
    }
    static JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject result = parent.deepCopy();
        for (var entry : child.entrySet()) {
            if (entry.getKey().equals("libraries")) {
                LinkedHashMap<String, JsonElement> libraries = new LinkedHashMap<>();
                for (JsonElement library : array(parent, "libraries")) libraries.put(
                        libraryKey(library), library.deepCopy());
                for (JsonElement library : array(child, "libraries")) libraries.put(
                        libraryKey(library), library.deepCopy());
                JsonArray all = new JsonArray();
                libraries.values().forEach(all::add);
                result.add("libraries", all);
            } else if (entry.getKey().equals("arguments")
                    && entry.getValue().isJsonObject()) {
                JsonObject args = object(parent, "arguments").deepCopy();
                for (var arg : entry.getValue().getAsJsonObject().entrySet()) {
                    if (arg.getValue().isJsonArray()) {
                        JsonArray combined = array(args, arg.getKey()).deepCopy();
                        arg.getValue().getAsJsonArray().forEach(
                                value -> combined.add(value.deepCopy()));
                        args.add(arg.getKey(), combined);
                    } else args.add(arg.getKey(), arg.getValue().deepCopy());
                }
                result.add("arguments", args);
            } else if (entry.getValue().isJsonObject() && result.has(entry.getKey())
                    && result.get(entry.getKey()).isJsonObject()) {
                JsonObject combined = result.getAsJsonObject(entry.getKey()).deepCopy();
                entry.getValue().getAsJsonObject().entrySet().forEach(
                        item -> combined.add(item.getKey(), item.getValue().deepCopy()));
                result.add(entry.getKey(), combined);
            } else result.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return result;
    }
    private static String libraryKey(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        String[] coordinate = string(element.getAsJsonObject(),
                "name", element.toString()).split(":", -1);
        return coordinate.length < 3 ? element.toString() : coordinate[0] + ":"
                + coordinate[1] + (coordinate.length > 3 ? ":" + coordinate[3] : "");
    }
    private List<String> arguments(JsonObject arguments, String key,
            Map<String, String> substitutions) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement entry : array(arguments, key)) {
            if (entry.isJsonPrimitive()) result.add(expand(entry.getAsString(),
                    substitutions));
            else if (entry.isJsonObject() && LaunchRules.allows(
                    entry.getAsJsonObject(), environment)) {
                JsonElement value = entry.getAsJsonObject().get("value");
                if (value != null && value.isJsonArray()) for (JsonElement item
                        : value.getAsJsonArray()) result.add(expand(
                        item.getAsString(), substitutions));
                else if (value != null && value.isJsonPrimitive()) result.add(
                        expand(value.getAsString(), substitutions));
            }
        }
        return result;
    }
    static String expand(String source, Map<String,
            String> substitutions) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = substitutions.get(matcher.group(1));
            if (value == null) throw new IOException(I18n.t(
                    "launch.unsupportedParameter", matcher.group(1)));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        cleanArgument(result.toString());
        return result.toString();
    }
    static List<String> tokenize(String arguments) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if ((c == '"' || c == '\'') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                started = true;
            } else if (Character.isWhitespace(c) && quote == 0) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new IOException(t("launch.unclosedQuotes"));
        if (started) result.add(token.toString());
        return result;
    }
    static String quoteArgfile(String argument) {
        cleanArgument(argument);
        return "\"" + argument.replace("\\", "\\\\").replace("\"",
                "\\\"").replace("\t", "\\t") + "\"";
    }
    private static boolean containsClasspath(List<String> arguments) {
        return arguments.contains("-cp") || arguments.contains("-classpath")
                || arguments.contains("--class-path");
    }
    static List<String> java8Classpath(List<String> arguments,
            Path helperJar) throws IOException {
        List<String> result = new ArrayList<>(arguments);
        // A single unambiguous declaration prevents a helper from silently changing
        // which class path Java would use. Normal Mojang/Forge profiles have one.
        int declarations = 0;
        for (int i = 0; i < result.size(); i++) {
            String argument = result.get(i);
            if (!argument.equals("-cp") && !argument.equals("-classpath")
                    && !argument.equals("--class-path")) continue;
            if (++i >= result.size()) throw new IOException(
                    "Falta la ruta de bibliotecas de Java.");
            if (++declarations > 1) throw new IOException(t("launch.multipleClasspath"));
            List<String> entries = new ArrayList<>();
            for (String entry : result.get(i).split(Pattern.quote(
                    File.pathSeparator), -1)) {
                if (entry.isEmpty() || entry.contains("*"))
                        throw new IOException(t("launch.explicitClasspath"));
                Path file;
                try { file = Path.of(entry).toAbsolutePath().normalize(); }
                catch (InvalidPathException exception) {
                    throw new IOException(t("launch.invalidClasspath"), exception);
                }
                checkPath(file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Falta una biblioteca de Java: "
                        + file.getFileName());
                entries.add(file.toUri().toASCIIString());
            }
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
                    String.join(" ", entries));
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(
                    helperJar, StandardOpenOption.CREATE_NEW), manifest)) { }
            result.set(i - 1, "-cp");
            result.set(i, helperJar.toString());
        }
        if (declarations == 0) throw new IOException(t("launch.noClasspath"));
        return result;
    }
    static void checkWindowsCommandLength(List<String> arguments) throws IOException {
        if (windowsCommandLength(arguments) > 30_000)
                throw new IOException(t("launch.commandTooLong"));
    }
    private static long windowsCommandLength(List<String> arguments) {
        // Conservative bound for CreateProcess quoting, including doubled backslashes.
        long length = 1;
        for (String argument : arguments) {
            cleanArgument(argument);
            length += 3L + argument.length();
            for (int i = 0; i < argument.length(); i++) if (argument.charAt(i) == '\\'
                    || argument.charAt(i) == '"') length++;
        }
        return length;
    }
    private static void materializeResources(Path objects, Path resources,
            JsonObject index) throws IOException {
        JsonObject entries = object(index, "objects");
        Files.createDirectories(resources);
        checkPath(resources);
        for (var entry : entries.entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IOException(
                    t("launch.legacyDescriptor"));
            JsonObject descriptor = entry.getValue().getAsJsonObject();
            String hash = required(descriptor, "hash");
            if (!hash.matches("[a-fA-F0-9]{40}"))
                    throw new IOException(t("launch.legacyHash"));
            JsonObject verification = descriptor.deepCopy();
            verification.addProperty("sha1", hash);
            Path source = safeResolve(objects, hash.substring(0, 2) + "/" + hash),
                    target = safeResolve(resources, entry.getKey());
            verify(source, verification);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireFile(target);
                try {
                    verify(target, verification);
                    continue;
                } catch (IOException ignored) {
                    // Repair only this declared resource inside the isolated instance.
                }
            }
            Files.createDirectories(target.getParent());
            checkPath(target.getParent());
            Path partial = Files.createTempFile(target.getParent(),
                    ".leverage-asset-", ".part");
            try {
                Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
                verify(partial, verification);
                checkPath(target);
                try {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(partial); }
        }
    }
    static void verify(Path file, JsonObject artifact) throws IOException {
        requireFile(file);
        if (artifact.has("size") && artifact.get("size").getAsLong() >= 0
                && Files.size(file) != artifact.get("size").getAsLong())
                throw new IOException(I18n.t("launch.sizeMismatch", file.getFileName()));
        String sha1 = string(artifact, "sha1", null);
        if (sha1 != null) {
            if (!sha1.matches("(?i)[a-f0-9]{40}"))
                    throw new IOException(t("launch.invalidHash"));
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0)
                            if (count > 0) digest.update(buffer, 0, count);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha1))
                        throw new IOException(I18n.t("launch.corruptFile",
                        file.getFileName()));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
    static void extractNatives(Path jar, Path destination,
            List<String> excludes) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000) throw new IOException(
                        "El archivo de nativos contiene demasiadas entradas.");
                String name = entry.getName().replace('\\', '/');
                // Excluded entries still need traversal checks.
                Path output = safeResolve(destination, name);
                if (excludes.stream().anyMatch(name::startsWith)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                if (entry.getSize() > 200L * 1024 * 1024) throw new IOException(
                        "Una biblioteca nativa es demasiado grande.");
                Files.createDirectories(output.getParent());
                checkPath(output.getParent());
                try (OutputStream target = Files.newOutputStream(output,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    long size = 0;
                    while ((count = zip.read(buffer)) != -1) {
                        size += count;
                        total += count;
                        if (size > 200L * 1024 * 1024 || total > 1024L * 1024 * 1024)
                                throw new IOException(t("launch.nativesTooLarge"));
                        target.write(buffer, 0, count);
                    }
                }
            }
        }
    }
    static Path safeResolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0
                || relative.contains(":")) throw new IOException(t("launch.invalidPath"));
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || Arrays.asList(normalized.split("/")).contains(
                "..")) throw new IOException("La ruta intenta salir de su carpeta.");
        Path base = root.toAbsolutePath().normalize(),
                file = base.resolve(normalized).normalize();
        if (!file.startsWith(base) || file.equals(base))
                throw new IOException("La ruta intenta salir de su carpeta.");
        checkPath(file);
        return file;
    }
    private static void checkPath(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize(), current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException(
                    "Las rutas del juego no pueden atravesar enlaces.");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current)) throw new IOException(
                    "Las rutas del juego no pueden atravesar enlaces o redirecciones.");
        }
    }
    private static void requireFile(Path file) throws IOException {
        checkPath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(
                "Falta un archivo de Minecraft: " + file.getFileName());
    }
    private static String mavenPath(String coordinate) throws IOException {
        String[] extension = coordinate.split("@", -1),
                parts = extension[0].split(":", -1);
        if (parts.length < 3 || parts.length > 4)
                throw new IOException(t("launch.libraryCoordinate"));
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + (parts.length == 4 ? "-" + parts[3]
                : "") + "." + (extension.length > 1 ? extension[1] : "jar");
    }
    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key) : new JsonObject();
    }
    private static JsonArray array(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonArray()
                ? value.getAsJsonArray(key) : new JsonArray();
    }
    private static String string(JsonObject value, String key, String fallback) {
        return value.has(key) && value.get(key).isJsonPrimitive()
                ? value.get(key).getAsString() : fallback;
    }
    private static boolean booleanValue(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.get(
                key).getAsJsonPrimitive().isBoolean() && value.get(key).getAsBoolean();
    }
    private static String required(JsonObject value, String key) throws IOException {
        String result = string(value, key, null);
        if (result == null || result.isBlank()) throw new IOException(
                "El descriptor no contiene " + key + ".");
        return result;
    }
    private static void requireId(String id) {
        if (!dev.leverage.client.VersionId.isValid(id))
                throw new IllegalArgumentException(t("launch.invalidVersion"));
    }
    private static void cleanArgument(String value) {
        if (value == null || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new IllegalArgumentException(t("launch.invalidArgument"));
    }
    private static int probeJava(Path executable) throws IOException,
            InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(),
                "-version").redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(t("launch.javaTimeout"));
            }
            String output = new String(process.getInputStream().readNBytes(16_384),
                    StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(
                    "(?:openjdk|java) version \\\"(?:1\\.)?(\\d+)").matcher(output);
            if (process.exitValue() != 0 || !matcher.find())
                    throw new IOException(t("launch.javaUnknown"));
            return Integer.parseInt(matcher.group(1));
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            throw exception;
        }
    }
    private static void removeGenerated(Path runtime) {
        // Remove only this run-* directory, never its instance or parent.
        if (runtime == null || !runtime.getFileName().toString().startsWith(
                "run-")) return;
        try (var paths = Files.walk(runtime)) {
            for (Path path : paths.sorted(
                    Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
    @FunctionalInterface interface JavaProbe {
        int major(Path executable) throws IOException, InterruptedException;
    }
    private record NativeJar(Path path, List<String> excludes) { }
}

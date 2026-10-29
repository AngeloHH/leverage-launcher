package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.*;
import dev.leverage.launch.RuntimePlatform;
import dev.leverage.launch.LaunchRules;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

// Install the requested Java component independently of the game source.
public final class JavaRuntimeInstaller {
    public static final URI OFFICIAL_CATALOG = URI.create(
            "https://piston-meta.mojang.com/v1/products/java-runtime/"
            + "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json");
    private final ArtifactDownloads downloads = new ArtifactDownloads();
    private final URI officialCatalog;
    private final RuntimePlatform hostPlatform;
    public JavaRuntimeInstaller() { this(OFFICIAL_CATALOG); }
    JavaRuntimeInstaller(URI officialCatalog) {
        this(officialCatalog, RuntimePlatform.current());
    }
    JavaRuntimeInstaller(URI officialCatalog, RuntimePlatform hostPlatform) {
        this.officialCatalog = officialCatalog;
        this.hostPlatform = Objects.requireNonNull(hostPlatform);
    }

    public record Installation(Path executable, RuntimePlatform platform) {
        public LaunchRules.Environment environment() { return platform.environment(); }
    }

    public Path ensureRuntime(String component, int major, Path minecraftDirectory,
            LauncherPreferences.DownloadSource source, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        return ensureInstallation(component, major, minecraftDirectory,
                source, progress, cancellation).executable();
    }

    public Installation ensureInstallation(String component, int major,
            Path minecraftDirectory, LauncherPreferences.DownloadSource source,
            ProgressListener progress, CancellationToken cancellation) throws IOException,
            InterruptedException {
        GameInstaller.validateId(component);
        if (major < 8 || major > 100) throw new IOException(t("java.invalidVersion"));
        RuntimePlatform machine = hostPlatform, selectedPlatform = null;
        progress.onProgress(I18n.t("progress.javaQuery",
                Integer.toString(major), component), 0, -1);
        JsonObject selected = null;
        if (source.useDefaults()) {
            JsonObject catalog = downloads.json(officialCatalog, cancellation);
            for (RuntimePlatform candidate : machine.candidates()) {
                JsonObject entry = catalog.getAsJsonObject(candidate.id());
                JsonArray builds = entry == null ? null : entry.getAsJsonArray(component);
                if (builds == null || builds.isEmpty()) continue;
                selected = java.util.stream.StreamSupport.stream(builds.spliterator(),
                        false).map(JsonElement::getAsJsonObject).max(
                        Comparator.comparing(build -> build.has("version")
                        ? GameInstaller.value(build.getAsJsonObject("version"),
                        "released", "") : "")).orElseThrow();
                selectedPlatform = candidate;
                break;
            }
        } else {
            JsonObject catalog = downloads.json(ArtifactDownloads.append(source.baseUrl(),
                    "mirror/versions/index.json"), cancellation);
            JsonArray runtimes = catalog.getAsJsonArray("javaRuntimes");
            if (runtimes != null) for (RuntimePlatform candidate : machine.candidates()) {
                for (JsonElement item : runtimes) {
                    JsonObject runtime = item.getAsJsonObject();
                    if (candidate.id().equals(GameInstaller.value(runtime, "platform",
                            "")) && component.equals(GameInstaller.value(runtime,
                            "component", ""))) {
                        selected = runtime;
                        selectedPlatform = candidate;
                        break;
                    }
                }
                if (selected != null) break;
            }
        }
        if (selected == null) {
            // Reuse an exact Java match; never switch download sources.
            if (Runtime.version().feature() == major) {
                Path home = Path.of(System.getProperty("java.home")).toRealPath();
                Path installed = home.resolve(machine.os().equals("windows")
                        ? "bin/java.exe" : "bin/java");
                if (Files.isRegularFile(installed))
                        return new Installation(installed, machine);
            }
            throw new IOException(t(source.useDefaults() ? "java.noOfficial"
                    : "java.mirrorMissing", component, machine.id()));
        }
        String platform = selectedPlatform.id();
        if (machine == RuntimePlatform.MAC_ARM64 && selectedPlatform ==
                RuntimePlatform.MAC_X64) progress.onProgress(t("java.usingIntel"), 0, -1);
        if (selected.has("version")) {
            String name = GameInstaller.value(selected.getAsJsonObject(
                    "version"), "name", "");
            int declared = javaMajor(name);
            if (declared != 0 && declared != major) throw new IOException(
                    I18n.t("java.wrongMajor", declared, major));
        }
        Path runtimeRoot = ArtifactDownloads.safeResolve(minecraftDirectory.resolve(
                "leverage/runtimes"), platform + "/" + component);
        JsonObject runtime;
        if (source.useDefaults()) {
            JsonObject manifest = selected.getAsJsonObject("manifest");
            if (manifest == null) throw new IOException(t("java.missingManifest"));
            runtime = downloads.verifiedJson(manifest,
                    ArtifactDownloads.safeResolve(minecraftDirectory.resolve(
                    "leverage/manifests"), ArtifactDownloads.string(manifest,
                    "sha1") + ".json"), progress, cancellation);
        } else runtime = downloads.json(URI.create(ArtifactDownloads.string(selected,
                "url")), cancellation);
        JsonObject files = runtime.getAsJsonObject("files");
        if (files == null || files.size() > 20000)
                throw new IOException(t("java.invalidManifest"));
        Files.createDirectories(runtimeRoot);
        List<Map.Entry<String, JsonElement>> links = new ArrayList<>();
        List<Future<?>> tasks = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
                cancellation.throwIfCancelled();
                Path target = ArtifactDownloads.safeResolve(runtimeRoot, entry.getKey());
                JsonObject file = entry.getValue().getAsJsonObject();
                switch (ArtifactDownloads.string(file, "type")) {
                    case "directory" -> Files.createDirectories(target);
                    case "link" -> links.add(entry);
                    case "file" -> {
                        JsonObject raw = file.has("downloads") ? file.getAsJsonObject(
                                "downloads").getAsJsonObject("raw") : null;
                        if (raw == null) throw new IOException(I18n.t("java.noRaw",
                                entry.getKey()));
                        tasks.add(executor.submit(() -> {
                            downloads.ensure(raw, target, progress, cancellation);
                            if (file.has("executable") && file.get(
                                    "executable").getAsBoolean() && !platform.startsWith(
                                    "windows") && !target.toFile().setExecutable(true,
                                    true)) throw new IOException(I18n.t(
                                    "java.executableError", target.getFileName()));
                            return null;
                        }));
                    }
                    default -> throw new IOException(t("java.unknownFile"));
                }
            }
            try { for (Future<?> task : tasks) task.get(); }
            catch (ExecutionException failure) {
                tasks.forEach(task -> task.cancel(true));
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                throw new IOException(t("java.installFailed"), cause);
            } catch (InterruptedException interrupted) {
                tasks.forEach(task -> task.cancel(true));
                throw interrupted;
            }
        }
        // Copy runtime file links safely; Windows symlinks need extra privileges.
        for (Map.Entry<String, JsonElement> entry : links) {
            cancellation.throwIfCancelled();
            Path link = ArtifactDownloads.safeResolve(runtimeRoot, entry.getKey());
            String relative = ArtifactDownloads.string(
                    entry.getValue().getAsJsonObject(), "target");
            if (relative.contains(":") || relative.contains("\\") || relative.startsWith(
                    "/")) throw new IOException(t("java.linkDestination"));
            Path target = link.getParent().resolve(relative).normalize();
            if (!target.startsWith(runtimeRoot) || !Files.isRegularFile(target,
                    LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(t("java.linkOutside"));
            Files.createDirectories(link.getParent());
            Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING);
        }
        Path release = runtimeRoot.resolve(selectedPlatform.releasePath());
        if (Files.isRegularFile(release)) {
            String content = Files.readString(release);
            var match = Pattern.compile("(?m)^JAVA_VERSION=\"([^\"]+)\"").matcher(
                    content);
            if (match.find() && javaMajor(match.group(1)) != major)
                    throw new IOException(t("java.versionMismatch"));
        }
        Path executable = runtimeRoot.resolve(selectedPlatform.javaPath());
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS))
                throw new IOException(t("java.missingExecutable"));
        cancellation.throwIfCancelled();
        progress.onProgress(I18n.t("progress.javaVerified",
                Integer.toString(major)), 1, 1);
        return new Installation(executable, selectedPlatform);
    }

    static String platform() throws IOException {
        try { return RuntimePlatform.current().id(); }
        catch (IllegalArgumentException unsupported) {
            throw new IOException(t("java.noInstaller"), unsupported);
        }
    }
    static int javaMajor(String version) {
        var matcher = Pattern.compile("^(?:1\\.)?(\\d+)").matcher(version);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}

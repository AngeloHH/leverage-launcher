package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.*;
import dev.leverage.launch.LaunchRules;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

// Install verified Mojang artifacts without sending account credentials.
public final class GameInstaller {
    public static final URI OFFICIAL_CATALOG = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final URI OFFICIAL_ASSETS = URI.create(
            "https://resources.download.minecraft.net");
    private static final URI OFFICIAL_LIBRARIES = URI.create(
            "https://libraries.minecraft.net");
    private static final URI FORGE_LIBRARIES = URI.create(
            "https://maven.minecraftforge.net");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();
    private final ArtifactDownloads downloads = new ArtifactDownloads();
    private final URI officialCatalog;
    private final URI mojangLibraries, forgeLibraries;
    public GameInstaller() { this(OFFICIAL_CATALOG); }
    GameInstaller(URI officialCatalog) {
        this(officialCatalog, OFFICIAL_LIBRARIES, FORGE_LIBRARIES);
    }
    GameInstaller(URI officialCatalog, URI mojangLibraries, URI forgeLibraries) {
        this.officialCatalog = officialCatalog;
        this.mojangLibraries = mojangLibraries;
        this.forgeLibraries = forgeLibraries;
    }

    public record VersionInfo(String id, String type, String releaseTime) { }
    public record InstalledVersion(Path descriptor,
            String javaComponent, int javaMajor) { }
    public record GameIdentity(String minecraftVersion, String loader) { }
    @FunctionalInterface public interface RuntimeSelector {
        LaunchRules.Environment select(String component,
                int major) throws IOException, InterruptedException;
    }

    // Policy uses game/loader identity; isolation keeps the full profile ID.
    public static GameIdentity identity(Path descriptor) throws IOException {
        if (!Files.isRegularFile(descriptor)||Files.size(descriptor)>16L*1024* 1024)
                throw new IOException(t("installer.identityDescriptor"));
        try (Reader reader=Files.newBufferedReader(descriptor)) {
            JsonObject version=JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject metadata=version.has("leverage")
                    ?version.getAsJsonObject("leverage"):new JsonObject();
            return new GameIdentity(value(metadata, "gameVersion",
                    ArtifactDownloads.string(version, "id")), value(metadata,
                    "loader", detectLoader(version)));
        } catch (RuntimeException invalid) {
            throw new IOException(t("installer.invalidIdentity"), invalid);
        }
    }

    public List<VersionInfo> catalog(LauncherPreferences
            .DownloadSources sources) throws IOException, InterruptedException {
        return catalog(sources, CancellationToken.NONE);
    }
    public List<VersionInfo> catalog(LauncherPreferences.DownloadSources sources,
            CancellationToken cancellation) throws IOException, InterruptedException {
        List<VersionInfo> versions = new ArrayList<>();
        JsonObject catalog = downloads.json(catalogUri(sources.versions()), cancellation);
        JsonArray values = catalog.getAsJsonArray("versions");
        if (values == null) throw new IOException(t("installer.noVersions"));
        for (JsonElement item : values) {
            JsonObject entry = item.getAsJsonObject();
            String id = ArtifactDownloads.string(entry, "id");
            validateId(id);
            versions.add(new VersionInfo(id, value(entry, "type",
                    "unknown"), value(entry, "releaseTime", "")));
        }
        return List.copyOf(versions);
    }

    public InstalledVersion install(String id, Path minecraftDirectory,
            LauncherPreferences.DownloadSources sources, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        return install(id, minecraftDirectory, sources, progress, cancellation,
                (component, major) -> LaunchRules.Environment.current());
    }

    public InstalledVersion install(String id, Path minecraftDirectory,
            LauncherPreferences.DownloadSources sources,
            ProgressListener progress, CancellationToken cancellation,
            RuntimeSelector runtimeSelector) throws IOException, InterruptedException {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(cancellation);
        validateId(id);
        Path root = minecraftDirectory.toAbsolutePath().normalize();
        progress.onProgress(t("progress.catalog"), 0, -1);
        JsonObject catalog = downloads.json(catalogUri(sources.versions()), cancellation);
        Map<String, JsonObject> descriptors = new HashMap<>();
        JsonArray entries = catalog.getAsJsonArray("versions");
        if (entries == null) throw new IOException(t("installer.noVersions"));
        for (JsonElement entry : entries) descriptors.put(ArtifactDownloads.string(
                entry.getAsJsonObject(), "id"), entry.getAsJsonObject());
        JsonObject version = resolve(id, root, descriptors, Map.of(),
                new LinkedHashSet<>(), progress, cancellation);
        JsonObject javaVersion = version.getAsJsonObject("javaVersion");
        String javaComponent = javaVersion == null ? "jre-legacy"
                : ArtifactDownloads.string(javaVersion, "component");
        int javaMajor = javaVersion == null ? 8 : javaVersion.get(
                "majorVersion").getAsInt();
        LaunchRules.Environment environment = Objects.requireNonNull(
                runtimeSelector.select(javaComponent, javaMajor));
        JsonObject client = version.has("downloads") ? version.getAsJsonObject(
                "downloads").getAsJsonObject("client") : null;
        if (client == null) throw new IOException(t("installer.noClient"));
        Path versionRoot = ArtifactDownloads.safeResolve(root.resolve("versions"), id);
        downloads.ensure(client, versionRoot.resolve(id + ".jar"),
                progress, cancellation);

        JsonArray libraries = version.getAsJsonArray("libraries");
        if (libraries != null) for (JsonElement item : libraries) {
            cancellation.throwIfCancelled();
            JsonObject library = item.getAsJsonObject();
            if (!LaunchRules.allows(library, environment)) continue;
            if (library.has("clientreq") && !library.get(
                    "clientreq").getAsBoolean()) continue;
            JsonObject artifacts = library.getAsJsonObject("downloads");
            if (artifacts == null) {
                artifacts = legacyLibraryDownloads(library, environment, cancellation);
                library.add("downloads", artifacts);
            }
            if (artifacts.has("artifact")) installLibrary(artifacts.getAsJsonObject(
                    "artifact"), root, progress, cancellation);
            JsonObject natives = library.getAsJsonObject("natives");
            if (natives != null && natives.has(environment.osName())) {
                String classifier = natives.get(
                        environment.osName()).getAsString().replace("${arch}",
                        environment.architecture().contains("64") ? "64" : "32");
                JsonObject classifiers = artifacts.getAsJsonObject("classifiers");
                if (classifiers == null || !classifiers.has(classifier))
                        throw new IOException(I18n.t("installer.missingNative",
                        classifier));
                installLibrary(classifiers.getAsJsonObject(classifier),
                        root, progress, cancellation);
            }
        }

        if (version.has("logging")) {
            JsonObject logging = version.getAsJsonObject(
                    "logging").getAsJsonObject("client");
            if (logging != null && logging.has("file")) {
                JsonObject file = logging.getAsJsonObject("file");
                downloads.ensure(file, ArtifactDownloads.safeResolve(root.resolve(
                        "assets/log_configs"), ArtifactDownloads.string(file,
                        "id")), progress, cancellation);
            }
        }
        if (version.has("assetIndex")) installAssets(version,
                root, progress, cancellation);
        cancellation.throwIfCancelled();
        version.addProperty("id", id);
        version.remove("inheritsFrom");
        version.remove("jar");
        Path destination = versionRoot.resolve(id + ".json");
        Files.createDirectories(versionRoot);
        Path temporary = versionRoot.resolve(id + ".json." + UUID.randomUUID() + ".part");
        try {
            Files.writeString(temporary, GSON.toJson(version),
                    StandardOpenOption.CREATE_NEW);
            ArtifactDownloads.move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
        progress.onProgress(t("progress.minecraftVerified", id), 1, 1);
        return new InstalledVersion(destination, javaComponent, javaMajor);
    }

    // Resolve a supplied profile using the same inheritance rules as installation.
    public JsonObject resolveDescriptor(Path descriptor) throws IOException,
            InterruptedException {
        Path file = descriptor.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || Files.size(file) > 16L * 1024 * 1024)
                throw new IOException(I18n.t("installer.badDescriptor", file));
        JsonObject supplied;
        try (Reader reader = Files.newBufferedReader(file)) {
            supplied = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String id = ArtifactDownloads.string(supplied, "id");
        validateId(id);
        Path directory = file.getParent();
        Path root = directory.getParent() != null
                && directory.getParent().getFileName().toString().equals("versions")
                ? directory.getParent().getParent() : directory;
        return resolve(id, root, new HashMap<>(), Map.of(id, supplied),
                new LinkedHashSet<>(), ProgressListener.NONE, CancellationToken.NONE);
    }

    private JsonObject resolve(String id, Path root, Map<String,
            JsonObject> entries, Map<String, JsonObject> supplied,
            Set<String> visiting, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        validateId(id);
        if (!visiting.add(id) || visiting.size() > 16)
                throw new IOException(t("installer.inheritance"));
        JsonObject version;
        JsonObject descriptor = entries.get(id);
        if (supplied.containsKey(id)) {
            version = supplied.get(id).deepCopy();
        } else if (descriptor != null) {
            Path cached = ArtifactDownloads.safeResolve(root.resolve(
                    "leverage/manifests"), ArtifactDownloads.string(descriptor,
                    "sha1") + ".json");
            version = downloads.verifiedJson(descriptor, cached, progress, cancellation);
        } else {
            Path existing = ArtifactDownloads.safeResolve(root.resolve("versions"),
                    id + "/" + id + ".json");
            if (!supplied.isEmpty() && !Files.isRegularFile(existing)) {
                Path sibling = ArtifactDownloads.safeResolve(root, id + ".json");
                if (Files.isRegularFile(sibling)) existing = sibling;
            }
            if (Files.isRegularFile(existing) && Files.size(
                    existing) <= 16L * 1024 * 1024) {
                try (Reader reader = Files.newBufferedReader(existing)) {
                    version = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else if (!supplied.isEmpty()) {
                // Look up only a missing parent, never the supplied profile.
                JsonArray available = downloads.json(officialCatalog,
                        cancellation).getAsJsonArray("versions");
                if (available != null) for (JsonElement entry : available) entries.put(
                        ArtifactDownloads.string(entry.getAsJsonObject(),
                        "id"), entry.getAsJsonObject());
                JsonObject parent = entries.get(id);
                if (parent == null) throw new IOException(I18n.t(
                        "installer.missingParent", id));
                Path cached = ArtifactDownloads.safeResolve(root.resolve(
                        "leverage/manifests"), ArtifactDownloads.string(parent,
                        "sha1") + ".json");
                version = downloads.verifiedJson(parent, cached, progress, cancellation);
            } else throw new IOException(I18n.t("installer.versionMissing", id));
        }
        String parentId = version.has("inheritsFrom") ? ArtifactDownloads.string(version,
                "inheritsFrom") : version.has("jar")
                && !id.equals(ArtifactDownloads.string(version,
                "jar")) ? ArtifactDownloads.string(version, "jar") : null;
        String gameVersion=version.has("leverage")?value(version.getAsJsonObject(
                "leverage"), "gameVersion", id):id;
        if (parentId != null) {
            JsonObject parent = resolve(parentId, root, entries,
                    supplied, visiting, progress, cancellation);
            gameVersion=value(parent.getAsJsonObject("leverage"),
                    "gameVersion", parentId);
            version = merge(parent, version);
        }
        JsonObject metadata=version.has("leverage")
                ?version.getAsJsonObject("leverage") :new JsonObject();
        metadata.addProperty("gameVersion", gameVersion);
        metadata.addProperty("loader", detectLoader(version));
        version.add("leverage", metadata);
        visiting.remove(id);
        return version;
    }

    private static String detectLoader(JsonObject version) {
        String main=value(version, "mainClass", "");
        if (main.startsWith("net.fabricmc."))return "fabric";
        if (main.startsWith("org.quiltmc."))return "quilt";
        String libraries=version.has("libraries")
                ?version.getAsJsonArray("libraries").toString():"";
        if (libraries.contains("net.neoforged:"))return "neoforge";
        if (libraries.contains("net.minecraftforge:")||value(version,
                "minecraftArguments", "").contains(
                "cpw.mods.fml.common.launcher.FMLTweaker"))return "forge";
        return "default";
    }

    private void installLibrary(JsonObject artifact, Path root, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        downloads.ensure(artifact, ArtifactDownloads.safeResolve(
                root.resolve("libraries"), ArtifactDownloads.string(artifact,
                "path")), progress, cancellation);
    }

    // Resolve old Forge coordinates and hashes from official repositories.
    public JsonObject legacyLibraryDownloads(JsonObject library,
            CancellationToken cancellation) throws IOException, InterruptedException {
        return legacyLibraryDownloads(library,
                LaunchRules.Environment.current(), cancellation);
    }

    public JsonObject legacyLibraryDownloads(JsonObject library,
            LaunchRules.Environment environment,
            CancellationToken cancellation) throws IOException, InterruptedException {
        String coordinate = ArtifactDownloads.string(library, "name");
        JsonObject result = new JsonObject();
        JsonObject natives = library.getAsJsonObject("natives");
        if (natives == null) result.add("artifact", legacyArtifact(library,
                coordinate, null, cancellation));
        if (natives != null && natives.has(environment.osName())) {
            String classifier = natives.get(environment.osName()).getAsString().replace(
                    "${arch}", environment.architecture().contains("64") ? "64" : "32");
            JsonObject classifiers = new JsonObject();
            classifiers.add(classifier, legacyArtifact(library,
                    coordinate, classifier, cancellation));
            result.add("classifiers", classifiers);
        }
        return result;
    }

    private JsonObject legacyArtifact(JsonObject library, String coordinate,
            String classifier, CancellationToken cancellation) throws IOException,
            InterruptedException {
        String path = mavenPath(coordinate, classifier);
        List<URI> repositories;
        if (library.has("url")) {
            URI declared;
            try { declared = URI.create(ArtifactDownloads.string(library, "url")); }
            catch (IllegalArgumentException invalid) {
                throw new IOException(t("installer.badRepository"), invalid);
            }
            String host = declared.getHost();
            if (host != null && (host.equalsIgnoreCase(
                    "files.minecraftforge.net") || host.equalsIgnoreCase(
                    "maven.minecraftforge.net"))) repositories = List.of(forgeLibraries);
            else if (host != null && host.equalsIgnoreCase(
                    "libraries.minecraft.net")) repositories = List.of(mojangLibraries);
            else if ("https".equalsIgnoreCase(declared.getScheme()) && host != null
                    && declared.getUserInfo() == null && declared.getQuery() == null
                    && declared.getFragment() == null) repositories = List.of(declared);
            else throw new IOException(I18n.t("installer.badRepositoryNamed",
                    coordinate));
        } else repositories = List.of(mojangLibraries, forgeLibraries);
        // Legacy Forge stores its universal JAR under the ordinary Maven name.
        String remotePath = coordinate.startsWith("net.minecraftforge:forge:")
                && coordinate.split(":", -1).length == 3 ? path.substring(0,
                path.length() - 4) + "-universal.jar" : path;
        IOException missing = null;
        for (URI repository : repositories) {
            URI uri = ArtifactDownloads.append(repository, remotePath);
            try {
                String hash = downloads.sha1(URI.create(uri + ".sha1"), cancellation);
                JsonObject artifact = new JsonObject();
                artifact.addProperty("path", path);
                artifact.addProperty("url", uri.toString());
                artifact.addProperty("sha1", hash);
                return artifact;
            } catch (ArtifactDownloads.HttpStatusException notFound) {
                if (notFound.status() != 404) throw notFound;
                // Some original Forge archives still exist but their .sha1 sidecars
                // were removed. The verified installer profile publishes their hashes.
                // This is not permission to accept an unverified JAR.
                if (library.has("checksums")) {
                    JsonArray published=library.getAsJsonArray("checksums");
                    if (published.isEmpty())throw new IOException(
                            t("installer.noChecksums"));
                    for (JsonElement digest:published)if (!digest.isJsonPrimitive()
                            ||!digest.getAsString().matches("(?i)[a-f0-9]{40}"))
                            throw new IOException(t("installer.badChecksum"));
                    JsonObject artifact=new JsonObject();
                    artifact.addProperty("path", path);
                    artifact.addProperty("url", uri.toString());
                    artifact.addProperty("sha1", published.get(
                            0).getAsString().toLowerCase(Locale.ROOT));
                    return artifact;
                }
                missing = notFound;
            }
        }
        throw new IOException(I18n.t("installer.missingHash", coordinate), missing);
    }

    static String mavenPath(String coordinate, String classifier) throws IOException {
        String[] extension = coordinate.split("@", -1),
                parts = extension[0].split(":", -1);
        if (extension.length > 2 || parts.length < 3 || parts.length > 4)
                throw new IOException(t("installer.mavenCoordinate"));
        for (String part : parts) if (!part.matches("[A-Za-z0-9_.+\\-]+")
                || part.contains("..")) throw new IOException(
                t("installer.mavenCoordinate"));
        String type = extension.length == 2 ? extension[1] : "jar";
        if (!type.matches("[A-Za-z0-9]+")) throw new IOException(
                t("installer.mavenExtension"));
        String chosen = classifier != null ? classifier
                : parts.length == 4 ? parts[3] : null;
        if (chosen != null && (!chosen.matches("[A-Za-z0-9_.+\\-]+") || chosen.contains(
                ".."))) throw new IOException(t("installer.mavenClassifier"));
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                + "/" + parts[1] + "-" + parts[2] + (chosen == null
                ? "" : "-" + chosen) + "." + type;
    }

    private void installAssets(JsonObject version, Path root, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        JsonObject descriptor = version.getAsJsonObject("assetIndex");
        String id = ArtifactDownloads.string(descriptor, "id");
        validateId(id);
        JsonObject index = downloads.verifiedJson(descriptor,
                ArtifactDownloads.safeResolve(root.resolve("assets"),
                "indexes/" + id + ".json"), progress, cancellation);
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects == null) throw new IOException(t("installer.resourceIndex"));
        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean resources = index.has("map_to_resources")
                && index.get("map_to_resources").getAsBoolean();
        // Validate every name before downloads, including aliases of one hash.
        for (String name : objects.keySet()) ArtifactDownloads.safeResolve(
                root.resolve("assets/virtual").resolve(id), name);
        URI assetBase = version.has("leverage") && version.getAsJsonObject(
                "leverage").has("assetBaseUrl") ? URI.create(version.getAsJsonObject(
                "leverage").get("assetBaseUrl").getAsString()) : null;
        List<Future<?>> futures = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (Map.Entry<String, JsonElement> item : objects.entrySet()) {
                JsonObject asset = item.getValue().getAsJsonObject();
                String hash = ArtifactDownloads.string(asset,
                        "hash").toLowerCase(Locale.ROOT);
                if (!hash.matches("[a-fA-F0-9]{40}"))
                        throw new IOException(t("installer.resourceHash"));
                if (!asset.has("size") || asset.get("size").getAsLong() < 0)
                        throw new IOException(t("installer.resourceSize"));
                Path target = ArtifactDownloads.safeResolve(root.resolve("assets"),
                        "objects/" + hash.substring(0, 2) + "/" + hash);
                JsonObject download = new JsonObject();
                download.addProperty("sha1", hash);
                download.add("size", asset.get("size"));
                URI uri = assetBase == null ? ArtifactDownloads.append(OFFICIAL_ASSETS,
                        hash.substring(0, 2) + "/" + hash) : ArtifactDownloads.append(
                        assetBase, encodePath(item.getKey()));
                download.addProperty("url", uri.toString());
                if (hashes.add(hash)) futures.add(executor.submit(() -> {
                    downloads.ensure(download, target, progress, cancellation);
                    return null;
                }));
            }
            try {
                for (Future<?> future : futures) future.get();
            } catch (ExecutionException failure) {
                futures.forEach(future -> future.cancel(true));
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                throw new IOException(t("installer.resourcesFailed"), cause);
            } catch (InterruptedException interrupted) {
                futures.forEach(future -> future.cancel(true));
                throw interrupted;
            }
        }
        if (virtual || resources) for (Map.Entry<String,
                JsonElement> item : objects.entrySet()) {
            cancellation.throwIfCancelled();
            JsonObject asset = item.getValue().getAsJsonObject();
            String hash = ArtifactDownloads.string(asset,
                    "hash").toLowerCase(Locale.ROOT);
            Path source = ArtifactDownloads.safeResolve(root.resolve("assets/objects"),
                    hash.substring(0, 2) + "/" + hash);
            if (virtual) downloads.materialize(source, ArtifactDownloads.safeResolve(
                    root.resolve("assets/virtual").resolve(id), item.getKey()),
                    hash, asset.get("size").getAsLong(), progress, cancellation);
            if (resources) downloads.materialize(source, ArtifactDownloads.safeResolve(
                    root.resolve("resources"), item.getKey()), hash,
                    asset.get("size").getAsLong(), progress, cancellation);
        }
    }

    static JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject result = parent.deepCopy();
        for (Map.Entry<String, JsonElement> item : child.entrySet()) {
            String key = item.getKey();
            JsonElement prior = result.get(key), next = item.getValue();
            if (key.equals("libraries") && prior != null) {
                LinkedHashMap<String, JsonElement> libraries = new LinkedHashMap<>();
                for (JsonElement library : prior.getAsJsonArray()) libraries.put(
                        libraryKey(library), library);
                for (JsonElement library : next.getAsJsonArray()) libraries.put(
                        libraryKey(library), library.deepCopy());
                JsonArray merged = new JsonArray();
                libraries.values().forEach(merged::add);
                result.add(key, merged);
            } else if (prior != null && prior.isJsonObject()
                    && next.isJsonObject()) result.add(key,
                    merge(prior.getAsJsonObject(), next.getAsJsonObject()));
            else if ((key.equals("game") || key.equals("jvm")) && prior != null
                    && prior.isJsonArray() && next.isJsonArray()) {
                JsonArray merged = prior == null ? new JsonArray()
                        : prior.getAsJsonArray().deepCopy();
                next.getAsJsonArray().forEach(merged::add);
                result.add(key, merged);
            } else result.add(key, next.deepCopy());
        }
        return result;
    }
    private static String libraryKey(JsonElement library) {
        String name = library.getAsJsonObject().get("name").getAsString();
        String[] parts = name.split(":", -1);
        return parts.length < 3 ? name : parts[0] + ":" + parts[1]
                + (parts.length > 3 ? ":" + parts[3] : "");
    }
    private URI catalogUri(LauncherPreferences.DownloadSource source) {
        return source.useDefaults() ? officialCatalog : ArtifactDownloads.append(
                source.baseUrl(), "game/version_manifest_v2.json");
    }
    static void validateId(String id) throws IOException {
        if (!VersionId.isValid(id)) throw new IOException(I18n.t(
                "installer.invalidVersionId", id));
    }
    static String value(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }
    private static String encodePath(String path) throws IOException {
        try {
            return new URI(null, null, "/" + path, null).getRawPath().substring(1);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(t("installer.resourcePath"), e);
        }
    }
}

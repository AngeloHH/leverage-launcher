package dev.leverage.mirror;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.client.CancellationToken;
import dev.leverage.client.GameInstaller;
import dev.leverage.launch.RuntimePlatform;
import dev.leverage.launch.LaunchRules;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public final class MirrorSynchronizer {
    public static final URI VERSION_MANIFEST = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    public static final URI JAVA_RUNTIME_MANIFEST = URI.create(
            "https://piston-meta.mojang.com/v1/products/java-runtime/"
            + "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();

    private final MirrorConfig config;
    private final MirrorRepository repository;
    private final URI publicBase;
    private final URI versionManifest;
    private final URI assetBase;
    private final URI javaRuntimeManifest;
    private final HttpClient client;
    private final ConcurrentHashMap<String, Object> hashLocks = new ConcurrentHashMap<>();
    private final Semaphore downloads = new Semaphore(8);
    private JsonObject javaCatalog;

    public MirrorSynchronizer(MirrorConfig config,
            MirrorRepository repository, URI publicBase) {
        this(config, repository, publicBase, VERSION_MANIFEST, URI.create(
                "https://resources.download.minecraft.net"), JAVA_RUNTIME_MANIFEST);
    }

    MirrorSynchronizer(MirrorConfig config, MirrorRepository repository,
            URI publicBase, URI versionManifest) {
        this(config, repository, publicBase, versionManifest, URI.create(
                "https://resources.download.minecraft.net"), JAVA_RUNTIME_MANIFEST);
    }

    MirrorSynchronizer(MirrorConfig config, MirrorRepository repository,
            URI publicBase, URI versionManifest, URI assetBase) {
        this(config, repository, publicBase, versionManifest,
                assetBase, JAVA_RUNTIME_MANIFEST);
    }

    MirrorSynchronizer(MirrorConfig config, MirrorRepository repository, URI publicBase,
            URI versionManifest, URI assetBase, URI javaRuntimeManifest) {
        this.config = config;
        this.repository = repository;
        this.publicBase = normalizePublicBase(publicBase);
        this.versionManifest = versionManifest;
        this.assetBase = assetBase;
        this.javaRuntimeManifest = javaRuntimeManifest;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public SyncResult synchronize() throws IOException,
            InterruptedException, SQLException {
        javaCatalog = null;
        Map<String, JsonObject> available = new java.util.HashMap<>();
        if (config.versions().stream().anyMatch(spec -> spec.descriptor() == null)) {
            JsonObject globalManifest = fetchJson(versionManifest);
            for (JsonElement value : globalManifest.getAsJsonArray("versions")) {
                JsonObject version = value.getAsJsonObject();
                available.put(version.get("id").getAsString(), version);
            }
        }

        JsonArray catalogVersions = new JsonArray();
        // Retain published versions outside this sync's selected entries.
        JsonObject previous = Files.isRegularFile(repository.versionsDirectory().resolve(
                "index.json")) ? parseJson(repository.versionsDirectory().resolve(
                "index.json")) : new JsonObject();
        if (previous.has("versions")) for (JsonElement entry
                : previous.getAsJsonArray("versions")) {
            String id = requiredString(entry.getAsJsonObject(), "id");
            if (config.versions().stream().noneMatch(spec -> spec.id().equals(
                    id))) catalogVersions.add(entry.deepCopy());
        }
        Set<String> javaComponents = new LinkedHashSet<>(
                config.javaRuntime().components());
        int[] counters = new int[2];
        for (MirrorConfig.VersionSpec spec : config.versions()) {
            JsonObject descriptor = available.get(spec.id());
            if (descriptor == null && spec.descriptor() == null) {
                throw new IllegalArgumentException("Minecraft no publicó la versión '"
                        + spec.id() + "'.");
            }
            System.out.println("Sincronizando Minecraft " + spec.id() + "...");
            JsonObject catalogEntry = synchronizeVersion(spec,
                    descriptor, javaComponents, counters);
            catalogVersions.add(catalogEntry);
        }

        JsonArray javaRuntimes = new JsonArray();
        if (previous.has("javaRuntimes")) javaRuntimes = previous.getAsJsonArray(
                "javaRuntimes").deepCopy();
        if (config.javaRuntime().enabled()) {
            if (javaComponents.isEmpty()) {
                throw new IllegalArgumentException("El mirror de Java está habilitado, "
                        + "pero ninguna versión declara javaVersion.component");
            }
            JsonArray updated = synchronizeJavaRuntimes(javaComponents, counters);
            JsonArray retained = new JsonArray();
            for (JsonElement old : javaRuntimes) {
                JsonObject item = old.getAsJsonObject();
                boolean replaced = java.util.stream.StreamSupport.stream(
                        updated.spliterator(),
                        false).map(JsonElement::getAsJsonObject).anyMatch(
                        next -> next.get("platform").equals(item.get("platform"))
                        && next.get("component").equals(item.get("component")));
                if (!replaced) retained.add(old);
            }
            retained.addAll(updated);
            javaRuntimes = retained;
        }

        JsonObject catalog = new JsonObject();
        catalog.addProperty("schemaVersion", 1);
        catalog.addProperty("generatedAt", Instant.now().toString());
        catalog.addProperty("host", publicBase.toString());
        catalog.add("versions", catalogVersions);
        catalog.add("javaRuntimes", javaRuntimes);
        writeJson(repository.versionsDirectory().resolve("index.json"), catalog);
        writeVersionManifests(catalogVersions);
        return new SyncResult(config.versions().size(),
                javaRuntimes.size(), counters[0], counters[1]);
    }

    private JsonObject synchronizeVersion(MirrorConfig.VersionSpec spec,
            JsonObject descriptor, Set<String> javaComponents,
            int[] counters) throws IOException, InterruptedException, SQLException {
        String versionId = spec.id();
        JsonObject version;
        if (spec.descriptor() != null) {
            version = new GameInstaller().resolveDescriptor(spec.descriptor());
            descriptor = version.deepCopy();
        } else {
            String metadataSha1 = requiredString(descriptor, "sha1");
            URI metadataUrl = URI.create(requiredString(descriptor, "url"));
            Path originalMetadata = ensureBlob(metadataUrl, metadataSha1, -1, counters);
            version = parseJson(originalMetadata);
        }
        version.remove("inheritsFrom");
        version.remove("jar");

        JsonObject downloadsObject = version.getAsJsonObject("downloads");
        if (downloadsObject != null) {
            for (Map.Entry<String, JsonElement> entry
                    : List.copyOf(downloadsObject.entrySet())) {
                String key = entry.getKey();
                boolean wanted = key.startsWith("client") ? spec.client()
                        : key.startsWith("server") && spec.server();
                if (!wanted) {
                    downloadsObject.remove(key);
                    continue;
                }
                String extension = key.contains("mappings") ? ".txt" : ".jar";
                mirrorDownload(entry.getValue().getAsJsonObject(), "/mirror/versions/"
                        + versionId + "/" + key + extension, counters);
            }
        }

        if (spec.client()) {
            mirrorLibraries(version, counters);
            mirrorLogging(versionId, version, counters);
        }

        String assetIndexId = null;
        if (spec.assets() && version.has("assetIndex")) {
            JsonObject assetIndex = version.getAsJsonObject("assetIndex");
            assetIndexId = requiredString(assetIndex, "id");
            Path indexBlob = ensureBlob(URI.create(requiredString(assetIndex,
                    "url")), requiredString(assetIndex, "sha1"),
                    longValue(assetIndex, "size", -1), counters);
            JsonObject indexJson = parseJson(indexBlob);
            mirrorAssets(assetIndexId, indexJson, counters);
            Path versionDirectory = repository.versionsDirectory().resolve(versionId);
            // The version descriptor still carries Mojang's SHA-1 and byte size.
            // Reformatting this JSON changes both; preserve the verified upstream bytes.
            Files.createDirectories(versionDirectory);
            Path indexTemporary = versionDirectory.resolve("assets.json.part");
            Files.copy(indexBlob, indexTemporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(indexTemporary, versionDirectory.resolve("assets.json"));
            assetIndex.addProperty("url", publicUrl("/mirror/versions/"
                    + versionId + "/assets.json"));

            JsonObject leverage = version.has("leverage")
                    ? version.getAsJsonObject("leverage") : new JsonObject();
            leverage.addProperty("assetBaseUrl",
                    publicUrl("/mirror/assets/" + assetIndexId + "/"));
            version.add("leverage", leverage);
        }

        Path versionDirectory = repository.versionsDirectory().resolve(versionId);
        writeJson(versionDirectory.resolve("version.json"), version);

        JsonObject catalogEntry = new JsonObject();
        catalogEntry.addProperty("id", versionId);
        catalogEntry.addProperty("type", stringValue(version, "type", "unknown"));
        catalogEntry.addProperty("url", publicUrl("/mirror/versions/"
                + versionId + "/version.json"));
        copyString(descriptor, catalogEntry, "time");
        copyString(descriptor, catalogEntry, "releaseTime");
        if (assetIndexId != null) {
            catalogEntry.addProperty("assetIndex", assetIndexId);
        }
        if (version.has("javaVersion")) {
            JsonObject javaVersion = version.getAsJsonObject("javaVersion");
            catalogEntry.add("javaVersion", javaVersion.deepCopy());
            if (config.javaRuntime().enabled()) {
                String component = requiredString(javaVersion, "component");
                validatePathIdentifier(component, "componente Java");
                javaComponents.add(component);
                JsonObject leverage = version.has("leverage")
                        ? version.getAsJsonObject("leverage") : new JsonObject();
                JsonObject manifests = new JsonObject();
                for (String platform : config.platforms()) {
                    validatePathIdentifier(platform, "plataforma Java");
                    if (runtimeBuilds(platform, component) != null) manifests.addProperty(
                            platform, publicUrl(javaManifestRoute(platform, component)));
                }
                leverage.add("javaRuntimeManifests", manifests);
                version.add("leverage", leverage);
                writeJson(versionDirectory.resolve("version.json"), version);
            }
        }
        catalogEntry.addProperty("sha1", MirrorRepository.sha1(
                versionDirectory.resolve("version.json")));
        catalogEntry.addProperty("complianceLevel",
                longValue(descriptor, "complianceLevel", 0));
        return catalogEntry;
    }

    private void writeVersionManifests(JsonArray catalogVersions) throws IOException {
        JsonObject latest = new JsonObject();
        Map<String, JsonObject> latestByType = new java.util.HashMap<>();
        JsonArray classicVersions = new JsonArray();
        JsonArray modernVersions = new JsonArray();

        for (JsonElement element : catalogVersions) {
            JsonObject source = element.getAsJsonObject();
            JsonObject classic = versionManifestEntry(source, false);
            JsonObject modern = versionManifestEntry(source, true);
            classicVersions.add(classic);
            modernVersions.add(modern);

            String type = requiredString(source, "type");
            if (type.equals("release") || type.equals("snapshot")) {
                JsonObject current = latestByType.get(type);
                String releaseTime = stringValue(source, "releaseTime", "");
                if (current == null || releaseTime.compareTo(stringValue(current,
                        "releaseTime", "")) > 0) {
                    latestByType.put(type, source);
                }
            }
        }
        latestByType.forEach((type, version) -> latest.addProperty(type,
                requiredString(version, "id")));

        JsonObject classic = new JsonObject();
        classic.add("latest", latest.deepCopy());
        classic.add("versions", classicVersions);
        writeJson(repository.versionsDirectory().resolve(
                "version_manifest.json"), classic);

        JsonObject modern = new JsonObject();
        modern.add("latest", latest.deepCopy());
        modern.add("versions", modernVersions);
        writeJson(repository.versionsDirectory().resolve(
                "version_manifest_v2.json"), modern);
    }

    private static JsonObject versionManifestEntry(JsonObject source, boolean modern) {
        JsonObject result = new JsonObject();
        copyString(source, result, "id");
        copyString(source, result, "type");
        copyString(source, result, "url");
        copyString(source, result, "time");
        copyString(source, result, "releaseTime");
        if (modern) {
            copyString(source, result, "sha1");
            result.addProperty("complianceLevel", longValue(source,
                    "complianceLevel", 0));
        }
        return result;
    }

    private static void copyString(JsonObject source,
            JsonObject destination, String name) {
        if (source.has(name) && source.get(name).isJsonPrimitive()) {
            destination.addProperty(name, source.get(name).getAsString());
        }
    }

    private JsonArray synchronizeJavaRuntimes(Set<String> components,
            int[] counters) throws IOException, InterruptedException, SQLException {
        JsonArray catalog = new JsonArray();
        for (String platform : config.platforms()) {
            validatePathIdentifier(platform, "plataforma Java");
            for (String component : components) {
                validatePathIdentifier(component, "componente Java");
                JsonArray builds = runtimeBuilds(platform, component);
                if (builds == null) {
                    System.out.println("Java " + component + " no está publicado para "
                            + platform + "; se omite esa combinación.");
                    continue;
                }
                JsonObject selected = newestRuntime(builds);
                System.out.println("Sincronizando Java " + component
                        + " para " + platform + "...");
                catalog.add(synchronizeJavaRuntime(platform,
                        component, selected, counters));
            }
        }
        if (catalog.isEmpty()) throw new IOException(
                "Mojang no publica ninguno de los runtimes "
                + "solicitados para estas plataformas.");
        return catalog;
    }

    private JsonArray runtimeBuilds(String platform,
            String component) throws IOException, InterruptedException {
        if (javaCatalog == null) javaCatalog = fetchJson(javaRuntimeManifest);
        JsonObject entry = javaCatalog.getAsJsonObject(platform);
        JsonArray builds = entry == null ? null : entry.getAsJsonArray(component);
        return builds == null || builds.isEmpty() ? null : builds;
    }

    private JsonObject synchronizeJavaRuntime(String platform, String component,
            JsonObject selected, int[] counters) throws IOException,
            InterruptedException, SQLException {
        JsonObject descriptor = selected.getAsJsonObject("manifest");
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "El runtime Java no contiene un manifiesto");
        }
        Path sourceManifest = ensureBlob(URI.create(requiredString(descriptor,
                "url")), requiredString(descriptor, "sha1"),
                longValue(descriptor, "size", -1), counters);
        JsonObject runtime = parseJson(sourceManifest);
        JsonObject files = runtime.getAsJsonObject("files");
        if (files == null) {
            throw new IllegalArgumentException(
                    "El manifiesto del runtime Java no contiene 'files'");
        }

        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
                String runtimePath = normalizeRuntimePath(entry.getKey());
                JsonObject item = entry.getValue().getAsJsonObject();
                String type = requiredString(item, "type");
                if (type.equals("directory")) {
                    continue;
                }
                if (type.equals("link")) {
                    validateLinkTarget(runtimePath, requiredString(item, "target"));
                    continue;
                }
                if (!type.equals("file")) {
                    throw new IllegalArgumentException(
                            "Tipo desconocido en runtime Java: " + type);
                }
                JsonObject itemDownloads = item.getAsJsonObject("downloads");
                JsonObject raw = itemDownloads == null ? null
                        : itemDownloads.getAsJsonObject("raw");
                if (raw == null) {
                    throw new IllegalArgumentException("El archivo Java '"
                            + runtimePath + "' no contiene una descarga raw");
                }
                String route = javaFileRoute(platform, component, runtimePath);
                futures.add(executor.submit(() -> {
                    downloads.acquire();
                    try {
                        mirrorDownload(raw, route, counters);
                        itemDownloads.entrySet().removeIf(
                                download -> !download.getKey().equals("raw"));
                    } finally {
                        downloads.release();
                    }
                    return null;
                }));
            }
            awaitDownloads(futures, "un runtime Java");
        }

        JsonObject leverage = new JsonObject();
        leverage.addProperty("platform", platform);
        leverage.addProperty("component", component);
        if (selected.has("version")) {
            leverage.add("version", selected.get("version").deepCopy());
        }
        runtime.add("leverage", leverage);

        String manifestRoute = javaManifestRoute(platform, component);
        Path destination = repository.versionsDirectory().resolve("java").resolve(
                platform).resolve(component).resolve("manifest.json");
        writeJson(destination, runtime);

        JsonObject entry = new JsonObject();
        entry.addProperty("platform", platform);
        entry.addProperty("component", component);
        entry.addProperty("url", publicUrl(manifestRoute));
        if (selected.has("version")) {
            entry.add("version", selected.get("version").deepCopy());
        }
        return entry;
    }

    private static JsonObject newestRuntime(JsonArray builds) {
        return java.util.stream.StreamSupport.stream(builds.spliterator(), false).map(
                JsonElement::getAsJsonObject).max(Comparator.comparing(value -> {
                    JsonObject version = value.getAsJsonObject("version");
                    return version == null ? "" : stringValue(version, "released", "");
        })).orElseThrow();
    }

    private static String javaManifestRoute(String platform, String component) {
        return "/mirror/versions/java/" + platform + "/" + component + "/manifest.json";
    }

    private static String javaFileRoute(String platform, String component, String path) {
        return "/mirror/java/" + platform + "/" + component + "/files/" + path;
    }

    private static String normalizeRuntimePath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")
                || value.contains("\\")) {
            throw new IllegalArgumentException("Ruta inválida en runtime Java: " + value);
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Ruta inválida en runtime Java: " + value);
            }
        }
        return value;
    }

    private static void validateLinkTarget(String linkPath, String target) {
        if (target == null || target.isBlank() || target.startsWith("/")
                || target.contains("\\")) {
            throw new IllegalArgumentException(
                    "Enlace inválido en runtime Java: " + target);
        }
        List<String> resolved = new ArrayList<>(List.of(linkPath.split("/")));
        resolved.remove(resolved.size() - 1);
        for (String segment : target.split("/")) {
            if (segment.isBlank() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (resolved.isEmpty()) {
                    throw new IllegalArgumentException(
                            "El enlace Java sale del runtime: " + target);
                }
                resolved.remove(resolved.size() - 1);
            } else {
                resolved.add(segment);
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "Enlace inválido en runtime Java: " + target);
        }
    }

    private static void validatePathIdentifier(String value, String description) {
        if (value == null || value.isBlank() || value.contains("/")
                || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("Identificador de "
                    + description + " inválido: " + value);
        }
    }

    private void mirrorLibraries(JsonObject version, int[] counters) throws IOException,
            InterruptedException, SQLException {
        JsonArray libraries = version.getAsJsonArray("libraries");
        if (libraries == null) {
            return;
        }
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (library.has("clientreq") && !library.get(
                    "clientreq").getAsBoolean()) continue;
            JsonObject downloadsObject = library.getAsJsonObject("downloads");
            if (downloadsObject == null) {
                downloadsObject = library.has("natives") ? new JsonObject()
                        : new GameInstaller().legacyLibraryDownloads(library,
                        CancellationToken.NONE);
                library.add("downloads", downloadsObject);
            }
            // Client platforms, not the mirror host, select native libraries.
            JsonObject nativeNames = library.getAsJsonObject("natives");
            if (nativeNames != null) for (String platform : config.platforms()) {
                LaunchRules.Environment environment = RuntimePlatform.fromId(
                        platform).environment();
                if (!LaunchRules.allows(library, environment)
                        || !nativeNames.has(environment.osName())) continue;
                String classifier = nativeNames.get(
                        environment.osName()).getAsString().replace("${arch}",
                        environment.architecture().contains("64") ? "64" : "32");
                JsonObject classifiers = downloadsObject.getAsJsonObject("classifiers");
                if (classifiers == null) {
                    classifiers = new JsonObject();
                    downloadsObject.add("classifiers", classifiers);
                }
                if (!classifiers.has(classifier)) {
                    JsonObject resolved = new GameInstaller().legacyLibraryDownloads(
                            library, environment, CancellationToken.NONE);
                    JsonObject values = resolved.getAsJsonObject("classifiers");
                    if (values != null && values.has(classifier)) classifiers.add(
                            classifier, values.get(classifier));
                }
            }
            if (downloadsObject.has("artifact")) {
                mirrorLibraryEntry(downloadsObject.getAsJsonObject("artifact"), counters);
            }
            JsonObject classifiers = downloadsObject.getAsJsonObject("classifiers");
            if (classifiers != null) {
                for (Map.Entry<String, JsonElement> classifier
                        : List.copyOf(classifiers.entrySet())) {
                    if (platformWanted(classifier.getKey())) {
                        mirrorLibraryEntry(classifier.getValue().getAsJsonObject(),
                                counters);
                    } else {
                        classifiers.remove(classifier.getKey());
                    }
                }
            }
        }
    }

    private void mirrorLibraryEntry(JsonObject download,
            int[] counters) throws IOException, InterruptedException, SQLException {
        String path = requiredString(download, "path").replace('\\', '/');
        if (path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("Ruta de librería inválida: " + path);
        }
        mirrorDownload(download, "/mirror/libraries/" + path, counters);
    }

    private void mirrorLogging(String versionId, JsonObject version,
            int[] counters) throws IOException, InterruptedException, SQLException {
        JsonObject logging = version.getAsJsonObject("logging");
        if (logging == null || !logging.has("client")) {
            return;
        }
        JsonObject file = logging.getAsJsonObject("client").getAsJsonObject("file");
        if (file == null || !file.has("url")) {
            return;
        }
        String name = requiredString(file, "id");
        mirrorDownload(file, "/mirror/versions/" + versionId
                + "/logging/" + name, counters);
    }

    private void mirrorAssets(String assetIndexId, JsonObject index,
            int[] counters) throws IOException, InterruptedException, SQLException {
        JsonObject objects = index.getAsJsonObject("objects");
        if (objects == null) {
            return;
        }
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String logicalName = entry.getKey();
                JsonObject asset = entry.getValue().getAsJsonObject();
                String hash = requiredString(asset, "hash");
                long size = longValue(asset, "size", -1);
                URI upstream = URI.create(assetBase.toString().replaceAll("/+$",
                        "") + "/" + hash.substring(0, 2) + "/" + hash);
                String route = "/mirror/assets/" + assetIndexId + "/" + logicalName;
                futures.add(executor.submit(() -> {
                    downloads.acquire();
                    try {
                        Path file = ensureBlob(upstream, hash, size, counters);
                        repository.registerRoute(route, hash, Files.size(file),
                                contentType(logicalName), fileName(logicalName),
                                upstream.toString());
                    } finally {
                        downloads.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof SQLException sql) {
                        throw sql;
                    }
                    if (cause instanceof InterruptedException interrupted) {
                        throw interrupted;
                    }
                    throw new IOException("No se pudo sincronizar un asset", cause);
                }
            }
        }
    }

    private static void awaitDownloads(List<Future<?>> futures,
            String description) throws IOException, SQLException, InterruptedException {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof SQLException sql) {
                    throw sql;
                }
                if (cause instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                throw new IOException("No se pudo sincronizar " + description, cause);
            }
        }
    }

    private void mirrorDownload(JsonObject download, String route,
            int[] counters) throws IOException, InterruptedException, SQLException {
        URI upstream = URI.create(requiredString(download, "url"));
        String hash = requiredString(download, "sha1");
        long size = longValue(download, "size", -1);
        Path file = ensureBlob(upstream, hash, size, counters);
        repository.registerRoute(route, hash, Files.size(file),
                contentType(route), fileName(route), upstream.toString());
        download.addProperty("url", publicUrl(route));
    }

    private Path ensureBlob(URI source, String sha1, long size,
            int[] counters) throws IOException, InterruptedException {
        // Serialize each shared blob, not all concurrent artifact downloads.
        Object lock = hashLocks.computeIfAbsent(sha1, ignored -> new Object());
        synchronized (lock) {
            if (repository.isValidBlob(sha1, size)) {
                synchronized (counters) {
                    counters[1]++;
                }
                return repository.blobPath(sha1);
            }
            Path destination = repository.blobPath(sha1);
            Files.createDirectories(destination.getParent());
            IOException lastFailure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Path temporary = repository.tempDirectory().resolve(
                        UUID.randomUUID() + ".part");
                try {
                    download(source, temporary);
                    if (!repository.isValidBlobAt(temporary, sha1, size)) {
                        throw new IOException("El SHA-1 o tamaño no coincide para "
                                + source);
                    }
                    moveAtomically(temporary, destination);
                    synchronized (counters) {
                        counters[0]++;
                    }
                    return destination;
                } catch (IOException exception) {
                    lastFailure = exception;
                    Files.deleteIfExists(temporary);
                }
            }
            throw lastFailure == null ? new IOException("No se pudo descargar "
                    + source) : lastFailure;
        }
    }

    private void download(URI source, Path destination) throws IOException,
            InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(
                5)).header("User-Agent", "Leverage/0.1 mirror").GET().build();
        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode()
                    + " descargando " + source);
        }
        try (InputStream input = new DigestInputStream(response.body(),
                MirrorRepository.digest());
             var output = Files.newOutputStream(destination)) {
            input.transferTo(output);
        }
    }

    private JsonObject fetchJson(URI source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(
                1)).header("User-Agent", "Leverage/0.1 mirror").GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode()
                    + " descargando " + source);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject parseJson(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeJson(Path destination, JsonObject value) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.writeString(temporary, GSON.toJson(value));
        moveAtomically(temporary, destination);
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean platformWanted(String classifier) {
        return config.platforms().stream().anyMatch(platform -> RuntimePlatform.fromId(
                platform).wantsClassifier(classifier));
    }

    private String publicUrl(String route) {
        String rawPath;
        try {
            rawPath = new URI(null, null, route, null).getRawPath();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("No se pudo construir la URL del mirror",
                    exception);
        }
        return publicBase + rawPath;
    }

    private static URI normalizePublicBase(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || (!uri.getScheme().equalsIgnoreCase("http") &&
                !uri.getScheme().equalsIgnoreCase("https")) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath()
                != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("--host debe ser una URL HTTP/HTTPS "
                    + "sin ruta, usuario, query ni fragmento");
        }
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value.substring(0,
                value.length() - 1) : value);
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Falta '" + name
                    + "' en un manifiesto de Minecraft");
        }
        return value.getAsString();
    }

    private static String stringValue(JsonObject object, String name, String fallback) {
        return object.has(name) ? object.get(name).getAsString() : fallback;
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        return object.has(name) ? object.get(name).getAsLong() : fallback;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".jar")) return "application/java-archive";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    public record SyncResult(int versions, int javaRuntimes,
            int downloadedFiles, int reusedFiles) {
    }
}

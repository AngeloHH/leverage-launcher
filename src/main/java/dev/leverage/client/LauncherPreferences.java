package dev.leverage.client;

import dev.leverage.i18n.I18n;
import dev.leverage.launch.GameMemory;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

// Local launcher choices that are not credentials and may safely remain on disk.
public final class LauncherPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public LauncherPreferences(Path minecraftDirectory) throws IOException {
        Path directory = minecraftDirectory.toAbsolutePath().normalize().resolve(
                "leverage");
        Files.createDirectories(directory);
        file = directory.resolve("launcher-preferences.json");
    }

    public Path minecraftDirectory() { return file.getParent().getParent(); }

    public synchronized String loadLanguage() throws IOException {
        return I18n.Language.fromTag(read().language).tag();
    }

    public synchronized void saveLanguage(String tag) throws IOException {
        Data data = read();
        data.language = I18n.Language.fromTag(tag).tag();
        write(data);
    }

    public synchronized Optional<String> loadSelectedVersion(
            String serverScope) throws IOException {
        Data data = read();
        if (data.selectedVersions == null) return Optional.empty();
        String key = selectedVersionKey(serverScope);
        String value = data.selectedVersions.get(key);
        if (value == null && !key.equals(serverScope)) {
            // Preferences from before server directories were identified by SHA-1.
            value = data.selectedVersions.get(LauncherPreferences.validateServerUrl(
                    serverScope).toString());
        }
        return Optional.ofNullable(value);
    }

    public synchronized void saveSelectedVersion(String serverScope,
            String version) throws IOException {
        Data data = read();
        if (data.selectedVersions ==
                null) data.selectedVersions = new java.util.LinkedHashMap<>();
        String key = selectedVersionKey(serverScope);
        if (!key.equals(serverScope)) data.selectedVersions.remove(
                LauncherPreferences.validateServerUrl(serverScope).toString());
        data.selectedVersions.put(key, java.util.Objects.requireNonNull(version));
        write(data);
    }

    private static String selectedVersionKey(String scope) {
        java.util.Objects.requireNonNull(scope);
        return scope.equals("offline") || scope.matches("[a-f0-9]{40}")
                ? scope : InstanceLayout.serverScope(URI.create(scope));
    }

    public synchronized DownloadSource loadDownloadSource() throws IOException {
        return decode(read().downloadSource);
    }

    public synchronized DownloadSources loadDownloadSources() throws IOException {
        Data data = read();
        DownloadSource versions = decode(data.downloadSource);
        return new DownloadSources(versions, data.javaDownloadSource == null
                ? versions : decode(data.javaDownloadSource));
    }

    private static DownloadSource decode(StoredDownloadSource stored) throws IOException {
        if (stored == null || stored.useDefaults) {
            return DownloadSource.defaults();
        }
        try {
            return DownloadSource.external(validateBaseUrl(stored.baseUrl));
        } catch (RuntimeException exception) {
            throw new IOException(t("prefs.invalidDownloadUrl"), exception);
        }
    }

    public synchronized boolean hasDownloadSource() throws IOException {
        // A saved choice of the official defaults is also a completed setup step.
        // Older files already contain downloadSource, so no migration flag is needed.
        return read().downloadSource != null;
    }

    public synchronized Optional<URI> loadServerUrl() throws IOException {
        String value = read().serverUrl;
        if (value == null) return Optional.empty();
        try {
            return Optional.of(validateBaseUrl(value));
        } catch (RuntimeException exception) {
            throw new IOException(t("prefs.invalidServerUrl"), exception);
        }
    }

    public synchronized void saveServerUrl(URI host) throws IOException {
        Data data = read();
        data.serverUrl = validateBaseUrl(host.toString()).toString();
        write(data);
    }

    public synchronized boolean loadRememberDevice() throws IOException {
        return read().rememberDevice;
    }

    public synchronized GameMemory loadGameMemory() throws IOException {
        GameMemory memory = read().gameMemory;
        return memory == null ? GameMemory.DEFAULT : memory;
    }

    public synchronized void saveGameMemory(GameMemory memory) throws IOException {
        Data data = read();
        data.gameMemory = java.util.Objects.requireNonNull(memory);
        write(data);
    }

    public synchronized void saveRememberDevice(boolean remember) throws IOException {
        Data data = read();
        data.rememberDevice = remember;
        write(data);
    }

    private Data read() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Data();
        }
        try (var reader = Files.newBufferedReader(file)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null || data.schemaVersion != 1) {
                throw new IOException(I18n.t("prefs.invalidFile", file));
            }
            return data;
        } catch (RuntimeException exception) {
            throw new IOException(I18n.t("prefs.readFile", file), exception);
        }
    }

    public synchronized void saveDownloadSource(
            DownloadSource source) throws IOException {
        saveDownloadSources(new DownloadSources(source, source));
    }

    public synchronized void saveDownloadSources(
            DownloadSources sources) throws IOException {
        Data data = read();
        data.downloadSource = encode(sources.versions());
        data.javaDownloadSource = encode(sources.javaRuntime());
        write(data);
    }

    public synchronized void saveServerConfiguration(URI host,
            DownloadSources sources) throws IOException {
        Data data = read();
        data.serverUrl = validateServerUrl(host.toString()).toString();
        data.downloadSource = encode(sources.versions());
        data.javaDownloadSource = encode(sources.javaRuntime());
        write(data);
    }

    private static StoredDownloadSource encode(DownloadSource source) {
        java.util.Objects.requireNonNull(source, t("prefs.sourceRequired"));
        return new StoredDownloadSource(source.useDefaults(), source.baseUrl() == null
                ? null : validateBaseUrl(source.baseUrl().toString()).toString());
    }

    public static URI validateServerUrl(String value) {
        URI uri = validateBaseUrl(value);
        if (uri.getPath() != null && !uri.getPath().isEmpty()) {
            throw new IllegalArgumentException(t("prefs.serverPath"));
        }
        try {
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https")
                    && port == 443)) port = -1;
            return new URI(scheme, null, uri.getHost().toLowerCase(
                    java.util.Locale.ROOT), port, "/", null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException(t("prefs.serverInvalid"), exception);
        }
    }

    public record DownloadSources(DownloadSource versions, DownloadSource javaRuntime) {
        public DownloadSources {
            java.util.Objects.requireNonNull(versions);
            java.util.Objects.requireNonNull(javaRuntime);
        }
    }

    private void write(Data data) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(temporary, GSON.toJson(data));
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static URI validateBaseUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(t("error.urlValid"), exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || (!uri.getScheme().equalsIgnoreCase("http") &&
                !uri.getScheme().equalsIgnoreCase("https")) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(t("error.urlValid"));
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized.substring(0,
                normalized.length() - 1) : normalized);
    }

    public record DownloadSource(boolean useDefaults, URI baseUrl) {
        public DownloadSource {
            if (useDefaults && baseUrl != null) {
                throw new IllegalArgumentException(t("prefs.defaultNoUrl"));
            }
            if (!useDefaults && baseUrl == null) {
                throw new IllegalArgumentException(t("prefs.externalUrl"));
            }
        }

        public static DownloadSource defaults() {
            return new DownloadSource(true, null);
        }

        public static DownloadSource external(URI baseUrl) {
            return new DownloadSource(false, baseUrl);
        }
    }

    private static final class Data {
        private int schemaVersion = 1;
        private String serverUrl;
        private String language = "es";
        private boolean rememberDevice = true;
        private GameMemory gameMemory;
        private StoredDownloadSource downloadSource;
        private StoredDownloadSource javaDownloadSource;
        private java.util.Map<String, String> selectedVersions;
    }

    private record StoredDownloadSource(boolean useDefaults, String baseUrl) {
    }
}

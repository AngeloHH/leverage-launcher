package dev.leverage.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class LauncherVersionService {
    private static final Gson GSON = new Gson();
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

    private final Path policyPath;

    public LauncherVersionService(Path policyPath) {
        this.policyPath = policyPath.toAbsolutePath().normalize();
        if (Files.exists(this.policyPath)) {
            policy();
        }
    }

    public CheckResult check(String channelValue, String sha1Value) {
        String channelName = label(channelValue, "channel", 50);
        String sha1 = requestSha1(sha1Value);
        Channel channel = policy().channels().stream().filter(
                candidate -> candidate.name().equalsIgnoreCase(
                channelName)).findFirst().orElseThrow(() -> ServiceException.notFound(
                "LAUNCHER_CHANNEL_NOT_FOUND", "El canal de actualizaciones no existe."));
        Release latest = channel.releases().stream().filter(
                release -> release.version().equals(channel.latestVersion()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                "La versión latestVersion no existe en su canal."));
        Release current = channel.releases().stream().filter(
                release -> release.sha1().equals(sha1)).findFirst().orElse(null);

        if (current == null) {
            return result(Status.UPDATE_REQUIRED, false, false, null, latest,
                    "Esta build del launcher no está reconocida por el " + "servidor.");
        }
        if (current.sha1().equals(latest.sha1())) {
            return result(Status.CURRENT, true, true, current, latest,
                    "El launcher está actualizado.");
        }
        if (current.allowLaunch()) {
            return result(Status.UPDATE_AVAILABLE, true, true, current, latest,
                    "Hay una actualización disponible, pero esta versión "
                    + "todavía puede continuar.");
        }
        return result(Status.UPDATE_REQUIRED, false, true, current, latest,
                "Esta versión ya no puede continuar y debe actualizarse.");
    }

    private static CheckResult result(Status status, boolean canContinue,
            boolean recognized, Release current, Release latest, String message) {
        return new CheckResult(status, canContinue, recognized, current == null
                ? null : current.version(), latest.version(), latest.sha1(),
                latest.downloadUrl(), latest.releaseNotesUrl(), message);
    }

    private Policy policy() {
        if (!Files.isRegularFile(policyPath)) {
            throw new ServiceException(503, "LAUNCHER_POLICY_NOT_CONFIGURED",
                    "El servidor todavía no tiene configuradas las "
                    + "versiones del launcher.");
        }
        try {
            RawPolicy raw = GSON.fromJson(Files.readString(policyPath), RawPolicy.class);
            if (raw == null || raw.schemaVersion() != 1 || raw.channels() == null
                    || raw.channels().isEmpty()) {
                throw new IllegalArgumentException(
                        "La política debe usar schemaVersion 1 y definir canales.");
            }
            List<Channel> channels = new ArrayList<>();
            Set<String> channelNames = new HashSet<>();
            for (RawChannel rawChannel : raw.channels()) {
                String name = configLabel(rawChannel.name(), "channel.name", 50);
                String latestVersion = configLabel(rawChannel.latestVersion(),
                        "channel.latestVersion", 100);
                if (!channelNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Canal repetido: " + name);
                }
                if (rawChannel.releases() == null || rawChannel.releases().isEmpty()) {
                    throw new IllegalArgumentException(
                            "El canal no contiene releases: " + name);
                }
                List<Release> releases = new ArrayList<>();
                Set<String> versions = new HashSet<>();
                Set<String> hashes = new HashSet<>();
                for (RawRelease rawRelease : rawChannel.releases()) {
                    String version = configLabel(rawRelease.version(),
                            "release.version", 100);
                    String sha1 = configSha1(rawRelease.sha1());
                    URI download = httpUri(rawRelease.downloadUrl(), "downloadUrl");
                    URI notes = optionalHttpUri(rawRelease.releaseNotesUrl(),
                            "releaseNotesUrl");
                    if (!versions.add(version) || !hashes.add(sha1)) {
                        throw new IllegalArgumentException(
                                "Versión o SHA-1 repetido en el canal " + name + ".");
                    }
                    releases.add(new Release(version, sha1, download,
                            notes, rawRelease.allowLaunch()));
                }
                Release latest = releases.stream().filter(item -> item.version().equals(
                        latestVersion)).findFirst().orElseThrow(
                        () -> new IllegalArgumentException(
                        "latestVersion no aparece en releases para " + name + "."));
                if (!latest.allowLaunch()) {
                    throw new IllegalArgumentException(
                            "La versión más reciente debe permitir el inicio.");
                }
                channels.add(new Channel(name, latestVersion, List.copyOf(releases)));
            }
            return new Policy(1, List.copyOf(channels));
        } catch (IOException | JsonParseException exception) {
            throw new IllegalArgumentException(
                    "No se pudo leer la política de versiones: " + policyPath, exception);
        }
    }

    private static URI httpUri(String value, String field) {
        String clean = configLabel(value, field, 2_000);
        URI uri;
        try {
            uri = URI.create(clean);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field
                    + " no contiene una URL válida.", exception);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !(uri.getScheme().equalsIgnoreCase("http")
                || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(field
                    + " debe ser una URL HTTP/HTTPS absoluta.");
        }
        return uri;
    }

    private static URI optionalHttpUri(String value, String field) {
        return value == null || value.isBlank() ? null : httpUri(value, field);
    }

    private static String requestSha1(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA1.matcher(normalized).matches()) {
            throw ServiceException.badRequest("INVALID_LAUNCHER_SHA1",
                    "El launcher debe enviar un SHA-1 hexadecimal válido.");
        }
        return normalized;
    }

    private static String configSha1(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA1.matcher(normalized).matches()) {
            throw new IllegalArgumentException("SHA-1 inválido en la política: " + value);
        }
        return normalized;
    }

    private static String label(String value, String field, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw ServiceException.badRequest("INVALID_" + field.toUpperCase(Locale.ROOT),
                    "El campo '" + field + "' no es válido.");
        }
        return clean;
    }

    private static String configLabel(String value, String field, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Campo inválido en la política: " + field);
        }
        return clean;
    }

    public enum Status {
        CURRENT, UPDATE_AVAILABLE, UPDATE_REQUIRED
    }

    public record Policy(int schemaVersion, List<Channel> channels) { }

    public record Channel(String name, String latestVersion, List<Release> releases) { }

    public record Release(String version, String sha1, URI downloadUrl,
            URI releaseNotesUrl, boolean allowLaunch) { }

    public record CheckResult(Status status, boolean canContinue, boolean recognized,
            String currentVersion, String latestVersion, String latestSha1,
            URI downloadUrl, URI releaseNotesUrl, String message) { }

    private record RawPolicy(int schemaVersion, List<RawChannel> channels) { }

    private record RawChannel(String name, String latestVersion,
            List<RawRelease> releases) { }

    private record RawRelease(String version, String sha1, String downloadUrl,
            String releaseNotesUrl, boolean allowLaunch) { }
}

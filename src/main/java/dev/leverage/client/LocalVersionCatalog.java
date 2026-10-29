package dev.leverage.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LocalVersionCatalog {
    private final Path minecraftDirectory;
    private final Path versionsDirectory;

    public LocalVersionCatalog(Path minecraftDirectory) {
        this.minecraftDirectory = minecraftDirectory.toAbsolutePath().normalize();
        this.versionsDirectory = this.minecraftDirectory.resolve("versions");
    }

    public static Path defaultMinecraftDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("mac") || os.contains("darwin")) return home.resolve(
                "Library/Application Support/minecraft").toAbsolutePath().normalize();
        String appData = System.getenv("APPDATA");
        if (os.contains("win") && appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".minecraft").toAbsolutePath().normalize();
        }
        return home.resolve(".minecraft").toAbsolutePath().normalize();
    }

    public Catalog scan() throws IOException {
        if (!Files.isDirectory(versionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return new Catalog(minecraftDirectory, List.of(), 0);
        }
        Map<String, Candidate> candidates = new HashMap<>();
        int ignored = 0;
        try (var directories = Files.list(versionsDirectory)) {
            for (Path directory : directories.toList()) {
                Candidate candidate = readCandidate(directory);
                if (candidate == null) {
                    ignored++;
                } else {
                    candidates.put(candidate.id(), candidate);
                }
            }
        }

        List<InstalledVersion> versions = new ArrayList<>();
        // Discover first: inherited JARs may appear later in the directory list.
        for (Candidate candidate : candidates.values()) {
            Path clientJar = resolveClientJar(candidate, candidates, new HashSet<>());
            if (clientJar == null) {
                ignored++;
                continue;
            }
            versions.add(new InstalledVersion(candidate.id(), candidate.type(),
                    candidate.inheritsFrom(), candidate.releaseTime(),
                    candidate.descriptor(), clientJar));
        }
        versions.sort(Comparator.comparing(InstalledVersion::releaseTime,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(
                InstalledVersion::id, String.CASE_INSENSITIVE_ORDER));
        return new Catalog(minecraftDirectory, List.copyOf(versions), ignored);
    }

    private Candidate readCandidate(Path directory) {
        try {
            Path normalized = directory.toAbsolutePath().normalize();
            if (!normalized.startsWith(versionsDirectory) || !Files.isDirectory(
                    normalized, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            String folder = normalized.getFileName().toString();
            Path descriptor = normalized.resolve(folder + ".json");
            if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                descriptor = normalized.resolve("version.json");
            }
            if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            JsonObject json = JsonParser.parseString(Files.readString(
                    descriptor)).getAsJsonObject();
            String id = string(json, "id");
            if (id == null || !safeId(id)) {
                return null;
            }
            String parent = string(json, "inheritsFrom");
            if (parent == null) {
                parent = string(json, "jar");
            }
            if (parent != null && !safeId(parent)) {
                return null;
            }
            return new Candidate(id, valueOr(json, "type",
                    "custom"), parent, instant(json, "releaseTime"),
                    descriptor.toAbsolutePath().normalize(), normalized);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Path resolveClientJar(Candidate candidate, Map<String,
            Candidate> candidates, Set<String> visiting) {
        if (!visiting.add(candidate.id())) {
            return null;
        }
        Path ownJar = candidate.directory().resolve(candidate.id() + ".jar");
        if (Files.isRegularFile(ownJar, LinkOption.NOFOLLOW_LINKS)) {
            return ownJar.toAbsolutePath().normalize();
        }
        Path genericJar = candidate.directory().resolve("client.jar");
        if (Files.isRegularFile(genericJar, LinkOption.NOFOLLOW_LINKS)) {
            return genericJar.toAbsolutePath().normalize();
        }
        if (candidate.inheritsFrom() == null) {
            return null;
        }
        Candidate parent = candidates.get(candidate.inheritsFrom());
        return parent == null ? null : resolveClientJar(parent, candidates, visiting);
    }

    private static boolean safeId(String value) {
        return VersionId.isValid(value);
    }

    private static String string(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) {
            return null;
        }
        String value = object.get(field).getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static String valueOr(JsonObject object, String field, String fallback) {
        String value = string(object, field);
        return value == null ? fallback : value.toLowerCase(Locale.ROOT);
    }

    private static Instant instant(JsonObject object, String field) {
        String value = string(object, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public record Catalog(Path minecraftDirectory,
            List<InstalledVersion> versions, int ignoredEntries) {
    }

    public record InstalledVersion(String id, String type, String inheritsFrom,
            Instant releaseTime, Path descriptor, Path clientJar) {
    }

    private record Candidate(String id, String type, String inheritsFrom,
            Instant releaseTime, Path descriptor, Path directory) {
    }
}

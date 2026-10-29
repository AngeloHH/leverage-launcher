package dev.leverage.mirror;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.launch.RuntimePlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record MirrorConfig(Path output, List<VersionSpec> versions,
        List<String> platforms, JavaSpec javaRuntime) {
    public MirrorConfig {
        versions = List.copyOf(versions);
        // Canonical names prevent duplicate native/runtime work from aliases.
        platforms = platforms.stream().map(RuntimePlatform::fromId).map(
                RuntimePlatform::id).distinct().toList();
    }
    public MirrorConfig(Path output, List<VersionSpec> versions, List<String> platforms) {
        this(output, versions, platforms, new JavaSpec(false, List.of()));
    }

    public static MirrorConfig load(Path configPath) throws IOException {
        Path absolute = configPath.toAbsolutePath().normalize();
        JsonObject json;
        try (var reader = Files.newBufferedReader(absolute)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        // A Minecraft descriptor is itself a valid mirror input, not a catalog ID.
        if (json.has("id") && !json.has("versions")) {
            return new MirrorConfig(Path.of("mirror").toAbsolutePath().normalize(),
                    List.of(new VersionSpec(requiredString(json, "id"), true,
                    true, true, absolute)), RuntimePlatform.MIRROR_DEFAULTS,
                    new JavaSpec(true, List.of()));
        }

        int schema = integer(json, "schemaVersion", 1);
        if (schema != 1) {
            throw new IllegalArgumentException("schemaVersion de mirror.json debe ser 1");
        }

        String outputValue = requiredString(json, "output");
        Path output = Path.of(outputValue);
        if (!output.isAbsolute()) {
            Path base = absolute.getParent();
            if (base != null && base.getFileName() != null
                    && base.getFileName().toString().equalsIgnoreCase("config")) {
                base = base.getParent();
            }
            output = (base == null ? Path.of("").toAbsolutePath()
                    : base).resolve(output).normalize();
        }

        JsonArray versionArray = json.getAsJsonArray("versions");
        if (versionArray == null || versionArray.isEmpty()) {
            throw new IllegalArgumentException(
                    "mirror.json debe contener al menos una versión");
        }
        List<VersionSpec> versions = new ArrayList<>();
        for (JsonElement element : versionArray) {
            if (element.isJsonPrimitive()) {
                versions.add(new VersionSpec(element.getAsString(), true, true, true));
                continue;
            }
            JsonObject value = element.getAsJsonObject();
            versions.add(new VersionSpec(requiredString(value, "id"),
                    bool(value, "client", true), bool(value, "server",
                    true), bool(value, "assets", true)));
        }

        List<String> platforms = new ArrayList<>();
        JsonArray platformArray = json.getAsJsonArray("platforms");
        if (platformArray == null || platformArray.isEmpty()) {
            platforms.addAll(RuntimePlatform.MIRROR_DEFAULTS);
        } else {
            platformArray.forEach(value -> {
                String platform = RuntimePlatform.fromId(value.getAsString()).id();
                if (!platforms.contains(platform)) platforms.add(platform);
            });
        }

        JsonObject javaObject = json.getAsJsonObject("java");
        JavaSpec javaRuntime;
        if (javaObject == null) {
            javaRuntime = new JavaSpec(false, List.of());
        } else {
            List<String> components = new ArrayList<>();
            JsonArray componentArray = javaObject.getAsJsonArray("components");
            if (componentArray != null) {
                componentArray.forEach(value -> components.add(value.getAsString()));
            }
            javaRuntime = new JavaSpec(bool(javaObject, "enabled",
                    true), List.copyOf(components));
        }

        return new MirrorConfig(output, List.copyOf(versions),
                List.copyOf(platforms), javaRuntime);
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("El campo '" + name
                    + "' es obligatorio en mirror.json");
        }
        return value.getAsString().trim();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        return object.has(name) ? object.get(name).getAsBoolean() : fallback;
    }

    public record VersionSpec(String id, boolean client, boolean server,
            boolean assets, Path descriptor) {
        public VersionSpec(String id, boolean client, boolean server, boolean assets) {
            this(id, client, server, assets, null);
        }
        public VersionSpec {
            if (id == null || id.isBlank() || id.contains("/")
                    || id.contains("\\") || id.contains("..")) {
                throw new IllegalArgumentException(
                        "Identificador de versión inválido: " + id);
            }
        }
    }

    public record JavaSpec(boolean enabled, List<String> components) {
        public JavaSpec {
            components = components == null ? List.of() : List.copyOf(components);
            for (String component : components) {
                validateIdentifier(component, "componente Java");
            }
        }
    }

    private static void validateIdentifier(String value, String description) {
        if (value == null || value.isBlank() || value.contains("/")
                || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("Identificador de "
                    + description + " inválido: " + value);
        }
    }
}

package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

// Read local metadata without loading classes or fetching external images.
public final class InstalledModCatalog {
    public record Mod(Path file, String sha1, String name, String version,
            String description, BufferedImage icon) { }

    public List<Mod> scan(Path modsDirectory) throws IOException {
        Path root = directory(modsDirectory);
        List<Mod> result = new ArrayList<>();
        for (var scanned : new ModDirectoryScanner().scan(root)) {
            Path file = root.resolve(scanned.fileName());
            regular(file);
            String name = file.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
            String version = t("files.noVersion");
            String description = t("files.noDescription");
            BufferedImage icon = null;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                Metadata metadata = metadata(zip);
                name = value(metadata.name, name);
                version = value(metadata.version, version);
                description = value(metadata.description, description);
                try {
                    icon = icon(zip, metadata.icon);
                } catch (IOException | RuntimeException ignored) { }
            } catch (IOException | RuntimeException ignored) {
                // A broken descriptor must not hide the installed list.
            }
            result.add(new Mod(file, scanned.sha1(), name, version, description, icon));
        }
        return List.copyOf(result);
    }

    public List<Mod> scanResources(Path resourcepacksDirectory) throws IOException {
        Path root = directory(resourcepacksDirectory);
        List<Mod> result = new ArrayList<>();
        for (var scanned : new ModDirectoryScanner().scanResourcePacks(root)) {
                Path file = root.resolve(scanned.fileName());
                String name = file.getFileName().toString().replaceFirst(
                    "(?i)\\.zip$", "");
                String version = t("files.noVersion");
                String description = t("files.noPackDescription");
                BufferedImage image = null;
                try {
                    String metadata;
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = entry(file.resolve("pack.mcmeta"), 131072);
                        metadata = bytes == null ? null : new String(bytes,
                            StandardCharsets.UTF_8);
                        try {
                            image = icon(entry(file.resolve("pack.png"), 1048576));
                    } catch (IOException | RuntimeException ignored) { }
                } else try (ZipFile zip = new ZipFile(file.toFile())) {
                        metadata = text(zip, "pack.mcmeta");
                        try {
                            image = icon(zip, "pack.png");
                    } catch (IOException | RuntimeException ignored) { }
                }
                    if (metadata != null) {
                        JsonObject pack = JsonParser.parseString(
                            metadata).getAsJsonObject().getAsJsonObject("pack");
                        if (pack != null) {
                            String format = string(pack, "pack_format");
                            if (format != null) version = I18n.t("files.format", format);
                            description = value(componentText(pack.get("description"),
                                0), description);
                    }
                }
            } catch (IOException | RuntimeException ignored) { }
                result.add(new Mod(file, scanned.sha1(), name,
                    version, description, image));
        }
        return List.copyOf(result);
    }

    private static String componentText(JsonElement element, int depth) {
        if (element == null || element.isJsonNull() || depth > 8) return "";
        StringBuilder text = new StringBuilder();
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                text.append(componentText(child, depth + 1));
                if (text.length() > 4096) break;
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            text.append(componentText(object.get("text"), depth + 1));
            text.append(componentText(object.get("extra"), depth + 1));
        }
        return text.substring(0, Math.min(text.length(), 4096));
    }

    // Imports neither replace installed files nor follow links.
    public Path install(Path targetDirectory, Path selectedFile,
            boolean resourcePack) throws IOException {
        Path root = directory(targetDirectory);
        Path source = selectedFile.toAbsolutePath().normalize();
        regular(source);
        String filename = source.getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(resourcePack ? ".zip" : ".jar")) {
            throw new IOException(resourcePack ? t("files.chooseZip")
                    : t("files.chooseJar"));
        }
        Path destination = root.resolve(filename);
        // Watchers must never inspect a partially copied ZIP/JAR.
        Path temporary = Files.createTempFile(root.getParent(),
                "leverage-import-", ".part");
        try {
            String expected = new ModDirectoryScanner().scanFile(source).sha1();
            Files.copy(source, temporary,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!sha1(temporary).equals(expected))
                    throw new IOException(t("files.changedCopy"));
            Files.move(temporary, destination);
            // No replacement of an existing installed file.
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }

    // Uninstall into a recoverable backup without replacing existing files.
    public Path remove(Path modsDirectory, Mod mod) throws IOException {
        Path root = directory(modsDirectory);
        Path source = mod.file().toAbsolutePath().normalize();
        if (source.equals(root) || !source.startsWith(root)
                || !source.toRealPath().startsWith(root))
                throw new IOException(t("files.outsideFolder"));
        String currentHash = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(
                ".zip") ? ModDirectoryScanner.resourcePack(source).sha1()
                : new ModDirectoryScanner().scanFile(source).sha1();
        if (!currentHash.equals(mod.sha1()))
                throw new IOException(t("files.itemChanged"));
        Path removed = root.resolve(".leverage-removed");
        if (Files.exists(removed, LinkOption.NOFOLLOW_LINKS)) directory(removed);
        else Files.createDirectory(removed);
        if (!removed.toRealPath().getParent().equals(root))
                throw new IOException(t("files.recoveryInvalid"));
        Path backup = Files.createDirectory(removed.resolve(
                UUID.randomUUID().toString())).resolve(source.getFileName());
        Files.move(source, backup);
        return backup;
    }

    private static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(t("files.modsFolder"));
        }
        return absolute.toRealPath();
    }

    private static void regular(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.t("files.linksNamed", file.getFileName()));
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) >= 0;
                        ) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Metadata metadata(ZipFile zip) throws IOException {
        String text = text(zip, "fabric.mod.json");
        if (text != null) {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonElement icon = json.get("icon");
            String iconPath = null;
            if (icon != null && icon.isJsonPrimitive()) iconPath = icon.getAsString();
            else if (icon != null && icon.isJsonObject()) {
                iconPath = icon.getAsJsonObject().entrySet().stream().filter(
                        entry -> entry.getValue().isJsonPrimitive()).sorted(
                        Comparator.comparingInt(entry -> {
                            try {
                                return Math.abs(Integer.parseInt(entry.getKey()) - 64);
                    } catch (NumberFormatException ignored) {
                                return Integer.MAX_VALUE;
                    }
                })).map(entry -> entry.getValue().getAsString()).findFirst().orElse(null);
            }
            return new Metadata(string(json, "name"), string(json, "version"),
                    string(json, "description"), iconPath);
        }
        text = text(zip, "mcmod.info");
        if (text != null) {
            JsonElement json = JsonParser.parseString(text);
            var mods = json.isJsonArray() ? json.getAsJsonArray()
                    : json.getAsJsonObject().getAsJsonArray("modList");
            JsonObject mod = mods.get(0).getAsJsonObject();
            return new Metadata(string(mod, "name"), string(mod, "version"),
                    string(mod, "description"), string(mod, "logoFile"));
        }
        text = text(zip, "META-INF/neoforge.mods.toml");
        if (text == null) text = text(zip, "META-INF/mods.toml");
        if (text != null) {
            // Read first-mod display strings without TOML interpolation.
            int first = text.indexOf("[[mods]]");
            String block = first < 0 ? text : text.substring(first + 8);
            int nextTable = block.indexOf("[[");
            if (nextTable >= 0) block = block.substring(0, nextTable);
            String version = toml(block, "version");
            if ("${file.jarVersion}".equals(version)) {
                byte[] manifest = entry(zip, "META-INF/MANIFEST.MF", 131072);
                version = manifest == null ? null : new Manifest(new ByteArrayInputStream(
                        manifest)).getMainAttributes().getValue("Implementation-Version");
            }
            return new Metadata(value(toml(block, "displayName"), toml(block, "modId")),
                    version, toml(block, "description"), toml(block, "logoFile"));
        }
        return new Metadata(null, null, null, null);
    }

    private static String toml(String text, String key) {
        var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + "\\s*=\\s*(?:'''([\\s\\S]*?)'''|\"\"\"([\\s\\S]*?)\"\"\"|"
                + "'([^'\\r\\n]*)'|\"((?:\\\\.|[^\"\\\\\\r\\n])*)\")").matcher(text);
        if (!match.find()) return null;
        for (int i = 1; i <= 4; i++) if (match.group(i) != null) {
            String value = match.group(i);
            if (i == 4) {
                try { return JsonParser.parseString("\"" + value + "\"").getAsString(); }
                catch (RuntimeException ignored) { }
            }
            return value;
        }
        return null;
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
    private static String value(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.trim();
    }
    private record Metadata(String name, String version,
            String description, String icon) { }
    private static String text(ZipFile zip, String name) throws IOException {
        byte[] bytes = entry(zip, name, 131072);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] entry(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) return null;
        if (entry.getSize() > limit) throw new IOException(t("files.metadataTooLarge"));
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException(t("files.metadataTooLarge"));
            return bytes;
        }
    }

    private static BufferedImage icon(ZipFile zip, String path) throws IOException {
        if (path == null || path.contains(":") || path.contains("..")
                || path.startsWith("/") || path.contains("\\")) return null;
        byte[] bytes = entry(zip, path, 1048576);
        return icon(bytes);
    }

    private static byte[] entry(Path path, int limit) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        regular(path);
        if (Files.size(path) > limit) throw new IOException(t("files.metadataTooLarge"));
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException(t("files.metadataTooLarge"));
            return bytes;
        }
    }

    private static BufferedImage icon(byte[] bytes) throws IOException {
        if (bytes == null) return null;
        try (var input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 1024
                        || height > 1024) return null;
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, (Math.max(width, height) + 95) / 96);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                return reader.read(0, parameters);
            } finally { reader.dispose(); }
        }
    }
}

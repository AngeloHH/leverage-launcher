package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

// The local server/account gallery contains no credentials.
public final class LocalSkinLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    public record Entry(String id, String name, String builtin,
            boolean slim, BufferedImage image) { }
    public record State(List<Entry> entries, String activeId, Boolean activeSlim) { }
    private record Stored(String id, String name, String builtin, boolean slim) { }
    private record Document(int schemaVersion, List<Stored> entries,
            String activeId, Boolean activeSlim) { }

    public LocalSkinLibrary(Path minecraft, URI server, UUID userId) {
        Path root = minecraft.toAbsolutePath().normalize().resolve(
                "leverage/skins").resolve(InstanceLayout.serverScope(server));
        directory = server == null ? root : root.resolve(userId == null
                ? "local" : userId.toString());
    }

    public static List<Entry> defaults() {
        return List.of();
    }

    public static Entry duplicate(Entry skin) {
        String name = skin.name().substring(0, Math.min(112,
                skin.name().length())) + " (copia)";
        return new Entry(UUID.randomUUID().toString(), name,
                skin.builtin(), skin.slim(), skin.image());
    }

    public static Entry toggleModel(Entry skin) {
        if (skin.image().getHeight() == 32) return new Entry(skin.id(),
                skin.name(), skin.builtin(), false, skin.image());
        return new Entry(skin.id(),
                skin.name(), null, !skin.slim(), skin.image());
    }

    public static Entry withModel(Entry skin, boolean slim) {
        return skin.slim() == slim ? skin : toggleModel(skin);
    }

    public static Entry importPng(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2 * 1024 * 1024) {
            throw new IOException(t("skins.selectPng"));
        }
        String name = path.getFileName().toString().replaceFirst("(?i)\\.png$", "");
        return new Entry(UUID.randomUUID().toString(), name.substring(0, Math.min(64,
                name.length())), null, false, readPng(Files.readAllBytes(path)));
    }

    public static BufferedImage readPng(byte[] bytes) throws IOException {
        if (bytes.length > 2 * 1024 * 1024) throw new IOException(t("skins.tooLarge"));
        try (var input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException(t("skins.notPng"));
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                        || reader.getWidth(0) != 64
                        || (reader.getHeight(0) != 64 && reader.getHeight(0) != 32))
                    throw new IOException(t("skins.pngDimensionsAlt"));
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    public synchronized State load() throws IOException {
        Path file = directory.resolve("skins.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new State(defaults(), null, null);
        ensureDirectory();
        if (Files.isSymbolicLink(file) || Files.size(file) > 128 * 1024)
            throw new IOException(t("skins.invalidCatalog"));
        try {
            Document document = GSON.fromJson(Files.readString(file), Document.class);
            if (document == null || document.schemaVersion() != 1
                    || document.entries() == null || document.entries().size() > 128)
                throw new IOException(t("skins.invalidCatalog"));
            List<Entry> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Stored row : document.entries()) {
                // Older catalogs reference bundled textures that are no longer shipped.
                if (row.builtin() != null) continue;
                checkId(row.id());
                if (row.name() == null || row.name().isBlank()
                        || row.name().length() > 128 || !seen.add(row.id()))
                        throw new IOException(t("skins.invalidEntry"));
                Path image = directory.resolve(row.id() + ".png");
                if (Files.isSymbolicLink(image) || !Files.isRegularFile(image,
                        LinkOption.NOFOLLOW_LINKS) || Files.size(image) > 2 * 1024 * 1024)
                        throw new IOException(t("skins.missingLibraryEntry"));
                BufferedImage texture = readPng(Files.readAllBytes(image));
                result.add(new Entry(row.id(), row.name(), null,
                        row.slim() && texture.getHeight() == 64, texture));
            }
            boolean active = result.stream().anyMatch(entry -> entry.id().equals(
                    document.activeId()));
            return new State(List.copyOf(result), active ? document.activeId() : null,
                    active ? document.activeSlim() : null);
        } catch (RuntimeException exception) {
            throw new IOException(t("skins.readLibraryError"), exception);
        }
    }

    public synchronized void save(List<Entry> entries,
            String activeId) throws IOException {
        save(entries, activeId, null);
    }

    public synchronized void save(List<Entry> entries, String activeId,
            Boolean activeSlim) throws IOException {
        if (entries.size() > 128) throw new IOException(t("skins.libraryLimit"));
        ensureDirectory();
        List<Stored> rows = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id().equals("server-current") || entry.builtin() != null) continue;
            checkId(entry.id());
            Path image = directory.resolve(entry.id() + ".png");
            if (Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(image) || !Files.isRegularFile(image,
                        LinkOption.NOFOLLOW_LINKS))
                        throw new IOException(t("skins.invalidPath"));
            } else {
                Path temporary = directory.resolve("skin-" + UUID.randomUUID() + ".tmp");
                try {
                    try (var output = Files.newOutputStream(temporary,
                            StandardOpenOption.CREATE_NEW)) {
                        ImageIO.write(entry.image(), "PNG", output);
                    }
                    Files.move(temporary, image);
                } finally { Files.deleteIfExists(temporary); }
            }
            rows.add(new Stored(entry.id(), entry.name(), null, entry.slim()));
        }
        Path temporary = directory.resolve("skins-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, GSON.toJson(new Document(1, rows,
                activeId, activeSlim)), StandardOpenOption.CREATE_NEW);
        try {
            Files.move(temporary, directory.resolve("skins.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, directory.resolve("skins.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureDirectory() throws IOException {
        Path current = directory.getRoot();
        for (Path segment : directory) {
            current = current.resolve(segment);
            if (!Files.exists(current,
                    LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current,
                    LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(
                    current.toAbsolutePath().normalize()))
                    throw new IOException(t("skins.noLinks"));
        }
    }

    private static void checkId(String id) throws IOException {
        if (id == null || !id.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IOException(t("skins.invalidId"));
    }
}

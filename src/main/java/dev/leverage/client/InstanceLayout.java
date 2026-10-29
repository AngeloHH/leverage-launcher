package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class InstanceLayout {
    private static final Object PREPARATION_LOCK = new Object();
    private final Path root;

    public InstanceLayout(Path minecraftDirectory) {
        this.root = minecraftDirectory.toAbsolutePath().normalize().resolve(
                "leverage").resolve("instances");
    }

    public Path instance(String server, String version) {
        return root.resolve(safeSegment(server, "server")).resolve(
                safeSegment(version, "version")).normalize();
    }

    public Path mods(String server, String version) {
        return instance(server, version).resolve("mods");
    }

    public Path mods(String server, String version, String loader) {
        String safeLoader = safeSegment(loader, "loader");
        if (safeLoader.equals("default")) {
            return mods(server, version);
        }
        return instance(server, version).resolve(safeLoader).resolve("mods");
    }

    public static String serverScope(URI server) {
        if (server == null) return "offline";
        String normalized = LauncherPreferences.validateServerUrl(
                server.toString()).toString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(
                    normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 no está disponible en este Java.",
                    exception);
        }
    }

    // Full profile IDs keep Forge, Fabric and custom installations distinct.
    public Path managedInstance(URI server, String version) {
        if (!VersionId.isValid(version)) {
            throw new IllegalArgumentException(t("instance.invalidVersion"));
        }
        return root.resolve(serverScope(server)).resolve(fileSegment(version));
    }

    // Prepare the isolated game directory, not shared or user-selected folders.
    public Path prepare(URI server, String version) throws IOException {
        Path instance = managedInstance(server, version);
        synchronized (PREPARATION_LOCK) {
            migrateLegacyServer(server, instance.getParent());
            createDirectory(instance.resolve("mods"));
            createDirectory(instance.resolve("resourcepacks"));
        }
        return instance;
    }

    // Move the old server directory once, keeping its versions and files intact.
    private void migrateLegacyServer(URI server, Path target) throws IOException {
        if (server == null) return;
        URI origin = LauncherPreferences.validateServerUrl(server.toString());
        int port = origin.getPort() == -1 ? (origin.getScheme().equals("https")
                ? 443 : 80) : origin.getPort();
        Path source = root.resolve(origin.getScheme() + "--"
                + fileSegment(origin.getHost()) + "--" + port);
        if (!source.toAbsolutePath().normalize().getParent().equals(root)
                || !target.toAbsolutePath().normalize().getParent().equals(root)) {
            throw new IOException(t("instance.migrationOutside"));
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        createDirectory(root);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.t("instance.oldNotDirectory", source));
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Existen instancias en ambas carpetas; "
                    + "no se han combinado ni sobrescrito: " + source + " y " + target);
        }
        try (var entries = Files.walk(source)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isSymbolicLink(entry) || (!Files.isDirectory(entry,
                        LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(entry,
                        LinkOption.NOFOLLOW_LINKS)) || !entry.toRealPath().equals(
                        entry.toAbsolutePath().normalize())) {
                    throw new IOException(I18n.t("instance.migrationLinks", entry));
                }
            }
        }
        Files.move(source, target);
        // Never replace or merge an existing destination.
    }

    private static void createDirectory(Path target) throws IOException {
        Path current = target.getRoot();
        for (Path segment : target) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current); }
                catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Recheck below.
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current) || !current.toRealPath().equals(
                    current.toAbsolutePath().normalize())) {
                throw new IOException(I18n.t("instance.linkPath", current));
            }
        }
    }

    // Reversible filename escaping, not hashing. Preserve case identity even on Windows.
    private static String fileSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || (c == '.' && i < value.length() - 1)) result.append(c);
            else result.append('~').append(Integer.toHexString(c));
        }
        String filename = result.toString();
        if (filename.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
            filename = "~" + Integer.toHexString(filename.charAt(0))
                    + filename.substring(1);
        }
        return filename;
    }

    private static String safeSegment(String value, String label) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.length() > 100 || clean.equals(".")
                || clean.equals("..") || !clean.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException(I18n.t("instance.invalidSegment", label));
        }
        return clean;
    }
}

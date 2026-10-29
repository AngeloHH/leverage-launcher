package dev.leverage.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

public final class MirrorRepository {
    private final Path root;
    private final Path storage;
    private final Path versions;
    private final String jdbcUrl;

    public MirrorRepository(Path root) throws IOException, SQLException {
        this.root = root.toAbsolutePath().normalize();
        this.storage = this.root.resolve("storage").resolve("sha1");
        this.versions = this.root.resolve("versions");
        Files.createDirectories(storage);
        Files.createDirectories(versions);
        Files.createDirectories(this.root.resolve("temp"));
        this.jdbcUrl = "jdbc:sqlite:" + this.root.resolve("mirror.db");
        initialize();
    }

    public Path root() {
        return root;
    }

    public Path versionsDirectory() {
        return versions;
    }

    public Path tempDirectory() {
        return root.resolve("temp");
    }

    public Path blobPath(String sha1) {
        // Routes share a verified blob addressed by hash, not upstream URL.
        String hash = requireSha1(sha1);
        return storage.resolve(hash.substring(0,
                2)).resolve(hash.substring(2, 4)).resolve(hash);
    }

    public boolean isValidBlob(String sha1, long expectedSize) throws IOException {
        Path path = blobPath(sha1);
        return isValidBlobAt(path, sha1, expectedSize);
    }

    public boolean isValidBlobAt(Path path, String sha1,
            long expectedSize) throws IOException {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        if (expectedSize >= 0 && Files.size(path) != expectedSize) {
            return false;
        }
        return sha1(path).equalsIgnoreCase(sha1);
    }

    public void registerRoute(String route, String sha1, long size, String contentType,
            String originalName, String upstreamUrl) throws SQLException {
        String normalizedRoute = requireRoute(route);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO routes(route, sha1, size, content_type, "
                + "original_name, upstream_url)\n" + "VALUES (?, ?, ?, ?, ?, ?)\n"
                + "ON CONFLICT(route) DO UPDATE SET\n" + "    sha1=excluded.sha1,\n" +
                "    size=excluded.size,\n" + "    content_type=excluded.content_type,\n"
                + "    original_name=excluded.original_name,\n"
                + "    upstream_url=excluded.upstream_url\n")) {
            statement.setString(1, normalizedRoute);
            statement.setString(2, requireSha1(sha1));
            statement.setLong(3, size);
            statement.setString(4, contentType);
            statement.setString(5, originalName);
            statement.setString(6, upstreamUrl);
            statement.executeUpdate();
        }
    }

    public Resource resolve(String route) throws SQLException, IOException {
        String normalizedRoute = requireRoute(route);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                "SELECT sha1, size, content_type, original_name "
                + "FROM routes WHERE route=?\n")) {
            statement.setString(1, normalizedRoute);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String hash = result.getString("sha1");
                Path file = blobPath(hash);
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                return new Resource(file, result.getLong("size"), result.getString(
                        "content_type"), result.getString("original_name"), hash, true);
            }
        }
    }

    public Resource resolveManifest(String route) throws IOException {
        String normalized = requireManifestRoute(route);
        Path file;
        if (normalized.equals("/mirror/versions/index.json")) {
            file = versions.resolve("index.json");
        } else if (normalized.equals("/mirror/versions/version_manifest.json")
                || normalized.equals("/game/version_manifest.json")) {
            file = versions.resolve("version_manifest.json");
        } else if (normalized.equals("/mirror/versions/version_manifest_v2.json")
                || normalized.equals("/game/version_manifest_v2.json")) {
            file = versions.resolve("version_manifest_v2.json");
        } else {
            String[] segments = normalized.split("/");
            if (segments.length == 7 && segments[1].equals("mirror")
                    && segments[2].equals("versions") && segments[3].equals("java")
                    && segments[6].equals("manifest.json")) {
                file = versions.resolve("java").resolve(segments[4]).resolve(
                        segments[5]).resolve("manifest.json").normalize();
            } else if (segments.length == 5 && segments[1].equals("mirror")
                    && segments[2].equals("versions") && (segments[4].equals(
                    "version.json") || segments[4].equals("assets.json"))) {
                file = versions.resolve(segments[3]).resolve(segments[4]).normalize();
            } else {
                return null;
            }
        }
        if (!file.startsWith(versions) || !Files.isRegularFile(file)) {
            return null;
        }
        return new Resource(file, Files.size(file), "application/json; charset=utf-8",
                file.getFileName().toString(), sha1(file), false);
    }

    public static String sha1(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 no está disponible", exception);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS routes (\n"
                    + "    route TEXT PRIMARY KEY,\n" + "    sha1 TEXT NOT NULL,\n"
                    + "    size INTEGER NOT NULL,\n" + "    content_type TEXT NOT NULL,\n"
                    + "    original_name TEXT NOT NULL,\n"
                    + "    upstream_url TEXT NOT NULL\n" + ")\n");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS routes_sha1_idx ON routes(sha1)");
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private static String requireSha1(String value) {
        String normalized = value == null ? "" : value.toLowerCase();
        if (!normalized.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("SHA-1 inválido: " + value);
        }
        return normalized;
    }

    private static String requireRoute(String value) {
        if (value == null || !value.startsWith("/mirror/")
                || value.contains("..") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Ruta de mirror inválida");
        }
        return value;
    }

    private static String requireManifestRoute(String value) {
        if (value == null || value.contains("..") || value.indexOf('\0') >= 0
                || (!value.startsWith("/mirror/") && !value.startsWith("/game/"))) {
            throw new IllegalArgumentException("Ruta de manifiesto inválida");
        }
        return value;
    }

    public record Resource(Path file, long size, String contentType,
            String originalName, String sha1, boolean immutable) {
    }
}

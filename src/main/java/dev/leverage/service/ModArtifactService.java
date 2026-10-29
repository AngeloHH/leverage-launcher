package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Permission;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;

// A single catalog row describes a file, its download route and its allow/deny decision.
public final class ModArtifactService {
    public static final long MAX_MOD_BYTES = 256L * 1024 * 1024;
    private final Database database;
    private final Path storageRoot;

    public ModArtifactService(Database database, Path storageRoot,
            Clock clock) throws IOException {
        this.database = database;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.storageRoot.resolve(".incoming"));
        backfillResourcePackContent();
    }

    // =========================================================================
    // CONTENT PUBLICATION AND ACCESS RULES
    // =========================================================================

    public PublishedFile publish(AuthService.AuthenticatedUser actor,
            InputStream input, long declaredLength, String minecraftVersionValue,
            String loaderValue, String modIdValue, String modVersionValue,
            String artifactTypeValue) throws IOException {
        return publish(actor, input, declaredLength, ContentType.MOD,
                minecraftVersionValue, loaderValue, modIdValue,
                modVersionValue, artifactTypeValue);
    }

    public PublishedFile publish(AuthService.AuthenticatedUser actor,
            InputStream input, long declaredLength, ContentType contentType,
            String minecraftVersionValue, String loaderValue, String modIdValue,
            String modVersionValue, String artifactTypeValue) throws IOException {
        requireAdmin(actor);
        String version = segment(minecraftVersionValue, "minecraftVersion", false);
        String loader = contentType == ContentType.RESOURCEPACK ? "default"
                : segment(loaderValue, "loader", true);
        if (contentType == ContentType.MOD && !Set.of("fabric", "quilt",
                "forge", "neoforge").contains(loader)) {
            throw ServiceException.badRequest("UNSUPPORTED_MOD_LOADER",
                    "El loader no es compatible.");
        }
        String modId = segment(modIdValue, "modId", true);
        if (!modId.matches("[a-z][a-z0-9_-]{1,63}")) {
            throw ServiceException.badRequest("INVALID_MOD_ID", "El modId no es válido.");
        }
        String modVersion = segment(modVersionValue, "modVersion", false);
        String type = segment(artifactTypeValue, "artifactType", true);
        if (!Set.of("release", "sourcecode", "javadoc", "installer").contains(type)) {
            throw ServiceException.badRequest("INVALID_ARTIFACT_TYPE",
                    "Tipo de archivo no válido.");
        }
        Stored content = store(input, declaredLength, false);
        String id = save(contentType, version, loader, modId,
                modVersion, type, modId, content);
        return new PublishedFile(contentType.value, id, version, loader,
                modId, modVersion, type, modId, fileName(contentType,
                modId, modVersion, type), content.sha1(), content.size(),
                route(contentType, version, loader, modId, modVersion, type));
    }

    public PublishedDownload download(String version, String loader,
            String modId, String modVersion, String type) {
        return download(ContentType.MOD, version, loader, modId, modVersion, type);
    }

    public PublishedDownload download(ContentType contentType, String version,
            String loader, String modId, String modVersion, String type) {
        try (Connection connection = database.open();
                PreparedStatement query = connection.prepareStatement( """
                SELECT * FROM mods WHERE minecraft_version=? AND loader=? AND mod_id=?
                  AND mod_version=? AND artifact_type=? AND content_type=?
                """)) {
            query.setString(1, segment(version, "minecraftVersion", false));
            query.setString(2, contentType == ContentType.RESOURCEPACK
                    ? "default" : segment(loader, "loader", true));
            query.setString(3, segment(modId, "modId", true));
            query.setString(4, segment(modVersion, "modVersion", false));
            query.setString(5, segment(type, "artifactType", true));
            query.setString(6, contentType.value);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw ServiceException.notFound("MOD_FILE_NOT_FOUND",
                        "Archivo no publicado.");
                Path path = storedPath(row.getString("storage_path"));
                return new PublishedDownload(path, size(path), generatedName(row),
                        row.getString("sha1"), row.getString("artifact_type"));
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public Rule setRule(AuthService.AuthenticatedUser actor, String hashValue,
            String ignoredServer, String versionValue, String loaderValue,
            boolean allowed, boolean required, String ignoredReason) {
        return setRule(actor, hashValue, ignoredServer, versionValue, loaderValue,
                allowed, required, ignoredReason, ContentType.MOD);
    }

    public Rule setRule(AuthService.AuthenticatedUser actor,
            String hashValue, String ignoredServer, String versionValue,
            String loaderValue, boolean allowed, boolean required,
            String ignoredReason, ContentType contentType) {
        requireAdmin(actor);
        String hash = sha1(hashValue);
        String version = segment(versionValue, "minecraftVersion", false);
        String loader = contentType == ContentType.RESOURCEPACK ? "default"
                : segment(loaderValue, "loader", true);
        try {
            return database.transaction(connection -> {
                try (PreparedStatement query = connection.prepareStatement( """
                        SELECT * FROM mods WHERE sha1=? AND minecraft_version=? AND \
                        loader=? AND artifact_type='release'
                        AND content_type=?
                        ORDER BY mod_id,mod_version LIMIT 1
                        """)) {
                    query.setString(1, hash);
                    query.setString(2, version);
                    query.setString(3, loader);
                    query.setString(4, contentType.value);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) throw ServiceException.notFound(
                                "MOD_RELEASE_NOT_FOUND",
                                "No existe ese release publicado.");
                        Rule result = rule(row, allowed, allowed && required);
                        updateDecision(connection, hash, allowed, required);
                        return result;
                    }
                }
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // Compatibility with the former raw upload
    // endpoint; new uploads should use publish().
    public UploadResult upload(AuthService.AuthenticatedUser actor, InputStream input,
            long declaredLength, String server, String versionValue,
            String loaderValue, boolean allowed, String displayName, String oldFileName,
            boolean required, String ignoredReason) throws IOException {
        requireAdmin(actor);
        String version = segment(versionValue, "minecraftVersion", false);
        String loader = segment(loaderValue, "loader", true);
        String name = label(displayName, "name", 200);
        Stored content = store(input, declaredLength, true);
        String modId = oldFileName == null ? "mod_" + content.sha1().substring(0,
                12) : oldFileName.replaceFirst("(?i)\\.jar$",
                "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (!modId.matches("[a-z][a-z0-9_-]{1,63}")) modId = "mod_"
                + content.sha1().substring(0, 12);
        save(ContentType.MOD, version, loader, modId, "unspecified",
                "release", name, content);
        try {
            database.transaction(connection -> {
                updateDecision(connection, content.sha1(), allowed, required);
                return null;
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        return new UploadResult(server, version, loader, allowed, name,
                fileName(modId, "unspecified", "release"), content.sha1(),
                content.size(), allowed && required, content.path());
    }

    // =========================================================================
    // PUBLISHED CONTENT AND POLICY VIEWS
    // =========================================================================

    public Catalog catalog(String server, String versionValue, String loaderValue) {
        return catalog(server, versionValue, loaderValue, ContentType.MOD);
    }

    public Catalog catalog(String server, String versionValue,
            String loaderValue, ContentType contentType) {
        String version = segment(versionValue, "version", false);
        String loader = contentType == ContentType.RESOURCEPACK ? "default"
                : segment(loaderValue, "loader", true);
        List<Rule> allowed = new ArrayList<>();
        List<Rule> blocked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Connection connection = database.open();
                PreparedStatement query = connection.prepareStatement( """
                SELECT * FROM mods WHERE minecraft_version=? AND loader=?
                  AND content_type=? AND artifact_type='release' AND allowed IS NOT NULL \
                ORDER BY sha1,mod_id,mod_version
                """)) {
            query.setString(1, version);
            query.setString(2, loader);
            query.setString(3, contentType.value);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    if (!seen.add(row.getString("sha1"))) continue;
                    boolean decision = row.getInt("allowed") == 1;
                    Rule rule = rule(row, decision, decision
                            && row.getInt("required") == 1);
                    (decision ? allowed : blocked).add(rule);
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        String id = policyFingerprint(version, loader);
        return new Catalog(id, server, version, loader,
                List.copyOf(allowed), List.copyOf(blocked));
    }

    public Optional<PolicyData> policy(String server, String version, String loader) {
        List<Rule> allowed = new ArrayList<>();
        List<Rule> blocked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Connection connection = database.open();
                PreparedStatement query = connection.prepareStatement( """
                SELECT *,CASE WHEN minecraft_version=? AND (loader=? OR \
                content_type='resourcepack') THEN 1 ELSE 0 END AS matching
                FROM mods WHERE allowed IS NOT NULL AND artifact_type='release'
                ORDER BY matching DESC,sha1,mod_id,mod_version,content_type
                """)) {
            query.setString(1, version);
            query.setString(2, loader);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    if (!seen.add(row.getString("sha1"))) continue;
                    boolean decision = row.getInt("allowed") == 1;
                    Rule rule = rule(row, decision, decision && row.getInt(
                            "matching") == 1 && row.getInt("required") == 1);
                    (decision ? allowed : blocked).add(rule);
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        return Optional.of(new PolicyData(policyFingerprint(version, loader), server,
                version, loader, List.copyOf(allowed), List.copyOf(blocked)));
    }

    // =========================================================================
    // SERVER-DERIVED RESOURCE PACK EQUIVALENCE
    // =========================================================================

    // All equivalences originate from ZIPs stored on
    // this server, never from a client-supplied alias.
    public java.util.Map<String, List<String>> resourcePackAliases() {
        var groups = new java.util.HashMap<String, Set<String>> ();
        try (Connection connection = database.open();
                var query = connection.createStatement();
                ResultSet row = query.executeQuery( """
                     SELECT sha1,content_sha1 FROM mods WHERE content_type='resourcepack'
                     AND artifact_type='release' AND content_sha1 IS NOT NULL AND \
                     content_sha1<>''
                     """)) {
            while (row.next()) groups.computeIfAbsent(row.getString("content_sha1"),
                    ignored -> new LinkedHashSet<>()).add(row.getString("sha1"));
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        var result = new java.util.HashMap<String, List<String>> ();
        groups.forEach((content, archives) -> {
            List<String> equivalent = List.copyOf(archives);
            result.put(content, equivalent);
            for (String archive : archives) result.put(archive, equivalent);
        });
        return java.util.Map.copyOf(result);
    }

    private void backfillResourcePackContent() throws IOException {
        record Pending(String id, String hash, String path) { }
        List<Pending> pending = new ArrayList<>();
        try (Connection connection = database.open();
                var query = connection.createStatement();
                ResultSet row = query.executeQuery( """
                     SELECT id,sha1,storage_path FROM mods WHERE \
                     content_type='resourcepack'
                     AND artifact_type='release' AND content_sha1 IS NULL
                     """)) {
            while (row.next()) pending.add(new Pending(row.getString("id"),
                    row.getString("sha1"), row.getString("storage_path")));
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        for (Pending item : pending) {
            Path archive;
            try {
                archive = storedPath(item.path());
            } catch (ServiceException unavailable) {
                System.err.println(
                        "Sin equivalencia de contenido; falta el ZIP almacenado: "
                        + item.hash());
                continue;
            }
            MessageDigest digest = digest();
            try (var input = new java.security.DigestInputStream(
                    Files.newInputStream(archive), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            if (!item.hash().equals(HexFormat.of().formatHex(digest.digest()))) {
                System.err.println(("Sin equivalencia de contenido; el ZIP no coincide "
                        + "con su SHA-1: ") + item.hash());
                continue;
            }
            String content;
            try {
                content = dev.leverage.content.ResourcePackFingerprint.zip(
                        archive).sha1();
            } catch (IOException invalid) {
                // Old invalid uploads retain their byte-level
                // rule but cannot authorize an unpacked pack.
                content = "";
                System.err.println("Paquete sin equivalencia descomprimida: "
                        + item.hash() + ": " + invalid.getMessage());
            }
            try (Connection connection = database.open();
                    PreparedStatement update = connection.prepareStatement(
                    "UPDATE mods SET content_sha1=? WHERE id=? AND sha1="
                    + "? AND content_sha1 IS NULL")) {
                update.setString(1, content);
                update.setString(2, item.id());
                update.setString(3, item.hash());
                update.executeUpdate();
            } catch (SQLException exception) {
                throw databaseFailure(exception);
            }
        }
    }

    private String policyFingerprint(String version, String loader) {
        StringBuilder value = new StringBuilder(version).append(':').append(loader);
        try (Connection connection = database.open();
                var query = connection.createStatement();
                ResultSet row = query.executeQuery( """
                     SELECT minecraft_version,loader,mod_id,mod_version,sha1,allowed,req\
                     uired,content_type,content_sha1 FROM mods
                     WHERE allowed IS NOT NULL AND artifact_type='release'
                     ORDER BY minecraft_version,loader,mod_id,mod_version,sha1,content_t\
                     ype
                     """)) {
            while (row.next()) {
                for (int column = 1; column <= 9; column++) value.append(
                        '|').append(row.getString(column));
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        return "mods:" + HexFormat.of().formatHex(digest().digest(
                value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public Download download(String hashValue, String ignoredServer,
            String version, String loader) {
        String hash = sha1(hashValue);
        try (Connection connection = database.open();
                PreparedStatement query = connection.prepareStatement( """
                SELECT * FROM mods WHERE sha1=? AND allowed=1 AND artifact_type='release'
                  AND content_type='mod'
                  AND minecraft_version=? AND loader=? ORDER BY mod_id,mod_version LIMIT 1
                """)) {
            query.setString(1, hash);
            query.setString(2, version);
            query.setString(3, loader);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw ServiceException.notFound("MOD_NOT_FOUND",
                        "El mod no está permitido o publicado.");
                Path path = storedPath(row.getString("storage_path"));
                return new Download(path, size(path), generatedName(row), hash);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // CATALOG PERSISTENCE
    // =========================================================================

    private String save(ContentType contentType, String version,
            String loader, String modId, String modVersion, String type,
            String name, Stored content) throws IOException {
        String contentHash = contentType == ContentType.RESOURCEPACK && type.equals(
                "release") ? dev.leverage.content.ResourcePackFingerprint.zip(
                storedPath(content.path())).sha1() : null;
        try {
            return database.transaction(connection -> {
                try (PreparedStatement insert = connection.prepareStatement( """
                        INSERT INTO \
                        mods(id,content_type,minecraft_version,loader,mod_id,mod_version\
                        ,artifact_type,name,sha1,storage_path,content_sha1,allowed,requi\
                        red)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,(SELECT MIN(allowed) FROM mods \
                        WHERE sha1=?),
                                COALESCE((SELECT MAX(required) FROM mods WHERE sha1=?),0))
                        ON CONFLICT(content_type,minecraft_version,loader,mod_id,mod_ver\
                        sion,artifact_type) DO UPDATE SET
                            name=excluded.name,sha1=excluded.sha1,storage_path=excluded.\
                        storage_path,content_sha1=excluded.content_sha1,
                            allowed=excluded.allowed,required=excluded.required
                        """)) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, contentType.value);
                    insert.setString(3, version);
                    insert.setString(4, loader);
                    insert.setString(5, modId);
                    insert.setString(6, modVersion);
                    insert.setString(7, type);
                    insert.setString(8, name);
                    insert.setString(9, content.sha1());
                    insert.setString(10, content.path());
                    insert.setString(11, contentHash);
                    insert.setString(12, content.sha1());
                    insert.setString(13, content.sha1());
                    insert.executeUpdate();
                }
                try (PreparedStatement query = connection.prepareStatement( """
                        SELECT id FROM mods WHERE minecraft_version=? AND loader=? AND \
                        mod_id=? AND mod_version=? AND artifact_type=? AND content_type=?
                        """)) {
                    query.setString(1, version);
                    query.setString(2, loader);
                    query.setString(3, modId);
                    query.setString(4, modVersion);
                    query.setString(5, type);
                    query.setString(6, contentType.value);
                    try (ResultSet row = query.executeQuery()) {
                        row.next();
                        return row.getString(1);
                    }
                }
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private static void updateDecision(Connection connection, String hash,
            boolean allowed, boolean required) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE mods SET allowed=?,required=? WHERE sha1=?")) {
            update.setInt(1, allowed ? 1 : 0);
            update.setInt(2, allowed && required ? 1 : 0);
            update.setString(3, hash);
            update.executeUpdate();
        }
    }

    private Rule rule(ResultSet row, boolean allowed,
            boolean required) throws SQLException {
        Path path = storageRoot.resolve(row.getString("storage_path")).normalize();
        if (!path.startsWith(storageRoot)) throw new ServiceException(500,
                "MOD_STORAGE_ERROR", "La ruta del archivo no es válida.");
        return new Rule(allowed, row.getString("name"), generatedName(row), row.getString(
                "sha1"), Files.isRegularFile(path) ? size(path) : 0, required, allowed
                ? route(ContentType.from(row.getString("content_type")), row.getString(
                "minecraft_version"), row.getString("loader"), row.getString("mod_id"),
                row.getString("mod_version"), row.getString("artifact_type")) : null);
    }

    // =========================================================================
    // BOUNDED ARTIFACT STORAGE
    // =========================================================================

    private Stored store(InputStream input, long declaredLength,
            boolean validateJar) throws IOException {
        if (declaredLength > MAX_MOD_BYTES) throw new ServiceException(413,
                "MOD_TOO_LARGE", "El archivo supera los 256 MiB.");
        Path temporary = Files.createTempFile(storageRoot.resolve(".incoming"),
                "mod-", ".part");
        try {
            MessageDigest digest = digest();
            long size = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_MOD_BYTES) throw new ServiceException(413,
                            "MOD_TOO_LARGE", "El archivo supera los 256 MiB.");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size == 0) throw ServiceException.badRequest("EMPTY_MOD",
                    "El archivo está vacío.");
            if (validateJar) {
                try (ZipFile zip = new ZipFile(temporary.toFile())) {
                    if (!zip.entries().hasMoreElements())
                            throw ServiceException.badRequest("INVALID_MOD_JAR",
                            "El JAR está vacío.");
                } catch (IOException exception) {
                    throw ServiceException.badRequest("INVALID_MOD_JAR",
                            "El archivo no es un JAR válido.");
                }
            }
            String sha1 = HexFormat.of().formatHex(digest.digest());
            Path destination = storageRoot.resolve(sha1.substring(0,
                    2)).resolve(sha1.substring(2, 4)).resolve(sha1);
            Files.createDirectories(destination.getParent());
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Stored(sha1, size, storageRoot.relativize(
                    destination).toString().replace('\\', '/'));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path storedPath(String relative) {
        Path path = storageRoot.resolve(relative).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path))
                throw new ServiceException(500, "MOD_STORAGE_ERROR",
                "El archivo no está disponible.");
        return path;
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ServiceException(500, "MOD_STORAGE_ERROR",
                    "No se pudo consultar el archivo.");
        }
    }

    private static String generatedName(ResultSet row) throws SQLException {
        return fileName(ContentType.from(row.getString("content_type")), row.getString(
                "mod_id"), row.getString("mod_version"), row.getString("artifact_type"));
    }

    private static String fileName(String id, String version, String type) {
        return fileName(ContentType.MOD, id, version, type);
    }

    private static String fileName(ContentType contentType,
            String id, String version, String type) {
        String suffix = switch (type) {
            case "sourcecode" -> "-sources";
            case "javadoc" -> "-javadoc";
            case "installer" -> "-installer";
            default -> "";
        };
        return id + "-" + version + suffix + (contentType == ContentType.RESOURCEPACK
                || Set.of("sourcecode", "javadoc").contains(type) ? ".zip" : ".jar");
    }

    private static String route(ContentType contentType, String version,
            String loader, String id, String modVersion, String type) {
        return "/" + contentType.value + "/" + version + "/" + (contentType ==
                ContentType.MOD ? loader + "/" : "") + id + "/" + modVersion + "/" + type;
    }

    public enum ContentType {
        MOD("mod"), RESOURCEPACK("resourcepack");
        private final String value;
        ContentType(String value) {
            this.value = value;
        }
        public static ContentType from(String value) {
            if (value == null || value.equals("mod")) return MOD;
            if (value.equals("resourcepack")) return RESOURCEPACK;
            throw ServiceException.badRequest("INVALID_CONTENT_TYPE",
                    "El tipo debe ser mod o resourcepack.");
        }
    }

    // =========================================================================
    // ROUTE SEGMENTS AND HASH VALIDATION
    // =========================================================================

    private static String segment(String value, String field, boolean lowercase) {
        String clean = label(value, field, 100);
        if (lowercase) clean = clean.toLowerCase(Locale.ROOT);
        if (!clean.matches("[A-Za-z0-9][A-Za-z0-9._+~-]{0,99}") || clean.contains(".."))
                throw ServiceException.badRequest("INVALID_" + field.toUpperCase(
                Locale.ROOT), "El campo " + field + " no es válido.");
        return clean;
    }

    private static String label(String value, String field, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0)
                throw ServiceException.badRequest("INVALID_LABEL",
                "El campo " + field + " no es válido.");
        return clean;
    }

    private static String sha1(String value) {
        String hash = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{40}")) throw ServiceException.badRequest(
                "INVALID_SHA1", "El SHA-1 no es válido.");
        return hash;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireAdmin(AuthService.AuthenticatedUser actor) {
        if (actor == null || !actor.permissions().contains(Permission.SYSTEM_ADMIN))
                throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                "Solo un administrador puede publicar mods.");
    }

    private static ServiceException databaseFailure(SQLException exception) {
        exception.printStackTrace(System.err);
        return new ServiceException(500, "DATABASE_ERROR",
                "No se pudo completar la operación.");
    }

    // =========================================================================
    // CATALOG AND DOWNLOAD VALUES
    // =========================================================================

    public record UploadResult(String server, String version, String loader,
            boolean allowed, String displayName, String fileName, String sha1,
            long size, boolean required, String storagePath) { }
    public record Rule(boolean allowed, String displayName, String fileName,
            String sha1, long size, boolean required, String downloadPath) { }
    public record Catalog(String policyId, String server, String version,
            String loader, List<Rule> allowed, List<Rule> blocked) { }
    public record PolicyData(String policyId, String server, String version,
            String loader, List<Rule> allowed, List<Rule> blocked) { }
    public record Download(Path path, long size, String fileName, String sha1) { }
    public record PublishedFile(String type, String uuid, String minecraftVersion,
            String loader, String modId, String modVersion,
            String artifactType, String displayName, String fileName,
            String sha1, long size, String downloadPath) { }
    public record PublishedDownload(Path path, long size, String fileName,
            String sha1, String artifactType) { }
    private record Stored(String sha1, long size, String path) { }
}

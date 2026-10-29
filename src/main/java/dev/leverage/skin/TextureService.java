package dev.leverage.skin;

import dev.leverage.db.Database;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

public final class TextureService {
    public static final int MAX_TEXTURE_BYTES = 2 * 1024 * 1024;

    private final Database database;
    private final Path storageRoot;
    private final URI publicHost;
    private final Clock clock;
    private final YggdrasilKeys keys;

    public TextureService(Database database, Path storageRoot, Path keyDirectory,
            URI publicHost, Clock clock) throws IOException {
        this.database = database;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.publicHost = publicHost;
        this.clock = clock;
        Files.createDirectories(this.storageRoot);
        this.keys = new YggdrasilKeys(keyDirectory);
    }

    // =========================================================================
    // OWNED TEXTURE UPDATES
    // =========================================================================

    public MinecraftProfile upload(AuthService.AuthenticatedUser actor,
            TextureType type, SkinModel model, byte[] uploaded) {
        if (uploaded == null || uploaded.length == 0
                || uploaded.length > MAX_TEXTURE_BYTES) {
            throw new ServiceException(413, "TEXTURE_TOO_LARGE",
                    "La textura debe ser un PNG de hasta 2 MiB.");
        }
        EncodedTexture texture = validateAndEncode(type, uploaded);
        Path destination = pathFor(texture.hash());
        try {
            Files.createDirectories(destination.getParent());
            if (!Files.exists(destination)) {
                Path temporary = Files.createTempFile(destination.getParent(),
                        "texture-", ".tmp");
                try {
                    Files.write(temporary, texture.png());
                    try {
                        Files.move(temporary, destination,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, destination);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return database.transaction(connection -> {
                long now = clock.instant().toEpochMilli();
                try (PreparedStatement statement = connection.prepareStatement( """
                        INSERT INTO textures(user_id,type,hash,storage_path,model)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(user_id,type) DO UPDATE SET hash=excluded.hash,
                            storage_path=excluded.storage_path,model=excluded.model
                        """)) {
                    statement.setString(1, actor.user().id().toString());
                    statement.setString(2, type.value());
                    statement.setString(3, texture.hash());
                    statement.setString(4, storageRoot.relativize(
                            destination).toString().replace('\\', '/'));
                    statement.setString(5, type == TextureType.SKIN
                            ? model.value() : null);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET updated_at=? WHERE id=?")) {
                    statement.setLong(1, now);
                    statement.setString(2, actor.user().id().toString());
                    statement.executeUpdate();
                }
                return findById(connection, actor.user().id());
            });
        } catch (IOException exception) {
            throw new ServiceException(500, "TEXTURE_STORAGE_ERROR",
                    "No se pudo guardar la textura.");
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public MinecraftProfile clear(AuthService.AuthenticatedUser actor, TextureType type) {
        try {
            return database.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM textures WHERE user_id=? AND type=?")) {
                    statement.setString(1, actor.user().id().toString());
                    statement.setString(2, type.value());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET updated_at=? WHERE id=?")) {
                    statement.setLong(1, clock.instant().toEpochMilli());
                    statement.setString(2, actor.user().id().toString());
                    statement.executeUpdate();
                }
                return findById(connection, actor.user().id());
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // PUBLIC PROFILE LOOKUPS
    // =========================================================================

    public MinecraftProfile profileFor(AuthService.AuthenticatedUser actor) {
        return profileById(actor.user().id(), true);
    }

    public MinecraftProfile profileById(UUID id, boolean createIfMissing) {
        try {
            return database.transaction(connection -> {
                return findById(connection, id);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public MinecraftProfile profileByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        try {
            return database.transaction(connection -> {
                UUID userId = null;
                try (PreparedStatement user = connection.prepareStatement(
                        "SELECT id FROM users WHERE username=? COLLATE "
                        + "NOCASE AND status='ACTIVE' LIMIT 1")) {
                    user.setString(1, normalized);
                    try (ResultSet row = user.executeQuery()) {
                        if (row.next()) {
                            userId = UUID.fromString(row.getString("id"));
                        }
                    }
                }
                if (userId == null) {
                    return null;
                }
                return findById(connection, userId);
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // TEXTURE DELIVERY AND PROFILE SIGNING
    // =========================================================================

    public byte[] readTexture(String hash) {
        if (DefaultSkin.sha1().equals(hash)) return DefaultSkin.png();
        if (hash == null || !hash.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            return null;
        }
        try (Connection connection = database.open();
                PreparedStatement query = connection.prepareStatement(
                "SELECT storage_path FROM textures WHERE hash=? LIMIT 1")) {
            query.setString(1, hash);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                Path path = storageRoot.resolve(row.getString(
                        "storage_path")).normalize();
                if (!path.startsWith(storageRoot)) return null;
                return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
            }
        } catch (IOException exception) {
            throw new ServiceException(500, "TEXTURE_STORAGE_ERROR",
                    "No se pudo leer la textura.");
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public String textureUrl(String hash) {
        return publicHost + "/api/yggdrasil/textures/" + hash;
    }

    public String skinUrl(MinecraftProfile profile) {
        return textureUrl(profile.skinHash() == null
                ? DefaultSkin.sha1() : profile.skinHash());
    }

    public String publicKeyPem() {
        return keys.publicKeyPem();
    }

    public String sign(String value) {
        return keys.sign(value);
    }

    public String skinDomain() {
        String host = publicHost.getHost();
        return host.equalsIgnoreCase("localhost") || host.contains(":")
                || host.matches("[0-9.]+") ? host : "." + host;
    }

    public Instant now() {
        return clock.instant();
    }

    // =========================================================================
    // STORED PROFILE VALUES
    // =========================================================================

    private static MinecraftProfile findById(Connection connection,
            UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT u.id, u.username AS name, s.hash AS skin_hash,
                       COALESCE(s.model,'classic') AS skin_model, c.hash AS cape_hash, \
                u.updated_at
                FROM users u LEFT JOIN textures s ON s.user_id=u.id AND s.type='skin'
                LEFT JOIN textures c ON c.user_id=u.id AND c.type='cape'
                WHERE u.id=? AND u.status='ACTIVE' LIMIT 1
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readProfile(row) : null;
            }
        }
    }

    private static MinecraftProfile readProfile(ResultSet row) throws SQLException {
        return new MinecraftProfile(UUID.fromString(row.getString("id")),
                row.getString("name"), row.getString("skin_hash"),
                SkinModel.from(row.getString("skin_model")), row.getString("cape_hash"),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    // =========================================================================
    // IMAGE BOUNDS AND CANONICAL PNG STORAGE
    // =========================================================================

    private EncodedTexture validateAndEncode(TextureType type, byte[] uploaded) {
        if (uploaded.length < 8 || uploaded[0] != (byte) 0x89 || uploaded[1] != 'P'
                || uploaded[2] != 'N' || uploaded[3] != 'G') {
            throw ServiceException.badRequest("INVALID_TEXTURE",
                    "La textura debe ser una imagen PNG válida.");
        }
        try {
            BufferedImage decoded;
            int width;
            int height;
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(uploaded))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    throw ServiceException.badRequest("INVALID_TEXTURE",
                            "La textura debe ser una imagen PNG válida.");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    boolean dimensionsValid = type == TextureType.SKIN ? width == 64 && (
                            height == 64 || height == 32) : width == 64 && height == 32;
                    if (!dimensionsValid) {
                        String expected = type == TextureType.SKIN
                                ? "64x64 o 64x32" : "64x32";
                        throw ServiceException.badRequest("INVALID_TEXTURE_DIMENSIONS",
                                "Las dimensiones requeridas para " + type.value()
                                + " son " + expected + ".");
                    }
                    decoded = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
            BufferedImage canonical = new BufferedImage(width,
                    height, BufferedImage.TYPE_INT_ARGB);
            var graphics = canonical.createGraphics();
            try {
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(canonical, "PNG", output)) {
                throw new IOException("No existe un codificador PNG.");
            }
            byte[] png = output.toByteArray();
            return new EncodedTexture(sha1(png), png, width, height);
        } catch (IOException exception) {
            throw ServiceException.badRequest("INVALID_TEXTURE",
                    "La textura debe ser una imagen PNG válida.");
        }
    }

    private Path pathFor(String hash) {
        return storageRoot.resolve(hash.substring(0,
                2)).resolve(hash.substring(2, 4)).resolve(hash);
    }

    private static String sha1(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-1").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 no está disponible.", exception);
        }
    }

    private static ServiceException databaseFailure(SQLException exception) {
        exception.printStackTrace(System.err);
        return new ServiceException(500, "DATABASE_ERROR",
                "No se pudo completar la operación.");
    }

    private record EncodedTexture(String hash, byte[] png, int width, int height) { }

    public enum TextureType {
        SKIN("skin"), CAPE("cape");

        private final String value;

        TextureType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static TextureType from(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw ServiceException.badRequest("INVALID_TEXTURE_TYPE",
                        "El tipo debe ser skin o cape.");
            }
        }
    }

    public enum SkinModel {
        CLASSIC("classic"), SLIM("slim");

        private final String value;

        SkinModel(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static SkinModel from(String value) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("classic")
                    || value.equalsIgnoreCase("default")) {
                return CLASSIC;
            }
            if (value.equalsIgnoreCase("slim")) {
                return SLIM;
            }
            throw ServiceException.badRequest("INVALID_SKIN_MODEL",
                    "El modelo debe ser classic o slim.");
        }
    }

    public record MinecraftProfile(UUID id, String name, String skinHash,
            SkinModel skinModel, String capeHash, Instant updatedAt) { }
}

package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

// One stored article: saving makes it a draft;
// publishing makes that same article visible.
public final class NewsService {
    public static final int MAX_MARKDOWN = 32_000, MAX_IMAGE_BYTES = 300_000;
    public record Item(UUID id, String markdown, boolean draft, UUID owner,
            String imageBase64, String createdAt, String modifiedAt) { }
    private final Database database;
    private final Clock clock;

    public NewsService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
        try {
            initialize();
        } catch (SQLException e) {
            throw failure();
        }
    }

    // =========================================================================
    // ARTICLE STORAGE AND SCHEMA UPGRADES
    // =========================================================================

    private static String schema(String table) {
        return "CREATE TABLE " + table + """
         (id TEXT PRIMARY KEY, markdown TEXT NOT NULL,
          draft INTEGER NOT NULL CHECK(draft IN (0,1)),
          owner TEXT REFERENCES users(id) ON DELETE RESTRICT, image BLOB,
          created_at INTEGER, modified_at INTEGER NOT NULL)
        """;
    }

    private void initialize() throws SQLException {
        Set<String> columns = new HashSet<>();
        boolean deletions;
        try (var c = database.open(); var s = c.createStatement()) {
            try (var r = s.executeQuery("PRAGMA table_info(news_articles)")) {
                while (r.next()) columns.add(r.getString("name"));
            }
            try (var r = s.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND "
                    + "name='news_deletions'")) {
                deletions = r.next();
            }
        }
        if (!columns.isEmpty() && !columns.contains("draft")) {
            if (!columns.containsAll(Set.of("id", "markdown", "image",
                    "revision", "published_revision", "published_at",
                    "updated_at"))) throw new SQLException("Unknown news schema");
            // Back up before removing old snapshots/tombstones.
            // Unknown authors and creation dates stay unknown.
            database.backup("before-news-simplification");
            String owner = columns.contains("created_by") ? "created_by" : "NULL";
            database.transaction(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate(schema("news_articles_simple"));
                    s.executeUpdate((
                            "INSERT INTO news_articles_simple SELECT id,markdown,"
                            + "CASE WHEN published_at IS NOT NULL AND "
                            + "published_revision=revision THEN 0 ELSE 1 END,") + owner
                            + ",image,NULL,updated_at FROM news_articles" + (deletions
                            ? (" WHERE NOT EXISTS (SELECT 1 FROM news_deletions d "
                            + "WHERE d.article_id=news_articles.id)") : ""));
                    if (deletions) s.executeUpdate("DROP TABLE news_deletions");
                    s.executeUpdate("DROP TABLE news_articles");
                    s.executeUpdate(
                            "ALTER TABLE news_articles_simple RENAME TO news_articles");
                }
                return null;
            });
        } else if (columns.isEmpty() && !deletions) {
            database.transaction(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate(schema("news_articles"));
                }
                return null;
            });
        } else if (!columns.equals(Set.of("id", "markdown", "draft",
                "owner", "image", "created_at", "modified_at")) || deletions)
                throw new SQLException("Unknown news schema");
    }

    // =========================================================================
    // DRAFTS, PUBLICATION AND DELETION
    // =========================================================================

    public List<Item> list(AuthService.AuthenticatedUser actor, boolean drafts) {
        try {
            return database.transaction(c -> {
                Role role = drafts ? requireEditor(c, actor) : null;
                var items = new ArrayList<Item>();
                boolean moderator = role == Role.MODERATOR;
                String filter = drafts ? (moderator ? "WHERE owner=? OR draft=0 "
                        : "") : "WHERE draft=0 ";
                try (var q = c.prepareStatement("SELECT * FROM news_articles "
                        + filter + "ORDER BY modified_at DESC,id LIMIT 20")) {
                    if (moderator) q.setString(1, actor.user().id().toString());
                    try (var r = q.executeQuery()) {
                        while (r.next()) items.add(item(r));
                    }
                }
                return List.copyOf(items);
            });
        } catch (SQLException e) {
            throw failure();
        }
    }

    public Item save(AuthService.AuthenticatedUser actor, UUID id,
            boolean create, String markdown, String imageBase64) {
        if (id == null) throw ServiceException.badRequest("INVALID_NEWS",
                "La noticia no es válida.");
        if (markdown == null || markdown.isBlank() || markdown.length() > MAX_MARKDOWN
                || markdown.indexOf('\0') >= 0) throw ServiceException.badRequest(
                "INVALID_NEWS_TEXT", "Escribe una noticia de hasta 32000 caracteres.");
        byte[] image = validateImage(imageBase64);
        try {
            return database.transaction(c -> {
                Role role = requireEditor(c, actor);
                requireOwnership(c, actor, id, role);
                long now = clock.millis();
                if (create) {
                    try (var q = c.prepareStatement(
                            "INSERT OR IGNORE INTO news_articles(id,markdown,"
                            + "draft,owner,image,created_at,modified_at) VALUES(?,"
                            + "?,1,?,?,?,?)")) {
                        q.setString(1, id.toString());
                        q.setString(2, markdown);
                        q.setString(3, actor.user().id().toString());
                        q.setBytes(4, image);
                        q.setLong(5, now);
                        q.setLong(6, now);
                        if (q.executeUpdate() != 1) throw conflict();
                    }
                } else try (var q = c.prepareStatement(
                        "UPDATE news_articles SET markdown=?,image=?,draft=1,"
                        + "modified_at=? WHERE id=?")) {
                    q.setString(1, markdown);
                    q.setBytes(2, image);
                    q.setLong(3, now);
                    q.setString(4, id.toString());
                    if (q.executeUpdate() != 1) throw conflict();
                }
                return find(c, id);
            });
        } catch (SQLException e) {
            throw failure();
        }
    }

    public Item publish(AuthService.AuthenticatedUser actor, UUID id) {
        try {
            return database.transaction(c -> {
                requireAdmin(c, actor);
                try (var q = c.prepareStatement(
                        "UPDATE news_articles SET draft=0,modified_at=? WHERE id=?")) {
                    q.setLong(1, clock.millis());
                    q.setString(2, id.toString());
                    if (q.executeUpdate() != 1) throw conflict();
                }
                return find(c, id);
            });
        } catch (SQLException e) {
            throw failure();
        }
    }

    public void delete(AuthService.AuthenticatedUser actor, UUID id) {
        if (id == null) throw ServiceException.badRequest("INVALID_NEWS",
                "La noticia no es válida.");
        try {
            database.transaction(c -> {
                requireAdmin(c, actor);
                try (var q = c.prepareStatement("DELETE FROM news_articles WHERE id=?")) {
                    q.setString(1, id.toString());
                    if (q.executeUpdate() != 1) throw conflict();
                }
                return null;
            });
        } catch (SQLException e) {
            throw failure();
        }
    }

    private Item find(Connection c, UUID id) throws SQLException {
        try (var q = c.prepareStatement("SELECT * FROM news_articles WHERE id=?")) {
            q.setString(1, id.toString());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw conflict();
                return item(r);
            }
        }
    }

    private static Item item(ResultSet r) throws SQLException {
        byte[] image = r.getBytes("image");
        String owner = r.getString("owner");
        return new Item(UUID.fromString(r.getString("id")), r.getString("markdown"),
                r.getBoolean("draft"), owner == null ? null : UUID.fromString(owner),
                image == null ? null : Base64.getEncoder().encodeToString(image),
                r.getObject("created_at") == null ? null
                : Instant.ofEpochMilli(r.getLong("created_at")).toString(),
                Instant.ofEpochMilli(r.getLong("modified_at")).toString());
    }

    // =========================================================================
    // IDENTITY AND ARTICLE OWNERSHIP
    // =========================================================================

    private void requireAdmin(Connection c,
            AuthService.AuthenticatedUser actor) throws SQLException {
        if (requireEditor(c, actor) != Role.ADMIN) throw denied();
    }

    private Role requireEditor(Connection c,
            AuthService.AuthenticatedUser actor) throws SQLException {
        if (actor == null || actor.permissions() == null
                || !actor.permissions().contains(Permission.USERS_MODERATE))
                throw ServiceException.forbidden("NEWS_EDITOR_REQUIRED",
                "Solo administradores y moderadores pueden crear o "
                + "editar noticias.");
        // Recheck revocation, current role and
        // sanctions within the mutation's transaction.
        try (var q = c.prepareStatement( """
            SELECT u.role FROM users u JOIN sessions s ON s.user_id=u.id
            WHERE u.id=? AND u.role IN ('ADMIN','MODERATOR') AND u.status='ACTIVE' AND \
            s.id=?
              AND s.revoked_at IS NULL AND s.expires_at>?
              AND NOT EXISTS (SELECT 1 FROM account_sanctions a WHERE a.user_id=u.id
                AND (a.resolution='BANNED' OR a.resolution IS NULL)
                AND (a.expires_at IS NULL OR a.expires_at>?))
            """)) {
            q.setString(1, actor.user().id().toString());
            q.setString(2, actor.sessionId());
            q.setLong(3, clock.millis());
            q.setLong(4, clock.millis());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw denied();
                return Role.valueOf(r.getString(1));
            }
        }
    }

    private void requireOwnership(Connection c, AuthService.AuthenticatedUser actor,
            UUID id, Role role) throws SQLException {
        if (role == Role.ADMIN) return;
        try (var q = c.prepareStatement("SELECT owner FROM news_articles WHERE id=?")) {
            q.setString(1, id.toString());
            try (var r = q.executeQuery()) {
                if (r.next() && !actor.user().id().toString().equals(r.getString(1)))
                        throw ServiceException.forbidden("NEWS_AUTHOR_REQUIRED",
                        "Solo puedes editar las noticias que hayas creado.");
            }
        }
    }

    // =========================================================================
    // BOUNDED IMAGE DECODING
    // =========================================================================

    public static byte[] validateImage(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        if (encoded.length() >((MAX_IMAGE_BYTES + 2) / 3) * 4)
                throw ServiceException.badRequest("NEWS_IMAGE_TOO_LARGE",
                "La imagen debe pesar hasta 300 KB.");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException();
            try (var input = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName();
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (!(format.equalsIgnoreCase("PNG") || format.equalsIgnoreCase(
                            "JPEG")) || width < 1 || height < 1 || width > 4096
                            || height > 4096 || (long)width * height > 8_000_000)
                            throw new IllegalArgumentException();
                    if (reader.read(0) == null) throw new IllegalArgumentException();
                } finally {
                    reader.dispose();
                }
            }
            return bytes;
        } catch (Exception e) {
            throw ServiceException.badRequest("INVALID_NEWS_IMAGE",
                    "Selecciona una imagen PNG o JPEG válida (máximo "
                    + "4096 px y 300 KB).");
        }
    }

    private static ServiceException denied() {
        return ServiceException.forbidden("NEWS_ADMIN_REQUIRED",
                "No tienes permiso para esta operación de noticias.");
    }
    private static ServiceException conflict() {
        return ServiceException.conflict("NEWS_CONFLICT",
                "La noticia ya no existe o el identificador está "
                + "ocupado. Vuelve a abrir Noticias.");
    }
    private static ServiceException failure() {
        return new ServiceException(500, "NEWS_STORAGE_ERROR",
                "No se pudo guardar o cargar la noticia.");
    }
}

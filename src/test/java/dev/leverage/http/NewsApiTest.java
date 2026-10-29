package dev.leverage.http;

import dev.leverage.client.*;
import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsApiTest {
    @TempDir Path directory;
    Database database;
    AuthService auth;
    ApiServer server;
    URI host;
    String adminToken, userToken;
    NewsService service;
    final LauncherNewsClient client=new LauncherNewsClient();
    @BeforeEach void setup()throws Exception {
        database=new Database(directory.resolve("news.db"));
        database.initialize();
        auth=new AuthService(database, new PasswordHasher(10_000), new RecoveryMailer() {
            public void sendEmailVerification(String e, String u,
                    String t, Instant at) { }
            public void sendPasswordReset(String e, String u, String t, Instant at) { }
        }, Clock.systemUTC(), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofMinutes(15), true);
        auth.createBootstrapAdmin("Editor", "editor@example.test", "test-news-password");
        var user=auth.register("Reader", "reader@example.test", "test-news-password");
        database.transaction(c-> {
            try (var q=c.prepareStatement(
                    "UPDATE users SET status='ACTIVE' WHERE id=?")) {
                q.setString(1, user.id().toString());
                q.executeUpdate();
            }
            return null;
        });
        adminToken=auth.login("Editor", "test-news-password").accessToken();
        userToken=auth.login("Reader", "test-news-password").accessToken();
        server=new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        host=URI.create("http://127.0.0.1:"+server.port());
        service=auth.news();
    }
    @AfterEach void close() { if (server!=null)server.close(); }
    String image(int color)throws Exception {
        var image=new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setColor(new java.awt.Color(color));
        g.fillRect(0, 0, 120, 80);
        g.dispose();
        Path file=directory.resolve("image-"+color+".png");
        ImageIO.write(image, "PNG", file.toFile());
        return LauncherNewsClient.importImage(file);
    }
    String moderator(String name)throws Exception {
        var user=auth.register(name, name.toLowerCase(Locale.ROOT)
                +"@example.test", "test-news-password");
        database.transaction(c-> {
            try (var q=c.prepareStatement(
                    "UPDATE users SET status='ACTIVE',role='MODERATOR' WHERE id=?")) {
                q.setString(1, user.id().toString());
                q.executeUpdate();
            }
            return null;
        });
        return auth.login(name, "test-news-password").accessToken();
    }
    @Test void moderatorsOwnNewsAndAdminsEditAny()throws Exception {
        String mod=moderator("ModOne"), other=moderator("ModTwo");
        UUID creator=auth.authenticate(mod).user().id();
        UUID id=UUID.randomUUID();
        var draft=client.save(host, mod, id, true, "# Mi primera noticia", null);
        assertEquals(creator, draft.owner());
        assertEquals("# Mi revisión", client.save(host, mod, id,
                false, "# Mi revisión", null).markdown());
        assertThrows(java.io.IOException.class, ()->client.save(host,
                other, id, false, "# Intento ajeno", null));
        var edited=client.save(host, adminToken, id, false,
                "# Revisión administrativa", null);
        assertEquals(creator, edited.owner());
        assertEquals(creator, client.save(host, mod, id, false,
                "# El autor todavía puede editar", null).owner());
        var adminNews=client.save(host, adminToken, UUID.randomUUID(),
                true, "# Del administrador", null);
        assertThrows(java.io.IOException.class, ()->client.save(host, mod,
                adminNews.id(), false, "# No permitido", null));
        assertThrows(java.io.IOException.class, ()->client.save(host, mod,
                adminNews.id(), true, "# Tampoco creando con el mismo ID", null));
        assertThrows(java.io.IOException.class, ()->client.publish(host, mod, id));
        assertThrows(java.io.IOException.class, ()->client.delete(host, mod, id));
        client.publish(host, adminToken, id);
        assertEquals(creator, client.list(host, userToken, false).getFirst().owner());
    }
    @Test void
            moderatorFeedNeverLeaksOtherDraftTextOrImagesAndCannotForgeTheCreator()throws
            Exception {
        String mod=moderator("NewsMod"), other=moderator("OtherMod");
        UUID creator=auth.authenticate(mod).user().id();
        var own=client.save(host, mod, UUID.randomUUID(), true, "# Mi borrador", null);
        var hidden=client.save(host, adminToken, UUID.randomUUID(),
                true, "# Secreto administrativo", null);
        var hiddenMod=client.save(host, other, UUID.randomUUID(),
                true, "# Borrador de otro moderador", null);
        String publicImage=image(0x218844), privateImage=image(0x884421);
        var article=client.save(host, adminToken, UUID.randomUUID(),
                true, "# Texto público", publicImage);
        client.publish(host, adminToken, article.id());
        var feed=client.list(host, mod, true);
        assertEquals(2, feed.size());
        assertTrue(feed.stream().anyMatch(i->i.id().equals(own.id())));
        assertFalse(feed.stream().anyMatch(i->i.id().equals(hidden.id())
                ||i.id().equals(hiddenMod.id())));
        var visible=feed.stream().filter(i->i.id().equals(
                article.id())).findFirst().orElseThrow();
        assertEquals("# Texto público", visible.markdown());
        assertEquals(publicImage, visible.imageBase64());
        assertFalse(visible.draft());
        client.save(host, adminToken, article.id(), false,
                "# Revisión secreta", privateImage);
        assertEquals(List.of(own), client.list(host, mod, true));
        assertTrue(client.list(host, userToken, false).isEmpty());
        var body=new com.google.gson.JsonObject();
        body.addProperty("markdown", "# Autor real");
        body.addProperty("owner", auth.authenticate(adminToken).user().id().toString());
        body.addProperty("draft", false);
        UUID forgedId=UUID.randomUUID();
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                host.resolve("/api/v1/admin/news/"+forgedId)).header("Authorization",
                "Bearer "+mod).header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(creator, client.list(host, mod, true).stream().filter(
                i->i.id().equals(forgedId)).findFirst().orElseThrow().owner());
        assertTrue(client.list(host, userToken, false).isEmpty());
    }
    @Test void oldRowsWithoutKnownOriginalAuthorsRemainAdministratorOnly()throws
            Exception {
        String mod=moderator("LegacyMod");
        var article=client.save(host, adminToken, UUID.randomUUID(),
                true, "# Histórica", null);
        database.transaction(c-> {
            try (var q=c.prepareStatement(
                    "UPDATE news_articles SET owner=NULL WHERE id=?")) {
                q.setString(1, article.id().toString());
                q.executeUpdate();
            }
            return null;
        });
        assertThrows(java.io.IOException.class, ()->client.save(host, mod,
                article.id(), false, "# No es mía por ser el último editor", null));
        assertNull(client.save(host, adminToken, article.id(), false,
                "# Conservada por administración", null).owner());
    }
    @Test void migrationAddsUnknownAuthorWithoutGuessingTheLastEditor()throws Exception {
        var legacy=new Database(directory.resolve("legacy.db"));
        legacy.initialize();
        UUID id=UUID.randomUUID();
        String lastEditor=UUID.randomUUID().toString();
        legacy.transaction(c-> {
            try (var s=c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE news_articles(id TEXT PRIMARY KEY,markdown TEXT"
                        + " NOT NULL,image BLOB,revision INTEGER NOT NULL,published_mar"
                        + "kdown TEXT,published_image BLOB,published_revision INTEGER,u"
                        + "pdated_at INTEGER NOT NULL,published_at INTEGER,updated_by T"
                        + "EXT NOT NULL)");
            }
            try (var q=c.prepareStatement(
                    "INSERT INTO news_articles VALUES(?,?,NULL,1,?,NULL,1,1,1,?)")) {
                q.setString(1, id.toString());
                q.setString(2, "# Histórica");
                q.setString(3, "# Histórica");
                q.setString(4, lastEditor);
                q.executeUpdate();
            }
            return null;
            });
        var migrated=new NewsService(legacy, Clock.systemUTC());
        var row=migrated.list(null, false).getFirst();
        assertEquals(id, row.id());
        assertNull(row.owner());
        assertEquals("# Histórica", row.markdown());
        assertEquals(row, new NewsService(legacy,
                Clock.systemUTC()).list(null, false).getFirst());
        assertNull(row.createdAt());
        assertEquals(Instant.ofEpochMilli(1).toString(), row.modifiedAt());
        try (var backups=java.nio.file.Files.list(directory)) {
            assertEquals(1, backups.filter(p->p.getFileName().toString().startsWith(
                    "legacy.db.before-news-simplification-")).count());
        }
    }
    @Test void migrationKeepsOnlySevenFieldsAndNeverResurrectsDeletedNews()throws
            Exception {
        String owner=auth.authenticate(adminToken).user().id().toString(),
                picture=image(0x427138);
        UUID published=UUID.randomUUID(), changed=UUID.randomUUID(),
                draft=UUID.randomUUID(), deleted=UUID.randomUUID();
        database.transaction(c-> { try (var s=c.createStatement()) {
                s.executeUpdate("DROP TABLE news_articles");
                s.executeUpdate(
                        "CREATE TABLE news_articles(id TEXT PRIMARY KEY,markdown TEXT"
                        + " NOT NULL,image BLOB,revision INTEGER NOT NULL,published_mar"
                        + "kdown TEXT,published_image BLOB,published_revision INTEGER,u"
                        + "pdated_at INTEGER NOT NULL,published_at INTEGER,updated_by T"
                        + "EXT NOT NULL,created_by TEXT)");
                s.executeUpdate(
                        "CREATE TABLE news_deletions(article_id TEXT PRIMARY KEY REFE" +
                        "RENCES news_articles(id),deleted_at INTEGER,deleted_by TEXT)");
            }
            for (UUID id:List.of(published, changed, draft,
                    deleted))try (var q=c.prepareStatement(
                    "INSERT INTO news_articles VALUES(?,?,?,2,?,NULL,?,200,?,?,?)")) {
                q.setString(1, id.toString());
                q.setString(2, "# Actual "+id);
                q.setBytes(3, Base64.getDecoder().decode(picture));
                q.setString(4, "# Anterior");
                q.setInt(5, id.equals(changed)?1:2);
                if (id.equals(draft))q.setNull(6, java.sql.Types.INTEGER);
                else q.setLong(6, 100);
                q.setString(7, owner);
                q.setString(8, owner);
                q.executeUpdate();
            }
            try (var q=c.prepareStatement("INSERT INTO news_deletions VALUES(?,300,?)")) {
                q.setString(1, deleted.toString());
                q.setString(2, owner);
                q.executeUpdate();
            }
            return null;
        });
        var migrated=new NewsService(database, Clock.systemUTC());
        var visible=migrated.list(null, false);
        assertEquals(1, visible.size());
        assertEquals(published, visible.getFirst().id());
        var all=migrated.list(auth.authenticate(adminToken), true);
        assertEquals(3, all.size());
        for (var row:all) {
            assertEquals("# Actual "+row.id(), row.markdown());
            assertEquals(picture, row.imageBase64());
            assertEquals(UUID.fromString(owner), row.owner());
            assertNull(row.createdAt());
            assertEquals(!row.id().equals(published), row.draft());
        }
        try (var c=database.open(); var s=c.createStatement()) {
            var columns=new ArrayList<String>();
            try (var r=s.executeQuery("PRAGMA table_info(news_articles)")) {
                while (r.next())columns.add(r.getString("name"));
            }
            assertEquals(List.of("id", "markdown", "draft", "owner",
                    "image", "created_at", "modified_at"), columns);
            try (var r=s.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE "
                    + "'news_%'")) {
                assertTrue(r.next());
                assertEquals("news_articles", r.getString(1));
                assertFalse(r.next());
            }
        }
        Path backup;
        try (var paths=java.nio.file.Files.list(directory)) {
            backup=paths.filter(p->p.getFileName().toString().startsWith(
                    "news.db.before-news-simplification-")).findFirst().orElseThrow();
        }
        try (var c=Database.existing(backup).open(); var s=c.createStatement();
                var r=s.executeQuery("SELECT count(*) FROM news_articles")) {
            assertTrue(r.next());
            assertEquals(4, r.getInt(1));
        }
        assertEquals(all, new NewsService(database,
                Clock.systemUTC()).list(auth.authenticate(adminToken), true));
    }
    @Test void liveDemotionLimitsAnOldAdministratorSessionToItsOwnNews()throws Exception {
        String mod=moderator("OriginalAuthor");
        var article=client.save(host, mod, UUID.randomUUID(), true,
                "# Propiedad del moderador", null);
        var staleAdmin=auth.authenticate(adminToken);
        database.transaction(c-> {
            try (var q=c.prepareStatement(
                    "UPDATE users SET role='MODERATOR' WHERE id=?")) {
                q.setString(1, staleAdmin.user().id().toString());
                q.executeUpdate();
            }
            return null;
        });
        assertThrows(ServiceException.class, ()->service.save(staleAdmin,
                article.id(), false, "# Ya no tiene acceso", null));
        assertThrows(ServiceException.class,
                ()->service.publish(staleAdmin, article.id()));
        assertTrue(service.list(staleAdmin, true).isEmpty());
        var newOwn=service.save(staleAdmin, UUID.randomUUID(), true,
                "# Todavía puede crear como moderador", null);
        assertEquals(staleAdmin.user().id(), newOwn.owner());
    }
    @Test void failedMigrationRollsBackInsteadOfLosingArticles()throws Exception {
        var legacy=new Database(directory.resolve("invalid-owner.db"));
        legacy.initialize();
        UUID id=UUID.randomUUID();
        legacy.transaction(c-> { try (var s=c.createStatement()) {
                s.executeUpdate(
                        "CREATE TABLE news_articles(id TEXT PRIMARY KEY,markdown TEXT"
                        + ",image BLOB,revision INTEGER,published_revision INTEGER,upda"
                        + "ted_at INTEGER,published_at INTEGER,created_by TEXT)");
            }
            try (var q=c.prepareStatement(
                    "INSERT INTO news_articles VALUES(?, 'Keep me', NULL, 1, 1, 100, "
                    + "100, ?)")) {
                q.setString(1, id.toString());
                q.setString(2, UUID.randomUUID().toString());
                q.executeUpdate();
            }
            return null;
            });
        assertThrows(ServiceException.class, ()->new NewsService(legacy,
                Clock.systemUTC()));
        try (var c=legacy.open(); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT markdown,revision FROM news_articles")) {
                assertTrue(r.next());
                assertEquals("Keep me", r.getString(1));
                assertEquals(1, r.getInt(2));
            }
            try (var r=s.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE name='news_articles_sim"
                    + "ple'")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
        try (var files=java.nio.file.Files.list(directory)) {
            assertTrue(files.anyMatch(p->p.getFileName().toString().startsWith(
                    "invalid-owner.db.before-news-simplification-")));
        }
    }
    @Test void draftsAndImagesPersistAndStayPrivateUntilExplicitlyPublished()throws
            Exception {
        UUID id=UUID.randomUUID();
        String firstImage=image(0xa03020), secondImage=image(0x2030a0);
        var draft=client.save(host, adminToken, id, true,
                "# Primera noticia\n\n**Comunidad**", firstImage);
        assertTrue(draft.draft());
        assertNotNull(draft.createdAt());
        assertEquals(draft.createdAt(), draft.modifiedAt());
        assertTrue(client.list(host, userToken, false).isEmpty());
        assertEquals(firstImage, client.list(host, adminToken,
                true).getFirst().imageBase64());
        assertEquals(draft, new NewsService(new Database(directory.resolve("news.db")),
                Clock.systemUTC()).list(auth.authenticate(adminToken), true).getFirst());
        assertThrows(java.io.IOException.class, ()->client.publish(host, userToken, id));
        client.publish(host, adminToken, id);
        var published=client.list(host, userToken, false).getFirst();
        assertEquals(draft.markdown(), published.markdown());
        assertEquals(firstImage, published.imageBase64());
        var next=client.save(host, adminToken, id, false,
                "# Otro título\n\nTexto revisado.", secondImage);
        assertTrue(next.draft());
        assertEquals(draft.createdAt(), next.createdAt());
        assertTrue(client.list(host, userToken, false).isEmpty());
        assertThrows(java.io.IOException.class, ()->client.save(host,
                adminToken, id, true, "# ID ocupado", firstImage));
        client.publish(host, adminToken, id);
        published=client.list(host, userToken, false).getFirst();
        assertEquals(next.markdown(), published.markdown());
        assertEquals(secondImage, published.imageBase64());
        assertFalse(client.list(host, adminToken, true).getFirst().draft());
    }
    @Test void anonymousReadersAndNonAdminsCannotAccessDraftsOrMutate()throws Exception {
        var http=HttpClient.newHttpClient();
        assertEquals(401, http.send(HttpRequest.newBuilder(
                host.resolve("/api/v1/news")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403, http.send(HttpRequest.newBuilder(host.resolve(
                "/api/v1/admin/news")).header("Authorization",
                "Bearer "+userToken).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertThrows(java.io.IOException.class, ()->client.save(host, userToken,
                UUID.randomUUID(), true, "# No permitido", null));
        assertTrue(client.list(host, adminToken, true).isEmpty());
    }
    @Test void deletionIsAdminOnlyPermanentAndStaleUpdatesCannotRecreateAnArticle()throws
            Exception {
        UUID id=UUID.randomUUID();
        String picture=image(0x429155);
        client.save(host, adminToken, id, true, "# Publicación original", picture);
        client.publish(host, adminToken, id);
        assertThrows(java.io.IOException.class, ()->client.delete(host, userToken, id));
        assertEquals(1, client.list(host, userToken, false).size());
        client.delete(host, adminToken, id);
        assertTrue(client.list(host, userToken, false).isEmpty());
        assertTrue(client.list(host, adminToken, true).isEmpty());
        assertTrue(new NewsService(new Database(directory.resolve("news.db")),
                Clock.systemUTC()).list(null, false).isEmpty());
        assertThrows(java.io.IOException.class, ()->client.save(host,
                adminToken, id, false, "# No resucitar", null));
        assertThrows(java.io.IOException.class, ()->client.publish(host, adminToken, id));
        assertThrows(java.io.IOException.class, ()->client.delete(host, adminToken, id));
        try (var connection=database.open(); var q=connection.prepareStatement(
                "SELECT count(*) FROM news_articles WHERE id=?")) {
            q.setString(1, id.toString());
            try (var rows=q.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }
    @Test void invalidImageOrOversizedMarkdownDoesNotReplaceSavedContent()throws
            Exception {
        var actor=auth.authenticate(adminToken);
        UUID id=UUID.randomUUID();
        var draft=service.save(actor, id, true, "# Noticia válida", image(0x427138));
        assertThrows(ServiceException.class, ()->service.save(actor,
                id, false, "# Imagen inválida",
                Base64.getEncoder().encodeToString("not-an-image".getBytes())));
        assertThrows(ServiceException.class, ()->service.save(actor, id,
                false, "a".repeat(NewsService.MAX_MARKDOWN+1), null));
        assertEquals(draft, service.list(actor, true).getFirst());
    }
    @Test void revocationAndRoleChangesInvalidateEvenPreviouslyAuthenticatedActors()throws
            Exception {
        var actor=auth.authenticate(adminToken);
        var draft=service.save(actor, UUID.randomUUID(), true, "# Borrador", null);
        database.transaction(c-> {
            try (var q=c.prepareStatement("UPDATE users SET role='USER' WHERE id=?")) {
                q.setString(1, actor.user().id().toString());
                q.executeUpdate();
            }
            return null;
        });
        assertThrows(ServiceException.class, ()->service.publish(actor, draft.id()));
        assertThrows(ServiceException.class, ()->service.list(actor, true));
        assertTrue(service.list(null, false).isEmpty());
        assertThrows(ServiceException.class, ()->service.delete(actor, draft.id()));
        database.transaction(c-> {
            try (var q=c.prepareStatement("UPDATE users SET role='ADMIN' WHERE id=?")) {
                q.setString(1, actor.user().id().toString());
                q.executeUpdate();
            }
            return null;
        });
        auth.logout(adminToken);
        assertThrows(ServiceException.class, ()->service.save(actor,
                draft.id(), false, "# Sesión revocada", null));
    }
    @Test void coverImportRejectsNonImagesAndBoundedClientDoesNotFollowRedirects()throws
            Exception {
        var bad=directory.resolve("bad.png");
        java.nio.file.Files.writeString(bad, "not an image");
        assertThrows(java.io.IOException.class, ()->LauncherNewsClient.importImage(bad));
        var redirects=com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var visits=new java.util.concurrent.atomic.AtomicInteger();
        redirects.createContext("/api/v1/news", e-> {
            e.getResponseHeaders().add("Location", "/capture");
            e.sendResponseHeaders(302, -1);
            e.close();
        });
        redirects.createContext("/capture", e-> {
            visits.incrementAndGet();
            e.sendResponseHeaders(200, -1);
            e.close();
        });
        redirects.start();
        try {
            assertThrows(java.io.IOException.class,
                    ()->client.list(URI.create("http://127.0.0.1:"
                    +redirects.getAddress().getPort()), adminToken, false));
            assertEquals(0, visits.get());
        } finally {
            redirects.stop(0);
        }
    }
}

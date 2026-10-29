package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.*;
import dev.leverage.db.Database;
import dev.leverage.http.ApiServer;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsAuthoringTest {
    @TempDir Path directory;
    @Test void moderatorCanCreateAndEditOwnNewsButOtherAndUnknownAuthorsAreReadOnly(
            )throws Exception {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        var saved=new java.util.concurrent.atomic.AtomicReference<dev
                .leverage.service.NewsService.Item>();
        var own=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# Mi noticia\n\nUn borrador del moderador.", true, owner, null, "", "");
        var foreign=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# De otro autor\n\nEsta noticia ya está publicada.",
                false, other, null, "", "");
        var legacy=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# Noticia histórica\n\nSin autor original registrado.",
                false, null, null, "", "");
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI h, String t, boolean drafts) {
                return drafts?java.util.List.of(own, foreign,
                        legacy):java.util.List.of(foreign, legacy);
            }
            @Override public dev.leverage.service.NewsService.Item save(URI h, String t,
                    UUID id, boolean create, String markdown, String image) {
                assertEquals("moderator-token", t);
                assertEquals(own.id(), id);
                var result=new dev.leverage.service.NewsService.Item(id,
                        markdown, true, owner, image, "", "");
                saved.set(result);
                return result;
            }
        };
        var session=new LauncherAuthClient.Session("moderator-token", Instant.now(
                ).plusSeconds(300), null, null, null, owner, "Moderador", "MODERATOR");
        URI host=URI.create("http://example.test");
        var panel=onEdt(()-> {
            FlatDarkLaf.setup();
            var p=new JourneyNewsPanel(client);
            p.setSession(host, session);
            return p;
        });
        await(panel);
        onEdt(()-> {
            assertTrue(named(panel, "news-create", JButton.class).isVisible());
            assertTrue(named(panel, "news-save-0", JButton.class).isVisible());
            assertFalse(named(panel, "news-save-1", JButton.class).isVisible());
            assertFalse(named(panel, "news-save-2", JButton.class).isVisible());
            named(panel, "news-save-1", JButton.class).doClick(0);
            assertFalse(find(named(panel, "news-card-1", JPanel.class),
                    NewsMarkdownPane.class).editing());
            named(panel, "news-save-0", JButton.class).doClick(0);
            assertTrue(named(panel, "news-cancel-0", JButton.class).isVisible());
            assertFalse(named(panel, "news-publish-0", JButton.class).isVisible());
            assertFalse(named(panel, "news-delete-0", JButton.class).isVisible());
            find(named(panel, "news-card-0", JPanel.class),
                    NewsMarkdownPane.class).setText("# Mi revisión\n\nCambios propios.");
            return null;
        });
        drainEdt();
        var screenshot=onEdt(()-> {
            panel.setSize(1040, 1);
            var image=render(panel, 1040, panel.getPreferredSize().height);
            assertControlsFit(panel);
            return image;
        });
        write(outputDirectory(), "news-moderator-own-editor", screenshot);
        onEdt(()-> {
            named(panel, "news-save-0", JButton.class).doClick(0);
            return null;
        });
        await(panel);
        assertNotNull(saved.get());
        assertEquals(owner, saved.get().owner());
        assertEquals("# Mi revisión\n\nCambios propios.", saved.get().markdown());
        onEdt(()-> {
            panel.setSession(host, session.withRole("ADMIN"));
            return null;
        });
        await(panel);
        onEdt(()-> {
            assertTrue(named(panel, "news-save-0", JButton.class).isVisible());
            assertTrue(named(panel, "news-save-1", JButton.class).isVisible());
            assertTrue(named(panel, "news-save-2", JButton.class).isVisible());
            panel.setSession(host, session.withRole("USER"));
            return null;
        });
        await(panel);
        onEdt(()-> {
            assertFalse(named(panel, "news-create", JButton.class).isVisible());
            assertFalse(named(panel, "news-save-0", JButton.class).isVisible());
            assertFalse(named(panel, "news-save-1", JButton.class).isVisible());
            return null;
        });
    }
    @Test void cancelRestoresSavedTextAndImageAndDiscardsANewUnsavedArticle(
            )throws Exception {
        String raw="# Noticia guardada\n\nTexto original.";
        var item=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                raw, false, null, null, "", "");
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI h, String t, boolean a) {
                return java.util.List.of(item);
            }
        };
        var session=new LauncherAuthClient.Session("token", Instant.now().plusSeconds(
                300), null, null, null, UUID.randomUUID(), "Editor", "ADMIN");
        var panel=onEdt(()-> {
            var p=new JourneyNewsPanel(client);
            p.setSize(1040, 1);
            p.setSession(URI.create("http://example.test"), session);
            return p;
        });
        await(panel);
        onEdt(()-> {
            named(panel, "news-save-0", JButton.class).doClick(0);
            find(panel, NewsMarkdownPane.class).setText("# Cambios sin guardar");
            return null;
        });
        drainEdt();
        var picture=new java.awt.image.BufferedImage(40, 40,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=picture.createGraphics();
        graphics.setColor(Color.MAGENTA);
        graphics.fillRect(0, 0, 40, 40);
        graphics.dispose();
        Path path=directory.resolve("changed.png");
        ImageIO.write(picture, "PNG", path.toFile());
        onEdt(()-> {
            panel.importImageForTest(path);
            return null;
        });
        await(panel);
        onEdt(()-> {
            named(panel, "news-cancel-0", JButton.class).doClick(0);
            assertEquals(raw, find(panel, NewsMarkdownPane.class).markdown());
            assertFalse(find(panel, NewsMarkdownPane.class).editing());
            assertEquals("Publicado", named(panel, "news-status-0",
                    JLabel.class).getText());
            assertFalse(named(panel, "news-publish-0", JButton.class).isEnabled());
            assertTrue(named(panel, "news-create", JButton.class).isEnabled());
            named(panel, "news-create", JButton.class).doClick(0);
            assertNotNull(named(panel, "news-card-1", JPanel.class));
            named(panel, "news-cancel-1", JButton.class).doClick(0);
            assertNull(named(panel, "news-card-1", JPanel.class));
            assertNotNull(named(panel, "news-card-0", JPanel.class));
            assertTrue(named(panel, "news-create", JButton.class).isEnabled());
            return null;
        });
    }
    @Test void deletionRequiresConfirmationAndKeepsTheCardOnFailure()throws Exception {
        var allow=new java.util.concurrent.atomic.AtomicBoolean();
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        var deletes=new java.util.concurrent.atomic.AtomicInteger();
        var confirmations=new java.util.concurrent.atomic.AtomicInteger();
        var item=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# Eliminar con cuidado\n\nContenido.", false, null, null, "", "");
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI h, String t, boolean a) {
                return java.util.List.of(item);
            }
            @Override public void delete(URI h, String t,
                    UUID id)throws java.io.IOException {
                deletes.incrementAndGet();
                assertEquals(item.id(), id);
                if (fail.get())throw new java.io.IOException("No se pudo eliminar");
            }
        };
        var session=new LauncherAuthClient.Session("token", Instant.now().plusSeconds(
                300), null, null, null, UUID.randomUUID(), "Editor", "ADMIN");
        var panel=onEdt(()-> {
            var p=new JourneyNewsPanel(client, (owner, title, published)-> {
                confirmations.incrementAndGet();
                assertEquals("Eliminar con cuidado", title);
                assertTrue(published);
                return allow.get();
            });
            p.setSession(URI.create("http://example.test"), session);
            return p;
        });
        await(panel);
        onEdt(()-> {
            named(panel, "news-read-update", JButton.class).doClick(0);
            named(panel, "news-delete-0", JButton.class).doClick(0);
            assertEquals(0, deletes.get());
            assertNotNull(named(panel, "news-card-0", JPanel.class));
            return null;
        });
        allow.set(true);
        onEdt(()-> {
            named(panel, "news-delete-0", JButton.class).doClick(0);
            return null;
        });
        await(panel);
        onEdt(()-> {
            assertNotNull(named(panel, "news-card-0", JPanel.class));
            assertTrue(named(panel, "news-delete-0", JButton.class).isEnabled());
            assertEquals("No se pudo eliminar", named(panel,
                    "news-notice", JLabel.class).getText());
            return null;
        });
        fail.set(false);
        onEdt(()-> {
            named(panel, "news-delete-0", JButton.class).doClick(0);
            return null;
        });
        await(panel);
        onEdt(()-> {
            assertNull(named(panel, "news-card-0", JPanel.class));
            assertTrue(named(panel, "news-create", JButton.class).isEnabled());
            return null;
        });
        assertEquals(2, deletes.get());
        assertEquals(3, confirmations.get());
    }
    @Test void creationIsAnImagelessEditorialCardInTheNewsGridAndNeverAHeaderAction(
            )throws Exception {
        var item=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# Una aventura compartida\n\nLas historias de Leverage empiezan con "
                + "su comunidad.", false, null, null, "", "");
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI host, String token, boolean admin) {
                return java.util.List.of(item);
            }
        };
        var session=new LauncherAuthClient.Session("token", Instant.now().plusSeconds(
                300), null, null, null, UUID.randomUUID(), "Editor", "ADMIN");
        var panel=onEdt(()-> {
            FlatDarkLaf.setup();
            var p=new JourneyNewsPanel(client);
            p.setSession(URI.create("http://example.test"), session);
            return p;
        });
        await(panel);
        for (int width:new int[] { 1040, 820, 520 }) {
            var picture=onEdt(()-> {
                panel.setSize(width, 1);
                var result=render(panel, width, panel.getPreferredSize().height);
                assertControlsFit(panel);
                var tile=named(panel, "news-create", JButton.class);
                var article=named(panel, "news-card-0", JPanel.class);
                assertNull(named(panel, "news-reload", JButton.class));
                assertTrue(tile.isVisible());
                assertTrue(tile.isEnabled());
                assertTrue(tile.getY()>=118);
                assertEquals("Escribir una noticia", tile.getText());
                assertEquals(0, tile.getComponentCount());
                if (width>=650) {
                    assertEquals(article.getY(), tile.getY());
                    assertEquals(article.getHeight(), tile.getHeight());
                    assertEquals(24, tile.getX()-article.getX()-article.getWidth());
                }
                else {
                    assertEquals(article.getX(), tile.getX());
                    assertTrue(tile.getY()>article.getY()+article.getHeight());
                }
                return result;
            });
            write(outputDirectory(), "news-create-card-"+width, picture);
        }
        onEdt(()-> {
            named(panel, "news-create", JButton.class).doClick(0);
            assertTrue(descendants(panel).stream().anyMatch(
                    c->c instanceof NewsMarkdownPane p&&p.editing()));
            assertFalse(named(panel, "news-create", JButton.class).isEnabled());
            return null;
        });
        var emptyClient=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI host, String token, boolean admin) {
                return java.util.List.of();
            }
        };
        var empty=onEdt(()-> {
            var p=new JourneyNewsPanel(emptyClient);
            p.setSession(URI.create("http://example.test"), session);
            return p;
        });
        await(empty);
        var emptyImage=onEdt(()-> {
            empty.setSize(1040, 1);
            var image=render(empty, 1040, empty.getPreferredSize().height);
            assertTrue(named(empty, "news-create", JButton.class).isVisible());
            assertNull(named(empty, "news-card-0", JPanel.class));
            return image;
        });
        write(outputDirectory(), "news-create-card-empty", emptyImage);
    }
    @Test void failedSavePreservesTextAndSessionRenewalDoesNotDiscardTheEditor(
            )throws Exception {
        var item=new dev.leverage.service.NewsService.Item(UUID.randomUUID(),
                "# Guardado\n\nContenido.", true, null, null, "", "");
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI host, String token, boolean admin) {
                return java.util.List.of(item);
            }
            @Override public dev.leverage.service.NewsService.Item save(URI host,
                    String token, UUID id, boolean create, String markdown,
                    String image)throws java.io.IOException {
                throw new java.io.IOException("Prueba de conexión fallida");
            }
        };
        URI host=URI.create("http://example.test");
        UUID user=UUID.randomUUID();
        var session=new LauncherAuthClient.Session("first", Instant.now(
                ).plusSeconds(3000), null, null, null, user, "Editor", "ADMIN");
        var panel=onEdt(()-> {
            var p=new JourneyNewsPanel(client);
            p.setSession(host, session);
            return p;
        });
        await(panel);
        onEdt(()-> {
            named(panel, "news-save-0", JButton.class).doClick(0);
            find(panel, NewsMarkdownPane.class).setText(
                    "### Новости\n\n**你好** y comunidad.");
            return null;
        });
        drainEdt();
        onEdt(()-> {
            panel.setSession(host, new LauncherAuthClient.Session("renewed",
                    session.expiresAt(), null, null, null, user, "Editor", "ADMIN"));
            assertEquals("### Новости\n\n**你好** y comunidad.", find(panel,
                    NewsMarkdownPane.class).markdown());
            named(panel, "news-save-0", JButton.class).doClick(0);
            return null;
        });
        await(panel);
        onEdt(()-> {
            var pane=find(panel, NewsMarkdownPane.class);
            assertTrue(pane.editing());
            assertTrue(pane.isEditable());
            assertEquals("### Новости\n\n**你好** y comunidad.", pane.markdown());
            assertFalse(named(panel, "news-publish-0", JButton.class).isEnabled());
            assertEquals("Prueba de conexión fallida", named(panel,
                    "news-notice", JLabel.class).getText());
            return null;
        });
    }
    @Test void lateResponsesCannotRestoreNewsAfterLogout()throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var client=new LauncherNewsClient() {
            @Override public java.util.List<dev.leverage.service.NewsService.Item> list(
                    URI host, String token, boolean admin)throws InterruptedException {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return java.util.List.of(new dev.leverage.service.NewsService.Item(
                        UUID.randomUUID(), "# Late", false, null, null, "", ""));
            }
        };
        var panel=onEdt(()-> {
            var p=new JourneyNewsPanel(client);
            p.setSession(URI.create("http://example.test"),
                    new LauncherAuthClient.Session("token", Instant.now().plusSeconds(
                    300), null, null, null, UUID.randomUUID(), "Editor", "ADMIN"));
            return p;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        onEdt(()-> {
            panel.setSession(null, null);
            return null;
        });
        release.countDown();
        await(panel);
        onEdt(()-> {
            assertNull(find(panel, NewsMarkdownPane.class));
            assertFalse(named(panel, "news-create", JButton.class).isVisible());
            assertNull(named(panel, "news-reload", JButton.class));
            return null;
        });
    }
    @Test void realLauncherEditsChangesImageSavesAndPublishesThroughIsolatedServer(
            )throws Exception {
        int windows=Window.getWindows().length;
        var database=new Database(directory.resolve("news-ui.db"));
        database.initialize();
        var auth=new AuthService(database, new PasswordHasher(10_000),
                new RecoveryMailer() {
            public void sendEmailVerification(String e, String u,
                    String t, Instant at) { }
            public void sendPasswordReset(String e, String u, String t, Instant at) { }
        }, Clock.systemUTC(), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofMinutes(15), true);
        var user=auth.createBootstrapAdmin("Editor", "editor@example.test",
                "news-ui-test-password");
        var login=auth.login("Editor", "news-ui-test-password");
        try (var server=new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC())) {
            server.start();
            URI host=URI.create("http://127.0.0.1:"+server.port());
            var session=new LauncherAuthClient.Session(login.accessToken(),
                    Instant.now().plusSeconds(3000), null, null, null,
                    user.id(), user.username(), "ADMIN");
            var panel=onEdt(()-> {
                FlatDarkLaf.setup();
                var p=new JourneyNewsPanel();
                p.setSize(1040, 680);
                p.setSession(host, session);
                return p;
            });
            await(panel);
            onEdt(()-> {
                named(panel, "news-create", JButton.class).doClick(0);
                var pane=find(panel, NewsMarkdownPane.class);
                assertTrue(pane.editing());
                pane.setText(
                        "# El próximo capítulo de Leverage\n\nMás espacio para **las "
                        + "historias**, las novedades y todo lo que construimos juntos."
                        + "\n\n### Un mismo lugar, más por contar\n\n- Los próximos enc"
                        + "uentros de la comunidad.\n- Los detalles de cada novedad del"
                        + " servidor.\n\n> Cada historia empieza con un primer bloque.");
                return null;
            });
            drainEdt();
            Path picture=directory.resolve("copper.png");
            assertTrue(ImageIO.write(JourneyStyle.COPPER, "PNG", picture.toFile()));
            onEdt(()-> {
                panel.importImageForTest(picture);
                return null;
            });
            await(panel);
            assertTrue(auth.news().list(null, false).isEmpty());
            for (int width:new int[] { 1040, 820, 1280, 520 }) {
                var image=onEdt(()-> {
                    panel.setSize(width, 1);
                    var result=render(panel, width, panel.getPreferredSize().height);
                    assertControlsFit(panel);
                    return result;
                });
                write(outputDirectory(), "news-authoring-edit-"+width, image);
            }
            onEdt(()-> {
                named(panel, "news-save-0", JButton.class).doClick(0);
                return null;
            });
            await(panel);
            onEdt(()-> {
                assertFalse(find(panel, NewsMarkdownPane.class).editing());
                assertTrue(named(panel, "news-publish-0", JButton.class).isEnabled());
                assertEquals("Borrador · Guardado", named(panel,
                        "news-status-0", JLabel.class).getText());
                return null;
            });
            assertTrue(auth.news().list(null, false).isEmpty());
            var draft=auth.news().list(auth.authenticate(login.accessToken()),
                    true).getFirst();
            assertNotNull(draft.imageBase64());
            assertTrue(draft.markdown().contains("**las historias**"));
            var saved=onEdt(()-> {
                panel.setSize(1040, 1);
                return render(panel, 1040, panel.getPreferredSize().height);
            });
            write(outputDirectory(), "news-authoring-saved", saved);
            onEdt(()-> {
                assertTrue(named(panel, "news-publish-0", JButton.class).isEnabled(),
                        named(panel, "news-status-0", JLabel.class).getText());
                named(panel, "news-publish-0", JButton.class).doClick(0);
                return null;
            });
            await(panel);
            assertFalse(auth.news().list(null, false).isEmpty(), onEdt(()->named(panel,
                    "news-notice", JLabel.class).getText()));
            assertEquals(draft.markdown(), auth.news().list(null,
                    false).getFirst().markdown());
            onEdt(()-> {
                assertEquals("Publicado", named(panel, "news-status-0",
                        JLabel.class).getText());
                named(panel, "news-save-0", JButton.class).doClick(0);
                assertEquals(draft.markdown(), find(panel,
                        NewsMarkdownPane.class).markdown());
                return null;
            });
            for (int width:new int[] { 1040, 820, 520 }) {
                var editorImage=onEdt(()-> {
                    panel.setSize(width, 1);
                    var result=render(panel, width, panel.getPreferredSize().height);
                    assertControlsFit(panel);
                    var cancel=named(panel, "news-cancel-0", JButton.class);
                    var delete=named(panel, "news-delete-0", JButton.class);
                    assertTrue(cancel.isVisible());
                    assertTrue(delete.isVisible());
                    assertTrue(delete.isEnabled());
                    var buttons=descendants(named(panel, "news-card-0",
                            JPanel.class)).stream().filter(c->c instanceof PixelButton
                            &&c.isVisible()).toList();
                    for (int a=0; a<buttons.size(); a++)for (int b=a+1;
                            b<buttons.size(); b++)assertFalse(buttons.get(a).getBounds(
                            ).intersects(buttons.get(b).getBounds()));
                    assertEquals(LauncherFonts.minecraftTen(14), cancel.getFont());
                    assertEquals(LauncherFonts.minecraftTen(14), delete.getFont());
                    return result;
                });
                    write(outputDirectory(), "news-authoring-cancel-delete-"
                        +width, editorImage);
            }
            var reopened=onEdt(()-> {
                var p=new JourneyNewsPanel();
                p.setSession(host, session);
                return p;
            });
            await(reopened);
            onEdt(()-> {
                assertNotNull(named(reopened, "news-read-update", JButton.class));
                reopened.setSession(null, null);
                assertNull(find(reopened, NewsMarkdownPane.class));
                return null;
            });
            var reader=onEdt(()-> {
                var p=new JourneyNewsPanel();
                p.setSession(host, session.withRole("USER"));
                return p;
            });
            await(reader);
            onEdt(()-> {
                assertFalse(named(reader, "news-create", JButton.class).isVisible());
                assertFalse(named(reader, "news-save-0", JButton.class).isVisible());
                assertFalse(named(reader, "news-delete-0", JButton.class).isVisible());
                assertFalse(named(reader, "news-cancel-0", JButton.class).isVisible());
                return null;
            });
            onEdt(()-> {
                find(panel, NewsMarkdownPane.class).setText(
                        "# Nueva edición\n\nSe publica solo al confirmarlo.");
                return null;
            });
            drainEdt();
            onEdt(()-> {
                named(panel, "news-save-0", JButton.class).doClick(0);
                return null;
            });
            await(panel);
            assertTrue(auth.news().list(null, false).isEmpty(),
                    "Saving a published article must return that same article to draf"
                    + "t");
            onEdt(()-> {
                assertEquals("Borrador · Guardado", named(panel,
                        "news-status-0", JLabel.class).getText());
                assertTrue(named(panel, "news-publish-0", JButton.class).isEnabled());
                named(panel, "news-publish-0", JButton.class).doClick(0);
                return null;
            });
            await(panel);
            assertEquals("# Nueva edición\n\nSe publica solo al confirmarlo.",
                    auth.news().list(null, false).getFirst().markdown());
        }
        assertEquals(windows, Window.getWindows().length,
                "News QA must never open windows or file choosers");
    }
    private static void await(JourneyNewsPanel panel)throws Exception {
        onEdt(panel::pendingOperation).get(15, TimeUnit.SECONDS);
        drainEdt();
    }
}

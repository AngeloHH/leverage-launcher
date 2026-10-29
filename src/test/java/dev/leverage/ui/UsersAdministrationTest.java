package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherUsersClient;
import dev.leverage.skin.DefaultSkin;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class UsersAdministrationTest {
    @Test void datedBanKeepsItsExpiryFromDialogThroughTheAdministrativeAction()
            throws Exception {
        Instant end=Instant.now().plusSeconds(3600);
        var received=new AtomicReference<Instant>();
        UsersPanel.AdminActions actions=new UsersPanel.AdminActions() {
            public void perform(URI host, String token, UUID id,
                    UsersPanel.Action action, String value, char[] password) {
                throw new AssertionError("Expiry must not be discarded");
            }
            public void perform(URI host, String token, UUID id, UsersPanel.Action action,
                    String value, char[] password, Instant expiresAt) {
                assertEquals(UsersPanel.Action.BAN, action);
                received.set(expiresAt);
            }
        };
        var panel=panel(List.of(richUser(TARGET, "Player", "ACTIVE",
                false, null, HASH)), actions, (host, hash)->skin(),
                (owner, action, user)->new AdminUserDialogs.Edit(action,
                "Motivo", new char[0], end));
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(()-> {
            find(panel, UsersPanel.UserRow.class).options.setSelectedItem(
                    UsersPanel.Action.BAN);
            return null;
        });
        await(onEdt(panel::pendingMutation));
        assertEquals(end, received.get());
    }
    @Test void banFormAcceptsARealFutureDateAndRejectsInvalidOrPastInput()
            throws Exception {
        onEdt(()-> {
            var form=new AdminUserDialogs.Editor(UsersPanel.Action.BAN,
                    richUser(TARGET, "Player", "ACTIVE", false, null, HASH));
            named(form, "admin-user-ban-reason",
                    JTextField.class).setText("Motivo de prueba");
            var end=named(form, "admin-user-ban-until", JTextField.class);
            try (var edit=form.validatedEdit()) { assertNull(edit.expiresAt()); }
            for (String invalid:List.of("31/02/2099 10:00",
                    "01/01/2000 10:00", "not a date")) {
                end.setText(invalid);
                assertThrows(IllegalArgumentException.class, form::validatedEdit);
            }
            end.setText("01/01/2099 10:00");
            try (var edit=form.validatedEdit()) {
                assertEquals(java.time.LocalDateTime.of(2099, 1, 1, 10, 0).atZone(
                        java.time.ZoneId.systemDefault()).toInstant(), edit.expiresAt());
            }
            var row=new UsersPanel.UserRow(richUser(TARGET, "Player",
                    "ACTIVE", false, null, HASH));
            assertEquals(LauncherFonts.minecraftSeven(14), row.status.getFont());
            return null;
        });
    }
    @Test void sortableHeadersUseNameFontAndToggleServerOrdering() throws Exception {
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
        UsersPanel panel=onEdt(()->new UsersPanel(new UsersPanel.QueryLoader() {
            public LauncherUsersClient.Page search(URI host, String token,
                    String query, int page, int size) {
                throw new AssertionError("Sort required");
            }
            public LauncherUsersClient.Page search(URI host, String token, String query,
                    int page, int size, String sort, String direction) {
                calls.add(sort+":"+direction+":"+page);
                return new LauncherUsersClient.Page(List.of(), 1, size, 0, 1);
            }
        }));
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        assertEquals("createdAt:asc:1", calls.getLast());
        for (String direction:List.of("asc", "desc")) {
            onEdt(()-> { JButton button=named(panel, "users-sort-email", JButton.class);
                assertEquals(LauncherFonts.minecraftTen(20), button.getFont());
                button.doClick(0);
                return null;
                });
            await(onEdt(panel::pendingLoad));
            assertEquals("email:"+direction+":1", calls.getLast());
        }
    }
    private static final URI HOST = URI.create("http://127.0.0.1:9000");
    private static final UUID ADMIN = new UUID(3, 1), TARGET = new UUID(3, 2);
    private static final String HASH = "a".repeat(40);

    @Test void stateReasonsDatesAndUntrustedTextAreShownSafely() throws Exception {
        onEdt(() -> {
            var banned = new LauncherUsersClient.User(TARGET,
                    "<html><img src='http://invalid.test/x'>",
                    "<html><img src='http://invalid.test/email'>@example.test",
                    "USER", "SUSPENDED",
                    Instant.parse("2025-03-14T12:20:00Z"),
                    Instant.parse("2026-10-25T16:05:00Z"), true,
                    "<html><img src='http://invalid.test/reason'> & griefing", HASH);
            var row = new UsersPanel.UserRow(banned);
            assertEquals("Baneado", row.status.getText());
            assertTrue(row.status.getToolTipText().contains("&lt;img"));
            assertFalse(row.status.getToolTipText().contains("<img"));
            assertTrue(row.status.getToolTipText().contains("&amp; griefing"));
            assertEquals(Boolean.TRUE, row.username.getClientProperty("html.disable"));
            assertFalse(row.username.getToolTipText().contains("<img"));
            assertFalse(row.email.getToolTipText().contains("<img"));
            assertNotEquals("Sin registro", row.created.getText());
            assertNotEquals("Sin registro", row.lastSeen.getText());
            assertTrue(row.lastSeen.getToolTipText().contains(
                    "Actividad de sesión autenticada"));
            assertTrue(row.lastSeen.getToolTipText().contains(
                    "No indica si Minecraft está abierto"));
            var suspended = new UsersPanel.UserRow(richUser(TARGET, "Suspended",
                    "SUSPENDED", false, "Uso de mods no permitidos", HASH));
            assertEquals("Suspendido", suspended.status.getText());
            assertTrue(suspended.status.getToolTipText().contains(
                    "Uso de mods no permitidos"));
            var active = new UsersPanel.UserRow(richUser(TARGET,
                    "Active", "ACTIVE", false, null, HASH));
            assertEquals("Activo", active.status.getText());
            assertTrue(active.status.getToolTipText().contains("Sin restricciones"));
            return null;
        });
    }

    @Test void
            ownCredentialsAndBanAreProtectedWhileMissingAndAlreadyBannedActionsAreDisabled
            () throws Exception {
        var own = richUser(ADMIN, "Administrator", "ACTIVE", false, null, HASH);
        UsersPanel panel = panel(List.of(own), noActions(), (host,
                hash)->DefaultSkin.image(), (owner, action, user)->null);
        onEdt(() -> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(() -> {
            var row = find(panel, UsersPanel.UserRow.class);
            for (var action:List.of(UsersPanel.Action.BAN,
                    UsersPanel.Action.PASSWORD, UsersPanel.Action.EMAIL)) {
                assertFalse(row.button(action).isEnabled());
                assertTrue(row.button(action).getToolTipText().contains("Ajustes"));
            }
            assertTrue(row.button(UsersPanel.Action.CLEAR_SKIN).isEnabled());
            var banned = new UsersPanel.UserRow(richUser(TARGET, "Banned",
                    "SUSPENDED", true, "Repeated cheating", null));
            banned.setActionsEnabled(true, ADMIN);
            assertFalse(banned.button(UsersPanel.Action.BAN).isEnabled());
            assertFalse(banned.button(UsersPanel.Action.CLEAR_SKIN).isEnabled());
            assertTrue(banned.button(UsersPanel.Action.EMAIL).isEnabled());
            return null;
        });
    }

    @Test void actualSkinHeadsLoadOffEdtOncePerHashAndIncludeTheHatOverlay()
            throws Exception {
        AtomicInteger loads = new AtomicInteger();
        UsersPanel panel = panel(List.of(richUser(TARGET, "First", "ACTIVE", false, null,
                HASH), richUser(new UUID(3, 3), "Second", "ACTIVE", false, null, HASH)),
                noActions(), (host, hash)-> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    assertEquals(HOST, host);
                    loads.incrementAndGet();
                    return skin();
                }, (owner, action, user)->null);
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        await(onEdt(panel::pendingSkins));
        assertEquals(1, loads.get());
        onEdt(()-> {
            var avatar=find(panel, UsersPanel.SkinHead.class);
            BufferedImage image=paintHead(avatar);
            assertEquals(Color.RED.getRGB(), image.getRGB(3, 3));
            assertEquals(new Color(0x23916B).getRGB(), image.getRGB(10, 3));
            assertEquals(JourneyStyle.GREEN.getRGB(), image.getRGB(0, 0));
            assertEquals(0, image.getRGB(2, 2)>>>24);
            return null;
        });
    }

    @Test void
            firstNarrowLayoutKeepsBothButtonRowsInsideTheirRowAndNeverAddsHorizontalScroll
            () throws Exception {
        UsersPanel panel=panel(List.of(richUser(TARGET, "Player", "ACTIVE", false, null,
                HASH)), noActions(), (host, hash)->skin(), (owner, action, user)->null);
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(()-> {
            panel.setSize(360, 500);
            JourneyStyle.layoutTree(panel);
            JourneyStyle.layoutTree(panel);
            var row=find(panel, UsersPanel.UserRow.class);
            assertEquals(84, row.getHeight());
            for (var action:UsersPanel.Action.values()) {
                var button=row.button(action);
                assertTrue(button.getY()+button.getHeight()<=row.getHeight());
            }
            JScrollPane scroll=named(panel, "users-list-scroll", JScrollPane.class);
            assertFalse(scroll.getHorizontalScrollBar().isVisible());
            assertControlsFit(panel);
            return null;
            });
    }

    @Test void
            compactFooterKeepsControlsReadableAndFallsBackForNarrowOrVeryLargePageCounts()
            throws Exception {
        var user=richUser(TARGET, "Player", "ACTIVE", false, null, HASH);
        UsersPanel panel=onEdt(()->new UsersPanel((host, token, query, page, size)->
                new LauncherUsersClient.Page(List.of(user), 100, size, 25_000, 1_000)));
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(()-> {
            for (int width:new int[] { 756, 900, 360 }) {
                panel.setSize(width, 500);
                JourneyStyle.layoutTree(panel);
                JourneyStyle.layoutTree(panel);
                JPanel footer=named(panel, "users-pagination", JPanel.class);
                JLabel page=named(panel, "users-page-status", JLabel.class);
                assertEquals(width>=720?66:118, footer.getHeight());
                assertTrue(page.getWidth()>=page.getPreferredSize().width,
                        "The full page count must remain readable");
                var total=named(panel, "users-total-status", JLabel.class);
                var size=named(panel, "users-page-size-group", JPanel.class);
                var pager=named(panel, "users-page-buttons", JPanel.class);
                assertFalse(total.getBounds().intersects(size.getBounds()));
                if (width>=720) {
                    assertFalse(total.getBounds().intersects(pager.getBounds()));
                    assertFalse(size.getBounds().intersects(pager.getBounds()));
                }
                assertControlsFit(panel);
            }
            return null;
        });
    }

    @Test void oldAvatarCompletionCannotPaintAfterLogout() throws Exception {
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        UsersPanel panel=panel(List.of(richUser(TARGET, "Player", "ACTIVE",
                false, null, HASH)), noActions(), (host, hash)-> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return skin();
        }, (owner, action, user)->null);
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var avatar=onEdt(()->find(panel, UsersPanel.SkinHead.class));
        var thumbnails=onEdt(panel::pendingSkins);
        BufferedImage before=onEdt(()->paintHead(avatar));
        onEdt(()-> {
            panel.clearSession();
            return null;
        });
        release.countDown();
        await(thumbnails);
        onEdt(()-> {
            assertEquals(before.getRGB(3, 3), paintHead(avatar).getRGB(3, 3));
            assertNull(find(panel, UsersPanel.UserRow.class));
            return null;
        });
    }

    @Test void everyConfirmedActionRunsOffEdtRefreshesThePageAndWipesPasswords()
            throws Exception {
        for (UsersPanel.Action action:UsersPanel.Action.values()) {
            AtomicInteger searches=new AtomicInteger(), calls=new AtomicInteger(),
                    clearedEvents=new AtomicInteger();
            AtomicReference<char[]> transferred=new AtomicReference<>();
            var user=richUser(TARGET, "Player", "ACTIVE", false, null, HASH);
            UsersPanel panel=onEdt(()->new UsersPanel((host, token, query, page, size)-> {
                searches.incrementAndGet();
                return page(List.of(user));
            },
                    (host, token, id, kind, value, password)-> {
                        assertFalse(SwingUtilities.isEventDispatchThread());
                        assertEquals(HOST, host);
                        assertEquals("fixture-token", token);
                        assertEquals(TARGET, id);
                        assertEquals(action, kind);
                        assertEquals(action==UsersPanel.Action.BAN?"Reason"
                        :action==UsersPanel.Action.EMAIL?"new@example.test":"", value);
                        if (action==UsersPanel.Action.PASSWORD)assertArrayEquals(
                        "A new password 123!".toCharArray(), password);
                        transferred.set(password);
                        calls.incrementAndGet();
            }, (host, hash)->skin(), (owner, kind,
                    target)->new AdminUserDialogs.Edit(kind,
                    kind==UsersPanel.Action.BAN?"Reason":kind==UsersPanel.Action.EMAIL
                    ?"new@example.test":"", kind==UsersPanel.Action.PASSWORD
                    ?"A new password 123!".toCharArray():new char[0])));
            onEdt(()-> {
                panel.addPropertyChangeListener("userSkinCleared", event-> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    assertEquals(TARGET, event.getNewValue());
                    clearedEvents.incrementAndGet();
                });
                panel.setSession(HOST, session());
                return null;
            });
            await(onEdt(panel::pendingLoad));
            onEdt(()-> {
                var row=find(panel, UsersPanel.UserRow.class);
                assertFalse(java.util.Arrays.stream(row.getComponents()).anyMatch(
                        PixelButton.class::isInstance));
                row.options.setSelectedItem(action);
                return null;
                });
                await(onEdt(panel::pendingMutation));
            assertEquals(1, calls.get());
            assertEquals(2, searches.get());
            assertEquals(action==UsersPanel.Action.CLEAR_SKIN?1:0, clearedEvents.get());
            for (char character:transferred.get())assertEquals('\0', character);
            onEdt(()-> {
                assertFalse(named(panel, "users-action-notice",
                        JLabel.class).getText().contains("password 123"));
                return null;
            });
        }
    }

    @Test void cancelOrAnAccountChangeInsideAModalPromptCannotSubmitAnAction()
            throws Exception {
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<UsersPanel> reference=new AtomicReference<>();
        AtomicReference<char[]> abandoned=new AtomicReference<>();
        var user=richUser(TARGET, "Player", "ACTIVE", false, null, HASH);
        UsersPanel panel=panel(List.of(user), (host, token, id, action, value, password)->
                calls.incrementAndGet(), (host, hash)->skin(), (owner, action, target)-> {
            reference.get().clearSession();
            char[] password="Abandoned password".toCharArray();
            abandoned.set(password);
            return new AdminUserDialogs.Edit(action, "", password);
        });
        reference.set(panel);
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(()-> {
            find(panel, UsersPanel.UserRow.class).button(
                    UsersPanel.Action.PASSWORD).doClick(0);
            assertNull(find(panel, UsersPanel.UserRow.class));
            return null;
        });
        assertEquals(0, calls.get());
        for (char character:abandoned.get())assertEquals('\0', character);
        UsersPanel cancelled=panel(List.of(user), (host, token, id,
                action, value, password)->calls.incrementAndGet(), (host,
                hash)->skin(), (owner, action, target)->null);
        onEdt(()-> {
            cancelled.setSession(HOST, session());
            return null;
        });
        await(onEdt(cancelled::pendingLoad));
        onEdt(()-> {
            find(cancelled, UsersPanel.UserRow.class).button(
                    UsersPanel.Action.BAN).doClick(0);
            return null;
        });
        assertEquals(0, calls.get());
    }

    @Test void pendingOrFailedActionsNeverExposeSecretsOrResurrectLoggedOutData()
            throws Exception {
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        var user=richUser(TARGET, "Player", "ACTIVE", false, null, HASH);
        UsersPanel panel=panel(List.of(user), (host, token,
                id, action, value, password)-> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IOException("private-sensitive-server-detail");
        }, (host, hash)->skin(), (owner, action, target)->new AdminUserDialogs.Edit(
                action, "new@example.test", new char[0]));
        onEdt(()-> {
            panel.setSession(HOST, session());
            return null;
        });
        await(onEdt(panel::pendingLoad));
        onEdt(()-> {
            find(panel, UsersPanel.UserRow.class).button(
                    UsersPanel.Action.EMAIL).doClick(0);
            assertFalse(named(panel, "users-search", JButton.class).isEnabled());
            return null;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var completion=onEdt(panel::pendingMutation);
        onEdt(()-> {
            panel.clearSession();
            return null;
        });
        release.countDown();
        await(completion);
        onEdt(()-> {
            assertNull(find(panel, UsersPanel.UserRow.class));
            assertFalse(named(panel, "users-action-notice", JLabel.class).isVisible());
            return null;
        });
        UsersPanel failed=panel(List.of(user), (host, token,
                id, action, value, password)-> {
            throw new IOException("private-sensitive-server-detail");
        },
                (host, hash)->skin(), (owner, action, target)->new AdminUserDialogs.Edit(
                action, "new@example.test", new char[0]));
        onEdt(()-> {
            failed.setSession(HOST, session());
            return null;
        });
        await(onEdt(failed::pendingLoad));
        onEdt(()-> {
            find(failed, UsersPanel.UserRow.class).button(
                    UsersPanel.Action.EMAIL).doClick(0);
            return null;
        });
        await(onEdt(failed::pendingMutation));
        onEdt(()-> {
            assertTrue(named(failed, "users-action-notice",
                    JLabel.class).getText().startsWith("No se pudo"));
            assertFalse(named(failed, "users-action-notice",
                    JLabel.class).getText().contains("sensitive"));
            assertTrue(find(failed, UsersPanel.UserRow.class).button(
                    UsersPanel.Action.EMAIL).isEnabled());
            return null;
            });
    }

    @Test void dialogsValidateFieldsAndMaskPasswords() throws Exception {
        var user=richUser(TARGET, "Player", "ACTIVE", false, null, HASH);
        onEdt(()-> {
            var ban=new AdminUserDialogs.Editor(UsersPanel.Action.BAN, user);
            assertThrows(IllegalArgumentException.class, ban::validatedEdit);
            named(ban, "admin-user-ban-reason",
                    JTextField.class).setText("  Repeated cheating  ");
            try (var edit=ban.validatedEdit()) {
                assertEquals("Repeated cheating", edit.value());
            }
            var email=new AdminUserDialogs.Editor(UsersPanel.Action.EMAIL, user);
            named(email, "admin-user-email", JTextField.class).setText("not-an-email");
            assertThrows(IllegalArgumentException.class, email::validatedEdit);
            named(email, "admin-user-email",
                    JTextField.class).setText(" new@example.test ");
            try (var edit=email.validatedEdit()) {
                assertEquals("new@example.test", edit.value());
            }
            var form=new AdminUserDialogs.Editor(UsersPanel.Action.PASSWORD, user);
            var password=named(form, "admin-user-password", JPasswordField.class);
            var repeat=named(form, "admin-user-repeat-password", JPasswordField.class);
            assertNotEquals('\0', password.getEchoChar());
            assertNotEquals('\0', repeat.getEchoChar());
            password.setText("short");
            repeat.setText("short");
            assertThrows(IllegalArgumentException.class, form::validatedEdit);
            password.setText("A long password 123!");
            repeat.setText("Different long password");
            assertThrows(IllegalArgumentException.class, form::validatedEdit);
            repeat.setText("A long password 123!");
            var edit=form.validatedEdit();
            assertEquals(0, password.getPassword().length);
            assertEquals(0, repeat.getPassword().length);
            assertArrayEquals("A long password 123!".toCharArray(), edit.password());
            edit.close();
            for (char character:edit.password())assertEquals('\0', character);
            return null;
        });
        var output=outputDirectory();
        for (var action:UsersPanel.Action.values()) {
            BufferedImage image=onEdt(()-> {
                FlatDarkLaf.setup();
                var content=new AdminUserDialogs.ConfirmationPanel(action, user);
                if (action==UsersPanel.Action.BAN)named(content, "admin-user-ban-reason",
                        JTextField.class).setText("Uso reiterado de mods no permitidos");
                Dimension size=content.getPreferredSize();
                var result=render(content, size.width, size.height);
                assertControlsFit(content);
                return result;
                });
            write(output, "users-dialog-"+action.name().toLowerCase(
                    java.util.Locale.ROOT), image);
        }
    }

    private static UsersPanel panel(List<LauncherUsersClient.User> users,
            UsersPanel.AdminActions actions, UsersPanel.SkinLoader skins,
            UsersPanel.Dialogs dialogs) throws Exception {
        return onEdt(()->new UsersPanel((host, token, query, page,
                size)->page(users), actions, skins, dialogs));
    }
    private static UsersPanel.AdminActions noActions() {
        return (host, token, id, action, value, password)-> {
            throw new AssertionError("No action expected");
        };
    }
    private static LauncherUsersClient.Page page(List<LauncherUsersClient.User> users) {
        return new LauncherUsersClient.Page(users, 1, 25, users.size(), 1);
    }
    private static LauncherAuthClient.Session session() {
        return new LauncherAuthClient.Session("fixture-token", Instant.now().plusSeconds(
                600), null, null, null, ADMIN, "Administrator", "ADMIN");
    }
    private static LauncherUsersClient.User richUser(UUID id, String name,
            String status, boolean banned, String reason, String hash) {
        return new LauncherUsersClient.User(id, name,
                "player@example.test", "USER", status,
                Instant.parse("2025-03-14T12:20:00Z"),
                Instant.parse("2026-10-25T16:05:00Z"), banned, reason, hash);
    }
    private static BufferedImage skin() {
        BufferedImage image=new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y=8; y<16; y++)for (int x=8; x<16; x++)image.setRGB(x,
                y, new Color(0x23916B).getRGB());
        image.setRGB(40, 8, Color.RED.getRGB());
        return image;
    }
    private static BufferedImage paintHead(UsersPanel.SkinHead avatar) {
        avatar.setSize(54, 54);
        BufferedImage image=new BufferedImage(54, 54, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try {
            avatar.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }
    private static void await(CompletableFuture<Void> completion)throws Exception {
        completion.get(10, TimeUnit.SECONDS);
    }
}

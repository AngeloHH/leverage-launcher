package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.InstalledModCatalog;
import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherUsersClient;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryAdministrationTest {
    private static final URI HOST=URI.create("http://127.0.0.1:9000");

    @Test void onlyAdministratorsHaveAFourthTabAndLosingTheRoleClearsItsData(
            ) throws Exception {
        AtomicInteger queries=new AtomicInteger();
        LauncherHomePanel home=onEdt(()-> {
            var users=new UsersPanel((host, token, query, page, size)-> {
                queries.incrementAndGet();
                return directory(page, size);
            });
            var panel=new LauncherHomePanel((message, error)-> { }, users);
            panel.setAnimationEnabled(false);
            panel.showSection(LauncherHomePanel.Section.VERSIONS);
            render(panel, 1040, 680);
            assertEquals(3, named(panel, "library-subtabs",
                    JPanel.class).getComponentCount());
            panel.showSection(LauncherHomePanel.Section.USERS);
            assertEquals(LauncherHomePanel.Section.VERSIONS, panel.selectedSection());
            panel.setUsersSession(HOST, session("USER"));
            assertEquals(0, queries.get());
            panel.setUsersSession(HOST, session("ADMIN"));
            return panel;
        });
        onEdt(()->home.users().pendingLoad()).get(10, TimeUnit.SECONDS);
        onEdt(()-> {
            assertEquals(1, queries.get());
            home.showSection(LauncherHomePanel.Section.USERS);
            for (int[] size:SIZES) {
                render(home, size[0], size[1]);
                assertControlsFit(home);
                assertEquals(4, named(home, "library-subtabs",
                        JPanel.class).getComponentCount());
                JPanel background=named(home, "section-users", JPanel.class);
                assertEquals(JourneyStyle.INK, background.getBackground());
                assertEquals(25, named(home, "users-page-size",
                        PixelDropdown.class).getSelectedItem());
            }
            home.setUsersSession(HOST, session("MODERATOR"));
            render(home, 1040, 680);
            assertEquals(3, named(home, "library-subtabs",
                    JPanel.class).getComponentCount());
            assertEquals(LauncherHomePanel.Section.VERSIONS, home.selectedSection());
            assertFalse(descendants(home.users()).stream().anyMatch(
                    UsersPanel.UserRow.class::isInstance));
            assertEquals(1, queries.get());
            return null;
        });
    }

    @Test void exportsActualAdminAndSixColumnResourceViewsAtAllSupportedSizes(
            ) throws Exception {
        Path output=outputDirectory();
        LauncherHomePanel home=onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new LauncherHomePanel((message, error)-> { }, new UsersPanel((host,
                    token, query, page, size)->directory(page, size),
                    (host, token, id, action, value, password)-> {
                        throw new AssertionError("Preview must not change accounts");
            },
                    (host, hash)->previewSkin(hash), (owner, action, user)->null));
            panel.setAnimationEnabled(false);
            panel.setAccount("Administrador", true);
            panel.setUsersSession(HOST, session("ADMIN"));
            find(panel, InstanceSelectionPanel.class).setPreviewVersions(
                    List.of("1.21.1"), "1.21.1");
            panel.resourcepacks().setMods(IntStream.range(0,
                    13).mapToObj(i->new InstalledModCatalog.Mod(Path.of("fixture-"
                    +i+".zip"), "0".repeat(40),
                    List.of("Bosque vivo", "Cobre y luz", "Piedra antigua",
                    "Río cristalino", "Mundo de papel",
                    "Aventura clásica").get(i%6), "Formato 34",
                    "Texturas para darle otra vida a tu mundo.",
                    artwork(i))).toList(), ignored-> { });
            return panel;
        });
        onEdt(()->home.users().pendingLoad()).get(10, TimeUnit.SECONDS);
        onEdt(()->home.users().pendingSkins()).get(10, TimeUnit.SECONDS);
        for (int[] size:SIZES) {
            BufferedImage users=onEdt(()-> {
                home.showSection(LauncherHomePanel.Section.USERS);
                var image=render(home, size[0], size[1]);
                assertControlsFit(home);
                JComponent pager=named(home, "users-pagination", JPanel.class);
                Rectangle bounds=SwingUtilities.convertRectangle(pager.getParent(),
                        pager.getBounds(), home);
                assertTrue(bounds.y>=0&&bounds.y+bounds.height<=size[1]-58,
                        "Pagination must remain above the green subtabs");
                assertEquals(66, pager.getHeight(),
                        "Full launcher widths use the compact single-line paginator");
                for (var child:descendants(home.users())) if (
                        child instanceof UsersPanel.UserRow row)
                    assertTrue(row.status.getWidth()>=row.status.getPreferredSize().width,
                        "Account states must not be truncated");
                for (var child:named(home, "users-table-header",
                        JPanel.class).getComponents())if (child instanceof JButton button
                        && !button.getText().contains(" "))
                    assertTrue(button.getWidth()>=button.getFontMetrics(
                        button.getFont()).stringWidth(button.getText())+32,
                        "Header text must leave room for the far-right arrow");
                var first=find(home.users(), UsersPanel.UserRow.class);
                var scroll=named(home, "users-list-scroll", JScrollPane.class);
                for (var button:List.of(first.options)) {
                    Rectangle actionBounds=SwingUtilities.convertRectangle(
                            button.getParent(), button.getBounds(), scroll.getViewport());
                    assertTrue(actionBounds.y>=0&&actionBounds.y
                            +actionBounds.height<=scroll.getViewport().getHeight(),
                            "First user actions must be fully visible even at 820×560");
                }
                assertTrue(named(home, "users-next", JButton.class).isEnabled());
                return image;
            });
            write(output, "admin-users-"+size[0], users);
            BufferedImage resources=onEdt(()-> {
                home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
                var image=render(home, size[0], size[1]);
                assertControlsFit(home);
                assertEquals(JourneyStyle.TABLE, named(home, "section-resourcepacks",
                        JPanel.class).getBackground());
                JPanel grid=named(home, "resourcepack-grid", JPanel.class);
                assertEquals(13, grid.getComponentCount());
                for (int i=1; i<6; i++)assertEquals(grid.getComponent(0).getY(),
                        grid.getComponent(i).getY());
                assertTrue(grid.getComponent(6).getY()>grid.getComponent(0).getY());
                for (Component child:descendants(home))if (
                        child instanceof JScrollPane scroll)assertFalse(
                        scroll.getHorizontalScrollBar().isVisible());
                return image;
            });
            write(output, "resource-packs-six-columns-"+size[0], resources);
        }
        onEdt(()-> {
            home.setAccount("Jugador", false);
            home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
            render(home, 1040, 680);
            assertEquals(3, named(home, "library-subtabs",
                    JPanel.class).getComponentCount());
            return null;
        });
    }

    private static LauncherAuthClient.Session session(String role) {
        return new LauncherAuthClient.Session("isolated-preview-token",
                Instant.now().plusSeconds(600), null, null, null,
                UUID.randomUUID(), "Administrador", role);
    }

    @Test void emptyResourceMessageUsesCenteredWhiteMinecraftTen(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var home=onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new LauncherHomePanel();
            panel.setAnimationEnabled(false);
            panel.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
            return panel;
        });
        onEdt(()->home.resourcepacks().loadDirectory(directory)).get(10,
                TimeUnit.SECONDS);
        for (int[] size:SIZES) {
            var image=onEdt(()-> {
                var result=render(home, size[0], size[1]);
                var message=named(home, "resourcepack-empty", JLabel.class);
                assertEquals("No hay paquetes ZIP en esta carpeta.", message.getText());
                assertEquals(LauncherFonts.minecraftTen(20), message.getFont());
                assertEquals(Color.WHITE, message.getForeground());
                assertEquals(SwingConstants.CENTER, message.getHorizontalAlignment());
                assertEquals(SwingConstants.CENTER, message.getVerticalAlignment());
                assertControlsFit(home);
                return result;
            });
                write(outputDirectory(), "resource-packs-empty-"+size[0], image);
        }
    }
    private static LauncherUsersClient.Page directory(int page, int size) {
        List<LauncherUsersClient.User> users=IntStream.range((page-1)*size, Math.min(67,
                page*size)).mapToObj(i->new LauncherUsersClient.User(new UUID(0, i+1),
                List.of("Angelo", "Weenda", "MacKey", "Akats", "GhostWarrior",
                "FinDeSiglo").get(i%6)+String.format("%02d",
                i), "jugador"+i+"@example.test",
                i==0?"ADMIN":i==4?"MODERATOR":"USER", i==1||i==2
                ||i==9?"SUSPENDED":"ACTIVE",
                Instant.parse("2023-04-12T14:30:00Z").plusSeconds(i*86400L), i==3?null
                :Instant.parse("2026-10-25T16:05:00Z").minusSeconds(i*7200L),
                i==2, i==2?"Uso reiterado de clientes modificados":i==1
                ||i==9?"Mods no permitidos en el servidor":null,
                i==3?null:String.format("%040x", i+1))).toList();
        return new LauncherUsersClient.Page(users, page, size, 67, (67+size-1)/size);
    }
    private static BufferedImage previewSkin(String hash) {
        BufferedImage image=new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D base=image.createGraphics();
        try {
            base.drawImage(dev.leverage.skin.DefaultSkin.image(), 0, 0, null);
        } finally {
            base.dispose();
        }
        int variant=Integer.parseInt(hash.substring(hash.length()-2), 16)%6;
        Color[] hair= { new Color(0x42342d), new Color(0xAC682E), new Color(0x252028),
                new Color(0xE6BC54), new Color(0x633B2A), new Color(0x293640) };
        Color[] skin= { new Color(0xD7A976), new Color(0xDCA38A), new Color(0x9F6748),
                new Color(0xE8BD87), new Color(0xC18D68), new Color(0xBA947D) };
        Graphics2D g=image.createGraphics(); try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(40, 8, 8, 8);
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(skin[variant]);
            g.fillRect(8, 8, 8, 8);
            g.setColor(hair[variant]);
            g.fillRect(8, 8, 8, 2);
            g.fillRect(8, 10, 1, 3);
            g.fillRect(15, 10, 1, 3);
            g.setColor(new Color(0xEAE6E0));
            g.fillRect(9, 11, 2, 1);
            g.fillRect(13, 11, 2, 1);
            g.setColor(new Color(0x355D87));
            g.fillRect(10, 11, 1, 1);
            g.fillRect(13, 11, 1, 1);
            g.setColor(new Color(0x92564A));
            g.fillRect(11, 14, 2, 1);
            if (variant==1||variant==4) {
                g.setColor(new Color(0x6C3D2B));
                g.fillRect(10, 13, 1, 2);
                g.fillRect(13, 13, 1, 2);
                g.fillRect(10, 15, 4, 1);
            }
        } finally {
            g.dispose();
        }
        return image;
    }
    private static BufferedImage artwork(int seed) {
        BufferedImage image=new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        Color[] colors= { new Color(0x4c793d), new Color(0xad5e35), new Color(0x797368),
                new Color(0x3c8493), new Color(0xc7ae77), new Color(0x926d96) };
        g.setColor(colors[seed%6].darker());
        g.fillRect(0, 0, 64, 64);
        for (int y=0; y<8; y++)for (int x=0; x<8; x++) {
            g.setColor((x+y+seed)%3==0?colors[seed%6].brighter():colors[seed%6]);
            g.fillRect(x*8, y*8, 7, 7);
        }
        g.setColor(new Color(255, 255, 255, 190));
        g.fillRect(22, 18, 20, 28);
        g.setColor(colors[seed%6].darker());
        g.fillRect(27, 23, 10, 18);
        g.dispose();
        return image;
    }
}

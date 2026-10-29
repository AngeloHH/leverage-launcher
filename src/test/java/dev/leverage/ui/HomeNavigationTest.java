package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

// Approved full-width journey replaces the retired one-third player sidebar.
class HomeNavigationTest {
    @Test void versionSelectionActionKeepsMinecraftTenWhenSelectedOrBrowsing(
            ) throws Exception {
        onEdt(()-> {
            var instances=new InstanceSelectionPanel();
            instances.setPreviewVersions(List.of("1.21.1", "1.20.4"), "1.21.1");
            var panel=new JourneyVersionPanel(instances);
            panel.setAnimationEnabled(false);
            for (int[] size:SIZES) {
                render(panel, size[0], size[1]);
                var selected=named(panel, "version-apply", PixelButton.class);
                assertEquals("SELECCIONADA", selected.getText());
                assertFalse(selected.isEnabled());
                assertEquals(LauncherFonts.minecraftTen(14), selected.getFont());
                named(panel, "next-version", JButton.class).doClick(0);
                render(panel, size[0], size[1]);
                assertEquals(LauncherFonts.minecraftTen(14), named(panel,
                        "version-apply", PixelButton.class).getFont());
                named(panel, "previous-version", JButton.class).doClick(0);
            }
            return null;
        });
    }

    @Test void nativeNoticesNeverCreateABannerOrChangeContentBounds() throws Exception {
        onEdt(()-> {
            var messages=new java.util.ArrayList<String>();
            var errors=new java.util.ArrayList<Boolean>();
            var home=new LauncherHomePanel((text, error)-> {
                messages.add(text);
                errors.add(error);
            });
            home.setAnimationEnabled(false);
            for (int[] size:SIZES)for (var screen:LauncherHomePanel.Screen.values()) {
                home.showScreen(screen);
                BufferedImage before=render(home, size[0], size[1]);
                var nav=named(home, "home-navigation", JPanel.class);
                Rectangle bounds=nav.getBounds();
                home.showNotice("");
                int count=messages.size();
                home.showNotice("Aviso de prueba", new Color(0xd4441a));
                BufferedImage after=render(home, size[0], size[1]);
                assertEquals(bounds, nav.getBounds());
                assertArrayEquals(before.getRGB(0, 0, size[0], size[1], null, 0, size[0]),
                        after.getRGB(0, 0, size[0], size[1], null, 0, size[0]));
                assertEquals(count+1, messages.size());
                assertTrue(errors.getLast());
                assertFalse(descendants(home).stream().anyMatch(c->c instanceof JLabel l
                        &&"Aviso de prueba".equals(l.getText())));
                for (Component c:descendants(home))if (
                        c instanceof JScrollPane scroll)assertFalse(
                        scroll.getHorizontalScrollBar().isVisible());
            }
            home.showNotice("Aviso normal");
            assertFalse(errors.getLast());
            return null;
        });
    }

    @Test void catalogErrorsGoToSystemHandlerButLoadingAndSuccessStaySilent(
            @TempDir Path directory)throws Exception {
        var messages=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var errors=new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        var home=onEdt(()->new LauncherHomePanel((text, error)-> {
            messages.add(text);
            errors.add(error);
        }));
        onEdt(()->home.mods().loadDirectory(directory.resolve("missing"))).get(10,
                java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, messages.size());
        assertTrue(errors.getFirst());
        assertTrue(messages.getFirst().startsWith("No se pudo leer"));
        onEdt(()->home.resourcepacks().loadDirectory(directory)).get(10,
                java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, messages.size());
    }

    @Test void oneFullWidthNavigationMovesFromHomeFootToDetailTopWithoutSidebar(
            )throws Exception {
        onEdt(()-> {
            var home=new LauncherHomePanel((text, error)-> { });
            home.setAnimationEnabled(false);
            for (int[] size:SIZES) {
                home.showHome();
                render(home, size[0], size[1]);
                var nav=named(home, "home-navigation", JPanel.class);
                assertFalse(descendants(home).stream().anyMatch(
                        c->"journey-account-header".equals(c.getName())));
                assertEquals(size[0], nav.getWidth());
                assertSame(nav, find(home, PlayerAccountCard.class).getParent());
                assertEquals(size[1], nav.getY()+nav.getHeight());
                assertNull(find(home, PlayerSkinView.class));
                for (var screen:LauncherHomePanel.Screen.values())if (
                        screen!=LauncherHomePanel.Screen.HOME) {
                    home.showScreen(screen);
                    render(home, size[0], size[1]);
                    assertSame(nav, named(home, "home-navigation", JPanel.class));
                    assertEquals(0, nav.getY());
                    var page=named(home, "journey-page-viewport", JPanel.class);
                    assertEquals(nav.getY()+nav.getHeight(), page.getY());
                    assertEquals(size[1], page.getY()+page.getHeight());
                }
            }
            assertNotNull(JourneyStyle.LOGO);
            assertTrue(JourneyStyle.LOGO.getColorModel().hasAlpha());
            return null;
        });
    }

    @Test void allTabsKeepRealPanelsAndTheirCallbacks()throws Exception {
        onEdt(()-> {
            var home=new LauncherHomePanel((text, error)-> { });
            home.setAnimationEnabled(false);
            render(home, 1040, 680);
            var tabs=named(home, "journey-main-tabs", PixelTabs.class);
            for (int i=0; i<4; i++) {
                tabs.setSelectedIndex(i);
                render(home, 1040, 680);
                assertEquals(LauncherHomePanel.Screen.values()[i], home.selectedScreen());
            }
            var mods=home.mods();
            var skins=home.skins();
            var profile=home.profile();
            var server=home.serverSettings();
            for (var section:new LauncherHomePanel.Section[] {
                    LauncherHomePanel.Section.VERSIONS, LauncherHomePanel.Section.MODS,
                    LauncherHomePanel.Section.RESOURCEPACKS }) {
                home.showSection(section);
                render(home, 1040, 680);
                assertEquals(section, home.selectedSection());
                assertEquals(LauncherHomePanel.Screen.LIBRARY, home.selectedScreen());
            }
            var menu=new JPopupMenu();
            home.setAccountMenu(menu);
            home.setAccount("ActualUser", true);
            var account=find(home, PlayerAccountCard.class);
            account.getActionMap().get("open-account").actionPerformed(null);
            assertEquals(LauncherHomePanel.Screen.SETTINGS, home.selectedScreen());
            assertSame(menu, account.getComponentPopupMenu());
            assertEquals("ActualUser", named(home.profile(),
                    "profile-username", JTextField.class).getText());
            int[] calls= { 0, 0, 0 };
            server.onTest(()->calls[0]++);
            home.settings().onDisconnect(()->calls[1]++);
            home.settings().onRememberDevice(v->calls[2]++);
            named(server, "test-server-connection", PixelButton.class).doClick(0);
            named(home.settings(), "settings-disconnect", JButton.class).doClick(0);
            named(home.settings(), "settings-remember-device",
                    PixelCheckBox.class).doClick(0);
            assertArrayEquals(new int[] { 1, 1, 1 }, calls);
            named(home.settings(), "settings-about", JButton.class).doClick(0);
            assertEquals(LauncherHomePanel.Screen.ABOUT, home.selectedScreen());
            named(home, "about-back", JButton.class).doClick(0);
            assertEquals(LauncherHomePanel.Screen.SETTINGS, home.selectedScreen());
            assertSame(mods, home.mods());
            assertSame(skins, home.skins());
            assertSame(profile, home.profile());
            assertSame(server, home.serverSettings());
            return null;
        });
    }

    @Test void longCatalogScrollsAndSurvivesNavigation()throws Exception {
        onEdt(()-> {
            var home=new LauncherHomePanel((text, error)-> { });
            home.setAnimationEnabled(false);
            home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
            var list=home.resourcepacks();
            list.setMods(java.util.stream.IntStream.range(0,
                    30).mapToObj(i->new dev.leverage.client.InstalledModCatalog.Mod(
                    Path.of("example-"+i+".zip"), "0".repeat(40), "Elemento "+i,
                    "Formato 46", "Descripción", null)).toList(), ignored-> { });
            render(home, 820, 560);
            var scroll= (JScrollPane)SwingUtilities.getAncestorOfClass(
                    JScrollPane.class, list);
            assertNotNull(scroll);
            assertTrue(scroll.getVerticalScrollBar().getMaximum(
                    )>scroll.getVerticalScrollBar().getVisibleAmount());
            for (Component child:list.getComponents())if (
                    child instanceof PixelSeparator)assertEquals(list.getWidth(),
                    child.getWidth());
            home.showScreen(LauncherHomePanel.Screen.NEWS);
            home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
            render(home, 820, 560);
            assertSame(scroll, SwingUtilities.getAncestorOfClass(
                    JScrollPane.class, list));
            assertTrue(descendants(list).stream().anyMatch(
                    c->c instanceof ResourcePackCard&&c.getAccessibleContext(
                    ).getAccessibleName().equals("Paquete de recursos: Elemento 29")));
            return null;
        });
    }

    @Test void everyMainTabUsesRealVerticalTravelAndOneMovingNav()throws Exception {
        Path output=outputDirectory();
        for (var from:List.of(LauncherHomePanel.Screen.HOME,
                LauncherHomePanel.Screen.LIBRARY, LauncherHomePanel.Screen.SKINS,
                LauncherHomePanel.Screen.NEWS)) {
            for (var to:List.of(LauncherHomePanel.Screen.HOME,
                    LauncherHomePanel.Screen.LIBRARY, LauncherHomePanel.Screen.SKINS,
                    LauncherHomePanel.Screen.NEWS))if (from!=to) {
                var frame=onEdt(()-> {
                    var home=new LauncherHomePanel((text, error)-> { });
                    home.showScreen(from);
                    render(home, 1040, 680);
                    home.previewTransitionForTest(to, .5);
                    BufferedImage image=render(home, 1040, 680);
                    assertEquals(.5, home.transitionProgress());
                    int page=680-JourneyNavigation.HEIGHT;
                    var nav=named(home, "home-navigation", JPanel.class);
                    assertEquals((int)Math.round(JourneyMotion.vertical(page,
                            from==LauncherHomePanel.Screen.HOME?page:0,
                            to==LauncherHomePanel.Screen.HOME?page:0,
                            to.ordinal()<from.ordinal(), .5).navigationY()), nav.getY());
                    home.setTransitionProgressForTest(1);
                    assertEquals(to, home.selectedScreen());
                    return image;
                });
                write(output, "scroll-"+from.name().toLowerCase()+"-"
                        +to.name().toLowerCase()+"-half", frame);
            }
        }
    }

    @Test void seamHasNoTransparentPixelsAndCorrectContactsInBothDirections() {
        for (boolean reverse:new boolean[] { false, true }) {
            var image=new BufferedImage(192, 400, BufferedImage.TYPE_INT_ARGB);
            var g=image.createGraphics();
            PixelSlideSeam.paint(g, 0, 0, 192, 400, 32, 8,
                    Color.RED, Color.BLUE, reverse);
            g.dispose();
            for (int y=0; y<400; y++) {
                assertEquals((reverse?Color.BLUE:Color.RED).getRGB(), image.getRGB(0, y));
                assertEquals((reverse?Color.RED:Color.BLUE).getRGB(),
                        image.getRGB(191, y));
                for (int x=0; x<192; x++)assertEquals(255, image.getRGB(x, y)>>>24);
            }
        }
    }

    @Test void nativeLibraryTravelsHorizontallyWithBlockSeamInBothDirections(
            )throws Exception {
        Path output=outputDirectory();
        for (boolean reverse:new boolean[] { false, true }) {
            BufferedImage image=onEdt(()-> {
                var home=new LauncherHomePanel((text, error)-> { });
                home.showSection(reverse?LauncherHomePanel.Section.MODS
                        :LauncherHomePanel.Section.VERSIONS);
                render(home, 1040, 680);
                var library=find(home, JourneyLibraryPanel.class);
                assertNotNull(library);
                library.previewTabForTest(reverse?0:1, .5);
                BufferedImage frame=render(home, 1040, 680);
                assertNotEquals(frame.getRGB(16, 180), frame.getRGB(1020, 180),
                        "Both differently colored pages must be visible together");
                return frame;
            });
            write(output, reverse?"library-seam-reverse-half"
                    :"library-seam-forward-half", image);
        }
    }
}

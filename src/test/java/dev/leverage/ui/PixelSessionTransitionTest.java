package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.LauncherPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PixelSessionTransitionTest {
    @TempDir Path isolated;

    @Test void squaresGrowToTheAvatarSizeInARightToLeftWave() {
        assertEquals(32, PixelSessionTransition.BLOCK_SIZE);
        for (int columns:new int[] { 1, 26, 33, 40 })for (int row=0; row<25;
                row++)for (int column=0; column<columns; column++) {
            int previous=0;
            for (int step=0; step<=100; step++) {
                int side=PixelSessionTransition.blockSide(column,
                        row, columns, step/100d, false);
                assertTrue(side>=previous && side<=32);
                previous=side;
                assertEquals(32-side, PixelSessionTransition.blockSide(column,
                        row, columns, step/100d, true));
            }
            assertEquals(32, previous);
        }
        assertEquals(0, PixelSessionTransition.blockSide(0, 0, 33, .4, false));
        assertEquals(32, PixelSessionTransition.blockSide(32, 0, 33, .4, false));
    }

    @Test void swapsOnlyWhenOpaqueAndCoversEveryPixelEvenAfterResize() throws Exception {
        onEdt(()-> {
            var swaps=new AtomicInteger();
            var panel=new PixelSessionTransition(new CardLayout());
            var old=new JPanel();
            old.setBackground(Color.RED);
            panel.pages().add(old, "old");
            panel.transition(()-> {
                assertEquals(PixelSessionTransition.Phase.HOLD, panel.phase());
                swaps.incrementAndGet();
                old.setBackground(Color.BLUE);
            }, true);
            panel.previewElapsed(PixelSessionTransition.COVER_MS/2);
            assertEquals(0, swaps.get());
            panel.previewElapsed(PixelSessionTransition.COVER_MS);
            assertEquals(1, swaps.get());
            for (int[] size:new int[][] { { 1040, 680 }, { 821, 563 }, { 1280, 800 } }) {
                BufferedImage covered=capture(panel, size[0], size[1]);
                for (int y=0; y<size[1]; y++)for (int x=0; x<size[0]; x++)assertEquals(
                        JourneyStyle.TABLE.getRGB(), covered.getRGB(x, y));
            }
            panel.previewElapsed(PixelSessionTransition.HOLD_MS);
            assertEquals(PixelSessionTransition.Phase.REVEAL, panel.phase());
            panel.previewElapsed(PixelSessionTransition.REVEAL_MS);
            assertFalse(panel.isActive());
            assertEquals(1, swaps.get());
            assertEquals(Color.BLUE.getRGB(), capture(panel, 1040, 680).getRGB(520, 340));
            return null;
        });
    }

    @Test void cancellationNewNavigationAndRemovalCannotRunAnOldCallback(
            ) throws Exception {
        onEdt(()-> {
            var panel=new PixelSessionTransition(new CardLayout());
            var old=new AtomicInteger();
            var next=new AtomicInteger();
            panel.transition(old::incrementAndGet, true);
            panel.showImmediately(next::incrementAndGet);
            panel.previewElapsed(5000);
            assertEquals(0, old.get());
            assertEquals(1, next.get());
            assertFalse(panel.isActive());
            panel.transition(old::incrementAndGet, true);
            panel.transition(next::incrementAndGet, true);
            panel.previewElapsed(PixelSessionTransition.COVER_MS);
            assertEquals(0, old.get());
            assertEquals(2, next.get());
            panel.transition(old::incrementAndGet, true);
            panel.removeNotify();
            panel.previewElapsed(5000);
            assertEquals(0, old.get());
            assertFalse(panel.isActive());
            return null;
        });
    }

    @Test @SuppressWarnings({ "rawtypes", "unchecked" })
    void blocksKeyboardDuringTheWaveAndRestoresItAfterward() throws Exception {
        onEdt(()-> {
            var panel=new PixelSessionTransition(new CardLayout());
            var field=new JTextField();
            panel.pages().add(field, "input");
            var layer=find(panel, JLayer.class);
            var ui= (LayerUI)layer.getUI();
            panel.transition(()-> { }, true);
            var during=new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    0, 0, KeyEvent.VK_ENTER, '\n');
            ui.eventDispatched(during, layer);
            assertTrue(during.isConsumed());
            panel.cancel();
            var after=new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    0, 0, KeyEvent.VK_ENTER, '\n');
            ui.eventDispatched(after, layer);
            assertFalse(after.isConsumed());
            return null;
        });
    }

    @Test void
            startupHoldsUntilTheSessionResultArrivesButWindowlessCallsFinishImmediately(
            ) throws Exception {
        onEdt(()-> {
            var panel=new PixelSessionTransition(new CardLayout());
            var result=new AtomicInteger();
            panel.coverStartup();
            assertEquals(PixelSessionTransition.Phase.HOLD, panel.phase());
            panel.previewElapsed(30_000);
            assertTrue(panel.isActive());
            assertEquals(0, result.get());
            panel.revealStartup(result::incrementAndGet);
            assertFalse(panel.isActive());
            assertEquals(1, result.get());
            panel.transition(result::incrementAndGet);
            assertEquals(2, result.get());
            assertFalse(panel.isActive());
            return null;
        });
    }

    @Test void earlyAndLateSessionResultsBothStayHiddenUntilTheCurtainIsOpaque(
            ) throws Exception {
        onEdt(()-> {
            for (boolean early:new boolean[] { true, false }) {
                var panel=new PixelSessionTransition(new CardLayout(), ()->true);
                var result=new AtomicInteger();
                panel.coverStartup();
                panel.previewElapsed(0);
                if (early)panel.revealStartup(result::incrementAndGet);
                assertEquals(0, result.get());
                panel.previewElapsed(PixelSessionTransition.COVER_MS);
                assertEquals(PixelSessionTransition.Phase.HOLD, panel.phase());
                if (!early) {
                    panel.previewElapsed(20_000);
                    assertEquals(0, result.get());
                    panel.revealStartup(result::incrementAndGet);
                }
                assertEquals(1, result.get());
                assertTrue(panel.isActive());
                panel.previewElapsed(PixelSessionTransition.HOLD_MS);
                assertEquals(PixelSessionTransition.Phase.REVEAL, panel.phase());
                panel.previewElapsed(PixelSessionTransition.REVEAL_MS);
                assertFalse(panel.isActive());
                assertEquals(1, result.get());
            }
            return null;
        });
    }

    @Test void changingLanguageReplaysTheSameCurtainAndPreservesTheSettingsScrollPosition(
            ) throws Exception {
        onEdt(()-> {
            FlatDarkLaf.setup();
            var panel=new JourneySettingsPanel(new ProfileSettingsPanel(),
                    new ServerSettingsPanel(), ()-> { });
            var scroll=JourneyStyle.scroll(panel, JourneyStyle.INK);
            var curtain=new PixelSessionTransition(new CardLayout(), ()->true);
            curtain.pages().add(scroll, "settings");
            panel.onLanguageChange(language->curtain.transition(()-> { }));
            render(curtain, 1040, 680);
            scroll.getViewport().setViewPosition(new Point(0, 250));
            Point position=scroll.getViewport().getViewPosition();
            var language=named(panel, "settings-language", PixelDropdown.class);
            for (int index:new int[] { 1, 2, 3, 0 }) {
                language.setSelectedIndex(index);
                assertEquals(PixelSessionTransition.Phase.COVER, curtain.phase());
                curtain.previewElapsed(PixelSessionTransition.COVER_MS);
                assertEquals(PixelSessionTransition.Phase.HOLD, curtain.phase());
                curtain.previewElapsed(PixelSessionTransition.HOLD_MS);
                assertEquals(PixelSessionTransition.Phase.REVEAL, curtain.phase());
                curtain.previewElapsed(PixelSessionTransition.REVEAL_MS);
                assertFalse(curtain.isActive());
                render(curtain, 1040, 680);
                assertEquals(position, scroll.getViewport().getViewPosition());
                assertTrue(scroll.isVisible());
            }
            language.setSelectedIndex(0);
            assertFalse(curtain.isActive());
            return null;
        });
    }

    @Test void exportsTheActualAccessAndHomeWaveWithoutAnyDesktopWindow(
            ) throws Exception {
        Path output=outputDirectory();
        var root=onEdt(()-> {
            FlatDarkLaf.setup();
            var window=new LauncherWindow(null, new LauncherPreferences(isolated));
            var create=LauncherWindow.class.getDeclaredMethod("createContent");
            create.setAccessible(true);
            var panel= (PixelSessionTransition)create.invoke(window);
            assertTrue(named(panel, "startup-wait", JPanel.class).isVisible());
            assertFalse(find(panel, AtlasSetupPanel.class).isVisible());
            assertFalse(find(panel, LauncherHomePanel.class).isVisible());
            var show=LauncherWindow.class.getDeclaredMethod("show",
                    String.class, JTextField.class);
            show.setAccessible(true);
            show.invoke(window, "login", null);
            var home=find(panel, LauncherHomePanel.class);
            home.setAnimationEnabled(false);
            home.setAccount("Jugador", true);
            render(panel, 1040, 680);
            panel.transition(()-> {
                ((CardLayout)panel.pages().getLayout()).show(panel.pages(), "home");
                home.showHome();
            }, true);
            panel.previewElapsed(0);
            return panel;
        });
        List<BufferedImage> frames=new ArrayList<>();
        for (int elapsed=0; elapsed<=PixelSessionTransition.COVER_MS; elapsed+=31) {
            int time=elapsed;
            frames.add(onEdt(()-> {
                root.previewElapsed(time);
                return capture(root, 1040, 680);
            }));
        }
        write(output, "login-wave-midpoint", frames.get(10));
        write(output, "wave-screen-covered", frames.getLast());
        frames.add(frames.getLast());
        frames.add(frames.getLast());
        int revealStart=frames.size();
        onEdt(()-> {
            root.previewElapsed(PixelSessionTransition.HOLD_MS);
            return null;
        });
        for (int elapsed=0; elapsed<=PixelSessionTransition.REVEAL_MS; elapsed+=31) {
            int time=elapsed;
            frames.add(onEdt(()-> {
                root.previewElapsed(time);
                return capture(root, 1040, 680);
            }));
        }
        write(output, "home-wave-midpoint", frames.get(revealStart+10));
        write(output, "home-wave-end", frames.getLast());
        for (int i=0; i<15; i++)frames.add(frames.getLast());
        writeGif(output.resolve("login-home-transition.gif"), frames);
        onEdt(()-> {
            root.cancel();
            return null;
        });
    }

    private static BufferedImage capture(JComponent panel, int width, int height) {
        panel.setSize(width, height);
        layout(panel);
        return JourneyStyle.snapshot(panel, width, height);
    }

    private static void writeGif(Path path, List<BufferedImage> frames) throws Exception {
        var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try (var output=ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i=0; i<frames.size(); i++) {
                var frame=frames.get(i);
                var metadata=writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame),
                        writer.getDefaultWriteParam());
                String format=metadata.getNativeMetadataFormatName();
                var tree= (IIOMetadataNode)metadata.getAsTree(format);
                var control= (IIOMetadataNode)tree.getElementsByTagName(
                        "GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", "3");
                control.setAttribute("transparentColorIndex", "0");
                if (i==0) {
                    var extensions=new IIOMetadataNode("ApplicationExtensions");
                    var loop=new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[] { 1, 0, 0 });
                    extensions.appendChild(loop);
                    tree.appendChild(extensions);
                }
                metadata.setFromTree(format, tree);
                writer.writeToSequence(new IIOImage(frame, null,
                        metadata), writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
    }
}

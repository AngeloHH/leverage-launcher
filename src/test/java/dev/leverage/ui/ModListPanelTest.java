package dev.leverage.ui;

import dev.leverage.client.InstalledModCatalog;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModListPanelTest {
    @Test void successfulModAndResourcepackImportsDoNotLeaveASuccessNotification(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        for (boolean packs:new boolean[] { false, true }) {
            Path target=java.nio.file.Files.createDirectory(
                    root.resolve(packs?"packs":"mods"));
            Path file=java.nio.file.Files.writeString(root.resolve(packs
                    ?"pack.zip":"mod.jar"), "fixture");
            var panel=new java.util.concurrent.atomic.AtomicReference<ModListPanel>();
            var operation=new java.util.concurrent.atomic.AtomicReference<java
                    .util.concurrent.CompletableFuture<Void>> ();
            var message=new java.util.concurrent.atomic.AtomicReference<String>();
            var error=new java.util.concurrent.atomic.AtomicBoolean();
            SwingUtilities.invokeAndWait(()-> {
                var value=new ModListPanel(packs);
                panel.set(value);
                value.onNotice((text, failed)-> {
                    message.set(text);
                    error.set(failed);
                });
                operation.set(value.loadDirectory(target));
            });
            operation.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(()->operation.set(panel.get(
                    ).installFiles(List.of(file))));
            operation.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("", message.get());
            assertFalse(error.get());
            SwingUtilities.invokeAndWait(()->assertEquals(1, countRows(panel.get())));
        }
    }

    @Test void filePickersAllowMultipleJarsZipsAndPngs() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var mods=new ModListPanel().filePicker();
            var packs=new ModListPanel(true).filePicker();
            var skins=SkinGalleryPanel.filePicker();
            for (var picker:List.of(mods, packs, skins)) {
                assertTrue(picker.isMultiSelectionEnabled());
                assertFalse(picker.isAcceptAllFileFilterUsed());
            }
            assertTrue(mods.getFileFilter().accept(new java.io.File("mod.jar")));
            assertFalse(mods.getFileFilter().accept(new java.io.File("pack.zip")));
            assertTrue(packs.getFileFilter().accept(new java.io.File("pack.zip")));
            assertTrue(skins.getFileFilter().accept(new java.io.File("skin.png")));
        });
    }

    @Test void batchImportsRefreshOnceAndKeepPartialFailureNotice(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        for (boolean packs:new boolean[] { false, true }) {
            Path target=java.nio.file.Files.createDirectory(
                    root.resolve(packs?"packs":"mods"));
            String extension=packs?".zip":".jar";
            Path first=java.nio.file.Files.writeString(root.resolve("a"
                    +extension), "first");
            Path second=java.nio.file.Files.writeString(root.resolve("b"
                    +extension), "second");
            Path invalid=java.nio.file.Files.writeString(
                    root.resolve("bad.txt"), "invalid");
            var ref=new java.util.concurrent.atomic.AtomicReference<ModListPanel>();
            var operation=new java.util.concurrent.atomic.AtomicReference<java
                    .util.concurrent.CompletableFuture<Void>> ();
            var message=new java.util.concurrent.atomic.AtomicReference<String>();
            var error=new java.util.concurrent.atomic.AtomicBoolean();
            SwingUtilities.invokeAndWait(()-> {
                var panel=new ModListPanel(packs);
                ref.set(panel);
                panel.onNotice((text, failed)-> {
                    message.set(text);
                    error.set(failed);
                });
                operation.set(panel.loadDirectory(target));
            });
            operation.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(()->operation.set(ref.get(
                    ).installFiles(List.of(first, invalid, second))));
            operation.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(()->assertEquals(2, countRows(ref.get())));
            assertTrue(error.get());
            assertTrue(message.get().contains("2 de 3"));
            assertTrue(message.get().contains("bad.txt"));
            SwingUtilities.invokeAndWait(()->operation.set(ref.get(
                    ).installFiles(List.of(first, second))));
            operation.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(message.get().contains("0 de 2"));
            assertEquals("first", java.nio.file.Files.readString(
                    target.resolve(first.getFileName())));
        }
    }

    @Test void oldBatchCannotPublishRowsOrNoticesInAnotherInstance(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path old=java.nio.file.Files.createDirectory(root.resolve("old"));
        Path next=java.nio.file.Files.createDirectory(root.resolve("next"));
        Path selected=java.nio.file.Files.writeString(root.resolve("mod.jar"), "fixture");
        var ref=new java.util.concurrent.atomic.AtomicReference<ModListPanel>();
        var load=new java.util.concurrent.atomic.AtomicReference<java
                .util.concurrent.CompletableFuture<Void>> ();
        var batch=new java.util.concurrent.atomic.AtomicReference<java
                .util.concurrent.CompletableFuture<Void>> ();
        var message=new java.util.concurrent.atomic.AtomicReference<String>();
        SwingUtilities.invokeAndWait(()-> {
            var panel=new ModListPanel();
            ref.set(panel);
            panel.onNotice((text, error)->message.set(text));
            load.set(panel.loadDirectory(old));
        });
        load.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()-> {
            batch.set(ref.get().installFiles(List.of(selected)));
            load.set(ref.get().loadDirectory(next));
        });
        batch.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
        load.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(()-> {
            assertEquals(next, ref.get().directory());
            assertEquals(0, countRows(ref.get()));
            assertEquals("", message.get());
        });
    }

    @Test
    void descriptionsRespectTheirLineLimitAndAddThreeDotsOnlyWhenNeeded(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int maxLines : new int[] { 1, 2 }) {
                var longText = new ModListPanel.RowDescription((
                        "Una descripción larga con muchas palabras que debe respetar "
                        + "el límite de líneas ")
                        .repeat(5), maxLines);
                var lines = longText.lines(190);
                assertEquals(maxLines, lines.size());
                assertTrue(lines.getLast().endsWith("..."));
                for (String line : lines) assertTrue(longText.getFontMetrics(
                        longText.getFont()).stringWidth(line) <= 190);
                assertEquals(List.of("Corta"), new ModListPanel.RowDescription("Corta",
                        maxLines).lines(190));
                assertTrue(longText.lines(0).isEmpty());
                assertEquals(LauncherFonts.minecraftSeven(12f), longText.getFont());
            }
        });
    }

    @Test
    void modRowsUseMinecraftTenExceptForDescriptionsAndInvokeOnlyTheirOwnRemoval(
            ) throws Exception {
        var image = ImageIO.read(LauncherIcon.class.getResource(
                "/dev/leverage/ui/images/favicon.png"));
        SwingUtilities.invokeAndWait(() -> {
            var mod = new InstalledModCatalog.Mod(Path.of("sample.jar"), "0".repeat(40),
                    "Ejemplo visual (no instalado)", "1.0 — ejemplo",
                    ("Descripción de ejemplo para revisar el tamaño, el ajuste" +
                    " a una línea y los puntos suspensivos. ").repeat(4), image);
            int[] clicked = { 0 };
            var row = new ModListPanel.ModRow(mod, selected -> {
                assertSame(mod, selected);
                clicked[0]++;
            });
            row.setSize(491, 96);
            row.doLayout();
            assertEquals(LauncherFonts.minecraftTen(20f), row.name.getFont());
            assertEquals(LauncherFonts.minecraftTen(14f), row.version.getFont());
            assertEquals(LauncherFonts.minecraftTen(16f), row.remove.getFont());
            assertEquals(Color.WHITE, row.remove.getForeground());
            var buttonImage=new BufferedImage(92, 34, BufferedImage.TYPE_INT_ARGB);
            var buttonGraphics=buttonImage.createGraphics();
            try {
                row.remove.paint(buttonGraphics);
            } finally {
                buttonGraphics.dispose();
            }
            assertEquals(PixelButton.Variant.DANGER.face().getRGB(),
                    buttonImage.getRGB(10, 8));
            assertEquals(LauncherFonts.minecraftSeven(12f), row.description.getFont());
            assertEquals(Color.WHITE, row.name.getForeground());
            assertEquals(34, row.remove.getHeight());
            assertEquals(32, row.open.getWidth());
            assertEquals(32, row.more.getWidth());
            assertTrue(row.description.getX()+row.description.getWidth(
                    ) < row.remove.getX());
            assertTrue(row.remove.getX()+row.remove.getWidth() < row.open.getX());
            assertTrue(row.open.getX()+row.open.getWidth() < row.more.getX());
            assertEquals(3, row.more.getComponentPopupMenu().getComponentCount());
            row.remove.doClick(0);
            assertEquals(1, clicked[0]);
            assertEquals(1, row.description.lines(row.description.getWidth()).size());
            assertTrue(row.description.lines(row.description.getWidth(
                    )).getFirst().endsWith("..."));
            assertEquals(row.description.getFontMetrics(row.description.getFont(
                    )).getHeight(), row.description.getHeight());
            String output = System.getenv("LEVERAGE_PLAY_PREVIEW");
            if (output != null) {
                LauncherHomePanel home = new LauncherHomePanel();
                home.mods().setMods(List.of(mod, mod), ignored -> { });
                home.showSection(LauncherHomePanel.Section.MODS);
                home.setSize(1040, 680);
                layout(home);
                var preview = new BufferedImage(1040, 680, BufferedImage.TYPE_INT_ARGB);
                var g = preview.createGraphics();
                try {
                    home.paint(g);
                } finally {
                    g.dispose();
                }
                try {
                    ImageIO.write(preview, "PNG", Path.of(output,
                            "leverage-mod-rows-preview.png").toFile());
                }
                catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
                home.resourcepacks().setMods(List.of(new InstalledModCatalog.Mod(
                        Path.of("example.zip"), "1".repeat(40),
                        "Paquete de ejemplo (no instalado)", "Formato 46",
                        "Descripción de ejemplo de un paquete de recursos. "
                        .repeat(4), image)), ignored -> { });
                home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
                layout(home);
                var resources = new BufferedImage(1040, 680, BufferedImage.TYPE_INT_ARGB);
                var resourceGraphics = resources.createGraphics();
                try {
                    home.paint(resourceGraphics);
                } finally {
                    resourceGraphics.dispose();
                }
                try {
                    ImageIO.write(resources, "PNG", Path.of(output,
                            "leverage-resourcepack-rows-preview.png").toFile());
                }
                catch (java.io.IOException exception) {
                    throw new AssertionError(exception);
                }
            }
        });
    }

    @Test
    void modVersionsSitNextToTheNameOnTheSameBaselineWithoutOverlappingActions(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String title:List.of("Sodium",
                    "Nombre de mod muy largo que debe truncarse sin tapar la versión")) {
                for (String version:List.of("1.2.3",
                        "Una versión extraordinariamente larga")) {
                    var mod = new InstalledModCatalog.Mod(Path.of("example.jar"),
                            "a".repeat(40), title, version, "Descripción", null);
                    var row = new ModListPanel.ModRow(mod, ignored -> { });
                    for (int width:new int[] { 360, 491, 694, 1000 }) {
                        row.setSize(width, 100);
                        row.doLayout();
                        assertTrue(row.version.getFont().getSize2D(
                                )<row.name.getFont().getSize2D());
                        assertTrue(row.version.getX()>row.name.getX()
                                +row.name.getWidth());
                        assertEquals(row.name.getY()+row.name.getBaseline(
                                row.name.getWidth(), row.name.getHeight()),
                                row.version.getY()+row.version.getBaseline(
                                row.version.getWidth(), row.version.getHeight()));
                        assertTrue(row.version.getX()+row.version.getWidth(
                                )<row.remove.getX());
                        assertTrue(row.description.getY()>=row.name.getY()
                                +row.name.getHeight());
                    }
                }
            }
        });
    }

    @Test
    void modTitleAndDescriptionFormAVerticallyCenteredBlock() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var mod = new InstalledModCatalog.Mod(Path.of("example.jar"),
                    "a".repeat(40), "Sodium", "1.2.3", "Descripción", null);
            var row = new ModListPanel.ModRow(mod, ignored -> { });
            for (int height:new int[] { 96, 100, 130 }) {
                row.setSize(694, height);
                row.doLayout();
                int top=row.name.getY();
                int bottom=height-row.description.getY()-row.description.getHeight();
                assertTrue(Math.abs(top-bottom)<=1,
                        "The block must have equal space above and below");
                assertEquals(5, row.description.getY()-row.name.getY()
                        -row.name.getHeight());
                assertEquals(row.name.getY()+row.name.getBaseline(row.name.getWidth(),
                        row.name.getHeight()),
                        row.version.getY()+row.version.getBaseline(row.version.getWidth(),
                        row.version.getHeight()));
                assertEquals((height-34)/2, row.remove.getY());
                assertEquals(1, row.description.lines(row.description.getWidth()).size());
            }
        });
    }

    @Test
    void resourcepacksUseNewsGreenAndAnEditorialCardWithTheirOwnActions(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LauncherHomePanel home = new LauncherHomePanel();
            home.showSection(LauncherHomePanel.Section.RESOURCEPACKS);
            assertEquals(LauncherHomePanel.Screen.LIBRARY, home.selectedScreen());
            var listBackground=home.resourcepacks().getParent().getBackground();
            assertEquals(JourneyStyle.TABLE, listBackground);
            int[] opened= { 0 }, removed= { 0 };
            var pack = new InstalledModCatalog.Mod(Path.of("example.zip"),
                    "a".repeat(40), "Paquete", "Formato 46", "Descripción", null);
            var card = new ResourcePackCard(pack, selected -> {
                assertSame(pack, selected);
                removed[0]++;
            }, path -> {
                assertEquals(pack.file().toAbsolutePath().normalize().getParent(), path);
                opened[0]++;
            }, (message, error) -> { });
            card.setSize(150, ResourcePackCard.heightForWidth(150));
            card.doLayout();
            card.open.doClick(0);
            card.remove.doClick(0);
            assertEquals(1, opened[0]);
            assertEquals(1, removed[0]);
            assertEquals(JourneyStyle.INK, card.title.getForeground());
            assertEquals(LauncherFonts.minecraftSeven(12), card.details.getFont());
            assertEquals(new Color(0x2d6b22), card.details.getForeground());
            assertEquals(new Color(0xa92c25), card.remove.getForeground());
            var image=JourneyStyle.snapshot(card, 150,
                    ResourcePackCard.heightForWidth(150));
            assertEquals(JourneyStyle.PAPER.getRGB(), image.getRGB(4, 101));
            assertEquals(ResourcePackCard.DEPTH.getRGB(),
                    image.getRGB(4, image.getHeight()-2));
            assertTrue(card.title.getY()>=90);
            assertTrue(card.details.getX()+card.details.getWidth()<card.more.getX());
            assertTrue(card.more.getX()+card.more.getWidth()<=card.getWidth());
        });
    }

    @Test void resourceGridAlwaysHasSixEqualColumnsAndNeverStretchesAPartialRow(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[] { 976, 1216 }) {
                var panel=new ModListPanel(true);
                var fixture=new InstalledModCatalog.Mod(Path.of("pack.zip"),
                        "a".repeat(40),
                        "Un nombre muy largo que necesita ocupar varias línea"
                        + "s", "Formato 46",
                        "Una descripción bastante larga ".repeat(5), null);
                panel.setMods(java.util.Collections.nCopies(13, fixture), ignored -> { });
                panel.setSize(width, panel.getPreferredSize().height);
                layout(panel);
                var grid= (ModListPanel.ResourceGrid)java.util.Arrays.stream(
                        panel.getComponents())
                        .filter(child -> child instanceof ModListPanel
                        .ResourceGrid).findFirst().orElseThrow();
                assertEquals(13, grid.getComponentCount());
                var first=grid.getComponent(0);
                int cardWidth=first.getWidth();
                for (int i=0; i<13; i++) {
                    var card= (ResourcePackCard)grid.getComponent(i);
                    assertEquals(cardWidth, card.getWidth());
                    assertEquals((i%6)* (cardWidth+ModListPanel.ResourceGrid.GAP),
                            card.getX());
                    assertEquals((i/6)* (card.getHeight()
                            +ModListPanel.ResourceGrid.GAP), card.getY());
                    assertTrue(card.getX()+card.getWidth()<=width);
                    assertTrue(card.title.lines(card.title.getWidth()).size()<=2);
                    assertTrue(card.description.lines(
                            card.description.getWidth()).size()<=2);
                }
                assertTrue(grid.getComponent(5).getX()>grid.getComponent(0).getX());
                assertEquals(0, grid.getComponent(6).getX());
                assertEquals(0, grid.getComponent(12).getX());
                assertEquals(46+grid.getHeight(), panel.getPreferredSize().height);
            }
        });
    }

    @Test void resourceCardBusyDisablesAllActionsAndRespectsReadOnlyRemoval(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var fixture=new InstalledModCatalog.Mod(Path.of("pack.zip"),
                    "a".repeat(40), "Paquete", "Formato 46", "Descripción", null);
            for (boolean removable:new boolean[] { true, false }) {
                int[] removed= { 0 }, opened= { 0 };
                var card=new ResourcePackCard(fixture,
                        removable?ignored -> removed[0]++:null,
                        ignored -> opened[0]++, (message, error)-> { });
                card.setBusy(true);
                assertFalse(card.details.isEnabled());
                assertFalse(card.more.isEnabled());
                for (Component action:card.more.getComponentPopupMenu(
                        ).getComponents()) assertFalse(action.isEnabled());
                card.open.doClick(0);
                card.remove.doClick(0);
                assertEquals(0, opened[0]);
                assertEquals(0, removed[0]);
                card.setBusy(false);
                assertTrue(card.details.isEnabled());
                assertTrue(card.more.isEnabled());
                assertTrue(card.open.isEnabled());
                assertEquals(removable, card.remove.isEnabled());
                assertEquals(5, card.more.getComponentPopupMenu().getComponentCount());
            }
        });
    }

    @Test void resourceCardsUseTheirOwnPackPngAndResizeTheirHeightWithTheViewport(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var icon=new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var pixels=icon.createGraphics();
            pixels.setColor(new Color(0xe46d91));
            pixels.fillRect(0, 0, 16, 16);
            pixels.dispose();
            var fixture=new InstalledModCatalog.Mod(Path.of("pack.zip"), "a".repeat(40),
                    "Paquete propio", "Formato 46", "Descripción", icon);
            var card=new ResourcePackCard(fixture, null,
                    ignored -> { }, (message, error)-> { });
            var painted=JourneyStyle.snapshot(card, 150,
                    ResourcePackCard.heightForWidth(150));
            assertEquals(new Color(0xe46d91).getRGB(), painted.getRGB(75, 40));
            var panel=new ModListPanel(true);
            panel.setMods(java.util.Collections.nCopies(13, fixture), null);
            var wrapper=new JPanel(new BorderLayout());
            wrapper.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
            wrapper.add(panel, BorderLayout.NORTH);
            int priorHeight=0;
            for (int width:new int[] { 1040, 1280, 1040 }) {
                wrapper.setSize(width, 1000);
                layout(wrapper);
                assertEquals(width-64, panel.getWidth());
                assertEquals(panel.getPreferredSize().height, panel.getHeight());
                if (width==1280) assertTrue(panel.getHeight()>priorHeight);
                priorHeight=panel.getHeight();
            }
        });
    }

    @Test
    void loadingARealDirectoryBuildsTheRowsOffTheUiThread(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path jar = directory.resolve("example.jar");
        try (var zip = new java.util.zip.ZipOutputStream(
                java.nio.file.Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write("{\"name\":\"Real fixture\",\"version\":\"1.0\"}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var panel = new java.util.concurrent.atomic.AtomicReference<ModListPanel>();
        var load = new java.util.concurrent.atomic.AtomicReference<java
                .util.concurrent.CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new ModListPanel());
            load.set(panel.get().loadDirectory(directory));
        });
        load.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, countRows(panel.get()));
            panel.get().clearDirectory();
            assertEquals(0, countRows(panel.get()));
        });
    }

    private static int countRows(Container parent) {
        int count = 0;
        for (Component child : parent.getComponents()) {
            if (child instanceof ModListPanel.ModRow
                    || child instanceof ResourcePackCard) count++;
            else if (child instanceof Container container) count += countRows(container);
        }
        return count;
    }

    @Test
    void inlineServerFieldsKeepIndependentDefaultsAndDisableEditingDuringProbes(
            ) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ServerSettingsPanel panel = new ServerSettingsPanel();
            var sources = new dev.leverage.client.LauncherPreferences.DownloadSources(
                    dev.leverage.client.LauncherPreferences.DownloadSource.external(
                    java.net.URI.create("https://versions.example.test")),
                    dev.leverage.client.LauncherPreferences.DownloadSource.defaults());
            panel.load(java.net.URI.create("http://localhost:8000"), sources);
            assertEquals(sources, panel.requestedSources());
            panel.setBusy(true);
            for (Component child : panel.getComponents()) if (
                    child instanceof JTextField) assertFalse(child.isEnabled());
            panel.setBusy(false);
            assertEquals(sources, panel.requestedSources());
        });
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (
                child instanceof Container container) layout(container);
    }
}

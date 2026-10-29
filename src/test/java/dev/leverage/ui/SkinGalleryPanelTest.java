package dev.leverage.ui;

import dev.leverage.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SkinGalleryPanelTest {
    @TempDir Path directory;
    @Test void importsSeveralSkinsOnceWithoutEquippingAndPreservesThemAfterRestart(
            ) throws Exception {
        Path first=directory.resolve("first.png"), second=directory.resolve("second.png"),
                invalid=directory.resolve("invalid.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", first.toFile());
        ImageIO.write(LocalSkinLibrary.defaults().getLast().image(),
                "PNG", second.toFile());
        java.nio.file.Files.writeString(invalid, "not a PNG");
        var ref=new AtomicReference<SkinGalleryPanel>();
        var applied=new AtomicInteger();
        var message=new AtomicReference<String>();
        var error=new AtomicBoolean();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var gallery=new SkinGalleryPanel();
            ref.set(gallery);
            gallery.onNotice((text, failed)-> {
                message.set(text);
                error.set(failed);
            });
            gallery.onUse(entry-> {
                applied.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            gallery.configure(preferences, null, null);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(ref.get().importFiles(
                java.util.List.of(first, invalid, second))));
        operation.get().get(10, TimeUnit.SECONDS);
        assertEquals(0, applied.get());
        assertTrue(error.get());
        assertTrue(message.get().contains("2 de 3"));
        assertTrue(message.get().contains("invalid.png"));
        var state=new LocalSkinLibrary(directory, null, null).load();
        assertEquals(8, state.entries().size());
        assertNull(state.activeId());
        assertEquals("first", state.entries().get(6).name());
        assertEquals("second", state.entries().get(7).name());
        SwingUtilities.invokeAndWait(()->assertEquals(9, ref.get().getComponentCount()));
    }

    @Test void successfulSkinImportsDoNotLeaveASuccessNotification() throws Exception {
        Path file=directory.resolve("skin.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", file.toFile());
        var preferences=new LauncherPreferences(directory);
        var gallery=new AtomicReference<SkinGalleryPanel>();
        var message=new AtomicReference<String>();
        var error=new AtomicBoolean();
        SwingUtilities.invokeAndWait(()-> {
            var panel=new SkinGalleryPanel();
            gallery.set(panel);
            panel.onNotice((text, failed)-> {
                message.set(text);
                error.set(failed);
            });
            panel.configure(preferences, null, null);
        });
        await(()->card(gallery.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get(
                ).importFiles(java.util.List.of(file))));
        operation.get().get(10, TimeUnit.SECONDS);
        assertEquals("", message.get());
        assertFalse(error.get());
        assertEquals(7, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
    }

    @Test void batchImportRespectsGalleryCapacity() throws Exception {
        var entries=new java.util.ArrayList<>(LocalSkinLibrary.defaults());
        while (entries.size()<127)entries.add(LocalSkinLibrary.duplicate(
                entries.getFirst()));
        new LocalSkinLibrary(directory, null, null).save(entries, null);
        Path first=directory.resolve("first.png"), second=directory.resolve("second.png");
        ImageIO.write(entries.getFirst().image(), "PNG", first.toFile());
        ImageIO.write(entries.getLast().image(), "PNG", second.toFile());
        var ref=new AtomicReference<SkinGalleryPanel>();
        var message=new AtomicReference<String>();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var gallery=new SkinGalleryPanel();
            ref.set(gallery);
            gallery.onNotice((text, error)->message.set(text));
            gallery.configure(preferences, null, null);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(ref.get(
                ).importFiles(java.util.List.of(first, second))));
        operation.get().get(10, TimeUnit.SECONDS);
        assertEquals(128, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
        assertTrue(message.get().contains("1 de 2"));
        assertTrue(message.get().contains("límite"));
    }

    @Test void lateImportsCannotChangeTheNewServerGallery() throws Exception {
        var ref=new AtomicReference<SkinGalleryPanel>();
        var message=new AtomicReference<String>();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var gallery=new SkinGalleryPanel();
            ref.set(gallery);
            gallery.onNotice((text, error)->message.set(text));
            gallery.configure(preferences, null, null);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        Path selected=directory.resolve("skin.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", selected.toFile());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()-> {
            operation.set(ref.get().importFiles(java.util.List.of(selected)));
            ref.get().configure(preferences, java.net.URI.create(
                    "https://other.example.test"), java.util.UUID.randomUUID());
        });
        operation.get().get(10, TimeUnit.SECONDS);
        await(()->card(ref.get(), "kai").copy.isEnabled());
        SwingUtilities.invokeAndWait(()->assertEquals(7, ref.get().getComponentCount()));
        assertFalse(message.get().contains("Archivos agregados"));
        assertEquals(7, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
    }

    @Test void matchesTheReferenceGridAndRendersTransparentStaticModels(
            ) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            SkinGalleryPanel gallery=new SkinGalleryPanel();
            gallery.select("kai");
            gallery.setSize(600, 460);
            layout(gallery);
            var first= (SkinGalleryPanel.SkinCard)gallery.getComponent(0);
            var kai= (SkinGalleryPanel.SkinCard)gallery.getComponent(3);
            var efe= (SkinGalleryPanel.SkinCard)gallery.getComponent(4);
            assertEquals(0, first.getY());
            assertTrue(kai.getY()>first.getY());
            assertTrue(efe.getY()>kai.getY());
            assertTrue(kai.apply.isVisible());
            assertFalse(first.apply.isVisible());
            assertEquals(new Color(0xE1D8CF), kai.getBackground());
            assertFalse(kai.model.isOpaque());
            assertEquals(32, kai.apply.getHeight());
            assertTrue(kai.modelLabel.getY()<kai.modelChoice.getY());
            assertTrue(kai.modelChoice.getY()+kai.modelChoice.getHeight(
                    )<=kai.apply.getY());
            assertTrue(kai.apply.getY()+kai.apply.getHeight()<=kai.copy.getY());
            assertTrue(kai.modelChoice.isVisible());
            assertFalse(kai.apply.isTextShadowPainted());
            assertFalse(kai.copy.isTextShadowPainted());
            for (PixelButton button:new PixelButton[] { kai.apply, kai.copy,
                    kai.remove })assertEquals(LauncherFonts.minecraftFive(13f),
                    button.getFont());
            var add=gallery.importButton();
            assertSame(add, gallery.getComponent(gallery.getComponentCount()-1));
            assertEquals("Añadir skin", add.getText());
            assertEquals(LauncherFonts.minecraftSeven(14f), add.getFont());
            assertEquals(new Color(0x376B24), add.getForeground());
            assertFalse(add.isContentAreaFilled());
            assertEquals("Agregar skin", add.getAccessibleContext().getAccessibleName());
            assertEquals(44, add.getHeight());
            assertEquals(1, add.getActionListeners().length);
            assertEquals(LauncherFonts.minecraftSeven(18f), kai.title.getFont());
            assertTrue(new PixelButton("Otro botón",
                    PixelButton.Variant.NEUTRAL).isTextShadowPainted());
            for (int width:new int[] { 523, 600, 694, 840 }) {
                gallery.setSize(width, gallery.getPreferredSize().height);
                layout(gallery);
                for (Component component:gallery.getComponents()) assertTrue(
                        component.getX()+component.getWidth()<=width);
            }
        });
        String output=System.getenv("LEVERAGE_PLAY_PREVIEW");
        if (output!=null) {
            var ref=new AtomicReference<LauncherHomePanel>();
            var preferences=new LauncherPreferences(directory);
            SwingUtilities.invokeAndWait(()-> {
                var home=new LauncherHomePanel();
                home.skins().configure(preferences, null, null);
                ref.set(home);
            });
            await(()->card(ref.get().skins(), "kai").copy.isEnabled());
            SwingUtilities.invokeAndWait(()-> {
                var home=ref.get();
                home.skins().select("kai");
                home.showSection(LauncherHomePanel.Section.SKINS);
                home.setSize(1040, 680);
                layout(home);
                BufferedImage image=new BufferedImage(1040, 680,
                        BufferedImage.TYPE_INT_ARGB);
                var g=image.createGraphics();
                try {
                    home.paint(g);
                } finally {
                    g.dispose();
                }
                try {
                    ImageIO.write(image, "PNG", Path.of(output,
                            "leverage-skins-gallery-preview.png").toFile());
                }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    @Test void removingAnImportedEntryPreservesItsSourceAndManagedPngAndSurvivesReload(
            ) throws Exception {
        Path source=directory.resolve("imported.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", source.toFile());
        var gallery=new AtomicReference<SkinGalleryPanel>();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var panel=new SkinGalleryPanel();
            panel.configure(preferences, null, null);
            gallery.set(panel);
        });
        await(()->card(gallery.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get(
                ).importFiles(java.util.List.of(source))));
        operation.get().get(10, TimeUnit.SECONDS);
        var library=new LocalSkinLibrary(directory, null, null);
        String id=library.load().entries().get(6).id();
        java.util.Set<Path> pngs;
        try (var files=java.nio.file.Files.walk(directory)) {
            pngs=files.filter(path->path.toString().endsWith(".png")).collect(
                    java.util.stream.Collectors.toSet());
        }
        SwingUtilities.invokeAndWait(()-> {
            var row=card(gallery.get(), id);
            gallery.get().select(id);
            gallery.get().setSize(740, 900);
            layout(gallery.get());
            assertTrue(row.remove.isVisible());
            var painted=new BufferedImage(120, 32, BufferedImage.TYPE_INT_ARGB);
            row.remove.setSize(120, 32);
            var g=painted.createGraphics();
            try {
                row.remove.paint(g);
            } finally {
                g.dispose();
            }
            assertEquals(PixelButton.Variant.DANGER.face().getRGB(),
                    painted.getRGB(8, 12));
            assertTrue(row.copy.getY()<row.remove.getY());
            operation.set(gallery.get().removeEntry(id));
        });
        operation.get().get(10, TimeUnit.SECONDS);
        assertEquals(6, library.load().entries().size());
        assertTrue(java.nio.file.Files.isRegularFile(source));
        for (Path png:pngs)assertTrue(java.nio.file.Files.isRegularFile(png),
                "Removing a library entry must not erase any PNG: "+png);
        SwingUtilities.invokeAndWait(()->assertThrows(AssertionError.class,
                ()->card(gallery.get(), id)));
        var reloaded=new LocalSkinLibrary(directory, null, null).load();
        assertTrue(reloaded.entries().stream().noneMatch(entry->entry.id().equals(id)));
    }

    @Test void removalRejectsDefaultAndActiveEntriesWithoutChangingTheLibrary(
            ) throws Exception {
        Path source=directory.resolve("active.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", source.toFile());
        var gallery=new AtomicReference<SkinGalleryPanel>();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var panel=new SkinGalleryPanel();
            panel.onUse(entry->CompletableFuture.completedFuture(null));
            panel.configure(preferences, null, null);
            gallery.set(panel);
        });
        await(()->card(gallery.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get().removeEntry("kai")));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(ExecutionException.class, ()->operation.get().get(10,
                TimeUnit.SECONDS)).getCause());
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get(
                ).importFiles(java.util.List.of(source))));
        operation.get().get(10, TimeUnit.SECONDS);
        var library=new LocalSkinLibrary(directory, null, null);
        String id=library.load().entries().get(6).id();
        SwingUtilities.invokeAndWait(()->card(gallery.get(), id).apply.doClick(0));
        await(()->card(gallery.get(), id).copy.isEnabled()
                &&!card(gallery.get(), id).apply.isEnabled());
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get().removeEntry(id)));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(ExecutionException.class, ()->operation.get().get(10,
                TimeUnit.SECONDS)).getCause());
        assertEquals(7, library.load().entries().size());
        assertEquals(id, library.load().activeId());
        assertTrue(java.nio.file.Files.isRegularFile(source));
    }

    @Test void
            aLateRemovalCannotReplaceAnotherServersGalleryAndToolbarOwnsOneImportAction(
            ) throws Exception {
        Path source=directory.resolve("remove-later.png");
        ImageIO.write(LocalSkinLibrary.defaults().getFirst().image(),
                "PNG", source.toFile());
        var gallery=new AtomicReference<SkinGalleryPanel>();
        var preferences=new LauncherPreferences(directory);
        var toolbar=new JPanel();
        SwingUtilities.invokeAndWait(()-> {
            var panel=new SkinGalleryPanel();
            toolbar.add(panel.importButton());
            panel.configure(preferences, null, null);
            gallery.set(panel);
        });
        await(()->card(gallery.get(), "kai").copy.isEnabled());
        var operation=new AtomicReference<CompletableFuture<Void>> ();
        SwingUtilities.invokeAndWait(()->operation.set(gallery.get(
                ).importFiles(java.util.List.of(source))));
        operation.get().get(10, TimeUnit.SECONDS);
        String id=new LocalSkinLibrary(directory, null,
                null).load().entries().get(6).id();
        SwingUtilities.invokeAndWait(()-> {
            operation.set(gallery.get().removeEntry(id));
            gallery.get().configure(preferences, java.net.URI.create(
                    "https://other.example.test"), java.util.UUID.randomUUID());
        });
        operation.get().get(10, TimeUnit.SECONDS);
        await(()->card(gallery.get(), "kai").copy.isEnabled());
        SwingUtilities.invokeAndWait(()-> {
            assertEquals(6, java.util.Arrays.stream(gallery.get().getComponents(
                    )).filter(SkinGalleryPanel.SkinCard.class::isInstance).count());
            assertSame(toolbar, gallery.get().importButton().getParent());
            assertEquals(1, gallery.get().importButton().getActionListeners().length);
            assertThrows(AssertionError.class, ()->card(gallery.get(), id));
        });
        assertEquals(6, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
        assertTrue(java.nio.file.Files.isRegularFile(source));
    }
    @Test void duplicatesAndChangesModelWithoutApplyingUntilUseIsPressed(
            ) throws Exception {
        var ref=new AtomicReference<SkinGalleryPanel>();
        var applied=new AtomicInteger();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var gallery=new SkinGalleryPanel();
            gallery.onUse(entry-> {
                applied.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            gallery.configure(preferences, null, null);
            ref.set(gallery);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        SwingUtilities.invokeAndWait(()->card(ref.get(), "kai").copy.doClick(0));
        await(()->ref.get().getComponentCount()==8 && ref.get(
                ).getComponent(6) instanceof SkinGalleryPanel.SkinCard row
                && row.modelChoice.isEnabled());
        SwingUtilities.invokeAndWait(()-> ((SkinGalleryPanel.SkinCard)ref.get(
                ).getComponent(6)).modelChoice.setSelectedIndex(1));
        await(()-> ((SkinGalleryPanel.SkinCard)ref.get().getComponent(6)).entry.slim() &&
                ((SkinGalleryPanel.SkinCard)ref.get().getComponent(6)).apply.isEnabled());
        assertEquals(0, applied.get());
        SwingUtilities.invokeAndWait(()-> ((SkinGalleryPanel.SkinCard)ref.get(
                ).getComponent(6)).apply.doClick(0));
        await(()->applied.get()==1 && ((SkinGalleryPanel.SkinCard)ref.get(
                ).getComponent(6)).copy.isEnabled());
        assertEquals(7, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
    }
    @Test void lateUseResultsDoNotReplaceTheNewAccountOrServerGallery() throws Exception {
        var ref=new AtomicReference<SkinGalleryPanel>();
        var shown=new AtomicInteger();
        var pending=new CompletableFuture<Void>();
        var preferences=new LauncherPreferences(directory);
        SwingUtilities.invokeAndWait(()-> {
            var gallery=new SkinGalleryPanel();
            gallery.onUse(entry->pending);
            gallery.onPreview((image, slim)->shown.incrementAndGet());
            gallery.configure(preferences, null, null);
            ref.set(gallery);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        SwingUtilities.invokeAndWait(()-> {
            card(ref.get(), "kai").apply.doClick(0);
            ref.get().configure(preferences, java.net.URI.create(
                    "https://other.example.test"), java.util.UUID.randomUUID());
            pending.complete(null);
        });
        await(()->card(ref.get(), "kai").copy.isEnabled());
        assertEquals(0, shown.get());
    }
    private static SkinGalleryPanel.SkinCard card(SkinGalleryPanel gallery, String id) {
        for (Component child:gallery.getComponents())if (
                child instanceof SkinGalleryPanel.SkinCard card
                && card.entry.id().equals(id))return card;
        throw new AssertionError("Missing skin "+id);
    }
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), ()-> {
            AtomicBoolean result=new AtomicBoolean();
            do {
                SwingUtilities.invokeAndWait(()->result.set(condition.getAsBoolean()));
                if (!result.get())Thread.sleep(20);
            }
            while (!result.get());
        }); }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child:container.getComponents())if (
                child instanceof Container nested)layout(nested);
    }
}

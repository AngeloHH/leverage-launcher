package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalSkinLibraryTest {
    @TempDir Path directory;
    @Test void containsTheSixReferenceSkinsWithRealClassicAndSlimVariants() {
        assertEquals(List.of("Sunny", "Noor", "Makena", "Kai", "Efe",
                "Ari"), LocalSkinLibrary.defaults().stream().map(
                LocalSkinLibrary.Entry::name).toList());
        for (var entry:LocalSkinLibrary.defaults()) {
            assertEquals(64, entry.image().getWidth());
            assertEquals(64, entry.image().getHeight());
            var slim=LocalSkinLibrary.toggleModel(entry);
            assertTrue(slim.slim());
            assertFalse(Arrays.equals(entry.image().getRGB(0, 0, 64, 64, null, 0,
                    64), slim.image().getRGB(0, 0, 64, 64, null, 0, 64)));
            assertFalse(LocalSkinLibrary.toggleModel(slim).slim());
        }
    }
    @Test void savesCopiesAndImportedPngsWithoutChangingTheEquippedModel(
            ) throws Exception {
        var library=new LocalSkinLibrary(directory, null, null);
        var state=library.load();
        var entries=new ArrayList<>(state.entries());
        var kai=entries.get(3);
        var copy=LocalSkinLibrary.duplicate(kai);
        entries.add(copy);
        Path input=directory.resolve("custom.png");
        Files.createDirectories(directory);
        ImageIO.write(kai.image(), "PNG", input.toFile());
        var imported=LocalSkinLibrary.importPng(input);
        entries.add(imported);
        entries.set(3, LocalSkinLibrary.toggleModel(kai));
        library.save(entries, "kai", false);
        var reopened=new LocalSkinLibrary(directory, null, null).load();
        assertEquals(8, reopened.entries().size());
        assertEquals("kai", reopened.activeId());
        assertFalse(reopened.activeSlim());
        assertTrue(reopened.entries().get(3).slim());
        assertEquals(imported.name(), reopened.entries().getLast().name());
        assertTrue(Files.exists(input));
    }
    @Test void keepsDifferentAccountsServersAndOfflineCollectionsSeparate(
            ) throws Exception {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        URI server=URI.create("https://example.test");
        var first=new LocalSkinLibrary(directory, server, a);
        var entries=new ArrayList<>(first.load().entries());
        entries.add(LocalSkinLibrary.duplicate(entries.getFirst()));
        first.save(entries, null);
        assertEquals(7, new LocalSkinLibrary(directory,
                URI.create("https://EXAMPLE.test:443/"), a).load().entries().size());
        assertEquals(6, new LocalSkinLibrary(directory, server,
                b).load().entries().size());
        assertEquals(6, new LocalSkinLibrary(directory,
                URI.create("http://example.test"), a).load().entries().size());
        assertEquals(6, new LocalSkinLibrary(directory, null,
                null).load().entries().size());
    }
    @Test void rejectsInvalidImagesAndTraversalInSavedEntries() throws Exception {
        Path invalid=directory.resolve("invalid.png");
        ImageIO.write(new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB),
                "PNG", invalid.toFile());
        assertThrows(java.io.IOException.class, ()->LocalSkinLibrary.importPng(invalid));
        var library=new LocalSkinLibrary(directory, null, null);
        library.save(library.load().entries(), null);
        Path catalog=directory.resolve("leverage/skins/offline/skins.json");
        Files.writeString(catalog,
                "{\"schemaVersion\":1,\"entries\":[{\"id\":\"../secret\",\"name\":\"B"
                + "ad\"}]}");
        assertThrows(java.io.IOException.class, library::load);
        assertTrue(Files.readString(catalog).contains("../secret"));
    }
}

package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class InstalledModCatalogTest {
    @TempDir Path directory;

    @Test
    void readsFabricIconMetadataAndLegacyForgeWithoutLoadingTheirCode() throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 3, 0xff36b030);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        jar("fabric.jar", Map.of("fabric.mod.json", utf8(
        """
                {"name":"Fabric Sample","version":"1.2.3","description":"A real \
                description",
                 "icon":{"64":"assets/sample/icon.png"}}
                """
        ), "assets/sample/icon.png", bytes.toByteArray()));
        jar("legacy.jar", Map.of("mcmod.info", utf8(
        """
                [{"name":"Legacy Sample","version":"2.0","description":"Legacy \
                description"}]
                """
        )));
        var mods = new InstalledModCatalog().scan(directory);
        assertEquals(2, mods.size());
        assertEquals("Fabric Sample", mods.get(0).name());
        assertEquals("1.2.3", mods.get(0).version());
        assertEquals("A real description", mods.get(0).description());
        assertEquals(0xff36b030, mods.get(0).icon().getRGB(3, 3));
        assertTrue(mods.get(0).sha1().matches("[0-9a-f]{40}"));
        assertEquals("Legacy Sample", mods.get(1).name());
    }

    @Test
    void readsFirstForgeModAndResolvesManifestVersion() throws Exception {
        jar("forge.jar", Map.of("META-INF/mods.toml", utf8(
        """
                modLoader="javafml"
                [[mods]]
                modId="sample"
                displayName="Forge Sample"
                version="${file.jarVersion}"
                description='''A multi-line
                description.'''
                [[dependencies.sample]]
                modId="minecraft"
                """
        ), "META-INF/MANIFEST.MF", utf8(
                "Manifest-Version: 1.0\r\nImplementation-Version: 3.2\r\n\r\n")));
        var mod = new InstalledModCatalog().scan(directory).getFirst();
        assertEquals("Forge Sample", mod.name());
        assertEquals("3.2", mod.version());
        assertEquals("A multi-line\ndescription.", mod.description());
    }

    @Test
    void malformedMetadataAndExternalIconsCannotBreakTheListOrTriggerDownloads(
            ) throws Exception {
        jar("bad.jar", Map.of("fabric.mod.json", utf8("invalid")));
        jar("remote.jar", Map.of("fabric.mod.json", utf8(
        """
                {"name":"Remote","version":"1","icon":"https://127.0.0.1:1/icon.png"}
                """
        )));
        jar("huge.jar", Map.of("fabric.mod.json", utf8(" ".repeat(140000))));
        var mods = new InstalledModCatalog().scan(directory);
        assertEquals(3, mods.size());
        assertEquals("bad", mods.get(0).name());
        assertEquals("huge", mods.get(1).name());
        assertEquals("Remote", mods.get(2).name());
        assertTrue(mods.stream().allMatch(mod -> mod.icon() == null));
    }

    @Test
    void removalIsRecoverableAndRefusesOtherFilesOrChangedContent() throws Exception {
        Path source = jar("sample.jar", Map.of("fabric.mod.json",
                utf8("{\"name\":\"Sample\",\"version\":\"1\"}")));
        var catalog = new InstalledModCatalog();
        var mod = catalog.scan(directory).getFirst();
        byte[] original = Files.readAllBytes(source);
        Path backup = catalog.remove(directory, mod);
        assertFalse(Files.exists(source));
        assertTrue(backup.startsWith(directory.resolve(".leverage-removed")));
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertTrue(catalog.scan(directory).isEmpty());
        Files.write(source, original);
        Files.writeString(source, "modified");
        assertThrows(java.io.IOException.class, () -> catalog.remove(directory, mod));
        assertEquals("modified", Files.readString(source));
        var outside = new InstalledModCatalog.Mod(directory.getParent().resolve(
                "outside.jar"), mod.sha1(), "Outside", "1", "", null);
        assertThrows(java.io.IOException.class, () -> catalog.remove(directory, outside));
    }

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        Path file = directory.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

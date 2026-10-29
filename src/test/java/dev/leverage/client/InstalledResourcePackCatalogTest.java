package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class InstalledResourcePackCatalogTest {
    @TempDir Path root;

    @Test void readsPackMetadataAndIconWithoutExecutingOrExtractingAnything(
            ) throws Exception {
        Path packs = Files.createDirectories(root.resolve("resourcepacks"));
        try (var output = new ZipOutputStream(Files.newOutputStream(
                packs.resolve("example.zip")))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write((
                    "{\"pack\":{\"pack_format\":46,\"description\":{\"text\":\"Pack "
                    + "\",\"extra\":[{\"text\":\"description\"}]}}}").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("pack.png"));
            try (var image = getClass().getResourceAsStream(
                    "/dev/leverage/ui/images/favicon.png")) {
                image.transferTo(output);
            }
            output.closeEntry();
        }
        Files.writeString(packs.resolve("ignored.jar"), "not a resource pack");
        var catalog = new InstalledModCatalog();
        var rows = catalog.scanResources(packs);
        assertEquals(1, rows.size());
        var pack = rows.getFirst();
        assertEquals("example", pack.name());
        assertEquals("Formato 46", pack.version());
        assertEquals("Pack description", pack.description());
        assertTrue(pack.sha1().matches("[a-f0-9]{40}"));
        assertNotNull(pack.icon());
        Path removed = catalog.remove(packs, pack);
        assertTrue(Files.isRegularFile(removed));
        assertFalse(Files.exists(pack.file()));
    }

    @Test void importsWithoutOverwritingAndRejectsTheWrongFileType() throws Exception {
        Path packs = Files.createDirectories(root.resolve("resourcepacks"));
        Path source = Files.writeString(root.resolve("pack.zip"), "user selected file");
        var catalog = new InstalledModCatalog();
        Path target = catalog.install(packs, source, true);
        assertEquals("user selected file", Files.readString(target));
        assertTrue(Files.exists(source));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                ()->catalog.install(packs, source, true));
        assertEquals("user selected file", Files.readString(target));
        Path jar = Files.writeString(root.resolve("mod.jar"), "mod");
        assertThrows(java.io.IOException.class, ()->catalog.install(packs, jar, true));
        Path mods=Files.createDirectories(root.resolve("mods"));
        assertEquals("mod", Files.readString(catalog.install(mods, jar, false)));
    }
}

package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModDirectoryScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void scansOnlyJarFilesAndUsesSha1() throws Exception {
        Path mods = temporaryDirectory.resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("example.jar"), "hello");
        Files.writeString(mods.resolve("readme.txt"), "ignored");

        var result = new ModDirectoryScanner().scan(mods);

        assertEquals(1, result.size());
        assertEquals("example.jar", result.getFirst().fileName());
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
                result.getFirst().sha1());
    }

    @Test
    void scansResourcePackArchivesWithoutIncludingUnrelatedFiles() throws Exception {
        Path packs = temporaryDirectory.resolve("resourcepacks");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("texture.ZIP"), "hello");
        Files.writeString(packs.resolve("readme.txt"), "ignored");
        Files.writeString(packs.resolve("not-a-pack.jar"), "ignored");
        var result = new ModDirectoryScanner().scanResourcePacks(packs);
        assertEquals(1, result.size());
        assertEquals("texture.ZIP", result.getFirst().fileName());
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
                result.getFirst().sha1());
    }

    @Test
    void usesIsolatedInstanceDirectories() {
        var layout = new InstanceLayout(temporaryDirectory.resolve(".minecraft"));
        assertEquals(temporaryDirectory.resolve(
                ".minecraft/leverage/instances/survival/26.3/mods")
                .toAbsolutePath().normalize(), layout.mods("Survival", "26.3"));
    }
}

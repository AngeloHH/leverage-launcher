package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalVersionCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsOfflineCatalogFromCompleteLocalVersionsAndInheritedProfiles(
            ) throws Exception {
        Path minecraft = temporaryDirectory.resolve(".minecraft");
        Path versions = minecraft.resolve("versions");
        Path vanilla = versions.resolve("1.21.4");
        Files.createDirectories(vanilla);
        Files.writeString(vanilla.resolve("1.21.4.json"),
        """
                {"id":"1.21.4","type":"release","releaseTime":"2024-12-03T10:00:00Z"}
                """
        );
        Files.writeString(vanilla.resolve("1.21.4.jar"), "client");

        Path fabric = versions.resolve("fabric-loader-0.16.10-1.21.4");
        Files.createDirectories(fabric);
        Files.writeString(fabric.resolve("fabric-loader-0.16.10-1.21.4.json"),
        """
                {"id":"fabric-loader-0.16.10-1.21.4","type":"release",
                 "inheritsFrom":"1.21.4","releaseTime":"2025-01-01T10:00:00Z"}
                """
        );

        Path incomplete = versions.resolve("1.20.1");
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve("1.20.1.json"), "{\"id\":\"1.20.1\"}");
        Path broken = versions.resolve("broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("broken.json"), "not-json");

        var result = new LocalVersionCatalog(minecraft).scan();

        assertEquals(2, result.versions().size());
        assertEquals("fabric-loader-0.16.10-1.21.4", result.versions().get(0).id());
        assertEquals("1.21.4", result.versions().get(1).id());
        assertEquals(vanilla.resolve("1.21.4.jar").toAbsolutePath().normalize(),
                result.versions().get(0).clientJar());
        assertEquals(2, result.ignoredEntries());
    }

    @Test
    void returnsAnEmptyCatalogWhenMinecraftHasNoVersionsDirectory() throws Exception {
        var result = new LocalVersionCatalog(temporaryDirectory.resolve(
                "missing")).scan();
        assertTrue(result.versions().isEmpty());
        assertEquals(0, result.ignoredEntries());
    }
}

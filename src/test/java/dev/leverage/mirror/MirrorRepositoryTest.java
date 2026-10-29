package dev.leverage.mirror;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesContentAndResolvesOnlyRegisteredRoutes() throws Exception {
        MirrorRepository repository = new MirrorRepository(
                temporaryDirectory.resolve("mirror"));
        Path source = temporaryDirectory.resolve("source.jar");
        Files.writeString(source, "library-content");
        String hash = MirrorRepository.sha1(source);
        Path blob = repository.blobPath(hash);
        Files.createDirectories(blob.getParent());
        Files.copy(source, blob);

        assertTrue(repository.isValidBlob(hash, Files.size(source)));
        repository.registerRoute("/mirror/libraries/example.jar",
                hash, Files.size(source),
                "application/java-archive", "example.jar",
                "https://example.test/example.jar");

        assertNotNull(repository.resolve("/mirror/libraries/example.jar"));
        assertNull(repository.resolve("/mirror/storage/sha1/" + hash));

        Files.writeString(blob, "corrupted");
        assertFalse(repository.isValidBlob(hash, Files.size(source)));
    }

    @Test
    void resolvesOnlyExpectedVersionManifests() throws Exception {
        MirrorRepository repository = new MirrorRepository(
                temporaryDirectory.resolve("mirror"));
        Path manifest = repository.versionsDirectory().resolve(
                "26.3").resolve("version.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{}");

        var resolved = repository.resolveManifest("/mirror/versions/26.3/version.json");
        assertNotNull(resolved);
        assertEquals(manifest, resolved.file());
        assertNull(repository.resolveManifest("/mirror/versions/26.3/private.json"));

        Path javaManifest = repository.versionsDirectory().resolve(
                "java").resolve("windows-x64")
                .resolve("java-runtime-epsilon").resolve("manifest.json");
        Files.createDirectories(javaManifest.getParent());
        Files.writeString(javaManifest, "{}");
        assertNotNull(repository.resolveManifest(
                "/mirror/versions/java/windows-x64/java-runtime-epsilon/manif"
                + "est.json"));
        assertNull(repository.resolveManifest(
                "/mirror/versions/java/windows-x64/java-runtime-epsilon/priva"
                + "te.json"));

        Files.writeString(repository.versionsDirectory().resolve(
                "version_manifest.json"), "{}");
        Files.writeString(repository.versionsDirectory().resolve(
                "version_manifest_v2.json"), "{}");
        assertNotNull(repository.resolveManifest(
                "/mirror/versions/version_manifest.json"));
        assertNotNull(repository.resolveManifest(
                "/mirror/versions/version_manifest_v2.json"));
        assertNotNull(repository.resolveManifest("/game/version_manifest.json"));
        assertNotNull(repository.resolveManifest("/game/version_manifest_v2.json"));
        assertNull(repository.resolveManifest("/game/private.json"));
    }
}

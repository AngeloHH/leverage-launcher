package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ManagedInstanceLayoutTest {
    @TempDir Path directory;

    @Test void createsAndReusesInstanceWithoutMovingSharedMods() throws Exception {
        Path shared = Files.createDirectories(directory.resolve(
                "mods")).resolve("shared.jar");
        Files.writeString(shared, "not part of an isolated instance");
        InstanceLayout layout = new InstanceLayout(directory);
        Path instance = layout.prepare(null, "26.3");
        assertEquals(directory.resolve("leverage/instances/offline/26.3"), instance);
        assertTrue(Files.isDirectory(instance.resolve("mods")));
        assertTrue(Files.isDirectory(instance.resolve("resourcepacks")));
        Path mod = instance.resolve("mods/example.jar");
        Files.writeString(mod, "installed");
        assertEquals(instance, layout.prepare(null, "26.3"));
        assertEquals("installed", Files.readString(mod));
        assertTrue(Files.exists(shared));
        assertFalse(Files.exists(instance.resolve("mods/shared.jar")));
    }

    @Test void hashesCanonicalOriginsAndKeepsVersionsAndLoaderProfilesSeparate(
            ) throws Exception {
        InstanceLayout layout = new InstanceLayout(directory);
        URI server = URI.create("http://LOCALHOST:80/");
        Path base = layout.prepare(server, "1.21.4");
        assertEquals(base, layout.prepare(URI.create("http://localhost"), "1.21.4"));
        assertNotEquals(base, layout.prepare(null, "1.21.4"));
        assertNotEquals(base, layout.prepare(URI.create("https://localhost"), "1.21.4"));
        assertNotEquals(base, layout.prepare(URI.create(
                "http://localhost:8000"), "1.21.4"));
        assertNotEquals(base, layout.prepare(server, "fabric-loader-0.16.10-1.21.4"));
        assertNotEquals(layout.prepare(server, "Custom+1"),
                layout.prepare(server, "custom+1"));
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-1")
                .digest("http://localhost/".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, base.getParent().getFileName().toString());
        assertEquals(expected, InstanceLayout.serverScope(server));
        assertTrue(expected.matches("[a-f0-9]{40}"));
        assertEquals("1.21.4", base.getFileName().toString());
    }

    @Test void rejectsTraversalAndNonDirectoryComponents() throws Exception {
        InstanceLayout layout = new InstanceLayout(directory);
        assertThrows(IllegalArgumentException.class,
                () -> layout.prepare(null, "../escape"));
        Files.createDirectories(directory.resolve("leverage"));
        Files.writeString(directory.resolve("leverage/instances"), "not a directory");
        assertThrows(java.io.IOException.class, () -> layout.prepare(null, "26.3"));
        assertFalse(Files.exists(directory.resolve("escape")));
    }

    @Test void remembersVersionPerOriginAndPreservesExistingPreferences(
            ) throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(directory);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        preferences.saveRememberDevice(false);
        preferences.saveSelectedVersion("offline", "26.3");
        preferences.saveSelectedVersion("https://example.test/", "fabric-1.21.4");
        LauncherPreferences reopened = new LauncherPreferences(directory);
        assertEquals("26.3", reopened.loadSelectedVersion("offline").orElseThrow());
        assertEquals("fabric-1.21.4", reopened.loadSelectedVersion(
                "https://example.test/").orElseThrow());
        assertTrue(reopened.loadSelectedVersion("https://other.test/").isEmpty());
        assertTrue(reopened.hasDownloadSource());
        assertFalse(reopened.loadRememberDevice());
        assertEquals(directory, reopened.minecraftDirectory());
        String json = Files.readString(directory.resolve(
                "leverage/launcher-preferences.json"));
        assertFalse(json.contains("https://example.test/"));
        assertTrue(json.contains(InstanceLayout.serverScope(
                URI.create("https://example.test/"))));
    }

    @Test void migratesAllVersionsAndTheirFilesByRenamingOldServerFolder(
            ) throws Exception {
        Path old = directory.resolve("leverage/instances/http--127.0.0.1--8000");
        Path mods = Files.createDirectories(old.resolve("26.3/mods"));
        Files.writeString(mods.resolve("example.jar"), "mod contents");
        Files.createDirectories(old.resolve("1.6.2/saves"));
        Files.writeString(old.resolve("1.6.2/saves/world.dat"), "world contents");
        Files.writeString(old.resolve("26.3/options.txt"), "settings");
        URI server = URI.create("http://127.0.0.1:8000/");
        InstanceLayout layout = new InstanceLayout(directory);
        Path instance = layout.prepare(server, "26.3");
        assertFalse(Files.exists(old));
        assertEquals("mod contents", Files.readString(
                instance.resolve("mods/example.jar")));
        assertEquals("settings", Files.readString(instance.resolve("options.txt")));
        assertEquals("world contents", Files.readString(instance.getParent(
                ).resolve("1.6.2/saves/world.dat")));
        assertEquals(instance, layout.prepare(server, "26.3"));
        assertTrue(instance.getParent().getFileName().toString().matches("[a-f0-9]{40}"));
    }

    @Test void refusesToMergeOrOverwriteWhenBothServerFoldersExist() throws Exception {
        URI server = URI.create("https://example.test");
        InstanceLayout layout = new InstanceLayout(directory);
        Path target = layout.prepare(server, "26.3");
        Files.writeString(target.resolve("mods/new.jar"), "new");
        Path old = Files.createDirectories(directory.resolve(
                "leverage/instances/https--example.test--443/26.3/mods"));
        Files.writeString(old.resolve("old.jar"), "old");
        assertThrows(java.io.IOException.class, () -> layout.prepare(server, "26.3"));
        assertEquals("old", Files.readString(old.resolve("old.jar")));
        assertEquals("new", Files.readString(target.resolve("mods/new.jar")));
        assertFalse(Files.exists(target.resolve("mods/old.jar")));
    }

    @Test void readsOldUrlKeyedPreferencesThenWritesHashKeyWithoutLosingChoices(
            ) throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(directory);
        Path file = directory.resolve("leverage/launcher-preferences.json");
        Files.writeString(file,
        """
                {"schemaVersion":1,"rememberDevice":false,"selectedVersions":{
                  "http://localhost/":"fabric-1.21.4","offline":"26.3"}}
                """
        );
        assertEquals("fabric-1.21.4", preferences.loadSelectedVersion(
                "HTTP://LOCALHOST:80/").orElseThrow());
        preferences.saveSelectedVersion("http://localhost/", "fabric-1.21.4");
        String json = Files.readString(file);
        assertFalse(json.contains("http://localhost/"));
        assertTrue(json.contains(InstanceLayout.serverScope(
                URI.create("http://localhost"))));
        assertEquals("fabric-1.21.4", new LauncherPreferences(directory)
                .loadSelectedVersion("http://localhost/").orElseThrow());
        assertEquals("26.3", preferences.loadSelectedVersion("offline").orElseThrow());
        assertFalse(preferences.loadRememberDevice());
    }

    @Test void doesNotRenameARegularFileMasqueradingAsAnOldServerFolder(
            ) throws Exception {
        Path old = directory.resolve("leverage/instances/http--localhost--80");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "keep this file");
        InstanceLayout layout = new InstanceLayout(directory);
        assertThrows(java.io.IOException.class, () -> layout.prepare(
                URI.create("http://localhost"), "26.3"));
        assertEquals("keep this file", Files.readString(old));
        assertFalse(Files.exists(layout.managedInstance(URI.create(
                "http://localhost"), "26.3").getParent()));
    }
}

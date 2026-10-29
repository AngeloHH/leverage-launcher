package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherPreferencesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsUntilAnExternalSourceIsSaved() throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(
                temporaryDirectory.resolve(".minecraft"));
        assertTrue(preferences.loadDownloadSource().useDefaults());
        assertFalse(preferences.hasDownloadSource());

        URI source = URI.create("https://downloads.example.test/leverage");
        preferences.saveDownloadSource(
                LauncherPreferences.DownloadSource.external(source));

        LauncherPreferences.DownloadSource loaded = preferences.loadDownloadSource();
        assertFalse(loaded.useDefaults());
        assertEquals(source, loaded.baseUrl());
        assertTrue(preferences.hasDownloadSource());
    }

    @Test
    void preservesServerSourcesAndRememberChoiceAcrossRestartsAndUpdates(
            ) throws Exception {
        Path root = temporaryDirectory.resolve("persisted");
        LauncherPreferences preferences = new LauncherPreferences(root);
        URI host = URI.create("https://leverage.example.test/");
        URI mirror = URI.create("https://mirror.example.test/files");
        preferences.saveServerUrl(host);
        assertFalse(preferences.hasDownloadSource());
        preferences.saveDownloadSource(
                LauncherPreferences.DownloadSource.external(mirror));
        preferences.saveRememberDevice(false);

        LauncherPreferences reopened = new LauncherPreferences(root);
        assertEquals(URI.create("https://leverage.example.test"),
                reopened.loadServerUrl().orElseThrow());
        assertEquals(mirror, reopened.loadDownloadSource().baseUrl());
        assertFalse(reopened.loadRememberDevice());
        reopened.saveServerUrl(URI.create("http://127.0.0.1:8000"));
        assertEquals(mirror, reopened.loadDownloadSource().baseUrl());
        reopened.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        assertTrue(new LauncherPreferences(root).hasDownloadSource());
        assertTrue(reopened.loadDownloadSource().useDefaults());
        assertEquals(URI.create("http://127.0.0.1:8000"),
                reopened.loadServerUrl().orElseThrow());
    }

    @Test
    void recognizesPreviouslySavedDefaultAndExternalSourcesWithoutRepeatingSetup(
            ) throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(temporaryDirectory);
        Path file = temporaryDirectory.resolve("leverage/launcher-preferences.json");
        Files.writeString(file,
        """
                {"schemaVersion":1,"downloadSource":{"useDefaults":true}}
                """
        );
        assertTrue(preferences.hasDownloadSource());
        assertTrue(preferences.loadDownloadSource().useDefaults());
        assertTrue(preferences.loadRememberDevice());
        assertTrue(preferences.loadServerUrl().isEmpty());
        Files.writeString(file,
        """
                {"schemaVersion":1,"downloadSource":{"useDefaults":false,"baseUrl":"http\
                s://mirror.example.test"}}
                """
        );
        preferences.saveServerUrl(URI.create("https://leverage.example.test"));
        assertEquals(URI.create("https://mirror.example.test"),
                preferences.loadDownloadSource().baseUrl());
    }

    @Test
    void reportsCorruptPreferencesWithoutOverwritingThem() throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(temporaryDirectory);
        Path file = temporaryDirectory.resolve("leverage/launcher-preferences.json");
        Files.writeString(file, "broken-json");
        assertThrows(java.io.IOException.class, preferences::loadServerUrl);
        assertThrows(java.io.IOException.class,
                () -> preferences.saveRememberDevice(true));
        assertEquals("broken-json", Files.readString(file));
    }

    @Test
    void rejectsNonHttpDownloadSources() {
        assertThrows(IllegalArgumentException.class,
                () -> LauncherPreferences.validateBaseUrl("file:///tmp/mirror"));
    }

    @Test
    void storesIndependentJavaAndMinecraftSourcesAndMigratesTheOldSingleUrl(
            ) throws Exception {
        var preferences = new LauncherPreferences(temporaryDirectory);
        var legacy = LauncherPreferences.DownloadSource.external(
                URI.create("https://old.example.test/mirror"));
        preferences.saveDownloadSource(legacy);
        assertEquals(legacy, preferences.loadDownloadSources().javaRuntime());
        URI server = URI.create("http://localhost:8000");
        var sources = new LauncherPreferences.DownloadSources(
                LauncherPreferences.DownloadSource.external(URI.create(
                "https://minecraft.example.test/files")),
                LauncherPreferences.DownloadSource.external(URI.create(
                "https://java.example.test/runtimes")));
        preferences.saveServerConfiguration(server, sources);
        var reopened = new LauncherPreferences(temporaryDirectory);
        assertEquals(sources, reopened.loadDownloadSources());
        assertEquals(sources.versions(), reopened.loadDownloadSource());
        assertEquals(URI.create("http://localhost:8000"),
                reopened.loadServerUrl().orElseThrow());
        assertEquals(URI.create("http://example.test/"),
                LauncherPreferences.validateServerUrl("HTTP://EXAMPLE.TEST:80/"));
        assertEquals(URI.create("https://example.test/"),
                LauncherPreferences.validateServerUrl("https://example.test:443"));
        assertThrows(IllegalArgumentException.class,
                () -> LauncherPreferences.validateServerUrl("https://example.test/api"));
    }
}

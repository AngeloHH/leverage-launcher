package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModDirectoryWatcherTest {
    @TempDir Path directory;

    @Test
    void watchesOnlyJarsInItsInstanceAndCloseStopsRequests() throws Exception {
        var inventories = new LinkedBlockingQueue<List<String>> ();
        var errors = new LinkedBlockingQueue<Exception>();
        Path mods = directory.resolve("instance/mods");
        var watcher = new ModDirectoryWatcher(mods, new ModDirectoryScanner(), hashes -> {
            inventories.add(hashes);
            return approved();
        }, ignored -> { }, errors::add);
        try {
            assertEquals(List.of(), inventories.poll(5, TimeUnit.SECONDS));
            Files.writeString(mods.resolve("readme.txt"), "ignored");
            Path other = Files.createDirectories(directory.resolve("other/mods"));
            Files.writeString(other.resolve("ignored.jar"), "hello");
            assertNull(inventories.poll(700, TimeUnit.MILLISECONDS));
            Files.writeString(mods.resolve("example.JAR"), "hello");
            assertEquals(List.of("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"),
                    inventories.poll(5, TimeUnit.SECONDS));
            assertEquals("example.JAR", watcher.inventory().getFirst().fileName());
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            watcher.close();
        }
        Files.writeString(mods.resolve("after-close.jar"), "hello");
        assertNull(inventories.poll(700, TimeUnit.MILLISECONDS));
        watcher.close();
    }

    @Test
    void networkFailureIsReportedAndNextChangeCanBeChecked() throws Exception {
        var attempts = new AtomicInteger();
        var errors = new LinkedBlockingQueue<Exception>();
        var results = new LinkedBlockingQueue<LauncherModVerifier.Result>();
        try (var watcher = new ModDirectoryWatcher(directory,
                new ModDirectoryScanner(), hashes -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("Sin conexión");
            return approved();
        }, results::add, errors::add)) {
            assertInstanceOf(IOException.class, errors.poll(5, TimeUnit.SECONDS));
            assertTrue(results.isEmpty());
            Files.writeString(directory.resolve("example.jar"), "hello");
            assertEquals("APPROVED", results.poll(5, TimeUnit.SECONDS).status());
            assertEquals(1, watcher.inventory().size());
        }
    }

    @Test
    void removalOfWatchedDirectoryReportsFailureInsteadOfApproval() throws Exception {
        var errors = new LinkedBlockingQueue<Exception>();
        var results = new LinkedBlockingQueue<LauncherModVerifier.Result>();
        Path mods = directory.resolve("mods");
        try (var watcher = new ModDirectoryWatcher(mods,
                new ModDirectoryScanner(), hashes -> approved(),
                results::add, errors::add)) {
            assertNotNull(results.poll(5, TimeUnit.SECONDS));
            Files.delete(mods);
            assertInstanceOf(IOException.class, errors.poll(5, TimeUnit.SECONDS));
            assertTrue(results.isEmpty());
        }
    }

    private static LauncherModVerifier.Result approved() {
        return new LauncherModVerifier.Result("APPROVED", Map.of());
    }
}

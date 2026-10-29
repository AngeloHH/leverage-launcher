package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import dev.leverage.service.ModPolicyService;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// This instance observer never grants permission to start the game.
public final class ModDirectoryWatcher implements AutoCloseable {
    private final Path directory;
    private final Path resourcePacks;
    private final ModDirectoryScanner scanner;
    private final Check check;
    private final Consumer<LauncherModVerifier.Result> onResult;
    private final Consumer<Exception> onError;
    private final WatchService events;
    private final Thread worker;
    private final java.util.Map<Path, WatchKey> registered = new java.util.HashMap<>();
    private volatile boolean closed;
    private volatile List<ModPolicyService.ClientMod> inventory = List.of();

    ModDirectoryWatcher(Path directory, ModDirectoryScanner scanner,
            Check check, Consumer<LauncherModVerifier.Result> onResult,
            Consumer<Exception> onError) throws IOException {
        this(directory, null, scanner, check, onResult, onError);
    }

    ModDirectoryWatcher(Path directory, Path resourcePacks, ModDirectoryScanner scanner,
            Check check, Consumer<LauncherModVerifier.Result> onResult,
            Consumer<Exception> onError) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.resourcePacks = resourcePacks == null ? null
                : resourcePacks.toAbsolutePath().normalize();
        this.scanner = Objects.requireNonNull(scanner);
        this.check = Objects.requireNonNull(check);
        this.onResult = Objects.requireNonNull(onResult);
        this.onError = Objects.requireNonNull(onError);
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(t("watch.modsPath"));
        }
        events = this.directory.getFileSystem().newWatchService();
        try {
            registerTree(this.directory);
            if (this.resourcePacks != null) {
                Files.createDirectories(this.resourcePacks);
                if (!Files.isDirectory(this.resourcePacks, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException(t("watch.resourcesPath"));
                registerTree(this.resourcePacks);
            }
        } catch (IOException | RuntimeException exception) {
            events.close();
            throw exception;
        }
        worker = Thread.ofPlatform().daemon().name(
                "leverage-mod-watcher").unstarted(this::run);
        worker.start();
    }

    public List<ModPolicyService.ClientMod> inventory() { return inventory; }

    private void run() {
        try {
            refresh();
            while (!closed) {
                WatchKey key = events.poll(30, TimeUnit.SECONDS);
                if (key == null) {
                    refresh();
                    continue;
                }
                boolean relevant = drain(key);
                // Coalesce a file copy's writes/renames into one request.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!closed && System.nanoTime() < deadline) {
                    key = events.poll(300, TimeUnit.MILLISECONDS);
                    if (key == null) break;
                    relevant |= drain(key);
                }
                if (relevant && !closed) refresh();
            }
        } catch (ClosedWatchServiceException ignored) {
            if (!closed) report(new IOException(t("watch.closed")));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!closed) report(exception);
        } catch (IOException | RuntimeException exception) {
            if (!closed) report(exception);
        } finally {
            closed = true;
            try { events.close(); } catch (IOException ignored) { }
        }
    }

    private boolean drain(WatchKey key) throws IOException {
        boolean relevant = false;
        Path watched = (Path) key.watchable();
        for (var event : key.pollEvents()) {
            relevant = true;
            if (event.context() instanceof Path relative) {
                Path changed = watched.resolve(relative);
                if (Files.isDirectory(changed,
                        LinkOption.NOFOLLOW_LINKS)) registerTree(changed);
            }
        }
        if (!key.reset()) {
            registered.remove(watched, key);
            relevant = true;
            if (watched.equals(directory) || watched.equals(resourcePacks))
                    throw new IOException(t("watch.folderGone"));
        }
        return relevant;
    }

    private void registerTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException(t("watch.noLinks"));
        Path realRoot = root.toRealPath();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path folder, java.nio.file.attribute
                    .BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(folder) || !folder.toRealPath().startsWith(
                        realRoot)) throw new IOException(t("watch.noLinks"));
                WatchKey previous = registered.get(folder);
                if (previous == null || !previous.isValid()) registered.put(folder,
                        folder.register(events, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFileFailed(Path file,
                    IOException error) throws IOException {
                throw error;
            }
        });
    }

    private void refresh() throws InterruptedException {
        try {
            // Full snapshots catch deletions and recover from OVERFLOW.
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || resourcePacks != null && !Files.isDirectory(
                    resourcePacks, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(t("watch.foldersGone"));
            var current = new java.util.ArrayList<>(scanner.scan(directory));
            if (resourcePacks != null) current.addAll(
                    scanner.scanResourcePacks(resourcePacks));
            inventory = List.copyOf(current);
            // Also recover registrations after an OVERFLOW or a newly copied tree.
            registerTree(directory);
            if (resourcePacks != null) registerTree(resourcePacks);
            var result = check.verify(inventory.stream().map(
                    ModPolicyService.ClientMod::sha1).toList());
            if (!closed) onResult.accept(result);
            if (result.status().equals("SUSPENDED")) close();
        } catch (IOException | RuntimeException exception) {
            if (!closed) report(exception);
        }
    }

    private void report(Exception exception) {
        try { onError.accept(exception); } catch (RuntimeException ignored) { }
    }

    @Override public void close() throws IOException {
        closed = true;
        events.close();
        worker.interrupt();
    }

    @FunctionalInterface interface Check {
        LauncherModVerifier.Result verify(List<String> hashes) throws IOException,
                InterruptedException;
    }
}

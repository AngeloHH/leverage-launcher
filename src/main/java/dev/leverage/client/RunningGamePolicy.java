package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import dev.leverage.launch.GameLaunchService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// Bind server artifact policy to one running game and its directories.
public final class RunningGamePolicy implements AutoCloseable {
    private final URI host;
    private final String token, scope, version, loader;
    private final Path instance;
    private final Consumer<String> onDenied;
    private final LauncherModVerifier verifier = new LauncherModVerifier();
    private final ModDirectoryScanner scanner = new ModDirectoryScanner();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicReference<GameLaunchService
            .RunningGame> game = new AtomicReference<>();
    private volatile ModDirectoryWatcher watcher;

    public RunningGamePolicy(URI host, String token, Path instance, String scope,
            String version, String loader, Consumer<String> onDenied) throws IOException {
        this.host = host;
        this.token = token;
        this.instance = instance.toAbsolutePath().normalize();
        this.scope = scope;
        this.version = version;
        this.loader = loader;
        this.onDenied = onDenied;
        watcher = verifier.watchGameDirectory(host, token, this.instance,
                scope, version, loader, result -> {
                    if (!result.approved()) deny(result.status().equals("SUSPENDED")
                    ? t("policy.suspended") : t("policy.changed"));
        }, error -> deny(I18n.t("policy.monitorFailed", error.getMessage())));
        // An early callback can fail before the watcher reference is assigned.
        if (closed.get()) closeWatcher();
    }

    public void attach(GameLaunchService.RunningGame running) {
        game.set(running);
        if (closed.get()) running.cancel();
        running.onExit().whenComplete((code, error) -> close());
    }

    public boolean failed() { return failure.get() != null; }

    public void requireActive() throws IOException {
        String reason = failure.get();
        if (reason != null) throw new IOException(reason);
        if (closed.get()) throw new IOException(t("policy.notActive"));
    }

    // Validate imports before Minecraft can reload newly copied files.
    public void checkImport(Path destination, List<Path> files) throws IOException,
            InterruptedException {
        Path target = destination.toAbsolutePath().normalize();
        if (!target.equals(instance.resolve("mods")) && !target.equals(
                instance.resolve("resourcepacks"))) return;
        requireActive();
        var hashes = new java.util.ArrayList<>(
                scanner.scanGameInstance(instance).stream().map(
                dev.leverage.service.ModPolicyService.ClientMod::sha1).toList());
        boolean resources = target.equals(instance.resolve("resourcepacks"));
        for (Path file : files) hashes.add(resources ? ModDirectoryScanner.resourcePack(
                file).sha1() : scanner.scanFile(file).sha1());
        var result = verifier.verifyHashes(host, token, scope, version,
                loader, hashes.stream().distinct().toList());
        if (!result.approved()) {
            if (result.status().equals("SUSPENDED")) deny(t("policy.suspended"));
            throw new IOException(t("policy.importRejected"));
        }
        requireActive();
    }

    private void deny(String reason) {
        if (closed.get() || !failure.compareAndSet(null, reason)) return;
        stop();
        onDenied.accept(GameLaunchService.redact(reason, token));
    }

    public void stop() {
        close();
        var running = game.get();
        if (running != null) running.cancel();
    }

    @Override public void close() {
        closed.set(true);
        closeWatcher();
    }

    private void closeWatcher() {
        var current = watcher;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}

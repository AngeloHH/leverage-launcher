package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.*;
import dev.leverage.launch.*;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

// Real asynchronous install / launch workflow. The UI never receives or displays a game
// token.
final class GameWorkflow {
    record Account(URI host, LauncherAuthClient.Session session) { }
    private final LauncherHomePanel home;
    private final LauncherPreferences preferences;
    private final Supplier<Account> account;
    private final GameLaunchService launcher = new GameLaunchService();
    private CancellationToken cancellation;
    private GameLaunchService.RunningGame running;
    private volatile RunningGamePolicy activePolicy;
    private boolean busy;

    GameWorkflow(LauncherHomePanel home, LauncherPreferences preferences,
            Supplier<Account> account) {
        this.home = home;
        this.preferences = preferences;
        this.account = account;
        home.onPlay(this::play);
        home.onInstall(this::chooseVersion);
        ModListPanel.ImportCheck check = (destination, files) -> {
            var policy = activePolicy;
            if (policy != null) policy.checkImport(destination, files);
        };
        home.mods().onImportCheck(check);
        home.resourcepacks().onImportCheck(check);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var policy = activePolicy;
            if (policy != null) policy.stop();
        }, "leverage-game-policy-stop"));
    }

    private void play() {
        if (GraphicsEnvironment.isHeadless()) {
            home.showNotice(t("game.noWindow"));
            return;
        }
        if (busy || running != null && running.isAlive()) return;
        String version = home.selectedVersion();
        if (version == null) {
            chooseVersion();
            return;
        }
        install(version, true);
    }

    private void chooseVersion() {
        if (GraphicsEnvironment.isHeadless()) return;
        if (busy) return;
        Window owner = SwingUtilities.getWindowAncestor(home);
        JDialog dialog = new JDialog(owner, t("game.installTitle"),
                Dialog.ModalityType.MODELESS);
        JPanel panel = new JPanel(new BorderLayout(12, 16));
        panel.setBackground(JourneyStyle.INK);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(JourneyStyle.label(t("game.chooseVersion"), 26,
                JourneyStyle.PAPER, true), BorderLayout.NORTH);
        JPanel footer = new JPanel(new BorderLayout(12, 12));
        footer.setOpaque(false);
        PixelButton install = new PixelButton(t("game.install"),
                PixelButton.Variant.PRIMARY);
        install.setEnabled(false);
        footer.add(install, BorderLayout.EAST);
        VersionSearchPanel search = new VersionSearchPanel(
                selected -> install.setEnabled(selected != null));
        panel.add(search, BorderLayout.CENTER);
        JButton refresh = JourneyStyle.link(t("game.refreshLocal"),
                () -> home.refreshVersions());
        footer.add(refresh, BorderLayout.WEST);
        panel.add(footer, BorderLayout.SOUTH);
        CancellationToken token = new CancellationToken();
        install.addActionListener(e -> {
            var selected = search.selected();
            if (selected != null) {
                dialog.dispose();
                install(selected.id(), false);
            }
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                token.cancel();
                search.close();
            }
        });
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(560, 560);
        dialog.setLocationRelativeTo(home);
        dialog.setVisible(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return new GameInstaller().catalog(preferences.loadDownloadSources(),
                        token);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((versions, failure) -> SwingUtilities.invokeLater(() -> {
                if (!dialog.isDisplayable()) return;
                if (failure != null) {
                    search.loadFailed();
                    home.showNotice(message(failure), new Color(0xd4441a));
                    return;
            }
                search.setCatalog(versions);
        }));
    }

    private void install(String version, boolean start) {
        if (busy) return;
        Account requested = account.get();
        LauncherPreferences.DownloadSources sources;
        try {
            sources = preferences.loadDownloadSources();
        } catch (Exception failure) {
            home.showNotice(message(failure), new Color(0xd4441a));
            return;
        }
        busy = true;
        cancellation = new CancellationToken();
        CancellationToken token = cancellation;
        home.setPlayState("ESPERA…", false);
        JDialog progress = new JDialog(SwingUtilities.getWindowAncestor(home),
                start ? t("game.prepareTitle") : t("game.installTitle"),
                Dialog.ModalityType.MODELESS);
        JPanel panel = new JPanel(new BorderLayout(12, 18));
        panel.setBackground(JourneyStyle.INK);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(JourneyStyle.label("MINECRAFT " + version, 24,
                JourneyStyle.PAPER, true), BorderLayout.NORTH);
        JLabel status = JourneyStyle.label(t("game.checkingFiles"),
                13, JourneyStyle.PAPER, false);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(status, BorderLayout.NORTH);
        center.add(bar, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        PixelButton cancel = new PixelButton(t("common.cancelUpper"),
                PixelButton.Variant.NEUTRAL);
        cancel.addActionListener(e -> {
            token.cancel();
            cancel.setEnabled(false);
            status.setText("Cancelando…");
        });
        panel.add(cancel, BorderLayout.SOUTH);
        progress.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                token.cancel();
                cancel.setEnabled(false);
                status.setText("Cancelando…");
            }
        });
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progress.setContentPane(panel);
        progress.setSize(560, 235);
        progress.setLocationRelativeTo(home);
        progress.setVisible(true);
        AtomicLong last = new AtomicLong();
        ProgressListener listener = (text, completed, total) -> {
            long now = System.nanoTime(), previous = last.get();
            if (now - previous < 120_000_000L || !last.compareAndSet(previous,
                    now)) return;
            SwingUtilities.invokeLater(() -> {
                if (!progress.isDisplayable()) return;
                status.setText(text);
                status.setToolTipText(text);
                bar.setIndeterminate(total <= 0);
                if (total > 0) bar.setValue((int) Math.min(100, completed * 100 / total));
            });
        };
        AtomicReference<RunningGamePolicy> launchPolicy = new AtomicReference<>();
        CompletableFuture.supplyAsync(() -> {
            try {
                Path root = preferences.minecraftDirectory();
                AtomicReference<JavaRuntimeInstaller.Installation> runtime
                        = new AtomicReference<>();
                var installed = new GameInstaller().install(version, root,
                        sources, listener, token, (component, major) -> {
                    var selected = new JavaRuntimeInstaller().ensureInstallation(
                            component, major, root, sources.javaRuntime(),
                            listener, token);
                    runtime.set(selected);
                    return selected.environment();
                });
                Path java = runtime.get().executable();
                preferences.saveSelectedVersion(requested.host() == null
                        ? "offline" : requested.host().toString(), version);
                if (!start) return null;
                token.throwIfCancelled();
                Path game = new InstanceLayout(root).prepare(requested.host(), version);
                List<String> agent = List.of();
                String username = "Jugador", access = "0";
                UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:Jugador".getBytes(
                        StandardCharsets.UTF_8));
                if (requested.host() != null && requested.session() != null) {
                    listener.onProgress(t("game.checkingPermission"), 0, -1);
                    if (!LauncherUpdateClient.runningFromClasses()) {
                        var compatibility
                                = new LauncherUpdateClient().checkRunningLauncher(
                                requested.host(), "stable");
                        if (!compatibility.canContinue())
                                throw new java.io.IOException(compatibility.message());
                    }
                    var scanner = new ModDirectoryScanner();
                    var hashes = scanner.scanGameInstance(game).stream().map(
                            dev.leverage.service.ModPolicyService
                            .ClientMod::sha1).distinct().toList();
                    var identity = GameInstaller.identity(installed.descriptor());
                    var verified = new LauncherModVerifier().verifyHashes(
                            requested.host(), requested.session().accessToken(),
                            InstanceLayout.serverScope(requested.host()),
                            identity.minecraftVersion(), identity.loader(), hashes);
                    if (!verified.approved()) throw new java.io.IOException(t(
                            "game.inventoryRejected", verified.status()));
                    var policy = new RunningGamePolicy(requested.host(),
                            requested.session().accessToken(), game,
                            InstanceLayout.serverScope(requested.host()),
                            identity.minecraftVersion(), identity.loader(),
                            reason -> SwingUtilities.invokeLater(() -> home.showNotice(
                            reason, new Color(0xd4441a))));
                    launchPolicy.set(policy);
                    activePolicy = policy;
                    username = requested.session().username();
                    uuid = requested.session().userId();
                    access = requested.session().accessToken();
                    var manager = new AuthlibInjectorManager(root.resolve(
                            "leverage/agents/authlib-injector-1.2.8.jar"),
                            AuthlibInjectorManager.OFFICIAL_URL,
                            AuthlibInjectorManager.OFFICIAL_SHA256);
                    manager.ensureInstalled();
                    HttpRequest metadata = HttpRequest.newBuilder(
                            AuthlibInjectorManager.apiRoot(requested.host())).timeout(
                            Duration.ofSeconds(20)).GET().build();
                    var response = HttpClient.newHttpClient().send(metadata,
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) throw new java.io.IOException(
                            t("game.skinMetadataError"));
                    agent = manager.jvmArguments(requested.host(), response.body(),
                            AuthlibInjectorManager.Side.CLIENT);
                }
                token.throwIfCancelled();
                var plan = launcher.prepare(new GameLaunchService.LaunchRequest(root,
                        game, version, java, username, uuid, access,
                        agent, preferences.loadGameMemory()),
                        runtime.get().environment());
                final boolean[] valid = { false };
                SwingUtilities.invokeAndWait(() -> valid[0]
                        = Objects.equals(requested, account.get()));
                if (!valid[0] || token.isCancelled()) {
                    plan.close();
                    throw new java.io.InterruptedIOException(t("game.sessionChanged"));
                }
                try {
                    var policy = launchPolicy.get();
                    if (policy != null) policy.requireActive();
                    var gameProcess = launcher.launch(plan,
                            new GameLaunchService.Listener() { });
                    if (policy != null) policy.attach(gameProcess);
                    return gameProcess;
                } catch (Exception failure) {
                    plan.close();
                    throw failure;
                }
            } catch (Exception failure) {
                var policy = launchPolicy.get();
                if (policy != null) {
                    policy.stop();
                    if (activePolicy == policy) activePolicy = null;
                }
                if (failure instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(failure);
            }
        }).whenComplete((game, failure) -> SwingUtilities.invokeLater(() -> {
            busy = false;
            progress.dispose();
            cancellation = null;
            if (failure != null) {
                home.setPlayState(t("game.play"), true);
                if (!token.isCancelled() && (launchPolicy.get() == null
                        || !launchPolicy.get().failed())) {
                    String reason = GameLaunchService.redact(message(failure),
                            requested.session() == null ? null
                            : requested.session().accessToken());
                    System.err.println("[Leverage/game] " + reason);
                    home.showNotice(reason, new Color(0xd4441a));
                } return; }
            home.refreshVersions();
            if (game == null) {
                boolean playing = running != null && running.isAlive();
                home.setPlayState(playing ? t("game.playing") : t("game.play"), !playing);
                home.showNotice(t("game.installed", version));
                return;
            }
            running = game;
            home.setPlayState(t("game.playing"), false);
            game.onExit().whenComplete((code,
                    exitFailure) -> SwingUtilities.invokeLater(() -> {
                var policy = launchPolicy.get();
                if (policy != null) {
                    policy.close();
                    if (activePolicy == policy) activePolicy = null;
                }
                if (running != game) return;
                running = null;
                home.setPlayState(t("game.play"), true);
                if ((exitFailure != null || code != 0) && (policy == null
                        || !policy.failed())) home.showNotice(t("game.crashed",
                        game.logPath().toString()), new Color(0xd4441a));
            }));
        }));
    }
    private static String message(Throwable failure) {
        // Keep meaningful installer/runtime errors (including Rosetta guidance), not just
        // their OS cause.
        while ((failure instanceof CompletionException
                || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return Objects.requireNonNullElse(failure.getMessage(),
                t("common.operationError"));
    }
}

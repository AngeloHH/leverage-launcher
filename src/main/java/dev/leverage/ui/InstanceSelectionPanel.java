package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.InstanceLayout;
import dev.leverage.client.LauncherPreferences;
import dev.leverage.client.LocalVersionCatalog;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

// Local version choice and automatic instance provisioning. Never asks the user to create
// folders.
final class InstanceSelectionPanel extends JPanel {
    private final PixelDropdown<String> versions = new PixelDropdown<>(List.of());
    private final java.util.ArrayList<PixelDropdown<String>> selectors
            = new java.util.ArrayList<>();
    private final java.util.ArrayList<Runnable> listeners = new java.util.ArrayList<>();
    private final PixelButton refresh = new PixelButton(t("common.refresh"),
            PixelButton.Variant.NEUTRAL);
    private final JLabel status = new JLabel(t("versions.noneSelected"));
    private LauncherPreferences preferences;
    private Consumer<Path> onInstance = path -> { };
    private Consumer<String> onError = message -> { };
    private Runnable installAction = () -> { };
    private URI server;
    private boolean initialized;
    private boolean updating;
    private long generation;
    private Path gameDirectory;
    private List<LocalVersionCatalog.InstalledVersion> installed = List.of();
    private List<String> versionIds = List.of();
    private String selectedVersion;
    private boolean preview;

    InstanceSelectionPanel() {
        setLayout(new BorderLayout(0, 6));
        setOpaque(false);
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        versions.setName("installed-version");
        selectors.add(versions);
        versions.setFont(LauncherFonts.minecraftTen(16f));
        versions.getAccessibleContext().setAccessibleName(t(
                "versions.installedAccessible"));
        versions.setEnabled(false);
        refresh.setFont(LauncherFonts.minecraftTen(14f));
        refresh.setEnabled(false);
        row.add(versions, BorderLayout.CENTER);
        row.add(refresh, BorderLayout.EAST);
        status.setFont(LauncherFonts.minecraftSeven(11f));
        status.setForeground(new Color(107, 101, 98));
        add(row, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        versions.addActionListener(event -> {
            if (!updating && versions.getSelectedItem() != null) selectVersion(
                    (String) versions.getSelectedItem());
        });
        refresh.addActionListener(event -> reload());
    }

    void configure(LauncherPreferences preferences, Consumer<Path> onInstance) {
        this.preferences = Objects.requireNonNull(preferences);
        this.onInstance = Objects.requireNonNull(onInstance);
        preview = false;
    }

    void setServer(URI server) {
        URI normalized = server == null ? null
                : LauncherPreferences.validateServerUrl(server.toString());
        if (initialized && Objects.equals(this.server, normalized)) return;
        this.server = normalized;
        initialized = true;
        reload();
    }

    private void reload() {
        if (preferences == null) return;
        long id =++generation;
        URI requestedServer = server;
        clearInstance();
        installed = List.of();
        versionIds = List.of();
        selectedVersion = null;
        syncSelectors();
        refresh.setEnabled(false);
        status.setText(t("versions.finding"));
        fireChange();
        CompletableFuture.supplyAsync(() -> {
            try {
                var catalog = new LocalVersionCatalog(
                        preferences.minecraftDirectory()).scan();
                String selected = preferences.loadSelectedVersion(
                        preferenceScope(requestedServer)).orElse(null);
                return new Choice(catalog.versions(), selected);
            } catch (Exception exception) { throw new CompletionException(exception); }
        }).whenComplete((choice, failure) -> SwingUtilities.invokeLater(() -> {
            if (id != generation) return;
            refresh.setEnabled(true);
            if (failure != null) {
                error(failure);
                return;
            }
            installed = choice.versions();
            versionIds = installed.stream().map(
                    LocalVersionCatalog.InstalledVersion::id).toList();
            String selected = installed.stream().map(
                    LocalVersionCatalog.InstalledVersion::id).filter(
                    value -> value.equals(choice.selected())).findFirst().orElse(
                    installed.isEmpty() ? null : installed.getFirst().id());
            selectedVersion = selected;
            syncSelectors();
            if (selected == null) {
                status.setText(t("versions.noLocal"));
                status.setToolTipText(preferences.minecraftDirectory().resolve(
                        "versions").toString());
                fireChange();
            } else prepare(selected);
        }));
    }

    private void prepare(String version) {
        if (installed.stream().noneMatch(item -> item.id().equals(version))) return;
        long id =++generation;
        URI requestedServer = server;
        clearInstance();
        status.setText(t("versions.preparing"));
        fireChange();
        try {
            preferences.saveSelectedVersion(preferenceScope(requestedServer), version);
        } catch (Exception exception) {
            error(exception);
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return new InstanceLayout(preferences.minecraftDirectory()).prepare(
                        requestedServer, version);
            }
            catch (Exception exception) { throw new CompletionException(exception); }
        }).whenComplete((directory, failure) -> SwingUtilities.invokeLater(() -> {
            if (id != generation) return;
            if (failure != null) {
                error(failure);
                return;
            }
            gameDirectory = directory;
            status.setText(t("versions.ready", version));
            status.setToolTipText(directory.toString());
            onInstance.accept(directory);
            fireChange();
        }));
    }

    Path gameDirectory() { return gameDirectory; }

    // Additional views share this state; they never scan or prepare their own instance.
    PixelDropdown<String> newSelector() {
        PixelDropdown<String> selector = new PixelDropdown<>(List.of());
        selector.setFont(LauncherFonts.minecraftSeven(16));
        selector.setPlaceholder(t("versions.noneInstalled"));
        selector.getAccessibleContext().setAccessibleName(t(
                "versions.installedAccessible"));
        selectors.add(selector);
        syncSelectors();
        selector.addActionListener(event -> {
            if (!updating && selector.getSelectedItem() != null) selectVersion(
                    (String) selector.getSelectedItem());
        });
        return selector;
    }

    List<String> versionIds() { return List.copyOf(versionIds); }
    String selectedVersion() { return selectedVersion; }
    String statusText() { return status.getText(); }
    void setCompact(boolean compact) {
        refresh.setVisible(!compact);
        status.setVisible(!compact);
        revalidate();
    }
    void refreshCatalog() { reload(); }
    boolean canRefreshCatalog() { return preferences != null && refresh.isEnabled(); }
    void onError(Consumer<String> handler) { onError = Objects.requireNonNull(handler); }
    void onInstall(Runnable handler) { installAction = Objects.requireNonNull(handler); }
    void requestInstall() { installAction.run(); }
    void addChangeListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
    }
    void selectVersion(String version) {
        if (!versionIds.contains(version) || Objects.equals(selectedVersion, version)) {
            syncSelectors();
            return;
        }
        selectedVersion = version;
        syncSelectors();
        if (preview) {
            status.setText(t("versions.preview", version));
            fireChange();
        }
        else prepare(version);
    }
    // In-memory visual fixture; deliberately performs no filesystem or preference work.
    void setPreviewVersions(List<String> ids, String selected) {
        generation++;
        preview = true;
        gameDirectory = null;
        installed = List.of();
        versionIds = List.copyOf(ids);
        selectedVersion = versionIds.contains(selected) ? selected
                : versionIds.isEmpty() ? null : versionIds.getFirst();
        status.setText(selectedVersion == null ? t("versions.noInstalledPeriod")
                : t("versions.preview", selectedVersion));
        syncSelectors();
        fireChange();
    }
    private void syncSelectors() {
        updating = true;
        try { for (PixelDropdown<String> selector : selectors) {
                selector.removeAllItems();
                for (String id : versionIds) selector.addItem(id);
                selector.setSelectedItem(selectedVersion);
                selector.setEnabled(!versionIds.isEmpty());
            } } finally { updating = false; }
    }
    private void fireChange() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    private static String preferenceScope(URI server) {
        // Supply the canonical URL so older URL-keyed preferences can be migrated as
        // well.
        return server == null ? "offline" : LauncherPreferences.validateServerUrl(
                server.toString()).toString();
    }

    private void clearInstance() {
        gameDirectory = null;
        status.setToolTipText(null);
        onInstance.accept(null);
    }

    private void error(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        status.setText(t("versions.prepareError"));
        status.setToolTipText(failure.getMessage());
        onError.accept(t("error.instancePrepare", Objects.requireNonNullElse(
                failure.getMessage(), t("common.localError"))));
        fireChange();
    }

    private record Choice(List<LocalVersionCatalog.InstalledVersion> versions,
            String selected) { }
}

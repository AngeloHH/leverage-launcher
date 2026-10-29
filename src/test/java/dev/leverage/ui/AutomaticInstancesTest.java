package dev.leverage.ui;

import dev.leverage.client.InstanceLayout;
import dev.leverage.client.LauncherPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticInstancesTest {
    @TempDir Path directory;

    @Test void homeAutomaticallyCreatesBindsAndRemembersIsolatedVersionFolders(
            ) throws Exception {
        install("26.3", "2026-10-01T00:00:00Z");
        install("fabric-loader-0.16.10-1.21.4", "2025-01-01T00:00:00Z");
        LauncherPreferences preferences = new LauncherPreferences(directory);
        AtomicReference<LauncherHomePanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            LauncherHomePanel home = new LauncherHomePanel();
            home.configureInstances(preferences);
            home.setInstanceServer(null);
            ref.set(home);
        });
        LauncherHomePanel home = ref.get();
        InstanceLayout layout = new InstanceLayout(directory);
        Path offline = layout.managedInstance(null, "26.3").resolve("mods");
        await(() -> offline.equals(home.mods().directory()) && offline.getParent(
                ).resolve("resourcepacks").equals(home.resourcepacks().directory()));
        assertTrue(Files.isDirectory(offline));
        assertFalse(Files.exists(directory.resolve("mods")));
        SwingUtilities.invokeAndWait(() -> {
            home.showSection(LauncherHomePanel.Section.MODS);
            assertTrue(labelsContain(home.mods(), "No hay mods en esta carpeta."));
            home.showHome();
            find(home, JComboBox.class).setSelectedItem("fabric-loader-0.16.10-1.21.4");
        });
        Path fabric = layout.managedInstance(null,
                "fabric-loader-0.16.10-1.21.4").resolve("mods");
        await(() -> fabric.equals(home.mods().directory()) && fabric.getParent(
                ).resolve("resourcepacks").equals(home.resourcepacks().directory()));
        assertEquals("fabric-loader-0.16.10-1.21.4",
                preferences.loadSelectedVersion("offline").orElseThrow());

        URI a = URI.create("https://a.example.test");
        URI b = URI.create("https://b.example.test");
        SwingUtilities.invokeAndWait(() -> {
            home.setInstanceServer(a);
            home.setInstanceServer(b);
            // Late results for a must never replace b's directory.
        });
        Path online = layout.managedInstance(b, "26.3").resolve("mods");
        await(() -> online.equals(home.mods().directory()) && online.getParent(
                ).resolve("resourcepacks").equals(home.resourcepacks().directory()));
        assertNotEquals(fabric, online);

        AtomicReference<LauncherHomePanel> reopened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            LauncherHomePanel next = new LauncherHomePanel();
            next.configureInstances(preferences);
            next.setInstanceServer(null);
            reopened.set(next);
        });
        await(() -> fabric.equals(reopened.get().mods().directory()));
        SwingUtilities.invokeAndWait(() -> preview(home));
    }

    @Test void emptyCatalogNeverAsksForAManualFolderOrCreatesAnInventedInstance(
            ) throws Exception {
        LauncherPreferences preferences = new LauncherPreferences(directory);
        AtomicReference<LauncherHomePanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var home = new LauncherHomePanel();
            home.configureInstances(preferences);
            home.setInstanceServer(null);
            ref.set(home);
        });
        await(() -> labelsContain(ref.get(),
                "No hay versiones instaladas en .minecraft/versions."));
        assertFalse(Files.exists(directory.resolve("leverage/instances")));
        SwingUtilities.invokeAndWait(() -> {
            var home = ref.get();
            assertNull(home.mods().directory());
            home.showSection(LauncherHomePanel.Section.MODS);
            assertTrue(clickButton(home.mods(), "Cambiar versión"));
            assertEquals(LauncherHomePanel.Section.VERSIONS, home.selectedSection());
        });
    }

    @Test void restoresOldServerChoiceAndMigratesExistingModsToHashDirectory(
            ) throws Exception {
        install("26.3", "2026-10-01T00:00:00Z");
        install("fabric-1.21.4", "2025-01-01T00:00:00Z");
        LauncherPreferences preferences = new LauncherPreferences(directory);
        Files.writeString(directory.resolve("leverage/launcher-preferences.json"),
        """
                {"schemaVersion":1,"selectedVersions":{"http://localhost/":"fabric-1.21.\
                4"}}
                """
        );
        Path old = Files.createDirectories(directory.resolve(
                "leverage/instances/http--localhost--80/fabric-1.21.4/mods"));
        Files.writeString(old.resolve("example.jar"), "existing mod");
        URI server = URI.create("HTTP://LOCALHOST:80/");
        AtomicReference<LauncherHomePanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var home = new LauncherHomePanel();
            home.configureInstances(preferences);
            home.setInstanceServer(server);
            ref.set(home);
        });
        Path expected = new InstanceLayout(directory).managedInstance(server,
                "fabric-1.21.4").resolve("mods");
        await(() -> expected.equals(ref.get().mods().directory()));
        assertFalse(Files.exists(old));
        assertEquals("existing mod", Files.readString(expected.resolve("example.jar")));
        assertFalse(Files.readString(directory.resolve(
                "leverage/launcher-preferences.json")).contains("http://localhost/"));
        assertEquals("fabric-1.21.4", preferences.loadSelectedVersion(
                "http://localhost/").orElseThrow());
    }

    @Test void removedSavedVersionFallsBackToAnActuallyInstalledVersion(
            ) throws Exception {
        install("26.3", "2026-10-01T00:00:00Z");
        LauncherPreferences preferences = new LauncherPreferences(directory);
        preferences.saveSelectedVersion("offline", "deleted-1.6.2");
        AtomicReference<InstanceSelectionPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new InstanceSelectionPanel();
            panel.configure(preferences, path -> { });
            panel.setServer(null);
            ref.set(panel);
        });
        await(() -> ref.get().gameDirectory() != null);
        assertEquals("26.3", preferences.loadSelectedVersion("offline").orElseThrow());
        assertFalse(Files.exists(directory.resolve(
                "leverage/instances/offline/deleted-1.6.2")));
    }

    private void install(String id, String releaseTime) throws Exception {
        Path folder = Files.createDirectories(directory.resolve("versions").resolve(id));
        Files.writeString(folder.resolve(id + ".json"), "{\"id\":\"" + id
                + "\",\"releaseTime\":\"" + releaseTime + "\"}");
        Files.write(folder.resolve(id + ".jar"), new byte[] { 0 });
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            AtomicBoolean ready = new AtomicBoolean();
            do {
                SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
                if (!ready.get()) Thread.sleep(20);
            } while (!ready.get());
        });
    }

    private static boolean labelsContain(Container parent, String value) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && value.equals(
                    label.getText())) return true;
            if (child instanceof Container container && labelsContain(container,
                    value)) return true;
        }
        return false;
    }

    private static boolean clickButton(Container parent, String text) {
        for (Component child:parent.getComponents()) {
            if (child instanceof PixelButton button && text.equals(button.getText())) {
                button.doClick(0);
                return true;
            }
            if (child instanceof Container container && clickButton(container,
                    text)) return true;
        }
        return false;
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (
                child instanceof Container container) layout(container);
    }

    private static void preview(LauncherHomePanel home) {
        String output = System.getenv("LEVERAGE_PLAY_PREVIEW");
        if (output == null) return;
        home.showSection(LauncherHomePanel.Section.VERSIONS);
        home.setSize(1040, 680);
        layout(home);
        BufferedImage image = new BufferedImage(1040, 680, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        home.printAll(graphics);
        graphics.dispose();
        try {
            ImageIO.write(image, "PNG", Path.of(output).resolve(
                    "leverage-automatic-instances.png").toFile());
        }
        catch (Exception exception) { throw new AssertionError(exception); }
    }
}

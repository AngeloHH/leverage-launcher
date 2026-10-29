package dev.leverage.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.InstalledModCatalog;
import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherPreferences;
import dev.leverage.client.LocalAccountStore;
import dev.leverage.client.RememberedAccountManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static dev.leverage.ui.JourneyPreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;

// Approved journey rendered from the real production panels, never from a mock drawing.
class ApprovedJourneyPreviewTest {
    @TempDir Path isolated;

    @Test
    void exportsEveryApprovedScreenUsingOnlyIsolatedFixturesAndRealSwingComponents(
            ) throws Exception {
        int windowsBefore = Window.getWindows().length;
        Path output = outputDirectory();
        LauncherHomePanel home = onEdt(() -> {
            installLookAndFeel();
            LauncherHomePanel panel = new LauncherHomePanel((message, error) -> { });
            panel.setAnimationEnabled(false);
            panel.setAccount("Jugador", true);
            panel.profile().load(new LauncherAuthClient.AccountProfile(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "Jugador", "jugador@example.test"));
            InstanceSelectionPanel versions = find(panel, InstanceSelectionPanel.class);
            assertNotNull(versions,
                    "The real version selector must remain part of the library");
            versions.setPreviewVersions(List.of("1.21.1", "1.20.4"), "1.21.1");
            panel.mods().setMods(List.of(
                    new InstalledModCatalog.Mod(Path.of("example-exploration.jar"),
                    "0".repeat(40), "Exploración", "1.0",
                    "Nuevos detalles para tu aventura. Datos de prueba.", null),
                    new InstalledModCatalog.Mod(Path.of("example-improvements.jar"),
                    "1".repeat(40), "Pequeñas mejoras", "2.4",
                    "Una descripción larga de ejemplo que permite revisar el "
                    + "recorte del texto sin cargar ningún archivo del jugador.",
                    null)), ignored -> { });
            panel.resourcepacks().setMods(List.of(
                    new InstalledModCatalog.Mod(Path.of("example-resource.zip"),
                    "2".repeat(40), "Mi mundo, a color", "Formato 34",
                    "Texturas de aventura. Datos de prueba.",
                    null)), ignored -> { });
            panel.skins().select("efe");
            return panel;
        });
        for (int[] size : SIZES) {
            capture(home, output, "home", size, () -> home.showScreen(
                    LauncherHomePanel.Screen.HOME));
            capture(home, output, "my-game-versions", size,
                    () -> home.showSection(LauncherHomePanel.Section.VERSIONS));
            capture(home, output, "my-game-mods", size,
                    () -> home.showSection(LauncherHomePanel.Section.MODS));
            capture(home, output, "my-game-resource-packs", size,
                    () -> home.showSection(LauncherHomePanel.Section.RESOURCEPACKS));
            capture(home, output, "skins", size, () -> home.showScreen(
                    LauncherHomePanel.Screen.SKINS));
            capture(home, output, "news", size, () -> home.showScreen(
                    LauncherHomePanel.Screen.NEWS));
            capture(home, output, "settings", size,
                    () -> home.showScreen(LauncherHomePanel.Screen.SETTINGS));
            capture(home, output, "about", size, () -> {
                home.showScreen(LauncherHomePanel.Screen.ABOUT);
                find(home, AboutPanel.class).closeStory();
            });
            capture(home, output, "about-story", size, () -> {
                home.showScreen(LauncherHomePanel.Screen.ABOUT);
                find(home, AboutPanel.class).openStory("origins");
            });
        }
        assertEquals(windowsBefore, Window.getWindows().length,
                "The screenshot harness must never create a JFrame or other top-level"
                + " window");
    }

    @Test
    void exportsAtlasAccountAndDownloadStepsWithoutRestoringOrConnectingAnyAccount(
            ) throws Exception {
        int windowsBefore = Window.getWindows().length;
        Path output = outputDirectory();
        LauncherWindow window = onEdt(() -> {
            installLookAndFeel();
            Path minecraft = isolated.resolve("minecraft");
            LauncherPreferences preferences = new LauncherPreferences(minecraft);
            LauncherAuthClient client = new LauncherAuthClient(
                    new RememberedAccountManager(
                    new LocalAccountStore(minecraft), new MemoryTokens()));
            LauncherWindow result = new LauncherWindow(client, preferences);
            var create = LauncherWindow.class.getDeclaredMethod("createContent");
            create.setAccessible(true);
            create.invoke(result);
            // Deliberately never call open(), the default constructor, or
            // restoreSavedSetup().
            return result;
        });
        JPanel root = field(window, "root", JPanel.class);
        var show = LauncherWindow.class.getDeclaredMethod("show",
                String.class, JTextField.class);
        show.setAccessible(true);
        for (int[] size : SIZES) {
            for (String step : new String[] { "server", "login", "download-source" }) {
                BufferedImage image = onEdt(() -> {
                    show.invoke(window, step, null);
                    field(window, "atlas", AtlasSetupPanel.class).showPhase(step, false);
                    BufferedImage frame = render(root, size[0], size[1]);
                    assertControlsFit(root);
                    return frame;
                });
                write(output, "atlas-" + step + "-" + size[0], image);
            }
        }
        for (String step : new String[] { "register-details",
                "register-username", "verify-email" }) {
            BufferedImage image = onEdt(() -> {
                show.invoke(window, step, null);
                field(window, "atlas", AtlasSetupPanel.class).showPhase(step, false);
                BufferedImage frame = render(root, 820, 560);
                assertControlsFit(root);
                return frame;
            });
            write(output, "atlas-" + step + "-820", image);
        }
        assertEquals(windowsBefore, Window.getWindows().length,
                "Atlas previews must remain windowless and use only injected local fi"
                + "xtures");
    }

    private static void capture(LauncherHomePanel home, Path output,
            String name, int[] size, Runnable select) throws Exception {
        BufferedImage image = onEdt(() -> {
            select.run();
            BufferedImage frame = render(home, size[0], size[1]);
            assertControlsFit(home);
            return frame;
        });
        write(output, name + "-" + size[0], image);
    }

    private static void installLookAndFeel() {
        FlatDarkLaf.setup();
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 0);
        UIManager.put("TextComponent.arc", 0);
    }

    private static <T> T field(Object target, String name,
            Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}

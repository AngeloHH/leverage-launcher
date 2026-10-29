package dev.leverage.ui;

import dev.leverage.client.LauncherPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

// The current home-entry check, formerly bundled with tests of the retired hexagon
// button.
class LauncherHomeEntryTest {
    @TempDir Path directory;

    @Test
    void readyEntersFullWidthNativeHomeWithoutPermanentPlayerSidebar() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            try {
                var window=new LauncherWindow(null, new LauncherPreferences(directory));
                var create=LauncherWindow.class.getDeclaredMethod("createContent");
                create.setAccessible(true);
                JPanel content= (JPanel)create.invoke(window);
                var show=LauncherWindow.class.getDeclaredMethod("show",
                        String.class, JTextField.class);
                show.setAccessible(true);
                show.invoke(window, "ready", null);
                var screensField=LauncherWindow.class.getDeclaredField("screens");
                screensField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, JPanel> screens= (Map<String,
                        JPanel>)screensField.get(window);
                find(screens.get("ready"), PixelButton.class).doClick(0);
                var home= (LauncherHomePanel)screens.get("home");
                home.setAnimationEnabled(false);
                assertTrue(home.isVisible());
                assertEquals(LauncherHomePanel.Screen.HOME, home.selectedScreen());
                var account=find(home, PlayerAccountCard.class);
                assertNotNull(account);
                assertEquals("Jugador · Offline",
                        account.getAccessibleContext().getAccessibleName());
                for (int[] size:new int[][] { { 1040, 680 }, { 820,
                        560 }, { 1280, 800 } }) {
                    render(content, size[0], size[1]);
                    assertNull(find(home, PlayerSkinView.class));
                    var nav=JourneyPreviewSupport.named(home,
                            "home-navigation", JPanel.class);
                    assertEquals(size[0], nav.getWidth());
                    assertEquals(size[1], nav.getY()+nav.getHeight());
                    var play=JourneyPreviewSupport.named(home,
                            "home-play", PixelButton.class);
                    assertEquals("JUGAR", play.getText());
                    Point position=SwingUtilities.convertPoint(play, 0, 0, content);
                    assertTrue(position.x>=0&&position.x+play.getWidth()<=size[0]);
                    assertTrue(position.y>=0&&position.y+play.getHeight()<=size[1]);
                    var previous=play.getBounds();
                    assertDoesNotThrow(()->play.doClick(0));
                    assertEquals(previous, play.getBounds());
                }
            } catch (ReflectiveOperationException|java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void render(JComponent component, int width, int height) {
        component.setSize(width, height);
        layout(component);
        var image=new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child:container.getComponents())if (
                child instanceof Container nested)layout(nested);
    }
    private static <T> T find(Container container, Class<T> type) {
        for (Component component:container.getComponents()) {
            if (type.isInstance(component))return type.cast(component);
            if (component instanceof Container child) {
                T found=find(child, type);
                if (found!=null)return found;
            }
        }
        return null;
    }
}

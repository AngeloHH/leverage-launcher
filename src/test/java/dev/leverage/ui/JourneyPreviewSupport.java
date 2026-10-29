package dev.leverage.ui;

import dev.leverage.client.RefreshTokenStore;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

// Real Swing paints, isolated fixtures and explicit EDT discipline. Never creates a
// window.
final class JourneyPreviewSupport {
    static final int[][] SIZES = { { 1040, 680 }, { 820, 560 }, { 1280, 800 } };

    private JourneyPreviewSupport() { }

    static <T> T onEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.call()); }
            catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() instanceof Exception exception) throw exception;
        return result.get();
    }

    static void drainEdt() throws Exception { onEdt(() -> null); }

    static void layout(Component component) {
        assertTrue(SwingUtilities.isEventDispatchThread(),
                "Layout and painting belong on the EDT");
        if (component instanceof Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) layout(child);
        }
    }

    static BufferedImage render(JComponent component, int width, int height) {
        assertTrue(SwingUtilities.isEventDispatchThread(),
                "Preview paints must not race Swing state");
        component.setSize(width, height);
        layout(component);
        // Text wrapping and scroll-view preferred sizes may change during the first
        // layout pass.
        layout(component);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { component.printAll(graphics); }
        finally { graphics.dispose(); }
        assertPainted(image);
        return image;
    }

    static void assertPainted(BufferedImage image) {
        int first = image.getRGB(0, 0), different = 0;
        for (int y = 0; y < image.getHeight(); y += 11) {
            for (int x = 0; x < image.getWidth(); x += 11) {
                if (image.getRGB(x, y) != first) different++;
            }
        }
        assertTrue(different > 50,
                "A successful preview must contain real content, not a blank surface");
        for (int x = 0; x < image.getWidth(); x++) {
            assertEquals(255, image.getRGB(x, image.getHeight() - 1) >>> 24,
                    "The window must paint to its bottom edge without a trans"
                    + "parent footer stripe");
        }
    }

    static Path outputDirectory() throws Exception {
        String configured = System.getenv("LEVERAGE_JOURNEY_PREVIEW");
        Path output = configured == null || configured.isBlank()
                ? Path.of("build", "journey-preview") : Path.of(configured);
        Files.createDirectories(output);
        return output.toAbsolutePath().normalize();
    }

    static void write(Path output, String name, BufferedImage image) throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread(),
                "PNG encoding must not block the EDT");
        assertTrue(ImageIO.write(image, "PNG", output.resolve(name + ".png").toFile()));
    }

    static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T result = find(nested, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    static <T> T named(Container parent, String name, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                T result = named(nested, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) result.addAll(descendants(nested));
        }
        return result;
    }

    static boolean visibleWithin(Component component, Component root) {
        for (Component current = component; current != null;
                current = current.getParent()) {
            if (!current.isVisible()) return false;
            if (current == root) return true;
        }
        return false;
    }

    static void assertControlsFit(JComponent root) {
        for (Component component : descendants(root)) {
            if (!(component instanceof AbstractButton
                    || component instanceof JTextComponent)
                    || !visibleWithin(component, root)
                    || component.getWidth() == 0) continue;
            Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(),
                    component.getBounds(), root);
            assertTrue(bounds.x >= -1 && bounds.x + bounds.width <= root.getWidth() + 1,
                    () -> "Visible control must fit horizontally: "
                    + component.getName() + " " + bounds);
        }
    }

    static final class MemoryTokens implements RefreshTokenStore {
        private final Map<String, String> tokens = new HashMap<>();
        @Override public synchronized void write(String id, String token) {
            tokens.put(id, token);
        }
        @Override public synchronized Optional<String> read(String id) {
            return Optional.ofNullable(tokens.get(id));
        }
        @Override public synchronized void delete(String id) { tokens.remove(id); }
    }
}

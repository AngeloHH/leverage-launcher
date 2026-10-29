package dev.leverage.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LauncherIconTest {
    @Test
    void preservesNativeIconPixelsAndOpacity() throws Exception {
        var source = ImageIO.read(LauncherIcon.class.getResource(
                "/dev/leverage/ui/images/favicon.png"));
        assertEquals(48, source.getWidth());
        assertEquals(48, source.getHeight());
        assertTrue(source.getColorModel().hasAlpha());
        var images = LauncherIcon.images();
        assertEquals(6, images.size());
        var nativeImage = (BufferedImage) images.get(3);
        int transparent = 0, visible = 0;
        for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            assertEquals(source.getRGB(x, y), nativeImage.getRGB(x, y));
            if (source.getRGB(x, y) >>> 24 == 0) transparent++;
            else visible++;
        }
        // Preserve the bundled PNG's original opacity.
        assertEquals(0, transparent);
        assertEquals(48 * 48, visible);
        int[] sizes = { 16, 24, 32, 48, 64, 128 };
        for (int i = 0; i < sizes.length; i++) {
            assertEquals(sizes[i], images.get(i).getWidth(null));
            assertEquals(sizes[i], images.get(i).getHeight(null));
        }
    }

    @Test
    void assignsTheBrandIconBeforeShowingAWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Leverage icon test");
            try {
                LauncherIcon.apply(frame);
                assertEquals(LauncherIcon.images(), frame.getIconImages());
                assertFalse(frame.isVisible());
            } finally {
                frame.dispose();
            }
        });
    }
}

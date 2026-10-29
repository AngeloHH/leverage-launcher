package dev.leverage.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class PixelSeparatorTest {
    @Test
    void matchesTheCssRuleShadowAndMarginsAtFullWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PixelSeparator separator = new PixelSeparator();
            assertEquals(34, separator.getPreferredSize().height);
            for (int width : new int[] { 1, 180, 638 }) {
                separator.setSize(width, 34);
                BufferedImage image = new BufferedImage(width, 34,
                        BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    separator.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < 34; y++) {
                        int expected = y == 14 || y == 15 ? 0x40000000
                                : y == 16 || y == 17 ? new Color(0x6b6562).getRGB() : 0;
                        assertEquals(expected, image.getRGB(x, y),
                                "Pixel " + x + "," + y);
                    }
                }
            }
        });
    }
}

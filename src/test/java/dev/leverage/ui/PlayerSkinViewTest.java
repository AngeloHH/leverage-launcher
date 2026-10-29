package dev.leverage.ui;

import dev.leverage.skin.DefaultSkin;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSkinViewTest {
    @Test
    void rendersClassicSlimLegacyAndOuterLayersWithoutChangingSource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlayerSkinView view = new PlayerSkinView();
            view.setSize(346, 680);
            var original = DefaultSkin.image();
            int before = original.getRGB(8, 8);
            view.setSkin(original, false);
            int[] classic = render(view);
            view.setSkin(original, true);
            assertFalse(Arrays.equals(classic, render(view)));
            view.setSkin(original.getSubimage(0, 0, 64, 32), false);
            assertDoesNotThrow(() -> render(view));
            BufferedImage overlay = DefaultSkin.image();
            for (int y = 8; y < 16; y++) for (int x = 40; x < 48;
                    x++) overlay.setRGB(x, y, 0xff00ff00);
            view.setSkin(overlay, false);
            assertFalse(Arrays.equals(classic, render(view)));
            assertEquals(before, original.getRGB(8, 8));
            assertThrows(IllegalArgumentException.class, () -> view.setSkin(null, false));
            assertThrows(IllegalArgumentException.class,
                    () -> view.setSkin(new BufferedImage(128, 128,
                    BufferedImage.TYPE_INT_ARGB), false));
        });
    }

    private static int[] render(PlayerSkinView view) {
        BufferedImage image = new BufferedImage(view.getWidth(),
                view.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
        }
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }
}

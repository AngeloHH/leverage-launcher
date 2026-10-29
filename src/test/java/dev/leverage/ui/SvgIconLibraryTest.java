package dev.leverage.ui;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgIconLibraryTest {
    @Test
    void exposesEveryBundledIcon() {
        assertEquals(15, SvgIconLibrary.ids().size());
        assertTrue(SvgIconLibrary.ids().contains("pixel-chevron-down"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-play"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-headset"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-grid-add"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-pencil"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-hanger"));
        assertTrue(SvgIconLibrary.ids().contains("pixel-appearance"));
        assertTrue(SvgIconLibrary.ids().containsAll(java.util.Set.of(
                "pixel-players", "pixel-sign", "pixel-chest",
                "pixel-news", "pixel-download",
                "pixel-chevron-up", "pixel-copy", "pixel-gift")));
    }

    @Test
    void createsScaledTintedIcons() {
        Icon icon = SvgIconLibrary.icon("pixel-play", 18, 20, Color.WHITE);
        assertEquals(18, icon.getIconWidth());
        assertEquals(20, icon.getIconHeight());
    }

    @Test
    void newPixelIconsRenderWithoutBackgroundAndCanBeTinted() throws Exception {
        for (String id : new String[] { "pixel-grid-add", "pixel-pencil",
                "pixel-hanger", "pixel-appearance",
                "pixel-players", "pixel-sign", "pixel-chest",
                    "pixel-news", "pixel-download",
                "pixel-chevron-up", "pixel-copy", "pixel-gift" }) {
            BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            Color tint = new Color(35, 145, 78);
            try { SvgIconLibrary.paint(graphics, id, 6, 6, 36, 36, tint); }
            finally { graphics.dispose(); }
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            int painted = 0, transparent = 0;
            for (int y = 6; y < 42; y++) for (int x = 6; x < 42; x++) {
                int pixel = image.getRGB(x, y);
                if (pixel >>> 24 == 255) {
                    painted++;
                    assertEquals(tint.getRGB(), pixel);
                } else if (pixel >>> 24 == 0) transparent++;
            }
            assertTrue(painted > 0, "The icon must not be empty.");
            assertTrue(transparent > 0, "The icon's holes must be transparent.");
            String output = System.getenv("LEVERAGE_ICON_PREVIEW");
            if (output != null) {
                var directory = java.nio.file.Path.of(output);
                java.nio.file.Files.createDirectories(directory);
                javax.imageio.ImageIO.write(image, "PNG",
                        directory.resolve(id + "-preview.png").toFile());
            }
        }
    }

    @Test
    void referenceIconsKeepTheirPixelOutlinesAndTransparentHoles() throws Exception {
        BufferedImage chevron = renderNative("pixel-chevron-up");
        assertEquals(Color.WHITE.getRGB(), chevron.getRGB(11, 6));
        assertEquals(Color.WHITE.getRGB(), chevron.getRGB(2, 15));
        assertEquals(0, chevron.getRGB(11, 12) >>> 24);
        BufferedImage copy = renderNative("pixel-copy");
        int gray = new Color(0xD4D3D3).getRGB();
        assertEquals(gray, copy.getRGB(8, 2));
        assertEquals(gray, copy.getRGB(14, 4));
        assertEquals(gray, copy.getRGB(4, 8));
        assertEquals(gray, copy.getRGB(10, 20));
        assertEquals(0, copy.getRGB(10, 4) >>> 24);
        assertEquals(0, copy.getRGB(6, 8) >>> 24);
        BufferedImage gift = renderNative("pixel-gift");
        assertEquals(Color.WHITE.getRGB(), gift.getRGB(7, 4));
        assertEquals(Color.WHITE.getRGB(), gift.getRGB(5, 19));
        assertEquals(Color.WHITE.getRGB(), gift.getRGB(11, 11));
        assertEquals(0, gift.getRGB(9, 6) >>> 24);
        assertEquals(0, gift.getRGB(8, 11) >>> 24);
        String output = System.getenv("LEVERAGE_ICON_PREVIEW");
        if (output != null) {
            BufferedImage preview = new BufferedImage(420, 150,
                    BufferedImage.TYPE_INT_ARGB);
            var graphics = preview.createGraphics();
            try {
                graphics.setColor(new Color(0x171615));
                graphics.fillRect(0, 0, 420, 150);
                graphics.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 14));
                String[] ids = { "pixel-chevron-up", "pixel-copy", "pixel-gift" };
                String[] labels = { "Chevron", "Copiar", "Regalo" };
                for (int i = 0; i < ids.length; i++) {
                    SvgIconLibrary.paint(graphics, ids[i],
                            i * 140 + 34, 18, 72, 72, null);
                    graphics.setColor(Color.WHITE);
                    graphics.drawString(labels[i], i * 140 + (140
                            - graphics.getFontMetrics().stringWidth(labels[i])) / 2, 125);
                }
            } finally { graphics.dispose(); }
            javax.imageio.ImageIO.write(preview, "PNG", java.nio.file.Path.of(
                    output).resolve("new-pixel-icons-preview.png").toFile());
        }
    }

    private static BufferedImage renderNative(String id) {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { SvgIconLibrary.paint(graphics, id, 0, 0, 24, 24, null); }
        finally { graphics.dispose(); }
        return image;
    }

    @Test
    void rejectsUnknownIds() {
        assertThrows(IllegalArgumentException.class,
                () -> SvgIconLibrary.icon("does-not-exist", 16, 16));
    }
}

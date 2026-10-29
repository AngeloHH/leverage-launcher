package dev.leverage.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

// Opaque A/B seam. It travels with two pages; it is not a reveal/wipe mask.
public final class PixelSlideSeam {
    private PixelSlideSeam() { }
    public static void paint(Graphics2D graphics, int x, int y, int width, int height,
            int cell, long seed, Color left, Color right, boolean reverse) {
        if (width <= 0 || height <= 0) return;
        int unit = Math.max(28, Math.min(36, cell));
        int rows = (height + unit - 1) / unit, patch = Math.min(12,
                rows), start = (rows - patch) / 2;
        int origin = (width - 5 * unit) / 2;
        List<String> data = PixelatedPanelGeometry.pattern(seed);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clipRect(x, y, width, height);
            g.setColor(reverse ? right : left);
            g.fillRect(x, y, Math.max(0, origin), height);
            g.setColor(reverse ? left : right);
            g.fillRect(x + origin + 5 * unit, y, Math.max(0,
                    width - origin - 5 * unit), height);
            for (int row = 0; row < rows; row++) for (int col = 0; col < 5; col++) {
                boolean a = col < 2;
                if (row >= start && row < start + patch && col >= 1 && col <= 3) a
                        = data.get(col - 1).charAt((row - start) / 2) == '1';
                int xx = reverse ? width - origin - (col + 1) * unit
                        : origin + col * unit;
                g.setColor(a ? left : right);
                g.fillRect(x + xx, y + row * unit, unit, Math.min(unit,
                        height - row * unit));
            }
        } finally { g.dispose(); }
    }
}

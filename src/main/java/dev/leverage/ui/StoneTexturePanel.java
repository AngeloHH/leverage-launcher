package dev.leverage.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

// Procedural, deterministic dark stone texture used behind launcher screens.
public final class StoneTexturePanel extends JPanel {
    private static final int PIXEL_SIZE = 32;

    public StoneTexturePanel() {
        setOpaque(true);
        setBackground(new Color(21, 22, 22));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            paintStone(g, getWidth(), getHeight());
            paintVignette(g, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    private static void paintStone(Graphics2D g, int width, int height) {
        g.setColor(new Color(21, 22, 22));
        g.fillRect(0, 0, width, height);

        int rows = (height + PIXEL_SIZE - 1) / PIXEL_SIZE;
        int columns = (width + PIXEL_SIZE - 1) / PIXEL_SIZE;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int detail = hash(row, column);
                int cluster = hash(row / 2, column / 2);
                int shade = 22 + Math.floorMod((cluster >>> 6) + detail, 9);

                if (Math.floorMod(detail >>> 12, 11) == 0) {
                    shade -= 4;
                }

                int red = Math.max(0, shade);
                int green = Math.max(0, shade + 1);
                int blue = Math.max(0, shade + 1);
                g.setColor(new Color(red, green, blue));
                g.fillRect(column * PIXEL_SIZE, row * PIXEL_SIZE, PIXEL_SIZE, PIXEL_SIZE);
            }
        }
    }

    private static void paintVignette(Graphics2D g, int width, int height) {
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 75), 0,
                Math.max(1, height / 2f), new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, width, Math.max(1, height / 2));

        g.setPaint(new GradientPaint(0, Math.max(0, height * 0.55f), new Color(0,
                0, 0, 0), 0, height, new Color(0, 0, 0, 115)));
        g.fillRect(0, Math.max(0, height / 2), width, Math.max(1, height / 2));

        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 85), Math.max(1,
                width * 0.22f), 0, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, Math.max(1, width / 3), height);

        g.setPaint(new GradientPaint(Math.max(0, width * 0.78f), 0, new Color(0,
                0, 0, 0), width, 0, new Color(0, 0, 0, 85)));
        g.fillRect(Math.max(0, width * 2 / 3), 0, Math.max(1, width / 3), height);
    }

    private static int hash(int row, int column) {
        int value = row * 0x45d9f3b + column * 0x27d4eb2d + 0x5f3759df;
        value ^= value >>> 16;
        value *= 0x45d9f3b;
        return value ^ value >>> 16;
    }
}

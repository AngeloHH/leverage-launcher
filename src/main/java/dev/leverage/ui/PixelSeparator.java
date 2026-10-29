package dev.leverage.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

// A full-width 2px rule, a 2px upper shadow and 16px vertical margins (1rem = 16px).
public final class PixelSeparator extends JComponent {
    private final int margin;
    public PixelSeparator() {
        this(16);
    }

    public PixelSeparator(int margin) {
        if (margin < 0) throw new IllegalArgumentException(
                "El margen no puede ser negativo.");
        this.margin = margin;
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(0, margin * 2 + 2));
        setMinimumSize(new Dimension(0, margin * 2 + 2));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, margin * 2 + 2));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(new Color(0, 0, 0, 0x40));
        graphics.fillRect(0, margin - 2, getWidth(), 2);
        graphics.setColor(new Color(0x6B, 0x65, 0x62));
        graphics.fillRect(0, margin, getWidth(), 2);
    }
}

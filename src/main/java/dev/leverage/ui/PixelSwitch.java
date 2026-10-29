package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

// Minecraft-style on/off control based on the minecraft.net pixel switch.
public final class PixelSwitch extends JToggleButton {
    private static final int WIDTH = 44;
    private static final int HEIGHT = 24;
    private static final int TRACK_HEIGHT = 21;
    private static final int OUTLINE = 1;
    private static final int KNOB = 24;

    private static final Color ACTIVE_TRACK = new Color(0x3C, 0x85, 0x27);
    private static final Color INACTIVE_TRACK = new Color(0x8C, 0x8D, 0x90);
    private static final Color DARK_OUTLINE = new Color(0x1E, 0x1E, 0x1F);
    private static final Color KNOB_FACE = new Color(0xD0, 0xD1, 0xD4);
    private static final Color KNOB_DEPTH = new Color(0x58, 0x58, 0x5A);

    public PixelSwitch(boolean selected) {
        setSelected(selected);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(new Dimension(WIDTH, HEIGHT));
        setMaximumSize(new Dimension(WIDTH, HEIGHT));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(t("sources.noExternal"));
        getAccessibleContext().setAccessibleDescription(t("sources.switchDescription"));
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            int y = (getHeight() - HEIGHT) / 2;
            int trackY = y + 3;
            boolean selected = isSelected();
            Color track = selected ? ACTIVE_TRACK : INACTIVE_TRACK;
            if (!isEnabled()) {
                track = new Color(111, 111, 113);
            }

            // Dark two-pixel frame around the complete switch.
            g.setColor(DARK_OUTLINE);
            g.fillRect(0, trackY, WIDTH, TRACK_HEIGHT);

            // Track with the same inset highlights as the CSS reference.
            g.setColor(track);
            g.fillRect(OUTLINE, trackY + OUTLINE, WIDTH - OUTLINE * 2,
                    TRACK_HEIGHT - OUTLINE * 2);
            g.setColor(new Color(255, 255, 255, 51));
            g.fillRect(OUTLINE, trackY + OUTLINE, WIDTH - OUTLINE * 2, 1);
            g.fillRect(OUTLINE, trackY + OUTLINE, 1, TRACK_HEIGHT - OUTLINE * 2);
            g.setColor(new Color(255, 255, 255, 26));
            g.fillRect(OUTLINE, trackY + TRACK_HEIGHT - 2, WIDTH - OUTLINE * 2, 1);
            g.fillRect(WIDTH - 2, trackY + OUTLINE, 1, TRACK_HEIGHT - OUTLINE * 2);

            // Let the knob cover the lateral outline completely at both ends.
            int knobX = selected ? WIDTH - KNOB : 0;
            int knobY = trackY + TRACK_HEIGHT - KNOB;
            paintKnob(g, knobX, knobY);
            paintStateGlyph(g, selected, trackY);
        } finally {
            g.dispose();
        }
    }

    private static void paintKnob(Graphics2D g, int x, int y) {
        g.setColor(Color.BLACK);
        g.fillRect(x, y, KNOB, KNOB);

        g.setColor(KNOB_FACE);
        g.fillRect(x + 1, y + 1, KNOB - 2, KNOB - 2);

        g.setColor(new Color(255, 255, 255, 153));
        g.fillRect(x + 1, y + 1, KNOB - 2, 1);
        g.fillRect(x + 1, y + 1, 1, KNOB - 2);

        g.setColor(new Color(255, 255, 255, 102));
        g.fillRect(x + KNOB - 2, y + 1, 1, KNOB - 2);

        g.setColor(KNOB_DEPTH);
        g.fillRect(x + 1, y + KNOB - 3, KNOB - 2, 2);
    }

    private static void paintStateGlyph(Graphics2D g, boolean selected, int y) {
        g.setColor(Color.WHITE);
        int centerX = selected ? 11 : 33;
        int top = y + 7;
        if (selected) {
            // The supplied I.svg is a 2 x 12 white rectangle.
            g.fillRect(centerX, top, 1, 8);
        } else {
            // Pixel O matching the scaled one-pixel stroke.
            g.fillRect(centerX - 3, top, 7, 1);
            g.fillRect(centerX - 3, top + 7, 7, 1);
            g.fillRect(centerX - 3, top + 1, 1, 6);
            g.fillRect(centerX + 3, top + 1, 1, 6);
        }
    }
}

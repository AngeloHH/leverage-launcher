package dev.leverage.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

// Square checkbox with a recessed pixel-style face and standard Swing interaction.
public final class PixelCheckBox extends JCheckBox {
    private static final int SIZE = 24;
    private static final int BORDER = 2;
    private static final int SHADOW_HEIGHT = 6;
    private static final Color OUTLINE = new Color(0x6B, 0x65, 0x62);
    private static final Color FACE = new Color(0x26, 0x24, 0x23);

    public PixelCheckBox(String text, boolean selected) {
        super(text, selected);
        Icon icon = new RecessedIcon();
        setIcon(icon);
        setSelectedIcon(icon);
        setDisabledIcon(icon);
        setDisabledSelectedIcon(icon);
        setRolloverIcon(icon);
        setRolloverSelectedIcon(icon);
        setPressedIcon(icon);
        setIconTextGap(10);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        setFont(LauncherFonts.minecraftSeven(14f));
        setForeground(new Color(28, 28, 28));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(0.5f);
        Dimension size = getPreferredSize();
        setMinimumSize(size);
        setMaximumSize(size);
    }

    private static final class RecessedIcon implements Icon {
        @Override public int getIconWidth() {
            return SIZE;
        }

        @Override public int getIconHeight() {
            return SIZE;
        }

        @Override public void paintIcon(Component component,
                Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setColor(OUTLINE);
                g.fillRect(0, 0, SIZE, SIZE);
                g.setColor(FACE);
                g.fillRect(BORDER, BORDER, SIZE - BORDER * 2, SIZE - BORDER * 2);
                g.setColor(new Color(0, 0, 0, 128));
                g.fillRect(BORDER, BORDER, SIZE - BORDER * 2, SHADOW_HEIGHT);
                if (component instanceof AbstractButton button && button.isSelected()) {
                    g.setColor(component.isEnabled() ? Color.WHITE
                            : new Color(160, 160, 160));
                    g.fillPolygon(new int[] { 5, 8, 11, 18, 20, 11 },
                            new int[] { 12, 10, 14, 6, 8, 19 }, 6);
                }
            } finally {
                g.dispose();
            }
        }
    }
}

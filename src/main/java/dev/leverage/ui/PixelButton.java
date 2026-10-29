package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

// Reusable launcher button with pixel-aligned borders and Minecraft-inspired depth.
public final class PixelButton extends JButton {
    private static final int CSS_INSET_SIZE = 4;
    private static final int BUTTON_DEPTH_SIZE = 8;
    private static final Font BUTTON_FONT = LauncherFonts.minecraftSeven(16f);

    private final Variant variant;
    private final Glyph glyph;
    private boolean textShadowPainted = true;
    private Variant disabledVariant = Variant.DISABLED;

    public PixelButton(String text, Variant variant) {
        this(text, variant, Glyph.NONE);
    }

    public PixelButton(String text, Variant variant, Glyph glyph) {
        super(text);
        this.variant = variant;
        this.glyph = glyph;

        setFont(BUTTON_FONT);
        setHorizontalAlignment(SwingConstants.CENTER);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setForeground(variant.foreground());
        setPreferredSize(new Dimension(230, 48));
        setMinimumSize(new Dimension(130, 40));
    }

    public boolean isTextShadowPainted() { return textShadowPainted; }

    public void setTextShadowPainted(boolean painted) {
        textShadowPainted = painted;
        repaint();
    }
    // Semantic selected actions may remain green while being genuinely disabled.
    public void setDisabledVariant(Variant palette) {
        disabledVariant = java.util.Objects.requireNonNull(palette);
        repaint();
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
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            paintRaisedStyle(g);
        } finally {
            g.dispose();
        }
    }

    private void paintRaisedStyle(Graphics2D g) {
        boolean pressed = getModel().isPressed() && getModel().isArmed();
        boolean hover = getModel().isRollover() && isEnabled();
        Variant palette = isEnabled() ? variant : disabledVariant;
        int contentOffset = pressed ? 2 : 0;
        int width = getWidth();
        int height = Math.max(0, getHeight() - contentOffset);
        int depth = pressed ? 4 : BUTTON_DEPTH_SIZE;
        int faceHeight = Math.max(0, height - depth);
        Color background = hover ? brighten(palette.face(), 10) : palette.face();
        if (pressed) {
            background = darken(background, 8);
        }

        // The colored face reaches every outer edge; there is no black outline.
        g.setColor(background);
        g.fillRect(0, contentOffset, width, faceHeight);

        // A wider, darker lower edge gives the face a solid three-dimensional base.
        g.setColor(palette.outline());
        g.fillRect(0, contentOffset + faceHeight, width, depth);

        // The same four inset shadows are tinted from each variant's palette.
        g.setColor(withAlpha(palette.shadow(), 128));
        g.fillRect(0, contentOffset + Math.max(0, faceHeight - CSS_INSET_SIZE),
                width, Math.min(CSS_INSET_SIZE, faceHeight));

        g.setColor(withAlpha(palette.highlight(), 252));
        g.fillRect(0, contentOffset, width, Math.min(CSS_INSET_SIZE, faceHeight));

        g.setColor(withAlpha(palette.highlight(), 128));
        g.fillRect(Math.max(0, width - CSS_INSET_SIZE), contentOffset,
                Math.min(CSS_INSET_SIZE, width), faceHeight);

        g.setColor(withAlpha(palette.shadow(), 128));
        g.fillRect(0, contentOffset, Math.min(CSS_INSET_SIZE, width), faceHeight);

        paintLabel(g, palette.foreground(), contentOffset, faceHeight);
    }

    private void paintLabel(Graphics2D g, Color color,
            int contentOffset, int faceHeight) {
        g.setFont(LauncherFonts.forLocale(getFont()));
        FontMetrics metrics = g.getFontMetrics();
        String label = getText() == null ? "" : getText();
        Icon icon = isEnabled() ? getIcon() : getDisabledIcon();
        if (icon == null) icon = getIcon();
        int iconWidth = icon != null ? icon.getIconWidth() : glyph == Glyph.NONE ? 0 : 15;
        int iconHeight = icon != null ? icon.getIconHeight() : iconWidth;
        int gap = iconWidth == 0 || label.isBlank() ? 0 : 9;
        int totalWidth = metrics.stringWidth(I18n.resolve(label)) + iconWidth + gap;
        int x = (getWidth() - totalWidth) / 2;
        int baseline = contentOffset + (faceHeight
                - metrics.getHeight()) / 2 + metrics.getAscent();

        if (iconWidth > 0) {
            int iconY = contentOffset + (faceHeight - iconHeight) / 2;
            if (icon != null) icon.paintIcon(this, g, x, iconY);
            else glyph.paint(g, x, iconY, iconWidth, color);
            x += iconWidth + gap;
        }

        if (textShadowPainted) {
            g.setColor(new Color(0, 0, 0, 115));
            g.drawString(I18n.resolve(label), x + 1, baseline + 2);
        }
        g.setColor(color);
        g.drawString(I18n.resolve(label), x, baseline);
    }

    private static Color brighten(Color color, int amount) {
        return new Color(Math.min(255, color.getRed() + amount),
                Math.min(255, color.getGreen() + amount), Math.min(255,
                color.getBlue() + amount), color.getAlpha());
    }

    private static Color darken(Color color, int amount) {
        return new Color(Math.max(0, color.getRed() - amount),
                Math.max(0, color.getGreen() - amount), Math.max(0,
                color.getBlue() - amount), color.getAlpha());
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public enum Variant {
        PRIMARY(new Color(0x36, 0xB0, 0x30), new Color(0x64, 0xFD, 0x1F),
                new Color(0x15, 0x6C, 0x00), new Color(0x1B, 0x6E, 0x18),
                Color.WHITE), SECONDARY(new Color(112, 66, 207), new Color(167, 92,
                235), new Color(61, 28, 151), new Color(70, 38, 143), Color.WHITE),
                NEUTRAL(new Color(205, 196, 193), new Color(245, 238, 236),
                new Color(139, 129, 127), new Color(145, 133, 129), new Color(22, 18,
                18)), DANGER(new Color(0xC6, 0x2E, 0x2E), new Color(0xFF, 0x68, 0x68),
                new Color(0x8E, 0x20, 0x20), new Color(0x74, 0x1A, 0x1A), Color.WHITE),
                DARK(new Color(67, 65, 65), new Color(146, 140, 140), new Color(32, 29,
                29), new Color(41, 39, 39), Color.WHITE), SELECTED(new Color(0x3b702c),
                new Color(0x5c9148), new Color(0x28501e), new Color(0x28501e),
                Color.WHITE), DISABLED(new Color(114, 108, 107), new Color(151, 144, 142),
                new Color(70, 65, 64), new Color(76, 70, 69), new Color(202, 197, 195));

        private final Color face;
        private final Color highlight;
        private final Color shadow;
        private final Color outline;
        private final Color foreground;

        Variant(Color face, Color highlight, Color shadow,
                Color outline, Color foreground) {
            this.face = face;
            this.highlight = highlight;
            this.shadow = shadow;
            this.outline = outline;
            this.foreground = foreground;
        }

        Color face() {
            return face;
        }

        Color highlight() {
            return highlight;
        }

        Color shadow() {
            return shadow;
        }

        Color outline() {
            return outline;
        }

        Color foreground() {
            return foreground;
        }
    }

    public enum Glyph {
        NONE {
            @Override void paint(Graphics2D g, int x, int y, int size, Color color) {
            }
        }, PLAY {
            @Override void paint(Graphics2D g, int x, int y, int size, Color color) {
                SvgIconLibrary.paint(g, "pixel-play", x, y, size, size, color);
            }
        }, HEADSET {
            @Override void paint(Graphics2D g, int x, int y, int size, Color color) {
                SvgIconLibrary.paint(g, "pixel-headset", x, y, size, size, color);
            }
        }, EXTERNAL_LINK {
            @Override void paint(Graphics2D g, int x, int y, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(2));
                g.drawRect(x + 1, y + 5, size - 7, size - 7);
                g.drawLine(x + 7, y + 1, x + size - 1, y + 1);
                g.drawLine(x + size - 1, y + 1, x + size - 1, y + 9);
                g.drawLine(x + size - 1, y + 1, x + 6, y + 10);
            }
        }, REFRESH {
            @Override void paint(Graphics2D g, int x, int y, int size, Color color) {
                g.setColor(color);
                g.setStroke(new BasicStroke(2));
                g.drawArc(x + 2, y + 2, size - 5, size - 5, 35, 285);
                Polygon arrow = new Polygon(new int[] { x + size - 1, x + size - 7,
                        x + size - 3 }, new int[] { y + 2, y + 1, y + 7 }, 3);
                g.fillPolygon(arrow);
            }
        };

        abstract void paint(Graphics2D g, int x, int y, int size, Color color);
    }
}

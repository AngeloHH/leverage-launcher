package dev.leverage.ui;

import javax.swing.BorderFactory;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.Objects;

// The dropdown's recessed surface, also applicable to ordinary text and password fields.
// Use on the EDT.
public final class PixelInputStyle {
    static final Color FACE = new Color(0x262423), OUTLINE = new Color(0x898481);
    private PixelInputStyle() { }

    // Styles an existing field without replacing its document, listeners, value, or
    // password masking.
    public static<T extends JTextField> T apply(T field) {
        Objects.requireNonNull(field);
        field.setFont(font());
        field.setBackground(FACE);
        field.setForeground(Color.WHITE);
        field.setOpaque(true);
        field.setBorder(new RecessedBorder());
        field.setMargin(new Insets(0, 0, 0, 0));
        field.setCaretColor(Color.WHITE);
        field.setDisabledTextColor(new Color(0xaba09c));
        field.setSelectionColor(new Color(0x3c8527));
        field.setSelectedTextColor(Color.WHITE);
        int width = Math.max(135, field.getPreferredSize().width);
        field.setPreferredSize(new Dimension(width, 48));
        field.setMinimumSize(new Dimension(135, 48));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return field;
    }

    static Font font() {
        return LauncherFonts.minecraftSeven(16).deriveFont(
                Map.of(TextAttribute.TRACKING, .01f));
    }
    static Border outlineBorder() { return BorderFactory.createLineBorder(OUTLINE, 2); }
    static void paintShadow(Graphics graphics, int x, int y, int width, int height) {
        Graphics copy = graphics.create();
        try {
            copy.setColor(new Color(0, 0, 0, 128));
            copy.fillRect(x + 2, y + 2, Math.max(0, width - 4),
                    Math.max(0, Math.min(6, height - 4)));
        }
        finally { copy.dispose(); }
    }

    private static final class RecessedBorder extends LineBorder {
        RecessedBorder() { super(OUTLINE, 2, false); }
        @Override public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(10, 14, 10, 10);
            return insets;
        }
        @Override public Insets getBorderInsets(Component component) {
            return new Insets(10, 14, 10, 10);
        }
        @Override public boolean isBorderOpaque() { return false; }
        @Override public void paintBorder(Component component,
                Graphics graphics, int x, int y, int width, int height) {
            super.paintBorder(component, graphics, x, y, width, height);
            paintShadow(graphics, x, y, width, height);
        }
    }
}

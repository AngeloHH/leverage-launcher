package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class JourneyStyle {
    static final Color INK = new Color(0x171615), NAVY = new Color(0x111c24),
            PAPER = new Color(0xfaf4ef), TABLE = new Color(0x3d7136),
            GREEN = new Color(0x86d562);
    static final BufferedImage COPPER = image(
            "/dev/leverage/ui/journey/ocean-exploration.png");
    static final BufferedImage LOGO = image("/dev/leverage/ui/images/leverage.png");

    private JourneyStyle() { }

    static BufferedImage image(String path) {
        try (var stream = JourneyStyle.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            return ImageIO.read(stream);
        } catch (IOException ignored) { return null; }
    }

    static JLabel label(String text, float size, Color color, boolean ten) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(ten ? LauncherFonts.minecraftTen(size)
                : LauncherFonts.minecraftSeven(size));
        label.setForeground(color);
        return label;
    }

    static JButton link(String text, Runnable action) {
        boolean arrow = text.contains("↗") || text.contains("→");
        JButton button = new JButton(I18n.transform(text,
                value -> value.replace("↗", "").replace("→", "").strip()));
        button.setFont(LauncherFonts.minecraftSeven(13));
        button.setForeground(GREEN);

        // Draw the arrow separately so translated labels retain their own width.
        if (arrow) {
            button.setHorizontalTextPosition(SwingConstants.LEFT);
            button.setIcon(new Icon() {
                public int getIconWidth() { return 16; }
                public int getIconHeight() { return 16; }
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    g.setColor(c.getForeground());
                    g.fillRect(x + 2, y + 7, 12, 2);
                    for (int i = 0; i < 4; i++) {
                        g.fillRect(x + 7 + i * 2, y + 1 + i * 2, 2, 2);
                        g.fillRect(x + 7 + i * 2, y + 13 - i * 2, 2, 2);
                    }
                }
            });
        }
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run());
        return button;
    }

    static JScrollPane scroll(JComponent content, Color background) {
        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setBackground(background);
        scroll.getViewport().setBackground(background);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        return scroll;
    }

    static void cover(Graphics2D g, BufferedImage image,
            Rectangle target, Rectangle source) {
        if (image == null || target.width <= 0 || target.height <= 0) return;
        Rectangle s = source == null ? new Rectangle(0, 0,
                image.getWidth(), image.getHeight()) : source;
        double scale = Math.max(target.width / (double) s.width,
                target.height / (double) s.height);
        int width = (int) Math.ceil(s.width * scale),
                height = (int) Math.ceil(s.height * scale);
        int x = target.x + (target.width - width) / 2,
                y = target.y + (target.height - height) / 2;
        Graphics2D copy = (Graphics2D) g.create();
        copy.clip(target);
        copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        copy.drawImage(image, x, y, x + width, y + height, s.x, s.y,
                s.x + s.width, s.y + s.height, null);
        copy.dispose();
    }

    static int text(Graphics2D g, String text, Font font, Color color,
            int x, int y, int width, int lineHeight) {
        text = I18n.resolve(text);
        font = LauncherFonts.forText(LauncherFonts.forLocale(font), text);
        g.setFont(font);
        g.setColor(color);
        int baseline = y + g.getFontMetrics().getAscent();
        for (String line : wrap(g.getFontMetrics(), text, width)) {
            g.drawString(I18n.resolve(line), x, baseline);
            baseline += lineHeight;
        }
        return baseline - g.getFontMetrics().getAscent();
    }

    static int centeredText(Graphics2D g, String text, Font font,
            Color color, int x, int y, int width, int lineHeight) {
        text = I18n.resolve(text);
        font = LauncherFonts.forText(LauncherFonts.forLocale(font), text);
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics();
        int baseline = y + metrics.getAscent();
        for (String line : wrap(metrics, text, width)) {
            g.drawString(I18n.resolve(line), x + (width - metrics.stringWidth(
                    I18n.resolve(line))) / 2, baseline);
            baseline += lineHeight;
        }
        return baseline - metrics.getAscent();
    }

    static List<String> wrap(FontMetrics metrics, String text, int width) {
        text = I18n.resolve(text);
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            String line = "";
            var boundaries = java.text.BreakIterator.getLineInstance(I18n.locale());
            boundaries.setText(paragraph);
            int start = boundaries.first();
            for (int end = boundaries.next(); end != java.text.BreakIterator.DONE;
                    start = end, end = boundaries.next()) {
                String token = paragraph.substring(start, end), next = line + token;
                if (!line.isBlank() && metrics.stringWidth(I18n.resolve(
                        next.stripTrailing())) > Math.max(1, width)) {
                    lines.add(line.stripTrailing());
                    line = token.stripLeading();
                } else line = next;
            }
            lines.add(line.stripTrailing());
        }
        return lines;
    }

    static BufferedImage snapshot(JComponent component, int width, int height) {
        component.setSize(Math.max(1, width), Math.max(1, height));
        layoutTree(component);
        BufferedImage image = new BufferedImage(Math.max(1, width),
                Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        component.printAll(g);
        g.dispose();
        return image;
    }

    static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents())
            if (child instanceof Container c) layoutTree(c);
    }
}

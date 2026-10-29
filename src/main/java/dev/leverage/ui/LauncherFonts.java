package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

// Fonts bundled with the launcher so the interface looks the same on every computer.
public final class LauncherFonts {
    private static final Font MINECRAFT_FIVE = load("minecraft-five.ttf");
    private static final Font MINECRAFT_SEVEN = load("minecraft-seven.ttf");
    private static final Font MINECRAFT_TEN = load("minecraft-ten.ttf");
    private static final String CJK_FONT = chooseCjkFont();

    private LauncherFonts() {
    }

    public static Font minecraftFive(float size) {
        return forLocale(MINECRAFT_FIVE.deriveFont(Font.PLAIN, size));
    }

    public static Font minecraftSeven(float size) {
        return forLocale(MINECRAFT_SEVEN.deriveFont(Font.PLAIN, size));
    }

    public static Font minecraftTen(float size) {
        return forLocale(MINECRAFT_TEN.deriveFont(Font.PLAIN, size));
    }

    // Logical fonts provide Windows font linking for scripts absent from the pixel fonts.
    public static Font forLocale(Font font) {
        if (font == null) return null;
        Font original = font instanceof ScriptFont script ? script.original : font;
        String sample = switch (I18n.language()) {
            case RUSSIAN -> "АБВГДабвгдёжий";
            case CHINESE -> "简体中文设置新闻用户";
            default -> "";
        };
        if (!sample.isEmpty() && original.canDisplayUpTo(sample) != -1) {
            boolean heading = original.getName().equals(MINECRAFT_TEN.getName());
            Font mapped = new ScriptFont(original,
                    I18n.language() == I18n.Language.CHINESE ? CJK_FONT : Font.DIALOG,
                    heading ? Font.BOLD : original.getStyle());
            return mapped.equals(font) ? font : mapped;
        }
        return original;
    }

    private static String chooseCjkFont() {
        var available = java.util.Set.of(java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : new String[] { "Microsoft YaHei",
                "Noto Sans CJK SC", "Noto Sans SC", "DengXian" })
                if (available.contains(candidate)) return candidate;
        return Font.DIALOG;
    }

    public static Font forText(Font font, String text) {
        if (font.canDisplayUpTo(text) == -1) return font;
        boolean cjk = text.codePoints().anyMatch(value -> Character.UnicodeScript.of(
                value) == Character.UnicodeScript.HAN);
        Font original = font instanceof ScriptFont script ? script.original : font;
        return new ScriptFont(original, cjk ? CJK_FONT : Font.DIALOG,
                original.getName().equals(MINECRAFT_TEN.getName())
                ? Font.BOLD : original.getStyle());
    }

    // Retain the pixel-font role even when a window starts in a non-Latin language.
    private static final class ScriptFont extends Font {
        private final Font original;
        ScriptFont(Font original, String family, int style) {
            super(new Font(family, style, 1).deriveFont(
                    original.getSize2D()).getAttributes());
            this.original = original;
        }
        @Override public Font deriveFont(float size) {
            return forLocale(original.deriveFont(size));
        }
        @Override public Font deriveFont(int style) {
            return forLocale(original.deriveFont(style));
        }
        @Override public Font deriveFont(int style, float size) {
            return forLocale(original.deriveFont(style, size));
        }
        @Override public Font deriveFont(java.awt.geom.AffineTransform transform) {
            return forLocale(original.deriveFont(transform));
        }
        @Override public Font deriveFont(int style,
                java.awt.geom.AffineTransform transform) {
            return forLocale(original.deriveFont(style, transform));
        }
        @Override public Font deriveFont(java.util.Map<?
                extends java.text.AttributedCharacterIterator.Attribute, ?> attributes) {
            return forLocale(original.deriveFont(attributes));
        }
    }

    private static Font load(String fileName) {
        String resource = "/dev/leverage/ui/fonts/" + fileName;
        try (InputStream stream = LauncherFonts.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (FontFormatException | IOException exception) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        }
    }
}

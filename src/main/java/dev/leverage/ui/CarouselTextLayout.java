package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

// Plain text wrapping; never interprets descriptions as HTML.
final class CarouselTextLayout {
    private CarouselTextLayout() { }

    static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        width = Math.max(1, width);
        if (text.isEmpty()) return List.of();
        for (String paragraph : text.replace('\t', ' ').split("\\R", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            int from = 0;
            while (from < paragraph.length()) {
                while (from < paragraph.length() && Character.isWhitespace(
                        paragraph.codePointAt(from))) from
                        += Character.charCount(paragraph.codePointAt(from));
                if (from == paragraph.length()) break;
                int end = from, lastSpace = -1, pixels = 0;
                while (end < paragraph.length()) {
                    int code = paragraph.codePointAt(end),
                            next = end + Character.charCount(code),
                            charWidth = metrics.charWidth(code);
                    if (pixels + charWidth > width && end > from) break;
                    pixels += charWidth;
                    if (Character.isWhitespace(code)) lastSpace = end;
                    end = next;
                    if (pixels > width) break;
                }
                int cut = end < paragraph.length() && lastSpace > from ? lastSpace : end;
                lines.add(paragraph.substring(from, cut).stripTrailing());
                from = cut;
            }
        }
        return List.copyOf(lines);
    }

    static String ellipsize(String line, FontMetrics metrics, int width) {
        String dots = "...";
        while (!dots.isEmpty() && metrics.stringWidth(I18n.resolve(
                dots)) > width) dots = dots.substring(1);
        while (!line.isEmpty() && metrics.stringWidth(I18n.resolve(line
                + dots)) > width) line = line.substring(0,
                line.offsetByCodePoints(line.length(), -1));
        return line.stripTrailing() + dots;
    }
}

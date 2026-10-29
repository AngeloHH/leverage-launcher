package dev.leverage.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;

// Image-derived surfaces share a hue and keep labels and links readable.
record ArtworkPalette(Color background, Color shade, Color accent) {
    static ArtworkPalette from(BufferedImage image) {
        Color dominant = dominant(image);
        float[] hsb = Color.RGBtoHSB(dominant.getRed(), dominant.getGreen(),
                dominant.getBlue(), null);
        Color background = Color.getHSBColor(hsb[0], Math.min(.65f, hsb[1] * .75f),
                Math.max(.25f, Math.min(.48f, hsb[2] * .65f)));
        while (contrast(Color.WHITE, background) < 4.5) background
                = blend(background, Color.BLACK, .1);
        Color accent = Color.WHITE;
        for (int step = 0; step <= 10; step++) {
            Color candidate = blend(JourneyStyle.GREEN, Color.WHITE, step / 10d);
            if (contrast(candidate, background) >= 4.5) {
                accent = candidate;
                break;
            }
        }
        return new ArtworkPalette(background, blend(background, Color.BLACK, .55),
                accent);
    }

    private static Color dominant(BufferedImage image) {
        if (image == null) return JourneyStyle.NAVY;
        double[] weight = new double[25], red = new double[25],
                green = new double[25], blue = new double[25];
        int columns = Math.min(64, image.getWidth()),
                rows = Math.min(64, image.getHeight()), best = 0;
        float[] hsb = new float[3];
        for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++) {
            int x = (int)((col + .5) * image.getWidth() / columns),
                    y = (int)((row + .5) * image.getHeight() / rows);
            int pixel = image.getRGB(x, y), alpha = pixel >>> 24;
            if (alpha < 128) continue;
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            Color.RGBtoHSB(r, g, b, hsb);
            int bin = hsb[1] < .12f ? 24 : Math.min(23, (int)(hsb[0] * 24));
            double score = alpha / 255d * (.12 + hsb[1]) * (.2 + hsb[2]);
            weight[bin] += score;
            red[bin] += r * score;
            green[bin] += g * score;
            blue[bin] += b * score;
        }
        for (int bin = 1; bin < weight.length; bin++)
            if (weight[bin] > weight[best]) best = bin;
        if (weight[best] == 0) return JourneyStyle.NAVY;
        return new Color((int)(red[best] / weight[best]),
                (int)(green[best] / weight[best]), (int)(blue[best] / weight[best]));
    }

    private static Color blend(Color from, Color to, double amount) {
        return new Color((int)Math.round(from.getRed() * (1 - amount)
                + to.getRed() * amount), (int)Math.round(from.getGreen() * (1 - amount)
                + to.getGreen() * amount), (int)Math.round(from.getBlue() * (1 - amount)
                + to.getBlue() * amount));
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen())
                + .0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255d;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}

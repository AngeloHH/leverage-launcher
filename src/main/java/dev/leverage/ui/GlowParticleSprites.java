package dev.leverage.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;

// Four cached soft shadow sprites. The crisp square cores are painted separately.
final class GlowParticleSprites {
    record Sprite(BufferedImage image, int left, int top) { }
    private record CoreKey(int variant, int distanceStep) { }
    private static final ConcurrentHashMap<Integer,
            Sprite> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,
            Sprite> HALOS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CoreKey,
            Sprite> CORES = new ConcurrentHashMap<>();
    private GlowParticleSprites() { }

    static Sprite sprite(int variant) {
        return CACHE.computeIfAbsent(variant, GlowParticleSprites::create);
    }

    static void paint(Graphics2D target, GlowParticleModel.State state,
            GlowParticles.Appearance global, GlowParticles.Appearance local) {
        double opacity = state.opacity() * global.intensity() * local.intensity();
        if (opacity <= .001) return;
        double size = state.scale() * global.size() * local.size();
        double distance = 1 - (1 - global.distance()) * (1 - local.distance());
        double reach = global.glowReach() * local.glowReach();
        var adjusted = new GlowParticleModel.State(state.id(),
                state.variant(), state.x(), state.y(), size, opacity);
        if (distance == 0 && reach == 1) {
            // Exact original rendering path for the approved defaults, including diffuse
            // type four.
            paint(target, adjusted, 1);
            return;
        }
        var style = GlowParticleModel.DATA.variants().get(state.variant() - 1);
        if (reach > 0) {
            Sprite halo = style.square() ? sprite(state.variant())
                    : HALOS.computeIfAbsent(state.variant(), id -> create(id, false));
            drawSprite(target, halo, state.x(), state.y(), size * reach, (float) opacity);
        }
        if (distance == 0 && style.square()) paintSquare(target,
                adjusted, (float) opacity, style);
        else drawSprite(target, core(state.variant(), distance),
                state.x(), state.y(), size, (float) opacity);
    }

    // Twenty-one small defocused cores per profile at most; halo size never adds cache
    // entries.
    static Sprite core(int variant, double distance) {
        return CORES.computeIfAbsent(new CoreKey(variant,
                (int) Math.round(distance * 20)), key -> {
            var style = GlowParticleModel.DATA.variants().get(key.variant() - 1);
            double sigma = Math.hypot(style.filterBlur(), key.distanceStep() * .3);
            double radius = GlowParticleModel.DATA.coreSize() / 2;
            int extent = (int) Math.ceil(radius + 3 * sigma + 2);
            return new Sprite(layer(2 * extent + 1, 2 * extent + 1, extent,
                    extent, radius, sigma, Color.decode(style.core()),
                    1, style.square()), -extent, -extent);
        });
    }

    static int coreCacheSize() { return CORES.size(); }

    private static void drawSprite(Graphics2D target, Sprite sprite,
            double x, double y, double scale, float alpha) {
        Graphics2D g = (Graphics2D) target.create();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1, alpha)));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.translate(x, y);
            g.scale(scale, scale);
            g.drawImage(sprite.image(), sprite.left(), sprite.top(), null);
        } finally { g.dispose(); }
    }

    static void paint(Graphics2D target, GlowParticleModel.State state, float intensity) {
        float alpha = (float)(state.opacity() * intensity);
        if (alpha <= .001f) return;
        var style = GlowParticleModel.DATA.variants().get(state.variant() - 1);
        Sprite sprite = sprite(state.variant());
        Graphics2D g = (Graphics2D) target.create();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1, alpha)));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.translate(state.x(), state.y());
            g.scale(state.scale(), state.scale());
            g.drawImage(sprite.image(), sprite.left(), sprite.top(), null);
        } finally { g.dispose(); }
        if (style.square()) paintSquare(target, state, alpha, style);
    }

    private static void paintSquare(Graphics2D target, GlowParticleModel.State state,
            float alpha, GlowParticleModel.Variant style) {
        Graphics2D g = (Graphics2D) target.create();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1, alpha)));
            g.setColor(Color.decode(style.core()));
            int size = Math.max(1, (int) Math.round(
                    GlowParticleModel.DATA.coreSize() * state.scale()));
            g.fillRect((int) Math.round(state.x() - size / 2d),
                    (int) Math.round(state.y() - size / 2d), size, size);
        } finally { g.dispose(); }
    }

    private static Sprite create(int id) { return create(id, true); }
    private static Sprite create(int id, boolean includeCore) {
        var style = GlowParticleModel.DATA.variants().get(id - 1);
        double coreRadius = GlowParticleModel.DATA.coreSize() / 2;
        double left = -coreRadius - 3 * style.filterBlur(),
                right = -left, top = left, bottom = right;
        for (var shadow : style.shadows()) {
            double sigma = Math.hypot(shadow.blur() / 2, style.filterBlur());
            double extent = coreRadius + shadow.spread() + 3 * sigma + 2;
            left = Math.min(left, shadow.x() - extent);
            right = Math.max(right, shadow.x() + extent);
            top = Math.min(top, shadow.y() - extent);
            bottom = Math.max(bottom, shadow.y() + extent);
        }
        int x0 = (int) Math.floor(left), y0 = (int) Math.floor(top);
        int width = (int) Math.ceil(right) - x0 + 1,
                height = (int) Math.ceil(bottom) - y0 + 1;
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            // CSS paints the first listed shadow in front of later shadows.
            for (int index = style.shadows().size() - 1; index >= 0; index--) {
                var shadow = style.shadows().get(index);
                g.drawImage(layer(width, height, shadow.x() - x0,
                        shadow.y() - y0, coreRadius + shadow.spread(),
                        Math.hypot(shadow.blur() / 2, style.filterBlur()),
                        Color.decode(shadow.color()), shadow.alpha()), 0, 0, null);
            }
            if (includeCore && !style.square()) g.drawImage(layer(width,
                    height, -x0, -y0, coreRadius, style.filterBlur(),
                    Color.decode(style.core()), 1), 0, 0, null);
        } finally { g.dispose(); }
        return new Sprite(image, x0, y0);
    }

    private static BufferedImage layer(int width, int height, double cx, double cy,
            double radius, double sigma, Color color, double alpha) {
        return layer(width, height, cx, cy, radius, sigma, color, alpha, false);
    }
    private static BufferedImage layer(int width, int height, double cx, double cy,
            double radius, double sigma, Color color, double alpha, boolean square) {
        float[] mask = new float[width * height], scratch = new float[mask.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) mask[y * width
                + x] = (float) Math.max(0, Math.min(1, radius + .5 - (square ? Math.max(
                Math.abs(x - cx), Math.abs(y - cy)) : Math.hypot(x - cx, y - cy))));
        if (sigma > 0) {
            int lower = (int) Math.floor(Math.sqrt(4 * sigma * sigma + 1));
            if ((lower & 1) == 0) lower--;
            lower = Math.max(1, lower);
            int m = Math.max(0, Math.min(3, (int) Math.round((12 * sigma * sigma
                    - 3 * lower * lower - 12 * lower - 9) / (-4d * lower - 4))));
            // Three separable box filters approximate a Gaussian in linear pixel time.
            for (int pass = 0; pass < 3; pass++) {
                int radiusBox = ((pass < m ? lower : lower + 2) - 1) / 2;
                horizontal(mask, scratch, width, height, radiusBox);
                vertical(scratch, mask, width, height, radiusBox);
            }
        }
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((java.awt.image.DataBufferInt) image
                .getRaster().getDataBuffer()).getData();
        int rgb = color.getRGB() & 0xffffff;
        for (int i = 0; i < pixels.length; i++) pixels[i] = Math.max(0, Math.min(255,
                Math.round((float)(mask[i] * alpha * 255)))) << 24 | rgb;
        return image;
    }

    private static void horizontal(float[] input, float[] output,
            int width, int height, int radius) {
        float divisor = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            float sum = 0;
            for (int x = 0; x <= Math.min(radius, width - 1); x++) sum += input[row + x];
            for (int x = 0; x < width; x++) {
                output[row + x] = sum / divisor;
                if (x - radius >= 0) sum -= input[row + x - radius];
                if (x + radius + 1 < width) sum += input[row + x + radius + 1];
            }
        }
    }
    private static void vertical(float[] input, float[] output,
            int width, int height, int radius) {
        float divisor = 2 * radius + 1;
        for (int x = 0; x < width; x++) {
            float sum = 0;
            for (int y = 0; y <= Math.min(radius, height - 1);
                    y++) sum += input[y * width + x];
            for (int y = 0; y < height; y++) {
                output[y * width + x] = sum / divisor;
                if (y - radius >= 0) sum -= input[(y - radius) * width + x];
                if (y + radius + 1 < height) sum += input[(y + radius + 1) * width + x];
            }
        }
    }
}

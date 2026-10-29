package dev.leverage.ui;

import com.google.gson.Gson;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// Pixel-soft square profile calibrated from the reference, not nested flat outline
// squares.
final class PurpleParticleSprites {
    record Profile(List<String> colors, List<Double> edgeProfile,
            double coreDiameter, double opacity) {
        Profile {
            colors = List.copyOf(colors);
            edgeProfile = List.copyOf(edgeProfile);
        }
    }
    record Sprite(BufferedImage image, int origin) { }
    private record Key(int shade, int distanceStep) { }
    static final Profile PROFILE = load();
    private static final ConcurrentHashMap<Key, Sprite> CACHE = new ConcurrentHashMap<>();
    private PurpleParticleSprites() { }

    static void paint(Graphics2D target, PurpleParticleModel.State state,
            PurpleParticles.Appearance global, PurpleParticles.Appearance local) {
        double alpha = state.opacity() * global.intensity() *
                local.intensity() * PROFILE.opacity();
        if (alpha <= .001) return;
        double scale = state.pixels() * global.size() *
                local.size() / PROFILE.coreDiameter();
        double distance = 1 - (1 - global.distance()) * (1 - local.distance());
        double reach = global.glowReach() * local.glowReach();
        Graphics2D g = (Graphics2D) target.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if (reach > 0) draw(g, sprite(state.shade(), 0), state.x(),
                    state.y(), scale * reach * 1.35, alpha * .12);
            draw(g, sprite(state.shade(), distance), state.x(), state.y(), scale, alpha);
        } finally { g.dispose(); }
    }

    private static void draw(Graphics2D graphics, Sprite sprite,
            double x, double y, double scale, double alpha) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
            g.translate(Math.rint(x), Math.rint(y));
            g.scale(scale, scale);
            g.drawImage(sprite.image(), sprite.origin(), sprite.origin(), null);
        } finally { g.dispose(); }
    }

    static Sprite sprite(int shade, double distance) {
        return CACHE.computeIfAbsent(new Key(shade, (int) Math.round(distance * 20)),
                PurpleParticleSprites::create);
    }
    static int cacheSize() { return CACHE.size(); }

    private static Sprite create(Key key) {
        int baseSize = PROFILE.edgeProfile().size() * 2;
        double sigma = key.distanceStep() / 20d * 3;
        int radius = (int) Math.ceil(3 * sigma), size = baseSize + 2 * (radius + 2);
        float[] base = new float[size * size];
        int offset = radius + 2;
        for (int y = 0; y < baseSize; y++) for (int x = 0; x < baseSize;
                x++) base[(y + offset) * size + x + offset] = (float)(
                PROFILE.edgeProfile().get(x / 2) * PROFILE.edgeProfile().get(y / 2));
        if (radius > 0) {
            double[] kernel = new double[2 * radius + 1];
            double sum = 0;
            for (int i = -radius; i <= radius; i++) {
                kernel[i + radius] = Math.exp(-i * i / (2 * sigma * sigma));
                sum += kernel[i + radius];
            }
            for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
            float[] scratch = new float[base.length];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                double value = 0;
                for (int k = -radius; k <= radius; k++) if (x + k >= 0 && x
                        + k < size) value += base[y * size + x + k] * kernel[k + radius];
                scratch[y * size + x] = (float) value;
            }
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                double value = 0;
                for (int k = -radius; k <= radius; k++) if (y + k >= 0
                        && y + k < size) value += scratch[(y + k) * size
                        + x] * kernel[k + radius];
                base[y * size + x] = (float) value;
            }
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int color = Color.decode(PROFILE.colors().get(key.shade())).getRGB() & 0xffffff;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setRGB(x,
                y, (Math.round(base[y * size + x] * 255) << 24) | color);
        return new Sprite(image, -size / 2);
    }

    private static Profile load() {
        try (var input = PurpleParticleSprites.class.getResourceAsStream(
                "/dev/leverage/ui/purple-particles.json")) {
            if (input == null) throw new IllegalStateException(
                    "Falta el perfil de partículas moradas.");
            Profile profile = new Gson().fromJson(new InputStreamReader(input,
                    StandardCharsets.UTF_8), Profile.class);
            if (profile.colors().size() != 3 || profile.edgeProfile().size() != 9
                    || profile.coreDiameter() != 6 || profile.opacity() <= 0
                    || profile.opacity() > 1 || profile.edgeProfile().stream().anyMatch(
                    value -> !Double.isFinite(value) || value < 0
                    || value > 1)) throw new IllegalStateException(
                    "Perfil de partículas moradas no válido.");
            profile.colors().forEach(Color::decode);
            return profile;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("No se pudo cargar el perfil morado.",
                    exception);
        }
    }
}

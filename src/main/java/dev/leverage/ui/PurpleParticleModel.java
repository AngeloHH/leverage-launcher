package dev.leverage.ui;

import java.util.SplittableRandom;

// Purple appearance and full-width distribution, driven by the regular particle motion
// sampler.
final class PurpleParticleModel {
    record Particle(int id, double u, double pixels, double brightness,
            int shade, GlowParticleModel.Particle motion) { }
    record State(int id, double x, double y, double pixels, double opacity, int shade) { }
    static final long DEFAULT_SEED = 42;
    private PurpleParticleModel() { }

    static Particle particle(int index, long seed) {
        var random = new SplittableRandom(seed + 0x9e3779b97f4a7c15L * (index + 1L));
        var field = new SplittableRandom(seed);
        double u = .05 + .9 * fraction(field.nextDouble()
                + (index + 1) * .6180339887498949);
        // Preserve the approved appearance stream; these discarded values drove the old
        // local drift.
        random.nextLong();
        double pixels = random.nextDouble(6, 11);
        random.nextLong();
        random.nextLong();
        double brightness = random.nextDouble(.65, 1);
        int shade = random.nextInt(3);
        var regular = GlowParticleModel.particle(index, seed);
        // Position and style remain purple-specific; neutral variant two adds no
        // orange-style opacity.
        var motion = new GlowParticleModel.Particle(index + 1, "left",
                u, 1, 2, regular.riseSeconds(), regular.delaySeconds(),
                regular.swingSeconds(), regular.pulseSeconds());
        return new Particle(index + 1, u, pixels, brightness, shade, motion);
    }

    static State sample(Particle particle, double time,
            int width, int height, double spread) {
        return sample(particle, time, width, height, spread,
                height + GlowParticleModel.DATA.topOverflow());
    }

    static State sample(Particle particle, double time, int width,
            int height, double spread, double travel) {
        var state = GlowParticleModel.sample(particle.motion(),
                time, width, height, travel);
        double anchor = width * particle.u() + GlowParticleModel.DATA.coreSize() / 2;
        double x = anchor + (state.x()
                - anchor) * spread / GlowParticleModel.DATA.swingAmplitude();
        return new State(particle.id(), x, state.y(), particle.pixels() * state.scale(),
                state.opacity() * particle.brightness(), particle.shade());
    }

    private static double fraction(double value) { return value - Math.floor(value); }
}

package dev.leverage.ui;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;

// Numeric translation of the supplied HTML/CSS; independent of painting and frame rate.
final class GlowParticleModel {
    record Shadow(double x, double y, double blur, double spread,
            String color, double alpha) { }
    record Variant(int id, String core, double opacity, double filterBlur,
            boolean square, List<Shadow> shadows) {
        Variant { shadows = List.copyOf(shadows); }
    }
    record Particle(int id, String anchor, double fraction, double scale,
            int variant, double riseSeconds, double delaySeconds,
            double swingSeconds, double pulseSeconds) { }
    record Definition(double coreSize, double topOverflow, double referenceTravel,
            double swingAmplitude, double endScale, double pulseMinimum,
            List<Variant> variants, List<Particle> particles) {
        Definition {
            variants = List.copyOf(variants);
            particles = List.copyOf(particles);
        }
    }
    record State(int id, int variant, double x, double y,
            double scale, double opacity) { }
    static final Definition DATA = load();

    private GlowParticleModel() { }

    // The approved twenty stay unchanged. Additional particles are stable for a seed and
    // index.
    static Particle particle(int index, long seed) {
        if (index<DATA.particles().size()) return DATA.particles().get(index);
        var random = new SplittableRandom(seed + 0x9e3779b97f4a7c15L * (index + 1L));
        return new Particle(index + 1, random.nextBoolean() ? "left" : "right",
                random.nextDouble(.02, .23), 1.4 + .4 * random.nextInt(6),
                random.nextInt(1, 5), random.nextDouble(12, 72), -random.nextDouble(0,
                60), random.nextDouble(2, 4), random.nextDouble(1.2, 2.5));
    }

    static State sample(Particle particle, double time,
            int width, int height, double travel) {
        double local = time - particle.delaySeconds();
        if (local < 0) return new State(particle.id(), particle.variant(), 0, 0, 1, 0);
        double phase = (local % particle.riseSeconds()) / particle.riseSeconds();
        double raised = easeOut(phase);
        double scale = 1 + (DATA.endScale() - 1) * raised;
        double fade = phase < .15 ? easeOut(phase / .15) : phase <= .97
                ? 1 : 1 - easeOut((phase - .97) / .03);
        double swingPhase = (time % (
                2 * particle.swingSeconds())) / particle.swingSeconds();
        double swing = swingPhase <= 1 ? easeInOut(swingPhase)
                : easeInOut(2 - swingPhase);
        double pulsePhase = (time % particle.pulseSeconds()) / particle.pulseSeconds();
        double pulse = pulsePhase <= .5 ? 1 - (1 - DATA.pulseMinimum()) * easeInOut(
                2 * pulsePhase) : DATA.pulseMinimum() + (1
                - DATA.pulseMinimum()) * easeInOut(2 * pulsePhase - 1);
        double anchor = particle.anchor().equals("left")
                ? width * particle.fraction() + DATA.coreSize() / 2 : width * (1
                - particle.fraction()) - DATA.coreSize() / 2;
        double x = anchor + (-DATA.swingAmplitude()
                + 2 * DATA.swingAmplitude() * swing) * scale;
        double y = -DATA.topOverflow() + DATA.coreSize() / 2 + travel * (1 - raised);
        return new State(particle.id(), particle.variant(), x, y,
                particle.scale() * scale, Math.max(0, Math.min(1,
                fade * pulse * DATA.variants().get(particle.variant() - 1).opacity())));
    }

    static double easeOut(double value) { return bezier(value, 0, 0, .58, 1); }
    static double easeInOut(double value) { return bezier(value, .42, 0, .58, 1); }

    private static double bezier(double x, double x1, double y1, double x2, double y2) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 30; i++) {
            double t = (lo + hi) / 2, u = 1 - t;
            double position = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
            if (position < x) lo = t;
            else hi = t;
        }
        double t = (lo + hi) / 2, u = 1 - t;
        return 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t;
    }

    private static Definition load() {
        try (var input = GlowParticleModel.class.getResourceAsStream(
                "/dev/leverage/ui/glow-particles.json")) {
            if (input == null) throw new IllegalStateException(
                    "Faltan los datos de las partículas.");
            Definition definition = new Gson().fromJson(new InputStreamReader(input,
                    StandardCharsets.UTF_8), Definition.class);
            if (definition.particles().size() != 20 || definition.variants().size() != 4)
                    throw new IllegalStateException(
                    "El efecto debe contener 20 partículas y cuatro perfiles de brill"
                    + "o.");
            for (int i = 0; i < definition.particles().size(); i++) {
                var particle = definition.particles().get(i);
                if (particle.id() != i + 1 || particle.riseSeconds() <= 0
                        || particle.swingSeconds() <= 0 || particle.pulseSeconds() <= 0
                        || particle.variant() < 1 || particle.variant() > 4
                        || !List.of("left", "right").contains(particle.anchor()))
                        throw new IllegalStateException(
                        "Datos de partículas no válidos.");
            }
            return definition;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("No se pudieron cargar las partículas.",
                    exception);
        }
    }
}

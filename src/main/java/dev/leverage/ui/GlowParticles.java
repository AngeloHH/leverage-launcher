package dev.leverage.ui;

import javax.swing.JComponent;
import javax.swing.Timer;
import javax.accessibility.AccessibleContext;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Configurable, click-through orange particle overlay. Mutate on Swing's event-dispatch
// thread.
public final class GlowParticles extends JComponent {
    public static final int MAX_PARTICLES = 200;

    // Size and halo reach are multipliers; intensity and distance range from zero to one.
    public record Appearance(double size, double intensity,
            double distance, double glowReach) {
        public static final Appearance DEFAULT = new Appearance(1, 1, 0, 1);
        public Appearance {
            range("Tamaño", size, .25, 4);
            range("Intensidad", intensity, 0, 1);
            range("Lejanía", distance, 0, 1);
            range("Alcance del brillo", glowReach, 0, 3);
        }
        public Appearance withSize(double value) {
            return new Appearance(value, intensity, distance, glowReach);
        }
        public Appearance withIntensity(double value) {
            return new Appearance(size, value, distance, glowReach);
        }
        public Appearance withDistance(double value) {
            return new Appearance(size, intensity, value, glowReach);
        }
        public Appearance withGlowReach(double value) {
            return new Appearance(size, intensity, distance, value);
        }
        private static void range(String name, double value,
                double minimum, double maximum) {
            if (!Double.isFinite(value) || value < minimum || value > maximum)
                    throw new IllegalArgumentException(name + " debe estar entre "
                    + minimum + " y " + maximum + ".");
        }
    }

    private final Timer timer = new Timer(33, event -> tick());
    private final List<GlowParticleModel.Particle> particles
            = new ArrayList<>(GlowParticleModel.DATA.particles());
    private final List<Appearance> particleAppearances = new ArrayList<>(
            java.util.Collections.nCopies(particles.size(), Appearance.DEFAULT));
    private Appearance appearance = Appearance.DEFAULT;
    private long seed;
    private boolean animationEnabled = true;
    private double elapsedSeconds;
    private long startedAt;
    private int travelDistance;

    public GlowParticles() {
        setOpaque(false);
        setFocusable(false);
        getAccessibleContext().setAccessibleDescription(
                "Efecto decorativo de partículas luminosas");
        timer.setCoalesce(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED)
                    != 0) updateTimer();
        });
    }

    public boolean isAnimationEnabled() { return animationEnabled; }
    public void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
        updateTimer();
        repaint();
    }
    public double getTimeSeconds() {
        return elapsedSeconds + (timer.isRunning() ? (System.nanoTime()
                - startedAt) / 1_000_000_000d : 0);
    }
    // Seek for deterministic previews; setting a time never starts a hidden timer.
    public void setTimeSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException(
                "El tiempo debe ser finito y no negativo.");
        elapsedSeconds = seconds;
        if (timer.isRunning()) startedAt = System.nanoTime();
        repaint();
    }
    public int getTravelDistance() { return travelDistance; }
    // Zero fits the rise distance to this overlay. Use 5202 for the literal source CSS.
    public void setTravelDistance(int pixels) {
        if (pixels < 0 || pixels > 100_000) throw new IllegalArgumentException(
                "Recorrido fuera de rango.");
        travelDistance = pixels;
        repaint();
    }
    public float getIntensity() { return (float) appearance.intensity(); }
    public void setIntensity(float intensity) {
        setAppearance(appearance.withIntensity(intensity));
    }
    public double getSizeMultiplier() { return appearance.size(); }
    public void setSizeMultiplier(double value) {
        setAppearance(appearance.withSize(value));
    }
    public double getDistance() { return appearance.distance(); }
    public void setDistance(double value) {
        setAppearance(appearance.withDistance(value));
    }
    public double getGlowReach() { return appearance.glowReach(); }
    public void setGlowReach(double value) {
        setAppearance(appearance.withGlowReach(value));
    }
    public Appearance getAppearance() { return appearance; }
    public void setAppearance(Appearance appearance) {
        this.appearance = Objects.requireNonNull(appearance);
        repaint();
    }
    public int getParticleCount() { return particles.size(); }
    // Resizing the population does not restart the animation or change retained
    // particles.
    public void setParticleCount(int count) {
        if (count < 0 || count > MAX_PARTICLES) throw new IllegalArgumentException(
                "La cantidad debe estar entre 0 y " + MAX_PARTICLES + ".");
        while (particles.size() < count) {
            particles.add(GlowParticleModel.particle(particles.size(), seed));
            particleAppearances.add(Appearance.DEFAULT);
        }
        if (count < particles.size()) {
            particles.subList(count, particles.size()).clear();
            particleAppearances.subList(count, particleAppearances.size()).clear();
        }
        updateTimer();
        repaint();
    }
    public long getSeed() { return seed; }
    // Only additional, generated particles use the seed. The original twenty keep their
    // CSS values.
    public void setSeed(long seed) {
        this.seed = seed;
        for (int i = GlowParticleModel.DATA.particles().size(); i < particles.size();
                i++) particles.set(i, GlowParticleModel.particle(i, seed));
        repaint();
    }
    // Indices start at zero. The local appearance combines with the global one at paint
    // time.
    public Appearance getParticleAppearance(int index) {
        return particleAppearances.get(index);
    }
    public void setParticleAppearance(int index, Appearance appearance) {
        particleAppearances.set(index, Objects.requireNonNull(appearance));
        repaint();
    }
    public void resetConfiguration() {
        appearance = Appearance.DEFAULT;
        seed = 0;
        travelDistance = 0;
        particles.clear();
        particles.addAll(GlowParticleModel.DATA.particles());
        particleAppearances.clear();
        particleAppearances.addAll(java.util.Collections.nCopies(particles.size(),
                Appearance.DEFAULT));
        updateTimer();
        repaint();
    }
    GlowParticleModel.Particle particleAt(int index) { return particles.get(index); }
    @Override public boolean contains(int x, int y) { return false; }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (timer != null) updateTimer();
    }
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleGlowParticles();
        return accessibleContext;
    }
    protected class AccessibleGlowParticles extends AccessibleJComponent { }
    @Override public void removeNotify() {
        pauseClock();
        super.removeNotify();
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (getWidth() <= 0 || getHeight() <= 0 || appearance.intensity() == 0
                || particles.isEmpty()) return;
        double time = getTimeSeconds();
        double travel = travelDistance == 0 ? getHeight()
                + GlowParticleModel.DATA.topOverflow() : travelDistance;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Preserve DOM order, including the diffuse profiles; no randomization per
            // frame.
            for (int i = 0; i < particles.size(); i++) {
                var state = GlowParticleModel.sample(particles.get(i),
                        time, getWidth(), getHeight(), travel);
                GlowParticleSprites.paint(g, state, appearance,
                        particleAppearances.get(i));
            }
        } finally { g.dispose(); }
    }

    private void updateTimer() {
        if (animationEnabled && isShowing() && isEnabled() && !particles.isEmpty()) {
            if (!timer.isRunning()) {
                startedAt = System.nanoTime();
                timer.start();
            }
        } else pauseClock();
    }
    private void pauseClock() {
        if (timer.isRunning()) {
            elapsedSeconds = getTimeSeconds();
            timer.stop();
        }
    }
    private void tick() {
        if (!animationEnabled || !isShowing() || !isEnabled() || particles.isEmpty()) {
            pauseClock();
            return;
        }
        repaint();
    }
    boolean isTimerRunning() { return timer.isRunning(); }
}

package dev.leverage.ui;

import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Independent transparent particle field. No child, button, padding, emitter or opaque
// backdrop.
public final class PurpleParticles extends JComponent {
    public static final int MAX_PARTICLES = 200;
    public record Appearance(double size, double intensity,
            double distance, double glowReach) {
        public static final Appearance DEFAULT = new Appearance(1, 1, 0, 1);
        public Appearance {
            range("Tamaño", size, .25, 4);
            range("Intensidad", intensity, 0, 1);
            range("Lejanía", distance, 0, 1);
            range("Alcance", glowReach, 0, 3);
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
    }
    private final Timer timer = new Timer(33, event -> tick());
    private final List<PurpleParticleModel.Particle> particles = new ArrayList<>();
    private final List<Appearance> appearances = new ArrayList<>();
    private Appearance appearance = Appearance.DEFAULT;
    private boolean animationEnabled = true;
    private double elapsedSeconds, spread
            = GlowParticleModel.DATA.swingAmplitude(), speed = 1;
    private int travelDistance;
    private long startedAt, seed = PurpleParticleModel.DEFAULT_SEED;

    public PurpleParticles() {
        setOpaque(false);
        setFocusable(false);
        getAccessibleContext().setAccessibleDescription(
                "Capa decorativa de partículas moradas libres");
        resizeParticles(20);
        timer.setCoalesce(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED)
                    != 0) updateTimer();
        });
    }
    public int getParticleCount() { return particles.size(); }
    public void setParticleCount(int count) {
        if (count < 0 || count > MAX_PARTICLES) throw new IllegalArgumentException(
                "Cantidad fuera de rango: 0–" + MAX_PARTICLES);
        resizeParticles(count);
        updateTimer();
        repaint();
    }
    private void resizeParticles(int count) {
        while (particles.size() < count) {
            particles.add(PurpleParticleModel.particle(particles.size(), seed));
            appearances.add(Appearance.DEFAULT);
        }
        if (count < particles.size()) {
            particles.subList(count, particles.size()).clear();
            appearances.subList(count, appearances.size()).clear();
        }
    }
    public Appearance getAppearance() { return appearance; }
    public void setAppearance(Appearance value) {
        appearance = Objects.requireNonNull(value);
        repaint();
    }
    public Appearance getParticleAppearance(int index) { return appearances.get(index); }
    public void setParticleAppearance(int index, Appearance value) {
        appearances.set(index, Objects.requireNonNull(value));
        repaint();
    }
    public double getSizeMultiplier() { return appearance.size(); }
    public void setSizeMultiplier(double value) {
        setAppearance(appearance.withSize(value));
    }
    public double getIntensity() { return appearance.intensity(); }
    public void setIntensity(double value) {
        setAppearance(appearance.withIntensity(value));
    }
    public double getDistance() { return appearance.distance(); }
    public void setDistance(double value) {
        setAppearance(appearance.withDistance(value));
    }
    public double getGlowReach() { return appearance.glowReach(); }
    public void setGlowReach(double value) {
        setAppearance(appearance.withGlowReach(value));
    }
    public double getSpread() { return spread; }
    public void setSpread(double pixels) {
        range("Oscilación", pixels, 0, 96);
        spread = pixels;
        repaint();
    }
    public int getTravelDistance() { return travelDistance; }
    // Zero fits the rise to panel height + 110 px, exactly as for regular particles.
    public void setTravelDistance(int pixels) {
        if (pixels < 0 || pixels > 100_000) throw new IllegalArgumentException(
                "Recorrido fuera de rango.");
        travelDistance = pixels;
        repaint();
    }
    public double getSpeed() { return speed; }
    public void setSpeed(double value) {
        range("Velocidad", value, .1, 4);
        elapsedSeconds = getTimeSeconds();
        startedAt = System.nanoTime();
        speed = value;
        repaint();
    }
    public long getSeed() { return seed; }
    public void setSeed(long value) {
        seed = value;
        for (int i = 0; i < particles.size(); i++) particles.set(i,
                PurpleParticleModel.particle(i, seed));
        repaint();
    }
    public boolean isAnimationEnabled() { return animationEnabled; }
    public void setAnimationEnabled(boolean value) {
        animationEnabled = value;
        updateTimer();
        repaint();
    }
    public double getTimeSeconds() {
        return elapsedSeconds + (timer.isRunning() ? (System.nanoTime()
                - startedAt) / 1_000_000_000d * speed : 0);
    }
    public void setTimeSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException(
                "El tiempo debe ser finito y no negativo.");
        elapsedSeconds = seconds;
        if (timer.isRunning()) startedAt = System.nanoTime();
        repaint();
    }
    public void resetConfiguration() {
        setSpeed(1);
        appearance = Appearance.DEFAULT;
        spread = GlowParticleModel.DATA.swingAmplitude();
        travelDistance = 0;
        seed = PurpleParticleModel.DEFAULT_SEED;
        particles.clear();
        appearances.clear();
        resizeParticles(20);
        updateTimer();
        repaint();
    }
    @Override public boolean contains(int x, int y) { return false; }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (timer != null) updateTimer();
    }
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext
                = new AccessiblePurpleParticles();
        return accessibleContext;
    }
    protected class AccessiblePurpleParticles extends AccessibleJComponent { }
    @Override public void removeNotify() {
        pauseClock();
        super.removeNotify();
    }
    @Override protected void paintComponent(Graphics graphics) {
        if (getWidth() <= 0 || getHeight() <= 0 || appearance.intensity() == 0
                || particles.isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            double time = getTimeSeconds();
            double travel = travelDistance == 0 ? getHeight()
                    + GlowParticleModel.DATA.topOverflow() : travelDistance;
            for (int i = 0; i < particles.size(); i++) PurpleParticleSprites.paint(g,
                    PurpleParticleModel.sample(particles.get(i), time, getWidth(),
                    getHeight(), spread, travel), appearance, appearances.get(i));
        } finally { g.dispose(); }
    }
    private boolean shouldAnimate() {
        return animationEnabled && isShowing() && isEnabled() && !particles.isEmpty();
    }
    private void updateTimer() {
        if (shouldAnimate()) {
            if (!timer.isRunning()) {
                startedAt = System.nanoTime();
                timer.start();
            }
        }
        else pauseClock();
    }
    private void pauseClock() {
        if (timer.isRunning()) {
            elapsedSeconds = getTimeSeconds();
            timer.stop();
        }
    }
    private void tick() {
        if (shouldAnimate()) repaint();
        else pauseClock();
    }
    boolean isTimerRunning() { return timer.isRunning(); }
    PurpleParticleModel.Particle particleAt(int index) { return particles.get(index); }
    private static void range(String name, double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum)
                throw new IllegalArgumentException(name + " debe estar entre "
                + minimum + " y " + maximum + ".");
    }
}

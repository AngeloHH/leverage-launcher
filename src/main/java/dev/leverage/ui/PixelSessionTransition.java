package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.function.BooleanSupplier;

// A live, resize-safe block curtain. All state changes belong on Swing's event thread.
final class PixelSessionTransition extends JPanel {
    static final int BLOCK_SIZE = PlayerAccountCard.COMPACT_AVATAR_SIZE;
    static final int COVER_MS = 620, HOLD_MS = 80, REVEAL_MS = 620;
    enum Phase { IDLE, COVER, HOLD, REVEAL }
    private final JPanel pages;
    private final JLayer<JPanel> layer;
    private final Timer timer;
    private final BooleanSupplier animationAllowed;
    private Phase phase = Phase.IDLE;
    private double progress;
    private long started;
    private Runnable pending;
    private boolean ready;

    PixelSessionTransition(CardLayout layout) { this(layout, null); }
    PixelSessionTransition(CardLayout layout, BooleanSupplier animationAllowed) {
        super(new BorderLayout());
        setName("session-transition");
        setBackground(JourneyStyle.INK);
        this.animationAllowed = animationAllowed == null ? () -> isShowing()
                && !GraphicsEnvironment.isHeadless() : animationAllowed;
        pages = new JPanel(layout);
        pages.setOpaque(true);
        pages.setBackground(JourneyStyle.INK);
        layer = new JLayer<>(pages, new LayerUI<JPanel>() {
            @Override public void paint(Graphics graphics, JComponent component) {
                super.paint(graphics, component);
                paintCurtain(graphics, component.getWidth(), component.getHeight());
            }
            @Override public void eventDispatched(AWTEvent event,
                    JLayer<? extends JPanel> source) {
                if (isActive() && event instanceof InputEvent input) input.consume();
            }
        });
        layer.setLayerEventMask(AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK |
                AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        add(layer, BorderLayout.CENTER);
        timer = new Timer(16, event -> advance(System.nanoTime()));
        timer.setCoalesce(true);
    }
    JPanel pages() { return pages; }
    Phase phase() { return phase; }
    boolean isActive() { return phase != Phase.IDLE; }
    private boolean canAnimate() { return animationAllowed.getAsBoolean(); }

    // Start hiding the neutral startup scene while session recovery runs independently.
    void coverStartup() { cover(canAnimate()); }
    private void cover(boolean animate) {
        cancel();
        phase = animate ? Phase.COVER : Phase.HOLD;
        progress = animate ? 0 : 1;
        started = System.nanoTime();
        blockInput(true);
        if (animate) timer.start();
        layer.repaint();
    }
    void transition(Runnable change) { transition(change, canAnimate()); }
    // Explicit animation selection also permits deterministic, windowless preview frames.
    void transition(Runnable change, boolean animate) {
        if (!animate) {
            showImmediately(change);
            return;
        }
        cover(true);
        pending = change;
    }
    // Reveal whichever page session recovery selected, including access errors.
    void revealStartup(Runnable change) {
        if (!canAnimate()) {
            showImmediately(change);
            return;
        }
        if (phase == Phase.IDLE) {
            transition(change);
            return;
        }
        pending = change;
        if (phase == Phase.HOLD) commit();
        timer.start();
        layer.repaint();
    }
    void showImmediately(Runnable change) {
        cancel();
        change.run();
        revalidate();
        repaint();
    }
    void cancel() {
        timer.stop();
        pending = null;
        ready = false;
        phase = Phase.IDLE;
        progress = 0;
        blockInput(false);
        layer.repaint();
    }
    private void blockInput(boolean blocked) {
        layer.getGlassPane().setVisible(blocked);
        layer.setCursor(Cursor.getPredefinedCursor(blocked
                ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }
    private void commit() {
        if (pending == null) return;
        Runnable change = pending;
        pending = null;
        ready = true;
        try {
            change.run();
            pages.revalidate();
            pages.repaint();
        }
        catch (RuntimeException | Error failure) {
            cancel();
            throw failure;
        }
    }
    private void advance(long now) {
        double elapsed = (now - started) / 1_000_000d;
        if (phase == Phase.COVER) {
            progress = Math.min(1, elapsed / COVER_MS);
            if (progress >= 1) {
                phase = Phase.HOLD;
                started = now;
                commit();
                if (!ready) timer.stop();
            }
        } else if (phase == Phase.HOLD) {
            if (ready && elapsed >= HOLD_MS) {
                phase = Phase.REVEAL;
                progress = 0;
                started = now;
            }
        } else if (phase == Phase.REVEAL) {
            progress = Math.min(1, elapsed / REVEAL_MS);
            if (progress >= 1) cancel();
        }
        layer.repaint();
    }
    // Stop the real-time clock and advance this phase deterministically for visual
    // verification.
    void previewElapsed(int milliseconds) {
        timer.stop();
        advance(started + milliseconds * 1_000_000L);
        timer.stop();
    }
    static int blockSide(int column, int row, int columns,
            double progress, boolean revealing) {
        if (progress <= 0) return revealing ? BLOCK_SIZE : 0;
        if (progress >= 1) return revealing ? 0 : BLOCK_SIZE;
        double horizontal = columns <= 1 ? 0 : (columns - 1
                - column) / (double)(columns - 1);
        int hash = (column * 374761393 + row * 668265263) ^ 0x51ed270b;
        hash = (hash ^ (hash >>> 13)) * 1274126177;
        double stagger = (hash & 0xffff) / 65535d * .12;
        double local = Math.max(0, Math.min(1, (progress
                - horizontal * .64 - stagger) / .24));
        double eased = local * local * (3 - 2 * local);
        return (int) Math.round(BLOCK_SIZE * (revealing ? 1 - eased : eased));
    }
    private void paintCurtain(Graphics graphics, int width, int height) {
        if (!isActive()) return;
        Graphics2D g = (Graphics2D) graphics.create(); try {
            g.setColor(JourneyStyle.TABLE);
            if (phase == Phase.HOLD) {
                g.fillRect(0, 0, width, height);
                if (!ready) {
                    g.setColor(JourneyStyle.PAPER);
                    g.setFont(LauncherFonts.forLocale(LauncherFonts.minecraftSeven(14)));
                    String message = t("auth.transitionRecovering");
                    var metrics = g.getFontMetrics();
                    g.drawString(I18n.resolve(message), (width
                            - metrics.stringWidth(I18n.resolve(message))) / 2,
                            (height - metrics.getHeight()) / 2 + metrics.getAscent());
                }
                return;
            }
            int columns = (width + BLOCK_SIZE - 1) / BLOCK_SIZE,
                    rows = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
            for (int row = 0; row < rows; row++) for (int column = 0;
                    column < columns; column++) {
                int side = blockSide(column, row, columns, progress,
                        phase == Phase.REVEAL), inset = (BLOCK_SIZE - side) / 2;
                if (side > 0) g.fillRect(column * BLOCK_SIZE + inset,
                        row * BLOCK_SIZE + inset, side, side);
            }
        } finally { g.dispose(); }
    }
    @Override public void removeNotify() {
        cancel();
        super.removeNotify();
    }
}

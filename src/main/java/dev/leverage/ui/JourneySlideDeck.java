package dev.leverage.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

// Native horizontal scene travel with a deterministic, opaque interlocking cell seam.
final class JourneySlideDeck extends JPanel {
    private JComponent current, pending;
    private BufferedImage before, after;
    private Color beforeColor, afterColor;
    private boolean reverse, enabled = true, seam, previewMotion;
    private long started, seed;
    private double progress = 1;
    private int lastWidth, lastHeight;
    private final Timer timer = new Timer(16, event -> pulse());
    JourneySlideDeck() {
        super(null);
        setBackground(JourneyStyle.INK);
        timer.setCoalesce(true);
    }
    void setAnimationEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) finish();
    }
    void showPage(JComponent page, boolean reverse, boolean seam, long seed) {
        if (current == page) return;
        if (progress < 1) {
            pending = page;
            finish();
        }
        JComponent old = current;
        boolean animate = enabled && (isShowing() || previewMotion)
                && old != null && getWidth() > 0 && getHeight() > 0;
        if (animate) {
            before = JourneyStyle.snapshot(old, getWidth(), getHeight());
            beforeColor = old.getBackground();
        }
        removeAll();
        current = page;
        add(page);
        page.setBounds(0, 0, getWidth(), getHeight());
        JourneyStyle.layoutTree(page);
        if (!animate) {
            progress = 1;
            page.setVisible(true);
            revalidate();
            repaint();
            return;
        }
        after = JourneyStyle.snapshot(page, getWidth(), getHeight());
        afterColor = page.getBackground();
        page.setVisible(false);
        this.reverse = reverse;
        this.seam = seam;
        this.seed = seed;
        progress = 0;
        started = System.nanoTime();
        timer.start();
        repaint();
    }
    private void pulse() {
        if (!isShowing()) {
            finish();
            return;
        }
        progress = Math.min(1, (System.nanoTime() - started) / 650_000_000d);
        if (progress >= 1) finish();
        else repaint();
    }
    void previewPageForTest(JComponent page, boolean reverse,
            boolean seam, long seed, double sample) {
        previewMotion = true;
        try {
            showPage(page, reverse, seam, seed);
        } finally {
            previewMotion = false;
        }
        timer.stop();
        progress = Math.max(0, Math.min(1, sample));
        if (progress >= 1) finish();
        else repaint();
    }
    private void finish() {
        timer.stop();
        progress = 1;
        before = null;
        after = null;
        if (current != null) current.setVisible(true);
        pending = null;
        revalidate();
        repaint();
    }
    @Override public void doLayout() {
        if ((lastWidth != getWidth() || lastHeight != getHeight())
                && progress < 1) finish();
        lastWidth = getWidth();
        lastHeight = getHeight();
        if (current != null) current.setBounds(0, 0, getWidth(), getHeight());
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (progress >= 1 || before == null || after == null) return;
        Graphics2D g = (Graphics2D) graphics.create(); try {
            int seamWidth = seam ? 160 : 0;
            var frame = JourneyMotion.horizontal(getWidth(),
                    seamWidth, reverse, progress);
            int oldX = (int) Math.round(frame.outgoingX()),
                    newX = (int) Math.round(frame.incomingX());
            g.drawImage(before, oldX, 0, null);
            g.drawImage(after, newX, 0, null);
            if (seam) {
                int seamX = (reverse ? newX : oldX) + getWidth();
                PixelSlideSeam.paint(g, seamX, 0, seamWidth, getHeight(),
                        32, seed, beforeColor, afterColor, reverse);
            }
        } finally { g.dispose(); }
    }
    @Override public void removeNotify() {
        finish();
        super.removeNotify();
    }
}

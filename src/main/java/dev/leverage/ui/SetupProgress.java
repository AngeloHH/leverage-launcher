package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

// Numbered square indicator for the current access route.
final class SetupProgress extends JComponent {
    private static final int WIDTH = 467;
    private static final int HEIGHT = 38;
    private static final int BOX_SIZE = 36;

    private static final Color TRACK = new Color(137, 137, 137);
    private static final Color GREEN = new Color(54, 176, 48);
    private static final Color COMPLETED = new Color(0, 133, 66);
    private static final Color BOX = new Color(30, 30, 31);

    private final int currentStep;
    private final int stepCount;

    SetupProgress(int currentStep) {
        this(currentStep, t("auth.server"), t("auth.signIn"));
    }

    SetupProgress(int currentStep, String... steps) {
        if (steps == null || steps.length < 2 || currentStep < 1
                || currentStep > steps.length) {
            throw new IllegalArgumentException(
                    "El paso debe pertenecer a la ruta de acceso.");
        }
        this.currentStep = currentStep;
        this.stepCount = steps.length;
        Dimension size = new Dimension(WIDTH, HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        setAlignmentX(0.5f);
        setOpaque(false);
        setToolTipText(t("auth.step", Integer.toString(currentStep),
                Integer.toString(stepCount), steps[currentStep - 1]));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            int centerY = HEIGHT / 2;
            int firstCenter = BOX_SIZE / 2 + 36;
            int lastCenter = WIDTH - firstCenter;
            int spacing = (lastCenter - firstCenter) / (stepCount - 1);
            int activeCenter = firstCenter + spacing * (currentStep - 1);

            g.setColor(TRACK);
            g.fillRect(0, centerY - 1, WIDTH, 2);
            g.setColor(GREEN);
            g.fillRect(0, centerY - 1, activeCenter + 1, 2);

            for (int step = 1; step <= stepCount; step++) {
                int centerX = firstCenter + spacing * (step - 1);
                int x = centerX - BOX_SIZE / 2;
                int y = centerY - BOX_SIZE / 2;
                boolean completed = step < currentStep;
                boolean active = step == currentStep;

                g.setColor(completed ? COMPLETED : BOX);
                g.fillRect(x, y, BOX_SIZE, BOX_SIZE);
                if (!completed) {
                    g.setColor(active ? GREEN : TRACK);
                    int border = active ? 2 : 1;
                    for (int i = 0; i < border; i++) {
                        g.drawRect(x + i, y + i, BOX_SIZE - 1 - i * 2,
                                BOX_SIZE - 1 - i * 2);
                    }
                }

                String number = Integer.toString(step);
                g.setFont(LauncherFonts.forLocale(LauncherFonts.minecraftSeven(18f)));
                FontMetrics metrics = g.getFontMetrics();
                int textX = centerX - metrics.stringWidth(I18n.resolve(number)) / 2;
                int textY = centerY + (metrics.getAscent() - metrics.getDescent()) / 2;
                g.setColor(Color.WHITE);
                g.drawString(I18n.resolve(number), textX, textY);
            }
        } finally {
            g.dispose();
        }
    }
}

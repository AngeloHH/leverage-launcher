package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

// Atlas presentation only: the real authentication cards remain owned by LauncherWindow.
final class AtlasSetupPanel extends JPanel {
    static final Color MINT = new Color(0x74c8b1);
    private final Stage stage;
    private final Header header = new Header();
    private boolean verifiedRegistration;
    private static final String[] LOGIN_STEPS = { t("auth.server"), t("auth.signIn") };
    private static final String[] REGISTER_STEPS = { t("auth.server"),
            t("auth.emailPassword"), t("auth.username"),
            t("auth.verification"), t("auth.signIn") };

    AtlasSetupPanel(JPanel cards) {
        super(new BorderLayout());
        setBackground(new Color(0x171615));
        stage = new Stage(cards);
        add(header, BorderLayout.NORTH);
        add(stage, BorderLayout.CENTER);
    }

    void showPhase(String screen, boolean animate) {
        boolean downloads = screen.equals("download-source") || screen.equals("ready");
        stage.setPhase(downloads ? 1 : 0, animate);
        if (screen.equals("server") || screen.equals(
                "register-details")) verifiedRegistration = false;
        boolean registration = screen.startsWith("register-") || screen.equals(
                "verify-email") || screen.equals("login") && verifiedRegistration;
        int step = switch (screen) {
            case "server" -> 1;
            case "register-username" -> 3;
            case "verify-email" -> 4;
            case "login" -> registration ? 5 : 2;
            default -> 2;
        };
        header.setStep(step, registration ? REGISTER_STEPS : LOGIN_STEPS);
        // Download sources are configured after authentication, not another access step.
        header.progress.setVisible(!downloads);
        stage.scroll.getViewport().setViewPosition(new Point());
    }

    void registrationVerified() { verifiedRegistration = true; }

    // Cards always follow the available column width, never creating a horizontal
    // scrollbar.
    static final class FormCards extends JPanel implements Scrollable {
        FormCards(CardLayout layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 32;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null
                    && getPreferredSize().height < getParent().getHeight();
        }
    }

    private static BufferedImage read(String path) {
        try (var input = AtlasSetupPanel.class.getResourceAsStream(path)) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException e) { return null; }
    }

    private static final class Header extends JComponent {
        private final BufferedImage logo = read("/dev/leverage/ui/images/leverage.png");
        private SetupProgress progress;
        Header() {
            setLayout(null);
            setPreferredSize(new Dimension(800, 88));
            setStep(1, LOGIN_STEPS);
        }
        void setStep(int step, String... steps) {
            if (progress != null) remove(progress);
            progress = new SetupProgress(step, steps);
            progress.setName("setup-progress");
            add(progress);
            revalidate();
            doLayout();
            repaint();
        }
        @Override public void doLayout() {
            Dimension size = progress.getPreferredSize();
            int available = getWidth() - 220 - 32;
            progress.setBounds(220 + Math.max(0, (available - size.width) / 2),
                    (getHeight() - size.height) / 2, size.width, size.height);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(new Color(0x171615));
                g.fillRect(0, 0, getWidth(), getHeight());
                int logoWidth = 152;
                if (logo != null) g.drawImage(logo, 28, 29, logoWidth, 28, null);
                else {
                    g.setFont(LauncherFonts.forLocale(LauncherFonts.minecraftTen(27)));
                    g.setColor(Color.WHITE);
                    g.drawString(I18n.resolve("LEVERAGE"), 28, 53);
                }
            } finally { g.dispose(); }
        }
    }

    private static final class Stage extends JPanel {
        private final BufferedImage artwork = read(
                "/dev/leverage/ui/journey/ocean-exploration.png");
        private final JScrollPane scroll;
        private double position, origin, destination;
        private long started;
        private final Timer motion;

        Stage(JPanel cards) {
            super(null);
            setBackground(MINT);
            scroll = new JScrollPane(cards, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
            add(scroll);
            motion = new Timer(16, e -> {
                double t = Math.min(1, (System.nanoTime() - started) / 650_000_000.0);
                double eased = t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                position = origin + (destination - origin) * eased;
                doLayout();
                repaint();
                if (t >= 1)((Timer) e.getSource()).stop();
            });
        }
        void setPhase(double phase, boolean animate) {
            destination = phase;
            if (!animate || !isShowing() || Boolean.getBoolean("leverage.reduceMotion")) {
                motion.stop();
                position = phase;
                doLayout();
                repaint();
            } else {
                origin = position;
                started = System.nanoTime();
                motion.start();
            }
        }
        @Override public void removeNotify() {
            motion.stop();
            super.removeNotify();
        }
        @Override public void doLayout() {
            int half = getWidth() / 2;
            scroll.setBounds((int) Math.round(position * half), 0, half, getHeight());
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int half = getWidth() / 2, artX = (int) Math.round((1 - position) * half);
                g.setClip(artX, 0, getWidth() - half, getHeight());
                if (artwork != null) {
                    double scale = Math.max((double) half / artwork.getWidth(),
                            (double) getHeight() / artwork.getHeight());
                    int w = (int) Math.ceil(artwork.getWidth() * scale),
                            h = (int) Math.ceil(artwork.getHeight() * scale);
                    g.drawImage(artwork, artX + (half - w) / 2,
                            (getHeight() - h) / 2, w, h, null);
                }
                g.setColor(new Color(0, 0, 0, 30));
                g.fillRect(artX, 0, half, getHeight());
                g.setColor(MINT);
                int c = 28;
                g.fillRect(artX, 0, c * 2, c);
                g.fillRect(artX, c, c, c);
                g.fillRect(artX + half - c * 2, getHeight() - c, c * 2, c);
                g.fillRect(artX + half - c, getHeight() - c * 2, c, c);
                g.fillRect(artX + c, getHeight() - c * 2, c * 2, c);
            } finally { g.dispose(); }
        }
    }
}

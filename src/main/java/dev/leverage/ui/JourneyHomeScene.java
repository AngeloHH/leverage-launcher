package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;

// The approved copper landscape: one editorial hero and a compact real launch plate.
final class JourneyHomeScene extends JPanel {
    private final InstanceSelectionPanel instances;
    private final JPanel launch = new JPanel(null);
    private final JLabel versionLabel = JourneyStyle.label(t("home.yourVersion"),
            12, JourneyStyle.PAPER, false);
    private final PixelButton play = new PixelButton(t("game.play"),
            PixelButton.Variant.PRIMARY);
    private final JLabel note = JourneyStyle.label(t("home.verified"),
            10, new Color(0xb9c5c9), false);
    private final JButton explore;
    JourneyHomeScene(InstanceSelectionPanel instances,
            Runnable playAction, Runnable news) {
        super(null);
        this.instances = instances;
        setName("journey-home-scene");
        setBackground(new Color(0x0c141d));
        launch.setName("home-launch-plate");
        launch.setBackground(JourneyStyle.NAVY);
        instances.setCompact(true);
        launch.add(versionLabel);
        launch.add(instances);
        launch.add(play);
        launch.add(note);
        add(launch);
        play.setName("home-play");
        play.setFont(LauncherFonts.minecraftSeven(20));
        play.addActionListener(event -> playAction.run());
        explore = new JButton() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setColor(new Color(0x101b25));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(new Color(255, 255, 255, 45));
                    g.fillRect(0, 0, 3, getHeight());
                    JourneyStyle.text(g, t("home.explore"), LauncherFonts.minecraftSeven(
                            10), new Color(0xffc49b), 16, 14, getWidth() - 32, 14);
                    JourneyStyle.text(g, t("home.discover"), LauncherFonts.minecraftTen(
                            26), JourneyStyle.PAPER, 16, 34, getWidth() - 32, 27);
                    JourneyStyle.text(g, t("home.readStories"),
                            LauncherFonts.minecraftSeven(12), JourneyStyle.GREEN,
                            16, 94, getWidth() - 32, 18);
                    g.fillRect(126, 100, 8, 2);
                    g.fillRect(132, 100, 2, 8);
                    g.fillRect(124, 108, 2, 2);
                    g.fillRect(126, 106, 2, 2);
                    g.fillRect(128, 104, 2, 2);
                    g.fillRect(130, 102, 2, 2);
                    if (hasFocus()) {
                        g.setColor(JourneyStyle.GREEN);
                        g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                } finally { g.dispose(); }
            }
        };
        explore.setName("home-explore-news");
        explore.getAccessibleContext().setAccessibleName(t("home.readStoriesAccessible"));
        explore.setBorderPainted(false);
        explore.setContentAreaFilled(false);
        explore.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        explore.addActionListener(event -> news.run());
        add(explore);
    }
    @Override public void doLayout() {
        int margin = getWidth() < 900 ? 36 : 56;
        int copyWidth = Math.min(470, Math.max(300, (int)(getWidth() * .55)));
        int launchHeight = 118;
        launch.setBounds(margin, Math.max(245, getHeight() - launchHeight - 32),
                Math.min(copyWidth, getWidth() - 2 * margin), launchHeight);
        versionLabel.setBounds(18, 12, 180, 18);
        int playWidth = 112, gap = 12, inner = launch.getWidth() - 36;
        instances.setBounds(18, 34, Math.max(140, inner - playWidth - gap), 48);
        play.setBounds(18 + inner - playWidth, 34, playWidth, 48);
        note.setBounds(18, 92, inner, 18);
        explore.setVisible(getWidth() >= 900 && getHeight() >= 460);
        explore.setBounds(getWidth() - 282, getHeight() - 164, 242, 130);
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            JourneyStyle.cover(g, JourneyStyle.COPPER, new Rectangle(0, 0,
                    getWidth(), getHeight()), null);
            g.setPaint(new LinearGradientPaint(0, 0, Math.max(1, getWidth()), 0,
                    new float[] { 0, .28f, .68f, 1 }, new Color[] { new Color(7,
                    17, 25, 230), new Color(7, 17, 25, 199), new Color(7,
                    17, 25, 38), new Color(7, 17, 25, 10) }));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setPaint(new GradientPaint(0, getHeight() * .48f, new Color(9, 21,
                    28, 0), 0, getHeight(), new Color(9, 21, 28, 240)));
            g.fillRect(0, 0, getWidth(), getHeight());
            int x = getWidth() < 900 ? 36 : 56, top = getHeight() < 500 ? 24 : 42;
            int logoWidth = getHeight() < 500 ? 210 : 246;
            if (JourneyStyle.LOGO != null) g.drawImage(JourneyStyle.LOGO, x, top,
                    logoWidth, (int)(logoWidth * JourneyStyle.LOGO.getHeight() / (
                    double) JourneyStyle.LOGO.getWidth()), null);
            int y = top + 62;
            JourneyStyle.text(g, "JAVA EDITION", LauncherFonts.minecraftSeven(13),
                    new Color(0xffc49b), x, y, 470, 18);
            y += 28;
            float size = getHeight() < 480 ? 48 : 64;
            JourneyStyle.text(g, t("home.world"), LauncherFonts.minecraftTen(size),
                    JourneyStyle.PAPER, x, y, Math.min(470,
                    getWidth() - 2 * x), (int) size);
            y += (int) size * 2 + 12;
            JourneyStyle.text(g, t("home.tagline"), LauncherFonts.minecraftSeven(17),
                    JourneyStyle.PAPER, x, y, 470, 24);
        } finally { g.dispose(); }
    }
}

package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;

// Green collection table with paper interior and the single shared version model.
final class JourneyCollectionPanel extends JPanel implements Scrollable {
    private final JPanel paper = new JPanel(null);
    private final JPanel launch = new JPanel(null) {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            graphics.setColor(new Color(0, 0, 0, 100));
            graphics.fillRect(0, getHeight() - 4, getWidth(), 4);
            }
    };
    private final JComponent artwork = new JComponent() {
        @Override protected void paintComponent(Graphics graphics) {
            JourneyStyle.cover((Graphics2D) graphics, JourneyStyle.COPPER,
                    new Rectangle(0, 0, getWidth(), getHeight()), null);
        }
    };
    private final SkinGalleryPanel gallery;
    private final PixelDropdown<String> version;
    private final PixelButton play = new PixelButton(t("game.play"),
            PixelButton.Variant.PRIMARY);
    private final JLabel bandTitle = JourneyStyle.label(t("skins.nextGame"),
            16, new Color(0xC9AD93), false);
    private final JLabel versionLabel = JourneyStyle.label(t("skins.version"),
            12, JourneyStyle.PAPER, false);
    private final JLabel title = JourneyStyle.label(t("skins.choose"),
            28, JourneyStyle.INK, true);
    private final JButton add;
    JourneyCollectionPanel(InstanceSelectionPanel instances,
            SkinGalleryPanel gallery, Runnable playAction) {
        super(null);
        this.gallery = gallery;
        setName("journey-skin-collection");
        setBackground(JourneyStyle.TABLE);
        paper.setBackground(JourneyStyle.PAPER);
        paper.setName("skins-paper");
        add(paper);
        version = instances.newSelector();
        version.setName("skins-installed-version");
        add = gallery.importButton();
        launch.setName("skins-launch-band");
        launch.setBackground(new Color(0x262423));
        paper.add(launch);
        launch.add(artwork);
        launch.add(bandTitle);
        launch.add(versionLabel);
        launch.add(version);
        launch.add(play);
        paper.add(title);
        paper.add(add);
        paper.add(gallery);
        play.setFont(LauncherFonts.minecraftSeven(18));
        play.setName("skins-play");
        play.addActionListener(e -> playAction.run());
    }
    private int galleryHeight(int width) {
        int pad = getWidth() > 0 && getWidth() < 700 ? 16 : 24;
        gallery.setOverflowInset(pad);
        gallery.setSize(Math.max(1, width + pad * 2), 1);
        return gallery.getPreferredSize().height;
    }
    @Override public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 1040;
        int outer = width < 700 ? 18 : 30, pad = width < 700 ? 16 : 24;
        return new Dimension(width, 20 + 24 + 132 + 66 + galleryHeight(width
                - outer * 2 - pad * 2) + 28 + 24);
        }
    @Override public void doLayout() {
        int outer = getWidth() < 700 ? 18 : 30, pad = getWidth() < 700 ? 16 : 24,
                paperWidth = Math.max(1, getWidth() - outer * 2),
                inner = paperWidth - pad * 2;
        paper.setBounds(outer, 20, paperWidth, Math.max(getHeight() - 44,
                24 + 132 + 66 + galleryHeight(inner) + 28));
        launch.setBounds(pad, 20, inner, 132);
        int imageWidth = Math.min(140, inner / 4), copyX = imageWidth + 20,
                available = inner - copyX - 20;
        artwork.setBounds(0, 0, imageWidth, 128);
        bandTitle.setBounds(copyX, 12, available, 24);
        versionLabel.setBounds(copyX, 43, available, 18);
        int buttonWidth = Math.min(148, Math.max(100, available / 3)),
                selectorWidth = Math.max(1, available - buttonWidth - 12);
        version.setBounds(copyX, 65, selectorWidth, 48);
        play.setBounds(copyX + selectorWidth + 12, 65, buttonWidth, 48);
        title.setBounds(pad, 166, inner - 150, 38);
        add.setBounds(paperWidth - pad - 140, 166, 140, 38);
        // Borrow the existing paper margins for overflow; card positions and widths stay
        // unchanged.
        gallery.setBounds(0, 220, paperWidth, galleryHeight(inner));
    }
    @Override protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int x = paper.getX(), y = paper.getY(), w = paper.getWidth(),
                    h = paper.getHeight();
            g.setColor(new Color(0x244b1f));
            g.fillRect(x, y + h, w, 8);
            g.setColor(new Color(0, 0, 0, 30));
            g.fillRect(x, y, 4, h);
            g.fillRect(x, y + h - 4, w, 4);
            g.setColor(new Color(255, 255, 255, 100));
            g.fillRect(x + w - 3, y, 3, h);
            g.fillRect(x, y, w, 3);
            int pad = getWidth() < 700 ? 16 : 24;
            g.setColor(new Color(0x6b6562));
            g.fillRect(x + pad, y + 162, w - pad * 2, 2);
        } finally { g.dispose(); }
    }
    @Override public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(1040, 680);
    }
    @Override public int getScrollableUnitIncrement(Rectangle visible,
            int orientation, int direction) {
        return 24;
    }
    @Override public int getScrollableBlockIncrement(Rectangle visible,
            int orientation, int direction) {
        return Math.max(24, visible.height - 48);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}

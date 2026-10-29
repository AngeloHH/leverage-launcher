package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

// Candidate browsing is separate from the explicitly applied local version.
final class JourneyVersionPanel extends JPanel {
    private static final List<String> ARTWORK = List.of(
            "cherry-grove-pavilion.png", "sky-parkour.png",
            "autumn-forest-exploration.png", "copper-golem-workshop.png",
            "drowned-nautilus.png", "abandoned-mineshaft.png",
            "pig-slime-contraption.png", "riverside-companions.png",
            "torchlit-workshop.png", "ocean-exploration-square.png");
    private static final Artwork EMPTY_ARTWORK = new Artwork(null,
            ArtworkPalette.from(null));
    private final InstanceSelectionPanel instances;
    private final JourneySlideDeck deck = new JourneySlideDeck();
    private final Artwork[] artwork = new Artwork[ARTWORK.size()];
    private String candidate;
    private List<String> lastIds = List.of();
    private boolean animationEnabled = true;
    JourneyVersionPanel(InstanceSelectionPanel instances) {
        super(new BorderLayout());
        this.instances = instances;
        setName("version-story-selector");
        setBackground(new Color(0x2b3654));
        add(deck);
        instances.addChangeListener(this::changed);
        changed();
    }
    void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
        deck.setAnimationEnabled(enabled);
    }
    private void changed() {
        List<String> ids = instances.versionIds();
        if (candidate == null || !ids.contains(candidate)) candidate
                = instances.selectedVersion();
        if (candidate == null && !ids.isEmpty()) candidate = ids.getFirst();
        lastIds = ids;
        // Selection/status changes update the current action, without animating a
        // fictitious version change.
        deck.setAnimationEnabled(false);
        deck.showPage(new StoryCard(candidate), false, false, 0);
        deck.setAnimationEnabled(animationEnabled);
    }
    private void browse(int delta) {
        if (lastIds.size() < 2) return;
        int index = lastIds.indexOf(candidate);
        candidate = lastIds.get(Math.floorMod(index + delta, lastIds.size()));
        deck.showPage(new StoryCard(candidate), delta < 0, false, 0);
    }
    private Artwork versionArtwork(String version) {
        if (version == null) return EMPTY_ARTWORK;
        int index = (int)(PixelOpeningGeometry.seed(version, 0) % ARTWORK.size());
        if (artwork[index] == null) {
            BufferedImage image = JourneyStyle.image(
                    "/dev/leverage/ui/journey/" + ARTWORK.get(index));
            artwork[index] = new Artwork(image, ArtworkPalette.from(image));
        }
        return artwork[index];
    }
    private record Artwork(BufferedImage image, ArtworkPalette palette) { }
    private final class StoryCard extends JPanel {
        private final String version;
        private final Color hole;
        private final BufferedImage illustration;
        private final PixelButton install = new PixelButton(t("versions.installOther"),
                PixelButton.Variant.PRIMARY);
        private final JButton select;
        private final JButton previous = arrow(false), next = arrow(true);
        StoryCard(String version) {
            super(null);
            this.version = version;
            Artwork art = versionArtwork(version);
            illustration = art.image();
            setName("version-candidate");
            setBackground(art.palette().background());
            hole = art.palette().shade();
            getAccessibleContext().setAccessibleName(version == null
                    ? t("versions.noInstalled") : t("versions.candidate", version));
            install.setName("version-install");
            install.setFont(LauncherFonts.minecraftTen(14));
            install.addActionListener(event -> instances.requestInstall());
            add(install);
            boolean selected = Objects.equals(version, instances.selectedVersion());
            select = JourneyStyle.link(version == null ? t("versions.none") : selected
                    ? t("versions.selected") : t("versions.select"), () -> {
                if (version != null) instances.selectVersion(version);
            });
            select.setName("version-apply");
            select.setFont(LauncherFonts.minecraftSeven(11));
            select.setForeground(art.palette().accent());
            if (version == null || selected) select.setCursor(Cursor.getDefaultCursor());
            add(select);
            previous.setName("previous-version");
            next.setName("next-version");
            previous.addActionListener(e -> browse(-1));
            next.addActionListener(e -> browse(1));
            previous.setEnabled(lastIds.size() > 1);
            next.setEnabled(lastIds.size() > 1);
            add(previous);
            add(next);
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                    KeyStroke.getKeyStroke("LEFT"), "previous");
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                    KeyStroke.getKeyStroke("RIGHT"), "next");
            getActionMap().put("previous", new AbstractAction() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    browse(-1);
                }
            });
            getActionMap().put("next", new AbstractAction() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    browse(1);
                }
            });
        }
        @Override public void doLayout() {
            int left = getWidth() < 800 ? 26 : 38, copyWidth = (int)(getWidth() * .34),
                    buttonWidth = Math.min(240, copyWidth);
            install.setBounds(left + (copyWidth - buttonWidth) / 2,
                    getHeight() - 82, buttonWidth, 48);
            select.setBounds(left, getHeight() - 30, copyWidth, 26);
            previous.setBounds(getWidth() - 132, getHeight() - 58, 48, 48);
            next.setBounds(getWidth() - 70, getHeight() - 58, 48, 48);
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int left = getWidth() < 800 ? 26 : 38, copyWidth = (int)(
                        getWidth() * .34), top = Math.max(20, (getHeight() - 360) / 2);
                boolean compact = getHeight() < 420;
                JourneyStyle.centeredText(g, "MINECRAFT JAVA EDITION",
                        LauncherFonts.minecraftSeven(11), Color.WHITE,
                        left, top, copyWidth, 16);
                int size = compact ? 32 : 36;
                int bottom = JourneyStyle.centeredText(g, t("versions.chooseAdventure"),
                        LauncherFonts.minecraftTen(size), Color.WHITE,
                        left, top + 28, copyWidth, size + 5);
                int descriptionBottom = JourneyStyle.centeredText(g,
                        version == null ? t("versions.getStarted") : t("versions.intro"),
                        LauncherFonts.minecraftSeven(compact ? 13 : 16), Color.WHITE,
                        left, bottom + (compact ? 12 : 20), copyWidth, compact ? 20 : 25);
                JourneyStyle.centeredText(g, t("versions.viewingSelected",
                        Objects.requireNonNullElse(version, "—"),
                        Objects.requireNonNullElse(instances.selectedVersion(), "—")),
                        LauncherFonts.minecraftSeven(11), Color.WHITE, left, Math.min(
                        descriptionBottom + 16, getHeight() - 112), copyWidth, 17);
                JourneyStyle.text(g, t("versions.position",
                        Integer.toString(lastIds.isEmpty() ? 0 : lastIds.indexOf(
                        version) + 1), Integer.toString(lastIds.size())),
                        LauncherFonts.minecraftSeven(12), Color.WHITE, Math.max(0,
                        getWidth() - 220), getHeight() - 42, 90, 18);
                Rectangle window = new Rectangle((int)(getWidth() * .40), 12, Math.max(1,
                        (int)(getWidth() * .60) - 16), Math.max(1, getHeight() - 76));
                var geometry = PixelOpeningGeometry.build(window.width,
                        window.height, 52, PixelOpeningGeometry.seed(
                        Objects.requireNonNullElse(version, "empty"), 8));
                Area opening = geometry.area();
                opening.transform(java.awt.geom.AffineTransform.getTranslateInstance(
                        window.x, window.y));
                g.setColor(hole);
                g.fill(opening);
                Graphics2D text = (Graphics2D) g.create(); try {
                    text.clip(opening);
                    if (illustration != null) {
                        JourneyStyle.cover(text, illustration, window, null);
                        text.setPaint(new LinearGradientPaint(window.x, window.y,
                                window.x, window.y + window.height,
                                new float[] { 0, .25f, .75f, 1 }, new Color[] {
                                new Color(0, 0, 0, 0), new Color(0, 0, 0, 144),
                                new Color(0, 0, 0, 144), new Color(0, 0, 0, 0) }));
                        text.fillRect(window.x, window.y, window.width, window.height);
                    }
                    String title = Objects.requireNonNullElse(version, "—");
                    float fontSize = Math.min(64, Math.max(32,
                            window.width / (float) Math.max(5, title.length()) * 1.15f));
                    int center = window.y + (window.height
                            - geometry.rows() * geometry.cellSize()) / 2
                            + (geometry.rows() + 1) * geometry.cellSize() / 2;
                    text.setFont(LauncherFonts.minecraftTen(Math.min(27,
                            fontSize * .42f)));
                    text.setColor(Color.WHITE);
                    var small = text.getFontMetrics();
                    String minecraft = "MINECRAFT";
                    text.drawString(I18n.resolve(minecraft), window.x + (window.width
                            - small.stringWidth(I18n.resolve(minecraft))) / 2,
                            center - (int)(fontSize * .60));
                    text.setFont(LauncherFonts.minecraftTen(fontSize));
                    var fm = text.getFontMetrics();
                    text.drawString(I18n.resolve(title), window.x + (window.width
                            - fm.stringWidth(I18n.resolve(title))) / 2,
                            center + (fm.getAscent() - fm.getDescent()) / 2);
                    text.setFont(LauncherFonts.minecraftTen(Math.min(14,
                            fontSize * .24f)));
                    var java = text.getFontMetrics();
                    String edition = "JAVA EDITION";
                    text.drawString(I18n.resolve(edition), window.x + (window.width
                            - java.stringWidth(I18n.resolve(edition))) / 2,
                            center + (int)(fontSize * .75));
                } finally { text.dispose(); }
            } finally { g.dispose(); }
        }
    }
    private static JButton arrow(boolean right) {
        JButton button = new JButton() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setColor(isEnabled() ? Color.WHITE : new Color(255, 255, 255, 65));
                    int[] points = { 50, 5, 40, 5, 40, 15, 30, 15, 30, 25, 20, 25, 20,
                            35, 10, 35, 10, 45, 0, 45, 0, 55, 10, 55, 10, 65, 20, 65, 20,
                            75, 30, 75, 30, 85, 40, 85, 40, 95, 50, 95, 50, 85, 40, 85,
                            40, 75, 30, 75, 30, 65, 20, 65, 20, 55, 100, 55, 100, 45, 20,
                            45, 20, 35, 30, 35, 30, 25, 40, 25, 40, 15, 50, 15, 50, 5 };
                    java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                    for (int i = 0; i < points.length; i += 2) {
                        double xx = (getWidth() - 32) / 2d + (right
                                ? 100 - points[i] : points[i]) * .32,
                                yy = (getHeight() - 32) / 2d + points[i + 1] * .32;
                        if (i == 0) path.moveTo(xx, yy);
                        else path.lineTo(xx, yy);
                    }
                    path.closePath();
                    g.fill(path);
                    if (hasFocus()) {
                        g.setColor(JourneyStyle.GREEN);
                        g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                } finally {
                    g.dispose();
                }
            }
        };
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.getAccessibleContext().setAccessibleName(right
                ? t("versions.next") : t("versions.previous"));
        return button;
    }
}

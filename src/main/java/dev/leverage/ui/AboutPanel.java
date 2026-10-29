package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Local editorial About page. Illustrations are not represented as historical team
// photographs.
public final class AboutPanel extends JPanel implements Scrollable {
    private static final Color INK = new Color(0x10251E), PAPER = new Color(0x171615),
            TEXT = new Color(0xFAF4EF), MUTED = new Color(0xA7A09A),
            GREEN = new Color(0x86D562), ACCENT = new Color(0xE5A367);
    private static final BufferedImage LOGO = image(
            "/dev/leverage/ui/images/leverage.png");
    private static final List<Story> STORIES = List.of(
            new Story("origins", "sky-parkour.png", t("about.originsTitle"),
                    t("about.originsHeading"), List.of(t("about.origins1"),
                    t("about.origins2"), t("about.origins3"))),
            new Story("relay", "autumn-forest-exploration.png", t("about.relayTitle"),
                    t("about.relayHeading"), List.of(t("about.relay1"), t("about.relay2"),
                    t("about.relay3"), t("about.relay4"), t("about.relay5"))),
            new Story("today", "copper-golem-workshop.png", t("about.todayTitle"),
                    t("about.todayHeading"), List.of(t("about.today1"),
                    t("about.today2"), t("about.today3"))));
    private final JPanel topbar = new JPanel(new BorderLayout(16, 0));
    private final JPanel heroCopy = new JPanel(null);
    private final JLabel heroEyebrow = label(t("about.generations"),
            11, new Color(0xB1C8B5));
    private final JLabel heroTitle = label(t("about.behind"), 54,
            TEXT), heroBrand = label("LEVERAGE.", 54, ACCENT);
    private final Paragraph heroIntro = new Paragraph(t("about.intro"),
            LauncherFonts.minecraftSeven(16), new Color(0xD0DBD2), false, 1.7f);
    private final Workshop workshop = new Workshop();
    private final JPanel sceneControls = new JPanel(new BorderLayout(12, 0));
    private final JButton light = link(t("about.light"), "about-light-switch");
    private final PixelEdge edge = new PixelEdge();
    private final Paragraph chapterIntro = new Paragraph(t("about.footprint"),
            LauncherFonts.minecraftSeven(20), TEXT, true, 1.5f);
    private final Paragraph chapterTitle = new Paragraph(t("about.oneStory"),
            LauncherFonts.minecraftTen(42), TEXT, true, 1.14f);
    private final PixelButton start = new PixelButton(t("about.start"),
            PixelButton.Variant.PRIMARY);
    private final List<StoryCard> cards = new ArrayList<>();
    private final Reader reader = new Reader();
    private final JLabel artNote = label(t("about.visualReference"), 11, MUTED);
    private final JPanel footer = new JPanel(new BorderLayout(20, 0));
    private final Map<String, JButton> storyLinks = new LinkedHashMap<>();
    private JButton lastStoryTrigger;
    private boolean workshopLit = true;
    private String selectedStory = "";
    private long storyRevision;
    private int storiesTop, footerTop;

    public AboutPanel(Runnable backToSettings) {
        Objects.requireNonNull(backToSettings);
        setName("section-about");
        setLayout(null);
        setBackground(INK);
        setForeground(TEXT);
        JButton back = link(t("about.back"), "about-back");
        back.setIcon(new ArrowIcon(true));
        back.addActionListener(event -> backToSettings.run());
        topbar.setOpaque(false);
        topbar.add(back, BorderLayout.WEST);
        topbar.add(label(t("about.project"), 11, new Color(0xA5B8AA)), BorderLayout.EAST);
        add(topbar);
        heroTitle.setFont(LauncherFonts.minecraftTen(54));
        heroBrand.setFont(LauncherFonts.minecraftTen(54));
        heroCopy.setOpaque(false);
        heroCopy.add(heroEyebrow);
        heroCopy.add(heroTitle);
        heroCopy.add(heroBrand);
        heroCopy.add(heroIntro);
        add(heroCopy);
        add(workshop);
        sceneControls.setOpaque(false);
        sceneControls.add(label(t("about.workshop"), 11,
                new Color(0xA5B8AA)), BorderLayout.WEST);
        light.setFont(LauncherFonts.minecraftSeven(12));
        light.setForeground(new Color(0xD5E6D8));
        light.setIcon(new Icon() {
            public int getIconWidth() {
                return 8;
            }
            public int getIconHeight() {
                return 8;
            }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(workshopLit ? ACCENT : new Color(0x697C6E));
                g.fillRect(x, y, 7, 7);
            }
        });
        light.setToolTipText(t("about.toggleLight"));
        light.addActionListener(event -> setWorkshopLit(!workshopLit));
        sceneControls.add(light, BorderLayout.EAST);
        add(sceneControls);
        add(edge);
        add(chapterIntro);
        chapterTitle.setName("about-stories-title");
        add(chapterTitle);
        start.setName("about-read-from-start");
        start.setFont(LauncherFonts.minecraftSeven(15));
        start.addActionListener(event -> {
            lastStoryTrigger = start;
            openStory("origins");
        });
        add(start);
        for (Story story : STORIES) {
            StoryCard card = new StoryCard(story);
            cards.add(card);
            add(card);
        }
        reader.setName("about-reader");
        reader.setVisible(false);
        add(reader);
        artNote.setHorizontalAlignment(SwingConstants.CENTER);
        add(artNote);
        footer.setOpaque(false);
        JLabel footerLogo = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                if (LOGO != null) {
                    int h = LOGO.getHeight() * 114 / LOGO.getWidth();
                    g.drawImage(LOGO, 0, (getHeight() - h) / 2, 114, h, null);
                }
            }
        };
        footerLogo.setPreferredSize(new Dimension(114, 40));
        footer.add(footerLogo, BorderLayout.WEST);
        JLabel farewell = label(t("about.forBuilders"), 12, new Color(0xBDB7B2));
        farewell.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(farewell, BorderLayout.CENTER);
        add(footer);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke("ESCAPE"), "close-story");
        getActionMap().put("close-story", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!selectedStory.isEmpty()) closeStory();
            }
        });
        setWorkshopLit(true);
    }

    public void openStory(String id) {
        Story story = STORIES.stream().filter(value -> value.id().equals(
                id)).findFirst().orElseThrow(() -> new IllegalArgumentException(
                "Historia desconocida: " + id));
        long request =++storyRevision;
        selectedStory = id;
        reader.setStory(story);
        reader.setVisible(true);
        for (var entry : storyLinks.entrySet()) entry.getValue().putClientProperty(
                "selected", entry.getKey().equals(id));
        if (lastStoryTrigger == null) lastStoryTrigger = storyLinks.get(id);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> {
            if (request != storyRevision || !reader.isVisible()
                    || !id.equals(selectedStory)) return;
            doLayout();
            scrollRectToVisible(new Rectangle(reader.getX(), reader.getY(),
                    reader.getWidth(), Math.min(reader.getHeight(), 160)));
            if (reader.close.isShowing()) reader.close.requestFocusInWindow();
        });
    }
    public void closeStory() {
        long request =++storyRevision;
        selectedStory = "";
        reader.setVisible(false);
        storyLinks.values().forEach(button -> button.putClientProperty(
                "selected", false));
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> {
            if (request != storyRevision || reader.isVisible()) return;
            doLayout();
            if (lastStoryTrigger != null && lastStoryTrigger.isShowing()) {
                Component target = lastStoryTrigger.getParent() instanceof StoryCard
                        ? lastStoryTrigger.getParent() : lastStoryTrigger;
                Rectangle bounds = SwingUtilities.convertRectangle(target.getParent(),
                        target.getBounds(), this);
                scrollRectToVisible(bounds);
                lastStoryTrigger.requestFocusInWindow();
            }
        });
    }
    public String selectedStory() { return selectedStory; }
    public void setWorkshopLit(boolean value) {
        workshopLit = value;
        light.putClientProperty("selected", value);
        light.getAccessibleContext().setAccessibleDescription(value
                ? t("about.lightOn") : t("about.lightOff"));
        workshop.repaint();
        light.repaint();
    }
    public boolean isWorkshopLit() { return workshopLit; }

    @Override public void doLayout() { layoutPage(true); }
    @Override public Dimension getPreferredSize() {
        return new Dimension(1040, layoutPage(false));
    }
    private int layoutPage(boolean apply) {
        int width = Math.max(320, getWidth() > 0 ? getWidth() : 1040),
                pad = width < 700 ? 24 : 40, content = width - pad * 2, y = 16;
        place(topbar, pad, y, content, 44, apply);
        y += 68;
        boolean stacked = content < 660;
        int copyWidth = stacked ? content : (int)((content - 32) * .53),
                artWidth = stacked ? content : content - copyWidth - 32;
        int introHeight = heroIntro.heightFor(copyWidth),
                copyHeight = 170 + introHeight, artHeight = 270;
        place(heroCopy, pad, y, copyWidth, copyHeight, apply);
        if (apply) {
            heroEyebrow.setBounds(0, 0, copyWidth, 22);
            heroTitle.setBounds(0, 34, copyWidth, 58);
            heroBrand.setBounds(0, 92, copyWidth, 58);
            heroIntro.setBounds(0, 170, copyWidth, introHeight);
        }
        int artY = stacked ? y + copyHeight + 26 : y,
                artX = stacked ? pad : pad + copyWidth + 32;
        place(workshop, artX, artY, artWidth, artHeight, apply);
        place(sceneControls, artX + 8, artY + artHeight + 6, artWidth - 16, 44, apply);
        y = (stacked ? artY + artHeight + 50 : y + Math.max(copyHeight,
                artHeight + 50)) + 8;
        place(edge, 0, y, width, 140, apply);
        y += 140;
        storiesTop = y;
        int h = chapterIntro.heightFor(content);
        place(chapterIntro, pad, y, content, h, apply);
        y += h + 20;
        h = chapterTitle.heightFor(content);
        place(chapterTitle, pad, y, content, h, apply);
        y += h + 24;
        place(start, (width - Math.min(340, content)) / 2, y,
                Math.min(340, content), 50, apply);
        y += 84;
        boolean single = content < 610;
        int cardWidth = single ? Math.min(360, content) : (content - 48) / 3;
        for (int i = 0; i < cards.size(); i++) {
            StoryCard card = cards.get(i);
            int ch = card.heightFor(cardWidth);
            place(card, single ? (width - cardWidth) / 2 : pad
                    + i * (cardWidth + 24), y, cardWidth, ch, apply);
            if (single) y += ch + 24;
        }
        if (!single) y += cards.getFirst().heightFor(cardWidth) + 26;
        if (reader.isVisible()) {
            int rw = Math.min(760, content), rh = reader.heightFor(rw);
            place(reader, (width - rw) / 2, y, rw, rh, apply);
            y += rh + 26;
        }
        place(artNote, pad, y, content, 30, apply);
        y += 54;
        footerTop = y;
        place(footer, pad, y + 18, content, 48, apply);
        return y + 88;
    }
    private static void place(Component component, int x,
            int y, int w, int h, boolean apply) {
        if (apply) {
            component.setBounds(x, y, Math.max(0, w), Math.max(0, h));
            if (component instanceof Container container) container.doLayout();
        }
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(PAPER);
        graphics.fillRect(0, storiesTop, getWidth(), Math.max(0, footerTop - storiesTop));
        graphics.setColor(new Color(0x242322));
        graphics.fillRect(0, footerTop, getWidth(), Math.max(0, getHeight() - footerTop));
    }

    private final class StoryCard extends JPanel {
        private final BufferedImage art;
        private final Paragraph title;
        private final JButton read;
        StoryCard(Story story) {
            setName("about-card-" + story.id());
            setLayout(null);
            setOpaque(false);
            art = image("/dev/leverage/ui/journey/" + story.artwork());
            title = new Paragraph(story.title(), LauncherFonts.minecraftSeven(20),
                    TEXT, true, 1.45f);
            add(title);
            read = link(t("about.read"), "about-story-" + story.id());
            read.setIcon(new ArrowIcon(false));
            read.setHorizontalTextPosition(SwingConstants.LEFT);
            read.setFont(LauncherFonts.minecraftSeven(14));
            read.getAccessibleContext().setAccessibleName(I18n.t(
                    "about.readNamed", story.title()));
            read.addActionListener(event -> {
                lastStoryTrigger = read;
                openStory(story.id());
            });
            storyLinks.put(story.id(), read);
            add(read);
        }
        int heightFor(int width) { return width + 16 + title.heightFor(width) + 44; }
        @Override public void doLayout() {
            int w = getWidth();
            title.setBounds(0, w + 16, w, title.heightFor(w));
            read.setBounds(0, w + 16 + title.getHeight(), w, 44);
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(new Color(0x242322));
            g.fillRect(0, 0, getWidth(), getWidth());
            cover(g, art, 0, 0, getWidth(), getWidth());
            g.dispose();
        }
    }
    private final class Reader extends JPanel {
        private final JLabel eyebrow = label("", 11, MUTED), title = label("",
                30, TEXT), byline = label(t("about.narrator"), 12, MUTED);
        private final JButton close = link(t("about.close"), "about-close-story");
        private final List<Paragraph> copy = new ArrayList<>();
        Reader() {
            setLayout(null);
            setOpaque(false);
            title.setName("about-reader-title");
            title.setFont(LauncherFonts.minecraftTen(30));
            add(eyebrow);
            add(title);
            add(close);
            add(byline);
            close.addActionListener(event -> closeStory());
        }
        void setStory(Story story) {
            copy.forEach(this::remove);
            copy.clear();
            eyebrow.setText(story.eyebrow());
            title.setText(story.title());
            for (String text : story.paragraphs()) {
                var paragraph = new Paragraph(text, LauncherFonts.minecraftSeven(14),
                        new Color(0xE1DCD8), false, 1.9f);
                copy.add(paragraph);
                add(paragraph);
            }
        }
        int heightFor(int width) {
            int height = 105;
            for (var paragraph : copy) height += paragraph.heightFor(width) + 16;
            return height + 40;
        }
        @Override public void doLayout() {
            int w = getWidth();
            eyebrow.setBounds(0, 26, Math.max(0, w - 170), 20);
            title.setBounds(0, 52, w, 38);
            close.setBounds(Math.max(0, w - 165), 20, 165, 36);
            int y = 105;
            for (var paragraph : copy) {
                int h = paragraph.heightFor(w);
                paragraph.setBounds(0, y, w, h);
                y += h + 16;
            }
            byline.setBounds(0, y + 6, w, 26);
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(new Color(0, 0, 0, 64));
            g.fillRect(0, 0, getWidth(), 2);
            g.setColor(new Color(0x6B6562));
            g.fillRect(0, 2, getWidth(), 2);
        }
    }
    private final class Workshop extends JComponent {
        private final BufferedImage art = image(
                "/dev/leverage/ui/journey/cherry-grove-sunset.png");
        Workshop() {
            setName("about-workshop");
            setToolTipText(t("about.workshopTooltip"));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            var shape = PixelOpeningGeometry.build(getWidth(), getHeight(), 28,
                    PixelOpeningGeometry.seed("leverage-workshop", 0));
            g.clip(shape.area());
            cover(g, art, 0, 0, getWidth(), getHeight());
            g.setColor(new Color(0, 0, 0, workshopLit ? 24 : 125));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (workshopLit) {
                g.setPaint(new RadialGradientPaint(getWidth() * .7f, getHeight() * .2f,
                        Math.max(1, getWidth() * .7f), new float[] { 0, 1 }, new Color[] {
                        new Color(255, 201, 117, 105), new Color(255, 201, 117, 0) }));
                g.fillRect(0, 0, getWidth(), getHeight());
                for (int[] spark : new int[][] { { 20, 24, 6 },
                        { 74, 70, 4 }, { 65, 18, 3 } }) {
                    int x = getWidth() * spark[0] / 100, y = getHeight() * spark[1] / 100;
                    g.setPaint(new RadialGradientPaint(x + spark[2] / 2f,
                            y + spark[2] / 2f, 16, new float[] { 0, 1 }, new Color[] {
                            new Color(247, 142, 57, 110), new Color(247, 142, 57, 0) }));
                    g.fillRect(x - 16, y - 16, 32, 32);
                    g.setColor(new Color(0xFFC16C));
                    g.fillRect(x, y, spark[2], spark[2]);
                }
            }
            if (LOGO != null) {
                int w = (int)(getWidth() * .64),
                        h = w * LOGO.getHeight() / LOGO.getWidth(),
                        x = (int)(getWidth() * .24), y = (int)(getHeight() * .44);
                g.drawImage(LOGO, x, y, w, h, null);
            }
            g.dispose();
        }
    }
    private static final class PixelEdge extends JComponent {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(getWidth(), 0);
            g.rotate(Math.PI / 2);
            PixelSlideSeam.paint(g, 0, 0, getHeight(), getWidth(),
                    28, 8, INK, PAPER, false);
            g.dispose();
        }
    }
    private static final class Paragraph extends JComponent {
        private final String text;
        private final boolean centered;
        private final float leading;
        Paragraph(String text, Font font, Color color, boolean centered, float leading) {
            this.text = text;
            this.centered = centered;
            this.leading = leading;
            setFont(font);
            setForeground(color);
            setOpaque(false);
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleParagraph();
            return accessibleContext;
        }
        protected final class AccessibleParagraph extends AccessibleJComponent {
            @Override public AccessibleRole getAccessibleRole() {
                return AccessibleRole.TEXT;
            }
            @Override public String getAccessibleName() {
                return accessibleName == null ? I18n.resolve(text) : accessibleName;
            }
            @Override public String getAccessibleDescription() {
                return I18n.resolve(text);
            }
        }
        int heightFor(int width) {
            return Math.max(1, lines(Math.max(1, width)).size()) * lineHeight();
        }
        private int lineHeight() { return Math.round(getFont().getSize2D() * leading); }
        private List<String> lines(int width) {
            return JourneyStyle.wrap(getFontMetrics(LauncherFonts.forLocale(
                    getFont())), text, width);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(getForeground());
            g.setFont(LauncherFonts.forLocale(getFont()));
            FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent();
            for (String line : lines(Math.max(1, getWidth()))) {
                g.drawString(I18n.resolve(line), centered ? Math.max(0,
                        (getWidth() - fm.stringWidth(I18n.resolve(line))) / 2) : 0, y);
                y += lineHeight();
            }
            g.dispose();
        }
    }
    private record Story(String id, String artwork, String title, String eyebrow,
            List<String> paragraphs) { }
    private record ArrowIcon(boolean left) implements Icon {
        @Override public int getIconWidth() {
            return 16;
        }
        @Override public int getIconHeight() {
            return 14;
        }
        @Override public void paintIcon(Component component,
                Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            if (left) {
                g.translate(16, 0);
                g.scale(-1, 1);
            }
            g.setColor(component.getForeground());
            g.fillRect(0, 6, 14, 2);
            for (int i = 0; i < 4; i++) {
                g.fillRect(7 + i * 2, i * 2, 2, 2);
                g.fillRect(7 + i * 2, 12 - i * 2, 2, 2);
            }
            g.dispose();
        }
    }
    private static JLabel label(String text, float size, Color color) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(LauncherFonts.minecraftSeven(size));
        label.setForeground(color);
        return label;
    }
    private static JButton link(String text, String name) {
        var button = new JButton(text);
        button.setName(name);
        button.setFont(LauncherFonts.minecraftSeven(13));
        button.setForeground(GREEN);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
    private static BufferedImage image(String path) {
        try (var input = AboutPanel.class.getResourceAsStream(path)) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }
    private static void cover(Graphics2D g, BufferedImage image,
            int x, int y, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return;
        double scale = Math.max((double) width / image.getWidth(),
                (double) height / image.getHeight());
        int w = (int) Math.ceil(image.getWidth() * scale),
                h = (int) Math.ceil(image.getHeight() * scale);
        Shape old = g.getClip();
        g.clipRect(x, y, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, x + (width - w) / 2, y + (height - h) / 2, w, h, null);
        g.setClip(old);
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

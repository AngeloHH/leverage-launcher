package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherNewsClient;
import dev.leverage.service.NewsService;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

// Server news with inline Markdown authoring, drafts and explicit publication.
final class JourneyNewsPanel extends JPanel implements Scrollable {
    record Article(String title, String description, BufferedImage image) { }
    private static final int GAP = 24;
    private final List<NewsCard> cards = new ArrayList<>();
    private final CreateCard create = new CreateCard();
    private final JLabel notice = JourneyStyle.label(t("news.signIn"),
            13, JourneyStyle.PAPER, false);
    private final LauncherNewsClient client;
    @FunctionalInterface interface DeleteConfirmation {
        boolean confirm(Component owner, String title, boolean published);
    }
    private final DeleteConfirmation deleteConfirmation;
    private URI host;
    private LauncherAuthClient.Session session;
    private boolean administrator, contributor, loading;
    private long generation;
    private NewsCard expanded;
    private int nextCardIndex;
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private final javax.swing.Timer motion = new javax.swing.Timer(16,
            event -> animate());
    private final Map<JComponent, Rectangle> before = new HashMap<>(),
            after = new HashMap<>();
    private long animationStarted;
    private boolean animating;
    private int animatedHeight;
    JourneyNewsPanel() { this(new LauncherNewsClient()); }
    JourneyNewsPanel(LauncherNewsClient client) {
        this(client, JourneyNewsPanel::confirmDeletion);
    }
    JourneyNewsPanel(LauncherNewsClient client, DeleteConfirmation confirmation) {
        super(null);
        this.client = client;
        this.deleteConfirmation = Objects.requireNonNull(confirmation);
        setName("section-news");
        setBackground(JourneyStyle.TABLE);
        motion.setCoalesce(true);
        create.setName("news-create");
        create.setVisible(false);
        create.addActionListener(e -> newArticle());
        notice.setName("news-notice");
        add(create);
        add(notice);
    }
    // Isolated design fixtures. Production content always comes from the server.
    JourneyNewsPanel(Runnable more) {
        this(List.of(new Article("LA EDAD DEL COBRE",
                "Nuevas ideas para construir, descubrir y dar vida a tu mundo.",
                JourneyStyle.COPPER)), more);
    }
    JourneyNewsPanel(List<Article> articles, Runnable more) {
        super(null);
        client = new LauncherNewsClient();
        deleteConfirmation = JourneyNewsPanel::confirmDeletion;
        setName("section-news");
        setBackground(JourneyStyle.TABLE);
        motion.setCoalesce(true);
        for (Article article : articles) {
            var item = new NewsService.Item(UUID.randomUUID(), "# " + article.title()
                    + "\n\n" + article.description(), false, null, null, "", "");
            addCard(item, article.image(), more);
        }
    }
    void setSession(URI nextHost, LauncherAuthClient.Session nextSession) {
        if (Objects.equals(host, nextHost) && Objects.equals(session,
                nextSession)) return;
        if (Objects.equals(host, nextHost) && session != null && nextSession != null
                && session.userId().equals(nextSession.userId())
                && session.role().equals(nextSession.role())) {
            session = nextSession;
            return;
        }
        generation++;
        stopMotion();
        host = nextHost;
        session = nextSession;
        administrator = host != null && session != null && "ADMIN".equals(session.role());
        contributor = host != null && session != null && (administrator
                || "MODERATOR".equals(session.role()));
        cards.forEach(this::remove);
        cards.clear();
        nextCardIndex = 0;
        expanded = null;
        create.setVisible(contributor);
        loading = false;
        if (session == null || host == null) {
            notice.setText(t("news.signIn"));
            revalidate();
            repaint();
            return;
        }
        refresh();
    }
    void refresh() {
        if (host == null || session == null || loading
                || cards.stream().anyMatch(c -> c.busy || c.editing)) return;
        long ticket = generation;
        URI origin = host;
        String token = session.accessToken();
        boolean admin = contributor;
        loading = true;
        create.setEnabled(false);
        notice.setText(t("news.loading"));
        cards.forEach(c -> {
            c.body.setEditable(false);
            c.controls();
        });
        var finished = new CompletableFuture<Void>();
        pending = finished;
        CompletableFuture.supplyAsync(() -> {
            try {
                var result = new ArrayList<Loaded>();
                for (var item : client.list(origin, token, admin)) result.add(new Loaded(
                        item, LauncherNewsClient.image(item.imageBase64())));
                return result;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((items, error) -> SwingUtilities.invokeLater(() -> {
                try {
                    if (ticket != generation) return;
                    loading = false;
                    create.setEnabled(admin);
                    if (error != null) {
                        notice.setText(message(error));
                        cards.forEach(c -> {
                            c.body.setEditable(c.editing);
                            c.controls();
                    });
                        return;
                }
                cards.forEach(this::remove);
                cards.clear();
                nextCardIndex = 0;
                expanded = null;
                for (var item : items) addCard(item.item, item.image, null);
                notice.setText(items.isEmpty() && !admin ? t("news.empty") : "");
                revalidate();
                doLayout();
                repaint();
            } finally {
                finished.complete(null);
            } }));
    }
    private record Loaded(NewsService.Item item, BufferedImage image) { }
    private record LoadedImage(String encoded, BufferedImage image) { }
    private void addCard(NewsService.Item item, BufferedImage image, Runnable more) {
        var card = new NewsCard(item, image, more, nextCardIndex++);
        cards.add(card);
        add(card);
    }
    private void newArticle() {
        if (!contributor || loading || cards.stream().anyMatch(
                c -> c.editing || c.busy)) return;
        var item = new NewsService.Item(UUID.randomUUID(), t("news.newMarkdown"),
                true, session.userId(), null, null, null);
        var card = new NewsCard(item, null, null, nextCardIndex++);
        card.persisted = false;
        cards.addFirst(card);
        add(card);
        expanded = card;
        card.editing = true;
        cards.forEach(NewsCard::updateBody);
        notice.setText("");
        revalidate();
        doLayout();
        repaint();
    }
    private int contentWidth() {
        return Math.min(1120, Math.max(GAP + 2,
                (getWidth() > 0 ? getWidth() : 1040) - 96));
    }
    private List<JComponent> tiles() {
        var tiles = new ArrayList<JComponent>(cards);
        if (create.getParent() == this && create.isVisible()) tiles.add(create);
        return tiles;
    }
    private int tileHeight(JComponent tile, int width, boolean full) {
        return tile instanceof NewsCard card ? card.heightFor(width,
                full) : create.heightFor(width);
    }
    private int arrange(boolean apply) {
        int panelWidth = getWidth() > 0 ? getWidth() : 1040, width = contentWidth(),
                column = (width - GAP) / 2, x = (panelWidth - width) / 2, y = 118;
        boolean single = panelWidth < 650;
        List<JComponent> ordered = tiles();
        if (expanded != null) {
            int at = ordered.indexOf(expanded);
            if (at % 2 == 1 && !single) Collections.swap(ordered, at, at - 1);
        }
        for (int i = 0; i < ordered.size();) { var first = ordered.get(i);
            if (first == expanded) {
                int h = tileHeight(first, panelWidth, true);
                if (apply) first.setBounds(0, y, panelWidth, h);
                y += h + GAP;
                i++;
            }
            else if (single || i + 1 == ordered.size()
                    || ordered.get(i + 1) == expanded) {
                int w = single ? width : column, h = tileHeight(first, w, false);
                if (apply) first.setBounds(x, y, w, h);
                y += h + GAP;
                i++;
            }
            else {
                var second = ordered.get(i + 1);
                int right = width - column - GAP, h = Math.max(tileHeight(first,
                        column, false), tileHeight(second, right, false));
                if (apply) {
                    first.setBounds(x, y, column, h);
                    second.setBounds(x + column + GAP, y, right, h);
                }
                y += h + GAP;
                i += 2;
            }
        }
        if (apply) {
            if (notice.getParent() == this) notice.setBounds(48, 80,
                    Math.max(1, panelWidth - 96), 30);
            for (var card : cards) card.doLayout();
        }
        return Math.max(220, y + 24);
    }
    @Override public Dimension getPreferredSize() {
        return new Dimension(getWidth() > 0 ? getWidth() : 1040,
                animating ? animatedHeight : arrange(false));
    }
    @Override public void doLayout() {
        if (!animating) arrange(true);
        else for (var card : cards) card.doLayout();
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g = (Graphics2D) graphics.create();
        try {
            String heading = t("news.heading");
            g.setFont(LauncherFonts.forLocale(LauncherFonts.minecraftTen(
                    getWidth() < 800 ? 30 : 38)));
            g.setColor(Color.WHITE);
            var fm = g.getFontMetrics();
            g.drawString(I18n.resolve(heading), (getWidth()
                    - fm.stringWidth(I18n.resolve(heading))) / 2, 65);
        } finally { g.dispose(); } }
    private void toggle(NewsCard card) {
        if (animating || cards.stream().anyMatch(c -> c.editing || c.busy)) return;
        before.clear();
        tiles().forEach(c -> before.put(c, c.getBounds()));
        int oldHeight = getPreferredSize().height;
        expanded = expanded == card ? null : card;
        cards.forEach(NewsCard::updateBody);
        int newHeight = arrange(true);
        after.clear();
        tiles().forEach(c -> after.put(c, c.getBounds()));
        if (!isShowing() || Boolean.getBoolean("leverage.reduceMotion")) {
            revalidate();
            repaint();
            return;
        }
        animatedHeight = Math.max(oldHeight, newHeight);
        animationStarted = System.nanoTime();
        animating = true;
        motion.start();
        animate();
    }
    private void animate() {
        double t = Math.min(1, (System.nanoTime() - animationStarted) / 520_000_000d),
                p = 1 - Math.pow(1 - t, 3);
        for (var card : tiles()) {
            Rectangle a = before.get(card), b = after.get(card);
            if (a == null || b == null) continue;
            card.setBounds((int) Math.round(a.x + (b.x - a.x) * p), (int) Math.round(a.y +
                    (b.y - a.y) * p), (int) Math.round(a.width + (b.width - a.width) * p),
                    (int) Math.round(a.height + (b.height - a.height) * p));
            card.doLayout();
        }
        revalidate();
        repaint();
        if (t >= 1) stopMotion();
    }
    private void stopMotion() {
        motion.stop();
        animating = false;
        before.clear();
        after.clear();
        revalidate();
        doLayout();
        repaint();
    }
    @Override public void removeNotify() {
        stopMotion();
        super.removeNotify();
    }
    CompletableFuture<Void> pendingOperation() { return pending; }
    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? t("common.operationError")
                : error.getMessage();
    }
    void importImageForTest(java.nio.file.Path path) {
        if (expanded != null) expanded.importImage(path);
    }
    private static boolean confirmDeletion(Component owner,
            String title, boolean published) {
        String text = t(published ? "news.deleteConfirmPublished"
                : "news.deleteConfirmDraft", title);
        return JOptionPane.showOptionDialog(owner, text, t("news.deleteTitle"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                null, new String[] { t("common.delete"),
                t("common.cancel") }, t("common.cancel")) == 0;
    }
    private void removeCard(NewsCard card) {
        stopMotion();
        cards.remove(card);
        remove(card);
        if (expanded == card) expanded = null;
        create.setEnabled(!loading && cards.stream().noneMatch(c -> c.editing || c.busy));
        revalidate();
        doLayout();
        repaint();
    }
    // A real keyboard-accessible action with the same paper surface and column as the
    // news.
    private final class CreateCard extends JButton {
        private static final String TITLE = t("news.createHeading");
        private static final String COPY = t("news.createCopy");
        CreateCard() {
            super(t("news.create"));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(t("news.createAccessible"));
        }
        private int titleHeight(int width) {
            return JourneyStyle.wrap(getFontMetrics(LauncherFonts.forLocale(
                    LauncherFonts.minecraftTen(28))), TITLE,
                    Math.max(1, width - 64)).size() * 34;
        }
        private int copyHeight(int width) {
            return JourneyStyle.wrap(getFontMetrics(LauncherFonts.forLocale(
                    LauncherFonts.minecraftSeven(16))), COPY,
                    Math.max(1, width - 64)).size() * 25;
        }
        int heightFor(int width) {
            return Math.max(360, 32 + 20 + 24 + titleHeight(width)
                    + 22 + copyHeight(width) + 32 + 44 + 32);
        }
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create(); try {
                int width = getWidth(), height = getHeight();
                g.setColor(JourneyStyle.PAPER);
                g.fillRect(0, 0, width, height);
                g.setColor(new Color(0x244b1f));
                g.fillRect(0, height - 5, width, 5);
                int contentHeight = 20 + 24 + titleHeight(width) + 22 + copyHeight(width)
                        + 32 + 30, y = Math.max(32, (height - contentHeight) / 2);
                JourneyStyle.text(g, t("news.stories"), LauncherFonts.minecraftFive(11),
                        new Color(0x646059), 32, y, width - 64, 20);
                y += 44;
                JourneyStyle.text(g, TITLE, LauncherFonts.minecraftTen(28),
                        JourneyStyle.INK, 32, y, width - 64, 34);
                y += titleHeight(width) + 22;
                JourneyStyle.text(g, COPY, LauncherFonts.minecraftSeven(16),
                        JourneyStyle.INK, 32, y, width - 64, 25);
                y += copyHeight(width) + 32;
                boolean hover = isEnabled() && getModel().isRollover();
                Color link = new Color(hover ? 0x1c4d14 : 0x2d6b22);
                JourneyStyle.text(g, t("news.createLink"),
                        LauncherFonts.minecraftSeven(14), isEnabled() ? link
                        : new Color(0x817a73), 32, y, width - 64, 24);
                if (hasFocus()) {
                    g.setColor(new Color(0x2d6b22));
                    g.drawRect(3, 3, width - 7, height - 10);
                }
            } finally { g.dispose(); }
        }
    }
    private final class NewsCard extends JPanel {
        private NewsService.Item item;
        private String imageBase64;
        private BufferedImage image;
        private BufferedImage savedImage;
        private boolean editing, busy, persisted = true;
        private final NewsMarkdownPane body = new NewsMarkdownPane();
        private final JButton more;
        private final PixelButton save = new PixelButton(t("common.edit"),
                PixelButton.Variant.NEUTRAL), publish = new PixelButton(t("news.publish"),
                PixelButton.Variant.PRIMARY), changeImage = new PixelButton(
                t("news.changeImage"), PixelButton.Variant.NEUTRAL);
        private final PixelButton cancel = new PixelButton(t("common.cancel"),
                PixelButton.Variant.NEUTRAL), delete = new PixelButton(
                t("common.delete"), PixelButton.Variant.DANGER);
        private final JLabel status = JourneyStyle.label("",
                11, new Color(0x646059), false);
        private int paperX, bodyX, bodyY, bodyWidth, bodyHeight;
        private final Runnable fixtureAction;
        NewsCard(NewsService.Item item, BufferedImage image,
                Runnable moreAction, int index) {
            super(null);
            this.item = item;
            this.image = image;
            this.savedImage = image;
            this.imageBase64 = item.imageBase64();
            fixtureAction = moreAction;
            setOpaque(false);
            setName("news-card-" + index);
            getAccessibleContext().setAccessibleName(title(item.markdown()));
            more = JourneyStyle.link(t("common.readMore"), () -> {
                toggle(this);
                if (fixtureAction != null) fixtureAction.run();
            });
            more.setForeground(new Color(0x2d6b22));
            more.setName(index == 0 ? "news-read-update" : "news-read-update-" + index);
            status.setName("news-status-" + index);
            status.setFont(LauncherFonts.minecraftFive(11));
            save.setName("news-save-" + index);
            publish.setName("news-publish-" + index);
            changeImage.setName("news-image-" + index);
            cancel.setName("news-cancel-" + index);
            delete.setName("news-delete-" + index);
            for (var button : List.of(save, publish, changeImage, cancel, delete)) {
                button.setFont(LauncherFonts.minecraftTen(14));
                button.setTextShadowPainted(false);
                add(button);
            }
            add(body);
            add(more);
            add(status);
            body.onChange(() -> {
                controls();
                revalidate();
                JourneyNewsPanel.this.revalidate();
                JourneyNewsPanel.this.doLayout();
                repaint();
            });
            save.addActionListener(e -> {
                if (!canEdit()) return;
                if (editing) saveDraft();
                else {
                    if (cards.stream().anyMatch(c -> c != this
                            && (c.editing || c.busy))) {
                        notice.setText(t("news.saveFirst"));
                        return;
                    }
                    expanded = this;
                    editing = true;
                    cards.forEach(NewsCard::updateBody);
                    JourneyNewsPanel.this.revalidate();
                    JourneyNewsPanel.this.doLayout();
                    repaint();
                }
            });
            publish.addActionListener(e -> {
                if (!administrator || session == null) return;
                URI origin = host;
                String token = session.accessToken();
                UUID id = this.item.id();
                mutate(() -> client.publish(origin, token, id), t("news.published"));
            });
            changeImage.addActionListener(e -> chooseImage());
            updateBody();
            cancel.addActionListener(e -> cancelEdit());
            delete.addActionListener(e -> deleteArticle());
        }
        private boolean open() { return expanded == this; }
        private boolean canEdit() {
            return contributor && fixtureAction == null && (administrator
                    || session != null && session.userId().equals(item.owner()));
        }
        private boolean dirty() {
            return !persisted || !body.markdown().equals(item.markdown())
                    || !Objects.equals(imageBase64, item.imageBase64());
        }
        private void controls() {
            boolean editor = canEdit();
            save.setVisible(editor);
            publish.setVisible(administrator && editor && open());
            changeImage.setVisible(editor && open());
            status.setVisible(editor && open());
            more.setVisible(!editing);
            cancel.setVisible(editor && open() && editing);
            delete.setVisible(administrator && editor && open() && persisted);
            cancel.setEnabled(!loading && !busy);
            delete.setEnabled(!loading && !busy && cards.stream().noneMatch(
                    c -> c != this && (c.editing || c.busy)));
            save.setText(editing ? t("common.save") : t("common.edit"));
            save.setEnabled(!loading && !busy && session != null
                    && (!editing || !body.markdown().isBlank()));
            changeImage.setEnabled(!loading && !busy && editing);
            publish.setEnabled(!loading && !busy && !editing && !dirty()
                    && item.draft() && persisted);
            publish.setText(!item.draft() && !dirty()
                    ? t("news.published") : t("news.publish"));
            status.setText(busy ? "Procesando…" : !item.draft() ? (dirty()
                    ? t("news.publishedDirty") : t("news.published")) : dirty()
                    ? t("news.draftDirty") : t("news.draftClean"));
            create.setEnabled(!loading && cards.stream().noneMatch(
                    c -> c.editing || c.busy));
        }
        private void updateBody() {
            String text = editing ? item.markdown() : open()
                    ? item.markdown() : summary(item.markdown());
            body.showMarkdown(text, editing);
            more.setText(open() ? t("common.readLess") : t("common.readMore"));
            controls();
        }
        private List<PixelButton> actions() {
            return List.of(changeImage, cancel, save, publish,
                    delete).stream().filter(Component::isVisible).toList();
        }
        private int actionWidth(PixelButton button) {
            return button == changeImage ? 150 : button == publish ? 102 : 100;
        }
        private List<List<PixelButton>> actionRows() {
            var rows = new ArrayList<List<PixelButton>> ();
            var row = new ArrayList<PixelButton>();
            int used = 0;
            for (var button : actions()) {
                int width = Math.min(bodyWidth, actionWidth(button));
                if (!row.isEmpty() && used + 10 + width > bodyWidth) {
                    rows.add(row);
                    row = new ArrayList<>();
                    used = 0;
                }
                if (!row.isEmpty()) used += 10;
                row.add(button);
                used += width;
            }
            if (!row.isEmpty()) rows.add(row);
            return rows;
        }
        private int measure(int width, boolean full) {
            paperX = full ? (width - contentWidth()) / 2 : 0;
            int paperWidth = width - 2 * paperX;
            int inset = full ? Math.max(28, (paperWidth - 720) / 2) : 24;
            bodyX = paperX + inset;
            bodyWidth = Math.max(10, paperWidth - 2 * inset);
            bodyY = (full ? 230 : (int)(width * .60)) + 56;
            body.setSize(bodyWidth, Short.MAX_VALUE);
            bodyHeight = body.getPreferredSize().height;
            return bodyY + bodyHeight + (canEdit() ? (full ? (30 + (editing ? 0
                    : 34) + actionRows().size() * 54 + 18) : 64) : 58) + 24;
        }
        int heightFor(int width, boolean full) { return measure(width, full); }
        @Override public void doLayout() {
            measure(getWidth(), open());
            body.setBounds(bodyX, bodyY, bodyWidth, bodyHeight);
            int bottom = getHeight() - 68;
            boolean admin = canEdit();
            more.setVisible(!editing);
            more.setBounds(bodyX, bottom, bodyWidth, 34);
            more.setHorizontalAlignment(SwingConstants.LEFT);
            if (open() && admin) {
                int y = bodyY + bodyHeight + 18;
                status.setBounds(bodyX, y, bodyWidth, 22);
                y += 28;
                if (!editing) {
                    more.setBounds(bodyX, y, bodyWidth, 28);
                    y += 34;
                }
                for (var row : actionRows()) {
                    int used = row.stream().mapToInt(button -> Math.min(bodyWidth,
                            actionWidth(button))).sum() + (row.size() - 1) * 10,
                            x = bodyX + bodyWidth - used;
                    for (var button : row) {
                        int w = Math.min(bodyWidth, actionWidth(button));
                        button.setBounds(x, y, w, 44);
                        x += w + 10;
                    }
                    y += 54;
                }
            } else if (!open()) {
                save.setBounds(Math.max(bodyX, getWidth() - bodyX - 100),
                        bottom, 100, 44);
                if (admin) more.setSize(Math.max(1, bodyWidth - 120), 34);
            }
        }
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create(); try {
                int imageHeight = open() ? 230 : (int)(getWidth() * .60),
                        paperWidth = getWidth() - 2 * paperX;
                JourneyStyle.cover(g, image == null ? JourneyStyle.COPPER
                        : image, new Rectangle(0, 0, getWidth(),
                        imageHeight + (open() ? 96 : 32)), null);
                g.setColor(JourneyStyle.PAPER);
                g.fillRect(paperX, imageHeight + 32, paperWidth, Math.max(0,
                        getHeight() - imageHeight - 32));
                NewsBlockEdge.paint(g, paperX, imageHeight - 64,
                        paperWidth, item.id().hashCode());
                g.setColor(new Color(0x244b1f));
                g.fillRect(paperX, getHeight() - 5, paperWidth, 5);
            } finally { g.dispose(); }
        }
        private void saveDraft() {
            String text = body.markdown(), picture = imageBase64;
            URI origin = host;
            String token = session.accessToken();
            UUID id = item.id();
            boolean create = !persisted;
            mutate(() -> client.save(origin, token, id, create,
                    text, picture), t("news.draftSaved"));
        }
        private void cancelEdit() {
            if (!editing || busy || loading) return;
            if (!persisted) {
                removeCard(this);
                notice.setText("");
                return;
            }
            image = savedImage;
            imageBase64 = item.imageBase64();
            editing = false;
            updateBody();
            notice.setText("");
            JourneyNewsPanel.this.revalidate();
            JourneyNewsPanel.this.doLayout();
            JourneyNewsPanel.this.repaint();
        }
        private void deleteArticle() {
            if (busy || loading || !administrator || session == null
                    || !persisted || cards.stream().anyMatch(c -> c != this
                    && (c.editing || c.busy))) return;
            if (!deleteConfirmation.confirm(this, title(item.markdown()),
                    !item.draft())) return;
            URI origin = host;
            String token = session.accessToken();
            UUID id = item.id();
            mutate(() -> {
                client.delete(origin, token, id);
                return null;
            }, t("news.deleted"));
        }
        private void mutate(NewsTask task, String done) {
            if (busy || !canEdit() || session == null) return;
            busy = true;
            body.setEditable(false);
            controls();
            long ticket = generation;
            var finished = new CompletableFuture<Void>();
            pending = finished;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                try {
                    if (ticket != generation) return;
                    busy = false;
                    if (error != null) {
                        notice.setText(message(error));
                        body.setEditable(editing);
                        controls();
                        return;
                    }
                    if (result == null) {
                        removeCard(this);
                        notice.setText(done);
                        return;
                    }
                    item = result;
                    persisted = true;
                    savedImage = image;
                    imageBase64 = item.imageBase64();
                    editing = false;
                    notice.setText(done);
                    updateBody();
                    JourneyNewsPanel.this.revalidate();
                    JourneyNewsPanel.this.doLayout();
                    JourneyNewsPanel.this.repaint();
                } finally { finished.complete(null); }
            }));
        }
        private void chooseImage() {
            if (!editing || busy) return;
            var chooser = new JFileChooser();
            chooser.setDialogTitle(t("news.changeImageTitle"));
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    t("news.imageFilter"), "png", "jpg", "jpeg"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            importImage(chooser.getSelectedFile().toPath());
        }
        void importImage(java.nio.file.Path path) {
            if (!editing || busy) return;
            busy = true;
            controls();
            long ticket = generation;
            var finished = new CompletableFuture<Void>();
            pending = finished;
            CompletableFuture.supplyAsync(() -> {
                try {
                    String encoded = LauncherNewsClient.importImage(path);
                    return new LoadedImage(encoded, LauncherNewsClient.image(encoded));
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).whenComplete((loaded, error) -> SwingUtilities.invokeLater(() -> {
                try {
                    if (ticket != generation) return;
                    busy = false;
                    if (error != null) notice.setText(message(error));
                    else {
                        imageBase64 = loaded.encoded;
                        image = loaded.image;
                        notice.setText(t("news.imageChanged"));
                    }
                    controls();
                    repaint();
                } finally {
                    finished.complete(null);
                }
            }));
        }
    }
    @FunctionalInterface private interface NewsTask {
        NewsService.Item run() throws Exception;
    }
    private static String title(String markdown) {
        return markdown.lines().findFirst().orElse(t(
                "news.fallbackTitle")).replaceFirst("^#{1,6}\\s+", "");
    }
    private static String summary(String markdown) {
        String[] parts = markdown.split("\\n\\s*\\n", 3);
        return parts.length > 1 ? parts[0] + "\n\n" + parts[1] : parts[0];
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

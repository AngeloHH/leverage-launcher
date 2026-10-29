package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

// Inventory thumbnails reuse the player renderer and one shared animation clock.
public final class SkinGalleryPanel extends JPanel implements Scrollable {
    private static final Color BACKGROUND = new Color(0xFAF4EF),
            TEXT = new Color(0x171615);
    private static final int CARD_HEIGHT = 260, GAP = 10, PADDING = 0;
    private final JButton importButton = new JButton(t("skins.add"));
    private List<LocalSkinLibrary.Entry> entries
            = new ArrayList<>(LocalSkinLibrary.defaults());
    private LocalSkinLibrary library;
    private String context;
    private String selectedId;
    private String activeId;
    private Boolean activeSlim;
    private BufferedImage currentImage;
    private boolean currentSlim;
    private boolean busy;
    private int overflowInset;
    private final javax.swing.Timer idleTimer = new javax.swing.Timer(40,
            event -> animateSkins());
    private long idleElapsedNanos, idleStartedAt;
    private long generation;
    private BiConsumer<String, Boolean> notice = (text, error) -> { };
    private BiConsumer<BufferedImage, Boolean> preview = (image, slim) -> { };
    private Function<LocalSkinLibrary.Entry, CompletableFuture<Void>> use
            = entry -> CompletableFuture.failedFuture(
            new IllegalStateException(t("skins.notConnected")));

    public SkinGalleryPanel() {
        setName("section-skins");
        setLayout(null);
        setBackground(BACKGROUND);
        importButton.setName("skin-import");
        importButton.setFont(LauncherFonts.minecraftSeven(14));
        importButton.setForeground(new Color(0x376B24));
        importButton.setContentAreaFilled(false);
        importButton.setBorder(BorderFactory.createEmptyBorder(10, 4, 10, 4));
        importButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        importButton.setToolTipText(t("skins.addTooltip"));
        importButton.getAccessibleContext().setAccessibleName(t("skins.addAccessible"));
        importButton.addActionListener(event -> importSkin());
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                revalidate();
            }
        });
        idleTimer.setCoalesce(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED)
                    != 0) syncIdleAnimation();
        });
        rebuild();
    }

    private void syncIdleAnimation() {
        if (isShowing() && !Boolean.getBoolean("leverage.reduceMotion")) {
            if (!idleTimer.isRunning()) {
                idleStartedAt = System.nanoTime();
                idleTimer.start();
            }
        } else pauseIdleAnimation();
    }
    private void pauseIdleAnimation() {
        if (idleTimer.isRunning()) {
            idleElapsedNanos += System.nanoTime() - idleStartedAt;
            idleTimer.stop();
        }
    }
    private double idleSeconds() {
        return (idleElapsedNanos + (idleTimer.isRunning() ? System.nanoTime()
                - idleStartedAt : 0)) / 1_000_000_000d;
    }
    private void animateSkins() {
        if (!isShowing() || Boolean.getBoolean("leverage.reduceMotion")) {
            pauseIdleAnimation();
            return;
        }
        Rectangle visible = getVisibleRect();
        double seconds = idleSeconds();
        for (Component child : getComponents()) if (child instanceof SkinCard card
                && visible.intersects(card.getBounds())) card.model.setIdlePose(
                card.idleMotion.sample(seconds));
        // Include model pixels drawn outside each card's hitbox.
        repaint(visible);
    }
    @Override public void removeNotify() {
        pauseIdleAnimation();
        super.removeNotify();
    }
    public void onNotice(BiConsumer<String, Boolean> handler) { notice = handler; }
    public void onPreview(BiConsumer<BufferedImage, Boolean> handler) {
        preview = handler;
    }
    public void onUse(Function<LocalSkinLibrary.Entry, CompletableFuture<Void>> handler) {
        use = handler;
    }
    // Move the import button between toolbars without duplicating its listeners.
    public JButton importButton() { return importButton; }

    public void configure(LauncherPreferences preferences, URI server, UUID user) {
        String key = InstanceLayout.serverScope(server) + ":" + user;
        if (Objects.equals(key, context)) {
            if (server == null) restoreActive();
            return;
        }
        context = key;
        long request = ++generation;
        busy = true;
        library = null;
        entries = new ArrayList<>(LocalSkinLibrary.defaults());
        activeId = null;
        activeSlim = null;
        rebuild();
        LocalSkinLibrary requestedLibrary = new LocalSkinLibrary(
                preferences.minecraftDirectory(), server, user);
        CompletableFuture.supplyAsync(() -> {
            try {
                return requestedLibrary.load();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((state, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != generation) return;
            busy = false;
            if (failure != null) {
                notice.accept(t("error.galleryRead", cause(failure).getMessage()), true);
                rebuild();
                return;
            }
            library = requestedLibrary;
            entries = new ArrayList<>(state.entries());
            activeId = state.activeId();
            activeSlim = state.activeSlim();
            if (server == null) restoreActive();
            rebuild();
        }));
    }

    private void restoreActive() {
        if (activeId != null) entries.stream().filter(entry -> entry.id().equals(
                activeId)).findFirst().ifPresent(entry -> {
            var active = activeSlim == null ? entry
                    : LocalSkinLibrary.withModel(entry, activeSlim);
            preview.accept(active.image(), active.slim());
        }); }

    public void setCurrentSkin(BufferedImage image, boolean slim) {
        currentImage = image;
        currentSlim = slim;
        var match = entries.stream().filter(entry -> entry.id().equals(activeId)
                && isCurrent(entry)).findFirst().orElseGet(() -> entries.stream().filter(
                this::isCurrent).findFirst().orElse(null));
        activeId = match == null ? null : match.id();
        activeSlim = match == null ? null : match.slim();
        rebuild();
    }

    private boolean isCurrent(LocalSkinLibrary.Entry entry) {
        if (currentImage == null || entry.slim() != currentSlim
                || entry.image().getHeight() != currentImage.getHeight()) return false;
        int height = currentImage.getHeight();
        return Arrays.equals(currentImage.getRGB(0, 0, 64, height, null, 0,
                64), entry.image().getRGB(0, 0, 64, height, null, 0, 64));
    }
    private boolean isActive(LocalSkinLibrary.Entry entry) {
        return activeId == null ? isCurrent(entry) : entry.id().equals(activeId)
                && (activeSlim == null || activeSlim == entry.slim());
    }

    private List<LocalSkinLibrary.Entry> visibleEntries() {
        List<LocalSkinLibrary.Entry> visible = new ArrayList<>(entries);
        if (currentImage != null && entries.stream().noneMatch(
                this::isCurrent)) visible.add(new LocalSkinLibrary.Entry("server-current",
                t("skins.current"), null, currentSlim, currentImage));
        return visible;
    }

    private void rebuild() {
        boolean inlineImport = importButton.getParent() == null
                || importButton.getParent() == this;
        removeAll();
        for (var entry : visibleEntries()) add(new SkinCard(entry));
        importButton.setEnabled(!busy && library != null && entries.size() < 128);
        if (inlineImport) add(importButton);
        revalidate();
        repaint();
    }

    void select(String id) {
        selectedId = id;
        for (Component component : getComponents())
                if (component instanceof SkinCard card) card.updateSelection();
        repaint();
    }

    private void useSkin(LocalSkinLibrary.Entry entry) {
        long request = generation;
        busy = true;
        rebuild();
        CompletableFuture<Void> operation;
        try {
            operation = use.apply(entry);
        } catch (Exception failure) {
            operation = CompletableFuture.failedFuture(failure);
        }
        operation.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != generation) return;
            busy = false;
            if (failure != null) {
                notice.accept(t("error.skinApply", cause(failure).getMessage()), true);
                rebuild();
                return;
            }
            activeId = entry.id();
            activeSlim = entry.slim();
            preview.accept(entry.image(), entry.slim());
            persist(t("skins.applied"));
        }));
    }

    private void duplicate(LocalSkinLibrary.Entry entry) {
        if (entries.size() >= 128) {
            notice.accept(t("skins.limit"), true);
            return;
        }
        var copy = LocalSkinLibrary.duplicate(entry);
        entries.add(copy);
        selectedId = copy.id();
        persist(t("skins.duplicated"));
    }

    private boolean canRemove(LocalSkinLibrary.Entry entry) {
        return !entry.id().equals("server-current") && !entry.id().equals(activeId)
                && !(activeId == null && isCurrent(entry));
    }
    private void confirmRemove(LocalSkinLibrary.Entry entry) {
        if (busy || library == null || !canRemove(entry)) return;
        int answer = JOptionPane.showConfirmDialog(this,
                t("skins.removeConfirm", entry.name()), t("skins.removeTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) removeEntry(
                entry.id()).exceptionally(failure -> null);
    }

    // Removes a local library entry only. Neither the imported source nor its managed PNG
    // is deleted.
    CompletableFuture<Void> removeEntry(String id) {
        if (busy || library == null) return CompletableFuture.failedFuture(
                new IllegalStateException(t("skins.notReady")));
        var entry = entries.stream().filter(value -> value.id().equals(
                id)).findFirst().orElse(null);
        if (entry == null || !canRemove(entry)) return CompletableFuture.failedFuture(
                new IllegalArgumentException(t("skins.cannotRemove")));
        List<LocalSkinLibrary.Entry> updated = entries.stream().filter(
                value -> !value.id().equals(id)).toList();
        LocalSkinLibrary requestedLibrary = library;
        String active = activeId;
        Boolean slim = activeSlim;
        long request = generation;
        busy = true;
        rebuild();
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                requestedLibrary.save(updated, active, slim);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                if (request != generation) {
                    result.complete(null);
                    return;
                }
                busy = false;
                if (failure == null) {
                    entries = new ArrayList<>(updated);
                    if (id.equals(selectedId)) selectedId = entries.isEmpty()
                        ? null : entries.getFirst().id();
                    notice.accept(t("skins.removed"), false);
                    result.complete(null);
            }
                else {
                    notice.accept(t("error.librarySave",
                        cause(failure).getMessage()), true);
                    result.completeExceptionally(cause(failure));
            }
                rebuild();
        }));
        return result;
    }

    private void toggle(LocalSkinLibrary.Entry entry) {
        if (entry.id().equals("server-current") && entries.size() >= 128) {
            notice.accept(t("skins.limit"), true);
            return;
        }
        LocalSkinLibrary.Entry changed = LocalSkinLibrary.toggleModel(entry);
        if (entry.id().equals("server-current")) {
            changed = new LocalSkinLibrary.Entry(UUID.randomUUID().toString(),
                    t("skins.currentCopy"), null, changed.slim(), changed.image());
            entries.add(changed);
            selectedId = changed.id();
        } else for (int i = 0; i < entries.size(); i++) if (entries.get(
                i).id().equals(entry.id())) entries.set(i, changed);
        persist(t("skins.modelChanged"));
    }

    private void importSkin() {
        if (busy || library == null || entries.size() >= 128) return;
        long beforeDialog = generation;
        JFileChooser picker = filePicker();
        if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION
                || generation != beforeDialog) return;
        importFiles(Arrays.stream(picker.getSelectedFiles()).map(
                java.io.File::toPath).toList());
    }

    static JFileChooser filePicker() {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle(t("skins.addTooltip"));
        picker.setAcceptAllFileFilterUsed(false);
        picker.setMultiSelectionEnabled(true);
        picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                t("skins.pngFilter"), "png"));
        return picker;
    }

    CompletableFuture<Void> importFiles(List<Path> selected) {
        if (busy || library == null || selected.isEmpty())
                return CompletableFuture.completedFuture(null);
        List<Path> sources = List.copyOf(selected);
        var snapshot = List.copyOf(entries);
        var requestedLibrary = library;
        String active = activeId;
        Boolean slim = activeSlim;
        long request = generation;
        busy = true;
        rebuild();
        notice.accept("", false);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
            var result = FileImportBatch.run(sources, 128 - snapshot.size(),
                    LocalSkinLibrary::importPng);
            if (!result.added().isEmpty()) {
                var updated = new ArrayList<>(snapshot);
                updated.addAll(result.added());
                try {
                    requestedLibrary.save(updated, active, slim);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }
            return result;
        }).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != generation) {
                completion.complete(null);
                return;
            }
            busy = false;
            if (failure != null) notice.accept(t("error.gallerySave",
                    cause(failure).getMessage()), true);
            else {
                entries.addAll(result.added());
                if (!result.added().isEmpty()) selectedId = result.added().getLast().id();
                notice.accept(result.hasErrors() ? result.message() : "",
                        result.hasErrors());
            }
            rebuild();
            completion.complete(null);
        }));
        return completion;
    }

    private void persist(String message) {
        if (library == null) {
            rebuild();
            notice.accept(message, false);
            return;
        }
        LocalSkinLibrary requestedLibrary = library;
        List<LocalSkinLibrary.Entry> snapshot = List.copyOf(entries);
        String active = activeId;
        Boolean slim = activeSlim;
        long request = generation;
        busy = true;
        rebuild();
        CompletableFuture.runAsync(() -> {
            try {
                requestedLibrary.save(snapshot, active, slim);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != generation) return;
            busy = false;
            rebuild();
            notice.accept(failure == null ? message : t("error.gallerySave",
                    cause(failure).getMessage()), failure != null);
        }));
    }

    private static Throwable cause(Throwable value) {
        while (value.getCause() != null) value = value.getCause();
        return value;
    }
    void setOverflowInset(int pixels) {
        int value = Math.max(0, pixels);
        if (value != overflowInset) {
            overflowInset = value;
            revalidate();
            repaint();
        }
    }
    private int columns() {
        int width = Math.max(1, getWidth() == 0 ? 900 : getWidth() - overflowInset * 2);
        return width >= 900 ? 6 : width >= 610 ? 3 : 2;
    }
    @Override public Dimension getPreferredSize() {
        int rows = (getComponentCount() + columns() - 1) / columns();
        return new Dimension(600, PADDING * 2 + rows * (CARD_HEIGHT + GAP) - GAP);
    }
    @Override public void doLayout() {
        int columns = columns();
        int width = Math.max(1, (getWidth() - overflowInset * 2
                - PADDING * 2 - (columns - 1) * GAP) / columns);
        for (int i = 0; i < getComponentCount(); i++) {
            Component child = getComponent(i);
            int x = overflowInset + PADDING + (i % columns) * (width + GAP),
                    y = PADDING + (i / columns) * (CARD_HEIGHT + GAP);
            if (child instanceof SkinCard) child.setBounds(x, y, width, CARD_HEIGHT);
            else child.setBounds(x, y + 76, width, 44);
        } }
    @Override public boolean isOptimizedDrawingEnabled() { return false; }
    @Override protected void paintChildren(Graphics graphics) {
        // First all surfaces, then un-clipped models, then all labels and hover controls.
        // A neighbouring card must not erase a hand that crosses its logical boundary.
        for (Component child : getComponents()) if (child instanceof SkinCard card
                && card.isVisible()) {
            graphics.setColor(card.getBackground());
            graphics.fillRect(card.getX(), card.getY(),
                    card.getWidth(), card.getHeight());
        }
        Rectangle clip = graphics.getClipBounds();
        for (Component child : getComponents()) if (child instanceof SkinCard card
                && card.isVisible()) {
            Rectangle reach = card.getBounds();
            reach.grow(40, 8);
            if (clip != null && !clip.intersects(reach)) continue;
            Graphics modelGraphics = graphics.create();
            try {
                modelGraphics.translate(card.getX() + card.model.getX(),
                        card.getY() + card.model.getY());
                card.model.paintOverflow(modelGraphics);
            }
            finally { modelGraphics.dispose(); }
        }
        super.paintChildren(graphics);
    }
    @Override public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(600, 460);
    }
    @Override public int getScrollableUnitIncrement(Rectangle r,
            int orientation, int direction) {
        return 24;
    }
    @Override public int getScrollableBlockIncrement(Rectangle r,
            int orientation, int direction) {
        return Math.max(24, r.height - 24);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    final class SkinCard extends JPanel {
        final LocalSkinLibrary.Entry entry;
        final JLabel title;
        final PlayerSkinView model = new PlayerSkinView();
        final SkinIdleMotion idleMotion;
        final PixelButton apply = new PixelButton(t("skins.use"),
                PixelButton.Variant.PRIMARY);
        final PixelButton copy = new PixelButton(t("skins.duplicate"),
                PixelButton.Variant.NEUTRAL);
        final PixelButton remove = new PixelButton(t("common.delete"),
                PixelButton.Variant.DANGER);
        final PixelDropdown<String> modelChoice = new PixelDropdown<>(
                List.of(t("skins.classic"), t("skins.slim")));
        final JLabel modelLabel = new JLabel(t("skins.model")),
                active = new JLabel(t("skins.inUse"), SwingConstants.CENTER);
        final JPanel overlay = new JPanel(null) {
            @Override protected void paintComponent(Graphics graphics) {
                graphics.setColor(new Color(0xE1, 0xD8, 0xCF, 238));
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        SkinCard(LocalSkinLibrary.Entry entry) {
            this.entry = entry;
            setLayout(null);
            setOpaque(false);
            setFocusable(true);
            setName("skin-" + entry.id());
            idleMotion = new SkinIdleMotion(entry.id());
            getAccessibleContext().setAccessibleName("Skin " + entry.name());
            title = new JLabel(entry.name(), SwingConstants.CENTER);
            title.putClientProperty("html.disable", true);
            title.setFont(LauncherFonts.minecraftSeven(18f));
            add(title);
            model.setSkin(entry.image(), entry.slim());
            model.setGalleryPainted(true);
            add(model);
            if (!Boolean.getBoolean("leverage.reduceMotion")) model.setIdlePose(
                    idleMotion.sample(idleSeconds()));
            for (PixelButton button : new PixelButton[] { apply, copy, remove }) {
                button.setTextShadowPainted(false);
                button.setFont(LauncherFonts.minecraftFive(13f));
                button.setMinimumSize(new Dimension(0, 0));
            }
            overlay.setName("skin-overlay-" + entry.id());
            overlay.setOpaque(false);
            add(overlay, 0);
            overlay.add(apply);
            overlay.add(copy);
            overlay.add(remove);
            modelLabel.setFont(LauncherFonts.minecraftSeven(11));
            modelLabel.setForeground(new Color(0x5E584E));
            overlay.add(modelLabel);
            modelChoice.setName("skin-model-" + entry.id());
            modelChoice.getAccessibleContext().setAccessibleName(t(
                    "skins.modelNamed", entry.name()));
            modelChoice.setFont(LauncherFonts.minecraftFive(12));
            modelChoice.setMinimumSize(new Dimension(0, 0));
            modelChoice.setSelectedIndex(entry.slim() ? 1 : 0);
            overlay.add(modelChoice);
            modelChoice.addActionListener(event -> {
                if ((modelChoice.getSelectedIndex() == 1) != entry.slim()
                        && !busy) toggle(entry);
            });
            active.setFont(LauncherFonts.minecraftFive(12));
            active.setForeground(new Color(0x376B24));
            add(active);
            apply.addActionListener(event -> useSkin(entry));
            copy.addActionListener(event -> duplicate(entry));
            remove.setName("skin-delete-" + entry.id());
            remove.addActionListener(event -> confirmRemove(entry));
            MouseAdapter selection = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    select(entry.id());
                }
                @Override public void mouseExited(MouseEvent event) {
                    Point point = SwingUtilities.convertPoint(event.getComponent(),
                            event.getPoint(), SkinCard.this);
                    if (!contains(point)) SwingUtilities.invokeLater(
                            () -> hideIfInactive());
                    }
                @Override public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        select(entry.id());
                        if (event.getComponent() == SkinCard.this
                                || event.getComponent() == model
                                || event.getComponent() == title) requestFocusInWindow();
                    } } };
            installInteraction(this, selection);
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "use");
            getActionMap().put("use", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    if (apply.isEnabled()) useSkin(entry);
                }
            });
            updateSelection();
        }
        void updateSelection() {
            boolean selected = entry.id().equals(selectedId);
            setBackground(selected ? new Color(0xE1D8CF) : BACKGROUND);
            overlay.setVisible(selected);
            title.setForeground(TEXT);
            apply.setVisible(selected);
            copy.setVisible(selected);
            modelLabel.setVisible(selected);
            modelChoice.setVisible(selected);
            active.setVisible(isActive(entry) && !selected);
            remove.setVisible(selected && canRemove(entry));
            remove.setEnabled(!busy && library != null && canRemove(entry));
            apply.setText(isActive(entry) ? t("skins.inUse") : t("skins.use"));
            apply.setEnabled(!busy && !isActive(entry));
            copy.setEnabled(!busy && library != null && entries.size() < 128);
            modelChoice.setEnabled(!busy && library != null
                    && entry.image().getHeight() == 64 && (!entry.id().equals(
                    "server-current") || entries.size() < 128));
        }
        private void hideIfInactive() {
            Component focus =
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (entry.id().equals(selectedId) && !modelChoice.isPopupVisible()
                    && (focus == null || !SwingUtilities.isDescendingFrom(focus, this))) {
                selectedId = null;
                updateSelection();
            } }
        private void installInteraction(Component component, MouseAdapter mouse) {
            component.addMouseListener(mouse);
            component.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) {
                    select(entry.id());
                }
                @Override public void focusLost(FocusEvent event) {
                    SwingUtilities.invokeLater(() -> hideIfInactive());
                }
            });
            if (component instanceof Container container) for (Component child
                    : container.getComponents()) installInteraction(child, mouse);
        }
        @Override public void doLayout() {
            title.setBounds(6, 10, getWidth() - 12, 26);
            model.setBounds(0, 32, getWidth(), 220);
            active.setBounds(6, 232, getWidth() - 12, 24);
            overlay.setBounds(6, 72, getWidth() - 12, 180);
            int width = Math.max(0, overlay.getWidth() - 8);
            modelLabel.setBounds(4, 0, width, 18);
            modelChoice.setBounds(4, 18, width, 34);
            apply.setBounds(4, 58, width, 32);
            copy.setBounds(4, 96, width, 32);
            remove.setBounds(4, 134, width, 32);
        }
    }
}

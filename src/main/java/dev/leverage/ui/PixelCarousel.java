package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.EventListenerList;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Reusable carousel, with either image/thumbnail navigation or expandable text/data. Use
// on the EDT.
public final class PixelCarousel extends JPanel {
    public enum Mode { IMAGES, DATA }
    // Images belong to the caller and must not be changed while displayed. Null image is
    // valid for data-only items.
    public record Item(String title, String metadata,
            String description, BufferedImage image) {
        public Item {
            if (Objects.requireNonNull(title).isBlank())
                    throw new IllegalArgumentException("El título no puede estar vacío.");
            metadata = Objects.requireNonNull(metadata);
            description = Objects.requireNonNull(description);
        }
    }
    private static final Color BACKGROUND = new Color(0x0e0d0d),
            GREEN = new Color(0x6cc349);
    private final EventListenerList changes = new EventListenerList();
    private final ImageView imageView = new ImageView();
    private final JPanel thumbs = new JPanel(null);
    private final ButtonGroup group = new ButtonGroup();
    private final List<Thumb> buttons = new ArrayList<>();
    private final JLabel title = new JLabel(), metadata = new JLabel(),
            empty = new JLabel(t("common.noContent"), SwingConstants.CENTER);
    private final JTextArea description = new JTextArea();
    private final MoreButton more = new MoreButton();
    private List<Item> items = List.of();
    private Mode mode;
    private int selectedIndex = -1, padding = 24, thumbnailWidth = 78,
            thumbnailGap = 16, collapsedLines = 4, thumbScroll;
    private boolean expanded;

    public PixelCarousel() { this(Mode.IMAGES); }
    public PixelCarousel(Mode mode) {
        super(null);
        this.mode = Objects.requireNonNull(mode);
        setOpaque(true);
        setBackground(BACKGROUND);
        getAccessibleContext().setAccessibleName(t("carousel.accessible"));
        thumbs.setOpaque(false);
        imageView.setName("carousel-image");
        thumbs.setName("carousel-thumbnails");
        title.setName("carousel-title");
        title.setFont(LauncherFonts.minecraftTen(26));
        title.setForeground(Color.WHITE);
        metadata.setName("carousel-metadata");
        metadata.setFont(LauncherFonts.minecraftSeven(20));
        metadata.setForeground(Color.WHITE);
        title.putClientProperty("html.disable", true);
        metadata.putClientProperty("html.disable", true);
        description.setName("carousel-description");
        description.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        description.setForeground(Color.WHITE);
        description.setOpaque(false);
        description.setEditable(false);
        description.setFocusable(true);
        description.setBorder(null);
        description.setMargin(new Insets(0, 0, 0, 0));
        description.setLineWrap(false);
        description.getAccessibleContext().setAccessibleName(t("common.description"));
        empty.setForeground(new Color(0xaba09c));
        empty.setFont(LauncherFonts.minecraftSeven(16));
        more.setName("carousel-more");
        more.addActionListener(event -> setExpanded(!expanded));
        add(imageView);
        add(thumbs);
        add(title);
        add(metadata);
        add(description);
        add(more);
        add(empty);
        bind(imageView, "LEFT", -1, false);
        bind(imageView, "RIGHT", 1, false);
        bind(thumbs, "UP", -1, false);
        bind(thumbs, "DOWN", 1, false);
        bind(thumbs, "LEFT", -1, false);
        bind(thumbs, "RIGHT", 1, false);
        for (JComponent owner : new JComponent[] { imageView, thumbs }) {
            bind(owner, "HOME", 1, true);
            bind(owner, "END", -1, true);
            owner.addMouseWheelListener(this::wheel);
        }
        imageView.setFocusable(true);
        updateContent();
    }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> value) {
        List<Item> copy = List.copyOf(value);
        // Validate before mutating the existing selection.
        Item old = getSelectedItem();
        int oldIndex = selectedIndex;
        for (var button : buttons) group.remove(button);
        buttons.clear();
        thumbs.removeAll();
        items = copy;
        selectedIndex = items.isEmpty() ? -1 : 0;
        expanded = false;
        thumbScroll = 0;
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            Thumb button = new Thumb(i);
            buttons.add(button);
            group.add(button);
            thumbs.add(button);
            button.setEnabled(isEnabled());
            button.addActionListener(event -> {
                if (isEnabled()) setSelectedIndex(index);
            });
        }
        updateContent();
        revalidate();
        repaint();
        if (oldIndex != selectedIndex || !Objects.equals(old,
                getSelectedItem())) fireChange();
    }
    public Mode getMode() { return mode; }
    public void setMode(Mode value) {
        mode = Objects.requireNonNull(value);
        updateContent();
        revalidate();
        repaint();
    }
    public int getSelectedIndex() { return selectedIndex; }
    public Item getSelectedItem() {
        return selectedIndex < 0 ? null : items.get(selectedIndex);
    }
    public void setSelectedIndex(int index) {
        Objects.checkIndex(index, items.size());
        if (index == selectedIndex) return;
        selectedIndex = index;
        expanded = false;
        updateContent();
        revalidate();
        doLayout();
        repaint();
        fireChange();
    }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean value) {
        boolean previous = expanded;
        expanded = value && selectedIndex >= 0;
        more.setText(expanded ? t("common.showLess") : t("common.showMore"));
        more.getAccessibleContext().setAccessibleDescription(expanded
                ? t("carousel.expanded") : t("carousel.collapsed"));
        revalidate();
        doLayout();
        repaint();
        firePropertyChange("expanded", previous, expanded);
    }
    public int getContentPadding() { return padding; }
    public void setContentPadding(int pixels) {
        range(pixels, 8, 96);
        padding = pixels;
        geometryChanged();
    }
    public int getThumbnailWidth() { return thumbnailWidth; }
    public void setThumbnailWidth(int pixels) {
        range(pixels, 40, 160);
        thumbnailWidth = pixels;
        geometryChanged();
    }
    public int getThumbnailGap() { return thumbnailGap; }
    public void setThumbnailGap(int pixels) {
        range(pixels, 0, 64);
        thumbnailGap = pixels;
        geometryChanged();
    }
    public int getCollapsedLineCount() { return collapsedLines; }
    public void setCollapsedLineCount(int lines) {
        range(lines, 1, 20);
        collapsedLines = lines;
        geometryChanged();
    }
    public void addChangeListener(ChangeListener listener) {
        changes.add(ChangeListener.class, Objects.requireNonNull(listener));
    }
    public void removeChangeListener(ChangeListener listener) {
        changes.remove(ChangeListener.class, listener);
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (buttons != null) {
            for (var button : buttons) button.setEnabled(enabled);
            more.setEnabled(enabled);
            imageView.setEnabled(enabled);
            updateFocus();
        }
    }
    private void geometryChanged() {
        revalidate();
        doLayout();
        repaint();
    }
    private static void range(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(
                "Valor fuera de rango.");
    }
    private void fireChange() {
        var event = new ChangeEvent(this);
        for (var listener : changes.getListeners(
                ChangeListener.class)) listener.stateChanged(event);
    }
    private void updateContent() {
        boolean has = selectedIndex >= 0, images = mode == Mode.IMAGES;
        imageView.setVisible(has && images);
        thumbs.setVisible(has && images);
        title.setVisible(has && !images);
        metadata.setVisible(has && !images && !getSelectedItem().metadata().isEmpty());
        description.setVisible(has && !images);
        more.setVisible(false);
        empty.setVisible(!has);
        empty.setText(images ? t("common.noImages") : t("common.noContent"));
        if (has) {
            Item item = getSelectedItem();
            title.setText(item.title());
            title.setToolTipText(item.title());
            metadata.setText(item.metadata());
            imageView.getAccessibleContext().setAccessibleName(item.title());
            imageView.setToolTipText(item.title());
            description.getAccessibleContext().setAccessibleDescription(
                    item.description());
        } else {
            title.setText("");
            metadata.setText("");
            description.setText("");
        }
        for (int i = 0; i < buttons.size(); i++) buttons.get(
                i).setSelected(i == selectedIndex);
        more.setText(expanded ? t("common.showLess") : t("common.showMore"));
        updateFocus();
    }
    private void updateFocus() {
        for (int i = 0; i < buttons.size(); i++) buttons.get(
                i).setFocusable(isEnabled() && i == selectedIndex);
        imageView.setFocusable(isEnabled());
    }
    private void bind(JComponent owner, String key, int direction, boolean edge) {
        String action = "carousel-" + key;
        owner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(key), action);
        owner.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(key), action);
        owner.getActionMap().put(action, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                navigate(direction, edge);
                if (selectedIndex >= 0) buttons.get(selectedIndex).requestFocusInWindow();
            }
        });
    }
    private void navigate(int direction, boolean edge) {
        if (!isEnabled() || mode != Mode.IMAGES || items.isEmpty()) return;
        setSelectedIndex(edge ? direction > 0 ? 0 : items.size() - 1
                : Math.floorMod(selectedIndex + direction, items.size()));
    }
    private void wheel(MouseWheelEvent event) {
        if (isEnabled() && mode == Mode.IMAGES && items.size() > 1
                && event.getWheelRotation() != 0) {
            navigate(event.getWheelRotation() > 0 ? 1 : -1, false);
            event.consume();
        }
    }
    @Override public Dimension getPreferredSize() {
        if (mode == Mode.IMAGES) return new Dimension(625, 337);
        int width = getWidth() > 0 ? getWidth() : 876;
        int lines = getSelectedItem() == null ? 1 : CarouselTextLayout.wrap(
                getSelectedItem().description(), description.getFontMetrics(
                LauncherFonts.forLocale(description.getFont())),
                Math.max(1, width - 2 * padding)).size();
        int height = padding * 2 + titleHeight() + metadataHeight() + 20 + (expanded
                ? lines : Math.min(lines, collapsedLines)) * lineHeight();
        return new Dimension(876, Math.max(120, height));
    }
    @Override public Dimension getMinimumSize() {
        return new Dimension(220, mode == Mode.IMAGES ? 160 : 120);
    }
    private int titleHeight() {
        return title.getFontMetrics(LauncherFonts.forLocale(title.getFont())).getHeight();
    }
    private int metadataHeight() {
        return metadata.isVisible() ? metadata.getFontMetrics(
                LauncherFonts.forLocale(metadata.getFont())).getHeight() : 0;
    }
    private int lineHeight() {
        return description.getFontMetrics(LauncherFonts.forLocale(
                description.getFont())).getHeight();
    }
    @Override public void doLayout() {
        int width = Math.max(0, getWidth() - padding * 2),
                height = Math.max(0, getHeight() - padding * 2);
        empty.setBounds(padding, padding, width, height);
        if (selectedIndex < 0) return;
        if (mode == Mode.IMAGES) {
            int thumbWidth = Math.min(thumbnailWidth, Math.max(24, width / 4));
            int gap = Math.min(24, Math.max(4, width / 20)),
                    mainWidth = Math.max(0, width - thumbWidth - gap);
            int mainHeight = Math.min(height, Math.round(mainWidth * 9f / 16));
            imageView.setBounds(padding, padding, mainWidth, mainHeight);
            thumbs.setBounds(padding + mainWidth + gap, padding, thumbWidth, height);
            int thumbHeight = Math.max(20, Math.round(thumbWidth * 9f / 16)),
                    step = thumbHeight + thumbnailGap;
            int total = buttons.size() * step - thumbnailGap;
            thumbScroll = Math.max(0, Math.min(thumbScroll, Math.max(0, total - height)));
            int top = selectedIndex * step;
            if (top < thumbScroll) thumbScroll = top;
            else if (top + thumbHeight > thumbScroll + height) thumbScroll
                    = top + thumbHeight - height;
            for (int i = 0; i < buttons.size(); i++) buttons.get(i).setBounds(0,
                    i * step - thumbScroll, thumbWidth, thumbHeight);
            return;
        }
        var metrics = description.getFontMetrics(LauncherFonts.forLocale(
                description.getFont()));
        List<String> all = CarouselTextLayout.wrap(getSelectedItem().description(),
                metrics, width);
        boolean overflow = all.size() > collapsedLines;
        more.setVisible(overflow);
        int toggleWidth = Math.min(width, more.getPreferredSize().width),
                toggleHeight = Math.max(28, titleHeight());
        more.setBounds(padding + width - toggleWidth, padding, toggleWidth, toggleHeight);
        title.setBounds(padding, padding, Math.max(0, width - (overflow
                ? toggleWidth + 16 : 0)), titleHeight());
        metadata.setBounds(padding, padding + titleHeight(), width, metadataHeight());
        int count = expanded ? all.size() : Math.min(collapsedLines, all.size());
        List<String> visible = new ArrayList<>(all.subList(0, count));
        if (overflow && !expanded && !visible.isEmpty()) visible.set(visible.size() - 1,
                CarouselTextLayout.ellipsize(visible.getLast(), metrics, width));
        String display = String.join("\n", visible);
        if (!display.equals(description.getText())) {
            description.setText(display);
            description.setCaretPosition(0);
        }
        int y = padding + titleHeight() + metadataHeight() + 20;
        description.setBounds(padding, y, width, Math.max(0,
                Math.min(count * lineHeight(), getHeight() - padding - y)));
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        int w = getWidth(), h = getHeight();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // CSS paints the second shadow behind the first: top/right black,
            // then left/bottom white at 10%. Do not replace the overlay with a fixed
            // gray.
            int x = Math.min(4, w), y = Math.min(4, h);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, y);
            g.fillRect(w - x, 0, x, h);
            g.setComposite(AlphaComposite.SrcOver.derive(.1f));
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, x, h);
            // Exclude the left strip so the bottom-left corner is not composited twice.
            g.fillRect(x, h - y, Math.max(0, w - x), y);
        } finally { g.dispose(); }
    }
    private static void paintImage(Graphics2D g, BufferedImage image,
            Rectangle bounds, boolean cover) {
        if (image == null || bounds.width <= 0 || bounds.height <= 0) return;
        double sx = (double) bounds.width / image.getWidth(), sy = (double) bounds.height
                / image.getHeight(), scale = cover ? Math.max(sx, sy) : Math.min(sx, sy);
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale)),
                h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        Graphics2D copy = (Graphics2D) g.create();
        try {
            copy.clip(bounds);
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            copy.drawImage(image, bounds.x + (bounds.width - w) / 2,
                    bounds.y + (bounds.height - h) / 2, w, h, null);
        } finally {
            copy.dispose();
        }
    }
    private final class ImageView extends JPanel {
        ImageView() { setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Item item = getSelectedItem();
            if (item == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (item.image() != null) paintImage(g, item.image(),
                        new Rectangle(0, 0, getWidth(), getHeight()), false);
                else {
                    g.setColor(new Color(0x262423));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.WHITE);
                    g.setFont(LauncherFonts.forLocale(LauncherFonts.minecraftSeven(15)));
                    String text = t("common.noImage");
                    g.drawString(I18n.resolve(text), Math.max(0,
                            (getWidth() - g.getFontMetrics().stringWidth(
                            I18n.resolve(text))) / 2), getHeight() / 2);
                }
                if (hasFocus()) {
                    g.setColor(GREEN);
                    g.drawRect(0, 0, Math.max(0, getWidth() - 1),
                            Math.max(0, getHeight() - 1));
                }
            } finally { g.dispose(); }
        }
    }
    private final class Thumb extends JToggleButton {
        private final int index;
        Thumb(int index) {
            this.index = index;
            setName("carousel-thumbnail-" + index);
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(items.get(index).title());
            getAccessibleContext().setAccessibleName(items.get(index).title());
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int w = getWidth(), h = getHeight(), inset = isSelected() ? 6 : 0;
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, w, h);
                BufferedImage image = items.get(index).image();
                if (image != null) paintImage(g, image, new Rectangle(inset, inset,
                        Math.max(0, w - 2 * inset), Math.max(0, h - 2 * inset)), true);
                else {
                    g.setColor(new Color(0x262423));
                    g.fillRect(inset, inset, Math.max(0, w - inset * 2),
                            Math.max(0, h - inset * 2));
                }
                if (isSelected() || hasFocus()) {
                    g.setColor(GREEN);
                    g.fillRect(0, 0, w, 2);
                    g.fillRect(0, h - 2, w, 2);
                    g.fillRect(0, 0, 2, h);
                    g.fillRect(w - 2, 0, 2, h);
                }
                if (!isEnabled()) {
                    g.setColor(new Color(0, 0, 0, 120));
                    g.fillRect(0, 0, w, h);
                }
            } finally { g.dispose(); }
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleThumb();
            return accessibleContext;
        }
        protected class AccessibleThumb extends AccessibleJToggleButton {
            @Override public AccessibleRole getAccessibleRole() {
                return AccessibleRole.PAGE_TAB;
            }
            @Override public AccessibleStateSet getAccessibleStateSet() {
                var states = super.getAccessibleStateSet();
                if (isSelected()) states.add(AccessibleState.SELECTED);
                return states;
            }
        }
    }
    private final class MoreButton extends JButton {
        MoreButton() {
            super(t("common.showMore"));
            setFont(LauncherFonts.minecraftSeven(16));
            setForeground(GREEN);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(LauncherFonts.forLocale(
                    getFont())).stringWidth(I18n.resolve(expanded
                    ? t("common.showLess") : t("common.showMore"))) + 24, 28);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(LauncherFonts.forLocale(getFont()));
                g.setColor(isEnabled() ? GREEN : GREEN.darker());
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                g.drawString(I18n.resolve(getText()), 0,
                        (getHeight() - g.getFontMetrics().getHeight()) / 2
                        + g.getFontMetrics().getAscent());
                int x = getWidth() - 11, y = (getHeight() - 6) / 2;
                if (hasFocus()) g.fillRect(0, getHeight() - 2, getWidth(), 2);
                if (expanded) {
                    g.translate(x + 11, y + 6);
                    g.rotate(Math.PI);
                    x = 0;
                    y = 0;
                }
                SvgIconLibrary.paint(g, "pixel-chevron-down", x, y, 11,
                        6, isEnabled() ? GREEN : GREEN.darker());
            } finally { g.dispose(); }
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleMore();
            return accessibleContext;
        }
        protected class AccessibleMore extends AccessibleJButton {
            @Override public AccessibleStateSet getAccessibleStateSet() {
                var states = super.getAccessibleStateSet();
                states.add(expanded ? AccessibleState.EXPANDED
                        : AccessibleState.COLLAPSED);
                return states;
            }
        }
    }
}

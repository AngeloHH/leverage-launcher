package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

// Recessed Minecraft dropdown, retaining native Swing popup, model, keyboard and
// accessibility behavior.
public final class PixelDropdown<T> extends JComboBox<T> {
    private static final Color FACE = PixelInputStyle.FACE,
            OUTLINE = PixelInputStyle.OUTLINE;
    private static final Color ROW = new Color(0x6b6562), CURRENT = new Color(0x56514e);
    private static final Color INSET = new Color(0x817c7a),
            OFF_BLACK = new Color(0x171615), GREEN = new Color(0x6cc349);
    private Function<? super T, String> label;
    private String placeholder = t("common.select");
    private boolean arrowOnly;
    private boolean embedded;
    public void setEmbedded(boolean value) {
        embedded = value;
        setBorder(value ? BorderFactory.createEmptyBorder()
                : PixelInputStyle.outlineBorder());
        revalidate();
        repaint();
    }
    public void setArrowOnly(boolean value) {
        arrowOnly = value;
        repaint();
    }

    public PixelDropdown(List<T> options) { this(options, String::valueOf); }
    public PixelDropdown(List<T> options, Function<? super T, String> label) {
        super(new DefaultComboBoxModel<>());
        this.label = Objects.requireNonNull(label);
        setFont(PixelInputStyle.font());
        setForeground(Color.WHITE);
        setBackground(FACE);
        setOpaque(true);
        setBorder(PixelInputStyle.outlineBorder());
        setRenderer(new OptionRenderer());
        setMaximumRowCount(3);
        setKeySelectionManager(new LabelKeys());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(t("dropdown.selectAccessible"));
        for (T option : List.copyOf(options)) addItem(option);
    }
    @Override public void updateUI() { setUI(new DropdownUI()); }
    @Override public void setEditable(boolean editable) {
        if (editable) throw new IllegalArgumentException(
                "El dropdown es de selección, no de edición.");
        super.setEditable(false);
    }
    public String getPlaceholder() { return placeholder; }
    public void setPlaceholder(String text) {
        placeholder = Objects.requireNonNull(text);
        revalidate();
        repaint();
    }
    @Override public void setMaximumRowCount(int count) {
        if (count < 1) throw new IllegalArgumentException(
                "Debe verse al menos una opción.");
        super.setMaximumRowCount(count);
    }
    @Override public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) return super.getPreferredSize();
        FontMetrics metrics = getFontMetrics(LauncherFonts.forLocale(getFont()));
        int width = metrics.stringWidth(I18n.resolve(placeholder == null
                ? "" : placeholder));
        for (int i = 0; i < getItemCount(); i++) width = Math.max(width,
                metrics.stringWidth(I18n.resolve(display(getItemAt(i)))));
        return new Dimension(Math.max(135, width + 60), 48);
    }
    @Override public Dimension getMinimumSize() {
        return isMinimumSizeSet() ? super.getMinimumSize() : new Dimension(135, 48);
    }
    @Override public Dimension getMaximumSize() {
        return isMaximumSizeSet() ? super.getMaximumSize()
                : new Dimension(Integer.MAX_VALUE, 48);
    }
    @Override public void setPopupVisible(boolean visible) {
        if (visible && (!isEnabled() || getItemCount() == 0)) return;
        super.setPopupVisible(visible);
        repaint();
    }
    @Override public void setEnabled(boolean enabled) {
        if (!enabled && getUI() != null) setPopupVisible(false);
        super.setEnabled(enabled);
        repaint();
    }
    @SuppressWarnings("unchecked") private String display(Object value) {
        return I18n.resolve(value == null ? placeholder == null ? ""
                : placeholder : label == null ? value.toString()
                : Objects.requireNonNullElse(label.apply((T) value), ""));
    }

    // Gallery-only inspection view. Uses the exact renderer and scrollbar used by the
    // actual popup.
    JComponent createOptionsPreview(int visibleRows) {
        if (visibleRows < 1) throw new IllegalArgumentException(
                "Debe verse al menos una opción.");
        JList<T> list = new JList<>();
        list.setModel(new AbstractListModel<>() {
            @Override public int getSize() { return getItemCount(); }
            @Override public T getElementAt(int index) { return getItemAt(index); }
        });
        list.setCellRenderer(getRenderer());
        list.setFixedCellHeight(48);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(getSelectedIndex());
        list.setFont(getFont());
        list.setBackground(ROW);
        boolean[] updating = { false };
        list.addListSelectionListener(event -> {
            if (!updating[0] && !event.getValueIsAdjusting() && isEnabled()
                    && list.getSelectedIndex() >= 0) setSelectedIndex(
                    list.getSelectedIndex());
        });
        addItemListener(event -> {
            updating[0] = true;
            try {
                list.setSelectedIndex(getSelectedIndex());
                list.repaint();
            } finally {
                updating[0] = false;
            }
        });
        addPropertyChangeListener("enabled", event -> list.setEnabled(isEnabled()));
        JScrollPane scroll = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            private boolean firstLayout = true;
            @Override public void doLayout() {
                super.doLayout();
                getViewport().doLayout();
                if (firstLayout && getViewport().getHeight() > 0) {
                    int maximum = Math.max(0, list.getHeight()
                            - getViewport().getHeight());
                    getViewport().setViewPosition(new Point(0, Math.min(maximum,
                            Math.max(0, getSelectedIndex()) * 48)));
                    firstLayout = false;
                }
            }
        };
        styleScroller(scroll);
        scroll.setPreferredSize(new Dimension(434, visibleRows * 48));
        return scroll;
    }

    private final class DropdownUI extends BasicComboBoxUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            padding = new Insets(0, 0, 0, 0);
            squareButton = false;
        }
        @Override protected ComboPopup createPopup() {
            return new BasicComboPopup(comboBox) {
                @Override protected void configureList() {
                    super.configureList();
                    // The real popup must not inherit FlatLaf's rounded selection or
                    // renderer insets.
                    list.setUI(new javax.swing.plaf.basic.BasicListUI());
                    list.setFixedCellHeight(48);
                    list.setBorder(null);
                    list.setBackground(ROW);
                    list.setSelectionBackground(CURRENT);
                    list.setSelectionForeground(Color.WHITE);
                }
                @Override protected void configureScroller() {
                    super.configureScroller();
                    styleScroller(scroller);
                }
                @Override protected void configurePopup() {
                    super.configurePopup();
                    setBorder(BorderFactory.createEmptyBorder());
                    putClientProperty("Popup.borderCornerRadius", 0);
                    putClientProperty("Popup.dropShadowPainted", false);
                }
                @Override protected Rectangle computePopupBounds(int x,
                        int y, int width, int height) {
                    int popupWidth = arrowOnly ? Math.max(210,
                            comboBox.getWidth()) : comboBox.getWidth();
                    return super.computePopupBounds(arrowOnly ? comboBox.getWidth()
                            - popupWidth : 0, comboBox.getHeight(), popupWidth, height);
                }
                @Override public void show() {
                    if (!PixelDropdown.this.isEnabled() || getItemCount() == 0) return;
                    super.show();
                    // Keep the selected option at the start when room remains below it,
                    // as in the reference.
                    int maximum = Math.max(0, list.getHeight()
                            - scroller.getViewport().getHeight());
                    scroller.getViewport().setViewPosition(new Point(0, Math.min(maximum,
                            Math.max(0, getSelectedIndex()) * 48)));
                    if (arrowButton != null) arrowButton.repaint();
                }
                @Override public void hide() {
                    super.hide();
                    if (arrowButton != null) arrowButton.repaint();
                }
            };
        }
        @Override protected JButton createArrowButton() {
            JButton arrow = new JButton() {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setColor(PixelDropdown.this.isEnabled()
                                ? new Color(0xd0d1d4) : new Color(0x898481));
                        int x = (getWidth() - 14) / 2, y = (getHeight() - 8) / 2;
                        for (int row = 0; row < 4; row++) {
                            int step = PixelDropdown.this.isPopupVisible()
                                    ? 3 - row : row;
                            g.fillRect(x + step * 2, y + row * 2, 14 - step * 4, 2);
                        }
                    } finally { g.dispose(); }
                }
            };
            arrow.setName("pixel-dropdown-arrow");
            arrow.setOpaque(false);
            arrow.setBorder(null);
            arrow.setContentAreaFilled(false);
            arrow.setFocusable(false);
            arrow.setPreferredSize(new Dimension(40, 44));
            arrow.getAccessibleContext().setAccessibleName(t(
                    "dropdown.toggleAccessible"));
            return arrow;
        }
        @Override protected LayoutManager createLayoutManager() {
            return new ComboBoxLayoutManager() {
                @Override public void layoutContainer(Container parent) {
                    if (arrowButton != null) arrowButton.setBounds(Math.max(2,
                            parent.getWidth() - 42), 2, 40, Math.max(0,
                            parent.getHeight() - 4));
                }
            };
        }
        @Override protected Rectangle rectangleForCurrentValue() {
            return new Rectangle(2, 2, Math.max(0, comboBox.getWidth() - 44),
                    Math.max(0, comboBox.getHeight() - 4));
        }
        @Override public void paint(Graphics graphics, JComponent component) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(FACE);
                g.fillRect(0, 0, component.getWidth(), component.getHeight());
                if (!embedded) PixelInputStyle.paintShadow(g, 0, 0, component.getWidth(),
                        component.getHeight());
                super.paint(g, component);
                if (comboBox.hasFocus() && !PixelDropdown.this.isPopupVisible()) {
                    g.setColor(GREEN);
                    g.fillRect(14, component.getHeight() - 5, Math.max(0,
                            component.getWidth() - 58), 1);
                }
            } finally { g.dispose(); }
        }
        @Override public void paintCurrentValueBackground(Graphics graphics,
                Rectangle bounds, boolean focused) {
            // preserve the inset shadow
        }
    }

    private final class OptionRenderer extends JLabel implements ListCellRenderer<T> {
        private int index = -1;
        private boolean highlighted, committed;
        OptionRenderer() {
            setOpaque(false);
            putClientProperty("html.disable", true);
        }
        @Override public Component getListCellRendererComponent(JList<? extends T> list,
                T value, int index, boolean selected, boolean focused) {
            this.index = index;
            highlighted = selected;
            committed = index >= 0 && index == getSelectedIndex();
            setText(index < 0 ? arrowOnly ? "" : display(value)
                    : display(value).toUpperCase(Locale.ROOT));
            setFont(LauncherFonts.forText(PixelDropdown.this.getFont(), getText()));
            setForeground(PixelDropdown.this.isEnabled()
                    ? Color.WHITE : new Color(0xaba09c));
            setBorder(BorderFactory.createEmptyBorder(0,
                    index < 0 ? 12 : 14, 0, index < 0 ? 0 : 32));
            return this;
        }
        @Override public Dimension getPreferredSize() {
            return new Dimension(Math.max(135, super.getPreferredSize().width), 48);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                if (index >= 0) {
                    int w = getWidth(), h = getHeight();
                    g.setColor(highlighted ? CURRENT : ROW);
                    g.fillRect(0, 0, w, h);
                    g.setColor(OFF_BLACK);
                    g.fillRect(0, 0, 2, h);
                    g.fillRect(0, Math.max(0, h - 2), w, 2);
                    g.setColor(INSET);
                    g.fillRect(2, 0, Math.max(0, w - 2), 2);
                    g.fillRect(Math.max(0, w - 2), 0, 2, Math.max(0, h - 2));
                }
                super.paintComponent(g);
                if (committed) {
                    g.setColor(GREEN);
                    int x = getWidth() - 24, y = (getHeight() - 12) / 2;
                    for (int i = 0; i < 4; i++) g.fillRect(x + i, y + 6 + i, 1, 2);
                    for (int i = 0; i < 11; i++) g.fillRect(x + 4 + i, y + 9 - i, 1, 2);
                }
            } finally { g.dispose(); }
        }
    }

    private static void styleScroller(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(ROW);
        JScrollBar bar = new GreenScrollBar();
        scroll.setVerticalScrollBar(bar);
        bar.setPreferredSize(new Dimension(6, 48));
        bar.setUnitIncrement(24);
        bar.setBlockIncrement(48);
        bar.setBorder(BorderFactory.createLineBorder(OFF_BLACK, 1));
    }
    private static final class GreenScrollBar extends JScrollBar {
        GreenScrollBar() { super(VERTICAL); }
        @Override public void updateUI() { setUI(new GreenScrollBarUI()); }
    }
    private static final class GreenScrollBarUI extends BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int orientation) {
            return noArrow();
        }
        @Override protected JButton createIncreaseButton(int orientation) {
            return noArrow();
        }
        private static JButton noArrow() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setFocusable(false);
            return button;
        }
        @Override protected Dimension getMinimumThumbSize() {
            return new Dimension(4, 40);
        }
        @Override protected void paintTrack(Graphics graphics,
                JComponent component, Rectangle bounds) {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        @Override protected void paintThumb(Graphics graphics,
                JComponent component, Rectangle bounds) {
            if (bounds.isEmpty()) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int bevel = Math.min(2, Math.max(1, (bounds.width - 2) / 2));
                g.setColor(GREEN);
                g.fill(bounds);
                g.setComposite(AlphaComposite.SrcOver.derive(.25f));
                g.setColor(Color.BLACK);
                g.fillRect(bounds.x, bounds.y, bevel, bounds.height);
                g.setColor(Color.WHITE);
                g.fillRect(bounds.x + bounds.width - bevel,
                        bounds.y, bevel, bounds.height);
            } finally { g.dispose(); }
        }
    }
    private final class LabelKeys implements KeySelectionManager {
        private String prefix = "";
        private long lastTyped;
        @Override public int selectionForKey(char key, ComboBoxModel<?> model) {
            if (Character.isISOControl(key) || model.getSize() == 0) return -1;
            long now = System.nanoTime();
            String character = String.valueOf(key).toUpperCase(Locale.ROOT);
            boolean repeat = prefix.equals(character);
            prefix = now - lastTyped > 1_000_000_000L || repeat
                    ? character : prefix + character;
            lastTyped = now;
            int selected = getSelectedIndex(), start = repeat || prefix.length() == 1
                    ? selected + 1 : Math.max(0, selected);
            for (int offset = 0; offset < model.getSize(); offset++) {
                int index = Math.floorMod(start + offset, model.getSize());
                if (display(model.getElementAt(index)).toUpperCase(
                        Locale.ROOT).startsWith(prefix)) return index;
            }
            prefix = "";
            return -1;
        }
    }
}

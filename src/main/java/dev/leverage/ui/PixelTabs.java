package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.EventListenerList;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

// Minecraft-style tabs and content cards. Mutate on the Swing event-dispatch thread.
public final class PixelTabs extends JPanel {
    private record Tab(String id, TabButton button,
            JComponent content, boolean enabled) { }
    private static final String EMPTY = "empty";
    private final List<Tab> tabs = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final EventListenerList changes = new EventListenerList();
    private final JPanel deck = new JPanel(new CardLayout());
    private final Header header;
    private final Timer timer;
    private final LongSupplier clock;
    private int selectedIndex = -1, nextId;
    private int tabHeight = 64, gap = 24, horizontalPadding = 16,
            maximumStripWidth = 1920;
    private Font tabFont = LauncherFonts.minecraftTen(27);
    private Color activeColor = new Color(0x86d562), inactiveColor = new Color(0xaba09c);
    private boolean animationEnabled = true;
    private TabIndicatorMotion.Bounds indicator, target, start;
    private long startedAt;

    public PixelTabs() { this(System::nanoTime); }
    PixelTabs(LongSupplier clock) {
        super(new BorderLayout());
        this.clock = Objects.requireNonNull(clock);
        setBackground(new Color(0x171615));
        deck.setOpaque(false);
        deck.add(emptyPanel(), EMPTY);
        timer = new Timer(16, event -> pulseIndicator());
        timer.setCoalesce(true);
        header = new Header();
        add(header, BorderLayout.NORTH);
        add(deck, BorderLayout.CENTER);
    }
    public void addTab(String title, JComponent content) {
        title = title(title);
        Objects.requireNonNull(content);
        if (tabs.stream().anyMatch(tab -> tab.content() == content))
                throw new IllegalArgumentException(
                "El contenido ya pertenece a una pestaña.");
        String id = "tab-" + nextId++;
        TabButton button = new TabButton(title);
        tabs.add(new Tab(id, button, content, true));
        group.add(button);
        header.add(button);
        deck.add(content, id);
        button.setEnabled(isEnabled());
        button.addActionListener(event -> {
            if (isEnabled()) setSelectedIndex(indexOf(button));
            else updateSelectionState();
        });
        if (selectedIndex < 0) select(tabs.size() - 1, false);
        else updateSelectionState();
        revalidate();
        repaint();
    }
    // A header-only tab can be bound to application navigation with a ChangeListener.
    public void addTab(String title) { addTab(title, emptyPanel()); }
    public int getTabCount() { return tabs.size(); }
    public int getSelectedIndex() { return selectedIndex; }
    public String getTitleAt(int index) { return tabs.get(index).button().getText(); }
    public JComponent getComponentAt(int index) { return tabs.get(index).content(); }
    public JComponent getTabBar() { return header; }
    public void setSelectedIndex(int index) {
        Tab tab = tabs.get(index);
        if (!tab.enabled()) throw new IllegalArgumentException(
                "La pestaña está deshabilitada.");
        select(index, true);
    }
    // Application utility pages may live outside the tab set without inventing a selected
    // tab.
    public void clearSelection() { select(-1, false); }
    public void setTitleAt(int index, String title) {
        String value = title(title);
        var button = tabs.get(index).button();
        button.setText(value);
        button.getAccessibleContext().setAccessibleName(value);
        header.revalidate();
        revalidate();
        repaint();
    }
    public boolean isTabEnabledAt(int index) { return tabs.get(index).enabled(); }
    public void setTabEnabledAt(int index, boolean enabled) {
        Tab tab = tabs.get(index);
        tabs.set(index, new Tab(tab.id(), tab.button(), tab.content(), enabled));
        tab.button().setEnabled(enabled && isEnabled());
        if (selectedIndex == index && !enabled) select(nextEnabled(index, 1), true);
        else if (selectedIndex < 0 && enabled) select(index, false);
        updateSelectionState();
        repaint();
    }
    public void removeTabAt(int index) {
        int previousIndex = selectedIndex;
        Tab oldSelected = selectedIndex < 0 ? null : tabs.get(selectedIndex);
        Tab removed = tabs.remove(index);
        group.remove(removed.button());
        header.remove(removed.button());
        deck.remove(removed.content());
        int next = oldSelected == removed ? nextEnabled(Math.min(index, tabs.size()) - 1,
                1) : oldSelected == null ? -1 : tabs.indexOf(oldSelected);
        selectedIndex = next;
        updateSelectionState();
        header.selectionChanged(false);
        if (previousIndex != next || oldSelected == removed) fireChange();
        revalidate();
        repaint();
    }
    public void addChangeListener(ChangeListener listener) {
        changes.add(ChangeListener.class, Objects.requireNonNull(listener));
    }
    public void removeChangeListener(ChangeListener listener) {
        changes.remove(ChangeListener.class, listener);
    }
    public boolean isAnimationEnabled() { return animationEnabled; }
    public void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
        if (!enabled) finishAnimation();
    }
    public Font getTabFont() { return tabFont; }
    public void setTabFont(Font font) {
        tabFont = Objects.requireNonNull(font);
        header.revalidate();
        revalidate();
        repaint();
    }
    public int getTabHeight() { return tabHeight; }
    public void setTabHeight(int pixels) {
        bounded(pixels, 40, 160);
        tabHeight = pixels;
        header.revalidate();
        revalidate();
        repaint();
    }
    public int getTabGap() { return gap; }
    public void setTabGap(int pixels) {
        bounded(pixels, 0, 128);
        gap = pixels;
        header.revalidate();
        revalidate();
        repaint();
    }
    public int getTabHorizontalPadding() { return horizontalPadding; }
    public void setTabHorizontalPadding(int pixels) {
        bounded(pixels, 0, 128);
        horizontalPadding = pixels;
        header.revalidate();
        revalidate();
        repaint();
    }
    public int getMaximumStripWidth() { return maximumStripWidth; }
    public void setMaximumStripWidth(int pixels) {
        bounded(pixels, 100, 10000);
        maximumStripWidth = pixels;
        header.revalidate();
        revalidate();
        repaint();
    }
    public void setActiveColor(Color color) {
        activeColor = Objects.requireNonNull(color);
        header.repaint();
    }
    public Color getActiveColor() { return activeColor; }
    public void setInactiveColor(Color color) {
        inactiveColor = Objects.requireNonNull(color);
        header.repaint();
    }
    public Color getInactiveColor() { return inactiveColor; }
    public void setHeaderBackground(Color color) {
        header.setBackground(Objects.requireNonNull(color));
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (tabs != null) {
            for (var tab : tabs) tab.button().setEnabled(enabled && tab.enabled());
            updateSelectionState();
            if (!enabled && timer != null) finishAnimation();
        }
    }
    @Override public void removeNotify() {
        finishAnimation();
        super.removeNotify();
    }

    private void select(int index, boolean animate) {
        if (index == selectedIndex) {
            updateSelectionState();
            return;
        }
        selectedIndex = index;
        updateSelectionState();
        header.selectionChanged(animate);
        fireChange();
    }
    private void fireChange() {
        ChangeEvent event = new ChangeEvent(this);
        for (var listener : changes.getListeners(
                ChangeListener.class)) listener.stateChanged(event);
    }
    private void updateSelectionState() {
        if (selectedIndex < 0) group.clearSelection();
        for (int i = 0; i < tabs.size(); i++) {
            var button = tabs.get(i).button();
            button.setSelected(i == selectedIndex);
            button.setFocusable(i == selectedIndex && button.isEnabled());
        }
        ((CardLayout) deck.getLayout()).show(deck, selectedIndex < 0
                ? EMPTY : tabs.get(selectedIndex).id());
    }
    private int indexOf(TabButton button) {
        for (int i = 0; i < tabs.size(); i++)
                if (tabs.get(i).button() == button) return i;
        return -1;
    }
    private int nextEnabled(int from, int direction) {
        for (int step = 1; step <= tabs.size(); step++) {
            int index = Math.floorMod(from + step * direction, tabs.size());
            if (isTabEnabledAt(index)) return index;
        }
        return -1;
    }
    private void navigate(int direction, boolean edge) {
        if (!isEnabled()) return;
        int index = edge ? nextEnabled(direction > 0 ? -1 : tabs.size(),
                direction) : nextEnabled(selectedIndex, direction);
        if (index >= 0) {
            select(index, true);
            tabs.get(index).button().requestFocusInWindow();
        }
    }
    private void moveIndicator(TabIndicatorMotion.Bounds value, boolean animate) {
        if (Objects.equals(value, target)) return;
        pulseIndicator();
        target = value;
        if (value == null || indicator == null || !animate || !animationEnabled
                || !isEnabled() || !header.isShowing()) {
            finishAnimation();
            return;
        }
        start = indicator;
        startedAt = clock.getAsLong();
        timer.start();
        header.repaint();
    }
    private void finishAnimation() {
        timer.stop();
        indicator = target;
        if (header != null) header.repaint();
    }
    void pulseIndicator() {
        if (!timer.isRunning()) return;
        if (!header.isShowing()) {
            finishAnimation();
            return;
        }
        double milliseconds = (clock.getAsLong() - startedAt) / 1_000_000d;
        indicator = TabIndicatorMotion.sample(start, target, milliseconds);
        if (milliseconds >= 500) finishAnimation();
        else header.repaint();
    }
    boolean isIndicatorAnimating() { return timer.isRunning(); }
    TabIndicatorMotion.Bounds indicatorBounds() { return indicator; }
    private static String title(String value) {
        if (Objects.requireNonNull(value).isBlank())
                throw new IllegalArgumentException("Título vacío.");
        return value;
    }
    private static void bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(
                "Valor fuera de rango.");
    }
    private static JPanel emptyPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(0, 0));
        return panel;
    }

    private final class Header extends JPanel {
        private int scroll;
        Header() {
            super(null);
            setBackground(new Color(0x171615));
            setFocusable(false);
            getAccessibleContext().setAccessibleName(t("tabs.accessible"));
            bind("LEFT", -1, false);
            bind("RIGHT", 1, false);
            bind("HOME", 1, true);
            bind("END", -1, true);
            addMouseWheelListener(event -> {
                if (event.getWheelRotation() != 0) {
                    navigate(event.getWheelRotation() > 0 ? 1 : -1, false);
                    event.consume();
                }
            });
            addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                        && !isShowing()) finishAnimation();
            });
        }
        private void bind(String key, int direction, boolean edge) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                    KeyStroke.getKeyStroke(key), key);
            getActionMap().put(key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    navigate(direction, edge);
                }
            });
        }
        int stripWidth() { return Math.min(getWidth(), maximumStripWidth); }
        int stripLeft() { return (getWidth() - stripWidth()) / 2; }
        int railY() { return tabHeight - 15; }
        @Override public Dimension getPreferredSize() {
            int width = 40;
            for (var tab : tabs) width += getFontMetrics(LauncherFonts.forLocale(
                    tabFont)).stringWidth(I18n.resolve(tab.button().getText()))
                    + 2 * horizontalPadding;
            return new Dimension(Math.min(maximumStripWidth, width + Math.max(0,
                    tabs.size() - 1) * gap), tabHeight);
        }
        @Override public Dimension getMinimumSize() {
            return new Dimension(100, tabHeight);
        }
        private void arrange() {
            int total = 0;
            for (var tab : tabs) total += getFontMetrics(LauncherFonts.forLocale(
                    tabFont)).stringWidth(I18n.resolve(tab.button().getText()))
                    + 2 * horizontalPadding;
            total += Math.max(0, tabs.size() - 1) * gap;
            int width = stripWidth(), available = Math.max(1, width - 40);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, total - available)));
            if (selectedIndex >= 0) {
                int selectedLeft = 0;
                for (int i = 0; i < selectedIndex; i++) selectedLeft += getFontMetrics(
                        LauncherFonts.forLocale(tabFont)).stringWidth(I18n.resolve(
                        tabs.get(i).button().getText())) + 2 * horizontalPadding + gap;
                int selectedWidth = getFontMetrics(LauncherFonts.forLocale(
                        tabFont)).stringWidth(I18n.resolve(tabs.get(
                        selectedIndex).button().getText())) + 2 * horizontalPadding;
                if (selectedWidth > available || selectedLeft < scroll) scroll
                        = selectedLeft;
                else if (selectedLeft + selectedWidth > scroll + available) scroll
                        = selectedLeft + selectedWidth - available;
            }
            int x = stripLeft() + 20 - scroll;
            for (var tab : tabs) {
                int itemWidth = getFontMetrics(LauncherFonts.forLocale(
                        tabFont)).stringWidth(I18n.resolve(tab.button().getText()))
                        + 2 * horizontalPadding;
                tab.button().setBounds(x, 0, itemWidth, Math.max(0, railY()));
                x += itemWidth + gap;
            }
        }
        private TabIndicatorMotion.Bounds selectedBounds() {
            if (selectedIndex < 0 || getWidth() <= 0) return null;
            Rectangle bounds = tabs.get(selectedIndex).button().getBounds();
            return new TabIndicatorMotion.Bounds(bounds.x, bounds.width);
        }
        void selectionChanged(boolean animate) {
            arrange();
            moveIndicator(selectedBounds(), animate);
            repaint();
        }
        @Override public void doLayout() {
            arrange();
            moveIndicator(selectedBounds(), false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            int x = stripLeft(), width = stripWidth(), y = railY();
            graphics.setColor(Color.BLACK);
            graphics.fillRect(x, y, width, 8);
            graphics.setColor(new Color(0x131211));
            graphics.fillRect(x, y + 1, width, 2);
            graphics.setColor(new Color(0x262423));
            graphics.fillRect(x, y + 3, width, 4);
            if (indicator != null) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.clipRect(x, y + 1, width, 6);
                    int left = (int) Math.round(indicator.left()),
                            size = (int) Math.round(indicator.width());
                    g.setColor(new Color(0xa0e081));
                    g.fillRect(left, y + 1, size, 2);
                    g.setColor(new Color(0x86d562));
                    g.fillRect(left, y + 3, size, 2);
                    g.setColor(new Color(0x649f49));
                    g.fillRect(left, y + 5, size, 2);
                } finally { g.dispose(); }
            }
        }
        @Override protected void paintChildren(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.clipRect(stripLeft(), 0, stripWidth(), Math.max(0, railY()));
                super.paintChildren(g);
            } finally {
                g.dispose();
            }
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleHeader();
            return accessibleContext;
        }
        protected class AccessibleHeader extends AccessibleJPanel {
            @Override public AccessibleRole getAccessibleRole() {
                return AccessibleRole.PAGE_TAB_LIST;
            }
        }
    }

    private final class TabButton extends JToggleButton {
        TabButton(String title) {
            super(title);
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(title);
        }
        @Override public boolean contains(int x, int y) {
            int global = getX() + x;
            return super.contains(x, y) && global >= header.stripLeft()
                    && global < header.stripLeft() + header.stripWidth();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                g.setFont(LauncherFonts.forLocale(tabFont));
                Color text = isSelected() ? activeColor : getModel().isRollover()
                        && isEnabled() ? inactiveColor.brighter() : inactiveColor;
                if (!isEnabled()) text = new Color(text.getRed(),
                        text.getGreen(), text.getBlue(), 90);
                g.setColor(text);
                var metrics = g.getFontMetrics();
                g.drawString(I18n.resolve(getText()), (getWidth()
                        - metrics.stringWidth(I18n.resolve(getText()))) / 2, (getHeight()
                        - metrics.getHeight()) / 2 + metrics.getAscent() - 2);
                if (hasFocus()) {
                    g.setColor(activeColor);
                    g.fillRect(horizontalPadding, Math.max(0, getHeight() - 6),
                            Math.max(0, getWidth() - 2 * horizontalPadding), 2);
                }
            } finally { g.dispose(); }
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleTab();
            return accessibleContext;
        }
        protected class AccessibleTab extends AccessibleJToggleButton {
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
}

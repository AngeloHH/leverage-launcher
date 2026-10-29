package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

final class JourneyLibraryPanel extends JPanel {
    private final JourneySlideDeck deck = new JourneySlideDeck();
    private final JourneyVersionPanel versions;
    private final JComponent[] pages;
    private final JToggleButton[] tabs = new JToggleButton[4];
    private final JPanel plate = new JPanel(new GridLayout(1, 0, 0, 0));
    private final Consumer<LauncherHomePanel.Section> section;
    private int selected;
    private boolean administrator;

    JourneyLibraryPanel(InstanceSelectionPanel instances,
            ModListPanel mods, ModListPanel resources, UsersPanel users,
            Consumer<LauncherHomePanel.Section> section) {
        super(new BorderLayout());
        setName("journey-library");
        setBackground(JourneyStyle.INK);
        this.section = section;
        versions = new JourneyVersionPanel(instances);
        pages = new JComponent[] { versions, catalog(mods,
                JourneyStyle.INK, "section-mods"), catalog(resources,
                JourneyStyle.TABLE, "section-resourcepacks"), catalog(users,
                JourneyStyle.INK, "section-users") };
        add(deck, BorderLayout.CENTER);
        deck.showPage(pages[0], false, true, 0);
        plate.setName("library-subtabs");
        plate.setPreferredSize(new Dimension(0, 58));
        plate.setBackground(JourneyStyle.TABLE);
        ButtonGroup group = new ButtonGroup();
        String[] titles = { t("nav.versions"), t("nav.mods"),
                t("nav.resources"), t("nav.users") };
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            JToggleButton button = new JToggleButton(titles[i]) {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create(); try {
                        g.setColor(isSelected() ? Color.WHITE : JourneyStyle.TABLE);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(isSelected() ? new Color(0xbfbfbf)
                                : new Color(0, 0, 0, 68));
                        g.fillRect(0, 0, getWidth(), 8);
                        if (getModel().isRollover() && !isSelected()) {
                            g.setColor(new Color(255, 255, 255, 15));
                            g.fillRect(0, 12, getWidth(), getHeight() - 12);
                        }
                        g.setFont(LauncherFonts.forLocale(
                                LauncherFonts.minecraftTen(23)));
                        g.setColor(isSelected() ? JourneyStyle.TABLE
                                : new Color(0xfffaf4));
                        var fm = g.getFontMetrics();
                        g.drawString(I18n.resolve(getText()), (getWidth()
                                - fm.stringWidth(I18n.resolve(getText()))) / 2,
                                (getHeight() - fm.getHeight()) / 2 + fm.getAscent() + 3);
                        if (hasFocus()) {
                            g.setColor(JourneyStyle.GREEN);
                            g.drawRect(8, 14, getWidth() - 17, getHeight() - 23);
                        }
                    } finally { g.dispose(); }
                }
            };
            button.setName("library-tab-" + i);
            button.setBorderPainted(false);
            button.setContentAreaFilled(false);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setSelected(i == 0);
            button.addActionListener(e -> {
                selectTab(index);
                section.accept(sectionAt(index));
            });
            group.add(button);
            tabs[i] = button;
        }
        rebuildTabs();
        add(plate, BorderLayout.SOUTH);
    }

    void setAdministrator(boolean enabled) {
        if (administrator == enabled) return;
        administrator = enabled;
        if (!enabled && selected == 3) {
            selectTab(0);
            section.accept(LauncherHomePanel.Section.VERSIONS);
        }
        rebuildTabs();
    }
    private void rebuildTabs() {
        plate.removeAll();
        for (int i = 0; i <(administrator ? 4 : 3); i++) plate.add(tabs[i]);
        plate.revalidate();
        plate.repaint();
    }
    private static LauncherHomePanel.Section sectionAt(int index) {
        return switch (index) {
            case 1 -> LauncherHomePanel.Section.MODS;
            case 2 -> LauncherHomePanel.Section.RESOURCEPACKS;
            case 3 -> LauncherHomePanel.Section.USERS;
            default -> LauncherHomePanel.Section.VERSIONS;
        };
    }
    void selectTab(int index) {
        if (index < 0 || index > 3 || index == 3 && !administrator)
                throw new IllegalArgumentException("Pestaña no disponible");
        int old = selected;
        selected = index;
        tabs[index].setSelected(true);
        deck.showPage(pages[index], index < old, true, Math.min(old,
                index) * 31L + Math.max(old, index) * 7L + 8);
    }
    void setAnimationEnabled(boolean enabled) {
        deck.setAnimationEnabled(enabled);
        versions.setAnimationEnabled(enabled);
    }
    void previewTabForTest(int index, double sample) {
        if (index < 0 || index > 3 || index == 3 && !administrator)
                throw new IllegalArgumentException("Pestaña no disponible");
        int old = selected;
        selected = index;
        tabs[index].setSelected(true);
        deck.previewPageForTest(pages[index], index < old, true, Math.min(old,
                index) * 31L + Math.max(old, index) * 7L + 8, sample);
    }
    private static JComponent catalog(JComponent content, Color color, String name) {
        JPanel panel = new CatalogPage();
        panel.setName(name);
        panel.setBackground(color);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        panel.add(content, content instanceof UsersPanel
                ? BorderLayout.CENTER : BorderLayout.NORTH);
        if (content instanceof UsersPanel) return panel;
        JScrollPane scroll = JourneyStyle.scroll(panel, color);
        scroll.setBackground(color);
        return scroll;
    }

    // Track the viewport even after a wider gallery was previously laid out.
    private static final class CatalogPage extends JPanel implements Scrollable {
        CatalogPage() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
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
}

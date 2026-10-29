package dev.leverage.ui;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

// The one live global tab strip, moved between the foot and the top of the scene.
final class JourneyNavigation extends JPanel {
    static final int HEIGHT = 72;
    private final PixelTabs tabs = new PixelTabs();
    private boolean syncing;
    private JComponent account, settings;

    void setSideComponents(JComponent account, JComponent settings) {
        this.account = account;
        this.settings = settings;
        add(account);
        add(settings);
        setComponentZOrder(settings, 0);
    }

    JourneyNavigation(Consumer<LauncherHomePanel.Screen> action) {
        super(null);
        setName("home-navigation");
        setBackground(JourneyStyle.INK);
        tabs.setName("journey-main-tabs");
        tabs.setTabHeight(80);
        tabs.setTabFont(LauncherFonts.minecraftTen(22));
        tabs.setTabGap(0);
        tabs.setTabHorizontalPadding(30);
        tabs.setHeaderBackground(JourneyStyle.INK);
        tabs.setInactiveColor(new Color(0xb9aea5));
        for (String title : new String[] {
                t("nav.home"), t("nav.library"), t("nav.skins"), t("nav.news")
        }) tabs.addTab(title);
        tabs.addChangeListener(event -> {
            if (!syncing && tabs.getSelectedIndex() >= 0) action.accept(
                    LauncherHomePanel.Screen.values()[tabs.getSelectedIndex()]);
        });
        add(tabs);
    }

    void select(LauncherHomePanel.Screen screen) {
        syncing = true;
        try {
            if (screen.ordinal() < 4) tabs.setSelectedIndex(screen.ordinal());
            else tabs.clearSelection();
        } finally { syncing = false; }
    }

    void setAnimationEnabled(boolean enabled) { tabs.setAnimationEnabled(enabled); }

    @Override public void doLayout() {
        tabs.setTabFont(LauncherFonts.minecraftTen(getWidth() < 700 ? 18 : 22));
        tabs.setTabHorizontalPadding(getWidth() < 700 ? 18 : 30);
        int width = Math.min(Math.max(0, getWidth() - 24), tabs.getPreferredSize().width);
        tabs.setBounds((getWidth() - width) / 2, 0, width, 80);
        tabs.doLayout();
        tabs.getTabBar().doLayout();
        // Side controls must not shift the window-centred navigation strip.
        int margin = getWidth() < 900 ? 12 : 24;
        if (account != null) {
            account.setBounds(margin, 12, Math.max(0, tabs.getX() - margin - 12), 42);
            account.doLayout();
        }
        if (settings != null) {
            int left = margin + 40;
            settings.setFont(LauncherFonts.minecraftFive(12));
            if (settings instanceof JButton button) {
                button.setHorizontalAlignment(SwingConstants.LEFT);
                button.setBorder(BorderFactory.createEmptyBorder());
                button.setFocusPainted(false);
            }
            settings.setBounds(left, 30, Math.max(0, tabs.getX() - left - 12), 22);
            if (settings instanceof JButton button) {
                for (int size = 12; size >= 8; size--) {
                    button.setFont(LauncherFonts.minecraftFive(size));
                    if (button.getFontMetrics(LauncherFonts.forLocale(
                            button.getFont())).stringWidth(I18n.resolve(
                            button.getText())) <= button.getWidth()) break;
                }
            }
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, HEIGHT - 7, getWidth(), 7);
        graphics.setColor(new Color(0x262423));
        graphics.fillRect(0, HEIGHT - 4, getWidth(), 4);
    }
}

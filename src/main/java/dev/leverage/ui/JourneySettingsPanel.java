package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

// The approved two-column settings view. It owns presentation, never credentials or
// preferences.
public final class JourneySettingsPanel extends JPanel implements Scrollable {
    private static final Color INK = new Color(0x171615),
            TEXT = new Color(0xF1EDEA), GREEN = new Color(0x86D562);
    private final ProfileSettingsPanel profile;
    private final ServerSettingsPanel server;
    private final JPanel left = column("settings-profile-column"),
            right = column("settings-server-column");
    private final PixelCheckBox remember = new PixelCheckBox(
            t("settings.remember"), false);
    private final JButton disconnect = link(t("settings.disconnect"),
            "settings-disconnect");
    private final JTextField directory = PixelInputStyle.apply(
            I18n.textField(t("settings.autoInstance")));
    private final PixelButton openFolder = new PixelButton(t("common.openFolderUpper"),
            PixelButton.Variant.NEUTRAL);
    private Consumer<Boolean> rememberAction = value -> { };
    private Runnable disconnectAction = () -> { },
            folderAction = this::openInstanceDirectory;
    private Consumer<I18n.Language> languageChangeAction = value -> { };
    private Path instanceDirectory;
    private boolean syncing;

    public JourneySettingsPanel(ProfileSettingsPanel profile,
            ServerSettingsPanel server, Runnable openAbout) {
        this.profile = Objects.requireNonNull(profile);
        this.server = Objects.requireNonNull(server);
        Objects.requireNonNull(openAbout);
        setName("journey-settings");
        setBackground(INK);
        setForeground(TEXT);
        setLayout(new BorderLayout(0, 30));
        setBorder(BorderFactory.createEmptyBorder(28, 40, 40, 40));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(title(t("settings.title"), 32), BorderLayout.WEST);
        add(heading, BorderLayout.NORTH);
        left.add(title(t("settings.profile"), 26));
        left.add(Box.createVerticalStrut(20));
        left.add(profile);
        left.add(new PixelSeparator(24));
        JPanel security = new JPanel(new BorderLayout(12, 0));
        security.setOpaque(false);
        security.setAlignmentX(LEFT_ALIGNMENT);
        security.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        security.setPreferredSize(new Dimension(0, 40));
        security.add(title(t("settings.security"), 20), BorderLayout.WEST);
        security.add(disconnect, BorderLayout.EAST);
        left.add(security);
        remember.setName("settings-remember-device");
        remember.setForeground(TEXT);
        remember.setAlignmentX(LEFT_ALIGNMENT);
        remember.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        left.add(remember);
        left.add(new PixelSeparator(24));
        left.add(title(t("settings.development"), 20));
        left.add(Box.createVerticalStrut(18));
        left.add(label(t("settings.modsFolder"), 14));
        left.add(Box.createVerticalStrut(6));
        directory.setName("settings-instance-directory");
        directory.setEditable(false);
        directory.setFocusable(true);
        directory.setPreferredSize(new Dimension(0, 48));
        directory.setAlignmentX(LEFT_ALIGNMENT);
        left.add(directory);
        left.add(Box.createVerticalStrut(12));
        openFolder.setName("settings-open-folder");
        openFolder.setFont(LauncherFonts.minecraftTen(14f));
        openFolder.setAlignmentX(LEFT_ALIGNMENT);
        openFolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        openFolder.setPreferredSize(new Dimension(0, 48));
        openFolder.setEnabled(false);
        left.add(openFolder);
        right.add(title(t("settings.servers"), 26));
        right.add(Box.createVerticalStrut(20));
        right.add(server);
        right.add(new PixelSeparator(24));
        var language = new PixelDropdown<I18n.Language>(
                java.util.List.of(I18n.Language.values()));
        language.setSelectedItem(I18n.language());
        language.setMaximumRowCount(4);
        language.setName("settings-language");
        language.setAlignmentX(LEFT_ALIGNMENT);
        language.getAccessibleContext().setAccessibleName(t(
                "settings.languageAccessible"));
        language.addActionListener(event -> {
            var next = (I18n.Language) language.getSelectedItem();
            if (next != null && next != I18n.language()) languageChangeAction.accept(
                    next);
        });
        JLabel languageTitle = title(t("settings.language"), 20);
        languageTitle.setLabelFor(language);
        right.add(languageTitle);
        right.add(Box.createVerticalStrut(12));
        right.add(language);
        right.add(new PixelSeparator(24));
        right.add(title(t("settings.about"), 20));
        right.add(Box.createVerticalStrut(12));
        JButton about = link(t("settings.aboutLink"), "settings-about");
        about.setIcon(new Icon() {
            public int getIconWidth() {
                return 16;
            }
            public int getIconHeight() {
                return 14;
            }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(c.getForeground());
                g.fillRect(x, y + 6, 14, 2);
                for (int i = 0; i < 4; i++) {
                    g.fillRect(x + 7 + i * 2, y + i * 2, 2, 2);
                    g.fillRect(x + 7 + i * 2, y + 12 - i * 2, 2, 2);
                }
            }
        });
        about.setHorizontalTextPosition(SwingConstants.LEFT);
        about.setHorizontalAlignment(SwingConstants.LEFT);
        about.setAlignmentX(LEFT_ALIGNMENT);
        about.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        about.addActionListener(event -> openAbout.run());
        right.add(about);
        JPanel columns = new JPanel(null) {
            @Override public void doLayout() {
                int width = getWidth();
                boolean stacked = width < 660;
                int gap = 40, columnWidth = stacked ? width : (width - gap) / 2;
                left.setSize(columnWidth, Short.MAX_VALUE);
                right.setSize(columnWidth, Short.MAX_VALUE);
                int a = left.getPreferredSize().height,
                        b = right.getPreferredSize().height;
                left.setBounds(0, 0, columnWidth, a);
                right.setBounds(stacked ? 0 : columnWidth + gap, stacked ? a + 36 : 0,
                        stacked ? width : width - columnWidth - gap, b);
                left.doLayout();
                right.doLayout();
            }
            @Override public Dimension getPreferredSize() {
                int width = getWidth() > 0 ? getWidth() : 900;
                boolean stacked = width < 660;
                int columnWidth = stacked ? width : (width - 40) / 2;
                left.setSize(columnWidth, Short.MAX_VALUE);
                right.setSize(columnWidth, Short.MAX_VALUE);
                int a = left.getPreferredSize().height,
                        b = right.getPreferredSize().height;
                return new Dimension(width, stacked ? a + b + 36 : Math.max(a, b));
            }
        };
        columns.setName("settings-columns");
        columns.setOpaque(false);
        columns.add(left);
        columns.add(right);
        add(columns, BorderLayout.CENTER);
        remember.addActionListener(event -> {
            if (!syncing) rememberAction.accept(remember.isSelected());
        });
        disconnect.addActionListener(event -> disconnectAction.run());
        openFolder.addActionListener(event -> folderAction.run());
    }
    public ProfileSettingsPanel profile() { return profile; }
    public ServerSettingsPanel serverSettings() { return server; }
    public void setRememberDevice(boolean value) {
        syncing = true;
        try {
            remember.setSelected(value);
        } finally {
            syncing = false;
        }
    }
    public boolean isRememberDevice() { return remember.isSelected(); }
    public void onRememberDevice(Consumer<Boolean> action) {
        rememberAction = Objects.requireNonNull(action);
    }
    public void onDisconnect(Runnable action) {
        disconnectAction = Objects.requireNonNull(action);
    }
    public void onOpenFolder(Runnable action) {
        folderAction = Objects.requireNonNull(action);
    }
    public void onLanguageChange(Consumer<I18n.Language> action) {
        languageChangeAction = Objects.requireNonNull(action);
    }
    public void setInstanceDirectory(Path path) {
        instanceDirectory = path;
        I18n.setText(directory, path == null ? t("settings.autoInstance")
                : path.resolve("mods").toString());
        directory.setToolTipText(path == null ? t("settings.chooseVersion")
                : directory.getText());
        openFolder.setEnabled(path != null);
    }
    public Path instanceDirectory() { return instanceDirectory; }
    public void setDisconnectEnabled(boolean enabled) { disconnect.setEnabled(enabled); }
    private void openInstanceDirectory() {
        if (instanceDirectory == null) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(
                    Desktop.Action.OPEN)) Desktop.getDesktop().open(
                    instanceDirectory.resolve("mods").toFile());
        } catch (Exception failure) {
            openFolder.setToolTipText(t("error.folderOpen", failure.getMessage()));
        }
    }
    private static JPanel column(String name) {
        var panel = new JPanel();
        panel.setName(name);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }
    private static JLabel title(String text, float size) {
        JLabel label = label(text, size);
        label.setFont(LauncherFonts.minecraftTen(size));
        return label;
    }
    private static JLabel label(String text, float size) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(LauncherFonts.minecraftSeven(size));
        label.setForeground(TEXT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }
    private static JButton link(String text, String name) {
        var button = new JButton(text);
        button.setName(name);
        button.setFont(LauncherFonts.minecraftSeven(13f));
        button.setForeground(GREEN);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
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

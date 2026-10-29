package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.LauncherPreferences;
import dev.leverage.launch.GameMemory;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.List;

// Account-server connection and independently persisted Minecraft / Java download
// origins.
public final class ServerSettingsPanel extends JPanel {
    private static final Color TEXT = new Color(0xF1EDEA), MUTED = new Color(0xA7A09A);
    private final JTextField serverUrl = field("server-url", t("sources.accountUrl"));
    private final JTextField versionsUrl = field("versions-url",
            t("sources.versionsBase"));
    private final JTextField javaUrl = field("java-url", t("sources.javaBase"));
    private final PixelDropdown<String> versionsDefault = sourceChoice("versions-source");
    private final PixelDropdown<String> javaDefault = sourceChoice("java-source");
    private final JPanel versionsCustom = custom(t("sources.versionsUrl"), versionsUrl);
    private final JPanel javaCustom = custom(t("sources.javaUrl"), javaUrl);
    private final JLabel connection = label(t("connection.offline"));
    private final PixelButton test = new PixelButton("", PixelButton.Variant.PRIMARY);
    private final PixelButton save = new PixelButton(t("sources.save"),
            PixelButton.Variant.PRIMARY);
    private final JTextField minimumMemory = field("minimum-memory",
            t("memory.minimum")), maximumMemory = field("maximum-memory",
            t("memory.maximum"));
    private final PixelDropdown<String> minimumUnit = new PixelDropdown<>(
            List.of("MB", "GB")), maximumUnit = new PixelDropdown<>(List.of("MB", "GB"));
    private final PixelButton saveMemory = new PixelButton(t("memory.save"),
            PixelButton.Variant.PRIMARY);
    private boolean busy;

    public ServerSettingsPanel() {
        setName("server-settings");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        add(label(t("sources.accountUrl")));
        add(Box.createVerticalStrut(6));
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        row.setPreferredSize(new Dimension(0, 48));
        test.setName("test-server-connection");
        test.setFont(LauncherFonts.minecraftTen(14f));
        test.setToolTipText(t("sources.test"));
        test.getAccessibleContext().setAccessibleName(t("sources.test"));
        test.setPreferredSize(new Dimension(52, 48));
        test.setMinimumSize(new Dimension(48, 48));
        test.setIcon(new ArrowIcon());
        row.add(serverUrl, BorderLayout.CENTER);
        row.add(test, BorderLayout.EAST);
        add(row);
        connection.setName("server-connection");
        connection.setFont(LauncherFonts.minecraftSeven(12f));
        connection.setForeground(MUTED);
        connection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        add(Box.createVerticalStrut(10));
        add(connection);
        add(new PixelSeparator(20));
        JLabel memory = label(t("memory.heading"));
        memory.setFont(LauncherFonts.minecraftTen(20f));
        add(memory);
        add(Box.createVerticalStrut(20));
        JPanel memoryFields = new JPanel(new GridLayout(1, 2, 12, 0));
        memoryFields.setOpaque(false);
        memoryFields.setAlignmentX(LEFT_ALIGNMENT);
        memoryFields.add(memoryField(t("memory.minimum"), minimumMemory, minimumUnit));
        memoryFields.add(memoryField(t("memory.maximum"), maximumMemory, maximumUnit));
        memoryFields.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        add(memoryFields);
        add(Box.createVerticalStrut(12));
        saveMemory.setName("save-game-memory");
        saveMemory.setFont(LauncherFonts.minecraftTen(14f));
        saveMemory.setAlignmentX(LEFT_ALIGNMENT);
        saveMemory.setPreferredSize(new Dimension(0, 48));
        saveMemory.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        add(saveMemory);
        loadMemory(GameMemory.DEFAULT);
        add(new PixelSeparator(20));
        JLabel downloads = label(t("sources.heading"));
        downloads.setFont(LauncherFonts.minecraftTen(20f));
        add(downloads);
        add(Box.createVerticalStrut(20));
        addChoice("Minecraft", versionsDefault, versionsCustom);
        add(Box.createVerticalStrut(20));
        addChoice("Java", javaDefault, javaCustom);
        add(Box.createVerticalStrut(20));
        save.setName("save-download-sources");
        save.setFont(LauncherFonts.minecraftTen(14f));
        save.setAlignmentX(LEFT_ALIGNMENT);
        save.setPreferredSize(new Dimension(0, 48));
        save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        add(save);
        versionsDefault.addActionListener(event -> updateSources());
        javaDefault.addActionListener(event -> updateSources());
        updateSources();
    }

    public void load(URI host, LauncherPreferences.DownloadSources sources) {
        serverUrl.setText(host == null ? "" : host.toString());
        versionsUrl.setText(sources.versions().baseUrl() == null ? ""
                : sources.versions().baseUrl().toString());
        javaUrl.setText(sources.javaRuntime().baseUrl() == null ? ""
                : sources.javaRuntime().baseUrl().toString());
        versionsDefault.setSelectedIndex(sources.versions().useDefaults() ? 0 : 1);
        javaDefault.setSelectedIndex(sources.javaRuntime().useDefaults() ? 0 : 1);
        updateSources();
    }
    public URI requestedServer() {
        return LauncherPreferences.validateServerUrl(serverUrl.getText());
    }
    public void loadMemory(GameMemory memory) {
        loadMemoryField(minimumMemory, minimumUnit, memory.minimumMb());
        loadMemoryField(maximumMemory, maximumUnit, memory.maximumMb());
    }
    public GameMemory requestedMemory() {
        try {
            return new GameMemory(memoryMb(minimumMemory, minimumUnit),
                    memoryMb(maximumMemory, maximumUnit));
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(t("memory.invalidNumber"));
        }
    }
    public void setServerUrl(URI host) {
        serverUrl.setText(host == null ? "" : host.toString());
    }
    public LauncherPreferences.DownloadSources requestedSources() {
        return new LauncherPreferences.DownloadSources(source(versionsDefault,
                versionsUrl), source(javaDefault, javaUrl));
    }
    public void setConnection(URI host, boolean authenticated) {
        connection.setText(host == null ? t("connection.offline") : t(authenticated
                ? "sources.connected" : "sources.notSignedIn", host.toString()));
        connection.setToolTipText(connection.getText());
    }
    public void setBusy(boolean value) {
        busy = value;
        test.setEnabled(!busy);
        save.setEnabled(!busy);
        saveMemory.setEnabled(!busy);
        minimumMemory.setEnabled(!busy);
        maximumMemory.setEnabled(!busy);
        minimumUnit.setEnabled(!busy);
        maximumUnit.setEnabled(!busy);
        serverUrl.setEnabled(!busy);
        versionsDefault.setEnabled(!busy);
        javaDefault.setEnabled(!busy);
        updateSources();
    }
    public void onTest(Runnable action) { test.addActionListener(event -> action.run()); }
    public void onSave(Runnable action) { save.addActionListener(event -> action.run()); }
    public void onSaveMemory(Runnable action) {
        saveMemory.addActionListener(event -> action.run());
    }

    private static void loadMemoryField(JTextField field, PixelDropdown<String> unit,
            int megabytes) {
        unit.setSelectedItem(megabytes >= 1024 ? "GB" : "MB");
        field.setText(BigDecimal.valueOf(megabytes).divide(BigDecimal.valueOf(
                unit.getSelectedIndex() == 1 ? 1024 : 1)).stripTrailingZeros()
                .toPlainString());
    }
    private static int memoryMb(JTextField field, PixelDropdown<String> unit) {
        return new BigDecimal(field.getText().trim().replace(',', '.')).multiply(
                BigDecimal.valueOf(unit.getSelectedIndex() == 1 ? 1024 : 1))
                .intValueExact();
    }
    private static JPanel memoryField(String title, JTextField field,
            PixelDropdown<String> unit) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        JLabel label = label(title);
        label.setLabelFor(field);
        panel.add(label, BorderLayout.NORTH);
        unit.setName(field.getName() + "-unit");
        unit.setPreferredSize(new Dimension(94, 48));
        unit.setMinimumSize(new Dimension(94, 48));
        unit.getAccessibleContext().setAccessibleName(t("memory.unit", title));
        unit.setEmbedded(true);
        field.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        JPanel input = new JPanel(new BorderLayout()) {
            @Override protected void paintChildren(Graphics graphics) {
                super.paintChildren(graphics);
                PixelInputStyle.paintShadow(graphics, 0, 0, getWidth(), getHeight());
                graphics.setColor(new Color(0x56514E));
                graphics.fillRect(unit.getX(), 12, 1, Math.max(0, getHeight() - 24));
            }
        };
        input.setBackground(PixelInputStyle.FACE);
        input.setBorder(PixelInputStyle.outlineBorder());
        input.setPreferredSize(new Dimension(0, 48));
        input.add(field, BorderLayout.CENTER);
        input.add(unit, BorderLayout.EAST);
        panel.add(input, BorderLayout.CENTER);
        return panel;
    }

    private void updateSources() {
        versionsCustom.setVisible(versionsDefault.getSelectedIndex() == 1);
        javaCustom.setVisible(javaDefault.getSelectedIndex() == 1);
        versionsUrl.setEnabled(!busy && versionsDefault.getSelectedIndex() == 1);
        javaUrl.setEnabled(!busy && javaDefault.getSelectedIndex() == 1);
        revalidate();
        repaint();
    }
    private static LauncherPreferences.DownloadSource source(
            PixelDropdown<String> choice, JTextField field) {
        return choice.getSelectedIndex() == 0
                ? LauncherPreferences.DownloadSource.defaults()
                : LauncherPreferences.DownloadSource.external(
                LauncherPreferences.validateBaseUrl(field.getText()));
    }
    private void addChoice(String title, PixelDropdown<String> choice, JPanel custom) {
        add(label(title));
        add(Box.createVerticalStrut(6));
        add(choice);
        add(custom);
    }
    private static PixelDropdown<String> sourceChoice(String name) {
        var choice = new PixelDropdown<String>(List.of(t("sources.default"),
                t("sources.custom")));
        choice.setName(name);
        choice.setAlignmentX(LEFT_ALIGNMENT);
        choice.getAccessibleContext().setAccessibleName(name.equals("java-source")
                ? t("sources.javaAccessible") : t("sources.minecraftAccessible"));
        return choice;
    }
    private static JPanel custom(String title, JTextField field) {
        var panel = new JPanel();
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(Box.createVerticalStrut(12));
        panel.add(label(title));
        panel.add(Box.createVerticalStrut(6));
        panel.add(field);
        return panel;
    }
    private static JLabel label(String text) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(LauncherFonts.minecraftSeven(14f));
        label.setForeground(TEXT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }
    private static JTextField field(String name, String description) {
        var field = PixelInputStyle.apply(new JTextField());
        field.setName(name);
        field.putClientProperty("JTextField.placeholderText", description);
        field.setPreferredSize(new Dimension(0, 48));
        field.setMinimumSize(new Dimension(100, 48));
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.getAccessibleContext().setAccessibleName(description);
        return field;
    }
    private static final class ArrowIcon implements Icon {
        @Override public int getIconWidth() {
            return 22;
        }
        @Override public int getIconHeight() {
            return 22;
        }
        @Override public void paintIcon(Component component, Graphics g, int x, int y) {
            g.setColor(component.isEnabled() ? Color.WHITE : new Color(0xCAC5C3));
            g.fillRect(x + 2, y + 9, 16, 3);
            for (int i = 0; i < 4; i++) {
                g.fillRect(x + 10 + i * 2, y + 3 + i * 2, 3, 3);
                g.fillRect(x + 10 + i * 2, y + 15 - i * 2, 3, 3);
            }
        }
    }
}

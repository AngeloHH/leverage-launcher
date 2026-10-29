package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.InstalledModCatalog;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// A compact editorial card using only the installed pack's own artwork and metadata.
final class ResourcePackCard extends JPanel {
    static final int BODY_HEIGHT = 167;
    static final Color DEPTH = new Color(0x244b1f);
    private final InstalledModCatalog.Mod pack;
    final ModListPanel.RowDescription title;
    final JLabel version;
    final ModListPanel.RowDescription description;
    final JButton details;
    final PixelDropdown<JMenuItem> more = new PixelDropdown<>(
            java.util.List.of(), JMenuItem::getText);
    final JMenuItem remove = new JMenuItem(t("common.delete"));
    final JMenuItem open = new JMenuItem(t("common.openFolder"));
    private final boolean removable;

    ResourcePackCard(InstalledModCatalog.Mod pack,
            Consumer<InstalledModCatalog.Mod> removal, Consumer<Path> openDirectory,
            BiConsumer<String, Boolean> notice) {
        super(null);
        this.pack = pack;
        removable = removal != null;
        setOpaque(false);
        setName("resourcepack-card");
        getAccessibleContext().setAccessibleName(t("resources.named", pack.name()));
        title = new ModListPanel.RowDescription(pack.name(), 2);
        title.setFont(LauncherFonts.minecraftTen(17));
        title.setForeground(JourneyStyle.INK);
        version = JourneyStyle.label(pack.version(), 11, new Color(0x665e57), false);
        version.setToolTipText(pack.version());
        description = new ModListPanel.RowDescription(pack.description(), 2);
        description.setFont(LauncherFonts.minecraftSeven(11));
        description.setForeground(new Color(0x3e3934));
        Runnable showDetails = () -> {
            JTextArea text = new JTextArea(pack.description(), 8, 44);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setFont(LauncherFonts.minecraftSeven(12));
            text.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(text),
                    pack.name(), JOptionPane.INFORMATION_MESSAGE);
        };
        details = JourneyStyle.link(t("common.more"), showDetails);
        details.setName("resourcepack-details");
        details.setFont(LauncherFonts.minecraftSeven(12));
        details.setForeground(new Color(0x2d6b22));
        details.setHorizontalAlignment(SwingConstants.LEFT);
        details.getAccessibleContext().setAccessibleName(t(
                "resources.descriptionNamed", pack.name()));
        more.setName("resourcepack-actions");
        more.setArrowOnly(true);
        more.setFont(LauncherFonts.minecraftSeven(13));
        more.setMinimumSize(new Dimension(0, 0));
        more.setToolTipText(I18n.t("mods.optionsNamed", pack.name()));
        more.getAccessibleContext().setAccessibleName(I18n.t(
                "mods.optionsNamed", pack.name()));
        JMenuItem fullDescription = new JMenuItem(t("common.viewDescription"));
        fullDescription.addActionListener(event -> showDetails.run());
        more.addItem(fullDescription);
        JMenuItem copy = new JMenuItem(t("common.copyHash"));
        copy.addActionListener(event -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(pack.sha1()), null);
                notice.accept(t("common.hashCopied"), false);
            } catch (Exception failure) { notice.accept(t("mods.copyError"), true); }
        });
        more.addItem(copy);
        open.addActionListener(event -> openDirectory.accept(
                pack.file().toAbsolutePath().normalize().getParent()));
        more.addItem(open);
        remove.setForeground(new Color(0xa92c25));
        remove.setEnabled(removable);
        remove.addActionListener(event -> {
            if (removal != null) removal.accept(pack);
        });
        more.addItem(remove);
        var renderer = more.getRenderer();
        more.setRenderer((list, item, index, selected, focused) -> {
            Component rendered = renderer.getListCellRendererComponent(list,
                    item, index, selected, focused);
            if (index >= 0 && item != null) {
                if (!more.isEnabled() || !item.isEnabled()) rendered.setForeground(
                        new Color(0xaba09c));
                else if (item == remove) rendered.setForeground(new Color(0xff9b87));
            }
            return rendered;
        });
        more.setSelectedIndex(-1);
        more.addActionListener(event -> {
            JMenuItem action = (JMenuItem) more.getSelectedItem();
            if (action == null) return;
            more.setPopupVisible(false);
            more.setSelectedIndex(-1);
            if (more.isEnabled() && action.isEnabled()) action.doClick();
        });
        add(title);
        add(version);
        add(description);
        add(details);
        add(more);
    }

    static int heightForWidth(int width) {
        return Math.max(1, width) * 3 / 5 + BODY_HEIGHT;
    }

    void setBusy(boolean busy) {
        details.setEnabled(!busy);
        more.setEnabled(!busy);
        more.setPopupVisible(false);
        for (int i = 0; i < more.getItemCount(); i++) more.getItemAt(i).setEnabled(!busy);
        remove.setEnabled(!busy && removable);
    }

    @Override public void doLayout() {
        int imageHeight = Math.max(1, getWidth()) * 3 / 5;
        int inset = 10, width = Math.max(0, getWidth() - inset * 2);
        title.setBounds(inset, imageHeight + 11, width, 39);
        version.setBounds(inset, imageHeight + 53, width, 18);
        description.setBounds(inset, imageHeight + 79, width, 34);
        details.setBounds(inset, getHeight() - 42, Math.max(0, width - 50), 28);
        more.setBounds(Math.max(inset, getWidth() - inset - 44),
                getHeight() - 42, 44, 28);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int imageHeight = Math.max(1, getWidth()) * 3 / 5;
            g.setColor(new Color(0x253c27));
            g.fillRect(0, 0, getWidth(), imageHeight);
            // pack.png is usually square pixel art: contain it, never crop the pack's
            // identity.
            if (pack.icon() != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale = Math.min(getWidth() / (double) pack.icon().getWidth(),
                        imageHeight / (double) pack.icon().getHeight());
                int width = Math.max(1, (int) Math.round(pack.icon().getWidth() * scale));
                int height = Math.max(1, (int) Math.round(
                        pack.icon().getHeight() * scale));
                g.drawImage(pack.icon(), (getWidth() - width) / 2,
                        (imageHeight - height) / 2, width, height, null);
            } else {
                int cell = Math.max(8, imageHeight / 5);
                for (int y = 0; y < imageHeight; y += cell) for (int x = 0;
                        x < getWidth(); x += cell) {
                    if ((x / cell + y / cell) % 2 == 0) {
                        g.setColor(new Color(0x2c4930));
                        g.fillRect(x, y, cell, Math.min(cell, imageHeight - y));
                    }
                }
                int icon = Math.min(40, Math.max(16, imageHeight - 24));
                SvgIconLibrary.paint(g, "pixel-chest", (getWidth() - icon) / 2,
                        (imageHeight - icon) / 2, icon, icon, new Color(0xbac8a4));
            }
            g.setColor(JourneyStyle.PAPER);
            g.fillRect(0, imageHeight, getWidth(), Math.max(0,
                    getHeight() - imageHeight - 5));
            g.setColor(DEPTH);
            g.fillRect(0, Math.max(0, getHeight() - 5), getWidth(), 5);
        } finally { g.dispose(); }
    }
}

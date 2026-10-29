package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.InstalledModCatalog;
import dev.leverage.client.FileImportBatch;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

// Installed JARs of the automatically managed instance, never a mixture of instances.
public final class ModListPanel extends JPanel {
    private final InstalledModCatalog catalog = new InstalledModCatalog();
    private final boolean resourcePacks;
    private final JPanel rows;
    private final JPanel actions = new JPanel(new BorderLayout(12, 0));
    private final PixelButton choose = new PixelButton(t("mods.changeVersion"),
            PixelButton.Variant.NEUTRAL);
    private final PixelButton refresh = new PixelButton(t("common.refresh"),
            PixelButton.Variant.NEUTRAL);
    private final PixelButton install;
    private Path directory;
    private long request;
    private BiConsumer<String, Boolean> notice = (message, error) -> { };
    private Runnable changeVersion = () -> { };
    @FunctionalInterface interface ImportCheck {
        void check(Path destination, List<Path> sources) throws java.io.IOException,
                InterruptedException;
    }
    private ImportCheck importCheck = (destination, sources) -> { };
    void onImportCheck(ImportCheck check) {
        importCheck = java.util.Objects.requireNonNull(check);
    }

    public ModListPanel() {
        this(false);
    }

    public ModListPanel(boolean resourcePacks) {
        this.resourcePacks = resourcePacks;
        rows = resourcePacks ? new ResourceGrid() : new JPanel();
        install = new PixelButton(resourcePacks ? t("resources.add")
                : t("mods.add"), PixelButton.Variant.NEUTRAL);
        setLayout(resourcePacks ? null : new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        actions.setPreferredSize(new Dimension(400, 34));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        for (PixelButton button : new PixelButton[] { install, choose, refresh }) {
            button.setFont(LauncherFonts.minecraftTen(14f));
            button.setPreferredSize(new Dimension(button == refresh ? 100 : 142, 34));
            button.setMinimumSize(new Dimension(0, 34));
        }
        left.add(install);
        left.add(Box.createHorizontalStrut(8));
        left.add(choose);
        actions.add(left, BorderLayout.WEST);
        actions.add(refresh, BorderLayout.EAST);
        refresh.setEnabled(false);
        install.setEnabled(false);
        add(actions);
        if (!resourcePacks) add(new PixelSeparator(8));
        if (!resourcePacks) rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setAlignmentX(LEFT_ALIGNMENT);
        add(rows);
        setMods(List.of(), null);
        choose.addActionListener(event -> changeVersion.run());
        install.addActionListener(event -> chooseFile());
        refresh.addActionListener(event -> loadDirectory(directory));
    }

    @Override public Dimension getPreferredSize() {
        if (!resourcePacks) return super.getPreferredSize();
        int width = getWidth();
        if (getParent() != null && getParent().getWidth() > 0) {
            Insets inset = getParent().getInsets();
            width = getParent().getWidth() - inset.left - inset.right;
        }
        width = Math.max(1, width > 0 ? width : 976);
        int height = 46 + ((ResourceGrid) rows).heightAt(width);
        if (rows.getComponentCount() == 1 && rows.getComponent(0) instanceof JLabel) {
            var viewport = (JViewport) SwingUtilities.getAncestorOfClass(
                    JViewport.class, this);
            if (viewport != null) {
                Insets padding = getParent().getInsets();
                height = Math.max(height, viewport.getExtentSize().height
                        - padding.top - padding.bottom);
            }
        }
        return new Dimension(width, height);
    }

    @Override public void doLayout() {
        if (!resourcePacks) {
            super.doLayout();
            return;
        }
        actions.setBounds(0, 0, getWidth(), 34);
        boolean empty = rows.getComponentCount() == 1
                && rows.getComponent(0) instanceof JLabel;
        rows.setBounds(0, 46, getWidth(), empty ? Math.max(0,
                getHeight() - 46) : ((ResourceGrid) rows).heightAt(getWidth()));
    }

    // Six columns even for a partial last row; height follows the assigned viewport
    // width.
    static final class ResourceGrid extends JPanel {
        static final int COLUMNS = 6, GAP = 16;
        ResourceGrid() {
            super(null);
            setName("resourcepack-grid");
        }
        int heightAt(int width) {
            if (getComponentCount() == 0) return 0;
            if (!(getComponent(0) instanceof ResourcePackCard)) return 220;
            int cardWidth = Math.max(1, (width - GAP * (COLUMNS - 1)) / COLUMNS);
            int count = (getComponentCount() + COLUMNS - 1) / COLUMNS;
            return count * ResourcePackCard.heightForWidth(cardWidth) + (count - 1) * GAP;
        }
        @Override public void doLayout() {
            if (getComponentCount() == 1 && !(getComponent(
                    0) instanceof ResourcePackCard)) {
                getComponent(0).setBounds(0, 0, getWidth(), getHeight());
                return;
            }
            int available = Math.max(COLUMNS, getWidth() - GAP * (COLUMNS - 1));
            int width = Math.max(1, available / COLUMNS);
            int height = ResourcePackCard.heightForWidth(width);
            for (int i = 0; i < getComponentCount(); i++) {
                int column = i % COLUMNS;
                getComponent(i).setBounds(column * (width + GAP),
                        (i / COLUMNS) * (height + GAP), width, height);
            }
        }
    }

    public void onNotice(BiConsumer<String, Boolean> handler) { notice = handler; }

    public void onChangeVersion(Runnable handler) { changeVersion = handler; }

    public void chooseAdvancedDirectory() {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle(t("mods.advancedFolder"));
        picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (directory != null) picker.setCurrentDirectory(directory.toFile());
        if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadDirectory(
                picker.getSelectedFile().toPath());
    }

    Path directory() { return directory; }

    public void clearDirectory() {
        request++;
        directory = null;
        setToolTipText(null);
        setMods(List.of(), null);
        busy(false);
        notice.accept("", false);
    }

    public CompletableFuture<Void> loadDirectory(Path path) {
        Path requested = path.toAbsolutePath().normalize();
        long id =++request;
        directory = null;
        setMods(List.of(), null);
        busy(true);
        notice.accept("", false);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
            try {
                return resourcePacks ? catalog.scanResources(requested)
                        : catalog.scan(requested);
            }
            catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((mods, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != id) {
                completion.complete(null);
                return;
            }
            busy(false);
            if (failure == null) {
                directory = requested.toAbsolutePath().normalize();
                setToolTipText(directory.toString());
                refresh.setEnabled(true);
                install.setEnabled(true);
                setMods(mods, this::remove);
                notice.accept("", false);
            } else notice.accept(t("error.filesRead", root(failure).getMessage()), true);
            completion.complete(null);
        }));
        return completion;
    }

    // Supplying a list changes only presentation; files are touched only by the confirmed
    // removal handler.
    public void setMods(List<InstalledModCatalog.Mod> mods,
            java.util.function.Consumer<InstalledModCatalog.Mod> remove) {
        rows.removeAll();
        if (mods.isEmpty()) {
            JLabel empty = new JLabel(directory == null ? (resourcePacks
                    ? t("resources.chooseVersion") : t("mods.chooseVersion"))
                    : (resourcePacks ? t("resources.empty") : t("mods.empty")));
            empty.setFont(resourcePacks ? LauncherFonts.minecraftTen(20f)
                    : LauncherFonts.minecraftSeven(14f));
            empty.setForeground(resourcePacks ? Color.WHITE : new Color(0xD0D1D4));
            if (resourcePacks) {
                empty.setHorizontalAlignment(SwingConstants.CENTER);
                empty.setVerticalAlignment(SwingConstants.CENTER);
            }
            if (resourcePacks) empty.setName("resourcepack-empty");
            empty.setAlignmentX(LEFT_ALIGNMENT);
            rows.add(empty);
        }
        for (int i = 0; i < mods.size(); i++) {
            InstalledModCatalog.Mod mod = mods.get(i);
            if (resourcePacks) rows.add(new ResourcePackCard(mod,
                    remove, this::openDirectory, (message,
                    error) -> notice.accept(message, error)));
            else {
                rows.add(new ModRow(mod, remove, this::openDirectory, (message,
                        error) -> notice.accept(message, error), false));
                rows.add(new PixelSeparator(0));
            }
        }
        rows.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                rows.getPreferredSize().height));
        revalidate();
        repaint();
    }

    private void chooseFile() {
        if (directory == null) return;
        long beforeDialog = request;
        JFileChooser picker = filePicker();
        if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION
                || request != beforeDialog) return;
        installFiles(java.util.Arrays.stream(picker.getSelectedFiles()).map(
                java.io.File::toPath).toList());
    }

    JFileChooser filePicker() {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle(resourcePacks ? t("resources.addMany") : t("mods.addMany"));
        picker.setMultiSelectionEnabled(true);
        picker.setAcceptAllFileFilterUsed(false);
        picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                resourcePacks ? t("resources.zip") : t("mods.jar"),
                resourcePacks ? "zip" : "jar"));
        return picker;
    }

    CompletableFuture<Void> installFiles(List<Path> selected) {
        if (directory == null || !install.isEnabled() || selected.isEmpty())
                return CompletableFuture.completedFuture(null);
        List<Path> sources = List.copyOf(selected);
        Path target = directory;
        long id =++request;
        busy(true);
        notice.accept("", false);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ImportCheck check = importCheck;
        CompletableFuture.supplyAsync(() -> {
            try { check.check(target, sources); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(interrupted);
            }
            catch (java.io.IOException rejected) {
                throw new java.util.concurrent.CompletionException(rejected);
            }
            return FileImportBatch.run(sources, Integer.MAX_VALUE,
                    source -> catalog.install(target, source, resourcePacks));
        }).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != id) {
                completion.complete(null);
                return;
            }
            busy(false);
            if (failure != null) {
                notice.accept(t("error.filesImport", root(failure).getMessage()), true);
                completion.complete(null);
            } else if (result.added().isEmpty()) {
                notice.accept(result.hasErrors() ? result.message() : "",
                        result.hasErrors());
                completion.complete(null);
            } else {
                CompletableFuture<Void> reload = loadDirectory(target);
                long reloadId = request;
                reload.thenRun(() -> {
                    if (request == reloadId && target.equals(directory)) notice.accept(
                            result.hasErrors() ? result.message() : "",
                            result.hasErrors());
                    completion.complete(null);
                });
            }
        }));
        return completion;
    }

    private void openDirectory(Path path) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(
                    Desktop.Action.OPEN)) {
                throw new java.io.IOException(t("mods.explorerUnavailable"));
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception exception) {
            notice.accept(t("error.folderOpen", exception.getMessage()), true);
        }
    }

    private void remove(InstalledModCatalog.Mod mod) {
        if (directory == null) return;
        int answer = JOptionPane.showConfirmDialog(this, t("mods.deleteConfirm",
                mod.name()), resourcePacks ? t("resources.delete") : t("mods.delete"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        Path selected = directory;
        long id =++request;
        busy(true);
        CompletableFuture.runAsync(() -> {
            try { catalog.remove(selected, mod); }
            catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != id) return;
            busy(false);
            if (failure != null) notice.accept(t("error.fileDelete",
                    root(failure).getMessage()), true);
            else loadDirectory(selected);
        }));
    }

    private void busy(boolean busy) {
        choose.setEnabled(!busy);
        install.setEnabled(!busy && directory != null);
        refresh.setEnabled(!busy && directory != null);
        for (Component component : rows.getComponents())
                if (component instanceof ModRow row) {
            row.remove.setEnabled(!busy);
            row.open.setEnabled(!busy);
            row.more.setEnabled(!busy);
        } else if (component instanceof ResourcePackCard card) card.setBusy(busy);
    }

    private static Throwable root(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    static final class ModRow extends JPanel {
        final JLabel name = new JLabel();
        final JLabel version = new JLabel();
        final RowDescription description;
        final PixelButton remove = new PixelButton(t("common.delete"),
                PixelButton.Variant.DANGER);
        final PixelButton open = new PixelButton("", PixelButton.Variant.NEUTRAL);
        final PixelButton more = new PixelButton("...", PixelButton.Variant.NEUTRAL);
        private final InstalledModCatalog.Mod mod;

        ModRow(InstalledModCatalog.Mod mod,
                java.util.function.Consumer<InstalledModCatalog.Mod> handler) {
            this(mod, handler, path -> { }, (message, error) -> { }, false);
        }

        ModRow(InstalledModCatalog.Mod mod,
                java.util.function.Consumer<InstalledModCatalog.Mod> handler,
                java.util.function.Consumer<Path> openDirectory, BiConsumer<String,
                Boolean> notice, boolean resourcePacks) {
            this.mod = mod;
            setLayout(null);
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(0, 100));
            setMinimumSize(new Dimension(0, 100));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            name.putClientProperty("html.disable", true);
            version.putClientProperty("html.disable", true);
            name.setText(mod.name());
            name.setToolTipText(" " + mod.name());
            name.setFont(LauncherFonts.minecraftTen(20f));
            version.setText(mod.version());
            version.setFont(LauncherFonts.minecraftTen(resourcePacks ? 15f : 14f));
            version.setToolTipText(" " + mod.version());
            name.setForeground(Color.WHITE);
            version.setForeground(new Color(0xD0D1D4));
            description = new RowDescription(mod.description(), resourcePacks ? 2 : 1);
            description.setForeground(new Color(0xB9B5B3));
            remove.setFont(LauncherFonts.minecraftTen(16f));
            remove.setEnabled(handler != null);
            remove.addActionListener(event -> {
                if (handler != null) handler.accept(mod);
            });
            open.setIcon(new FolderIcon());
            open.setMinimumSize(new Dimension(0, 0));
            more.setMinimumSize(new Dimension(0, 0));
            more.setFont(LauncherFonts.minecraftTen(14f));
            open.setToolTipText(t("common.openFolder"));
            open.getAccessibleContext().setAccessibleName(t("mods.openNamed",
                    mod.name()));
            more.setToolTipText(t("common.moreOptions"));
            more.getAccessibleContext().setAccessibleName(t("mods.optionsNamed",
                    mod.name()));
            Runnable browse = () -> openDirectory.accept(
                    mod.file().toAbsolutePath().normalize().getParent());
            open.addActionListener(event -> browse.run());
            JPopupMenu menu = new JPopupMenu();
            JMenuItem details = new JMenuItem(t("common.viewDescription"));
            details.addActionListener(event -> {
                JTextArea text = new JTextArea(mod.description(), 8, 44);
                text.setEditable(false);
                text.setLineWrap(true);
                text.setWrapStyleWord(true);
                text.setFont(LauncherFonts.minecraftSeven(12f));
                text.setCaretPosition(0);
                JOptionPane.showMessageDialog(this, new JScrollPane(text),
                        mod.name(), JOptionPane.INFORMATION_MESSAGE);
            });
            menu.add(details);
            JMenuItem copy = new JMenuItem(t("common.copyHash"));
            copy.addActionListener(event -> {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new java.awt.datatransfer.StringSelection(mod.sha1()), null);
                    notice.accept(t("common.hashCopied"), false);
                } catch (Exception failure) { notice.accept(t("mods.copyError"), true); }
            });
            menu.add(copy);
            JMenuItem folder = new JMenuItem(t("common.openFolder"));
            folder.addActionListener(event -> browse.run());
            menu.add(folder);
            for (Component item : menu.getComponents()) item.setFont(
                    LauncherFonts.minecraftTen(14f));
            more.setComponentPopupMenu(menu);
            more.addActionListener(event -> {
                if (more.isShowing()) menu.show(more, 0, more.getHeight());
            });
            putClientProperty("resourcepack", resourcePacks);
            add(name);
            add(version);
            add(description);
            add(remove);
            add(open);
            add(more);
        }

        @Override public void doLayout() {
            int buttonTop = (getHeight() - 34) / 2;
            int moreX = Math.max(0, getWidth() - 16 - 32);
            more.setBounds(moreX, buttonTop, 32, 34);
            open.setBounds(Math.max(0, moreX - 8 - 32), buttonTop, 32, 34);
            int removeX = Math.max(0, open.getX() - 10 - 92);
            remove.setBounds(removeX, buttonTop, 92, 34);
            int contentWidth = Math.max(0, removeX - 80 - 14);
            if (Boolean.TRUE.equals(getClientProperty("resourcepack"))) {
                name.setBounds(80, 10, contentWidth, 25);
                version.setBounds(80, 35, contentWidth, 20);
                description.setBounds(80, 58, contentWidth, 34);
            } else {
                int gap = version.getText().isBlank() ? 0 : Math.min(10, contentWidth);
                int versionWidth = Math.min(version.getPreferredSize().width,
                        Math.max(0, (contentWidth - gap) * 2 / 5));
                int nameWidth = Math.min(name.getPreferredSize().width,
                        Math.max(0, contentWidth - versionWidth - gap));
                int descriptionHeight = description.getFontMetrics(
                        LauncherFonts.forLocale(description.getFont())).getHeight();
                int titleTop = (getHeight() - 25 - 5 - descriptionHeight) / 2;
                name.setBounds(80, titleTop, nameWidth, 25);
                int versionY = name.getY() + name.getBaseline(nameWidth,
                        25) - version.getBaseline(versionWidth, 20);
                version.setBounds(80 + nameWidth + gap, versionY, versionWidth, 20);
                description.setBounds(80, titleTop + 25 + 5,
                        contentWidth, descriptionHeight);
            }
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (mod.icon() != null) {
                double scale = Math.min(48.0 / mod.icon().getWidth(),
                        48.0 / mod.icon().getHeight());
                int width = (int) Math.round(mod.icon().getWidth() * scale);
                int height = (int) Math.round(mod.icon().getHeight() * scale);
                graphics.drawImage(mod.icon(), 16 + (48 - width) / 2,
                        (getHeight() - height) / 2, width, height, null);
            } else {
                graphics.setColor(new Color(0x3B3837));
                graphics.fillRect(16, (getHeight() - 48) / 2, 48, 48);
                SvgIconLibrary.paint((Graphics2D) graphics,
                        Boolean.TRUE.equals(getClientProperty("resourcepack"))
                        ? "pixel-chest" : "pixel-grid-add", 28,
                        (getHeight() - 24) / 2, 24, 24, new Color(0xD0D1D4));
            }
        }
    }

    private static final class FolderIcon implements Icon {
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(Component component,
                Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setColor(new Color(0x262423));
                g.drawLine(1, 3, 7, 3);
                g.drawLine(1, 3, 1, 13);
                g.drawLine(7, 3, 9, 5);
                g.drawLine(1, 5, 14, 5);
                g.drawLine(14, 5, 14, 13);
                g.drawLine(1, 13, 14, 13);
            } finally { g.dispose(); }
        }
    }

    static final class RowDescription extends JComponent {
        private final String text;
        private final int maxLines;
        RowDescription(String text, int maxLines) {
            this.text = I18n.transform(text == null ? "" : text,
                    value -> value.replaceAll("\\s+", " ").trim());
            this.maxLines = maxLines;
            setFont(LauncherFonts.minecraftSeven(12f));
            setForeground(new Color(107, 101, 98));
            setToolTipText(I18n.transform(this.text, value -> " " + value));
        }
        List<String> lines(int width) {
            var metrics = getFontMetrics(LauncherFonts.forText(
                    LauncherFonts.forLocale(getFont()), I18n.resolve(text)));
            if (text.isEmpty() || width <= 0) return List.of();
            List<String> lines = new ArrayList<>();
            String remaining = I18n.resolve(text);
            for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
                if (metrics.stringWidth(I18n.resolve(remaining)) <= width) {
                    lines.add(remaining);
                    break;
                }
                String suffix = line == maxLines - 1 ? "..." : "";
                int end = 0;
                while (end < remaining.length()) {
                    int next = remaining.offsetByCodePoints(end, 1);
                    if (metrics.stringWidth(I18n.resolve(remaining.substring(0,
                            next) + suffix)) > width) break;
                    end = next;
                }
                if (end == 0) {
                    lines.add(metrics.stringWidth(I18n.resolve(
                            "...")) <= width ? "..." : "");
                    break;
                }
                if (line < maxLines - 1) {
                    int space = remaining.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                    lines.add(remaining.substring(0, end).stripTrailing());
                    remaining = remaining.substring(end).stripLeading();
                } else lines.add(remaining.substring(0, end).stripTrailing() + "...");
            }
            return lines;
        }
        @Override protected void paintComponent(Graphics graphics) {
            graphics.setFont(LauncherFonts.forText(LauncherFonts.forLocale(getFont()),
                    I18n.resolve(text)));
            graphics.setColor(getForeground());
            var metrics = graphics.getFontMetrics();
            var lines = lines(getWidth());
            for (int i = 0; i < lines.size(); i++) graphics.drawString(I18n.resolve(
                    lines.get(i)), 0, metrics.getAscent() + i * metrics.getHeight());
        }
    }
}

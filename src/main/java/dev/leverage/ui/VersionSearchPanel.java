package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.GameInstaller;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

// Debounced search with immutable result snapshots; no network work is done while typing.
final class VersionSearchPanel extends JPanel implements AutoCloseable {
    private enum Kind {
        ALL(t("versions.all")), RELEASE(t("versions.releases")),
                SNAPSHOT(t("versions.snapshots")), HISTORIC(t("versions.historic"));
        final String label;
        Kind(String label) { this.label = label; }
        boolean accepts(String type) {
            return this == ALL || this == RELEASE && type.equals("release")
                    || this == SNAPSHOT && type.equals("snapshot") || this == HISTORIC
                    && (type.equals("old_alpha") || type.equals("old_beta"));
        }
    }
    private final JTextField query = PixelInputStyle.apply(new JTextField());
    private final PixelDropdown<Kind> kind = new PixelDropdown<>(
            List.of(Kind.values()), value -> value.label);
    private final JList<GameInstaller.VersionInfo> results
            = new JList<>(new DefaultListModel<>());
    private final JLabel status = JourneyStyle.label(t("versions.loading"),
            12, JourneyStyle.PAPER, false);
    private final Consumer<GameInstaller.VersionInfo> onSelection;
    private final Timer debounce = new Timer(200, event -> search());
    private List<GameInstaller.VersionInfo> catalog = List.of();
    private SwingWorker<List<GameInstaller.VersionInfo>, Void> worker;
    private boolean loaded, pending, closed;
    private long revision;

    VersionSearchPanel(Consumer<GameInstaller.VersionInfo> onSelection) {
        super(new BorderLayout(0, 12));
        this.onSelection = onSelection;
        setOpaque(false);
        query.setName("version-search");
        query.putClientProperty("JTextField.placeholderText",
                t("versions.searchPlaceholder"));
        query.getAccessibleContext().setAccessibleName(t("versions.searchAccessible"));
        kind.setName("version-type-filter");
        kind.setFont(LauncherFonts.minecraftSeven(14));
        kind.setPreferredSize(new Dimension(170, 48));
        kind.getAccessibleContext().setAccessibleName(t("versions.type"));
        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        controls.add(query, BorderLayout.CENTER);
        controls.add(kind, BorderLayout.EAST);
        add(controls, BorderLayout.NORTH);
        results.setName("version-results");
        results.getAccessibleContext().setAccessibleName(t("versions.available"));
        results.setBackground(new Color(0x262423));
        results.setForeground(Color.WHITE);
        results.setFont(LauncherFonts.minecraftSeven(16));
        results.setFixedCellHeight(42);
        // All rows stretch to the viewport; never measure every label to discover a
        // preferred width.
        results.setFixedCellWidth(1);
        results.setSelectionBackground(new Color(0x3c8527));
        results.setSelectionForeground(Color.WHITE);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer(new DefaultListCellRenderer() {
            private final javax.swing.border.Border padding
                    = BorderFactory.createEmptyBorder(6, 12, 6, 12);
            @Override public Component getListCellRendererComponent(JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                var version = (GameInstaller.VersionInfo) value;
                setText(version.id() + "  ·  " + switch (version.type()) {
                    case "release" -> t("versions.release");
                    case "snapshot" -> t("versions.snapshot");
                    case "old_alpha" -> t("versions.alpha");
                    case "old_beta" -> t("versions.beta");
                    default -> version.type();
                });
                setBorder(padding);
                return this;
            }
        });
        add(JourneyStyle.scroll(results, JourneyStyle.INK), BorderLayout.CENTER);
        status.setName("version-result-count");
        add(status, BorderLayout.SOUTH);
        debounce.setRepeats(false);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { request(); }
            public void removeUpdate(DocumentEvent event) { request(); }
            public void changedUpdate(DocumentEvent event) { request(); }
        });
        kind.addActionListener(event -> request());
        results.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) onSelection.accept(selected());
        });
    }

    void setCatalog(List<GameInstaller.VersionInfo> versions) {
        if (closed) return;
        catalog = List.copyOf(versions);
        loaded = true;
        request();
        debounce.stop();
        search();
    }

    void loadFailed() {
        if (closed) return;
        loaded = false;
        pending = false;
        revision++;
        debounce.stop();
        if (worker != null) worker.cancel(true);
        results.setModel(new DefaultListModel<>());
        onSelection.accept(null);
        status.setText(t("versions.catalogError"));
    }

    GameInstaller.VersionInfo selected() {
        return closed || !loaded || pending ? null : results.getSelectedValue();
    }

    private void request() {
        if (closed) return;
        revision++;
        pending = true;
        if (worker != null) worker.cancel(true);
        onSelection.accept(null);
        if (loaded) {
            status.setText(t("versions.searching"));
            debounce.restart();
        }
    }

    private void search() {
        if (closed || !loaded) return;
        long request = revision;
        String text = query.getText();
        Kind type = (Kind) kind.getSelectedItem();
        List<GameInstaller.VersionInfo> source = catalog;
        String selectedId = results.getSelectedValue() == null
                ? null : results.getSelectedValue().id();
        worker = new SwingWorker<>() {
            @Override protected List<GameInstaller.VersionInfo> doInBackground() {
                String term = text.strip().toLowerCase(Locale.ROOT);
                String[] words = term.isEmpty() ? new String[0] : term.split("\\s+");
                // A trailing space completes a numeric token: 1.1 must not also match
                // 1.10 / 1.19.
                Pattern complete = !text.isEmpty() && Character.isWhitespace(
                        text.charAt(text.length() - 1)) && words.length > 0
                        && words[words.length - 1].matches("[0-9]+(?:\\.[0-9]+)*")
                        ? Pattern.compile("(?<![A-Za-z0-9.])" + Pattern.quote(
                        words[words.length - 1]) + "(?![A-Za-z0-9])") : null;
                List<GameInstaller.VersionInfo> matches = new ArrayList<>();
                for (var version : source) {
                    if (isCancelled()) return List.of();
                    if (type != null && !type.accepts(version.type())) continue;
                    String name = version.id().toLowerCase(Locale.ROOT);
                    String searchable = name + " " + version.type();
                    boolean match = true;
                    for (String word : words) if (!searchable.contains(word)) {
                        match = false;
                        break;
                    }
                    if (match && (complete == null || complete.matcher(
                            name).find())) matches.add(version);
                }
                if (!term.isEmpty()) matches.sort(Comparator.comparingInt(version -> {
                    String name = version.id().toLowerCase(Locale.ROOT);
                    return name.equals(term) ? 0 : name.startsWith(term) ? 1 : 2;
                }));
                return List.copyOf(matches);
            }
            @Override protected void done() {
                if (closed || isCancelled() || request != revision) return;
                try {
                    List<GameInstaller.VersionInfo> matches = get();
                    DefaultListModel<GameInstaller.VersionInfo> snapshot
                            = new DefaultListModel<>();
                    snapshot.addAll(matches);
                    // Build off-list, then publish one complete model.
                    pending = false;
                    results.setModel(snapshot);
                    int index = 0;
                    for (int i = 0; i < matches.size(); i++)
                            if (matches.get(i).id().equals(selectedId)) {
                        index = i;
                        break;
                    }
                    if (!matches.isEmpty()) {
                        results.setSelectedIndex(index);
                        results.ensureIndexIsVisible(index);
                    }
                    status.setText(matches.isEmpty() ? t("versions.noMatches",
                            Integer.toString(source.size())) : t("versions.resultCount",
                            Integer.toString(matches.size()),
                            Integer.toString(source.size())));
                    onSelection.accept(selected());
                } catch (CancellationException ignored) {
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    pending = false;
                    results.setModel(new DefaultListModel<>());
                    onSelection.accept(null);
                    status.setText(t("versions.searchError"));
                }
            }
        };
        worker.execute();
    }

    @Override public void close() {
        closed = true;
        revision++;
        debounce.stop();
        if (worker != null) worker.cancel(true);
        onSelection.accept(null);
    }
}

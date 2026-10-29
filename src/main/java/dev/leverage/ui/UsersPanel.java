package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherUsersClient;
import dev.leverage.skin.DefaultSkin;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Server-backed administrator directory. All presentation changes run on the EDT.
public final class UsersPanel extends JPanel {
    @FunctionalInterface interface QueryLoader {
        LauncherUsersClient.Page search(URI host, String token, String query,
                int page, int pageSize) throws IOException, InterruptedException;
        default LauncherUsersClient.Page search(URI host, String token,
                String query, int page, int pageSize, String sort,
                String direction) throws IOException, InterruptedException {
            return search(host, token, query, page, pageSize);
        }
    }

    enum Action { BAN, CLEAR_SKIN, PASSWORD, EMAIL }
    @FunctionalInterface interface AdminActions {
        void perform(URI host, String token, java.util.UUID user, Action action,
                String value, char[] password) throws IOException, InterruptedException;
        default void perform(URI host, String token, java.util.UUID user,
                Action action, String value, char[] password,
                Instant expiresAt) throws IOException, InterruptedException {
            perform(host, token, user, action, value, password);
        }
    }
    @FunctionalInterface interface SkinLoader {
        BufferedImage load(URI host, String hash) throws IOException,
                InterruptedException;
    }
    @FunctionalInterface interface Dialogs {
        AdminUserDialogs.Edit request(Component owner, Action action,
                LauncherUsersClient.User user);
    }
    private static final ExecutorService SKIN_WORKERS
            = Executors.newFixedThreadPool(4, work -> {
        Thread thread = new Thread(work, "users-skin-thumbnail");
        thread.setDaemon(true);
        return thread;
    });

    private final QueryLoader loader;
    private final AdminActions administrativeActions;
    private final SkinLoader skinLoader;
    private final Dialogs dialogs;
    private final LinkedHashMap<String, BufferedImage> skins
            = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,
                BufferedImage> eldest) {
            return size() > 128;
        }
    };
    private final JLabel notice = label("", 12, JourneyStyle.GREEN, false);
    private final JTextField searchInput = PixelInputStyle.apply(new SearchField());
    private final PixelButton search = new PixelButton(t("common.search"),
            PixelButton.Variant.PRIMARY);
    private final PixelDropdown<Integer> pageSize
            = new PixelDropdown<>(List.of(10, 25, 50, 100));
    private final JPanel rows = new RowsPanel();
    private final JScrollPane usersScroll = JourneyStyle.scroll(rows, JourneyStyle.INK);
    private final JButton previous = new PagerButton();
    private final JButton next = new PagerButton();
    private final JLabel pageStatus = label(t("users.pageInitial"),
            13, JourneyStyle.PAPER, false);
    private final JLabel totalStatus = label("", 12, new Color(0xB9B5B3), false);
    private URI host;
    private LauncherAuthClient.Session session;
    private volatile long generation;
    private int currentPage = 1, totalPages = 1;
    private long total;
    private String query = "";
    private String sortKey = "createdAt";
    private boolean sortDescending;
    private final JButton[] sortHeaders = new JButton[5];
    private boolean loading, updatingPageSize, mutating;
    private CompletableFuture<Void> pendingLoad = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> pendingMutation
            = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> pendingSkins
            = CompletableFuture.completedFuture(null);

    public UsersPanel() { this(new LauncherUsersClient()); }

    private UsersPanel(LauncherUsersClient client) {
        this(new QueryLoader() {
            public LauncherUsersClient.Page search(URI host, String token, String query,
                    int page, int size) throws IOException, InterruptedException {
                return client.search(host, token, query, page, size);
            }
            public LauncherUsersClient.Page search(URI host, String token,
                    String query, int page, int size, String sort,
                    String direction) throws IOException, InterruptedException {
                return client.search(host, token, query, page, size, sort, direction);
            }
        }, new AdminActions() {
            public void perform(URI host, String token,
                    java.util.UUID user, Action action, String value,
                    char[] password) throws IOException, InterruptedException {
                perform(host, token, user, action, value, password, null);
            }
            public void perform(URI host, String token, java.util.UUID user,
                    Action action, String value, char[] password,
                    Instant expiresAt) throws IOException, InterruptedException {
                switch (action) {
                    case BAN -> client.ban(host, token, user, value, expiresAt);
                    case CLEAR_SKIN -> client.clearSkin(host, token, user);
                    case PASSWORD -> client.changePassword(host, token,
                            user, new String(password));
                    case EMAIL -> client.changeEmail(host, token, user, value);
                } }
        }, client::loadSkin, AdminUserDialogs::show);
    }

    UsersPanel(QueryLoader loader) {
        this(loader, (host, token, user, action, value, password) -> {
            throw new IOException("No administrative backend configured");
        }, (host, hash) -> DefaultSkin.image(), AdminUserDialogs::show);
    }

    UsersPanel(QueryLoader loader, AdminActions administrativeActions,
            SkinLoader skinLoader, Dialogs dialogs) {
        this.loader = Objects.requireNonNull(loader);
        this.administrativeActions = Objects.requireNonNull(administrativeActions);
        this.skinLoader = Objects.requireNonNull(skinLoader);
        this.dialogs = Objects.requireNonNull(dialogs);
        setName("users-panel");
        setBackground(JourneyStyle.INK);
        setOpaque(false);
        setLayout(new BorderLayout());
        setAlignmentX(LEFT_ALIGNMENT);
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setName("users-search-bar");
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.setPreferredSize(new Dimension(400, 48));
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        searchInput.setName("users-search-input");
        searchInput.setToolTipText(t("users.searchTooltip"));
        searchInput.getAccessibleContext().setAccessibleName(t("users.searchDetailed"));
        search.setName("users-search");
        search.getAccessibleContext().setAccessibleName(t("users.searchAccessible"));
        search.setFont(LauncherFonts.minecraftSeven(16));
        search.setPreferredSize(new Dimension(120, 48));
        search.setMinimumSize(new Dimension(90, 48));
        searchInput.addActionListener(event -> startSearch());
        search.addActionListener(event -> startSearch());
        actions.add(searchInput, BorderLayout.CENTER);
        actions.add(search, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        notice.setName("users-action-notice");
        notice.setVisible(false);
        notice.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        JPanel headerBottom = new JPanel(new BorderLayout());
        headerBottom.setOpaque(false);
        headerBottom.add(notice, BorderLayout.NORTH);
        JPanel tableHeader = new JPanel(null) {
            final String[] titles = { t("auth.user"), t("users.email"),
                    t("users.created"), t("users.lastSeen"), t("users.status") };
            final String[] keys = { "username", "email", "createdAt",
                    "lastSeenAt", "status" };
            {
                setOpaque(false);
                setName("users-table-header");
                for (int i = 0; i < titles.length; i++) {
                    String key = keys[i];
                    JButton button = new SortHeader(titles[i], key);
                    sortHeaders[i] = button;
                    add(button);
                    button.addActionListener(event -> {
                        if (!administrator() || mutating) return;
                        sortDescending = key.equals(sortKey) ? !sortDescending : false;
                        sortKey = key;
                        repaint();
                        load(1);
                    });
                }
            }
            @Override public Dimension getPreferredSize() { return new Dimension(0, 66); }
            @Override public void doLayout() {
                int[] edges = columnEdges(Math.max(0, getWidth() - 8));
                for (int i = 0; i < titles.length; i++) getComponent(i).setBounds(
                        edges[i], 6, Math.max(0, edges[i + 1] - edges[i] - 4), 56);
            }
        };
        headerBottom.add(tableHeader, BorderLayout.SOUTH);
        header.add(actions, BorderLayout.CENTER);
        header.add(headerBottom, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        rows.setName("users-rows");
        rows.setLayout(null);
        rows.setOpaque(false);
        rows.setAlignmentX(LEFT_ALIGNMENT);
        usersScroll.setName("users-list-scroll");
        usersScroll.getVerticalScrollBar().setBlockIncrement(100);
        usersScroll.getAccessibleContext().setAccessibleName(t("users.listAccessible"));
        add(usersScroll, BorderLayout.CENTER);
        totalStatus.setName("users-total-status");
        JPanel size = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        size.setOpaque(false);
        JLabel sizeLabel = label(t("users.perPage"), 12, JourneyStyle.PAPER, false);
        sizeLabel.setLabelFor(pageSize);
        pageSize.setName("users-page-size");
        pageSize.getAccessibleContext().setAccessibleName(t("users.perPageAccessible"));
        pageSize.setSelectedItem(25);
        pageSize.setMaximumRowCount(4);
        pageSize.setPreferredSize(new Dimension(96, 48));
        pageSize.setMinimumSize(new Dimension(80, 48));
        size.setName("users-page-size-group");
        size.add(sizeLabel);
        size.add(pageSize);
        previous.setIcon(new PagerArrow(false));
        next.setIcon(new PagerArrow(true));
        for (JButton button : List.of(previous, next)) {
            button.setBorder(null);
            button.setContentAreaFilled(false);
        }
        JPanel pager = new JPanel(null) {
            @Override public void doLayout() {
                previous.setBounds(2, 2, 40, 44);
                next.setBounds(getWidth() - 42, 2, 40, 44);
                pageStatus.setBounds(44, 2, Math.max(0, getWidth() - 88), 44);
            }
            @Override protected void paintComponent(Graphics graphics) {
                graphics.setColor(PixelInputStyle.FACE);
                graphics.fillRect(0, 0, getWidth(), getHeight());
                PixelInputStyle.paintShadow(graphics, 0, 0, getWidth(), getHeight());
            }
        };
        pager.setBorder(PixelInputStyle.outlineBorder());
        previous.setName("users-previous");
        previous.getAccessibleContext().setAccessibleName(t("users.previous"));
        next.setName("users-next");
        next.getAccessibleContext().setAccessibleName(t("users.next"));
        pageStatus.setName("users-page-status");
        pageStatus.setHorizontalAlignment(SwingConstants.CENTER);
        pager.setName("users-page-buttons");
        pager.add(previous);
        pager.add(pageStatus);
        pager.add(next);
        JPanel footer = new JPanel(null) {
            @Override public Dimension getPreferredSize() {
                return new Dimension(400, UsersPanel.this.getWidth() >= 620 ? 66 : 118);
            }
            @Override public void doLayout() {
                int sizeWidth = Math.min(getWidth(), size.getPreferredSize().width);
                int pagerWidth = Math.min(getWidth(), Math.max(180,
                        pageStatus.getPreferredSize().width + 100));
                if (getWidth() >= 620) {
                    totalStatus.setBounds(0, 18, Math.max(0,
                            (getWidth() - pagerWidth) / 2 - 12), 48);
                    pager.setBounds((getWidth() - pagerWidth) / 2, 18, pagerWidth, 48);
                    size.setBounds(getWidth() - sizeWidth, 18, sizeWidth, 48);
                } else {
                    totalStatus.setBounds(0, 18, Math.max(0,
                            getWidth() - sizeWidth - 12), 48);
                    size.setBounds(getWidth() - sizeWidth, 18, sizeWidth, 48);
                    pager.setBounds((getWidth() - pagerWidth) / 2, 70, pagerWidth, 48);
                }
            }
        };
        footer.setName("users-pagination");
        footer.setOpaque(false);
        footer.add(totalStatus);
        footer.add(size);
        footer.add(pager);
        add(footer, BorderLayout.SOUTH);
        previous.addActionListener(event -> {
            if (!loading && currentPage > 1) load(currentPage - 1);
        });
        next.addActionListener(event -> {
            if (!loading && currentPage < totalPages) load(currentPage + 1);
        });
        pageSize.addActionListener(event -> {
            if (!updatingPageSize && administrator()) load(1);
        });
        clearSession();
    }

    // Switching accounts or servers invalidates every outstanding result, including
    // errors.
    public void setSession(URI host, LauncherAuthClient.Session session) {
        if (host == null || session == null
                || !"ADMIN".equalsIgnoreCase(session.role())) {
            clearSession();
            return;
        }
        if (host.equals(this.host) && session.equals(this.session)) return;
        generation++;
        this.host = host;
        this.session = session;
        skins.clear();
        mutating = false;
        notice.setVisible(false);
        searchInput.setText("");
        query = "";
        currentPage = 1;
        totalPages = 1;
        total = 0;
        updatingPageSize = true;
        try {
            pageSize.setSelectedItem(25);
        } finally {
            updatingPageSize = false;
        }
        load(1);
    }

    public void clearSession() {
        generation++;
        host = null;
        session = null;
        loading = false;
        mutating = false;
        skins.clear();
        notice.setText("");
        notice.setVisible(false);
        currentPage = 1;
        totalPages = 1;
        total = 0;
        query = "";
        searchInput.setText("");
        updatingPageSize = true;
        try {
            pageSize.setSelectedItem(25);
        } finally {
            updatingPageSize = false;
        }
        showMessage(t("users.adminOnly"), false);
        updateControls();
    }

    CompletableFuture<Void> pendingLoad() { return pendingLoad; }
    CompletableFuture<Void> pendingMutation() { return pendingMutation; }
    CompletableFuture<Void> pendingSkins() { return pendingSkins; }

    private boolean administrator() {
        return host != null && session != null
                && "ADMIN".equalsIgnoreCase(session.role());
    }

    private void startSearch() {
        if (!administrator() || mutating) return;
        query = searchInput.getText().strip();
        load(1);
    }

    private void load(int requestedPage) {
        if (!administrator() || mutating) return;
        long id =++generation;
        URI requestedHost = host;
        String token = session.accessToken(), requestedQuery = query;
        int requestedSize = (Integer) pageSize.getSelectedItem();
        String requestedSort = sortKey, requestedDirection
                = sortDescending ? "desc" : "asc";
        loading = true;
        showMessage(t("users.loading"), false);
        updateControls();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingLoad = completion;
        CompletableFuture.supplyAsync(() -> {
            try {
                return Objects.requireNonNull(loader.search(requestedHost,
                        token, requestedQuery, requestedPage, requestedSize,
                        requestedSort, requestedDirection));
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CompletionException(failure);
            }
            catch (IOException failure) { throw new CompletionException(failure); }
        }).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (id != generation || !administrator()) {
                completion.complete(null);
                return;
            }
            loading = false;
            if (failure != null) {
                total = 0;
                totalPages = 1;
                currentPage = 1;
                showMessage(t("users.loadError"), true);
            } else {
                currentPage = result.page();
                totalPages = Math.max(1, result.totalPages());
                total = result.total();
                showUsers(result.users());
            }
            updateControls();
            completion.complete(null);
        }));
    }

    private void updateControls() {
        boolean allowed = administrator();
        for (JButton button : sortHeaders) if (button != null) {
            button.setEnabled(allowed && !mutating);
            button.repaint();
        }
        searchInput.setEnabled(allowed && !mutating);
        search.setEnabled(allowed && !mutating);
        pageSize.setEnabled(allowed && !mutating);
        previous.setEnabled(allowed && !loading && !mutating && currentPage > 1);
        next.setEnabled(allowed && !loading && !mutating && currentPage < totalPages);
        for (Component child : rows.getComponents())
                if (child instanceof UserRow row) row.setActionsEnabled(allowed
                && !loading && !mutating, session == null ? null : session.userId());
        pageStatus.setText(loading ? t("common.loading")
                : currentPage + " / " + totalPages);
        pageStatus.getAccessibleContext().setAccessibleName(pageStatus.getText());
        int count = (Integer) pageSize.getSelectedItem();
        long first = total == 0 ? 0 : (long)(currentPage - 1) * count + 1;
        long last = Math.min(total, (long) currentPage * count);
        totalStatus.setText(!allowed || loading ? "" : t("users.count",
                Long.toString(first), Long.toString(last), Long.toString(total)));
        totalStatus.setToolTipText(totalStatus.getText());
    }

    private void showUsers(List<LauncherUsersClient.User> users) {
        rows.removeAll();
        if (users.isEmpty()) {
            showMessage(query.isEmpty() ? t("users.empty") : t("users.notFound"), false);
            return;
        }
        long requestedGeneration = generation;
        URI requestedHost = host;
        java.util.Map<String, CompletableFuture<BufferedImage>> requests
                = new java.util.HashMap<>();
        List<CompletableFuture<Void>> applied = new ArrayList<>();
        for (LauncherUsersClient.User user : users) {
            UserRow row = new UserRow(user);
            for (Action action : Action.values()) row.button(action).addActionListener(
                    event -> edit(user, action, requestedGeneration));
            rows.add(row);
            rows.add(new PixelSeparator(0));
            if (user.skinHash() == null) continue;
            String key = requestedHost + "#" + user.skinHash();
            BufferedImage cached = skins.get(key);
            if (cached != null) {
                row.avatar.setSkin(cached);
                continue;
            }
            CompletableFuture<BufferedImage> request = requests.computeIfAbsent(key,
                    ignored -> CompletableFuture.supplyAsync(() -> {
                if (requestedGeneration != generation) return null;
                try { return skinLoader.load(requestedHost, user.skinHash()); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(failure);
                }
                catch (IOException failure) { throw new CompletionException(failure); }
            }, SKIN_WORKERS));
            CompletableFuture<Void> shown = new CompletableFuture<>();
            applied.add(shown);
            request.whenComplete((skin, failure) -> SwingUtilities.invokeLater(() -> {
                if (requestedGeneration == generation && administrator()
                        && failure == null && validSkin(skin)) {
                    skins.put(key, skin);
                    row.avatar.setSkin(skin);
                }
                shown.complete(null);
            }));
        }
        pendingSkins = CompletableFuture.allOf(applied.toArray(CompletableFuture[]::new));
        revalidate();
        repaint();
        usersScroll.getViewport().setViewPosition(new Point());
    }

    private void edit(LauncherUsersClient.User user, Action action, long rowGeneration) {
        if (!administrator() || loading || mutating
                || rowGeneration != generation) return;
        if (user.id().equals(session.userId()) && action != Action.CLEAR_SKIN) return;
        if (action == Action.BAN && user.banned()) return;
        if (action == Action.CLEAR_SKIN && user.skinHash() == null) return;
        URI requestedHost = host;
        String token = session.accessToken();
        AdminUserDialogs.Edit edit = dialogs.request(this, action, user);
        if (edit == null) return;
        // A modal prompt runs a nested event loop: logout/server changes must invalidate
        // its answer too.
        if (!administrator() || rowGeneration != generation || action != edit.action()) {
            edit.close();
            return;
        }
        mutating = true;
        showNotice(t("users.applying"), false);
        updateControls();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingMutation = completion;
        CompletableFuture.runAsync(() -> {
            try {
                administrativeActions.perform(requestedHost, token, user.id(),
                        action, edit.value(), edit.password(), edit.expiresAt());
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CompletionException(failure);
            }
            catch (IOException failure) { throw new CompletionException(failure); }
            finally { edit.close(); }
        }).whenComplete((unused, failure) -> SwingUtilities.invokeLater(() -> {
            if (rowGeneration != generation || !administrator()) {
                completion.complete(null);
                return;
            }
            mutating = false;
            if (failure != null) {
                showNotice(actionFailure(failure), true);
                updateControls();
                completion.complete(null);
                return;
            }
            skins.clear();
            if (action == Action.CLEAR_SKIN) firePropertyChange("userSkinCleared",
                    null, user.id());
            showNotice(switch (action) {
                case BAN -> t("users.bannedSuccess");
                case CLEAR_SKIN -> t("users.skinSuccess");
                case PASSWORD -> t("users.passwordSuccess");
                case EMAIL -> t("users.emailSuccess");
            }, false);
            load(currentPage);
            pendingLoad.whenComplete((ignored,
                    reloadFailure) -> completion.complete(null));
        }));
    }

    private void showNotice(String text, boolean error) {
        notice.setText(text);
        notice.setToolTipText(text);
        notice.setForeground(error ? new Color(0xFF9B87) : JourneyStyle.GREEN);
        notice.setVisible(true);
        revalidate();
        repaint();
    }

    private static String actionFailure(Throwable failure) {
        while (failure instanceof CompletionException
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof LauncherAuthClient.AuthHttpException rejected)
                return switch (rejected.status()) {
            case 503 -> t("users.updateServer");
            case 401 -> t("auth.sessionExpiredUsers");
            case 403 -> t("users.forbidden");
            case 404 -> t("users.gone");
            case 409 -> t("users.conflict");
            case 429 -> t("users.rateLimited");
            default -> t("users.changeDataError");
        };
        return t("users.changeNetworkError");
    }

    private static boolean validSkin(BufferedImage image) {
        return image != null && image.getWidth() == 64
                && (image.getHeight() == 64 || image.getHeight() == 32);
    }

    private void showMessage(String text, boolean error) {
        pendingSkins = CompletableFuture.completedFuture(null);
        rows.removeAll();
        JLabel message = label(text, 14, error ? new Color(0xFF9B87)
                : new Color(0xD0D1D4), false);
        message.setName("users-message");
        message.setAlignmentX(LEFT_ALIGNMENT);
        message.setBorder(BorderFactory.createEmptyBorder(14, 0, 14, 0));
        message.setToolTipText(text);
        message.getAccessibleContext().setAccessibleName(text);
        rows.add(message);
        revalidate();
        repaint();
        usersScroll.getViewport().setViewPosition(new Point());
    }

    private static JLabel label(String text, float size, Color color, boolean ten) {
        // Disable Swing's HTML renderer before assigning server-supplied account text.
        JLabel label = JourneyStyle.label("", size, color, ten);
        label.setText(text);
        label.setToolTipText(AdminUserDialogs.tooltip(text));
        return label;
    }

    static final class UserRow extends JPanel {
        private static final DateTimeFormatter DATE
                = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm",
                Locale.forLanguageTag("es")).withZone(ZoneId.systemDefault());
        private static final int LEFT = 58;
        final JLabel username, role, email, status, created, lastSeen;
        final SkinHead avatar = new SkinHead();
        private final JLabel createdLabel = label(t("users.accountCreated"),
                11, new Color(0xA69F9B), false);
        private final JLabel seenLabel = label(t("users.lastSeen"),
                11, new Color(0xA69F9B), false);
        private final LauncherUsersClient.User user;
        final PixelDropdown<Action> options = new PixelDropdown<>(List.of(
                Action.values()), action -> switch (action) {
            case BAN -> t("users.ban");
            case CLEAR_SKIN -> t("users.clearSkin");
            case PASSWORD -> t("auth.password");
            case EMAIL -> t("users.email");
        });
        private boolean updatingOptions;
        private final java.util.Map<Action, PixelButton> buttons
                = new java.util.EnumMap<>(Action.class);

        UserRow(LauncherUsersClient.User user) {
            this.user = user;
            setName("users-row-" + user.id());
            setOpaque(false);
            setLayout(null);
            setAlignmentX(LEFT_ALIGNMENT);
            username = label(user.username(), 20, Color.WHITE, true);
            username.setName("users-row-name");
            String userRole = user.role() == null ? ""
                    : user.role().toUpperCase(Locale.ROOT);
            role = label(switch (userRole) {
                case "ADMIN" -> t("users.administrator");
                case "MODERATOR" -> t("users.moderator");
                default -> t("auth.user");
            }, 12, new Color(0xD0D1D4), true);
            role.setName("users-row-role");
            role.setToolTipText(AdminUserDialogs.tooltip(user.role()));
            role.setFont(LauncherFonts.minecraftFive(12));
            email = label(user.email(), 14, new Color(0xB9B5B3), false);
            email.setName("users-row-email");
            String state = user.status() == null ? ""
                    : user.status().toUpperCase(Locale.ROOT);
            status = label(user.banned() ? t("users.banned") : switch (state) {
                case "ACTIVE" -> t("users.active");
                case "SUSPENDED" -> t("users.suspended");
                case "PENDING_VERIFICATION" -> t("users.unverified");
                default -> user.status();
            }, 15, user.banned() ? new Color(0xFF9B87) : switch (state) {
                        case "ACTIVE" -> JourneyStyle.GREEN;
                        case "SUSPENDED" -> new Color(0xEDBF72);
                        default -> new Color(0xD0D1D4);
            }, false);
            status.setName("users-row-status");
            status.setHorizontalAlignment(SwingConstants.RIGHT);
            status.setFont(LauncherFonts.minecraftSeven(14));
            String reason = user.banned() || "SUSPENDED".equals(state) ?
                    user.restrictionReason() == null || user.restrictionReason().isBlank()
                    ? t("users.noReason") : user.restrictionReason() : "ACTIVE".equals(
                    state) ? t("users.noRestrictions") : t("users.pending");
            status.setToolTipText(AdminUserDialogs.tooltip(reason));
            status.getAccessibleContext().setAccessibleDescription(reason);
            created = label(date(user.createdAt()), 14, new Color(0xD0D1D4), false);
            created.setName("users-row-created");
            lastSeen = label(date(user.lastSeenAt()), 14, new Color(0xD0D1D4), false);
            lastSeen.setName("users-row-last-seen");
            created.setToolTipText(user.createdAt() == null ? t("users.noCreated")
                    : t("users.createdTooltip", user.createdAt().toString()));
            lastSeen.setToolTipText(user.lastSeenAt() == null ? t("users.noActivity")
                    : t("users.lastSeenTooltip", user.lastSeenAt().toString()));
            avatar.setName("users-row-avatar");
            avatar.getAccessibleContext().setAccessibleName(t("users.skinHead",
                    user.username()));
            avatar.setToolTipText(AdminUserDialogs.tooltip(t("users.skin",
                    user.username())));
            add(avatar);
            for (JLabel item : List.of(username, role, email,
                    status, created, lastSeen)) add(item);
            addButton(Action.BAN, t("users.ban"), PixelButton.Variant.DANGER);
            addButton(Action.CLEAR_SKIN, t("users.clearSkin"),
                    PixelButton.Variant.NEUTRAL);
            addButton(Action.PASSWORD, t("auth.password"), PixelButton.Variant.DARK);
            addButton(Action.EMAIL, t("users.email"), PixelButton.Variant.DARK);
            getAccessibleContext().setAccessibleName(user.username() + ", "
                    + role.getText() + ", " + status.getText());
            options.setName("users-row-options");
            options.setArrowOnly(true);
            options.setPlaceholder("");
            options.setMinimumSize(new Dimension(40, 40));
            options.setMaximumRowCount(4);
            options.getAccessibleContext().setAccessibleName(t("users.actions",
                    user.username()));
            options.addActionListener(event -> {
                if (!updatingOptions
                        && options.getSelectedItem() instanceof Action action) {
                    options.setSelectedIndex(-1);
                    button(action).doClick(0);
                } });
                add(options);
            setActionsEnabled(false, null);
        }

        private void addButton(Action action, String text, PixelButton.Variant style) {
            PixelButton button = new PixelButton(text, style);
            button.setFont(LauncherFonts.minecraftSeven(12));
            button.setName("users-" + action.name().toLowerCase(
                    Locale.ROOT).replace('_', '-'));
            button.getAccessibleContext().setAccessibleName(text
                    + " · " + user.username());
            buttons.put(action, button);
        }
        PixelButton button(Action action) { return buttons.get(action); }
        void setActionsEnabled(boolean enabled, java.util.UUID actor) {
            boolean self = user.id().equals(actor);
            updatingOptions = true;
            options.removeAllItems();
            for (Action action : Action.values()) {
                PixelButton button = button(action);
                boolean selfProtected = self && action != Action.CLEAR_SKIN;
                boolean unavailable = action == Action.BAN && user.banned()
                        || action == Action.CLEAR_SKIN && user.skinHash() == null;
                button.setEnabled(enabled && !selfProtected && !unavailable);
                if (button.isEnabled()) options.addItem(action);
                button.setToolTipText(selfProtected ? t("users.manageSelf")
                        : action == Action.BAN && user.banned() ? t("users.alreadyBanned")
                        : action == Action.CLEAR_SKIN && user.skinHash() == null
                        ? t("users.noCustomSkin") : switch (action) {
                    case BAN -> t("users.banTooltip");
                            case CLEAR_SKIN -> t("users.skinTooltip");
                            case PASSWORD -> t("users.passwordTooltip");
                            case EMAIL -> t("users.emailTooltip");
                            });
            }
            options.setSelectedIndex(-1);
            options.setEnabled(enabled && options.getItemCount() > 0);
            updatingOptions = false;
        }

        private static String date(Instant value) {
            return value == null ? t("users.noRecord") : DATE.format(value);
        }
        private static int heightForWidth(int width) { return 84; }
        @Override public Dimension getPreferredSize() { return new Dimension(0, 84); }
        @Override public void doLayout() {
            int[] edges = columnEdges(getWidth());
            avatar.setBounds(8, 20, 42, 42);
            username.setBounds(LEFT, 18, Math.max(0, edges[1] - LEFT - 8), 25);
            role.setBounds(LEFT, 46, Math.max(0, edges[1] - LEFT - 8), 18);
            JLabel[] cells = { email, created, lastSeen, status };
            for (int i = 0; i < cells.length; i++) cells[i].setBounds(edges[i + 1] + 8,
                    30, Math.max(0, edges[i + 2] - edges[i + 1] - 12), 22);
            status.setHorizontalAlignment(SwingConstants.LEFT);
            options.setBounds(edges[5] + 2, 22, Math.max(0, edges[6] - edges[5] - 4), 40);
        }
    }

    private static int[] columnEdges(int width) {
        return new int[] { 0, width * 25 / 100, width * 46 / 100, width * 63 / 100,
                width * 79 / 100, Math.max(0, width - 46), width };
    }
    private final class SortHeader extends JButton {
        private final String key;
        SortHeader(String title, String key) {
            super(title);
            this.key = key;
            setName("users-sort-" + key);
            setFont(LauncherFonts.minecraftTen(20));
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(t("users.sort", title));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); try {
                g.setFont(LauncherFonts.forLocale(getFont()));
                g.setColor(key.equals(sortKey) ? JourneyStyle.GREEN : JourneyStyle.PAPER);
                FontMetrics fm = g.getFontMetrics();
                String title = getText();
                if (fm.stringWidth(I18n.resolve(title)) > getWidth() - 32
                        && title.contains(" ")) {
                    int space = title.lastIndexOf(' ');
                    g.drawString(I18n.resolve(title.substring(0, space)), 8, 23);
                    g.drawString(I18n.resolve(title.substring(space + 1)), 8, 47);
                } else g.drawString(I18n.resolve(title), 8,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g.setColor(isEnabled() ? JourneyStyle.PAPER : new Color(0x777270));
                int x = getWidth() - 18, y = (getHeight() - 8) / 2;
                for (int row = 0; row < 4; row++) {
                    int step = key.equals(sortKey) && !sortDescending ? 3 - row : row;
                    g.fillRect(x + step * 2, y + row * 2, 14 - step * 4, 2);
                }
                if (hasFocus()) {
                    g.setColor(JourneyStyle.GREEN);
                    g.drawRect(2, 2, getWidth() - 5, getHeight() - 5);
                }
            } finally { g.dispose(); }
        }
    }
    private static final class PagerArrow implements Icon {
        private final boolean right;
        PagerArrow(boolean right) {
            this.right = right;
        }
        public int getIconWidth() {
            return 8;
        }
        public int getIconHeight() {
            return 14;
        }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(component.isEnabled() ? JourneyStyle.PAPER
                    : new Color(0x777270));
            for (int row = 0; row < 4; row++) {
                int step = right ? row : 3 - row;
                graphics.fillRect(x + row * 2, y + step * 2, 2, 14 - step * 4);
            }
        }
    }
    private static final class PagerButton extends JButton {
        PagerButton() {
            setOpaque(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (getModel().isRollover() && isEnabled()) {
                graphics.setColor(new Color(255, 255, 255, 20));
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
            if (getIcon() != null) getIcon().paintIcon(this, graphics,
                    (getWidth() - getIcon().getIconWidth()) / 2,
                    (getHeight() - getIcon().getIconHeight()) / 2);
        }
    }

    static final class SkinHead extends JComponent {
        private BufferedImage face;
        SkinHead() {
            accessibleContext = new AccessibleJComponent() { };
            setSkin(DefaultSkin.image());
        }
        void setSkin(BufferedImage skin) {
            if (!validSkin(skin)) return;
            face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) face.setRGB(x,
                    y, skin.getRGB(8 + x, 8 + y) | 0xff000000);
            Graphics2D g = face.createGraphics();
            try {
                g.drawImage(skin, 0, 0, 8, 8, 40, 8, 48, 16, null);
            } finally {
                g.dispose();
            }
            repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(JourneyStyle.GREEN);
                g.fillRect(0, 0, getWidth(), 2);
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
                g.fillRect(0, 2, 2, Math.max(0, getHeight() - 4));
                g.fillRect(getWidth() - 2, 2, 2, Math.max(0, getHeight() - 4));
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(face, 3, 3, getWidth() - 6, getHeight() - 6, null);
            } finally { g.dispose(); }
        }
    }

    private static final class RowsPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredSize() {
            int width = getParent() instanceof JViewport viewport
                    ? viewport.getExtentSize().width : getWidth();
            int height = 0;
            for (Component child : getComponents()) if (child.isVisible()) height
                    += child instanceof UserRow ? UserRow.heightForWidth(width)
                    : child.getPreferredSize().height;
            return new Dimension(0, height);
        }
        @Override public void doLayout() {
            int y = 0;
            for (Component child : getComponents()) if (child.isVisible()) {
                int height = child instanceof UserRow ? UserRow.heightForWidth(
                        getWidth()) : child.getPreferredSize().height;
                child.setBounds(0, y, getWidth(), height);
                y += height;
            }
        }
        @Override public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(400, 300);
        }
        @Override public int getScrollableUnitIncrement(Rectangle visible,
                int orientation, int direction) {
            return 24;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible,
                int orientation, int direction) {
            return Math.max(24, visible.height - 100);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class SearchField extends JTextField {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty() && !hasFocus()) {
                Graphics copy = graphics.create();
                try {
                    Insets insets = getInsets();
                    copy.clipRect(insets.left, insets.top, Math.max(0,
                            getWidth() - insets.left - insets.right), Math.max(0,
                            getHeight() - insets.top - insets.bottom));
                    copy.setFont(LauncherFonts.forLocale(getFont()));
                    copy.setColor(new Color(0xABA09C));
                    FontMetrics metrics = copy.getFontMetrics();
                    copy.drawString(I18n.resolve(t("users.searchHint")), insets.left, (
                            getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                } finally { copy.dispose(); }
            }
        }
    }
}

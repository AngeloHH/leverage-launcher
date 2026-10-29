package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

// Native approved journey: landscape, one moving navigation, and connected real launcher
// panels.
public final class LauncherHomePanel extends JPanel {
    public enum Screen { HOME, LIBRARY, SKINS, NEWS, SETTINGS, ABOUT }
    public enum Section {
        PROFILE, MODS, RESOURCEPACKS, SKINS, SETTINGS, VERSIONS, USERS, SERVER, NEWS }
    private final BiConsumer<String, Boolean> systemNotice;
    private final ProfileSettingsPanel profileSettings = new ProfileSettingsPanel();
    private final ServerSettingsPanel serverSettings = new ServerSettingsPanel();
    private final ModListPanel mods = new ModListPanel(),
            resourcepacks = new ModListPanel(true);
    private final SkinGalleryPanel skins = new SkinGalleryPanel();
    private final InstanceSelectionPanel instances = new InstanceSelectionPanel();
    private final JourneySettingsPanel settings;
    private final AboutPanel about;
    private final JourneyLibraryPanel library;
    private final UsersPanel users;
    private final JourneyNewsPanel news = new JourneyNewsPanel();
    private boolean administrator;
    private final Map<Screen, JComponent> screens = new EnumMap<>(Screen.class);
    private final JPanel viewport = new JPanel(new BorderLayout());
    private final JourneyNavigation navigation = new JourneyNavigation(this::showScreen);
    private final SceneTravel travel = new SceneTravel();
    private final PlayerAccountCard account = new PlayerAccountCard();
    private final JButton settingsButton = JourneyStyle.link(t(
            "settings.link"), this::showSettings);
    private Runnable playAction, installAction = () -> showNotice(
            t("game.installerNotConnected"));
    private Screen screen = Screen.HOME, from = Screen.HOME, queued;
    private Section selectedSection = Section.VERSIONS;
    private BufferedImage outgoing, incoming;
    private boolean animationEnabled = !Boolean.getBoolean("leverage.reduceMotion"),
            transitioning, reverse, previewMotion;
    private double progress = 1;
    private long started;
    private int layoutWidth, layoutHeight;
    private final Timer timer = new Timer(16, event -> pulse());

    public LauncherHomePanel() { this(null); }
    LauncherHomePanel(BiConsumer<String, Boolean> notificationHandler) {
        this(notificationHandler, new UsersPanel());
    }
    LauncherHomePanel(BiConsumer<String, Boolean> notificationHandler, UsersPanel users) {
        super(null);
        systemNotice = notificationHandler == null ? new DesktopNotifications(
                this)::show : notificationHandler;
        this.users = Objects.requireNonNull(users);
        setName("launcher-home");
        setBackground(JourneyStyle.INK);
        setPreferredSize(new Dimension(1040, 680));
        timer.setCoalesce(true);
        account.setName("account-profile");
        account.setAccountAction(() -> showSection(Section.PROFILE));
        settingsButton.setName("header-settings");
        settingsButton.setForeground(JourneyStyle.PAPER);
        account.setCompact(true);
        navigation.setSideComponents(account, settingsButton);
        settings = new JourneySettingsPanel(profileSettings,
                serverSettings, this::showAbout);
        about = new AboutPanel(this::showSettings);
        library = new JourneyLibraryPanel(instances, mods, resourcepacks,
                users, section -> selectedSection = section);
        screens.put(Screen.HOME, new JourneyHomeScene(instances,
                this::play, this::showNews));
        screens.put(Screen.LIBRARY, library);
        screens.put(Screen.SKINS, JourneyStyle.scroll(new JourneyCollectionPanel(
                instances, skins, this::play), JourneyStyle.TABLE));
        screens.put(Screen.NEWS, JourneyStyle.scroll(news, JourneyStyle.TABLE));
        screens.put(Screen.SETTINGS, JourneyStyle.scroll(settings, JourneyStyle.INK));
        screens.put(Screen.ABOUT, JourneyStyle.scroll(about, JourneyStyle.INK));
        viewport.setName("journey-page-viewport");
        viewport.setBackground(JourneyStyle.INK);
        viewport.add(screens.get(screen));
        add(navigation);
        add(travel);
        add(viewport);
        setComponentZOrder(navigation, 0);
        setComponentZOrder(travel, 1);
        travel.setVisible(false);
        mods.onChangeVersion(() -> showSection(Section.VERSIONS));
        resourcepacks.onChangeVersion(() -> showSection(Section.VERSIONS));
        mods.onNotice(this::notifyUser);
        resourcepacks.onNotice(this::notifyUser);
        skins.onNotice(this::notifyUser);
        profileSettings.onNotice(this::notifyUser);
        skins.onPreview(this::setSkin);
        instances.onError(message -> notifyUser(message, true));
        instances.onInstall(() -> installAction.run());
        setAccount("Jugador", false);
        navigation.select(Screen.HOME);
        setAnimationEnabled(animationEnabled);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentHidden(ComponentEvent event) {
                finishTransition();
            }
        });
    }
    public void showHome() { showScreen(Screen.HOME); }
    public void showSettings() { showScreen(Screen.SETTINGS); }
    public void showAbout() { showScreen(Screen.ABOUT); }
    private void showNews() { showScreen(Screen.NEWS); }
    public Screen selectedScreen() { return screen; }
    public void showScreen(Screen target) {
        Objects.requireNonNull(target);
        if (target == Screen.NEWS) news.refresh();
        if (transitioning) {
            queued = target;
            return;
        }
        if (target == screen) {
            navigation.select(target);
            return;
        }
        from = screen;
        int pageHeight = Math.max(1, getHeight() - JourneyNavigation.HEIGHT);
        boolean animate = animationEnabled && (isShowing()
                || previewMotion) && getWidth() > 0;
        if (animate) outgoing = JourneyStyle.snapshot(viewport, getWidth(), pageHeight);
        screen = target;
        viewport.removeAll();
        viewport.add(screens.get(target));
        viewport.setSize(getWidth(), pageHeight);
        JourneyStyle.layoutTree(viewport);
        navigation.select(target);
        if (!animate) {
            progress = 1;
            revalidate();
            doLayout();
            repaint();
            return;
        }
        incoming = JourneyStyle.snapshot(viewport, getWidth(), pageHeight);
        reverse = rank(target) < rank(from);
        progress = 0;
        transitioning = true;
        started = System.nanoTime();
        viewport.setVisible(false);
        travel.setVisible(true);
        timer.start();
        doLayout();
        repaint();
    }
    public void showSection(Section section) {
        if (section == Section.USERS && !administrator) return;
        selectedSection = Objects.requireNonNull(section);
        switch (section) {
            case VERSIONS -> {
                library.selectTab(0);
                showScreen(Screen.LIBRARY);
            }
            case MODS -> {
                library.selectTab(1);
                showScreen(Screen.LIBRARY);
            }
            case RESOURCEPACKS -> {
                library.selectTab(2);
                showScreen(Screen.LIBRARY);
            }
            case USERS -> {
                library.selectTab(3);
                showScreen(Screen.LIBRARY);
            }
            case SKINS -> showScreen(Screen.SKINS);
            case NEWS -> showScreen(Screen.NEWS);
            case PROFILE, SETTINGS, SERVER -> showScreen(Screen.SETTINGS);
        }
    }
    Section selectedSection() { return selectedSection; }
    public void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
        navigation.setAnimationEnabled(enabled);
        library.setAnimationEnabled(enabled);
        if (!enabled) finishTransition();
    }
    void setTransitionProgressForTest(double value) {
        if (!transitioning) return;
        timer.stop();
        progress = Math.max(0, Math.min(1, value));
        if (progress >= 1) finishTransition();
        else {
            doLayout();
            repaint();
        }
    }
    void previewTransitionForTest(Screen target, double value) {
        previewMotion = true;
        try {
            showScreen(target);
        } finally {
            previewMotion = false;
        }
        setTransitionProgressForTest(value);
    }
    double transitionProgress() { return progress; }
    private static int rank(Screen screen) { return screen.ordinal(); }
    private void pulse() {
        if (!isShowing()) {
            finishTransition();
            return;
        }
        progress = Math.min(1, (System.nanoTime() - started) / 650_000_000d);
        if (progress >= 1) finishTransition();
        else {
            doLayout();
            repaint();
        }
    }
    private void finishTransition() {
        timer.stop();
        transitioning = false;
        progress = 1;
        outgoing = null;
        incoming = null;
        travel.setVisible(false);
        viewport.setVisible(true);
        Screen next = queued;
        queued = null;
        revalidate();
        doLayout();
        repaint();
        if (next != null && next != screen) showScreen(next);
    }
    @Override public void doLayout() {
        if ((layoutWidth != getWidth() || layoutHeight != getHeight()) && transitioning) {
            timer.stop();
            transitioning = false;
            progress = 1;
            outgoing = null;
            incoming = null;
            travel.setVisible(false);
            viewport.setVisible(true);
            queued = null;
        }
        layoutWidth = getWidth();
        layoutHeight = getHeight();
        int width = getWidth(), body = Math.max(0, getHeight()),
                page = Math.max(0, body - JourneyNavigation.HEIGHT);
        int nav = screen == Screen.HOME ? page : 0;
        if (transitioning) {
            var frame = JourneyMotion.vertical(page, from == Screen.HOME ? page : 0,
                    screen == Screen.HOME ? page : 0, reverse, progress);
            nav = (int) Math.round(frame.navigationY());
        }
        navigation.setBounds(0, nav, width, JourneyNavigation.HEIGHT);
        navigation.doLayout();
        viewport.setBounds(0, screen == Screen.HOME ? 0
                : JourneyNavigation.HEIGHT, width, page);
        viewport.doLayout();
        travel.setBounds(0, 0, width, body);
    }
    public void setSkin(BufferedImage texture, boolean slim) {
        account.setSkin(texture);
        skins.setCurrentSkin(texture, slim);
    }
    public void setAccount(String username, boolean online) {
        account.setAccount(username, online);
        profileSettings.setIdentity(username, online);
        if (!online) setUsersSession(null, null);
    }
    public void setUsersSession(URI host,
            dev.leverage.client.LauncherAuthClient.Session session) {
        administrator = host != null && session != null && "ADMIN".equals(session.role());
        if (administrator) users.setSession(host, session);
        else users.clearSession();
        library.setAdministrator(administrator);
        news.setSession(host, session);
        if (!administrator && selectedSection == Section.USERS) selectedSection
                = Section.VERSIONS;
    }
    public UsersPanel users() { return users; }
    public void setAccountMenu(JPopupMenu menu) { account.setAccountMenu(menu); }
    public ServerSettingsPanel serverSettings() { return serverSettings; }
    public ModListPanel mods() { return mods; }
    public ModListPanel resourcepacks() { return resourcepacks; }
    public SkinGalleryPanel skins() { return skins; }
    public ProfileSettingsPanel profile() { return profileSettings; }
    public JourneySettingsPanel settings() { return settings; }
    public void configureInstances(dev.leverage.client.LauncherPreferences preferences) {
        instances.configure(preferences, path -> {
            mods.clearDirectory();
            resourcepacks.clearDirectory();
            settings.setInstanceDirectory(path);
            if (path != null) {
                mods.loadDirectory(path.resolve("mods"));
                resourcepacks.loadDirectory(path.resolve("resourcepacks"));
            }
        });
    }
    public void setInstanceServer(URI host) { instances.setServer(host); }
    public void showNotice(String text) { notifyUser(text, false); }
    public void showNotice(String text, Color background) {
        notifyUser(text, new Color(0xd4441a).equals(background));
    }
    private void notifyUser(String message, boolean error) {
        if (message != null && !message.isBlank()) systemNotice.accept(message, error);
    }
    public void onPlay(Runnable action) { playAction = Objects.requireNonNull(action); }
    public void onInstall(Runnable action) {
        installAction = Objects.requireNonNull(action);
    }
    public String selectedVersion() { return instances.selectedVersion(); }
    public void refreshVersions() { instances.refreshCatalog(); }
    public void setPlayState(String text, boolean enabled) {
        for (var page : screens.values()) updatePlayButtons(page, text, enabled);
    }
    private static void updatePlayButtons(Container parent,
            String text, boolean enabled) {
        for (Component child : parent.getComponents()) {
            if (child instanceof PixelButton button && ("home-play".equals(
                    button.getName()) || "skins-play".equals(button.getName()))) {
                button.setText(text);
                button.setEnabled(enabled);
            }
            if (child instanceof Container nested) updatePlayButtons(nested,
                    text, enabled);
        }
    }
    private void play() {
        if (playAction != null) playAction.run();
        else showNotice(t("game.engineNotConnected"));
    }
    @Override public void removeNotify() {
        finishTransition();
        super.removeNotify();
    }
    private final class SceneTravel extends JComponent {
        @Override protected void paintComponent(Graphics graphics) {
            if (!transitioning || outgoing == null || incoming == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(JourneyStyle.INK);
                g.fillRect(0, 0, getWidth(), getHeight());
                int page = Math.max(0, getHeight() - JourneyNavigation.HEIGHT);
                var frame = JourneyMotion.vertical(page, from == Screen.HOME ? page : 0,
                        screen == Screen.HOME ? page : 0, reverse, progress);
                g.drawImage(outgoing, 0, (from == Screen.HOME
                        ? 0 : JourneyNavigation.HEIGHT)
                        + (int) Math.round(frame.outgoingY()), null);
                g.drawImage(incoming, 0, (screen == Screen.HOME
                        ? 0 : JourneyNavigation.HEIGHT)
                        + (int) Math.round(frame.incomingY()), null);
            } finally { g.dispose(); }
        }
    }
}

package dev.leverage.ui;

import dev.leverage.i18n.I18n;
import static dev.leverage.i18n.I18n.t;

import com.formdev.flatlaf.FlatDarkLaf;
import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherPreferences;
import dev.leverage.client.LauncherProfileCompatibility;
import dev.leverage.client.LocalVersionCatalog;
import dev.leverage.client.LauncherSkinClient;
import dev.leverage.skin.DefaultSkin;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

// Main graphical entry point for connecting to a Leverage server and authenticating.
public final class LauncherWindow {
    private static final String SERVER = "server";
    private static final String LOGIN = "login";
    private static final String REGISTER_DETAILS = "register-details";
    private static final String REGISTER_USERNAME = "register-username";
    private static final String VERIFY_EMAIL = "verify-email";
    private static final String DOWNLOAD_SOURCE = "download-source";
    private static final String READY = "ready";
    private static final String HOME = "home";

    private static final Color TEXT = new Color(0x14242c);
    private static final Color MUTED = new Color(0x245046);
    private static final Color SUCCESS = new Color(35, 116, 39);
    private static final Color ERROR = new Color(174, 35, 35);
    private static final Color ERROR_BACKGROUND = new Color(0xD4, 0x44, 0x1A);
    private static final Color SUCCESS_BACKGROUND = new Color(0x36, 0xB0, 0x30);
    private static final Color LINK = new Color(0x24594a);
    private static final int SCREEN_WIDTH = 500;
    private static final int CONTENT_WIDTH = 428;
    private static final Dimension FIELD_SIZE = new Dimension(CONTENT_WIDTH, 48);
    private static final Dimension BUTTON_SIZE = new Dimension(CONTENT_WIDTH, 50);

    private final LauncherAuthClient client;
    private final LauncherPreferences preferences;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new AtlasSetupPanel.FormCards(cardLayout);
    private final Map<String, JPanel> screens = new LinkedHashMap<>();
    private final CardLayout rootLayout = new CardLayout();
    private final PixelSessionTransition root = new PixelSessionTransition(rootLayout);
    private final DesktopNotifications notifications = new DesktopNotifications(root);
    private boolean restoringStartup;
    private LauncherHomePanel home;
    private AtlasSetupPanel atlas;
    private LauncherAuthClient.Session currentSession;

    private URI serverHost;
    private String registrationEmail;
    private char[] registrationPassword;

    private JTextField serverUrl;
    private PixelButton connectButton;
    private PixelButton offlineButton;
    private PixelButton loginButton;
    private JLabel serverStatus;
    private JLabel loginStatus;
    private JLabel downloadStatus;
    private boolean editingDownloadSource;
    private long navigationVersion;
    private long skinRevision;
    private long profileRevision;
    private JTextField loginIdentifier;
    private JPasswordField loginPassword;
    private JCheckBox rememberDevice;
    private JTextField registerEmail;
    private JPasswordField registerPassword;
    private JPasswordField registerPasswordAgain;
    private JTextField registerUsername;
    private VerificationCodeInput verificationCode;
    private JLabel verificationDescription;
    private JTextField downloadSourceUrl;
    private PixelSwitch useDefaultSources;
    private JTextField javaSourceUrl;
    private PixelDropdown<String> versionsSourceChoice;
    private PixelDropdown<String> javaSourceChoice;
    private JLabel downloadSourceHint;
    private JLabel readyDescription;
    private JPanel downloadForm;

    private LauncherWindow() throws Exception {
        var minecraftDirectory = LocalVersionCatalog.defaultMinecraftDirectory();
        LauncherProfileCompatibility.ensure(minecraftDirectory);
        this.client = LauncherAuthClient.forMinecraftDirectory(minecraftDirectory);
        this.preferences = new LauncherPreferences(minecraftDirectory);
    }

    LauncherWindow(LauncherAuthClient client, LauncherPreferences preferences) {
        this.client = client;
        this.preferences = preferences;
    }

    public static void open() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "La interfaz gráfica no está disponible en un entorno headless.");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            createAndShow();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(LauncherWindow::createAndShow);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el inicio del launcher.",
                    exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("No se pudo abrir el launcher.",
                    exception.getCause());
        }
    }

    private static void createAndShow() {
        try {
            FlatDarkLaf.setup();
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Button.arc", 0);
            UIManager.put("Component.arc", 0);
            UIManager.put("ComboBox.arc", 0);
            UIManager.put("Popup.borderCornerRadius", 0);
            UIManager.put("PopupMenu.borderCornerRadius", 0);
            UIManager.put("TextComponent.arc", 0);

            var languagePreferences = new LauncherPreferences(
                    LocalVersionCatalog.defaultMinecraftDirectory());
            try {
                I18n.setLanguage(I18n.Language.fromTag(
                        languagePreferences.loadLanguage()));
            }
            catch (java.io.IOException ignored) {
                I18n.setLanguage(I18n.Language.SPANISH);
            }

            LauncherWindow window = new LauncherWindow();
            JFrame frame = new JFrame("Leverage");
            LauncherIcon.apply(frame);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(820, 560));
            frame.setSize(1040, 680);
            frame.setLocationRelativeTo(null);
            frame.setContentPane(window.createContent());
            I18n.attach(frame);
            frame.setVisible(true);
            SwingUtilities.invokeLater(window::restoreSavedSetup);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo preparar el almacenamiento local de cuentas.", exception);
        }
    }

    private JPanel createContent() {
        cards.setOpaque(false);
        addScreen(SERVER, createServerScreen());
        addScreen(LOGIN, createLoginScreen());
        addScreen(REGISTER_DETAILS, createRegisterDetailsScreen());
        addScreen(REGISTER_USERNAME, createRegisterUsernameScreen());
        addScreen(VERIFY_EMAIL, createVerificationScreen());
        addScreen(DOWNLOAD_SOURCE, createDownloadSourceScreen());
        addScreen(READY, createReadyScreen());
        home = new LauncherHomePanel(notifications::show);
        home.configureInstances(preferences);
        home.skins().onUse(this::uploadGallerySkin);
        home.users().addPropertyChangeListener("userSkinCleared", event -> {
            if (currentSession != null && currentSession.userId().equals(
                    event.getNewValue())) loadHomeSkin();
        });
        home.profile().onRefresh(this::loadHomeProfile);
        home.profile().onEdit(this::editHomeProfile);
        home.setAccountMenu(createAccountMenu());
        home.settings().setRememberDevice(rememberDevice.isSelected());
        home.settings().onRememberDevice(this::rememberDeviceChanged);
        home.settings().onDisconnect(this::disconnect);
        home.settings().onLanguageChange(language -> SwingUtilities.invokeLater(() -> {
            // Let the native dropdown close first. Keep the current screen and scroll
            // position intact.
            if (home.isVisible() && home.selectedScreen() ==
                    LauncherHomePanel.Screen.SETTINGS) root.transition(() -> {
                I18n.setLanguage(language);
                CompletableFuture.runAsync(() -> {
                    try {
                        preferences.saveLanguage(language.tag());
                    }
                    catch (java.io.IOException failure) {
                        SwingUtilities.invokeLater(() -> notifications.show(
                                I18n.t("settings.languageSaveError"), true));
                    } });
            });
        }));
        new GameWorkflow(home, preferences, () -> new GameWorkflow.Account(
                serverHost, currentSession));
        configureHomeServer();
        screens.put(HOME, home);
        cards.setPreferredSize(screens.get(SERVER).getPreferredSize());

        atlas = new AtlasSetupPanel(cards);
        JPanel startup = new JPanel(new GridBagLayout());
        startup.setName("startup-wait");
        startup.setBackground(JourneyStyle.INK);
        var loading = JourneyStyle.label(t("home.preparing"),
                22, JourneyStyle.PAPER, true);
        startup.add(loading);
        root.pages().add(startup, "startup");
        root.pages().add(atlas, "setup");
        root.pages().add(home, HOME);
        return root;
    }

    private JPanel createServerScreen() {
        JPanel screen = screen(t("auth.hero"), t("auth.serverIntro"), 430, 1);
        serverUrl = textField("http://127.0.0.1:8000");
        JLabel status = statusLabel(" ");
        PixelButton connect = primary(t("auth.connect"));
        PixelButton continueOffline = neutral(t("auth.offline"));
        connectButton = connect;
        offlineButton = continueOffline;
        serverStatus = status;
        resizeButton(connect, 250);
        resizeButton(continueOffline, 168);
        JPanel actions = buttonRow(connect, continueOffline);

        addField(screen, t("auth.serverUrl"), serverUrl);
        screen.add(Box.createVerticalStrut(18));
        screen.add(actions);
        screen.add(Box.createVerticalStrut(14));
        screen.add(status);

        connect.addActionListener(event -> connect(serverUrl.getText(), connect, status));
        continueOffline.addActionListener(event -> {
            serverHost = null;
            currentSession = null;
            home.setSkin(DefaultSkin.image(), false);
            readyDescription.setText(t("auth.offlineReady"));
            status.setText(" ");
            continueWithSavedSources();
        });
        serverUrl.addActionListener(event -> connect.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createLoginScreen() {
        JPanel screen = screen(t("auth.hero"), t("auth.loginIntro"), 550, 2);
        loginIdentifier = textField(t("auth.userOrEmail"));
        loginPassword = passwordField(t("auth.password"));
        rememberDevice = new PixelCheckBox(t("settings.remember"), true);
        try {
            rememberDevice.setSelected(preferences.loadRememberDevice());
        } catch (Exception ignored) {
            // Startup reports an unreadable preference file outside the container.
        }
        JPanel rememberRow = new JPanel();
        rememberRow.setLayout(new BoxLayout(rememberRow, BoxLayout.X_AXIS));
        rememberRow.setOpaque(false);
        rememberRow.setAlignmentX(0f);
        Dimension rememberSize = new Dimension(CONTENT_WIDTH,
                rememberDevice.getPreferredSize().height);
        rememberRow.setPreferredSize(rememberSize);
        rememberRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, rememberSize.height));
        rememberRow.add(Box.createHorizontalGlue());
        rememberRow.add(rememberDevice);
        rememberDevice.addActionListener(event -> {
            if (home != null) home.settings().setRememberDevice(
                    rememberDevice.isSelected());
        });
        JLabel status = statusLabel(" ");
        PixelButton login = primary(t("auth.signIn"));
        loginButton = login;
        loginStatus = status;

        addField(screen, t("auth.user"), loginIdentifier);
        screen.add(Box.createVerticalStrut(13));
        addField(screen, t("auth.password"), loginPassword);
        screen.add(Box.createVerticalStrut(7));
        screen.add(rememberRow);
        screen.add(Box.createVerticalStrut(14));
        screen.add(login);
        screen.add(Box.createVerticalStrut(12));
        screen.add(linkRow(t("auth.newHere"), t("auth.register"),
                () -> show(REGISTER_DETAILS, registerEmail)));
        screen.add(Box.createVerticalStrut(4));
        screen.add(link(t("auth.changeServer"), () -> show(SERVER, serverUrl)));
        screen.add(Box.createVerticalStrut(10));
        screen.add(status);

        login.addActionListener(event -> login(login, status));
        loginPassword.addActionListener(event -> login.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createRegisterDetailsScreen() {
        JPanel screen = screen(t("auth.createHeading"), t("auth.createIntro"), 520, 2);
        registerEmail = textField("correo@ejemplo.com");
        registerPassword = passwordField(t("auth.minPassword"));
        registerPasswordAgain = passwordField(t("auth.repeatPassword"));
        JLabel status = statusLabel(" ");
        PixelButton next = primary(t("auth.continue"));

        addField(screen, t("auth.email"), registerEmail);
        screen.add(Box.createVerticalStrut(11));
        addField(screen, t("auth.password"), registerPassword);
        screen.add(Box.createVerticalStrut(11));
        addField(screen, t("auth.repeatPasswordLabel"), registerPasswordAgain);
        screen.add(Box.createVerticalStrut(16));
        screen.add(next);
        screen.add(Box.createVerticalStrut(8));
        screen.add(link(t("auth.haveAccount"), () -> show(LOGIN, loginIdentifier)));
        screen.add(Box.createVerticalStrut(7));
        screen.add(status);

        next.addActionListener(event -> continueRegistration(status));
        registerPasswordAgain.addActionListener(event -> next.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createRegisterUsernameScreen() {
        JPanel screen = screen(t("auth.chooseName"), t("auth.nameIntro"), 450, 2);
        registerUsername = textField(t("auth.username"));
        JLabel hint = body(t("auth.nameHint"));
        JLabel status = statusLabel(" ");
        PixelButton submit = primary(t("auth.create"));

        addField(screen, t("auth.username"), registerUsername);
        screen.add(Box.createVerticalStrut(8));
        screen.add(hint);
        screen.add(Box.createVerticalStrut(20));
        screen.add(submit);
        screen.add(Box.createVerticalStrut(8));
        screen.add(link(t("common.back"), () -> show(REGISTER_DETAILS, registerEmail)));
        screen.add(Box.createVerticalStrut(10));
        screen.add(status);

        submit.addActionListener(event -> register(submit, status));
        registerUsername.addActionListener(event -> submit.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createVerificationScreen() {
        JPanel screen = screen(t("auth.verifyHeading"), t("auth.verifyIntro"), 500, 2);
        verificationDescription = body(t("auth.checkInbox"));
        verificationCode = new VerificationCodeInput();
        JLabel status = statusLabel(" ");
        PixelButton verify = primary(t("auth.verify"));
        PixelButton resend = neutral(t("auth.resend"));

        screen.add(verificationDescription);
        screen.add(Box.createVerticalStrut(18));
        addField(screen, t("auth.code"), verificationCode);
        screen.add(Box.createVerticalStrut(17));
        screen.add(verify);
        screen.add(Box.createVerticalStrut(10));
        screen.add(resend);
        screen.add(Box.createVerticalStrut(7));
        screen.add(link(t("auth.backLogin"), () -> show(LOGIN, loginIdentifier)));
        screen.add(Box.createVerticalStrut(10));
        screen.add(status);

        verify.addActionListener(event -> verifyEmail(verify, status));
        resend.addActionListener(event -> resendVerification(resend, status));
        verificationCode.addActionListener(event -> verify.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createDownloadSourceScreen() {
        JPanel screen = screen(t("auth.downloadHeading"),
                t("auth.downloadIntro"), 390, 3, 18, 18);
        downloadForm = screen;
        LauncherPreferences.DownloadSources saved;
        try {
            saved = preferences.loadDownloadSources();
        } catch (Exception exception) {
            saved = new LauncherPreferences.DownloadSources(
                    LauncherPreferences.DownloadSource.defaults(),
                    LauncherPreferences.DownloadSource.defaults());
        }
        downloadSourceUrl = textField("https://tu-servidor.example");
        javaSourceUrl = textField("https://java.example");
        if (saved.versions().baseUrl() != null) downloadSourceUrl.setText(
                saved.versions().baseUrl().toString());
        if (saved.javaRuntime().baseUrl() != null) javaSourceUrl.setText(
                saved.javaRuntime().baseUrl().toString());
        useDefaultSources = new PixelSwitch(saved.versions().useDefaults());
        versionsSourceChoice = new PixelDropdown<>(java.util.List.of(
                t("sources.default"), t("sources.custom")));
        javaSourceChoice = new PixelDropdown<>(java.util.List.of(t(
                "sources.default"), t("sources.custom")));
        versionsSourceChoice.setSelectedIndex(saved.versions().useDefaults() ? 0 : 1);
        javaSourceChoice.setSelectedIndex(saved.javaRuntime().useDefaults() ? 0 : 1);
        downloadSourceHint = body(t("sources.different"));
        JLabel status = statusLabel(" ");
        PixelButton save = primary(t("auth.saveContinue"));
        downloadStatus = status;

        addField(screen, "Minecraft · versiones", versionsSourceChoice);
        screen.add(Box.createVerticalStrut(6));
        screen.add(downloadSourceUrl);
        screen.add(Box.createVerticalStrut(14));
        addField(screen, "Java · runtime", javaSourceChoice);
        screen.add(Box.createVerticalStrut(6));
        screen.add(javaSourceUrl);
        screen.add(Box.createVerticalStrut(10));
        screen.add(downloadSourceHint);
        screen.add(Box.createVerticalStrut(18));
        screen.add(save);
        screen.add(Box.createVerticalStrut(8));
        screen.add(link("← Volver", () -> show(currentSession == null
                ? SERVER : LOGIN, null)));
        screen.add(Box.createVerticalStrut(11));
        screen.add(status);

        updateDownloadSourceState();
        versionsSourceChoice.addActionListener(event -> updateDownloadSourceState());
        javaSourceChoice.addActionListener(event -> updateDownloadSourceState());
        useDefaultSources.addActionListener(event -> {
            versionsSourceChoice.setSelectedIndex(useDefaultSources.isSelected() ? 0 : 1);
            updateDownloadSourceState();
        });
        save.addActionListener(event -> saveDownloadSource(status));
        downloadSourceUrl.addActionListener(event -> save.doClick());
        return withSystemNotifications(screen, status);
    }

    private JPanel createReadyScreen() {
        JPanel screen = screen(t("auth.readyHeading"), t("auth.readyIntro"), 350, 4);
        readyDescription = body(t("auth.readyNext"));
        PixelButton continueButton = primary(t("auth.continue"));
        screen.add(readyDescription);
        screen.add(Box.createVerticalStrut(24));
        screen.add(continueButton);
        continueButton.addActionListener(event -> show(HOME, null));
        return screen;
    }

    private void connect(String value, PixelButton button, JLabel status) {
        status.setText(" ");
        if (value == null || value.isBlank()) {
            error(status, t("auth.enterServer"));
            return;
        }
        URI requested;
        try {
            requested = LauncherPreferences.validateBaseUrl(value);
        } catch (RuntimeException exception) {
            error(status, t("error.urlValid"));
            return;
        }
        setConnectionBusy(true);
        status.setForeground(MUTED);
        status.setText(t("auth.recovering"));
        CompletableFuture.supplyAsync(() -> {
            try {
                var info = client.checkServer(requested);
                preferences.saveServerUrl(info.host());
                var account = client.rememberedAccount().filter(saved -> URI.create(
                        saved.host()).equals(info.host()));
                LauncherAuthClient.Session session = null;
                java.io.IOException resumeError = null;
                if (preferences.loadRememberDevice()) {
                    try {
                        session = client.resume(info.host()).orElse(null);
                        if (session != null && !client.isDeviceRemembered()) preferences
                                .saveRememberDevice(false);
                    } catch (java.io.IOException exception) {
                        // Keep credentials after a transient outage, but still allow a
                        // manual login.
                        resumeError = exception;
                    }
                }
                return new Connection(info, session, account.map(
                        saved -> saved.username()).orElse(""), resumeError);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(exception);
            }
        }).whenComplete((connection, failure) -> SwingUtilities.invokeLater(() -> {
            setConnectionBusy(false);
            if (failure != null) {
                if (restoringStartup) show(SERVER, serverUrl);
                error(status, friendlyMessage(rootCause(failure)));
                return;
            }
            serverHost = connection.server().host();
            home.serverSettings().setServerUrl(serverHost);
            currentSession = connection.session();
            status.setText(" ");
            loginPassword.setText("");
            loginIdentifier.setText(connection.username());
            if (currentSession != null) {
                continueWithSavedSources();
                showCredentialStorageWarning();
            } else {
                show(LOGIN, connection.username().isBlank()
                        ? loginIdentifier : loginPassword);
                if (handleAccountRestriction(connection.resumeError())) return;
                if (connection.resumeError() != null) {
                    error(loginStatus, t("auth.sessionRestoreError"));
                } else if (connection.username().isBlank()) {
                    success(loginStatus, t("auth.connectedVersion",
                            connection.server().version()));
                } else {
                    error(loginStatus, t("auth.savedSessionGone"));
                }
            }
        }));
    }

    // Called once after showing the window; network and OS keychain work stay off the UI
    // thread.
    void restoreSavedSetup() {
        restoringStartup = true;
        root.coverStartup();
        try {
            var savedHost = preferences.loadServerUrl();
            // Also recover accounts saved by older launchers that did not remember the
            // server URL.
            if (savedHost.isEmpty()) {
                savedHost = client.rememberedAccount().map(
                        account -> URI.create(account.host()));
            }
            if (savedHost.isPresent()) {
                serverUrl.setText(savedHost.get().toString());
                connect(serverUrl.getText(), connectButton, serverStatus);
            } else {
                show(SERVER, serverUrl);
            }
        } catch (Exception exception) {
            show(SERVER, serverUrl);
            error(serverStatus, friendlyMessage(exception));
        }
    }

    private void setConnectionBusy(boolean busy) {
        connectButton.setEnabled(!busy);
        offlineButton.setEnabled(!busy);
        serverUrl.setEnabled(!busy);
    }

    private void continueWithSavedSources() {
        editingDownloadSource = false;
        try {
            if (preferences.hasDownloadSource()) {
                preferences.loadDownloadSource();
                // Validate before skipping the configuration screen.
                show(HOME, null);
                return;
            }
        } catch (Exception exception) {
            show(DOWNLOAD_SOURCE, downloadSourceUrl);
            error(downloadStatus, friendlyMessage(exception));
            return;
        }
        show(DOWNLOAD_SOURCE, useDefaultSources.isSelected() ? null : downloadSourceUrl);
    }

    private JPopupMenu createAccountMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem server = new JMenuItem(t("auth.changeServer"));
        server.addActionListener(event -> {
            show(HOME, null);
            home.showSection(LauncherHomePanel.Section.SERVER);
        });
        JMenuItem sources = new JMenuItem(t("sources.menu"));
        sources.addActionListener(event -> {
            show(HOME, null);
            home.showSection(LauncherHomePanel.Section.SERVER);
        });
        JMenuItem forget = new JMenuItem(t("auth.forget"));
        forget.addActionListener(event -> {
            server.setEnabled(false);
            sources.setEnabled(false);
            forget.setEnabled(false);
            CompletableFuture.runAsync(() -> {
                try {
                    preferences.saveRememberDevice(false);
                    client.forgetDevice();
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) Thread
                            .currentThread().interrupt();
                    throw new CompletionException(exception);
                }
            }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                server.setEnabled(true);
                sources.setEnabled(true);
                forget.setEnabled(true);
                rememberDevice.setSelected(false);
                home.showNotice(failure == null ? t("auth.forgotten")
                        : t("error.forgetDetail", friendlyMessage(rootCause(failure))));
            }));
        });
        menu.add(server);
        menu.add(sources);
        menu.add(forget);
        return menu;
    }

    private void configureHomeServer() {
        ServerSettingsPanel panel = home.serverSettings();
        try {
            panel.load(preferences.loadServerUrl().orElse(null),
                    preferences.loadDownloadSources());
            panel.loadMemory(preferences.loadGameMemory());
        } catch (Exception exception) {
            home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
        }
        panel.onTest(this::testHomeServer);
        panel.onSaveMemory(() -> {
            try {
                preferences.saveGameMemory(panel.requestedMemory());
                home.showNotice(t("memory.saved"), SUCCESS_BACKGROUND);
            } catch (Exception exception) {
                home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
            }
        });
        panel.onSave(() -> {
            try {
                preferences.saveDownloadSources(panel.requestedSources());
                home.showNotice(t("sources.saved"), SUCCESS_BACKGROUND);
            } catch (Exception exception) {
                home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
            }
        });
    }

    private void rememberDeviceChanged(boolean selected) {
        try {
            preferences.saveRememberDevice(selected);
            rememberDevice.setSelected(selected);
        } catch (Exception exception) {
            home.settings().setRememberDevice(rememberDevice.isSelected());
            home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
        }
    }

    private void showCredentialStorageWarning() {
        client.takeCredentialWarning().ifPresent(warning -> {
            rememberDevice.setSelected(false);
            home.settings().setRememberDevice(false);
            notifications.show(warning, true);
        });
    }

    // End this local session only; never discard a remembered account belonging to
    // another host.
    private void disconnect() {
        var session = currentSession;
        URI host = serverHost;
        currentSession = null;
        skinRevision++;
        profileRevision++;
        home.profile().clearSecrets();
        loginPassword.setText("");
        registerPassword.setText("");
        registerPasswordAgain.setText("");
        verificationCode.clear();
        clearRegistrationPassword();
        home.setAccount("Jugador", false);
        home.setSkin(DefaultSkin.image(), false);
        home.serverSettings().setConnection(host, false);
        show(host == null ? SERVER : LOGIN, host == null ? serverUrl : loginIdentifier);
        loginButton.setEnabled(false);
        long requestedNavigation = navigationVersion;
        JLabel status = host == null ? serverStatus : loginStatus;
        status.setForeground(MUTED);
        status.setText(t("auth.signingOut"));
        CompletableFuture.runAsync(() -> {
            if (session != null && host != null) {
                try {
                    client.forgetLocalAccount(host, session);
                    client.logout(host, session);
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) Thread
                            .currentThread().interrupt();
                    throw new CompletionException(exception);
                }
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            loginButton.setEnabled(true);
            if (navigationVersion != requestedNavigation
                    || currentSession != null) return;
            if (failure == null) success(status, t("auth.signedOut"));
            else error(status, t("error.signOutDetail",
                    friendlyMessage(rootCause(failure))));
        }));
    }

    private void testHomeServer() {
        ServerSettingsPanel panel = home.serverSettings();
        URI requested;
        LauncherPreferences.DownloadSources sources;
        try {
            requested = panel.requestedServer();
            sources = panel.requestedSources();
        } catch (Exception exception) {
            home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
            return;
        }
        URI previousHost = serverHost;
        LauncherAuthClient.Session previousSession = currentSession;
        long navigation = navigationVersion;
        panel.setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try { return client.checkServer(requested); }
            catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(exception);
            }
        }).whenComplete((info, failure) -> SwingUtilities.invokeLater(() -> {
            panel.setBusy(false);
            // A late probe must never replace a session established elsewhere in the
            // meantime.
            if (navigationVersion != navigation || currentSession != previousSession
                    || !java.util.Objects.equals(serverHost, previousHost)) return;
            if (failure != null) {
                home.showNotice(t("error.connectDetail",
                        friendlyMessage(rootCause(failure))), ERROR_BACKGROUND);
                return;
            }
            try {
                preferences.saveServerConfiguration(info.host(), sources);
            } catch (Exception exception) {
                home.showNotice(friendlyMessage(exception), ERROR_BACKGROUND);
                return;
            }
            boolean changed = previousHost != null
                    && !LauncherPreferences.validateServerUrl(
                    previousHost.toString()).equals(requested);
            if (changed || currentSession == null) {
                // Deliberately require a fresh login; never forward/resume the previous
                // server's credential.
                serverHost = info.host();
                currentSession = null;
                home.setInstanceServer(serverHost);
                serverUrl.setText(serverHost.toString());
                panel.setServerUrl(serverHost);
                panel.setConnection(serverHost, false);
                home.setAccount("Jugador", false);
                home.setSkin(DefaultSkin.image(), false);
                loginIdentifier.setText("");
                loginPassword.setText("");
                show(LOGIN, loginIdentifier);
                success(loginStatus, changed ? t("auth.serverChanged")
                        : t("auth.connectedLogin"));
            } else {
                panel.setConnection(serverHost, true);
                home.showNotice(t("auth.connectionSaved"), SUCCESS_BACKGROUND);
            }
        }));
    }

    private record Connection(LauncherAuthClient.ServerInfo server,
            LauncherAuthClient.Session session, String username,
            java.io.IOException resumeError) { }

    private boolean handleAccountRestriction(Throwable failure) {
        if (!(failure instanceof LauncherAuthClient.AuthHttpException http)
                || !http.accountRestricted()) return false;
        ++profileRevision;
        ++skinRevision;
        currentSession = null;
        loginPassword.setText("");
        home.setUsersSession(serverHost, null);
        home.setAccount("Jugador", false);
        home.setSkin(DefaultSkin.image(), false);
        home.profile().setIdentity("Jugador", false);
        home.serverSettings().setConnection(serverHost, false);
        show(LOGIN, loginPassword);
        loginStatus.setText(" ");
        AccountRestrictionDialog.show(cards, http.code().equals("ACCOUNT_BANNED"),
                http.restrictionMessage());
        return true;
    }

    private void login(PixelButton button, JLabel status) {
        String identifier = loginIdentifier.getText().trim();
        char[] password = loginPassword.getPassword();
        if (identifier.isBlank() || password.length == 0) {
            Arrays.fill(password, '\0');
            error(status, t("auth.enterCredentials"));
            return;
        }
        String passwordValue = new String(password);
        Arrays.fill(password, '\0');
        URI requestedHost = serverHost;
        boolean remember = rememberDevice.isSelected();
        runAsync(button, status, t("auth.signingIn"), () -> {
                    var session = client.login(requestedHost, identifier, passwordValue,
                    System.getProperty("user.name", "Este dispositivo"), remember);
                    preferences.saveRememberDevice(client.isDeviceRemembered());
                    return session;
        }, session -> {
                    currentSession = session;
                    loginPassword.setText("");
                    readyDescription.setText(t("auth.welcome", session.username()));
                    continueWithSavedSources();
                    showCredentialStorageWarning();
        });
    }

    private void continueRegistration(JLabel status) {
        String email = registerEmail.getText().trim();
        char[] first = registerPassword.getPassword();
        char[] second = registerPasswordAgain.getPassword();
        try {
            if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                    || email.length() > 254) {
                error(status, t("error.emailValid"));
                return;
            }
            int length = Character.codePointCount(first, 0, first.length);
            if (length < 8 || length > 128) {
                error(status, t("error.passwordLength"));
                return;
            }
            if (!Arrays.equals(first, second)) {
                error(status, t("error.passwordMismatch"));
                return;
            }
            clearRegistrationPassword();
            registrationEmail = email;
            registrationPassword = Arrays.copyOf(first, first.length);
            status.setText(" ");
            show(REGISTER_USERNAME, registerUsername);
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
        }
    }

    private void register(PixelButton button, JLabel status) {
        String username = registerUsername.getText().trim();
        if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            error(status, t("error.usernameHint"));
            return;
        }
        if (registrationEmail == null || registrationPassword == null) {
            error(status, t("auth.completeDetails"));
            return;
        }
        String passwordValue = new String(registrationPassword);
        runAsync(button, status, t("auth.registering"), () -> client.register(serverHost,
                registrationEmail, passwordValue, username), registration -> {
                    clearRegistrationPassword();
                    registerPassword.setText("");
                    registerPasswordAgain.setText("");
                    verificationDescription.setText(t("auth.emailSent",
                    registration.email()));
                    verificationCode.clear();
                    show(VERIFY_EMAIL, verificationCode.firstField());
        });
    }

    private void verifyEmail(PixelButton button, JLabel status) {
        String code = verificationCode.getCode();
        if (code.length() != 6) {
            error(status, t("auth.enterCode"));
            return;
        }
        if (registrationEmail == null) {
            error(status, t("auth.returnRegister"));
            return;
        }
        runAsync(button, status, "Verificando…", () -> client.confirmEmail(serverHost,
                registrationEmail, code), account -> {
            verificationCode.clear();
            loginIdentifier.setText(account.username());
            atlas.registrationVerified();
            success(status, t("auth.verified"));
            long requestedNavigation = navigationVersion;
            Timer timer = new Timer(650, event -> {
                if (navigationVersion == requestedNavigation) show(LOGIN, loginPassword);
            });
            timer.setRepeats(false);
            timer.start();
        });
    }

    private void resendVerification(PixelButton button, JLabel status) {
        if (registrationEmail == null) {
            error(status, t("auth.noRegistrationEmail"));
            return;
        }
        runAsync(button, status, "Enviando…", () -> {
                    client.requestEmailVerification(serverHost, registrationEmail);
                    return null;
        }, ignored -> success(status, t("auth.resent")));
    }

    private void updateDownloadSourceState() {
        boolean external = versionsSourceChoice.getSelectedIndex() == 1;
        useDefaultSources.setSelected(!external);
        downloadSourceUrl.setEnabled(external);
        downloadSourceUrl.setVisible(external);
        javaSourceUrl.setEnabled(javaSourceChoice.getSelectedIndex() == 1);
        javaSourceUrl.setVisible(javaSourceChoice.getSelectedIndex() == 1);
        if (downloadForm != null) downloadForm.setPreferredSize(
                new Dimension(SCREEN_WIDTH, 530 + (external ? 54 : 0)
                + (javaSourceUrl.isVisible() ? 54 : 0)));
        cards.revalidate();
        cards.repaint();
    }

    private void saveDownloadSource(JLabel status) {
        status.setText(" ");
        try {
            var versions = versionsSourceChoice.getSelectedIndex() == 0
                    ? LauncherPreferences.DownloadSource.defaults()
                    : LauncherPreferences.DownloadSource.external(
                    LauncherPreferences.validateBaseUrl(downloadSourceUrl.getText()));
            var javaRuntime = javaSourceChoice.getSelectedIndex() == 0
                    ? LauncherPreferences.DownloadSource.defaults()
                    : LauncherPreferences.DownloadSource.external(
                    LauncherPreferences.validateBaseUrl(javaSourceUrl.getText()));
            preferences.saveDownloadSources(new LauncherPreferences.DownloadSources(
                    versions, javaRuntime));
            home.serverSettings().load(serverHost,
                    new LauncherPreferences.DownloadSources(versions, javaRuntime));
            success(status, t("sources.saved"));
            long requestedNavigation = navigationVersion;
            Timer timer = new Timer(350, event -> {
                if (requestedNavigation == navigationVersion) show(HOME, null);
            });
            timer.setRepeats(false);
            timer.start();
        } catch (Exception exception) {
            error(status, friendlyMessage(exception));
        }
    }

    private<T> void runAsync(PixelButton button, JLabel status, String progress,
            CheckedSupplier<T> operation, Consumer<T> onSuccess) {
        button.setEnabled(false);
        long requestedNavigation = navigationVersion;
        status.setForeground(MUTED);
        status.setText(progress);
        CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((result, throwable) -> SwingUtilities.invokeLater(() -> {
            button.setEnabled(true);
            if (navigationVersion != requestedNavigation) return;
            if (throwable == null) {
                onSuccess.accept(result);
            } else {
                Throwable cause = rootCause(throwable);
                if (!handleAccountRestriction(cause)) error(status,
                        friendlyMessage(cause));
            }
        }));
    }

    private void show(String name, JTextField focus) {
        navigationVersion++;
        Runnable change;
        if (HOME.equals(name)) {
            boolean entering = !home.isVisible();
            change = () -> {
                rootLayout.show(root.pages(), HOME);
                if (entering) {
                    home.showHome();
                    loadHomeSkin();
                }
            };
            if (restoringStartup) {
                restoringStartup = false;
                root.revealStartup(change);
            }
            else if (entering) root.transition(change);
            else root.showImmediately(change);
            return;
        }
        change = () -> {
            rootLayout.show(root.pages(), "setup");
            if (atlas != null) atlas.showPhase(name, true);
            JPanel screen = screens.get(name);
            if (screen != null) cards.setPreferredSize(screen.getPreferredSize());
            cardLayout.show(cards, name);
            cards.revalidate();
            cards.repaint();
            if (focus != null) SwingUtilities.invokeLater(focus::requestFocusInWindow);
        };
        if (restoringStartup) {
            restoringStartup = false;
            root.revealStartup(change);
        }
        else root.showImmediately(change);
    }

    private void loadHomeSkin() {
        long requestedRevision =++skinRevision;
        home.showNotice("");
        home.setInstanceServer(serverHost);
        home.serverSettings().setConnection(serverHost, currentSession != null);
        home.setAccount(currentSession == null ? "Jugador" : currentSession.username(),
                currentSession != null && serverHost != null);
        home.setUsersSession(serverHost, currentSession);
        home.setSkin(DefaultSkin.image(), false);
        home.skins().configure(preferences, serverHost,
                currentSession == null ? null : currentSession.userId());
        loadHomeProfile();
        if (serverHost == null || currentSession == null) return;
        URI requestedHost = serverHost;
        LauncherAuthClient.Session session = currentSession;
        CompletableFuture.supplyAsync(() -> {
            try {
                return new LauncherSkinClient().load(requestedHost,
                        session.accessToken());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(exception);
            }
        }).whenComplete((skin, failure) -> SwingUtilities.invokeLater(() -> {
            // A late response must never replace the skin of a different account/server.
            if (!sameSkinAccount(requestedHost, session)
                    || requestedRevision != skinRevision) return;
            if (failure == null) home.setSkin(skin.image(), skin.slim());
            else home.showNotice(t("skins.loadError"));
        }));
    }

    private CompletableFuture<Void> loadHomeProfile() {
        long revision =++profileRevision;
        if (serverHost == null || currentSession == null) {
            home.profile().setIdentity("Jugador", false);
            return CompletableFuture.completedFuture(null);
        }
        URI host = serverHost;
        var session = currentSession;
        home.profile().setBusy(true);
        var completion = new CompletableFuture<Void>();
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.profile(host, session);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(failure);
            }
        }).whenComplete((profile, failure) -> SwingUtilities.invokeLater(() -> {
            if (revision != profileRevision || currentSession != session
                    || !host.equals(serverHost)) {
                completion.complete(null);
                return;
            }
            home.profile().setBusy(false);
            if (failure != null) {
                var cause = rootCause(failure);
                if (cause instanceof LauncherAuthClient.AuthHttpException http
                        && http.status() == 401) requireProfileLogin(
                        t("auth.sessionExpired"), false);
                else home.showNotice(t("error.profileLoad",
                        friendlyMessage(cause)), ERROR_BACKGROUND);
            } else {
                home.profile().load(profile);
                if (!profile.role().equals(session.role())) {
                    currentSession = session.withRole(profile.role());
                    home.setUsersSession(host, currentSession);
                }
            }
            completion.complete(null);
        }));
        return completion;
    }

    private void editHomeProfile(ProfileSettingsPanel.Edit edit) {
        if (serverHost == null || currentSession == null) {
            edit.clear();
            home.showNotice(t("profile.editSignIn"), ERROR_BACKGROUND);
            return;
        }
        URI host = serverHost;
        var session = currentSession;
        long revision =++profileRevision;
        home.profile().setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                LauncherAuthClient.AccountProfile profile = null;
                String warning = null;
                switch (edit.kind()) {
                    case USERNAME -> {
                        profile = client.changeUsername(host, session, edit.value(),
                                new String(edit.currentPassword()));
                        try {
                            client.updateRememberedUsername(host, profile);
                        } catch (java.io.IOException failure) {
                            warning = t("profile.localNameWarning");
                        }
                    }
                    case EMAIL -> client.changeEmail(host, session, edit.value(),
                            new String(edit.currentPassword()));
                    case PASSWORD -> {
                        client.changePassword(host, session, new String(
                                edit.currentPassword()), new String(edit.newPassword()));
                        try {
                            client.forgetLocalAccount(host, session);
                        } catch (java.io.IOException failure) {
                            warning = t("profile.localSessionWarning");
                        }
                    }
                }
                return new ProfileChange(profile, warning);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(failure);
            }
            finally { edit.clear(); }
        }).whenComplete((changed, failure) -> SwingUtilities.invokeLater(() -> {
            if (revision != profileRevision || currentSession != session
                    || !host.equals(serverHost)) return;
            home.profile().setBusy(false);
            if (failure != null) {
                var cause = rootCause(failure);
                if (cause instanceof LauncherAuthClient.AuthHttpException http
                        && http.status() == 401) requireProfileLogin(
                        t("auth.sessionExpired"), false);
                else home.showNotice(friendlyMessage(cause), ERROR_BACKGROUND);
                return;
            }
            if (changed.warning() != null) home.showNotice(changed.warning(),
                    ERROR_BACKGROUND);
            switch (edit.kind()) {
                case USERNAME -> {
                    currentSession = session.withUsername(changed.profile().username());
                    loadHomeSkin();
                    home.showNotice(t("profile.nameChanged"), SUCCESS_BACKGROUND);
                }
                case EMAIL -> {
                    home.showNotice(t("profile.emailPending"), SUCCESS_BACKGROUND);
                    loadHomeProfile();
                    // Keep showing the server's current email until confirmation
                    // succeeds.
                }
                case PASSWORD -> {
                    requireProfileLogin(t("profile.passwordChanged"), true);
                }
            }
        }));
    }

    private void requireProfileLogin(String message, boolean passwordChanged) {
        String username = currentSession == null ? "" : currentSession.username();
        home.showNotice(message, passwordChanged ? SUCCESS_BACKGROUND : ERROR_BACKGROUND);
        ++profileRevision;
        ++skinRevision;
        currentSession = null;
        if (passwordChanged) {
            rememberDevice.setSelected(false);
            try {
                preferences.saveRememberDevice(false);
            } catch (java.io.IOException ignored) {
                home.showNotice(t("profile.rememberError"), ERROR_BACKGROUND);
            }
        }
        home.setAccount("Jugador", false);
        home.setSkin(DefaultSkin.image(), false);
        home.serverSettings().setConnection(serverHost, false);
        loginIdentifier.setText(username);
        loginPassword.setText("");
        show(LOGIN, loginPassword);
        if (passwordChanged) success(loginStatus, message);
        else error(loginStatus, message);
    }

    private record ProfileChange(LauncherAuthClient.AccountProfile profile,
            String warning) { }

    // A refreshed role does not change which account owns the pending texture.
    private boolean sameSkinAccount(URI host, LauncherAuthClient.Session session) {
        return currentSession != null && host.equals(serverHost)
                && session.userId().equals(currentSession.userId())
                && session.accessToken().equals(currentSession.accessToken());
    }

    private CompletableFuture<Void> uploadGallerySkin(
            dev.leverage.client.LocalSkinLibrary.Entry entry) {
        long requestedRevision =++skinRevision;
        if (serverHost == null) return CompletableFuture.completedFuture(null);
        if (currentSession == null) return CompletableFuture.failedFuture(
                new IllegalStateException(t("skins.signIn")));
        URI requestedHost = serverHost;
        LauncherAuthClient.Session session = currentSession;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                new LauncherSkinClient().upload(requestedHost,
                        session.accessToken(), entry.image(), entry.slim());
            }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread
                        .currentThread().interrupt();
                throw new CompletionException(failure);
            }
        }).whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (requestedRevision != skinRevision
                    || !sameSkinAccount(requestedHost, session)) {
                completion.completeExceptionally(new IllegalStateException(
                        t("profile.requestChanged")));
                return;
            }
            if (failure != null) completion.completeExceptionally(rootCause(failure));
            else completion.complete(null);
        }));
        return completion;
    }

    private void addScreen(String name, JPanel screen) {
        screens.put(name, screen);
        cards.add(screen, name);
    }

    private static JPanel screen(String titleText,
            String subtitleText, int height, int step) {
        return screen(titleText, subtitleText, height, step, 24, 28);
    }

    private static JPanel screen(String titleText, String subtitleText,
            int height, int step, int topPadding, int bottomPadding) {
        JPanel screen = new JPanel();
        screen.setAlignmentX(0f);
        screen.setLayout(new BoxLayout(screen, BoxLayout.Y_AXIS));
        screen.setOpaque(true);
        screen.setBackground(AtlasSetupPanel.MINT);
        screen.setPreferredSize(new Dimension(SCREEN_WIDTH, height));
        screen.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        screen.setBorder(BorderFactory.createEmptyBorder(30, 28, 24, 28));

        JComponent title = new AtlasTypography(I18n.transform(titleText,
                value -> value.replace("<br>", "\n")), LauncherFonts.minecraftTen(
                titleText.contains("<br>") ? 44f : 29f), TEXT, true);
        title.setAlignmentX(0f);
        JComponent subtitle = new AtlasTypography(subtitleText,
                LauncherFonts.minecraftSeven(13f), MUTED, false);
        subtitle.setAlignmentX(0f);

        screen.add(title);
        screen.add(Box.createVerticalStrut(9));
        screen.add(subtitle);
        screen.add(Box.createVerticalStrut(22));
        return screen;
    }

    private JPanel withSystemNotifications(JPanel content, JLabel status) {
        // Keep the existing status model/listeners, but remove its row and trailing gap.
        int row = content.getComponentZOrder(status);
        if (row >= 0) {
            content.remove(row);
            if (row > 0 && content.getComponent(row
                    - 1) instanceof Box.Filler) content.remove(row - 1);
        }
        status.setVisible(false);
        status.addPropertyChangeListener("text", event -> {
            boolean error = ERROR.equals(status.getForeground());
            // Progress messages stay silent; only completed results and validation errors
            // notify.
            if (error || SUCCESS.equals(status.getForeground())) notifications.show(
                    status.getText(), error);
        });
        return content;
    }

    private static void addField(JPanel screen, String labelText, JComponent field) {
        field.setAlignmentX(0f);
        JLabel label = new JLabel(labelText);
        label.setLabelFor(field);
        label.setAlignmentX(0f);
        label.setPreferredSize(new Dimension(FIELD_SIZE.width, 20));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        label.setForeground(TEXT);
        label.setFont(LauncherFonts.minecraftSeven(14f));
        screen.add(label);
        screen.add(Box.createVerticalStrut(6));
        screen.add(field);
    }

    private static JTextField textField(String placeholder) {
        JTextField field = new JTextField();
        styleField(field, placeholder);
        return field;
    }

    private static JPasswordField passwordField(String placeholder) {
        JPasswordField field = new JPasswordField();
        styleField(field, placeholder);
        return field;
    }

    private static void styleField(JTextField field, String placeholder) {
        field.putClientProperty("JTextField.placeholderText", placeholder);
        PixelInputStyle.apply(field);
        field.setAlignmentX(0f);
        field.setPreferredSize(FIELD_SIZE);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_SIZE.height));
        field.setMinimumSize(new Dimension(100, FIELD_SIZE.height));
    }

    private static PixelButton primary(String text) {
        return button(text, PixelButton.Variant.PRIMARY);
    }

    private static PixelButton neutral(String text) {
        return button(text, PixelButton.Variant.NEUTRAL);
    }

    private static PixelButton button(String text, PixelButton.Variant variant) {
        PixelButton button = new PixelButton(text, variant);
        button.setAlignmentX(0f);
        resizeButton(button, BUTTON_SIZE.width);
        return button;
    }

    private static void resizeButton(PixelButton button, int width) {
        Dimension size = new Dimension(width, BUTTON_SIZE.height);
        button.setPreferredSize(size);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
        button.setMinimumSize(new Dimension(80, size.height));
    }

    private static JPanel buttonRow(PixelButton first, PixelButton second) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.setPreferredSize(BUTTON_SIZE);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_SIZE.height));
        row.add(first);
        row.add(Box.createHorizontalStrut(10));
        row.add(second);
        return row;
    }

    private static JLabel body(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setAlignmentX(0f);
        label.setForeground(MUTED);
        label.setFont(LauncherFonts.minecraftSeven(14f));
        return label;
    }

    private static JLabel statusLabel(String text) {
        JLabel label = body(text);
        label.setPreferredSize(new Dimension(FIELD_SIZE.width, 22));
        label.setMaximumSize(new Dimension(FIELD_SIZE.width, 22));
        return label;
    }

    private static JPanel linkRow(String text, String actionText, Runnable action) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.add(body(text));
        row.add(link(actionText, action));
        return row;
    }

    private static JButton link(String text, Runnable action) {
        boolean back = text.startsWith("← ");
        JButton button = new JButton(back ? text.substring(2) : text);
        if (back) button.setIcon(new javax.swing.Icon() {
            public int getIconWidth() { return 16; }
            public int getIconHeight() { return 16; }
            public void paintIcon(java.awt.Component component,
                    Graphics graphics, int x, int y) {
                graphics.setColor(component.getForeground());
                graphics.fillRect(x + 2, y + 7, 13, 2);
                for (int i = 0; i < 4; i++) {
                    graphics.fillRect(x + 2 + i * 2, y + 7 - i * 2, 2, 2);
                    graphics.fillRect(x + 2 + i * 2, y + 7 + i * 2, 2, 2);
                }
            }
        });
        button.setAlignmentX(0f);
        button.setFont(LauncherFonts.minecraftSeven(14f).deriveFont(Font.BOLD));
        button.setForeground(LINK);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(event -> action.run());
        return button;
    }

    // Paint loaded pixel fonts directly; Swing's HTML renderer substitutes unknown font
    // families.
    private static final class AtlasTypography extends JComponent {
        private final String text;
        private final boolean heading;
        AtlasTypography(String text, Font font, Color color, boolean heading) {
            this.text = text;
            this.heading = heading;
            setFont(font);
            setForeground(color);
            int line = (int) Math.ceil(font.getSize2D() * (heading ? 1.18 : 1.5));
            int height = line * (heading ? text.split("\n", -1).length : 2);
            setPreferredSize(new Dimension(CONTENT_WIDTH, height));
            setMinimumSize(new Dimension(1, height));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            accessibleContext = new AccessibleJComponent() { };
            accessibleContext.setAccessibleName(I18n.transform(text,
                    value -> value.replace('\n', ' ')));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(LauncherFonts.forLocale(getFont()));
                g.setColor(getForeground());
                int line = (int) Math.ceil(getFont().getSize2D() * (heading
                        ? 1.18 : 1.5)), y = g.getFontMetrics().getAscent();
                java.util.List<String> lines = JourneyStyle.wrap(g.getFontMetrics(),
                        text, getWidth());
                for (String value : lines) {
                    g.drawString(I18n.resolve(value), 0, y);
                    y += line;
                }
            } finally { g.dispose(); }
        }
    }

    private static void success(JLabel status, String message) {
        status.setForeground(SUCCESS);
        status.setText(message);
    }

    private static void error(JLabel status, String message) {
        status.setForeground(ERROR);
        status.setText(message);
    }

    private static String friendlyMessage(Throwable throwable) {
        if (throwable instanceof LauncherAuthClient.AuthHttpException http) {
            return switch (http.code()) {
                case "INVALID_CREDENTIALS" -> t("error.invalidCredentials");
                case "EMAIL_NOT_VERIFIED", "ACCOUNT_PENDING" -> t("error.verifyFirst");
                case "ACCOUNT_SUSPENDED" -> t("error.suspended");
                case "ACCOUNT_BANNED" -> t("error.banned");
                case "ACCOUNT_EXISTS" -> t("error.accountExists");
                case "USERNAME_EXISTS" -> t("error.usernameExists");
                case "EMAIL_EXISTS" -> t("error.emailExists");
                case "EMAIL_UNCHANGED" -> t("error.emailUnchanged");
                case "CURRENT_PASSWORD_INCORRECT" -> t("error.currentPassword");
                case "INVALID_PASSWORD" -> t("error.invalidPassword");
                case "INVALID_USERNAME" -> t("error.usernameFormat");
                case "INVALID_EMAIL" -> t("error.emailFormat");
                case "INVALID_SESSION" -> t("auth.sessionExpired");
                case "REGISTRATION_DISABLED" -> t("error.registrationDisabled");
                case "INVALID_TOKEN" -> t("error.invalidToken");
                case "RATE_LIMITED" -> t("error.rateLimited");
                default -> t("error.serverRequest", http.code());
            };
        }
        if (throwable instanceof IllegalArgumentException) {
            return throwable.getMessage();
        }
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            return throwable.getMessage();
        }
        return t("error.connection");
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void clearRegistrationPassword() {
        if (registrationPassword != null) {
            Arrays.fill(registrationPassword, '\0');
            registrationPassword = null;
        }
    }

    @FunctionalInterface private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}

package dev.leverage.ui;

import dev.leverage.client.LauncherAuthClient;
import dev.leverage.client.LauncherPreferences;
import dev.leverage.client.LocalAccountStore;
import dev.leverage.client.RefreshTokenStore;
import dev.leverage.client.RememberedAccountManager;
import dev.leverage.db.Database;
import dev.leverage.http.ApiServer;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

// Real HTTP server and persistent account metadata, without opening a desktop window.
class LauncherStartupTest {
    @TempDir Path directory;
    private ApiServer server;
    private URI host;
    private AuthService auth;
    private LauncherPreferences preferences;
    private LocalAccountStore accounts;
    private MemoryTokens tokens;
    private NoMail mailer;

    @BeforeEach
    void prepare() throws Exception {
        Database database = new Database(directory.resolve("server.db"));
        database.initialize();
        mailer=new NoMail();
        auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(60),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("SavedUser", "saved@example.test",
                "saved-user-password");
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        host = URI.create("http://127.0.0.1:" + server.port());
        preferences = new LauncherPreferences(directory.resolve("minecraft"));
        accounts = new LocalAccountStore(directory.resolve("minecraft"));
        tokens = new MemoryTokens();
    }

    @AfterEach
    void closeServer() {
        if (server != null) server.close();
    }

    @Test
    void rememberedLoginAutomaticallyPreparesModsWithoutChoosingAFolder()
            throws Exception {
        Path version = java.nio.file.Files.createDirectories(
                directory.resolve("minecraft/versions/26.3"));
        java.nio.file.Files.writeString(version.resolve("26.3.json"),
                "{\"id\":\"26.3\"}");
        java.nio.file.Files.write(version.resolve("26.3.jar"), new byte[] { 0 });
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        Path mods = new dev.leverage.client.InstanceLayout(directory.resolve("minecraft"))
                .managedInstance(host, "26.3").resolve("mods");
        waitUntil(() -> mods.equals(field(window, "home",
                LauncherHomePanel.class).mods().directory()));
        assertTrue(java.nio.file.Files.isDirectory(mods));
        assertTrue(onUi(() -> visible(window, "home")));
    }

    @Test
    void firstLaunchStillShowsServerAndSourcesAreNotAssumedConfigured() throws Exception {
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "server")));
        assertEquals("", onUi(() -> field(window, "serverUrl",
                JTextField.class).getText()));
        assertFalse(preferences.hasDownloadSource());
    }

    @Test
    void reopensDirectlyAtHomeWithRotatedSessionAndSavedDefaultSources()
            throws Exception {
        var first = client().login(host, "SavedUser",
                "saved-user-password", "Test", true);
        String previousToken = tokens.values.values().iterator().next();
        // No server preference yet: migrate the URL from an account saved by an older
        // launcher.
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "home")));
        var resumed = onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class));
        assertEquals(first.userId(), resumed.userId());
        assertNotEquals(first.accessToken(), resumed.accessToken());
        assertNotEquals(previousToken, tokens.values.values().iterator().next());
        assertEquals(host, preferences.loadServerUrl().orElseThrow());
        assertEquals("SavedUser · Online", onUi(() -> find(field(window,
                "home", LauncherHomePanel.class),
                PlayerAccountCard.class).getAccessibleContext().getAccessibleName()));
    }

    @Test
    void rememberedLoginStillAsksForSourcesIfTheyWereNeverConfigured() throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "download-source")));
        assertNotNull(onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class)));
    }

    @Test
    void withoutRememberingKeepsTheServerAndSkipsSourcesAfterManualLogin()
            throws Exception {
        preferences.saveServerUrl(host);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.external(
                URI.create("https://mirror.example.test")));
        preferences.saveRememberDevice(false);
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "login")));
        assertFalse(onUi(() -> field(window, "rememberDevice",
                JCheckBox.class).isSelected()));
        assertEquals("https://mirror.example.test", onUi(() -> field(window,
                "downloadSourceUrl", JTextField.class).getText()));
        onUi(() -> {
            field(window, "loginIdentifier", JTextField.class).setText("SavedUser");
            field(window, "loginPassword", JPasswordField.class).setText(
                    "saved-user-password");
            find(screen(window, "login"), PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(() -> visible(window, "home"));
        waitUntil(() -> "saved@example.test".equals(named(field(window,
                "home", LauncherHomePanel.class).profile(),
                "profile-email", JTextField.class).getText()));
        assertTrue(accounts.active().isEmpty());
        assertTrue(tokens.values.isEmpty());
    }

    @Test
    void revokedSessionReturnsToLoginWithoutDiscardingDownloadSettings()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        auth.revokeDeviceByRefreshToken(tokens.values.values().iterator().next());
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "login")));
        assertEquals("SavedUser", onUi(() -> field(window,
                "loginIdentifier", JTextField.class).getText()));
        assertTrue(onUi(() -> field(window, "loginStatus",
                JLabel.class).getText().contains("sesión guardada")));
        assertTrue(accounts.active().isEmpty());
        assertTrue(preferences.hasDownloadSource());
    }

    @Test
    void outageKeepsTheSessionAndAllowsOfflineWithoutRepeatingSources() throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveServerUrl(host);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        server.close();
        server = null;
        LauncherWindow window = open();
        waitForConnection(window);
        assertTrue(onUi(() -> visible(window, "server")));
        assertEquals(host.toString(), onUi(() -> field(window,
                "serverUrl", JTextField.class).getText()));
        assertTrue(accounts.active().isPresent());
        assertFalse(tokens.values.isEmpty());
        onUi(() -> {
            field(window, "offlineButton", PixelButton.class).doClick(0);
            return null;
        });
        assertTrue(onUi(() -> visible(window, "home")));
        assertNull(onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class)));
    }

    @Test
    void changingToAnotherServerNeverResumesTheOriginalServersAccount() throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        String previousToken = tokens.values.values().iterator().next();
        try (ApiServer other = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC())) {
            other.start();
            URI otherHost = URI.create("http://127.0.0.1:" + other.port());
            preferences.saveServerUrl(otherHost);
            preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
            LauncherWindow window = open();
            waitForConnection(window);
            assertTrue(onUi(() -> visible(window, "login")));
            assertNull(onUi(() -> field(window, "currentSession",
                    LauncherAuthClient.Session.class)));
            assertEquals(previousToken, tokens.values.values().iterator().next());
            assertEquals(otherHost, preferences.loadServerUrl().orElseThrow());
        }
    }

    @Test
    void accountMenuCanEditSourcesAndForgetTheDeviceAfterAutomaticLogin()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        PlayerAccountCard card = onUi(() -> find(field(window, "home",
                LauncherHomePanel.class), PlayerAccountCard.class));
        onUi(() -> {
            LauncherHomePanel home = field(window, "home", LauncherHomePanel.class);
            home.showSection(LauncherHomePanel.Section.SETTINGS);
            var sections = find(home, javax.swing.JScrollPane.class);
            find((java.awt.Container) sections.getViewport().getView(),
                    PixelButton.class).doClick(0);
            return null;
        });
        assertTrue(onUi(() -> visible(window, "home")));
        onUi(() -> {
            var panel = field(window, "home", LauncherHomePanel.class).serverSettings();
            panel.load(host, new LauncherPreferences.DownloadSources(
                    LauncherPreferences.DownloadSource.external(URI.create(
                    "https://new-mirror.example.test")),
                    LauncherPreferences.DownloadSource.defaults()));
            named(panel, "save-download-sources", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(() -> visible(window, "home"));
        assertEquals(URI.create("https://new-mirror.example.test"),
                preferences.loadDownloadSource().baseUrl());
        String token = tokens.values.values().iterator().next();
        onUi(() -> {
            ((JMenuItem) card.getComponentPopupMenu().getComponent(2)).doClick(0);
            return null;
        });
        waitUntil(() -> ((JMenuItem) card.getComponentPopupMenu().getComponent(
                2)).isEnabled());
        assertFalse(preferences.loadRememberDevice());
        assertTrue(accounts.active().isEmpty());
        assertTrue(tokens.values.isEmpty());
        assertThrows(dev.leverage.service.ServiceException.class,
                () -> auth.refresh(token));
        var current = onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class));
        assertEquals("SavedUser", auth.authenticate(
                current.accessToken()).user().username());
        onUi(() -> {
            ((JMenuItem) card.getComponentPopupMenu().getComponent(0)).doClick(0);
            return null;
        });
        assertTrue(onUi(() -> visible(window, "home")));
        assertEquals(LauncherHomePanel.Section.SERVER, onUi(() -> field(window,
                "home", LauncherHomePanel.class).selectedSection()));
    }

    @Test
    void probingAnotherServerRequiresLoginAndDoesNotSendOrResumeThePreviousCredential()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        String remembered = tokens.values.values().iterator().next();
        try (ApiServer other = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC())) {
            other.start();
            URI target = URI.create("http://127.0.0.1:" + other.port());
            var panel = onUi(() -> field(window, "home",
                    LauncherHomePanel.class).serverSettings());
            onUi(() -> {
                field(window, "home", LauncherHomePanel.class).showSection(
                        LauncherHomePanel.Section.SERVER);
                panel.load(target, new LauncherPreferences.DownloadSources(
                        LauncherPreferences.DownloadSource.external(
                        URI.create("https://versions.example.test")),
                        LauncherPreferences.DownloadSource.external(
                        URI.create("https://java.example.test"))));
                named(panel, "test-server-connection", PixelButton.class).doClick(0);
                return null;
            });
            waitUntil(() -> named(panel, "test-server-connection",
                    PixelButton.class).isEnabled());
            assertTrue(onUi(() -> visible(window, "login")));
            assertNull(onUi(() -> field(window, "currentSession",
                    LauncherAuthClient.Session.class)));
            assertTrue(onUi(() -> field(window, "loginStatus",
                    JLabel.class).getText().contains("Has cambiado de servidor")));
            assertEquals(remembered, tokens.values.values().iterator().next());
            assertEquals(LauncherPreferences.validateBaseUrl(target.toString()),
                    preferences.loadServerUrl().orElseThrow());
            assertEquals(URI.create("https://java.example.test"),
                    preferences.loadDownloadSources().javaRuntime().baseUrl());
        }
    }

    @Test
    void probingTheSameServerKeepsTheExactSessionAndSavesIndependentSources()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        var previous = onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class));
        String token = tokens.values.values().iterator().next();
        var panel = onUi(() -> field(window, "home",
                LauncherHomePanel.class).serverSettings());
        var sources = new LauncherPreferences.DownloadSources(
                LauncherPreferences.DownloadSource.external(URI.create(
                "https://versions.example.test/catalog")),
                LauncherPreferences.DownloadSource.external(URI.create(
                "https://java.example.test/runtime")));
        onUi(() -> {
            panel.load(host, sources);
            named(panel, "test-server-connection", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(() -> named(panel, "test-server-connection",
                PixelButton.class).isEnabled());
        assertSame(previous, onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class)));
        assertEquals(token, tokens.values.values().iterator().next());
        assertTrue(onUi(() -> visible(window, "home")));
        assertEquals(sources, preferences.loadDownloadSources());
    }

    @Test
    void failedProbePreservesTheConnectedAccountAndSavedConfiguration() throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window = open();
        waitForConnection(window);
        var previous = onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class));
        URI previousHost = preferences.loadServerUrl().orElseThrow();
        var previousSources = preferences.loadDownloadSources();
        var panel = onUi(() -> field(window, "home",
                LauncherHomePanel.class).serverSettings());
        onUi(() -> {
            panel.load(URI.create("http://127.0.0.1:1"),
                    new LauncherPreferences.DownloadSources(
                    LauncherPreferences.DownloadSource.external(
                    URI.create("https://not-saved.example.test")),
                    LauncherPreferences.DownloadSource.defaults()));
            named(panel, "test-server-connection", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(() -> named(panel, "test-server-connection",
                PixelButton.class).isEnabled());
        assertSame(previous, onUi(() -> field(window, "currentSession",
                LauncherAuthClient.Session.class)));
        assertEquals(previousHost, preferences.loadServerUrl().orElseThrow());
        assertEquals(previousSources, preferences.loadDownloadSources());
    }

    @Test void profileEditingUpdatesTheNameAndRequiresFreshLoginAfterPasswordChanges()
            throws Exception {
        var original=client().login(host, "SavedUser", "saved-user-password", "PC", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        var window=open();
        waitForConnection(window);
        var home=onUi(()->field(window, "home", LauncherHomePanel.class));
        waitUntil(()->named(home.profile(), "profile-save-username",
                PixelButton.class).isEnabled());
        assertEquals("saved@example.test", onUi(()->named(home.profile(),
                "profile-email", JTextField.class).getText()));
        onUi(()-> { home.showSection(LauncherHomePanel.Section.PROFILE);
            named(home.profile(), "profile-username",
                    JTextField.class).setText("UpdatedUser");
            named(home.profile(), "profile-current-password",
                    JPasswordField.class).setText("saved-user-password");
            named(home.profile(), "profile-save-username", PixelButton.class).doClick(0);
            return null;
            });
        waitUntil(()->"UpdatedUser".equals(field(window, "currentSession",
                LauncherAuthClient.Session.class).username())
                && named(home.profile(), "profile-save-password",
                PixelButton.class).isEnabled());
        assertEquals(original.userId(), onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class).userId()));
        assertEquals("UpdatedUser", accounts.active().orElseThrow().username());
        var active=onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class));
        onUi(()-> {
            named(home.profile(), "profile-current-password",
                    JPasswordField.class).setText("saved-user-password");
            named(home.profile(), "profile-new-password",
                    JPasswordField.class).setText("updated-long-password");
            named(home.profile(), "profile-repeat-password",
                    JPasswordField.class).setText("updated-long-password");
            named(home.profile(), "profile-save-password", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(()->visible(window, "login"));
        assertNull(onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class)));
        assertTrue(accounts.active().isEmpty());
        assertTrue(tokens.values.isEmpty());
        assertFalse(preferences.loadRememberDevice());
        assertThrows(dev.leverage.service.ServiceException.class,
                ()->auth.authenticate(active.accessToken()));
        assertEquals(original.userId(), auth.login("UpdatedUser",
                "updated-long-password").user().id());
    }

    @Test void expiredProfileSessionReturnsToLoginInsteadOfLeavingAnUnusableEditor()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "PC", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        var window=open();
        waitForConnection(window);
        var home=onUi(()->field(window, "home", LauncherHomePanel.class));
        waitUntil(()->named(home.profile(), "profile-save-username",
                PixelButton.class).isEnabled());
        auth.logout(onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class).accessToken()));
        onUi(()-> {
            named(home.profile(), "profile-refresh", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(()->visible(window, "login"));
        assertNull(onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class)));
        assertEquals(0, onUi(()->named(home.profile(), "profile-current-password",
                JPasswordField.class).getPassword().length));
    }

    @Test void lateProfileResponsesNeverReplaceAnotherAccountsFields() throws Exception {
        var window=open();
        waitForConnection(window);
        var delayed=com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var oldId=java.util.UUID.randomUUID();
        delayed.createContext("/api/v1/me", exchange-> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            byte[] body= ("{\"user\":{\"id\":\""+oldId
                    +"\",\"username\":\"OldUser\",\"email\":\"old@example.test\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        delayed.start();
        try {
            var oldHost=URI.create("http://127.0.0.1:"+delayed.getAddress().getPort());
            var pending=onUi(()-> {
                var sessionField=LauncherWindow.class.getDeclaredField("currentSession");
                sessionField.setAccessible(true);
                sessionField.set(window, new LauncherAuthClient.Session("old-token",
                        Instant.now().plusSeconds(60), null, null,
                        null, oldId, "OldUser"));
                var hostField=LauncherWindow.class.getDeclaredField("serverHost");
                hostField.setAccessible(true);
                hostField.set(window, oldHost);
                field(window, "home", LauncherHomePanel.class).setAccount(
                        "OldUser", true);
                var load=LauncherWindow.class.getDeclaredMethod("loadHomeProfile");
                load.setAccessible(true);
                return (java.util.concurrent.CompletableFuture<?>)load.invoke(window);
            });
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            onUi(()-> {
                var nextId=java.util.UUID.randomUUID();
                var sessionField=LauncherWindow.class.getDeclaredField("currentSession");
                sessionField.setAccessible(true);
                sessionField.set(window, new LauncherAuthClient.Session("next-token",
                        Instant.now().plusSeconds(60), null, null,
                        null, nextId, "NextUser"));
                field(window, "home", LauncherHomePanel.class).profile().load(
                        new LauncherAuthClient.AccountProfile(nextId,
                        "NextUser", "next@example.test"));
                return null;
            });
            release.countDown();
            pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("next@example.test", onUi(()->named(field(window,
                    "home", LauncherHomePanel.class).profile(),
                    "profile-email", JTextField.class).getText()));
        } finally {
            release.countDown();
            delayed.stop(0);
        }
    }

    @Test
    void disconnectRevokesAccessAndKeepsPreferences() throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSources(new LauncherPreferences.DownloadSources(
                LauncherPreferences.DownloadSource.external(URI.create(
                "https://versions.example.test")),
                LauncherPreferences.DownloadSource.external(
                URI.create("https://java.example.test"))));
        LauncherWindow window=open();
        waitForConnection(window);
        waitUntil(()->"saved@example.test".equals(named(field(window,
                "home", LauncherHomePanel.class).profile(),
                "profile-email", JTextField.class).getText()));
        var session=onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class));
        onUi(()-> {
            named(field(window, "home", LauncherHomePanel.class).settings(),
                    "settings-disconnect", javax.swing.JButton.class).doClick(0);
            return null;
        });
        waitUntil(()->visible(window, "login")&&field(window,
                "loginButton", PixelButton.class).isEnabled());
        assertNull(onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class)));
        assertTrue(accounts.active().isEmpty());
        assertTrue(tokens.values.isEmpty());
        assertTrue(preferences.loadRememberDevice());
        assertEquals(host, preferences.loadServerUrl().orElseThrow());
        assertEquals(URI.create("https://java.example.test"),
                preferences.loadDownloadSources().javaRuntime().baseUrl());
        assertThrows(dev.leverage.service.ServiceException.class,
                ()->auth.authenticate(session.accessToken()));
    }

    @Test
    void disconnectNeverRemovesARememberedCredentialBelongingToAnotherHost()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window=open();
        waitForConnection(window);
        waitUntil(()->"saved@example.test".equals(named(field(window,
                "home", LauncherHomePanel.class).profile(),
                "profile-email", JTextField.class).getText()));
        try (ApiServer other=new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC())) {
            other.start();
            URI otherHost=URI.create("http://127.0.0.1:"+other.port());
            var otherSession=client().login(otherHost, "SavedUser",
                    "saved-user-password", "Other", true);
            String savedToken=tokens.values.values().iterator().next();
            onUi(()-> {
                named(field(window, "home", LauncherHomePanel.class).settings(),
                        "settings-disconnect", javax.swing.JButton.class).doClick(0);
                return null;
            });
            waitUntil(()->visible(window, "login")&&field(window,
                    "loginButton", PixelButton.class).isEnabled());
            assertEquals(LauncherPreferences.validateServerUrl(otherHost.toString()),
                    LauncherPreferences.validateServerUrl(
                    accounts.active().orElseThrow().host()));
            assertEquals(savedToken, tokens.values.values().iterator().next());
            assertEquals("SavedUser", auth.authenticate(
                    otherSession.accessToken()).user().username());
        }
    }

    @Test
    void atlasSavesMinecraftAndJavaOriginsIndependently() throws Exception {
        LauncherWindow window=open();
        waitForConnection(window);
        onUi(()-> {
            var show=LauncherWindow.class.getDeclaredMethod("show",
                    String.class, JTextField.class);
            show.setAccessible(true);
            show.invoke(window, "download-source", null);
            field(window, "versionsSourceChoice",
                    PixelDropdown.class).setSelectedIndex(1);
            field(window, "downloadSourceUrl", JTextField.class).setText(
                    "https://versions.example.test");
            field(window, "javaSourceChoice", PixelDropdown.class).setSelectedIndex(1);
            field(window, "javaSourceUrl", JTextField.class).setText(
                    "https://java.example.test");
            find(screen(window, "download-source"), PixelButton.class).doClick(0);
            return null;
        });
        assertEquals(URI.create("https://versions.example.test"),
                preferences.loadDownloadSources().versions().baseUrl());
        assertEquals(URI.create("https://java.example.test"),
                preferences.loadDownloadSources().javaRuntime().baseUrl());
        waitUntil(()->visible(window, "home"));
    }

    @Test void administratorDirectoryTracksServerRoleAndClearsWhenDemoted()
            throws Exception {
        client().login(host, "SavedUser", "saved-user-password", "Test", true);
        preferences.saveDownloadSource(LauncherPreferences.DownloadSource.defaults());
        LauncherWindow window=open();
        waitForConnection(window);
        waitUntil(()->visible(window, "home"));
        LauncherHomePanel home=field(window, "home", LauncherHomePanel.class);
        onUi(()->home.users().pendingLoad()).get(10,
                java.util.concurrent.TimeUnit.SECONDS);
        onUi(()-> {
            assertEquals("ADMIN", field(window, "currentSession",
                    LauncherAuthClient.Session.class).role());
            home.setAnimationEnabled(false);
            home.showSection(LauncherHomePanel.Section.USERS);
            JourneyPreviewSupport.render(home, 1040, 680);
            assertEquals(4, named(home, "library-subtabs",
                    JPanel.class).getComponentCount());
            assertNotNull(named(home, "users-row-name", JLabel.class));
            return null;
        });
        var original=auth.authenticate(onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class).accessToken()));
        var second=auth.register("SecondAdmin", "second-admin@example.test",
                "second-admin-password");
        auth.confirmEmail(mailer.verificationToken);
        auth.changeRole(original, second.id(), dev.leverage.domain.Role.ADMIN);
        var actor=auth.authenticate(auth.login("SecondAdmin",
                "second-admin-password").accessToken());
        auth.changeRole(actor, onUi(()->field(window, "currentSession",
                LauncherAuthClient.Session.class).userId()),
                dev.leverage.domain.Role.USER);
        onUi(()-> {
            named(home.profile(), "profile-refresh", PixelButton.class).doClick(0);
            return null;
        });
        waitUntil(()->"USER".equals(field(window, "currentSession",
                LauncherAuthClient.Session.class).role()));
        onUi(()-> {
            JourneyPreviewSupport.render(home, 1040, 680);
            assertEquals(3, named(home, "library-subtabs",
                    JPanel.class).getComponentCount());
            assertEquals(LauncherHomePanel.Section.VERSIONS, home.selectedSection());
            assertNull(named(home.users(), "users-row-name", JLabel.class));
            return null;
        });
    }

    @Test void skinRequestOwnershipSurvivesOnlyARoleRefreshNotAnAccountOrTokenChange()
            throws Exception {
        LauncherWindow window=open();
        waitForConnection(window);
        onUi(()-> {
            var session=new LauncherAuthClient.Session("fixture-token",
                    Instant.now().plusSeconds(600), null, null, null,
                    java.util.UUID.randomUUID(), "Player", "USER");
            var active=LauncherWindow.class.getDeclaredField("currentSession");
            active.setAccessible(true);
            var serverField=LauncherWindow.class.getDeclaredField("serverHost");
            serverField.setAccessible(true);
            serverField.set(window, host);
            var same=LauncherWindow.class.getDeclaredMethod("sameSkinAccount",
                    URI.class, LauncherAuthClient.Session.class);
            same.setAccessible(true);
            active.set(window, session.withRole("ADMIN"));
            assertEquals(true, same.invoke(window, host, session));
            assertEquals(false, same.invoke(window,
                    URI.create("http://127.0.0.1:1"), session));
            active.set(window, new LauncherAuthClient.Session("another-token",
                    session.expiresAt(), null, null, null, session.userId(),
                    session.username(), "ADMIN"));
            assertEquals(false, same.invoke(window, host, session));
            active.set(window, new LauncherAuthClient.Session(session.accessToken(),
                    session.expiresAt(), null, null, null,
                    java.util.UUID.randomUUID(), session.username(), "ADMIN"));
            assertEquals(false, same.invoke(window, host, session));
            active.set(window, null);
            assertEquals(false, same.invoke(window, host, session));
            return null;
        });
    }

    private static <T> T named(java.awt.Container parent, String name, Class<T> type) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof java.awt.Container container) {
                T result = named(container, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private LauncherAuthClient client() {
        return new LauncherAuthClient(new RememberedAccountManager(accounts, tokens));
    }

    private LauncherWindow open() throws Exception {
        LauncherPreferences reopened = new LauncherPreferences(
                directory.resolve("minecraft"));
        LauncherAuthClient reopenedClient = new LauncherAuthClient(
                new RememberedAccountManager(
                new LocalAccountStore(directory.resolve("minecraft")), tokens));
        return onUi(() -> {
            LauncherWindow window = new LauncherWindow(reopenedClient, reopened);
            var content = LauncherWindow.class.getDeclaredMethod("createContent");
            content.setAccessible(true);
            content.invoke(window);
            window.restoreSavedSetup();
            return window;
        });
    }

    private static void waitForConnection(LauncherWindow window) throws Exception {
        waitUntil(() -> field(window, "connectButton", PixelButton.class).isEnabled());
    }

    private static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (onUi(condition)) return;
            Thread.sleep(20);
        }
        fail("The interface did not complete the operation in time");
    }

    private static boolean visible(LauncherWindow window, String name) throws Exception {
        JPanel panel = screen(window, name);
        // A card may still be locally visible while its whole setup root is hidden.
        for (Component component = panel; component != null;
                component = component.getParent()) {
            if (!component.isVisible()) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static JPanel screen(LauncherWindow window, String name) throws Exception {
        return field(window, "screens", Map.class).get(
                name) instanceof JPanel panel ? panel : null;
    }

    private static <T> T field(Object target, String name,
            Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static <T> T onUi(Callable<T> operation) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(operation.call()); }
            catch (Exception exception) { failure.set(exception); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class MemoryTokens implements RefreshTokenStore {
        private final Map<String, String> values = new HashMap<>();
        @Override public synchronized void write(String id, String token) {
            values.put(id, token);
        }
        @Override public synchronized Optional<String> read(String id) {
            return Optional.ofNullable(values.get(id));
        }
        @Override public synchronized void delete(String id) { values.remove(id); }
    }

    private static final class NoMail implements RecoveryMailer {
        private String verificationToken;
        @Override public void sendEmailVerification(String email,
                String username, String token, Instant expiry) {
            verificationToken=token;
        }
        @Override public void sendPasswordReset(String email,
                String username, String token, Instant expiry) { }
    }
}

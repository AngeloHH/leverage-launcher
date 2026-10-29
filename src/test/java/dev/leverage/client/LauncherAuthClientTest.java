package dev.leverage.client;

import com.sun.jna.Platform;
import dev.leverage.db.Database;
import dev.leverage.http.ApiServer;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LauncherAuthClientTest {
    @TempDir
    Path temporaryDirectory;

    private ApiServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.close();
    }

    @Test
    void logsInOnceAndResumesWithProtectedRefreshToken() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("server.db"));
        database.initialize();
        AuthService auth = new AuthService(database,
                new PasswordHasher(10_000), new NoOpMailer(),
                Clock.systemUTC(), Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(60),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        var user = auth.createBootstrapAdmin("AutoLogin",
                "autologin@example.test", "auto-login-password");
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();

        LocalAccountStore localAccounts = new LocalAccountStore(
                temporaryDirectory.resolve(".minecraft"));
        RefreshTokenStore tokenStore = new MemoryTokenStore();
        LauncherAuthClient client = new LauncherAuthClient(
                new RememberedAccountManager(localAccounts, tokenStore));
        URI host = URI.create("http://127.0.0.1:" + server.port());

        LauncherAuthClient.Session login = client.login(host,
                "AutoLogin", "auto-login-password",
                "PC de pruebas", true);
        assertEquals(user.id(), login.userId());
        assertEquals("ADMIN", login.role());
        assertEquals("ADMIN", login.withUsername("RenamedAdmin").role());
        assertEquals("ADMIN", client.profile(host, login).role());
        assertTrue(login.accessToken().length() > 30);
        assertTrue(localAccounts.active().isPresent());

        String metadata = Files.readString(localAccounts.leverageDirectory(
                ).resolve("accounts.json"));
        assertFalse(metadata.contains("refreshToken"));
        assertFalse(metadata.contains(login.accessToken()));
        assertFalse(metadata.contains("auto-login-password"));

        client = new LauncherAuthClient(new RememberedAccountManager(
                new LocalAccountStore(temporaryDirectory.resolve(".minecraft")),
                tokenStore));
        assertEquals(user.id(), client.rememberedAccount().orElseThrow().userId());
        // No request or token decryption should be made for an unrelated server.
        assertTrue(client.resume(URI.create("http://127.0.0.1:1")).isEmpty());

        LauncherAuthClient.Session resumed = client.resume().orElseThrow();
        assertEquals(user.id(), resumed.userId());
        assertEquals("ADMIN", resumed.role(),
                "Remembered login must reload the server role, not infer it from loca"
                + "l files");
        assertFalse(login.accessToken().equals(resumed.accessToken()));

        client.forgetDevice();
        assertTrue(client.resume().isEmpty());
        assertTrue(localAccounts.active().isEmpty());
    }

    @Test
    void uncheckingRememberRevokesAndRemovesAnEarlierRememberedLogin() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("forget.db"));
        database.initialize();
        AuthService auth = new AuthService(database,
                new PasswordHasher(10_000), new NoOpMailer(),
                Clock.systemUTC(), Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(60),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("ForgetMe", "forget@example.test",
                "forget-me-password");
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        var manager = new RememberedAccountManager(new LocalAccountStore(
                temporaryDirectory), new MemoryTokenStore());
        var client = new LauncherAuthClient(manager);
        URI host = URI.create("http://127.0.0.1:" + server.port());
        client.login(host, "ForgetMe", "forget-me-password", "Test", true);
        String oldToken = manager.active().orElseThrow().refreshToken();
        var session = client.login(host, "ForgetMe", "forget-me-password", "Test", false);
        assertTrue(client.rememberedAccount().isEmpty());
        assertTrue(client.resume().isEmpty());
        assertEquals(session.userId(), auth.authenticate(
                session.accessToken()).user().id());
        assertThrows(dev.leverage.service.ServiceException.class,
                () -> auth.refresh(oldToken));
    }

    @Test
    void forgetsAnUnreadableCredentialWithoutNeedingToDecryptIt() throws Exception {
        var accounts = new LocalAccountStore(temporaryDirectory);
        String id = "4a5bb57e-6192-4d57-bc3f-d8bf543a4c40";
        accounts.remember(new LocalAccountStore.Account(
                java.util.UUID.fromString(id), "Test",
                "https://leverage.example.test/", id, Instant.now()));
        boolean[] deleted = { false };
        RefreshTokenStore brokenStore = new RefreshTokenStore() {
            @Override public void write(String key, String value) { }
            @Override public Optional<String> read(
                    String key) throws java.io.IOException {
                throw new java.io.IOException("Cannot decrypt");
            }
            @Override public void delete(String key) { deleted[0] = true; }
        };
        var client = new LauncherAuthClient(new RememberedAccountManager(accounts,
                brokenStore));
        assertThrows(java.io.IOException.class, client::forgetDevice);
        assertTrue(deleted[0]);
        assertTrue(accounts.active().isEmpty());
    }

    @Test
    void checksServerAndRegistersAnAccount() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("registration.db"));
        database.initialize();
        AuthService auth = new AuthService(database,
                new PasswordHasher(10_000), new NoOpMailer(),
                Clock.systemUTC(), Duration.ofMinutes(15),
                Duration.ofDays(30), Duration.ofSeconds(60),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();

        LocalAccountStore localAccounts = new LocalAccountStore(
                temporaryDirectory.resolve("minecraft"));
        LauncherAuthClient client = new LauncherAuthClient(
                new RememberedAccountManager(localAccounts, new MemoryTokenStore()));
        URI host = URI.create("http://127.0.0.1:" + server.port());

        LauncherAuthClient.ServerInfo info = client.checkServer(host);
        assertEquals("0.1.0", info.version());
        LauncherAuthClient.Registration registration = client.register(
                host, "new@example.test", "a-valid-password", "NewPlayer");
        assertEquals("NewPlayer", registration.username());
        assertEquals("new@example.test", registration.email());
        assertTrue(registration.emailVerificationRequired());
    }

    @Test
    void dpapiDoesNotStoreRefreshTokenAsPlainText() throws Exception {
        assumeTrue(Platform.isWindows());
        Path root = temporaryDirectory.resolve("leverage");
        WindowsDpapiTokenStore store = new WindowsDpapiTokenStore(root);
        String credential = "4a5bb57e-6192-4d57-bc3f-d8bf543a4c40";
        String token = "secret-refresh-token-that-must-not-be-plain";
        store.write(credential, token);

        byte[] bytes = Files.readAllBytes(root.resolve(
                "credentials").resolve(credential + ".bin"));
        assertFalse(new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8).contains(token));
        assertEquals(token, new WindowsDpapiTokenStore(root).read(
                credential).orElseThrow());
        store.delete(credential);
        assertTrue(store.read(credential).isEmpty());
    }

    @Test
    void registersAndVerifiesEmailWithSixDigitsThroughHttp() throws Exception {
        Database database = new Database(temporaryDirectory.resolve(
                "code-registration.db"));
        database.initialize();
        NoOpMailer mailer = new NoOpMailer();
        AuthService auth = new AuthService(database, new PasswordHasher(10_000), mailer,
                Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofHours(24),
                Duration.ofMinutes(30), true);
        server = new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        LauncherAuthClient client = new LauncherAuthClient(new RememberedAccountManager(
                new LocalAccountStore(temporaryDirectory.resolve("code-minecraft")),
                new MemoryTokenStore()));
        URI host = URI.create("http://127.0.0.1:" + server.port());
        var registration = client.register(host, "digits@example.test",
                "six-digit-password", "DigitPlayer");
        assertTrue(mailer.verificationCode.matches("[0-9]{6}"));
        var verified = client.confirmEmail(host, registration.email(),
                mailer.verificationCode);
        assertEquals(registration.userId(), verified.userId());
        assertEquals("ACTIVE", verified.status());
        var login=client.login(host, "DigitPlayer", "six-digit-password", "Test", false);
        assertEquals("DigitPlayer", login.username());
        assertEquals("USER", login.role());
        assertEquals("USER", client.profile(host, login).role());
    }

    private static final class NoOpMailer implements RecoveryMailer {
        private String verificationCode;

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt,
                String code, Instant codeExpiresAt) {
            verificationCode = code;
        }

        @Override
        public void sendEmailVerification(String email, String username,
                String token, Instant expiresAt) {
        }

        @Override
        public void sendPasswordReset(String email, String username,
                String token, Instant expiresAt) {
        }
    }

    @Test void profileEditsUseTheActiveServerAndKeepRememberedMetadataScoped(
            ) throws Exception {
        Database database=new Database(temporaryDirectory.resolve("profile.db"));
        database.initialize();
        var auth=new AuthService(database, new PasswordHasher(10_000),
                new NoOpMailer(), Clock.systemUTC(), Duration.ofMinutes(15),
                Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("ProfileUser", "profile@example.test",
                "current-long-password");
        server=new ApiServer("127.0.0.1", 0, auth, Clock.systemUTC());
        server.start();
        URI host=URI.create("http://127.0.0.1:"+server.port());
        var accounts=new LocalAccountStore(temporaryDirectory.resolve("profile-client"));
        var tokens=new MemoryTokenStore();
        var client=new LauncherAuthClient(new RememberedAccountManager(accounts, tokens));
        var session=client.login(host, "ProfileUser",
                "current-long-password", "PC", true);
        assertEquals("profile@example.test", client.profile(host, session).email());
        var profile=client.changeUsername(host, session,
                "RenamedUser", "current-long-password");
        client.updateRememberedUsername(host, profile);
        assertEquals("RenamedUser", accounts.active().orElseThrow().username());
        URI different=URI.create("http://127.0.0.1:1");
        client.updateRememberedUsername(different, new LauncherAuthClient.AccountProfile(
                profile.userId(), "WrongServer", profile.email()));
        client.forgetLocalAccount(different, session);
        assertEquals("RenamedUser", accounts.active().orElseThrow().username());
        client.changeEmail(host, session, "next-profile@example.test",
                "current-long-password");
        assertEquals("profile@example.test", client.profile(host, session).email());
        client.changePassword(host, session, "current-long-password",
                "different-long-password");
        assertThrows(LauncherAuthClient.AuthHttpException.class,
                ()->client.profile(host, session));
        client.forgetLocalAccount(host, session);
        assertTrue(accounts.active().isEmpty());
        assertTrue(tokens.values.isEmpty());
        assertEquals("RenamedUser", client.login(host, "RenamedUser",
                "different-long-password", "PC", false).username());
        String metadata=Files.readString(accounts.leverageDirectory(
                ).resolve("accounts.json"));
        assertFalse(metadata.contains("different-long-password"));
        assertFalse(metadata.contains("current-long-password"));
    }

    @Test void profileRequestsNeverFollowRedirectsWithCredentials() throws Exception {
        var target=com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var origin=com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var forwarded=new java.util.concurrent.atomic.AtomicInteger();
        target.createContext("/leak", exchange-> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        target.start();
        origin.createContext("/api/v1/me", exchange-> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:"
                    +target.getAddress().getPort()+"/leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        origin.start();
        try {
            var client=new LauncherAuthClient(new RememberedAccountManager(
                    new LocalAccountStore(temporaryDirectory.resolve("redirect")),
                    new MemoryTokenStore()));
            var session=new LauncherAuthClient.Session("fixture-access-token",
                    Instant.now().plusSeconds(60), null, null, null,
                    java.util.UUID.randomUUID(), "Fixture");
            assertThrows(LauncherAuthClient.AuthHttpException.class,
                    ()->client.profile(URI.create("http://127.0.0.1:"
                    +origin.getAddress().getPort()), session));
            assertEquals(0, forwarded.get());
        } finally {
            origin.stop(0);
            target.stop(0);
        }
    }

    private static final class MemoryTokenStore implements RefreshTokenStore {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public void write(String credentialId, String refreshToken) {
            values.put(credentialId, refreshToken);
        }

        @Override
        public Optional<String> read(String credentialId) {
            return Optional.ofNullable(values.get(credentialId));
        }

        @Override
        public void delete(String credentialId) {
            values.remove(credentialId);
        }
    }
}

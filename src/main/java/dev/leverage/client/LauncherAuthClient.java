package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class LauncherAuthClient {
    private static final Gson GSON = new Gson();
    private final HttpClient http;
    private final RememberedAccountManager rememberedAccounts;
    private String credentialWarning;
    private boolean deviceRemembered;

    public LauncherAuthClient(RememberedAccountManager rememberedAccounts) {
        this.rememberedAccounts = rememberedAccounts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                15)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static LauncherAuthClient forMinecraftDirectory(
            Path minecraftDirectory) throws IOException {
        LocalAccountStore accounts = new LocalAccountStore(minecraftDirectory);
        return new LauncherAuthClient(new RememberedAccountManager(accounts,
                RefreshTokenStores.forDirectory(accounts.leverageDirectory())));
    }

    public synchronized boolean isDeviceRemembered() { return deviceRemembered; }

    // One-shot UI notice, without exposing refresh tokens or native helper output.
    public synchronized Optional<String> takeCredentialWarning() {
        String warning = credentialWarning;
        credentialWarning = null;
        return Optional.ofNullable(warning);
    }

    public ServerInfo checkServer(URI host) throws IOException, InterruptedException {
        URI base = validateHost(host);
        HttpRequest request = HttpRequest.newBuilder(base.resolve(
                "/api/v1/health")).timeout(Duration.ofSeconds(10)).header("Accept",
                "application/json").GET().build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthHttpException(response.statusCode(),
                    errorCode(response.body()));
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String status = json.get("status").getAsString();
            String service = json.get("service").getAsString();
            if (!status.equalsIgnoreCase("ok") || !service.equalsIgnoreCase("leverage")) {
                throw new IOException(t("network.notLeverage"));
            }
            String version = json.has("version") ? json.get(
                    "version").getAsString() : "desconocida";
            return new ServerInfo(base, version);
        } catch (RuntimeException exception) {
            throw new IOException(t("network.healthInvalid"), exception);
        }
    }

    public Registration register(URI host, String email, String password,
            String username) throws IOException, InterruptedException {
        URI base = validateHost(host);
        JsonObject request = new JsonObject();
        request.addProperty("username", username);
        request.addProperty("email", email);
        request.addProperty("password", password);
        JsonObject response = post(base, "/api/v1/auth/register", request);
        try {
            JsonObject user = response.getAsJsonObject("user");
            return new Registration(UUID.fromString(user.get("id").getAsString()),
                    user.get("username").getAsString(), user.get("email").getAsString(),
                    response.get("emailVerificationRequired").getAsBoolean());
        } catch (RuntimeException exception) {
            throw new IOException(t("network.registrationIncomplete"), exception);
        }
    }

    public void requestEmailVerification(URI host,
            String email) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("email", email);
        post(validateHost(host), "/api/v1/auth/verify-email/request", request);
    }

    public VerifiedAccount confirmEmail(URI host,
            String token) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("token", token);
        return confirmEmail(host, request);
    }

    public VerifiedAccount confirmEmail(URI host, String email,
            String code) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("email", email);
        request.addProperty("code", code);
        return confirmEmail(host, request);
    }

    private VerifiedAccount confirmEmail(URI host,
            JsonObject request) throws IOException, InterruptedException {
        JsonObject response = post(validateHost(host),
                "/api/v1/auth/verify-email/confirm", request);
        try {
            JsonObject user = response.getAsJsonObject("user");
            return new VerifiedAccount(UUID.fromString(user.get("id").getAsString()),
                    user.get("username").getAsString(), user.get("status").getAsString());
        } catch (RuntimeException exception) {
            throw new IOException(t("network.verificationIncomplete"), exception);
        }
    }

    public synchronized Session login(URI host, String identifier, String password,
            String deviceName, boolean rememberDevice) throws IOException,
            InterruptedException {
        credentialWarning = null;
        deviceRemembered = false;
        URI base = validateHost(host);
        JsonObject request = new JsonObject();
        request.addProperty("identifier", identifier);
        request.addProperty("password", password);
        request.addProperty("rememberDevice", rememberDevice);
        request.addProperty("deviceName", deviceName);
        Session session = parseSession(post(base, "/api/v1/auth/login", request));
        if (rememberDevice) {
            if (session.refreshToken() == null) {
                throw new IOException(t("network.noRememberedSession"));
            }
            rememberSession(base, session);
        } else {
            // Unchecking the box must also stop a previous account from auto-signing in.
            try {
                forgetDevice();
            } catch (IOException exception) {
                // Report local cleanup failure even if revocation fails.
                if (rememberedAccounts.account().isPresent()) throw exception;
            }
        }
        return session.withoutRefreshToken();
    }

    public synchronized Optional<LocalAccountStore
            .Account> rememberedAccount() throws IOException {
        return rememberedAccounts.account();
    }

    public synchronized Optional<Session> resume() throws IOException,
            InterruptedException {
        Optional<LocalAccountStore.Account> account = rememberedAccount();
        return account.isEmpty() ? Optional.empty()
                : resume(URI.create(account.get().host()));
    }

    public synchronized Optional<Session> resume(URI expectedHost) throws IOException,
            InterruptedException {
        credentialWarning = null;
        deviceRemembered = false;
        URI expected = validateHost(expectedHost);
        Optional<LocalAccountStore.Account> account = rememberedAccount();
        // Saved credentials belong to one server, even on the same PC.
        if (account.isEmpty() || !validateHost(URI.create(
                account.get().host())).equals(expected)) {
            return Optional.empty();
        }
        Optional<RememberedAccountManager.SavedLogin> saved = rememberedAccounts.active();
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        URI host = validateHost(URI.create(saved.get().account().host()));
        JsonObject request = new JsonObject();
        request.addProperty("refreshToken", saved.get().refreshToken());
        try {
            Session session = parseSession(post(host, "/api/v1/auth/refresh", request));
            if (session.refreshToken() == null || session.refreshToken().isBlank()) {
                throw new IOException(t("network.refreshFailed"));
            }
            rememberSession(host, session);
            return Optional.of(session.withoutRefreshToken());
        } catch (AuthHttpException exception) {
            if (exception.status() == 401 || exception.status() == 403) {
                rememberedAccounts.forgetActive();
                if (exception.accountRestricted()) throw exception;
                return Optional.empty();
            }
            throw exception;
        }
    }

    private void rememberSession(URI host, Session session) throws InterruptedException {
        try {
            rememberedAccounts.remember(session.userId(), session.username(),
                    host.toString(), session.refreshToken());
            deviceRemembered = true;
        } catch (IOException unavailable) {
            // A valid access session must work without device storage.
            credentialWarning = t("credentials.sessionOnly");
            try { rememberedAccounts.forget(session.userId(), host.toString()); }
            catch (IOException ignored) { }
            JsonObject revoke = new JsonObject();
            revoke.addProperty("refreshToken", session.refreshToken());
            try { post(host, "/api/v1/auth/forget-device", revoke); }
            catch (IOException ignored) {
                // Nothing is persisted; the unused device token expires server-side.
            }
        }
    }

    public synchronized void forgetDevice() throws IOException, InterruptedException {
        deviceRemembered = false;
        try {
            Optional<RememberedAccountManager
                    .SavedLogin> saved = rememberedAccounts.active();
            if (saved.isEmpty()) return;
            URI host = validateHost(URI.create(saved.get().account().host()));
            JsonObject request = new JsonObject();
            request.addProperty("refreshToken", saved.get().refreshToken());
            post(host, "/api/v1/auth/forget-device", request);
        } finally {
            // Forget locally even if the token or server is unavailable.
            rememberedAccounts.forgetActive();
        }
    }

    public AccountProfile profile(URI host, Session session) throws IOException,
            InterruptedException {
        return parseProfile(authenticated(host, session,
                "/api/v1/me", null), session.userId());
    }

    public AccountProfile changeUsername(URI host, Session session, String username,
            String currentPassword) throws IOException, InterruptedException {
        JsonObject body=new JsonObject();
        body.addProperty("username", username);
        body.addProperty("currentPassword", currentPassword);
        return parseProfile(authenticated(host, session,
                "/api/v1/me/username", body), session.userId());
    }

    public void changeEmail(URI host, Session session, String email,
            String currentPassword) throws IOException, InterruptedException {
        JsonObject body=new JsonObject();
        body.addProperty("newEmail", email);
        body.addProperty("currentPassword", currentPassword);
        authenticated(host, session, "/api/v1/me/email", body);
    }

    public void changePassword(URI host, Session session, String currentPassword,
            String newPassword) throws IOException, InterruptedException {
        JsonObject body=new JsonObject();
        body.addProperty("currentPassword", currentPassword);
        body.addProperty("newPassword", newPassword);
        authenticated(host, session, "/api/v1/me/password", body);
    }

    public void updateRememberedUsername(URI host,
            AccountProfile profile) throws IOException {
        rememberedAccounts.updateUsername(profile.userId(),
                validateHost(host).toString(), profile.username());
    }

    public void forgetLocalAccount(URI host, Session session) throws IOException {
        rememberedAccounts.forget(session.userId(), validateHost(host).toString());
    }

    // Revoke the current access session using the existing server logout endpoint.
    public void logout(URI host, Session session) throws IOException,
            InterruptedException {
        if (session == null) return;
        URI base = validateHost(host);
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/auth/logout")).timeout(
                Duration.ofSeconds(8)).header("Authorization", "Bearer " +
                session.accessToken()).POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthHttpException(response.statusCode(), "LOGOUT_FAILED");
        }
    }

    private JsonObject authenticated(URI host, Session session, String path,
            JsonObject body) throws IOException, InterruptedException {
        URI base=validateHost(host);
        if (session==null || session.accessToken()==null
                || session.accessToken().isBlank())
                throw new IllegalArgumentException(t("profile.editSignIn"));
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(
                30)).header("Accept", "application/json").header("Authorization",
                "Bearer "+session.accessToken());
        if (body==null)builder.GET();
        else builder.header("Content-Type", "application/json").PUT(
                HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        var response=http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()<200 || response.statusCode()>=300)
                throw authError(response.statusCode(), response.body());
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException failure) {
            throw new IOException(t("network.profileInvalid"), failure);
        }
    }

    private static AccountProfile parseProfile(JsonObject json,
            UUID expected) throws IOException {
        try {
            var user=json.getAsJsonObject("user");
            var profile=new AccountProfile(UUID.fromString(user.get("id").getAsString()),
                    user.get("username").getAsString(), user.get("email").getAsString(),
                    user.has("role")?user.get("role").getAsString():"USER");
            if (!profile.userId().equals(expected)
                    || profile.username().isBlank() || profile.email().isBlank())
                    throw new IllegalArgumentException("Wrong account");
            return profile;
        } catch (RuntimeException failure) {
            throw new IOException(t("network.accountInvalid"), failure);
        }
    }

    public record AccountProfile(UUID userId, String username,
            String email, String role) {
        public AccountProfile(UUID userId, String username, String email) {
            this(userId, username, email, "USER");
        }
        public AccountProfile { role=knownRole(role); }
    }

    private JsonObject post(URI host, String path,
            JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(host.resolve(
                path)).timeout(Duration.ofSeconds(30)).header("Content-Type",
                "application/json").header("Accept", "application/json").POST(
                HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw authError(response.statusCode(), response.body());
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException(t("network.authInvalid"), exception);
        }
    }

    private static Session parseSession(JsonObject json) throws IOException {
        try {
            JsonObject user = json.getAsJsonObject("user");
            return new Session(json.get("accessToken").getAsString(),
                    Instant.parse(json.get("expiresAt").getAsString()),
                    json.has("refreshToken") ? json.get("refreshToken").getAsString()
                    : null, json.has("refreshExpiresAt") ? Instant.parse(json.get(
                    "refreshExpiresAt").getAsString()) : null, json.has("deviceSessionId")
                    ? json.get("deviceSessionId").getAsString() : null, UUID.fromString(
                    user.get("id").getAsString()), user.get("username").getAsString(),
                    user.has("role") ? user.get("role").getAsString() : "USER");
        } catch (RuntimeException exception) {
            throw new IOException(t("network.sessionIncomplete"), exception);
        }
    }

    private static String errorCode(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().getAsJsonObject(
                    "error").get("code").getAsString();
        } catch (RuntimeException exception) {
            return "HTTP_ERROR";
        }
    }

    private static AuthHttpException authError(int status, String body) {
        String code = errorCode(body), message = null;
        if (code.equals("ACCOUNT_BANNED") || code.equals("ACCOUNT_SUSPENDED")) {
            try {
                message = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject(
                        "error").get("message").getAsString();
            } catch (RuntimeException ignored) { }
        }
        return new AuthHttpException(status, code, message);
    }

    private static URI validateHost(URI host) {
        if (host == null || host.getScheme() == null || host.getHost() == null
                || (!host.getScheme().equalsIgnoreCase("http")
                && !host.getScheme().equalsIgnoreCase("https"))
                || host.getUserInfo() != null || host.getQuery() != null
                || host.getFragment() != null || (host.getPath() != null
                && !host.getPath().isBlank() && !host.getPath().equals("/"))) {
            throw new IllegalArgumentException(t("network.hostAlt"));
        }
        return LauncherPreferences.validateServerUrl(host.toString());
    }

    public record Session(String accessToken, Instant expiresAt,
            String refreshToken, Instant refreshExpiresAt, String deviceSessionId,
            UUID userId, String username, String role) {
        // Older integrations without a role never acquire administrative UI access.
        public Session(String accessToken, Instant expiresAt,
                String refreshToken, Instant refreshExpiresAt,
                String deviceSessionId, UUID userId, String username) {
            this(accessToken, expiresAt, refreshToken, refreshExpiresAt,
                    deviceSessionId, userId, username, "USER");
        }
        public Session { role=knownRole(role); }
        public Session withUsername(String value) {
            return new Session(accessToken, expiresAt, refreshToken,
                    refreshExpiresAt, deviceSessionId, userId, value, role);
        }
        public Session withRole(String value) {
            return new Session(accessToken, expiresAt, refreshToken,
                    refreshExpiresAt, deviceSessionId, userId, username, value);
        }
        private Session withoutRefreshToken() {
            return new Session(accessToken, expiresAt, null, refreshExpiresAt,
                    deviceSessionId, userId, username, role);
        }
    }

    private static String knownRole(String role) {
        return "ADMIN".equals(role)||"MODERATOR".equals(role)?role:"USER";
    }

    public record ServerInfo(URI host, String version) {
    }

    public record Registration(UUID userId, String username,
            String email, boolean emailVerificationRequired) {
    }

    public record VerifiedAccount(UUID userId, String username, String status) {
    }

    public static final class AuthHttpException extends IOException {
        private final int status;
        private final String code;
        private final String serverMessage;

        public AuthHttpException(int status, String code) {
            this(status, code, null);
        }

        private AuthHttpException(int status, String code, String serverMessage) {
            super(I18n.t("network.rejected", code, status));
            this.status = status;
            this.code = code;
            this.serverMessage = serverMessage == null ? null
                    : serverMessage.substring(0, Math.min(2400,
                    serverMessage.length())).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        }

        public boolean accountRestricted() {
            return code.equals("ACCOUNT_BANNED") || code.equals("ACCOUNT_SUSPENDED");
        }
        public String restrictionMessage() {
            return serverMessage == null || serverMessage.isBlank()
                    ? code.equals("ACCOUNT_BANNED") ? t("restriction.serverBanned")
                    : t("restriction.serverSuspended") : serverMessage;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}

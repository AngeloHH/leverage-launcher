package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.skin.DefaultSkin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Administrative credentials stay in headers sent to the configured host.
public final class LauncherUsersClient {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(
            Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public record User(UUID id, String username, String email, String role,
            String status, Instant createdAt, Instant lastSeenAt,
            boolean banned, String restrictionReason, String skinHash) {
        // Old servers and fixtures can omit the additional account metadata.
        public User(UUID id, String username, String email, String role, String status) {
            this(id, username, email, role, status, null, null, false, null, null);
        }
    }

    public record Page(List<User> users, int page, int pageSize,
            long total, int totalPages) {
        public Page { users = List.copyOf(users); }
    }

    public Page search(URI host, String accessToken, String query, int page,
            int pageSize) throws IOException, InterruptedException {
        return search(host, accessToken, query, page, pageSize, null, null);
    }

    public Page search(URI host, String accessToken, String query, int page, int pageSize,
            String sort, String direction) throws IOException, InterruptedException {
        URI base = validateHost(host);
        requireToken(accessToken);
        if (pageSize != 10 && pageSize != 25 && pageSize != 50 && pageSize != 100)
                throw new IllegalArgumentException(t("users.pageSizeInvalid"));
        String search = query == null ? "" : query.trim();
        if (search.length() > 200) throw new IllegalArgumentException(
                t("users.searchTooLong"));
        URI endpoint = base.resolve("/api/v1/admin/users?q=" + URLEncoder.encode(search,
                StandardCharsets.UTF_8) + "&page=" + Math.max(1, page) + "&pageSize="
                + pageSize + (sort==null?"":"&sort="+URLEncoder.encode(sort,
                StandardCharsets.UTF_8) +"&direction="+URLEncoder.encode(direction==null
                ?"asc":direction, StandardCharsets.UTF_8)));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(
                20)).header("Authorization", "Bearer " + accessToken).header("Accept",
                "application/json").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var input = response.body()) {
            if (response.statusCode() != 200)
                    throw new LauncherAuthClient.AuthHttpException(
                    response.statusCode(), "USERS_REQUEST_FAILED");
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new IOException(
                t("users.responseTooLarge"));
        try {
            JsonObject json = JsonParser.parseString(new String(bytes,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            int actualPage = json.get("page").getAsInt(),
                    actualSize = json.get("pageSize").getAsInt();
            long total = json.get("total").getAsLong();
            int totalPages = json.get("totalPages").getAsInt();
            if (actualSize != pageSize || total < 0 || actualPage < 1
                    || actualPage > totalPages || totalPages != Math.max(1L,
                    (total + pageSize - 1) / pageSize))
                    throw new IllegalArgumentException("Invalid pagination");
            List<User> users = new ArrayList<>();
            for (var element : json.getAsJsonArray("users")) {
                users.add(parseUser(element.getAsJsonObject()));
            }
            long expectedRows = Math.min(pageSize, Math.max(0,
                    total - (long)(actualPage - 1) * pageSize));
            if (users.size() != expectedRows)
                    throw new IllegalArgumentException("Invalid row count");
            return new Page(users, actualPage, actualSize, total, totalPages);
        } catch (RuntimeException exception) {
            throw new IOException(t("users.responseInvalid"));
        }
    }

    public void ban(URI host, String accessToken, UUID userId,
            String reason) throws IOException, InterruptedException {
        ban(host, accessToken, userId, reason, null);
    }
    public void ban(URI host, String accessToken, UUID userId, String reason,
            Instant expiresAt) throws IOException, InterruptedException {
        String clean = reason == null ? "" : reason.trim();
        if (clean.isBlank() || clean.codePointCount(0, clean.length()) > 1_000
                || clean.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t'))
                throw new IllegalArgumentException(t("users.enterBanReason"));
        var json = new JsonObject();
        json.addProperty("reason", clean);
        if (expiresAt!=null)json.addProperty("expiresAt", expiresAt.toString());
        mutate(host, accessToken, userId, expiresAt==null?"ban":"ban-until", "PUT", json);
    }

    public void clearSkin(URI host, String accessToken,
            UUID userId) throws IOException, InterruptedException {
        mutate(host, accessToken, userId, "skin", "DELETE", null);
    }

    public void changePassword(URI host, String accessToken, UUID userId,
            String password) throws IOException, InterruptedException {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (password == null || password.isBlank() || length < 8 || length > 128)
                throw new IllegalArgumentException(t("error.passwordLength"));
        var json = new JsonObject();
        json.addProperty("password", password);
        mutate(host, accessToken, userId, "password", "PUT", json);
    }

    public void changeEmail(URI host, String accessToken, UUID userId,
            String email) throws IOException, InterruptedException {
        String clean = email == null ? "" : email.trim();
        if (clean.length() > 254 || !clean.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                throw new IllegalArgumentException(t("error.emailFormat"));
        var json = new JsonObject();
        json.addProperty("email", clean);
        mutate(host, accessToken, userId, "email", "PUT", json);
    }

    // Fetch public textures from the configured server, not user-provided URLs.
    public BufferedImage loadSkin(URI host, String hash) throws IOException,
            InterruptedException {
        URI base = validateHost(host);
        if (hash == null) return DefaultSkin.image();
        if (!isTextureHash(hash)) throw new IllegalArgumentException(
                t("skins.invalidReference"));
        var request = HttpRequest.newBuilder(base.resolve("/api/yggdrasil/textures/"
                + hash)).timeout(Duration.ofSeconds(20)).header("Accept",
                "image/png").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var input = response.body()) {
            if (response.statusCode() != 200) throw new IOException(
                    t("skins.userDownloadError"));
            bytes = input.readNBytes(MAX_SKIN_BYTES + 1);
        }
        if (bytes.length > MAX_SKIN_BYTES) throw new IOException(t("skins.tooLarge"));
        try {
            return LocalSkinLibrary.readPng(bytes);
        } catch (RuntimeException exception) {
            throw new IOException(t("skins.userPngDimensions"));
        }
    }

    private void mutate(URI host, String accessToken, UUID userId, String action,
            String method, JsonObject json) throws IOException, InterruptedException {
        URI base = validateHost(host);
        requireToken(accessToken);
        if (userId == null) throw new IllegalArgumentException(t("users.choose"));
        var builder = HttpRequest.newBuilder(base.resolve("/api/v1/admin/users/" + userId
                + "/" + action)).timeout(Duration.ofSeconds(20)).header("Authorization",
                "Bearer " + accessToken).header("Accept", "application/json");
        if (json == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json; charset=utf-8").method(
                method, HttpRequest.BodyPublishers.ofString(json.toString(),
                StandardCharsets.UTF_8));
        var response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var input = response.body()) {
            if (action.equals("ban-until") && response.statusCode()==404)
                    throw new LauncherAuthClient.AuthHttpException(503,
                    "BAN_EXPIRY_UNSUPPORTED");
            if (response.statusCode() != 200)
                    throw new LauncherAuthClient.AuthHttpException(
                    response.statusCode(), "USERS_ACTION_FAILED");
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new IOException(
                t("users.responseTooLarge"));
        try {
            var result = JsonParser.parseString(new String(bytes,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (!userId.equals(parseUser(result.getAsJsonObject("user")).id()))
                    throw new IllegalArgumentException("Invalid account");
        } catch (RuntimeException exception) {
            throw new IOException(t("users.adminResponseInvalid"));
        }
    }

    private static User parseUser(JsonObject user) {
        String hash = optionalString(user, "skinHash");
        if (hash != null && !isTextureHash(hash))
                throw new IllegalArgumentException("Invalid texture reference");
        boolean banned = false;
        if (user.has("banned") && !user.get("banned").isJsonNull()) {
            if (!user.get("banned").isJsonPrimitive()
                    || !user.get("banned").getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("Invalid restriction flag");
            banned = user.get("banned").getAsBoolean();
        }
        return new User(UUID.fromString(user.get("id").getAsString()),
                user.get("username").getAsString(), user.get("email").getAsString(),
                user.get("role").getAsString(), user.get("status").getAsString(),
                optionalInstant(user, "createdAt"), optionalInstant(user, "lastSeenAt"),
                banned, optionalString(user, "restrictionReason"), hash);
    }

    private static String optionalString(JsonObject user, String name) {
        if (!user.has(name) || user.get(name).isJsonNull()) return null;
        if (!user.get(name).isJsonPrimitive()
                || !user.get(name).getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid metadata");
        return user.get(name).getAsString();
    }

    private static Instant optionalInstant(JsonObject user, String name) {
        String value = optionalString(user, name);
        return value == null ? null : Instant.parse(value);
    }

    private static boolean isTextureHash(String value) {
        return value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    }

    private static void requireToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank())
                throw new IllegalArgumentException(t("users.signInAdmin"));
    }

    private static URI validateHost(URI host) {
        if (host == null || host.getScheme() == null || host.getHost() == null
                || (!host.getScheme().equalsIgnoreCase("http")
                && !host.getScheme().equalsIgnoreCase("https"))
                || host.getUserInfo() != null || host.getQuery() != null
                || host.getFragment() != null || (host.getPath() != null
                && !host.getPath().isBlank() && !host.getPath().equals("/")))
                throw new IllegalArgumentException(t("network.hostAlt"));
        return LauncherPreferences.validateServerUrl(host.toString());
    }
}

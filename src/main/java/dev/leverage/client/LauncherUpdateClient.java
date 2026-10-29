package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public final class LauncherUpdateClient {
    private static final Gson GSON = new Gson();
    private final HttpClient http;

    public LauncherUpdateClient() {
        this.http = HttpClient.newBuilder().connectTimeout(
                Duration.ofSeconds(15)).build();
    }

    public CheckResult checkRunningLauncher(URI host,
            String channel) throws IOException, InterruptedException {
        return check(host, channel, runningLauncherPath());
    }

    public CheckResult check(URI host, String channel,
            Path launcherJar) throws IOException, InterruptedException {
        URI base = validateHost(host);
        JsonObject body = new JsonObject();
        body.addProperty("channel", channel);
        body.addProperty("sha1", sha1(launcherJar));
        HttpRequest request = HttpRequest.newBuilder(base.resolve(
                "/api/v1/launcher/check")).timeout(Duration.ofSeconds(
                30)).header("Content-Type", "application/json").header("Accept",
                "application/json").POST(HttpRequest.BodyPublishers.ofString(
                GSON.toJson(body))).build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(I18n.t("update.checkRejected",
                    errorCode(response.body()), response.statusCode()));
        }
        try {
            JsonObject result = JsonParser.parseString(
                    response.body()).getAsJsonObject().getAsJsonObject("launcher");
            return new CheckResult(Status.valueOf(result.get("status").getAsString()),
                    result.get("canContinue").getAsBoolean(),
                    result.get("recognized").getAsBoolean(), value(result,
                    "currentVersion"), result.get("latestVersion").getAsString(),
                    result.get("latestSha1").getAsString(),
                    URI.create(result.get("downloadUrl").getAsString()), uri(result,
                    "releaseNotesUrl"), result.get("message").getAsString());
        } catch (RuntimeException exception) {
            throw new IOException(t("update.invalidResponse"), exception);
        }
    }

    // Loose classes have no release JAR to compare with the server's hash.
    public static boolean runningFromClasses() throws IOException {
        return Files.isDirectory(runningCodeLocation());
    }

    public static Path runningLauncherPath() throws IOException {
        Path path = runningCodeLocation();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(
                java.util.Locale.ROOT).endsWith(".jar")) {
            throw new IOException(t("update.classes"));
        }
        return path;
    }

    private static Path runningCodeLocation() throws IOException {
        try {
            URI location = LauncherUpdateClient.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IOException(t("update.locateError"), exception);
        }
    }

    public static String sha1(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(I18n.t("update.jarMissing", file));
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("La JVM no admite SHA-1.", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String value(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static URI uri(JsonObject object, String name) {
        String value = value(object, name);
        return value == null ? null : URI.create(value);
    }

    private static String errorCode(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject().getAsJsonObject(
                    "error").get("code").getAsString();
        } catch (RuntimeException exception) {
            return "HTTP_ERROR";
        }
    }

    private static URI validateHost(URI host) {
        if (host == null || host.getScheme() == null || host.getHost() == null
                || (!host.getScheme().equalsIgnoreCase("http")
                && !host.getScheme().equalsIgnoreCase("https"))
                || host.getUserInfo() != null || host.getQuery() != null
                || host.getFragment() != null || (host.getPath() != null
                && !host.getPath().isBlank() && !host.getPath().equals("/"))) {
            throw new IllegalArgumentException(t("network.host"));
        }
        String value = host.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    public enum Status { CURRENT, UPDATE_AVAILABLE, UPDATE_REQUIRED }

    public record CheckResult(Status status, boolean canContinue, boolean recognized,
            String currentVersion, String latestVersion, String latestSha1,
            URI downloadUrl, URI releaseNotesUrl, String message) {
    }
}

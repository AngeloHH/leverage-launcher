package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.service.ModPolicyService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class LauncherModVerifier {
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final ModDirectoryScanner scanner;

    public LauncherModVerifier() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                new ModDirectoryScanner());
    }

    LauncherModVerifier(HttpClient http, ModDirectoryScanner scanner) {
        this.http = http;
        this.scanner = scanner;
    }

    public Result verifyBeforePlay(URI host, String accessToken, Path minecraftDirectory,
            String server, String version) throws IOException, InterruptedException {
        return verifyBeforePlay(host, accessToken, minecraftDirectory,
                server, version, "default");
    }

    public Result verifyBeforePlay(URI host, String accessToken,
            Path minecraftDirectory, String server, String version,
            String loader) throws IOException, InterruptedException {
        URI base = validateHost(host);
        List<ModPolicyService.ClientMod> mods = scanner.scan(new InstanceLayout(
                minecraftDirectory).mods(server, version, loader));
        return verifyHashes(base, accessToken, server, version, loader,
                mods.stream().map(ModPolicyService.ClientMod::sha1).toList());
    }

    public Result verifyHashes(URI host, String accessToken,
            String server, String version, String loader,
            List<String> hashes) throws IOException, InterruptedException {
        return verify(validateHost(host), accessToken, server,
                version, loader, null, hashes);
    }

    // Start with a full scan; callbacks run on the watcher thread, not the EDT.
    public ModDirectoryWatcher watchInstance(URI host, String accessToken,
            Path minecraftDirectory, String server, String version, String loader,
            Consumer<Result> onResult, Consumer<Exception> onError) throws IOException {
        URI base = validateHost(host);
        Path directory = new InstanceLayout(minecraftDirectory).mods(server,
                version, loader);
        return new ModDirectoryWatcher(directory, scanner, hashes -> verifyHashes(base,
                accessToken, server, version, loader, hashes), onResult, onError);
    }

    // Watch the launched instance, including packs Minecraft can reload live.
    public ModDirectoryWatcher watchGameDirectory(URI host, String accessToken,
            Path instance, String server, String version, String loader,
            Consumer<Result> onResult, Consumer<Exception> onError) throws IOException {
        URI base = validateHost(host);
        return new ModDirectoryWatcher(instance.resolve("mods"),
                instance.resolve("resourcepacks"), scanner,
                hashes -> verifyHashes(base, accessToken, server, version, loader,
                hashes.stream().distinct().toList()), onResult, onError);
    }

    // Rescan disk; watcher results alone never authorize a launch.
    public PlayAuthorization prepareToPlay(URI host, String accessToken,
            Path minecraftDirectory, String server, String version,
            String loader) throws IOException, InterruptedException {
        List<String> hashes = scanner.scan(new InstanceLayout(
                minecraftDirectory).mods(server, version,
                loader)).stream().map(ModPolicyService.ClientMod::sha1).toList();
        JsonObject payload = new JsonObject();
        payload.addProperty("server", server);
        payload.addProperty("version", version);
        payload.addProperty("loader", loader);
        payload.add("sha1", GSON.toJsonTree(hashes));
        HttpResponse<String> response = send(validateHost(host), "/api/v1/game/tickets",
                accessToken, HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
        if (response.statusCode() == 403 || response.statusCode() == 409) {
            return new PlayAuthorization(decisions(response, false), null);
        }
        if (response.statusCode() != 201) throw httpFailure(response);
        JsonObject json = parseObject(response.body());
        try {
            GameTicket ticket = new GameTicket(json.get("ticket").getAsString(),
                    json.get("server").getAsString(),
                    Instant.parse(json.get("expiresAt").getAsString()));
            return new PlayAuthorization(new Result("APPROVED", Map.of()), ticket);
        } catch (RuntimeException exception) {
            throw new IOException(t("policy.invalidTicket"), exception);
        }
    }

    public Result verifyMod(URI host, String accessToken,
            String server, String version, String loader,
            ModPolicyService.ClientMod mod) throws IOException, InterruptedException {
        return verifyHash(host, accessToken, server, version, loader, mod.sha1());
    }

    public Result verifyHash(URI host, String accessToken, String server, String version,
            String loader, String sha1) throws IOException, InterruptedException {
        return verify(validateHost(host), accessToken, server,
                version, loader, sha1, null);
    }

    private Result verify(URI base, String accessToken,
            String server, String version, String loader, String sha1,
            List<String> hashes) throws IOException, InterruptedException {
        boolean single = sha1 != null;
        String request = GSON.toJson(single ? sha1 : hashes);

        String path = "/api/v1/game/mods/verify?server=" + encode(server)
                + "&version=" + encode(version) + "&loader=" + encode(loader);
        HttpResponse<String> response = send(base, path, accessToken,
                HttpRequest.BodyPublishers.ofString(request));
        if (response.statusCode() != 200 && response.statusCode() != 403
                && response.statusCode() != 409) {
            throw httpFailure(response);
        }
        return decisions(response, single);
    }

    private static Result decisions(HttpResponse<String> response,
            boolean single) throws IOException {
        try {
            JsonObject decisionsJson = JsonParser.parseString(
                    response.body()).getAsJsonObject();
            Map<String, Boolean> decisions = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : decisionsJson.entrySet()) {
                JsonElement allowed = entry.getValue().getAsJsonObject().get("allowed");
                decisions.put(entry.getKey(), allowed == null
                        || allowed.isJsonNull() ? null : allowed.getAsBoolean());
            }
            String status = response.statusCode() == 403 ? "SUSPENDED"
                    : response.statusCode() == 409 ? (single ? "UNKNOWN"
                    : "REJECTED") : single ? "ALLOWED" : "APPROVED";
            return new Result(status, java.util.Collections.unmodifiableMap(decisions));
        } catch (RuntimeException exception) {
            throw new IOException(t("policy.invalidResult"), exception);
        }
    }

    private HttpResponse<String> send(URI host, String path, String accessToken,
            HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(host.resolve(path)).timeout(
                Duration.ofSeconds(30)).header("Authorization", "Bearer "
                + accessToken).header("Content-Type", "application/json").header("Accept",
                "application/json").POST(body).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static IOException httpFailure(HttpResponse<String> response) {
        String code = "HTTP_ERROR";
        try {
            code = parseObject(response.body()).getAsJsonObject(
                    "error").get("code").getAsString();
        } catch (RuntimeException | IOException ignored) {
        }
        return new IOException(I18n.t("policy.checkRejected",
                code, response.statusCode()));
    }

    private static JsonObject parseObject(String body) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException(t("network.invalidJson"), exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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

    public record Result(String status, Map<String, Boolean> decisions) {
        public boolean approved() {
            return status.equals("APPROVED") || status.equals("ALLOWED");
        }
    }

    public record GameTicket(String token, String server, Instant expiresAt) { }

    public record PlayAuthorization(Result verification, GameTicket ticket) {
        public boolean approved() { return ticket != null && verification.approved(); }
    }

}

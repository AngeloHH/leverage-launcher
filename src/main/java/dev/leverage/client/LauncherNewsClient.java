package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.*;
import dev.leverage.service.NewsService;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;

// Credentials go only to the configured server and never follow redirects.
public class LauncherNewsClient {
    private static final Gson JSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(
            Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public List<NewsService.Item> list(URI host, String token,
            boolean admin) throws IOException, InterruptedException {
        var json = request(host, token, "GET", admin
                ? "/api/v1/admin/news" : "/api/v1/news", null);
        var result = new ArrayList<NewsService.Item>();
        try {
            for (var element : json.getAsJsonArray("news")) {
                var item = JSON.fromJson(element, NewsService.Item.class);
                validate(item);
                result.add(item);
            }
            if (result.size() > 20) throw new IllegalArgumentException();
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new IOException(t("news.responseInvalid"), e);
        }
    }
    public NewsService.Item save(URI host, String token, UUID id, boolean create,
            String markdown, String image) throws IOException, InterruptedException {
        var body = new JsonObject();
        body.addProperty("markdown", markdown);
        if (image != null) body.addProperty("imageBase64", image);
        return article(request(host, token, create ? "POST" : "PUT",
                "/api/v1/admin/news/" + id, body));
    }
    public NewsService.Item publish(URI host, String token,
            UUID id) throws IOException, InterruptedException {
        return article(request(host, token, "POST",
                "/api/v1/admin/news/" + id + "/publish", null));
    }
    public void delete(URI host, String token, UUID id) throws IOException,
            InterruptedException {
        request(host, token, "DELETE", "/api/v1/admin/news/" + id, null);
    }
    private static NewsService.Item article(JsonObject json) throws IOException {
        try {
            var item = JSON.fromJson(json.get("article"), NewsService.Item.class);
            validate(item);
            return item;
        } catch (RuntimeException e) {
            throw new IOException(t("news.articleInvalid"), e);
        }
    }
    private static void validate(NewsService.Item item) {
        if (item == null || item.id() == null
                || item.markdown() == null || item.markdown().isBlank()
                || item.markdown().length() > NewsService.MAX_MARKDOWN
                || item.modifiedAt() == null) throw new IllegalArgumentException();
        NewsService.validateImage(item.imageBase64());
    }
    private JsonObject request(URI host, String token, String method, String path,
            JsonObject body) throws IOException, InterruptedException {
        var base = LauncherPreferences.validateServerUrl(host.toString());
        if (token == null || token.isBlank())
                throw new IOException(t("news.signInRequired"));
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(
                Duration.ofSeconds(25)).header("Authorization",
                "Bearer " + token).header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        var response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var input = response.body()) {
            bytes = input.readNBytes(12 * 1024 * 1024 + 1);
        }
        if (bytes.length > 12 * 1024 * 1024) throw new IOException(
                t("news.responseTooLarge"));
        JsonObject json;
        try {
            json = JsonParser.parseString(new String(bytes,
                    StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException(t("news.apiUnavailable")); }
        if (response.statusCode() != 200) {
            String code = "HTTP_" + response.statusCode();
            try { code = json.getAsJsonObject("error").get("code").getAsString(); }
            catch (RuntimeException ignored) { }
            throw new IOException(I18n.serverError(code));
        }
        return json;
    }
    // Re-encode bounded local PNG/JPEG covers without their original metadata.
    public static String importImage(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > 15_000_000)
                throw new IOException(t("image.select"));
        BufferedImage source;
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException(t("image.format"));
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (!(format.equalsIgnoreCase("PNG")
                        || format.equalsIgnoreCase("JPEG")) || pixels > 40_000_000)
                        throw new IOException(t("image.tooLarge"));
                source = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (source == null) throw new IOException(t("image.readError"));
        for (int side : new int[] { 1600, 1280, 1024, 800 }) {
            double scale = Math.min(1, side / (double) Math.max(source.getWidth(),
                    source.getHeight()));
            var resized = new BufferedImage(Math.max(1, (int)(source.getWidth() * scale)),
                    Math.max(1, (int)(source.getHeight() * scale)),
                    BufferedImage.TYPE_INT_RGB);
            var g = resized.createGraphics();
            g.setColor(new Color(0x171615));
            g.fillRect(0, 0, resized.getWidth(), resized.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
            g.dispose();
            var output = new ByteArrayOutputStream();
            ImageIO.write(resized, "JPEG", output);
            if (output.size() <= NewsService.MAX_IMAGE_BYTES)
                    return Base64.getEncoder().encodeToString(output.toByteArray());
        }
        throw new IOException(t("image.resizeError"));
    }
    public static BufferedImage image(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(
                    NewsService.validateImage(encoded)));
        } catch (RuntimeException e) { throw new IOException(t("image.invalid"), e); }
    }
}

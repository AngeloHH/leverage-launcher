package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leverage.skin.DefaultSkin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

// Authenticated profile lookup followed by a public, credential-free texture download.
public final class LauncherSkinClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(
            Duration.ofSeconds(10)).build();

    public void upload(URI host, String accessToken, BufferedImage image,
            boolean slim) throws IOException, InterruptedException {
        if (image==null || image.getWidth()!=64 || (image.getHeight()!=32
                && image.getHeight()!=64)) throw new IOException(t("skins.dimensions"));
        if (slim && image.getHeight()==32)throw new IOException(t("skins.legacyClassic"));
        var bytes=new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        URI base=LauncherPreferences.validateServerUrl(host.toString());
        var request=HttpRequest.newBuilder(base.resolve("/api/v1/me/textures/skin?model="
                + (slim?"slim":"classic"))).timeout(Duration.ofSeconds(20)).header(
                "Authorization", "Bearer "+accessToken).header("Content-Type",
                "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(
                bytes.toByteArray())).build();
        var response=http.send(request, bounded(64*1024));
        if (response.statusCode()!=200)throw new IOException(I18n.t(
                "skins.serverRejected", response.statusCode()));
    }

    public Skin load(URI host, String accessToken) throws IOException,
            InterruptedException {
        URI base = LauncherPreferences.validateBaseUrl(host.toString());
        HttpRequest profileRequest = HttpRequest.newBuilder(base.resolve(
                "/api/v1/me/minecraft-profile")).timeout(Duration.ofSeconds(
                20)).header("Authorization", "Bearer " + accessToken).header("Accept",
                "application/json").GET().build();
        HttpResponse<byte[]> profileResponse = http.send(profileRequest,
                bounded(64 * 1024));
        if (profileResponse.statusCode() != 200)
                throw new IOException(t("skins.fetchError"));
        URI url;
        boolean slim;
        try {
            JsonObject profile = JsonParser.parseString(new String(profileResponse.body(),
                    StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("profile");
            if (!profile.has("skinUrl") || profile.get("skinUrl").isJsonNull()) {
                return new Skin(DefaultSkin.image(), false);
            }
            url = base.resolve(profile.get("skinUrl").getAsString());
            if (url.getHost() == null || url.getUserInfo() != null
                    || url.getFragment() != null || !("http".equalsIgnoreCase(
                    url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))) {
                throw new IllegalArgumentException(t("skins.httpUrl"));
            }
            slim = profile.has("skinModel") && "slim".equals(
                    profile.get("skinModel").getAsString());
        } catch (RuntimeException exception) {
            throw new IOException(t("skins.profileInvalid"), exception);
        }
        // Texture URLs never receive the profile's access token.
        HttpRequest imageRequest = HttpRequest.newBuilder(url).timeout(
                Duration.ofSeconds(20)).header("Accept", "image/png").GET().build();
        HttpResponse<byte[]> response = http.send(imageRequest, bounded(2 * 1024 * 1024));
        if (response.statusCode() != 200) throw new IOException(t("skins.downloadError"));
        try (var input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(response.body()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException(t("skins.notPngAlt"));
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                        || width != 64 || (height != 64 && height != 32)) {
                    throw new IOException(t("skins.pngDimensions"));
                }
                return new Skin(reader.read(0), slim && height == 64);
            } finally { reader.dispose(); }
        }
    }

    private static HttpResponse.BodyHandler<byte[]> bounded(int limit) {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse
                    .BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long size;
            private boolean done;

            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            public void onNext(List<ByteBuffer> buffers) {
                if (done) return;
                for (ByteBuffer buffer : buffers) size += buffer.remaining();
                if (size > limit) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new IOException(t("skins.responseTooLarge")));
                } else delegate.onNext(buffers);
            }
            public void onError(Throwable error) {
                if (!done) {
                    done = true;
                    delegate.onError(error);
                }
            }
            public void onComplete() {
                if (!done) {
                    done = true;
                    delegate.onComplete();
                }
            }
        };
    }

    public record Skin(BufferedImage image, boolean slim) { }
}

package dev.leverage.launch;

import static dev.leverage.i18n.I18n.t;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AuthlibInjectorManager {
    public static final String OFFICIAL_VERSION = "1.2.8";
    public static final URI OFFICIAL_URL = URI.create(
            "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-"
            + "1.2.8.jar");
    public static final String OFFICIAL_SHA256 =
            "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";
    private static final String EMBEDDED_RESOURCE = "/agents/authlib-injector-"
            + OFFICIAL_VERSION + ".jar";

    private final Path destination;
    private final URI source;
    private final String expectedSha256;
    private final HttpClient client;

    public AuthlibInjectorManager(Path destination, URI source, String expectedSha256) {
        this(destination, source, expectedSha256,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                20)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    AuthlibInjectorManager(Path destination, URI source,
            String expectedSha256, HttpClient client) {
        this.destination = destination.toAbsolutePath().normalize();
        this.source = source;
        this.expectedSha256 = requireSha256(expectedSha256);
        this.client = client;
        if (source == null || source.getScheme() == null
                || (!source.getScheme().equalsIgnoreCase("https")
                && !source.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException(t("injector.url"));
        }
    }

    public Path ensureInstalled() throws IOException, InterruptedException {
        // Embedded and downloaded agents must pass the same digest check.
        if (Files.isRegularFile(destination) && sha256(
                destination).equals(expectedSha256)) {
            return destination;
        }
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(),
                "authlib-injector-", ".part");
        try {
            if (expectedSha256.equals(OFFICIAL_SHA256) && extractEmbedded(temporary)) {
                installVerified(temporary);
                return destination;
            }
            HttpRequest request = HttpRequest.newBuilder(source).timeout(
                    Duration.ofMinutes(2)).header("User-Agent",
                    "Leverage/0.1").GET().build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode()
                        + " descargando authlib-injector.");
            }
            try (InputStream input = response.body()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = sha256(temporary);
            if (!actual.equals(expectedSha256)) {
                throw new IOException("El SHA-256 de authlib-injector no coincide: "
                        + actual);
            }
            installVerified(temporary);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean extractEmbedded(Path temporary) throws IOException {
        try (InputStream input = AuthlibInjectorManager.class.getResourceAsStream(
                EMBEDDED_RESOURCE)) {
            if (input == null) {
                return false;
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = sha256(temporary);
        if (!actual.equals(expectedSha256)) {
            throw new IOException(
                    "El authlib-injector integrado no coincide con su SHA-256: "
                    + actual);
        }
        return true;
    }

    private void installVerified(Path temporary) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> jvmArguments(URI publicHost, String metadataJson, Side side) {
        Objects.requireNonNull(side, "side");
        if (!Files.isRegularFile(destination)) {
            throw new IllegalStateException(t("injector.notInstalled"));
        }
        String metadata = metadataJson == null ? "" : metadataJson.trim();
        if (metadata.isEmpty() || !metadata.startsWith("{") || !metadata.endsWith("}")) {
            throw new IllegalArgumentException(t("injector.metadata"));
        }
        URI api = apiRoot(publicHost);
        String prefetched = Base64.getEncoder().encodeToString(
                metadata.getBytes(StandardCharsets.UTF_8));
        return List.of("-javaagent:" + destination + "=" + api,
                "-Dauthlibinjector.yggdrasil.prefetched=" + prefetched);
    }

    public Path destination() {
        return destination;
    }

    public static URI apiRoot(URI publicHost) {
        if (publicHost == null || publicHost.getScheme() == null || publicHost.getHost()
                == null || (!publicHost.getScheme().equalsIgnoreCase("http")
                && !publicHost.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(t("injector.host"));
        }
        String value = publicHost.toString();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value + "/api/yggdrasil/");
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
        try (InputStream input = new DigestInputStream(
                Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requireSha256(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 inválido para authlib-injector.");
        }
        return normalized;
    }

    public enum Side {
        CLIENT, SERVER;

        public static Side from(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("--side debe ser client o server.");
            }
        }
    }
}

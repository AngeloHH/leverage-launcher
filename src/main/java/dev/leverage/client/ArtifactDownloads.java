package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

// Credential-free verified artifact transport shared by game and runtime installers.
final class ArtifactDownloads {
    private static final long MAX_JSON = 16L * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(
            Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    JsonObject json(URI uri, CancellationToken cancellation) throws IOException,
            InterruptedException {
        try (InputStream input = response(uri, cancellation)) {
            byte[] bytes = input.readNBytes((int) MAX_JSON + 1);
            cancellation.throwIfCancelled();
            if (bytes.length > MAX_JSON) throw new IOException(I18n.t(
                    "artifact.manifestLargeNamed", uri));
            try {
                return JsonParser.parseString(new String(bytes,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException invalid) {
                throw new IOException(I18n.t("artifact.badJson", uri), invalid);
            }
        }
    }

    // Maven sidecars are plain text; missing or malformed digests are rejected.
    String sha1(URI uri, CancellationToken cancellation) throws IOException,
            InterruptedException {
        try (InputStream input = response(uri, cancellation)) {
            byte[] bytes = input.readNBytes(1025);
            cancellation.throwIfCancelled();
            String value = new String(bytes,
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (bytes.length > 1024 || !value.matches(
                    "(?i)[a-f0-9]{40}(?:[ \\t]+\\*?[^\\r\\n]+)?"))
                    throw new IOException(I18n.t("artifact.repositoryHash", uri));
            return value.substring(0, 40).toLowerCase(java.util.Locale.ROOT);
        }
    }

    JsonObject verifiedJson(JsonObject descriptor, Path destination,
            ProgressListener progress, CancellationToken cancellation) throws IOException,
            InterruptedException {
        ensure(descriptor, destination, progress, cancellation);
        if (Files.size(destination) > MAX_JSON) throw new IOException(
                t("artifact.manifestTooLarge"));
        try (Reader reader = Files.newBufferedReader(destination)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(I18n.t("artifact.badJson",
                    destination.getFileName()), invalid);
        }
    }

    void ensure(JsonObject descriptor, Path destination, ProgressListener progress,
            CancellationToken cancellation) throws IOException, InterruptedException {
        cancellation.throwIfCancelled();
        String hash = string(descriptor, "sha1");
        if (!hash.matches("[a-fA-F0-9]{40}")) throw new IOException(t("artifact.noHash"));
        long size = descriptor.has("size") ? descriptor.get("size").getAsLong() : -1;
        if (size < -1) throw new IOException(t("artifact.invalidSize"));
        if (valid(destination, hash, size)) {
            progress.onProgress(I18n.t("progress.verified",
                    destination.getFileName().toString()),
                    Math.max(0, size), Math.max(0, size));
            return;
        }
        URI source = URI.create(string(descriptor, "url"));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = destination.resolveSibling(destination.getFileName()
                + "." + UUID.randomUUID() + ".part");
        try {
            MessageDigest digest = digest();
            long count = 0;
            try (InputStream input = response(source, cancellation);
                    OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    cancellation.throwIfCancelled();
                    count += read;
                    if ((size >= 0 && count > size) || count > 1024L * 1024 * 1024)
                            throw new IOException(I18n.t("artifact.tooLarge",
                            destination.getFileName()));
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    progress.onProgress(I18n.t("progress.downloading",
                            destination.getFileName().toString()), count, size);
                }
            }
            if ((size >= 0 && count != size) || !HexFormat.of().formatHex(
                    digest.digest()).equalsIgnoreCase(hash)) throw new IOException(
                    I18n.t("artifact.hashMismatch", destination.getFileName()));
            cancellation.throwIfCancelled();
            move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
    }

    // Materialize verified legacy resources without following existing links.
    void materialize(Path source, Path destination,
            String hash, long size, ProgressListener progress,
            CancellationToken cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (!valid(source, hash, size)) throw new IOException(
                t("artifact.sourceUnverified"));
        if (valid(destination, hash, size)) return;
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName()
                + "." + UUID.randomUUID() + ".part");
        try {
            Files.copy(source, temporary, LinkOption.NOFOLLOW_LINKS);
            cancellation.throwIfCancelled();
            if (!valid(temporary, hash, size)) throw new IOException(I18n.t(
                    "artifact.changedCopy", destination.getFileName()));
            move(temporary, destination);
            progress.onProgress(I18n.t("progress.resource",
                    destination.getFileName().toString()), size, size);
        } finally { Files.deleteIfExists(temporary); }
    }

    private InputStream response(URI uri,
            CancellationToken cancellation) throws IOException, InterruptedException {
        cancellation.throwIfCancelled();
        validateUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(
                5)).header("User-Agent", "Leverage/0.1 artifact-installer").GET().build();
        HttpResponse<InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new HttpStatusException(response.statusCode(), uri);
        }
        return response.body();
    }

    static boolean valid(Path file, String hash, long size) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || (size >= 0
                && Files.size(file) != size)) return false;
        MessageDigest digest = digest();
        try (InputStream input = new DigestInputStream(
                Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(hash);
    }
    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static String string(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || object.get(key).getAsString().isBlank())
                throw new IOException(I18n.t("artifact.missingField", key));
        return object.get(key).getAsString();
    }
    static Path safeResolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()
                || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0
                || relative.indexOf(':') >= 0 || relative.startsWith("/"))
                throw new IOException(t("artifact.invalidPath"));
        try {
            Path base = root.toAbsolutePath().normalize(),
                    result = base.resolve(relative).normalize();
            if (!result.startsWith(base) || result.equals(base))
                    throw new IOException(t("artifact.outsideRoot"));
            for (Path part : Path.of(relative)) if (part.toString().equals("..")
                    || part.toString().equals("."))
                    throw new IOException(t("artifact.invalidPath"));
            Path current = result.getRoot();
            for (Path part : result) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current) || (Files.exists(current,
                        LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(
                        current))) throw new IOException(t("artifact.noLinks"));
            }
            return result;
        } catch (InvalidPathException invalid) {
            throw new IOException(t("artifact.invalidPath"), invalid);
        }
    }
    static void validateUri(URI uri) throws IOException {
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IOException(t("artifact.invalidUrl"));
    }
    static URI append(URI base, String path) {
        return URI.create(base.toString().replaceAll("/+$",
                "") + "/" + path.replaceAll("^/+", ""));
    }
    static final class HttpStatusException extends IOException {
        private final int status;
        HttpStatusException(int status, URI uri) {
            super(I18n.t("artifact.http", status, uri));
            this.status = status;
        }
        int status() { return status; }
    }
}

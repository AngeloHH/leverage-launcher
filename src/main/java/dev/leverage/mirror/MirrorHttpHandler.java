package dev.leverage.mirror;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Map;

public final class MirrorHttpHandler implements HttpHandler {
    private static final Gson GSON = new Gson();
    private static final int BUFFER_BYTES = 64 * 1024;

    private final MirrorRepository repository;

    public MirrorHttpHandler(MirrorRepository repository) {
        this.repository = repository;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                sendError(exchange, 405, "METHOD_NOT_ALLOWED",
                        "Sólo se permiten GET y HEAD.");
                return;
            }

            String route = exchange.getRequestURI().getPath();
            MirrorRepository.Resource resource = repository.resolveManifest(route);
            if (resource == null) {
                resource = route.startsWith("/mirror/")
                        ? repository.resolve(route) : null;
            }
            if (resource == null) {
                sendError(exchange, 404, "MIRROR_FILE_NOT_FOUND",
                        "El archivo no existe en el mirror.");
                return;
            }
            serve(exchange, resource, method.equals("HEAD"));
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_MIRROR_PATH",
                    "La ruta solicitada no es válida.");
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "MIRROR_DATABASE_ERROR",
                    "No se pudo consultar el mirror.");
        } finally {
            exchange.close();
        }
    }

    private static void serve(HttpExchange exchange, MirrorRepository.Resource resource,
            boolean head) throws IOException {
        long size = resource.size();
        Range range = parseRange(exchange.getRequestHeaders().getFirst("Range"), size);
        int status = range.partial() ? 206 : 200;

        exchange.getResponseHeaders().set("Content-Type", resource.contentType());
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("ETag", "\"" + resource.sha1() + "\"");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // Blobs never change; catalogs revalidate after each mirror sync.
        exchange.getResponseHeaders().set("Cache-Control", resource.immutable()
                ? "public, max-age=31536000, immutable" : "no-cache");
        exchange.getResponseHeaders().set("Content-Length",
                Long.toString(range.length()));
        if (range.partial()) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + range.start() + "-" + range.end() + "/" + size);
        }

        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        exchange.sendResponseHeaders(status, range.length());
        try (FileChannel input = FileChannel.open(resource.file(),
                StandardOpenOption.READ);
             OutputStream output = exchange.getResponseBody()) {
            input.position(range.start());
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
            long remaining = range.length();
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer.array(), 0, read);
                remaining -= read;
            }
        }
    }

    private static Range parseRange(String header, long size) {
        if (header == null || header.isBlank()) {
            return new Range(0, size - 1, false);
        }
        if (!header.startsWith("bytes=") || header.contains(",")) {
            throw new IllegalArgumentException("Range inválido");
        }
        String value = header.substring(6).trim();
        int separator = value.indexOf('-');
        if (separator < 0) {
            throw new IllegalArgumentException("Range inválido");
        }
        try {
            long start;
            long end;
            if (separator == 0) {
                long suffixLength = Long.parseLong(value.substring(1));
                if (suffixLength <= 0) throw new IllegalArgumentException(
                        "Range inválido");
                start = Math.max(0, size - suffixLength);
                end = size - 1;
            } else {
                start = Long.parseLong(value.substring(0, separator));
                end = separator == value.length() - 1 ? size - 1
                        : Long.parseLong(value.substring(separator + 1));
            }
            if (start < 0 || end < start || start >= size) {
                throw new IllegalArgumentException("Range inválido");
            }
            return new Range(start, Math.min(end, size - 1), true);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Range inválido", exception);
        }
    }

    private static void sendError(HttpExchange exchange, int status,
            String code, String message) throws IOException {
        byte[] body = GSON.toJson(Map.of("error", Map.of("code", code, "message",
                message))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private record Range(long start, long end, boolean partial) {
        long length() {
            return end - start + 1;
        }
    }
}

package dev.leverage.skin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.leverage.service.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class YggdrasilHttpHandler implements HttpHandler {
    private static final Gson GSON = new Gson();
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String ROOT = "/api/yggdrasil";

    private final TextureService textures;
    private final YggdrasilSessionService sessions;

    public YggdrasilHttpHandler(TextureService textures) {
        this(textures, null);
    }

    public YggdrasilHttpHandler(TextureService textures,
            YggdrasilSessionService sessions) {
        this.textures = textures;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Authlib-Injector-API-Location",
                    ROOT + "/");
            route(exchange);
        } catch (ServiceException exception) {
            if (exception.code().equals("INVALID_TOKEN")) {
                sendYggdrasilError(exchange, 403, "ForbiddenOperationException",
                        "Invalid token.");
            } else {
                sendError(exchange, exception.status(), exception.code(),
                        exception.getMessage());
            }
        } catch (JsonParseException exception) {
            sendYggdrasilError(exchange, 400, "IllegalArgumentException",
                    "Invalid JSON request.");
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "INTERNAL_ERROR",
                    "No se pudo completar la solicitud.");
        } finally {
            exchange.close();
        }
    }

    // =========================================================================
    // YGGDRASIL ROUTES
    // =========================================================================

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = normalize(exchange.getRequestURI().getPath());
        if (method.equals("GET") && path.equals(ROOT)) {
            sendJson(exchange, 200, metadata());
            return;
        }

        if (method.equals("POST") && path.equals(ROOT
                + "/sessionserver/session/minecraft/join")) {
            requireSessions();
            JoinRequest request = GSON.fromJson(readBody(exchange), JoinRequest.class);
            if (request == null) {
                throw ServiceException.badRequest("INVALID_REQUEST",
                        "La solicitud no es válida.");
            }
            sessions.join(request.accessToken(), request.selectedProfile(),
                    request.serverId());
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (method.equals("GET") && path.equals(ROOT
                + "/sessionserver/session/minecraft/hasJoined")) {
            requireSessions();
            TextureService.MinecraftProfile profile = sessions.hasJoined(queryParameter(
                    exchange, "username"), queryParameter(exchange, "serverId"));
            if (profile == null) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                sendJson(exchange, 200, profileJson(profile, true));
            }
            return;
        }

        String profilePrefix = ROOT + "/sessionserver/session/minecraft/profile/";
        if (method.equals("GET") && path.startsWith(profilePrefix)) {
            UUID id = parseUuid(path.substring(profilePrefix.length()));
            TextureService.MinecraftProfile profile = textures.profileById(id, true);
            if (profile == null) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String unsignedValue = queryParameter(exchange, "unsigned");
            boolean unsigned = unsignedValue == null
                    || Boolean.parseBoolean(unsignedValue);
            sendJson(exchange, 200, profileJson(profile, !unsigned));
            return;
        }

        if (method.equals("POST") && path.equals(ROOT + "/api/profiles/minecraft")) {
            JsonElement parsed = JsonParser.parseString(readBody(exchange));
            if (!parsed.isJsonArray()) {
                throw ServiceException.badRequest("INVALID_REQUEST",
                        "Se esperaba una lista de nombres.");
            }
            JsonArray names = parsed.getAsJsonArray();
            if (names.size() > 100) {
                throw ServiceException.badRequest("TOO_MANY_PROFILES",
                        "Se permiten hasta 100 perfiles.");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonElement element : names) {
                if (!element.isJsonPrimitive()
                        || !element.getAsJsonPrimitive().isString()) {
                    throw ServiceException.badRequest("INVALID_REQUEST",
                            "Cada nombre debe ser texto.");
                }
                TextureService.MinecraftProfile profile = textures.profileByName(
                        element.getAsString());
                if (profile != null) {
                    result.add(Map.of("id", compact(profile.id()),
                            "name", profile.name()));
                }
            }
            sendJson(exchange, 200, result);
            return;
        }

        String texturePrefix = ROOT + "/textures/";
        if (method.equals("GET") && path.startsWith(texturePrefix)) {
            String hash = path.substring(texturePrefix.length());
            byte[] png = textures.readTexture(hash);
            if (png == null) {
                sendError(exchange, 404, "NOT_FOUND", "La textura no existe.");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control",
                    "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            return;
        }

        sendError(exchange, 404, "NOT_FOUND", "La ruta solicitada no existe.");
    }

    // =========================================================================
    // SIGNED PROFILE AND SERVER METADATA
    // =========================================================================

    public Map<String, Object> profileJson(TextureService.MinecraftProfile profile,
            boolean signed) {
        Map<String, Object> textureEntries = new LinkedHashMap<>();
        Map<String, Object> skin = new LinkedHashMap<>();
        skin.put("url", textures.skinUrl(profile));
        if (profile.skinModel() == TextureService.SkinModel.SLIM) {
            skin.put("metadata", Map.of("model", "slim"));
        }
        textureEntries.put("SKIN", skin);
        if (profile.capeHash() != null) {
            textureEntries.put("CAPE", Map.of("url",
                    textures.textureUrl(profile.capeHash())));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", textures.now().toEpochMilli());
        payload.put("profileId", compact(profile.id()));
        payload.put("profileName", profile.name());
        payload.put("textures", textureEntries);
        String value = Base64.getEncoder().encodeToString(GSON.toJson(
                payload).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> property = new LinkedHashMap<>();
        property.put("name", "textures");
        property.put("value", value);
        if (signed) {
            property.put("signature", textures.sign(value));
        }
        return Map.of("id", compact(profile.id()), "name", profile.name(),
                "properties", List.of(property));
    }

    private Map<String, Object> metadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("serverName", "Leverage");
        meta.put("implementationName", "Leverage");
        meta.put("implementationVersion", "0.1.0");
        return Map.of("meta", meta, "skinDomains", List.of(textures.skinDomain()),
                "signaturePublickey", textures.publicKeyPem());
    }

    // =========================================================================
    // REQUEST VALUES AND BODY LIMITS
    // =========================================================================

    private void requireSessions() {
        if (sessions == null) {
            throw new ServiceException(503, "SESSIONS_DISABLED",
                    "El servicio de sesiones no está configurado.");
        }
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (key.equalsIgnoreCase(name)) {
                return parts.length == 1 ? "" : java.net.URLDecoder.decode(parts[1],
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(
                Locale.ROOT).startsWith("application/json")) {
            throw new ServiceException(415, "JSON_REQUIRED",
                    "Content-Type debe ser application/json.");
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new ServiceException(413, "BODY_TOO_LARGE",
                        "La solicitud es demasiado grande.");
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static UUID parseUuid(String value) {
        String clean = value.trim();
        if (clean.matches("[0-9a-fA-F]{32}")) {
            clean = clean.substring(0, 8) + "-" + clean.substring(8,
                    12) + "-" + clean.substring(12, 16) + "-" + clean.substring(16,
                    20) + "-" + clean.substring(20);
        }
        try {
            return UUID.fromString(clean);
        } catch (IllegalArgumentException exception) {
            throw ServiceException.badRequest("INVALID_PROFILE_ID",
                    "El UUID del perfil no es válido.");
        }
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String normalize(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0,
                path.length() - 1) : path;
    }

    // =========================================================================
    // PROTOCOL ERROR AND JSON RESPONSES
    // =========================================================================

    private static void sendError(HttpExchange exchange, int status,
            String code, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", Map.of("code",
                code, "message", message)));
    }

    private static void sendYggdrasilError(HttpExchange exchange, int status,
            String error, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", error, "errorMessage", message));
    }

    private static void sendJson(HttpExchange exchange, int status,
            Object value) throws IOException {
        byte[] body = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private record JoinRequest(String accessToken,
            String selectedProfile, String serverId) { }
}

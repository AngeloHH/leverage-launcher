package dev.leverage.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.leverage.domain.Permission;
import dev.leverage.domain.Role;
import dev.leverage.domain.User;
import dev.leverage.mirror.MirrorHttpHandler;
import dev.leverage.mirror.MirrorRepository;
import dev.leverage.service.AuthService;
import dev.leverage.service.AdminUserService;
import dev.leverage.service.LauncherVersionService;
import dev.leverage.service.ModArtifactService;
import dev.leverage.service.ModPolicyService;
import dev.leverage.service.ServiceException;
import dev.leverage.skin.TextureService;
import dev.leverage.skin.YggdrasilHttpHandler;
import dev.leverage.skin.YggdrasilSessionService;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class ApiServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final Gson GSON = new Gson();
    private static final Gson HASH_RESPONSE_GSON = new com.google
            .gson.GsonBuilder().serializeNulls().create();

    private final HttpServer server;
    private final AuthService auth;
    private final AdminUserService adminUsers;
    private final dev.leverage.service.NewsService news;
    private final RateLimiter rateLimiter;
    private final TextureService textures;
    private final ModPolicyService modPolicies;
    private final LauncherVersionService launcherVersions;
    private final ModArtifactService modArtifacts;

    public ApiServer(String bindAddress, int port, AuthService auth,
            Clock clock) throws IOException {
        this(bindAddress, port, auth, clock, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth, Clock clock,
            MirrorRepository mirrorRepository) throws IOException {
        this(bindAddress, port, auth, clock, mirrorRepository, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth,
            Clock clock, MirrorRepository mirrorRepository,
            TextureService textures) throws IOException {
        this(bindAddress, port, auth, clock, mirrorRepository, textures, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth, Clock clock,
            MirrorRepository mirrorRepository, TextureService textures,
            YggdrasilSessionService yggdrasilSessions) throws IOException {
        this(bindAddress, port, auth, clock, mirrorRepository,
                textures, yggdrasilSessions, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth,
            Clock clock, MirrorRepository mirrorRepository,
            TextureService textures, YggdrasilSessionService yggdrasilSessions,
            ModPolicyService modPolicies) throws IOException {
        this(bindAddress, port, auth, clock, mirrorRepository, textures,
                yggdrasilSessions, modPolicies, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth, Clock clock,
            MirrorRepository mirrorRepository, TextureService textures,
            YggdrasilSessionService yggdrasilSessions, ModPolicyService modPolicies,
            LauncherVersionService launcherVersions) throws IOException {
        this(bindAddress, port, auth, clock, mirrorRepository, textures,
                yggdrasilSessions, modPolicies, launcherVersions, null);
    }

    public ApiServer(String bindAddress, int port, AuthService auth,
            Clock clock, MirrorRepository mirrorRepository,
            TextureService textures, YggdrasilSessionService yggdrasilSessions,
            ModPolicyService modPolicies, LauncherVersionService launcherVersions,
            ModArtifactService modArtifacts) throws IOException {
        this.auth = auth;
        this.adminUsers = auth.administrativeUsers();
        this.news = auth.news();
        this.textures = textures;
        this.modPolicies = modPolicies;
        this.launcherVersions = launcherVersions;
        this.modArtifacts = modArtifacts;
        this.rateLimiter = new RateLimiter(clock);
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.createContext("/api/v1", this::handle);
        this.server.createContext("/mod", this::handle);
        this.server.createContext("/resourcepack", this::handle);
        if (mirrorRepository != null) {
            MirrorHttpHandler mirrorHandler = new MirrorHttpHandler(mirrorRepository);
            this.server.createContext("/mirror", mirrorHandler);
            this.server.createContext("/game", mirrorHandler);
        }
        if (textures != null) {
            this.server.createContext("/api/yggdrasil",
                    new YggdrasilHttpHandler(textures, yggdrasilSessions));
        }
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
    }

    // =========================================================================
    // REQUEST LIFECYCLE AND ROUTING
    // =========================================================================

    private void handle(HttpExchange exchange) throws IOException {
        try {
            addSecurityHeaders(exchange);
            route(exchange);
        } catch (ServiceException exception) {
            sendError(exchange, exception.status(), exception.code(),
                    exception.getMessage());
        } catch (JsonParseException exception) {
            sendError(exchange, 400, "INVALID_JSON", "El cuerpo JSON no es válido.");
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, "INVALID_REQUEST", "La solicitud no es válida.");
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "INTERNAL_ERROR",
                    "No se pudo completar la solicitud.");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(exchange.getRequestURI().getPath());

        if (method.equals("GET")&&path.equals("/api/v1/news")) {
            authenticate(exchange);
            sendJson(exchange, 200, Map.of("news", news.list(null, false)));
            return;
        }
        if (method.equals("GET")&&path.equals("/api/v1/admin/news")) {
            sendJson(exchange, 200, Map.of("news",
                    news.list(authenticate(exchange), true)));
            return;
        }
        if (path.startsWith("/api/v1/admin/news/")) {
            var actor = authenticate(exchange);
            if (!actor.permissions().contains(Permission.USERS_MODERATE))
                    throw ServiceException.forbidden("NEWS_EDITOR_REQUIRED",
                    "Solo administradores y moderadores pueden crear o "
                    + "editar noticias.");
            String tail = path.substring("/api/v1/admin/news/".length());
            boolean publish = tail.endsWith("/publish");
            UUID id = UUID.fromString(publish?tail.substring(0, tail.length()-8):tail);
            if (!publish&&method.equals("DELETE")) {
                limit(exchange, "news-edit", 40, Duration.ofMinutes(1));
                news.delete(actor, id);
                sendJson(exchange, 200, Map.of("status", "deleted"));
                return;
            }
            if (publish&&method.equals("POST")) {
                limit(exchange, "news-edit", 40, Duration.ofMinutes(1));
                sendJson(exchange, 200, Map.of("article", news.publish(actor, id)));
                return;
            }
            if (!publish&& (method.equals("PUT")||method.equals("POST"))) {
                limit(exchange, "news-edit", 40, Duration.ofMinutes(1));
                NewsRequest request = readJson(exchange, NewsRequest.class, 1024*1024);
                sendJson(exchange, 200, Map.of("article", news.save(actor,
                        id, method.equals("POST"), request.markdown(),
                        request.imageBase64())));
                return;
            }
        }

        if (method.equals("GET") && path.equals("/api/v1/health")) {
            sendJson(exchange, 200, Map.of("status", "ok", "service",
                    "leverage", "version", "0.1.0"));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/launcher/check")) {
            requireLauncherVersions();
            limit(exchange, "launcher-check", 60, Duration.ofMinutes(1));
            LauncherCheckRequest request = readJson(exchange, LauncherCheckRequest.class);
            sendJson(exchange, 200, Map.of("launcher", launcherCheckJson(
                    launcherVersions.check(request.channel(), request.sha1()))));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/register")) {
            limit(exchange, "register", 5, Duration.ofHours(1));
            RegisterRequest request = readJson(exchange, RegisterRequest.class);
            User user = auth.register(request.username(),
                    request.email(), request.password());
            sendJson(exchange, 201, Map.of("user", userJson(user),
                    "emailVerificationRequired", true));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/verify-email/request")) {
            limit(exchange, "verify", 5, Duration.ofMinutes(15));
            EmailRequest request = readJson(exchange, EmailRequest.class);
            auth.requestEmailVerification(request.email());
            sendJson(exchange, 202, genericDeliveryResponse());
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/verify-email/confirm")) {
            limit(exchange, "verify-confirm", 10, Duration.ofMinutes(15));
            EmailVerificationRequest request = readJson(exchange,
                    EmailVerificationRequest.class);
            User user = request.code() != null ? auth.confirmEmailCode(request.email(),
                    request.code()) : auth.confirmEmail(request.token());
            sendJson(exchange, 200, Map.of("user", userJson(user)));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/login")) {
            limit(exchange, "login", 10, Duration.ofMinutes(1));
            LoginRequest request = readJson(exchange, LoginRequest.class);
            AuthService.LoginResult result = auth.login(request.identifier(),
                    request.password(), Boolean.TRUE.equals(request.rememberDevice()),
                    request.deviceName());
            sendJson(exchange, 200, loginJson(result));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/refresh")) {
            limit(exchange, "refresh", 30, Duration.ofMinutes(1));
            RefreshRequest request = readJson(exchange, RefreshRequest.class);
            sendJson(exchange, 200, loginJson(auth.refresh(request.refreshToken())));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/logout")) {
            auth.logout(bearer(exchange));
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/forget-device")) {
            limit(exchange, "forget-device", 20, Duration.ofMinutes(1));
            RefreshRequest request = readJson(exchange, RefreshRequest.class);
            auth.revokeDeviceByRefreshToken(request.refreshToken());
            sendJson(exchange, 200, Map.of("status", "device_revoked"));
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/auth/devices")) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            sendJson(exchange, 200, Map.of("devices", auth.listDeviceSessions(
                    actor).stream().map(ApiServer::deviceJson).toList()));
            return;
        }
        UUID deviceSessionId = idFromPath(path, "/api/v1/auth/devices/");
        if (method.equals("DELETE") && deviceSessionId != null) {
            auth.revokeDeviceSession(authenticate(exchange), deviceSessionId);
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/password/forgot")) {
            limit(exchange, "forgot", 5, Duration.ofMinutes(15));
            EmailRequest request = readJson(exchange, EmailRequest.class);
            auth.requestPasswordReset(request.email());
            sendJson(exchange, 202, genericDeliveryResponse());
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/password/reset")) {
            PasswordResetRequest request = readJson(exchange, PasswordResetRequest.class);
            auth.confirmPasswordReset(request.token(), request.newPassword());
            sendJson(exchange, 200, Map.of("status", "password_updated"));
            return;
        }
        if (method.equals("PUT") && path.equals("/api/v1/me/username")) {
            limit(exchange, "username-change", 10, Duration.ofMinutes(15));
            UsernameChangeRequest request = readJson(exchange,
                    UsernameChangeRequest.class);
            sendJson(exchange, 200, Map.of("user",
                    userJson(auth.changeUsername(authenticate(exchange),
                    request.username(), request.currentPassword()))));
            return;
        }
        if (method.equals("PUT") && path.equals("/api/v1/me/password")) {
            limit(exchange, "password-change", 5, Duration.ofMinutes(15));
            PasswordChangeRequest request = readJson(exchange,
                    PasswordChangeRequest.class);
            auth.changePassword(authenticate(exchange),
                    request.currentPassword(), request.newPassword());
            sendJson(exchange, 200, Map.of("status", "password_updated"));
            return;
        }
        if (method.equals("PUT") && path.equals("/api/v1/me/email")) {
            limit(exchange, "email-change", 5, Duration.ofMinutes(15));
            EmailChangeRequest request = readJson(exchange, EmailChangeRequest.class);
            auth.requestEmailChange(authenticate(exchange),
                    request.newEmail(), request.currentPassword());
            sendJson(exchange, 202, Map.of("status", "confirmation_sent"));
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/me")) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            sendJson(exchange, 200, authenticatedJson(actor));
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/me/minecraft-profile")) {
            requireTextures();
            sendJson(exchange, 200, Map.of("profile",
                    profileJson(textures.profileFor(authenticate(exchange)))));
            return;
        }
        TextureService.TextureType textureType = textureTypeFromPath(path);
        if (textureType != null && method.equals("PUT")) {
            requireTextures();
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            TextureService.SkinModel model = textureType ==
                    TextureService.TextureType.SKIN ? TextureService.SkinModel.from(
                    queryParameter(exchange, "model")) : TextureService.SkinModel.CLASSIC;
            byte[] png = readPng(exchange);
            sendJson(exchange, 200, Map.of("profile", profileJson(textures.upload(actor,
                    textureType, model, png))));
            return;
        }
        if (textureType != null && method.equals("DELETE")) {
            requireTextures();
            sendJson(exchange, 200, Map.of("profile", profileJson(textures.clear(
                    authenticate(exchange), textureType))));
            return;
        }
        ModFilePath directModFile = modFilePathFromPath(path, "/");
        if (method.equals("PUT") && directModFile != null) {
            requireModArtifacts();
            requireArtifactContentType(exchange);
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            try (InputStream input = exchange.getRequestBody()) {
                sendJson(exchange, 201, Map.of("file", modArtifacts.publish(actor,
                        input, parseContentLength(exchange), directModFile.contentType(),
                        directModFile.minecraftVersion(), directModFile.loader(),
                        directModFile.modId(), directModFile.modVersion(),
                        directModFile.artifactType())));
            }
            return;
        }
        if (method.equals("GET") && directModFile != null) {
            requireModArtifacts();
            authenticate(exchange);
            sendPublishedMod(exchange, modArtifacts.download(directModFile.contentType(),
                    directModFile.minecraftVersion(), directModFile.loader(),
                    directModFile.modId(), directModFile.modVersion(),
                    directModFile.artifactType()));
            return;
        }
        String policyHash = valueFromPath(path, "/api/v1/admin/mod-policies/");
        if (method.equals("PUT") && policyHash != null) {
            requireModArtifacts();
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            ModPolicyRequest request = readJson(exchange, ModPolicyRequest.class);
            if (request.allowed() == null) {
                throw ServiceException.badRequest("ALLOWED_REQUIRED",
                        "El campo 'allowed' es obligatorio.");
            }
            sendJson(exchange, 200, Map.of("policy", modArtifacts.setRule(actor,
                    policyHash, request.server(), request.minecraftVersion(),
                    request.loader(), request.allowed(),
                    Boolean.TRUE.equals(request.required()), request.reason(),
                    ModArtifactService.ContentType.from(request.type()))));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/admin/mods")) {
            requireModArtifacts();
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            requireJarContentType(exchange);
            long contentLength = parseContentLength(exchange);
            boolean required = Boolean.parseBoolean(queryParameter(exchange, "required"));
            boolean allowed = requiredBooleanQuery(exchange, "allowed");
            try (InputStream input = exchange.getRequestBody()) {
                sendJson(exchange, 201, Map.of("mod", modArtifacts.upload(actor,
                        input, contentLength, queryParameter(exchange, "server"),
                        queryParameter(exchange, "version"), queryOrDefault(exchange,
                        "loader", "default"), allowed, queryParameter(exchange,
                        "name"), queryParameter(exchange, "fileName"),
                        required, queryParameter(exchange, "reason"))));
            }
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/game/mods/catalog")) {
            requireModArtifacts();
            authenticate(exchange);
            sendJson(exchange, 200, Map.of("catalog", publicCatalogJson(
                    modArtifacts.catalog(queryParameter(exchange, "server"),
                    queryParameter(exchange, "version"), queryOrDefault(exchange,
                    "loader", "default"), ModArtifactService.ContentType.from(
                    queryParameter(exchange, "type"))))));
            return;
        }
        String requestedModHash = modHashFromPath(path);
        if (method.equals("GET") && requestedModHash != null) {
            requireModArtifacts();
            authenticate(exchange);
            sendMod(exchange, modArtifacts.download(requestedModHash,
                    queryParameter(exchange, "server"), queryParameter(exchange,
                    "version"), queryOrDefault(exchange, "loader", "default")));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/game/mods/verify")) {
            requireModPolicies();
            JsonElement payload = readJson(exchange, JsonElement.class);
            boolean single = payload.isJsonPrimitive()
                    && payload.getAsJsonPrimitive().isString();
            List<String> hashes = hashesFromPayload(payload);
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            String serverScope = queryParameter(exchange, "server");
            String minecraftVersion = queryParameter(exchange, "version");
            String loader = queryOrDefault(exchange, "loader", "default");
            ModPolicyService.Verification verification = single
                    ? modPolicies.checkHash(actor, serverScope, minecraftVersion,
                    loader, hashes.getFirst()) : modPolicies.verifyInventoryHashes(actor,
                    serverScope, minecraftVersion, loader, hashes);
            int status = switch (verification.status()) {
                case "APPROVED", "ALLOWED" -> 200;
                case "REJECTED", "UNKNOWN" -> 409;
                case "SUSPENDED" -> 403;
                default -> 500;
            };
            sendJson(exchange, status, hashDecisionsJson(verification),
                    HASH_RESPONSE_GSON);
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/game/tickets")) {
            GameTicketRequest request = readJson(exchange, GameTicketRequest.class);
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            if (modPolicies != null) {
                ModPolicyService.Verification verification = modPolicies
                        .verifyInventoryHashes(actor, request.server(),
                        request.version(), request.loader() == null ? "default"
                        : request.loader(), request.sha1());
                if (!verification.status().equals("APPROVED")) {
                    sendJson(exchange, verification.status().equals("SUSPENDED") ? 403
                            : 409, hashDecisionsJson(verification), HASH_RESPONSE_GSON);
                    return;
                }
            }
            AuthService.GameTicket ticket = auth.createGameTicket(actor,
                    request.server());
            sendJson(exchange, 201, Map.of("ticket", ticket.token(), "server",
                    ticket.server(), "expiresAt", ticket.expiresAt().toString()));
            return;
        }
        if (method.equals("POST") && path.equals("/api/v1/game/tickets/consume")) {
            limit(exchange, "game-ticket", 60, Duration.ofMinutes(1));
            GameTicketConsumeRequest request = readJson(exchange,
                    GameTicketConsumeRequest.class);
            AuthService.GameIdentity identity = auth.consumeGameTicket(
                    request.ticket(), request.server());
            sendJson(exchange, 200, Map.of("userId", identity.userId().toString(),
                    "username", identity.username(), "role",
                    identity.role().name(), "server", identity.server()));
            return;
        }
        if (method.equals("GET") && path.equals("/api/v1/admin/users")) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            Boolean suspended = optionalBooleanQuery(exchange, "suspended");
            if (queryParameter(exchange, "q") != null || queryParameter(exchange,
                    "page") != null || queryParameter(exchange,
                    "pageSize") != null || queryParameter(exchange, "sort") != null
                    || queryParameter(exchange, "direction") != null) {
                var result = auth.searchUsers(actor, queryParameter(exchange, "q"),
                        integerQuery(exchange, "page", 1), integerQuery(exchange,
                        "pageSize", 25), suspended, queryParameter(exchange,
                        "sort"), queryParameter(exchange, "direction"));
                sendJson(exchange, 200, Map.of("users", adminUsers.details(actor,
                        result.users()).stream().map(ApiServer::adminUserJson).toList(),
                        "page", result.page(), "pageSize", result.pageSize(), "total",
                        result.total(), "totalPages", result.totalPages()));
                return;
            }
            List<Map<String, Object>> users = adminUsers.details(actor, auth.listUsers(
                    actor, suspended)).stream().map(ApiServer::adminUserJson).toList();
            sendJson(exchange, 200, Map.of("users", users));
            return;
        }

        UUID target = userIdFromPath(path, "/ban");
        boolean datedBan = false;
        if (target == null) {
            target = userIdFromPath(path, "/ban-until");
            datedBan = target != null;
        }
        if (method.equals("PUT") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            limit(exchange, "admin-ban", 30, Duration.ofMinutes(15));
            BanRequest request = readJson(exchange, BanRequest.class);
            java.time.Instant end = null;
            try {
                if (request.expiresAt() != null)end = java.time.Instant.parse(
                        request.expiresAt());
            } catch (java.time.DateTimeException invalid) {
                throw ServiceException.badRequest("INVALID_BAN_EXPIRY",
                        "La fecha de fin no es válida.");
            }
            if (datedBan && end == null)throw ServiceException.badRequest(
                    "INVALID_BAN_EXPIRY", "La fecha de fin es obligatoria.");
            sendJson(exchange, 200, Map.of("user", userJson(adminUsers.ban(actor,
                    target, request.reason(), end))));
            return;
        }
        target = userIdFromPath(path, "/skin");
        if (method.equals("DELETE") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            limit(exchange, "admin-clear-skin", 60, Duration.ofMinutes(15));
            sendJson(exchange, 200, Map.of("user",
                    userJson(adminUsers.clearSkin(actor, target))));
            return;
        }
        target = userIdFromPath(path, "/password");
        if (method.equals("PUT") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            limit(exchange, "admin-password", 30, Duration.ofMinutes(15));
            AdminPasswordRequest request = readJson(exchange, AdminPasswordRequest.class);
            sendJson(exchange, 200, Map.of("user", userJson(adminUsers.changePassword(
                    actor, target, request.password()))));
            return;
        }
        target = userIdFromPath(path, "/email");
        if (method.equals("PUT") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            limit(exchange, "admin-email", 30, Duration.ofMinutes(15));
            EmailRequest request = readJson(exchange, EmailRequest.class);
            sendJson(exchange, 200, Map.of("user", userJson(adminUsers.changeEmail(actor,
                    target, request.email()))));
            return;
        }

        target = userIdFromPath(path, "/role");
        if (method.equals("PUT") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            RoleRequest request = readJson(exchange, RoleRequest.class);
            if (request.role() == null) {
                throw ServiceException.badRequest("MISSING_FIELD",
                        "El campo 'role' es obligatorio.");
            }
            Role role;
            try {
                role = Role.valueOf(request.role().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw ServiceException.badRequest("INVALID_ROLE",
                        "El rol debe ser USER, MODERATOR o ADMIN.");
            }
            sendJson(exchange, 200, Map.of("user",
                    userJson(auth.changeRole(actor, target, role))));
            return;
        }

        target = userIdFromPath(path, "/suspension");
        if (method.equals("PUT") && target != null) {
            AuthService.AuthenticatedUser actor = authenticate(exchange);
            SuspensionRequest request = readJson(exchange, SuspensionRequest.class);
            if (request.resolution() != null) {
                if (request.suspended() != null) {
                    throw ServiceException.badRequest("AMBIGUOUS_SUSPENSION_ACTION",
                            "Use 'suspended' o 'resolution', pero no ambos.");
                }
                requireModPolicies();
                if (request.suspensionId() == null || request.suspensionId().isBlank()) {
                    throw ServiceException.badRequest("MISSING_FIELD",
                            "El campo 'suspensionId' es obligatorio para "
                            + "resolver una suspensión.");
                }
                UUID suspensionId;
                try {
                    suspensionId = UUID.fromString(request.suspensionId());
                } catch (IllegalArgumentException exception) {
                    throw ServiceException.badRequest("INVALID_SUSPENSION_ID",
                            "El identificador de suspensión no es válido.");
                }
                ModPolicyService.Resolution resolution;
                try {
                    resolution = ModPolicyService.Resolution.valueOf(
                            request.resolution().trim().toUpperCase(Locale.ROOT));
                } catch (RuntimeException exception) {
                    throw ServiceException.badRequest("INVALID_RESOLUTION",
                            "La resolución debe ser LIFT o BAN.");
                }
                sendJson(exchange, 200, Map.of("suspension",
                        suspensionJson(modPolicies.resolveForUser(actor, target,
                        suspensionId, resolution, request.reason()))));
                return;
            }
            if (request.suspended() == null) {
                throw ServiceException.badRequest("MISSING_FIELD",
                        "El campo 'suspended' o 'resolution' es obligatorio.");
            }
            sendJson(exchange, 200, Map.of("user", userJson(auth.setSuspended(actor,
                    target, request.suspended()))));
            return;
        }

        sendError(exchange, 404, "NOT_FOUND", "La ruta solicitada no existe.");
    }

    // =========================================================================
    // AUTHENTICATION AND OPTIONAL SERVER FEATURES
    // =========================================================================

    private AuthService.AuthenticatedUser authenticate(HttpExchange exchange) {
        return auth.authenticate(bearer(exchange));
    }

    private void requireTextures() {
        if (textures == null) {
            throw new ServiceException(503, "TEXTURES_DISABLED",
                    "El servicio de texturas no está configurado.");
        }
    }

    private void requireModPolicies() {
        if (modPolicies == null) {
            throw new ServiceException(503, "MOD_POLICIES_DISABLED",
                    "El servicio de políticas de mods no está configurado.");
        }
    }

    private void requireLauncherVersions() {
        if (launcherVersions == null) {
            throw new ServiceException(503, "LAUNCHER_VERSIONS_DISABLED",
                    "El servicio de versiones del launcher no está configurado.");
        }
    }

    private void requireModArtifacts() {
        if (modArtifacts == null) {
            throw new ServiceException(503, "MOD_STORAGE_DISABLED",
                    "El almacenamiento de mods no está configurado.");
        }
    }

    // =========================================================================
    // PATH SEGMENTS AND AUTHORIZATION HEADERS
    // =========================================================================

    private static TextureService.TextureType textureTypeFromPath(String path) {
        String prefix = "/api/v1/me/textures/";
        if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) {
            return null;
        }
        String type = path.substring(prefix.length());
        if (!type.equals("skin") && !type.equals("cape")) {
            return null;
        }
        return TextureService.TextureType.from(type);
    }

    private static String bearer(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.regionMatches(true,
                0, "Bearer ", 0, 7)) {
            throw ServiceException.unauthorized("AUTHENTICATION_REQUIRED",
                    "Se requiere una sesión válida.");
        }
        String token = authorization.substring(7).trim();
        if (token.isBlank()) {
            throw ServiceException.unauthorized("AUTHENTICATION_REQUIRED",
                    "Se requiere una sesión válida.");
        }
        return token;
    }

    private void limit(HttpExchange exchange, String action,
            int maximum, Duration duration) {
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!rateLimiter.allow(action + ":" + address, maximum, duration)) {
            throw new ServiceException(429, "RATE_LIMITED",
                    "Demasiados intentos; inténtalo más tarde.");
        }
    }

    private static UUID userIdFromPath(String path, String suffix) {
        String prefix = "/api/v1/admin/users/";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String id = path.substring(prefix.length(), path.length() - suffix.length());
        if (id.endsWith("/")) {
            id = id.substring(0, id.length() - 1);
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw ServiceException.badRequest("INVALID_USER_ID",
                    "El identificador de usuario no es válido.");
        }
    }

    private static UUID idFromPath(String path, String prefix) {
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            return null;
        }
        String value = path.substring(prefix.length());
        if (value.contains("/")) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw ServiceException.badRequest("INVALID_ID",
                    "El identificador no es válido.");
        }
    }

    private static String modHashFromPath(String path) {
        String prefix = "/api/v1/game/mods/files/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String hash = path.substring(prefix.length());
        return hash.contains("/") || hash.isBlank() ? null : hash;
    }

    private static ModFilePath modFilePathFromPath(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return null;
        }
        String[] parts = path.substring(prefix.length()).split("/", -1);
        if (parts.length == 5 && parts[0].equals("resourcepack")) {
            return new ModFilePath(ModArtifactService.ContentType.RESOURCEPACK,
                    parts[1], "default", parts[2], parts[3], parts[4]);
        }
        if (parts.length != 6 || !parts[0].equals("mod")) {
            return null;
        }
        return new ModFilePath(ModArtifactService.ContentType.MOD,
                parts[1], parts[2], parts[3], parts[4], parts[5]);
    }

    private static String valueFromPath(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return null;
        }
        String value = path.substring(prefix.length());
        return value.isBlank() || value.contains("/") ? null : value;
    }

    private static String normalizePath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    // =========================================================================
    // BOUNDED REQUEST BODIES
    // =========================================================================

    private static <T> T readJson(HttpExchange exchange,
            Class<T> type) throws IOException {
        return readJson(exchange, type, MAX_BODY_BYTES);
    }

    private static <T> T readJson(HttpExchange exchange,
            Class<T> type, int limit) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(
                Locale.ROOT).startsWith("application/json")) {
            throw new ServiceException(415, "JSON_REQUIRED",
                    "Content-Type debe ser application/json.");
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(limit + 1);
            if (body.length > limit) {
                throw new ServiceException(413, "BODY_TOO_LARGE",
                        "El cuerpo de la solicitud es demasiado grande.");
            }
            T parsed = GSON.fromJson(new String(body, StandardCharsets.UTF_8), type);
            if (parsed == null) {
                throw ServiceException.badRequest("INVALID_JSON",
                        "El cuerpo JSON no es válido.");
            }
            return parsed;
        }
    }

    private static byte[] readPng(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(
                Locale.ROOT).startsWith("image/png")) {
            throw new ServiceException(415, "PNG_REQUIRED",
                    "Content-Type debe ser image/png.");
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(TextureService.MAX_TEXTURE_BYTES + 1);
            if (body.length > TextureService.MAX_TEXTURE_BYTES) {
                throw new ServiceException(413, "TEXTURE_TOO_LARGE",
                        "La textura debe pesar hasta 2 MiB.");
            }
            return body;
        }
    }

    private static void requireJarContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !(contentType.toLowerCase(Locale.ROOT).startsWith(
                "application/java-archive") || contentType.toLowerCase(
                Locale.ROOT).startsWith("application/octet-stream"))) {
            throw new ServiceException(415, "MOD_JAR_REQUIRED",
                    "Content-Type debe ser application/java-archive o "
                    + "application/octet-stream.");
        }
    }

    private static void requireArtifactContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !(contentType.toLowerCase(Locale.ROOT).startsWith(
                "application/java-archive") || contentType.toLowerCase(
                Locale.ROOT).startsWith("application/zip") || contentType.toLowerCase(
                Locale.ROOT).startsWith("application/octet-stream"))) {
            throw new ServiceException(415, "BINARY_FILE_REQUIRED",
                    ("Content-Type debe ser application/java-archive, "
                    + "application/zip ") + "o application/octet-stream.");
        }
    }

    // =========================================================================
    // QUERY VALUES AND CONTENT LENGTH
    // =========================================================================

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equalsIgnoreCase(name)) {
                return parts.length == 1 ? "" : java.net.URLDecoder.decode(parts[1],
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String queryOrDefault(HttpExchange exchange,
            String name, String fallback) {
        String value = queryParameter(exchange, name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int integerQuery(HttpExchange exchange, String name, int fallback) {
        String value = queryParameter(exchange, name);
        if (value == null) return fallback;
        try {
            if (!value.matches("-?[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ServiceException.badRequest("INVALID_PAGINATION",
                    "El parámetro '" + name + "' debe ser un entero válido.");
        }
    }

    private static List<String> hashesFromPayload(JsonElement payload) {
        if (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()) {
            return List.of(payload.getAsString());
        }
        if (!payload.isJsonArray()) {
            throw ServiceException.badRequest("INVALID_MOD_CHECK",
                    "El cuerpo debe ser un SHA-1 JSON o un arreglo de SHA-1.");
        }
        List<String> hashes = new java.util.ArrayList<>();
        for (JsonElement element : payload.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw ServiceException.badRequest("INVALID_MOD_CHECK",
                        "Todos los elementos deben ser SHA-1 en formato de texto.");
            }
            hashes.add(element.getAsString());
        }
        return List.copyOf(hashes);
    }

    private static boolean requiredBooleanQuery(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null || !(value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("false"))) {
            throw ServiceException.badRequest("INVALID_" + name.toUpperCase(Locale.ROOT),
                    "El parámetro '" + name + "' debe ser true o false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static Boolean optionalBooleanQuery(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null) {
            return null;
        }
        if (!(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            throw ServiceException.badRequest("INVALID_" + name.toUpperCase(Locale.ROOT),
                    "El parámetro '" + name + "' debe ser true o false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static long parseContentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ServiceException.badRequest("INVALID_CONTENT_LENGTH",
                    "Content-Length no es válido.");
        }
    }

    // =========================================================================
    // RESPONSE HEADERS AND FILE DELIVERY
    // =========================================================================

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    private static void sendError(HttpExchange exchange, int status,
            String code, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", Map.of("code",
                code, "message", message)));
    }

    private static void sendJson(HttpExchange exchange, int status,
            Object value) throws IOException {
        sendJson(exchange, status, value, GSON);
    }

    private static void sendJson(HttpExchange exchange, int status,
            Object value, Gson serializer) throws IOException {
        byte[] body = serializer.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sendMod(HttpExchange exchange,
            ModArtifactService.Download download) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + download.fileName().replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("X-Content-SHA1", download.sha1());
        exchange.sendResponseHeaders(200, download.size());
        Files.copy(download.path(), exchange.getResponseBody());
    }

    private static void sendPublishedMod(HttpExchange exchange,
            ModArtifactService.PublishedDownload download) throws IOException {
        String contentType = download.fileName().endsWith(".zip")
                ? "application/zip" : switch (download.artifactType()) {
            case "sourcecode", "javadoc" -> "application/zip";
            default -> "application/java-archive";
        };
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + download.fileName().replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("X-Content-SHA1", download.sha1());
        exchange.getResponseHeaders().set("X-Leverage-Artifact-Type",
                download.artifactType());
        exchange.sendResponseHeaders(200, download.size());
        Files.copy(download.path(), exchange.getResponseBody());
    }

    // =========================================================================
    // PUBLIC RESPONSE SHAPES
    // =========================================================================

    private static Map<String, Object> genericDeliveryResponse() {
        return Map.of("accepted", true, "message",
                "Si la cuenta existe y aplica, se enviaron las " + "instrucciones.");
    }

    private static Map<String, Object> userJson(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.id().toString());
        result.put("username", user.username());
        result.put("email", user.email());
        result.put("role", user.role().name());
        result.put("status", user.status().name());
        result.put("createdAt", user.createdAt().toString());
        result.put("updatedAt", user.updatedAt().toString());
        return result;
    }

    private static Map<String, Object> adminUserJson(AdminUserService.Details details) {
        Map<String, Object> result = userJson(details.user());
        result.put("lastSeenAt", details.lastSeenAt() == null ? null
                : details.lastSeenAt().toString());
        result.put("banned", details.banned());
        result.put("restrictionReason", details.restrictionReason());
        result.put("skinHash", details.skinHash());
        return result;
    }

    private static Map<String, Object> loginJson(AuthService.LoginResult result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("accessToken", result.accessToken());
        json.put("tokenType", "Bearer");
        json.put("expiresAt", result.expiresAt().toString());
        if (result.refreshToken() != null) {
            json.put("refreshToken", result.refreshToken());
            json.put("refreshExpiresAt", result.refreshExpiresAt().toString());
            json.put("deviceSessionId", result.deviceSessionId());
        }
        json.put("user", userJson(result.user()));
        json.put("permissions", result.permissions().stream().map(
                Permission::name).sorted().toList());
        return json;
    }

    private static Map<String, Object> deviceJson(AuthService.DeviceSession device) {
        return Map.of("id", device.id(), "deviceName", device.deviceName(), "createdAt",
                device.createdAt().toString(), "expiresAt", device.expiresAt().toString(),
                "lastUsedAt", device.lastUsedAt().toString());
    }

    private static Map<String, Object> launcherCheckJson(
            LauncherVersionService.CheckResult check) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", check.status().name());
        result.put("canContinue", check.canContinue());
        result.put("recognized", check.recognized());
        result.put("currentVersion", check.currentVersion());
        result.put("latestVersion", check.latestVersion());
        result.put("latestSha1", check.latestSha1());
        result.put("downloadUrl", check.downloadUrl().toString());
        result.put("releaseNotesUrl", check.releaseNotesUrl() == null
                ? null : check.releaseNotesUrl().toString());
        result.put("message", check.message());
        return result;
    }

    private static Map<String, Object> hashDecisionsJson(
            ModPolicyService.Verification verification) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ModPolicyService.CheckedMod mod : verification.checkedMods()) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("allowed", switch (mod.status()) {
                case "ALLOWED" -> true;
                case "BLOCKED" -> false;
                default -> null;
            });
            result.put(mod.sha1(), decision);
        }
        return result;
    }

    private static Map<String, Object> publicCatalogJson(
            ModArtifactService.Catalog catalog) {
        return Map.of("policyId", catalog.policyId(), "server",
                catalog.server() == null ? "" : catalog.server(), "version",
                catalog.version(), "loader", catalog.loader(), "mods", catalog.allowed());
    }

    private static Map<String, Object> suspensionJson(
            ModPolicyService.Suspension suspension) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", suspension.id());
        result.put("userId", suspension.userId().toString());
        result.put("username", suspension.username());
        result.put("byMod", suspension.byMod());
        result.put("reason", suspension.reason());
        result.put("createdAt", suspension.createdAt().toString());
        result.put("expiresAt", suspension.expiresAt() == null ? null
                : suspension.expiresAt().toString());
        result.put("resolvedAt", suspension.resolvedAt() == null
                ? null : suspension.resolvedAt().toString());
        result.put("resolution", suspension.resolution());
        result.put("resolvedBy", suspension.resolvedBy());
        result.put("evidence", suspension.evidence());
        return result;
    }

    private static Map<String, Object> authenticatedJson(
            AuthService.AuthenticatedUser result) {
        return Map.of("user", userJson(result.user()), "permissions",
                result.permissions().stream().map(Permission::name).sorted().toList());
    }

    private Map<String, Object> profileJson(TextureService.MinecraftProfile profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.id().toString());
        result.put("name", profile.name());
        result.put("skinModel", profile.skinModel().value());
        result.put("skinHash", profile.skinHash());
        result.put("skinUrl", textures.skinUrl(profile));
        result.put("capeHash", profile.capeHash());
        result.put("capeUrl", profile.capeHash() == null ? null
                : textures.textureUrl(profile.capeHash()));
        result.put("updatedAt", profile.updatedAt().toString());
        return result;
    }

    // =========================================================================
    // REQUEST VALUES
    // =========================================================================

    private record RegisterRequest(String username, String email, String password) { }

    private record NewsRequest(String markdown, String imageBase64) { }

    private record ModFilePath(ModArtifactService.ContentType contentType,
            String minecraftVersion, String loader, String modId,
            String modVersion, String artifactType) { }

    private record ModPolicyRequest(String server, String minecraftVersion, String loader,
            Boolean allowed, Boolean required, String reason, String type) { }

    private record LauncherCheckRequest(String channel, String sha1) { }

    private record LoginRequest(String identifier, String password,
            Boolean rememberDevice, String deviceName) { }

    private record RefreshRequest(String refreshToken) { }

    private record EmailRequest(String email) { }

    private record EmailChangeRequest(String newEmail, String currentPassword) { }

    private record UsernameChangeRequest(String username, String currentPassword) { }
    private record PasswordChangeRequest(String currentPassword, String newPassword) { }

    private record EmailVerificationRequest(String token, String email, String code) { }

    private record PasswordResetRequest(String token, String newPassword) { }

    private record GameTicketRequest(String server, String version,
            String loader, List<String> sha1) { }

    private record GameTicketConsumeRequest(String ticket, String server) { }

    private record RoleRequest(String role) { }

    private record BanRequest(String reason, String expiresAt) { }
    private record AdminPasswordRequest(String password) { }

    private record SuspensionRequest(Boolean suspended, String suspensionId,
            String resolution, String reason) { }
}

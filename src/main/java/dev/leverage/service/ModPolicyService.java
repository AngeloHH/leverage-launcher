package dev.leverage.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.leverage.db.Database;
import dev.leverage.domain.Permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ModPolicyService {
    private static final Gson GSON = new Gson();
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final int MAX_MODS = 2_000;

    private final Database database;
    private final Path policyPath;
    private final Clock clock;
    private final Duration suspensionDuration;
    private final ModArtifactService artifacts;

    public ModPolicyService(Database database, Path policyPath,
            Clock clock, Duration suspensionDuration) {
        this(database, policyPath, clock, suspensionDuration, null);
    }

    public ModPolicyService(Database database, Path policyPath, Clock clock,
            Duration suspensionDuration, ModArtifactService artifacts) {
        this.database = database;
        this.policyPath = policyPath.toAbsolutePath().normalize();
        this.clock = clock;
        this.suspensionDuration = suspensionDuration;
        this.artifacts = artifacts;
        policies();
    }

    // =========================================================================
    // POLICY SELECTION AND INVENTORY ENTRY POINTS
    // =========================================================================

    public Policy policy(String serverValue, String versionValue) {
        return policy(serverValue, versionValue, "default");
    }

    public Policy policy(String serverValue, String versionValue, String loaderValue) {
        String server = requiredLabel(serverValue, "server", 255);
        String version = requiredLabel(versionValue, "version", 100);
        String loader = requiredLabel(loaderValue, "loader",
                100).toLowerCase(Locale.ROOT);
        if (artifacts != null) {
            var stored = artifacts.policy(server, version, loader);
            if (stored.isPresent()) {
                ModArtifactService.PolicyData data = stored.get();
                return new Policy(data.policyId(), data.server(), data.version(),
                        data.allowed().stream().map(rule -> new ModRule(
                        rule.displayName(), rule.sha1(), rule.required(),
                        null)).toList(), data.blocked().stream().map(rule -> new ModRule(
                        rule.displayName(), rule.sha1(), false, null)).toList());
            }
        }
        return policies().stream().filter(
                policy -> policy.server().equalsIgnoreCase(server)
                && policy.version().equalsIgnoreCase(version)).findFirst().orElseThrow(
                () -> ServiceException.notFound("MOD_POLICY_NOT_FOUND",
                "No existe una política de mods para ese servidor y " + "versión."));
    }

    public Verification verify(AuthService.AuthenticatedUser actor, String serverValue,
            String versionValue, String policyIdValue, List<ClientMod> reportedMods) {
        return verify(actor, serverValue, versionValue, "default",
                policyIdValue, reportedMods);
    }

    public Verification verify(AuthService.AuthenticatedUser actor,
            String serverValue, String versionValue, String loaderValue,
            String policyIdValue, List<ClientMod> reportedMods) {
        Policy policy = policy(serverValue, versionValue, loaderValue);
        String policyId = requiredLabel(policyIdValue, "policyId", 150);
        if (!policy.id().equals(policyId)) {
            throw ServiceException.conflict("STALE_MOD_POLICY",
                    "La política cambió; vuelve a obtenerla antes de "
                    + "iniciar el juego.");
        }
        return verifyAgainstPolicy(actor, policy, reportedMods, true);
    }

    public Verification verifyInventory(AuthService.AuthenticatedUser actor,
            String serverValue, String versionValue, String loaderValue,
            List<ClientMod> reportedMods) {
        return verifyAgainstPolicy(actor, policy(serverValue,
                versionValue, loaderValue), reportedMods, true);
    }

    public Verification checkMod(AuthService.AuthenticatedUser actor, String serverValue,
            String versionValue, String loaderValue, ClientMod reportedMod) {
        if (reportedMod == null) {
            throw ServiceException.badRequest("MISSING_MOD", "El mod es obligatorio.");
        }
        return verifyAgainstPolicy(actor, policy(serverValue, versionValue,
                loaderValue), List.of(reportedMod), false);
    }

    public Verification verifyInventoryHashes(AuthService.AuthenticatedUser actor,
            String serverValue, String versionValue,
            String loaderValue, List<String> sha1Values) {
        return verifyAgainstPolicy(actor, policy(serverValue, versionValue,
                loaderValue), hashOnlyMods(sha1Values), true);
    }

    public Verification checkHash(AuthService.AuthenticatedUser actor, String serverValue,
            String versionValue, String loaderValue, String sha1Value) {
        if (sha1Value == null) {
            throw ServiceException.badRequest("MISSING_SHA1", "El SHA-1 es obligatorio.");
        }
        return verifyAgainstPolicy(actor, policy(serverValue, versionValue,
                loaderValue), hashOnlyMods(List.of(sha1Value)), false);
    }

    // =========================================================================
    // INVENTORY COMPARISON
    // =========================================================================

    private Verification verifyAgainstPolicy(AuthService.AuthenticatedUser actor,
            Policy policy, List<ClientMod> reportedMods, boolean fullInventory) {
        List<ClientMod> mods = validateMods(reportedMods);
        Map<String, ModRule> allowed = byHash(policy.allowed());
        Map<String, ModRule> blocked = byHash(policy.blocked());
        Map<String, List<String>> equivalents = artifacts == null
                ? Map.of() : artifacts.resourcePackAliases();
        List<DetectedMod> blockedMods = new ArrayList<>();
        List<ClientMod> unknownMods = new ArrayList<>();
        List<CheckedMod> checkedMods = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (ClientMod mod : mods) {
            present.add(mod.sha1());
            List<String> equivalent = equivalents.getOrDefault(mod.sha1(), List.of());
            present.addAll(equivalent);
            ModRule blockedRule = blocked.get(mod.sha1());
            if (blockedRule == null) for (String hash : equivalent) {
                blockedRule = blocked.get(hash);
                if (blockedRule != null) break;
            }
            ModRule allowedRule = allowed.get(mod.sha1());
            if (allowedRule == null) for (String hash : equivalent) {
                allowedRule = allowed.get(hash);
                if (allowedRule != null) break;
            }
            if (blockedRule != null) {
                blockedMods.add(new DetectedMod(mod.fileName(),
                        mod.sha1(), blockedRule.name()));
                checkedMods.add(new CheckedMod(mod.fileName(), mod.sha1(),
                        "BLOCKED", blockedRule.name(), blockedRule.reason()));
            } else if (allowedRule == null) {
                unknownMods.add(mod);
                checkedMods.add(new CheckedMod(mod.fileName(), mod.sha1(), "UNKNOWN",
                        null, "El mod no está registrado en el servidor."));
            } else {
                checkedMods.add(new CheckedMod(mod.fileName(), mod.sha1(),
                        "ALLOWED", allowedRule.name(), allowedRule.reason()));
            }
        }
        List<ModRule> missingRequired = !fullInventory ? List.of()
                : policy.allowed().stream().filter(rule -> rule.required()
                && !present.contains(rule.sha1())).toList();

        if (!blockedMods.isEmpty()) {
            return suspendForViolation(actor, policy, blockedMods, checkedMods);
        }
        if (!unknownMods.isEmpty() || !missingRequired.isEmpty()) {
            return new Verification(fullInventory ? "REJECTED" : "UNKNOWN",
                    null, null, policy.id(), unknownMods, missingRequired,
                    List.of(), List.copyOf(checkedMods));
        }
        if (!fullInventory) {
            return new Verification("ALLOWED", null, null, policy.id(), List.of(),
                    List.of(), List.of(), List.copyOf(checkedMods));
        }

        return new Verification("APPROVED", null, null, policy.id(), List.of(),
                List.of(), List.of(), List.copyOf(checkedMods));
    }

    // =========================================================================
    // SUSPENSION REVIEW AND RESOLUTION
    // =========================================================================

    public List<Suspension> listSuspensions(AuthService.AuthenticatedUser actor) {
        requireAdmin(actor);
        Instant now = clock.instant();
        try (Connection connection = database.open()) {
            expireAll(connection, now);
            List<Suspension> suspensions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement( """
                    SELECT s.id, s.user_id, u.username, s.by_mod, s.reason,
                           s.created_at, s.expires_at, s.resolved_at, s.resolution, \
                    s.created_by AS resolved_by
                    FROM account_sanctions s
                    JOIN users u ON u.id=s.user_id
                    ORDER BY s.created_at DESC LIMIT 200
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    suspensions.add(new Suspension(id, UUID.fromString(
                            rows.getString("user_id")), rows.getString("username"),
                            rows.getInt("by_mod") == 1, rows.getString("reason"),
                            Instant.ofEpochMilli(rows.getLong("created_at")),
                            nullableInstant(rows, "expires_at"), nullableInstant(rows,
                            "resolved_at"), rows.getString("resolution"),
                            rows.getString("resolved_by"), evidence(connection, id)));
                }
            }
            return List.copyOf(suspensions);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public Suspension resolve(AuthService.AuthenticatedUser actor,
            UUID suspensionId, Resolution resolution, String reasonValue) {
        return resolve(actor, null, suspensionId, resolution, reasonValue);
    }

    public Suspension resolveForUser(AuthService.AuthenticatedUser actor, UUID userId,
            UUID suspensionId, Resolution resolution, String reasonValue) {
        return resolve(actor, userId, suspensionId, resolution, reasonValue);
    }

    private Suspension resolve(AuthService.AuthenticatedUser actor, UUID expectedUserId,
            UUID suspensionId, Resolution resolution, String reasonValue) {
        requireAdmin(actor);
        String decisionReason = requiredLabel(reasonValue, "reason", 500);
        Instant now = clock.instant();
        try {
            database.transaction(connection -> {
                SuspensionRow row = suspensionRow(connection, suspensionId);
                if (row == null) {
                    throw ServiceException.notFound("SUSPENSION_NOT_FOUND",
                            "La suspensión no existe.");
                }
                if (expectedUserId != null && !expectedUserId.equals(row.userId())) {
                    throw ServiceException.notFound("SUSPENSION_NOT_FOUND",
                            "La suspensión no pertenece al usuario indicado.");
                }
                if (row.resolution() != null) {
                    throw ServiceException.conflict("SUSPENSION_ALREADY_RESOLVED",
                            "La suspensión ya fue resuelta.");
                }
                String storedResolution = resolution == Resolution.LIFT
                        ? "LIFTED" : "BANNED";
                try (PreparedStatement statement = connection.prepareStatement( """
                        UPDATE account_sanctions
                        SET resolved_at=?, resolution=?, created_by=?, reason=?,
                            expires_at=CASE WHEN ?='BANNED' THEN NULL ELSE expires_at END
                        WHERE id=? AND resolved_at IS NULL
                        """)) {
                    statement.setLong(1, now.toEpochMilli());
                    statement.setString(2, storedResolution);
                    statement.setString(3, actor.user().id().toString());
                    statement.setString(4, decisionReason);
                    statement.setString(5, storedResolution);
                    statement.setString(6, suspensionId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw ServiceException.conflict("SUSPENSION_ALREADY_RESOLVED",
                                "La suspensión ya fue resuelta.");
                    }
                }
                if (resolution == Resolution.BAN) {
                    setStatus(connection, row.userId(), "SUSPENDED", now);
                    AccountRestrictions.revokeAll(connection, row.userId(), now);
                    audit(connection, actor.user().id(), row.userId(),
                            "MOD_SUSPENSION_BANNED", Map.of("suspensionId",
                            suspensionId.toString(), "reason", decisionReason));
                } else {
                    AccountRestrictions.refreshExpired(connection, row.userId(), now);
                    if (!AccountRestrictions.hasActiveBan(connection, row.userId(),
                            now) && !AccountRestrictions.hasActiveSuspension(connection,
                            row.userId(), now)) {
                        setStatus(connection, row.userId(), "ACTIVE", now);
                    }
                    audit(connection, actor.user().id(), row.userId(),
                            "MOD_SUSPENSION_LIFTED", Map.of("suspensionId",
                            suspensionId.toString(), "reason", decisionReason));
                }
                return null;
            });
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        return listSuspensions(actor).stream().filter(item -> item.id().equals(
                suspensionId.toString())).findFirst().orElseThrow();
    }

    // =========================================================================
    // VIOLATION EVIDENCE AND SESSION REVOCATION
    // =========================================================================

    private Verification suspendForViolation(AuthService.AuthenticatedUser actor,
            Policy policy, List<DetectedMod> blockedMods, List<CheckedMod> checkedMods) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(suspensionDuration);
        UUID suspensionId = UUID.randomUUID();
        try {
            database.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement( """
                        INSERT INTO account_sanctions(id, user_id, by_mod, created_at, \
                        expires_at)
                        VALUES (?, ?, 1, ?, ?)
                        """)) {
                    statement.setString(1, suspensionId.toString());
                    statement.setString(2, actor.user().id().toString());
                    statement.setLong(3, now.toEpochMilli());
                    statement.setLong(4, expiresAt.toEpochMilli());
                    statement.executeUpdate();
                }
                for (DetectedMod mod : blockedMods) {
                    try (PreparedStatement statement = connection.prepareStatement( """
                            INSERT INTO mod_violation_evidence(id, suspension_id, \
                            file_name, mod_name,
                                                               sha1, detected_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        statement.setString(1, UUID.randomUUID().toString());
                        statement.setString(2, suspensionId.toString());
                        statement.setString(3, mod.fileName());
                        statement.setString(4, mod.modName());
                        statement.setString(5, mod.sha1());
                        statement.setLong(6, now.toEpochMilli());
                        statement.executeUpdate();
                    }
                }
                setStatus(connection, actor.user().id(), "SUSPENDED", now);
                AccountRestrictions.revokeAll(connection, actor.user().id(), now);
                audit(connection, actor.user().id(), actor.user().id(),
                        "MOD_POLICY_SUSPENSION", Map.of("suspensionId",
                        suspensionId.toString(), "policyId", policy.id(),
                        "expiresAt", expiresAt.toString(), "hashes",
                        blockedMods.stream().map(DetectedMod::sha1).toList()));
                return null;
            });
            return new Verification("SUSPENDED", suspensionId,
                    expiresAt, policy.id(), List.of(), List.of(),
                    List.copyOf(blockedMods), List.copyOf(checkedMods));
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // =========================================================================
    // POLICY LOADING AND NORMALIZATION
    // =========================================================================

    private List<Policy> policies() {
        if (artifacts != null) return List.of();
        if (!Files.exists(policyPath)) {
            return List.of();
        }
        try {
            PolicyDocument document = GSON.fromJson(Files.readString(policyPath),
                    PolicyDocument.class);
            if (document == null || document.schemaVersion() != 1
                    || document.policies() == null) {
                throw new IllegalArgumentException(
                        "El archivo de políticas debe usar schemaVersion 1.");
            }
            List<Policy> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> scopes = new HashSet<>();
            for (Policy raw : document.policies()) {
                Policy policy = normalizePolicy(raw);
                if (!ids.add(policy.id()) || !scopes.add((policy.server() + "\n"
                        + policy.version()).toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Hay políticas duplicadas por id o servidor/versión.");
                }
                result.add(policy);
            }
            return List.copyOf(result);
        } catch (IOException | JsonParseException exception) {
            throw new IllegalArgumentException("No se pudo leer la política de mods: "
                    + policyPath, exception);
        }
    }

    private static Policy normalizePolicy(Policy raw) {
        if (raw == null) {
            throw new IllegalArgumentException("La política contiene una entrada vacía.");
        }
        String id = configLabel(raw.id(), "id", 150);
        String server = configLabel(raw.server(), "server", 255);
        String version = configLabel(raw.version(), "version", 100);
        List<ModRule> allowed = normalizeRules(raw.allowed(), false);
        List<ModRule> blocked = normalizeRules(raw.blocked(), true);
        Set<String> hashes = new HashSet<>();
        for (ModRule rule : allowed) {
            hashes.add(rule.sha1());
        }
        for (ModRule rule : blocked) {
            if (!hashes.add(rule.sha1())) {
                throw new IllegalArgumentException(
                        "Un SHA-1 no puede estar permitido y bloqueado a la vez: "
                        + rule.sha1());
            }
        }
        return new Policy(id, server, version, allowed, blocked);
    }

    private static List<ModRule> normalizeRules(List<ModRule> rawRules, boolean blocked) {
        if (rawRules == null) {
            return List.of();
        }
        List<ModRule> result = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        for (ModRule raw : rawRules) {
            String name = configLabel(raw.name(), "mod.name", 200);
            String hash = normalizeSha1(raw.sha1());
            if (!hashes.add(hash)) {
                throw new IllegalArgumentException(
                        "SHA-1 repetido en la política: " + hash);
            }
            String reason = raw.reason() == null ? "" : raw.reason().trim();
            if (reason.length() > 500) {
                throw new IllegalArgumentException(
                        "El motivo de un mod es demasiado largo.");
            }
            result.add(new ModRule(name, hash, !blocked && raw.required(), reason));
        }
        return List.copyOf(result);
    }

    private static List<ClientMod> validateMods(List<ClientMod> rawMods) {
        if (rawMods == null) {
            throw ServiceException.badRequest("MISSING_MOD_LIST",
                    "La lista de mods es obligatoria.");
        }
        if (rawMods.size() > MAX_MODS) {
            throw new ServiceException(413, "TOO_MANY_MODS",
                    "La instancia contiene demasiados mods.");
        }
        List<ClientMod> result = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        for (ClientMod raw : rawMods) {
            if (raw == null) {
                throw ServiceException.badRequest("INVALID_MOD",
                        "La lista contiene un mod inválido.");
            }
            String fileName = requiredLabel(raw.fileName(), "fileName", 255);
            if (fileName.contains("/") || fileName.contains("\\")
                    || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw ServiceException.badRequest("INVALID_MOD_FILENAME",
                        "El nombre de un mod no es válido.");
            }
            if (!fileNames.add(fileName.toLowerCase(Locale.ROOT))) {
                throw ServiceException.badRequest("DUPLICATE_MOD",
                        "La lista contiene archivos repetidos.");
            }
            result.add(new ClientMod(fileName, requestSha1(raw.sha1()),
                    Math.max(raw.size(), 0)));
        }
        return List.copyOf(result);
    }

    private static List<ClientMod> hashOnlyMods(List<String> values) {
        if (values == null) {
            throw ServiceException.badRequest("MISSING_SHA1_LIST",
                    "La lista de SHA-1 es obligatoria.");
        }
        if (values.size() > MAX_MODS) {
            throw new ServiceException(413, "TOO_MANY_MODS",
                    "La instancia contiene demasiados mods.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            unique.add(requestSha1(value));
        }
        return unique.stream().map(hash -> new ClientMod(hash + ".jar",
                hash, 0)).toList();
    }

    private static Map<String, ModRule> byHash(List<ModRule> rules) {
        Map<String, ModRule> result = new HashMap<>();
        for (ModRule rule : rules) {
            result.put(rule.sha1(), rule);
        }
        return result;
    }

    // =========================================================================
    // STORED SANCTIONS AND AUDIT EVENTS
    // =========================================================================

    private static List<DetectedMod> evidence(Connection connection,
            String suspensionId) throws SQLException {
        List<DetectedMod> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT file_name, sha1, mod_name
                FROM mod_violation_evidence WHERE suspension_id=? ORDER BY detected_at ASC
                """)) {
            statement.setString(1, suspensionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DetectedMod(rows.getString("file_name"),
                            rows.getString("sha1"), rows.getString("mod_name")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static SuspensionRow suspensionRow(Connection connection,
            UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT user_id, resolution FROM account_sanctions WHERE id=?
                """)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new SuspensionRow(UUID.fromString(result.getString(
                        "user_id")), result.getString("resolution")) : null;
            }
        }
    }

    private static void expireAll(Connection connection,
            Instant now) throws SQLException {
        List<UUID> users = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement( """
                SELECT DISTINCT user_id FROM account_sanctions
                WHERE (resolution IS NULL OR resolution='BANNED') AND expires_at<=?
                """)) {
            statement.setLong(1, now.toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    users.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        for (UUID user : users) {
            AccountRestrictions.refreshExpired(connection, user, now);
        }
    }

    private static void setStatus(Connection connection, UUID userId,
            String status, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET status=?, updated_at=? WHERE id=?")) {
            statement.setString(1, status);
            statement.setLong(2, now.toEpochMilli());
            statement.setString(3, userId.toString());
            statement.executeUpdate();
        }
    }

    private static void audit(Connection connection, UUID actor, UUID target,
            String action, Map<String, ?> details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement( """
                INSERT INTO audit_events(id, actor_user_id, target_user_id, action, \
                details, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actor == null ? null : actor.toString());
            statement.setString(3, target == null ? null : target.toString());
            statement.setString(4, action);
            statement.setString(5, GSON.toJson(details));
            statement.setLong(6, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    // =========================================================================
    // PERMISSIONS AND INPUT BOUNDS
    // =========================================================================

    private static void requireAdmin(AuthService.AuthenticatedUser actor) {
        if (actor == null || !actor.permissions().contains(Permission.SYSTEM_ADMIN)) {
            throw ServiceException.forbidden("INSUFFICIENT_PERMISSION",
                    "Solo un administrador puede resolver suspensiones " + "por mods.");
        }
    }

    private static String requestSha1(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA1.matcher(normalized).matches()) {
            throw ServiceException.badRequest("INVALID_SHA1",
                    "Cada mod debe incluir un SHA-1 hexadecimal válido.");
        }
        return normalized;
    }

    private static String normalizeSha1(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA1.matcher(normalized).matches()) {
            throw new IllegalArgumentException("SHA-1 inválido en la política: " + value);
        }
        return normalized;
    }

    private static String requiredLabel(String value, String field, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw ServiceException.badRequest("INVALID_" + field.toUpperCase(Locale.ROOT),
                    "El campo '" + field + "' no es válido.");
        }
        return clean;
    }

    private static String configLabel(String value, String field, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Campo inválido en la política: " + field);
        }
        return clean;
    }

    private static Instant nullableInstant(ResultSet result,
            String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static ServiceException databaseFailure(SQLException exception) {
        exception.printStackTrace(System.err);
        return new ServiceException(500, "DATABASE_ERROR",
                "No se pudo completar la operación.");
    }

    public enum Resolution {
        LIFT, BAN
    }

    // =========================================================================
    // POLICY AND VERIFICATION VALUES
    // =========================================================================

    public record PolicyDocument(int schemaVersion, List<Policy> policies) { }

    public record Policy(String id, String server, String version,
            List<ModRule> allowed, List<ModRule> blocked) { }

    public record ModRule(String name, String sha1, boolean required, String reason) { }

    public record ClientMod(String fileName, String sha1, long size) { }

    public record DetectedMod(String fileName, String sha1, String modName) { }

    public record CheckedMod(String fileName, String sha1, String status,
            String registeredName, String reason) { }

    public record Verification(String status, UUID suspensionId, Instant expiresAt,
            String policyId, List<ClientMod> unknownMods, List<ModRule> missingRequired,
            List<DetectedMod> blockedMods, List<CheckedMod> checkedMods) { }

    public record Suspension(String id, UUID userId, String username, boolean byMod,
            String reason, Instant createdAt, Instant expiresAt, Instant resolvedAt,
            String resolution, String resolvedBy, List<DetectedMod> evidence) { }

    private record SuspensionRow(UUID userId, String resolution) { }
}

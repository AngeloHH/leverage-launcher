package dev.leverage.db;

import dev.leverage.mail.ConsoleRecoveryMailer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.security.SecureTokens;
import dev.leverage.service.AuthService;
import dev.leverage.skin.TextureService;
import dev.leverage.skin.YggdrasilSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SchemaSimplificationTest {
    @TempDir Path directory;

    @Test
    void freshDatabaseHasOneModsTableAndNoDuplicatedProfileOrCodeTables(
            ) throws Exception {
        Database database = new Database(directory.resolve("fresh.db"));
        database.initialize();
        database.initialize();
        try (var connection = database.open(); var statement
                = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'")) {
                Set<String> tables = new java.util.HashSet<>();
                while (rows.next()) tables.add(rows.getString(1));
                assertEquals(Set.of("users", "sessions", "device_sessions",
                        "device_refresh_history", "game_tickets",
                        "account_tokens", "email_change_requests", "textures",
                        "yggdrasil_joins", "account_sanctions",
                        "mod_violation_evidence", "mods", "audit_events"), tables);
            }
            assertEquals(Set.of("id", "content_type", "minecraft_version",
                    "loader", "mod_id", "mod_version", "artifact_type",
                    "name", "sha1", "storage_path", "allowed", "required"),
                    columns(statement, "mods"));
            assertEquals(Set.of("user_id", "type", "hash", "storage_path",
                    "model"), columns(statement, "textures"));
            assertFalse(columns(statement, "account_sanctions").contains("policy_id"));
            assertFalse(columns(statement, "account_sanctions").contains("sha1"));
            assertTrue(columns(statement, "account_sanctions").contains("by_mod"));
            assertFalse(columns(statement, "mod_violation_evidence").contains("reason"));
            assertFalse(columns(statement, "yggdrasil_joins").contains(
                    "username_normalized"));
            assertTrue(columns(statement, "account_tokens").containsAll(Set.of(
                    "code_hash", "code_expires_at", "attempts")));
        }
    }

    @Test
    void removesOldComplianceWithBackupAndPreservesAccounts() throws Exception {
        Path path = directory.resolve("compliance.db");
        Database database = new Database(path);
        database.initialize();
        AuthService auth = new AuthService(database, new PasswordHasher(10_000),
                new ConsoleRecoveryMailer(),
                Clock.systemUTC(), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofMinutes(30), true);
        var user = auth.createBootstrapAdmin("Player",
                "player@example.test", "player-password-long");
        String access = auth.login("Player", "player-password-long").accessToken();
        String session = auth.authenticate(access).sessionId();
        try (var connection = database.open(); var sql = connection.createStatement()) {
            sql.executeUpdate(
                    "CREATE TABLE mod_compliance(session_id TEXT PRIMARY KEY REFERENC"
                    + "ES sessions(id), server_scope TEXT, policy_id TEXT, verified_at "
                    + "INTEGER, expires_at INTEGER)");
            sql.executeUpdate("INSERT INTO mod_compliance VALUES ('" + session
                    + "','survival','old-policy',0,9999999999999)");
        }
        database.initialize();
        database.initialize();
        assertEquals(user.id(), auth.authenticate(access).user().id());
        try (var connection = database.open(); var sql = connection.createStatement();
             var rows = sql.executeQuery(
                "SELECT name FROM sqlite_master WHERE name='mod_compliance'")) {
            assertFalse(rows.next());
        }
        try (var files = Files.list(directory)) {
            var backups = files.filter(file -> file.getFileName().toString(
                    ).startsWith("compliance.db.before-")).toList();
            assertEquals(1, backups.size());
            try (var connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + backups.getFirst());
                 var sql = connection.createStatement(); var rows
                    = sql.executeQuery("SELECT COUNT(*) FROM mod_compliance")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void migrationPreservesSixDigitCodesSkinsAndActiveJoins() throws Exception {
        Database database = new Database(directory.resolve("legacy.db"));
        var legacy = Database.class.getDeclaredMethod("initializeLegacy");
        legacy.setAccessible(true);
        legacy.invoke(database);
        String active = UUID.randomUUID().toString();
        String pending = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        String tokenId = UUID.randomUUID().toString();
        String code = "012345";
        String token = SecureTokens.create();
        String access = SecureTokens.create();
        long now = Instant.now().toEpochMilli();
        long later = now + Duration.ofHours(1).toMillis();
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64,
                64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", bytes);
        byte[] png = bytes.toByteArray();
        String oldHash = HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(png));
        Path textures = directory.resolve("textures");
        Path file = textures.resolve(oldHash.substring(0,
                2)).resolve(oldHash.substring(2, 4)).resolve(oldHash);
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        try (var connection = database.open(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users VALUES ('"+active
                    +"','SkinPlayer','skin@example.test','hash','USER','ACTIVE',"
                    +now+","+now+","+now+")");
            sql.executeUpdate("INSERT INTO users VALUES ('"+pending
                    + ("','CodePlayer','code@example.test','hash','USER','PENDING_VERIFI"
                    + "CATION',")+now+","+now+","+now+")");
            sql.executeUpdate("INSERT INTO textures VALUES ('"+oldHash
                    +"',"+png.length+",64,64,"+now+")");
            sql.executeUpdate("INSERT INTO minecraft_profiles VALUES ('"
                    +active+"','"+active+"','SkinPlayer','skinplayer','"
                    +oldHash+"','slim',NULL,"+now+","+now+")");
            sql.executeUpdate("INSERT INTO sessions VALUES ('"+session+"','"+active+"',X'"
                    +hex(SecureTokens.hash(access))+"',"+now+","+later+","+now+",NULL)");
            sql.executeUpdate("INSERT INTO yggdrasil_joins VALUES ('challenge','"
                    +active+"','"+session+"','skinplayer',"+now+","+later+")");
            sql.executeUpdate("INSERT INTO account_tokens VALUES ('"
                    +tokenId+"','"+pending+"','EMAIL_VERIFY',X'"
                    +hex(SecureTokens.hash(token))+"',"+now+","+later+",NULL)");
            sql.executeUpdate("INSERT INTO email_verification_codes VALUES ('"+tokenId
                    +"',X'"+hex(SecureTokens.hash(tokenId+":"+code))+"',"+later+",2)");
        }
        database.initialize();
        database.initialize();
        AuthService auth = new AuthService(database, new PasswordHasher(10_000),
                new ConsoleRecoveryMailer(), Clock.systemUTC(),
                Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(30), true);
        assertEquals(UUID.fromString(pending),
                auth.confirmEmailCode("code@example.test", code).id());
        TextureService service = new TextureService(database, textures, directory.resolve(
                "keys"), URI.create("https://example.test"), Clock.systemUTC());
        var profile = service.profileById(UUID.fromString(active), false);
        assertEquals(oldHash, profile.skinHash());
        assertEquals(TextureService.SkinModel.SLIM, profile.skinModel());
        assertArrayEquals(png, service.readTexture(oldHash));
        var joins = new YggdrasilSessionService(database, auth, service,
                Clock.systemUTC(), Duration.ofMinutes(1));
        assertEquals(UUID.fromString(active), joins.hasJoined("SKINPLAYER",
                "challenge").id());
        assertNull(joins.hasJoined("AnotherPlayer", "challenge"));
        try (var connection = database.open(); var sql = connection.createStatement();
                var violations = sql.executeQuery("PRAGMA foreign_key_check")) {
            assertFalse(violations.next());
        }
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static Set<String> columns(java.sql.Statement sql,
            String table) throws Exception {
        Set<String> result = new java.util.HashSet<>();
        try (var rows = sql.executeQuery("PRAGMA table_info("+table+")")) {
            while (rows.next()) result.add(rows.getString("name"));
        }
        return result;
    }
}

package dev.leverage.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addsContentTypeToTheSameTableAndPreservesExistingModsAndDecisions(
            ) throws Exception {
        Path file = temporaryDirectory.resolve("content-types.db");
        Database database = new Database(file);
        try (var connection = database.open(); var sql = connection.createStatement()) {
            sql.executeUpdate(
            """
                    CREATE TABLE mods (
                      id TEXT PRIMARY KEY, minecraft_version TEXT NOT NULL, loader TEXT \
                    NOT NULL,
                      mod_id TEXT NOT NULL, mod_version TEXT NOT NULL, artifact_type \
                    TEXT NOT NULL,
                      name TEXT NOT NULL, sha1 TEXT NOT NULL, storage_path TEXT NOT NULL,
                      allowed INTEGER, required INTEGER NOT NULL DEFAULT 0,
                      UNIQUE(minecraft_version,loader,mod_id,mod_version,artifact_type))
                    """
            );
            sql.executeUpdate(
            """
                    INSERT INTO mods VALUES \
                    ('original-id','1.21.4','default','sample','1.0','release',
                      'Existing \
                    mod','1111111111111111111111111111111111111111','11/11/hash',0,0)
                    """
            );
        }
        database.initialize();
        database.initialize();
        // Migration must be idempotent.
        try (var connection = database.open(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT * FROM mods WHERE id='original-id'")) {
            assertTrue(row.next());
            assertEquals("mod", row.getString("content_type"));
            assertEquals("Existing mod", row.getString("name"));
            assertEquals("11/11/hash", row.getString("storage_path"));
            assertEquals(0, row.getInt("allowed"));
        }
        try (var connection = database.open(); var sql = connection.createStatement()) {
            sql.executeUpdate(
            """
                    INSERT INTO \
                    mods(id,content_type,minecraft_version,loader,mod_id,mod_version,art\
                    ifact_type,name,
                                     sha1,storage_path,allowed,required)
                    SELECT 'pack-id','resourcepack',minecraft_version,loader,mod_id,mod_\
                    version,artifact_type,name,
                           sha1,storage_path,NULL,0 FROM mods WHERE id='original-id'
                    """
            );
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> sql.executeUpdate(
                    "UPDATE mods SET content_type='unknown' WHERE id='pack-id"
                    + "'"));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM mods")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = sql.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE "
                    + "'resourcepack%'")) {
                assertFalse(rows.next());
            }
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString(
                    ).contains(".before-content-types-")).count());
        }
    }

    @Test
    void migratesLegacyListTypeToAllowedBooleanWithoutLosingRules() throws Exception {
        Database database = new Database(temporaryDirectory.resolve("migration.db"));
        initializeLegacy(database);
        String user = UUID.randomUUID().toString();
        String scope = UUID.randomUUID().toString();
        String rule = UUID.randomUUID().toString();
        String sha1 = "1111111111111111111111111111111111111111";
        long now = Instant.now().toEpochMilli();

        try (var connection = database.open(); Statement statement
                = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS server_mod_rules_scope_idx");
            statement.executeUpdate("DROP TABLE server_mod_rules");
            statement.executeUpdate(
            """
                    CREATE TABLE server_mod_rules (
                        id TEXT PRIMARY KEY,
                        scope_id TEXT NOT NULL REFERENCES mod_policy_scopes(id) ON \
                    DELETE CASCADE,
                        sha1 TEXT NOT NULL REFERENCES mod_artifacts(sha1) ON DELETE \
                    RESTRICT,
                        list_type TEXT NOT NULL CHECK (list_type IN ('ALLOWED', \
                    'BLOCKED')),
                        display_name TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        required INTEGER NOT NULL DEFAULT 0,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(scope_id, sha1)
                    )
                    """
            );
            statement.executeUpdate("INSERT INTO users VALUES ('" + user
                    + "','User','user@example.test','hash','USER','ACTIVE',"
                    + now + "," + now + "," + now + ")");
            statement.executeUpdate("INSERT INTO mod_artifacts VALUES ('" + sha1
                    + "','11/11/" + sha1 + "',10,'blocked.jar',"
                    + now + ",'" + user + "')");
            statement.executeUpdate("INSERT INTO mod_policy_scopes VALUES ('" + scope
                    + "','survival','26.3','fabric',1," + now + ")");
            statement.executeUpdate("INSERT INTO server_mod_rules VALUES ('"
                    + rule + "','" + scope
                    + "','" + sha1 + "','BLOCKED','Blocked','blocked.jar',0,'reason',"
                    + now + "," + now + ")");
        }

        database.initialize();

        try (var connection = database.open(); Statement statement
                = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT allowed, name FROM mods")) {
            assertTrue(row.next());
            assertEquals(0, row.getInt("allowed"));
            assertEquals("Blocked", row.getString("name"));
        }
        boolean hasAllowed = false;
        boolean hasLegacy = false;
        try (var connection = database.open(); Statement statement
                = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(mods)")) {
            while (columns.next()) {
                hasAllowed |= columns.getString("name").equals("allowed");
                hasLegacy |= columns.getString("name").equals("list_type");
            }
        }
        assertTrue(hasAllowed);
        assertFalse(hasLegacy);
    }

    @Test
    void assignsStableModUuidToExistingPublishedFiles() throws Exception {
        Database database = new Database(temporaryDirectory.resolve(
                "mod-files-migration.db"));
        initializeLegacy(database);
        String user = UUID.randomUUID().toString();
        String file = UUID.randomUUID().toString();
        String sha1 = "2222222222222222222222222222222222222222";
        long now = Instant.now().toEpochMilli();

        try (var connection = database.open(); Statement statement
                = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE mod_files");
            statement.executeUpdate("DROP TABLE mods");
            statement.executeUpdate(
            """
                    CREATE TABLE mod_files (
                        id TEXT PRIMARY KEY,
                        minecraft_version TEXT NOT NULL,
                        loader TEXT NOT NULL,
                        mod_id TEXT NOT NULL,
                        mod_version TEXT NOT NULL,
                        artifact_type TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        sha1 TEXT NOT NULL REFERENCES mod_artifacts(sha1),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        uploaded_by TEXT NOT NULL REFERENCES users(id),
                        UNIQUE(minecraft_version, loader, mod_id, mod_version, \
                    artifact_type)
                    )
                    """
            );
            statement.executeUpdate("INSERT INTO users VALUES ('" + user
                    + "','User','user@example.test','hash','USER','ACTIVE',"
                    + now + "," + now + "," + now + ")");
            statement.executeUpdate("INSERT INTO mod_artifacts VALUES ('" + sha1
                    + "','22/22/" + sha1 + "',10,'sodium.jar',"
                    + now + ",'" + user + "')");
            statement.executeUpdate("INSERT INTO mod_files VALUES ('" + file
                    + ("','1.21.4','fabric','sodium','0.6.5','release','sodium',"
                    + "'sodium.jar','")
                    + sha1 + "'," + now + "," + now + ",'" + user + "')");
        }

        database.initialize();

        try (var connection = database.open(); Statement statement
                = connection.createStatement();
             ResultSet row = statement.executeQuery(
             """
                     SELECT id, mod_id, sha1 FROM mods
                     """
             )) {
            assertTrue(row.next());
            assertEquals("sodium", row.getString("mod_id"));
            assertEquals(file, row.getString("id"));
            assertEquals(sha1, row.getString("sha1"));
        }
    }

    @Test
    void unifiesLegacySanctionsAndRemovesDuplicatedUserTextWithoutLosingRelations(
            ) throws Exception {
        Path file = temporaryDirectory.resolve("legacy-accounts.db");
        Database database = new Database(file);
        String user = UUID.randomUUID().toString();
        String admin = UUID.randomUUID().toString();
        String pending = UUID.randomUUID().toString();
        String banned = UUID.randomUUID().toString();
        String manualBan = UUID.randomUUID().toString();
        String sha1 = "3333333333333333333333333333333333333333";
        try (var connection = database.open(); Statement sql
                = connection.createStatement()) {
            sql.executeUpdate(
            """
                    CREATE TABLE users (
                        id TEXT PRIMARY KEY, username TEXT NOT NULL, username_normalized \
                    TEXT UNIQUE NOT NULL,
                        email TEXT NOT NULL, email_normalized TEXT UNIQUE NOT NULL, \
                    password_hash TEXT NOT NULL,
                        role TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT \
                    NULL,
                        updated_at INTEGER NOT NULL, password_changed_at INTEGER NOT NULL
                    )
                    """
            );
            sql.executeUpdate("INSERT INTO users VALUES ('" + user
                    + ("','MixedPlayer','mixedplayer','Mixed@Example.test','mixe"
                    + "d@example.test','hash','USER','SUSPENDED',1,2,3)"));
            sql.executeUpdate("INSERT INTO users VALUES ('" + admin
                    + ("','Admin','admin','Admin@Example.test','admin@example.te"
                    + "st','hash','ADMIN','ACTIVE',1,2,3)"));
            sql.executeUpdate(
            """
                    CREATE TABLE account_suspensions (
                        id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id), \
                    policy_id TEXT, source TEXT,
                        reason TEXT, created_at INTEGER, expires_at INTEGER, resolved_at \
                    INTEGER,
                        resolution TEXT, resolved_by TEXT REFERENCES users(id)
                    )
                    """
            );
            sql.executeUpdate("INSERT INTO account_suspensions VALUES ('"
                    + pending + "','" + user
                    + ("','survival','MOD_POLICY','Pendiente',10,100,NULL,NULL,N"
                    + "ULL)"));
            sql.executeUpdate("INSERT INTO account_suspensions VALUES ('"
                    + banned + "','" + user
                    + ("','survival','MOD_POLICY','Original',20,200,25,'BANNED',"
                    + "'") + admin + "')");
            sql.executeUpdate(
            """
                    CREATE TABLE account_bans (
                        id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id),
                        suspension_id TEXT REFERENCES account_suspensions(id), reason \
                    TEXT,
                        created_at INTEGER, created_by TEXT REFERENCES users(id),
                        lifted_at INTEGER, lifted_by TEXT REFERENCES users(id)
                    )
                    """
            );
            sql.executeUpdate("INSERT INTO account_bans VALUES ('"
                    + UUID.randomUUID() + "','" + user + "','"
                    + banned + "','Confirmado por admin',25,'" + admin + "',NULL,NULL)");
            sql.executeUpdate("INSERT INTO account_bans VALUES ('"
                    + manualBan + "','" + user
                    + "',NULL,'Ban manual',30,'" + admin + "',NULL,NULL)");
            sql.executeUpdate(
            """
                    CREATE TABLE mod_violation_evidence (
                        id TEXT PRIMARY KEY, suspension_id TEXT REFERENCES \
                    account_suspensions(id),
                        file_name TEXT, mod_name TEXT, sha1 TEXT, reason TEXT, \
                    detected_at INTEGER
                    )
                    """
            );
            sql.executeUpdate("INSERT INTO mod_violation_evidence VALUES ('"
                    + UUID.randomUUID() + "','"
                    + pending + "','blocked.jar','Blocked','" + sha1
                    + "','Prohibido',10)");
            sql.executeUpdate("INSERT INTO mod_violation_evidence VALUES ('"
                    + UUID.randomUUID() + "','"
                    + banned + "','blocked.jar','Blocked','" + sha1
                    + "','Prohibido',20)");
            sql.executeUpdate(
            """
                    CREATE TABLE email_change_requests (
                        id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id), \
                    new_email TEXT,
                        new_email_normalized TEXT, token_hash BLOB UNIQUE, created_at \
                    INTEGER,
                        expires_at INTEGER, used_at INTEGER
                    )
                    """
            );
            sql.executeUpdate("INSERT INTO email_change_requests VALUES ('request','"
                    + user
                    + ("','Next@Example.test','next@example.test',X'01',1,999,NU"
                    + "LL)"));
        }

        database.initialize();
        database.initialize();
        try (var connection = database.open(); Statement sql
                = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery(
                    "SELECT username, email, password_hash FROM users WHERE id='"
                    + user + "'")) {
                assertTrue(rows.next());
                assertEquals("MixedPlayer", rows.getString("username"));
                assertEquals("Mixed@Example.test", rows.getString("email"));
                assertEquals("hash", rows.getString("password_hash"));
            }
            try (ResultSet rows = sql.executeQuery("PRAGMA table_info(users)")) {
                while (rows.next()) assertFalse(rows.getString(
                        "name").contains("normalized"));
            }
            try (ResultSet rows = sql.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('account_bans'"
                    + ",'account_suspensions')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (ResultSet rows = sql.executeQuery(
                    "SELECT COUNT(*) FROM account_sanctions")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
            try (ResultSet rows = sql.executeQuery(
                    "SELECT * FROM account_sanctions WHERE id='" + banned + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("by_mod"));
                assertEquals("Confirmado por admin", rows.getString("reason"));
                assertEquals(admin, rows.getString("created_by"));
                assertEquals("BANNED", rows.getString("resolution"));
                org.junit.jupiter.api.Assertions.assertNull(rows.getObject("expires_at"));
            }
            try (ResultSet rows = sql.executeQuery(
                    "SELECT COUNT(*) FROM mod_violation_evidence")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (ResultSet rows = sql.executeQuery(
                    "SELECT new_email FROM email_change_requests")) {
                assertTrue(rows.next());
                assertEquals("Next@Example.test", rows.getString(1));
            }
            try (ResultSet rows = sql.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rows.next());
            }
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith(
                    "legacy-accounts.db.before-schema-simplification-")).count());
        }
    }

    private static void initializeLegacy(Database database) throws Exception {
        var method = Database.class.getDeclaredMethod("initializeLegacy");
        method.setAccessible(true);
        method.invoke(database);
    }
}

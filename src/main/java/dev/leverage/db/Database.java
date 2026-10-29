package dev.leverage.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private final String jdbcUrl;
    private final Path databasePath;
    private final java.util.Properties connectionProperties;

    public Database(Path path) throws IOException {
        this(path, false);
    }

    // Operator commands must never create a new database because a path was mistyped.
    public static Database existing(Path path) throws IOException {
        return new Database(path, true);
    }

    private Database(Path path, boolean existingOnly) throws IOException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("No se encontró el controlador de SQLite.",
                    exception);
        }
        Path absolutePath = path.toAbsolutePath().normalize();
        if (existingOnly&&!Files.isRegularFile(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(("La base de datos del servidor no existe o no es un "
                    + "archivo normal: ")+absolutePath);
        }
        if (!existingOnly&&absolutePath.getParent() != null) {
            Files.createDirectories(absolutePath.getParent());
        }
        this.jdbcUrl = "jdbc:sqlite:" + absolutePath;
        this.databasePath = absolutePath;
        if (existingOnly) {
            var sqlite = new org.sqlite.SQLiteConfig();
            sqlite.resetOpenMode(org.sqlite.SQLiteOpenMode.CREATE);
            sqlite.setOpenMode(org.sqlite.SQLiteOpenMode.READWRITE);
            connectionProperties = sqlite.toProperties();
        } else connectionProperties = new java.util.Properties();
    }

    // =========================================================================
    // SCHEMA SELECTION AND RECOVERABLE BACKUP
    // =========================================================================

    public void initialize() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            boolean legacy = hasColumn(connection, "users", "username_normalized")
                    || hasColumn(connection, "account_suspensions", "id")
                    || hasColumn(connection, "account_bans", "id")
                    || hasColumn(connection, "account_sanctions", "policy_id")
                    || hasColumn(connection, "email_verification_codes",
                            "account_token_id")
                    || hasColumn(connection, "minecraft_profiles", "id")
                    || hasColumn(connection, "mod_artifacts", "sha1")
                    || hasColumn(connection, "mods", "slug");
            boolean oldCompliance = hasColumn(connection, "mod_compliance", "session_id");
            if (legacy || oldCompliance) {
                Path backup = databasePath.resolveSibling(databasePath.getFileName()
                        + ".before-schema-simplification-"
                        + java.util.UUID.randomUUID() + ".bak");
                statement.execute("VACUUM INTO '" + backup.toString().replace("'",
                        "''") + "'");
                if (legacy) {
                    initializeLegacy();
                    compactLegacySchema(connection);
                }
                statement.executeUpdate("DROP TABLE IF EXISTS mod_compliance");
            }
            if (hasColumn(connection, "mods", "sha1") && !hasColumn(connection,
                    "mods", "content_type")) {
                Path backup = databasePath.resolveSibling(databasePath.getFileName() +
                        ".before-content-types-" + java.util.UUID.randomUUID() + ".bak");
                statement.execute("VACUUM INTO '" + backup.toString().replace("'",
                        "''") + "'");
                migrateContentTypes(connection);
            }
            createCurrentSchema(connection);
            if (!hasColumn(connection, "mods", "content_sha1")) {
                Path backup = databasePath.resolveSibling(databasePath.getFileName()
                        + ".before-resourcepack-content-"
                        + java.util.UUID.randomUUID() + ".bak");
                statement.execute("VACUUM INTO '" + backup.toString().replace("'",
                        "''") + "'");
                statement.executeUpdate("ALTER TABLE mods ADD COLUMN content_sha1 TEXT");
            }
        }
    }

    // Consistent SQLite backup, including committed
    // WAL contents, before a schema migration.
    public Path backup(String reason) throws SQLException {
        if (!reason.matches("[a-z-]+")) throw new IllegalArgumentException(
                "Invalid backup reason");
        Path backup = databasePath.resolveSibling(databasePath.getFileName()
                + "." + reason + "-" + java.util.UUID.randomUUID() + ".bak");
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + backup.toString().replace("'",
                    "''") + "'");
        }
        return backup;
    }

    // =========================================================================
    // CURRENT INSTALLATION SCHEMA
    // =========================================================================

    private static void createCurrentSchema(Connection connection) throws SQLException {
        String[] tables = {
            """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    email TEXT NOT NULL COLLATE NOCASE UNIQUE, password_hash TEXT NOT \
                NULL,
                    role TEXT NOT NULL CHECK(role IN ('USER','MODERATOR','ADMIN')),
                    status TEXT NOT NULL CHECK(status IN \
                ('PENDING_VERIFICATION','ACTIVE','SUSPENDED')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, \
                password_changed_at INTEGER NOT NULL
                )
                """, """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    token_hash BLOB NOT NULL UNIQUE, created_at INTEGER NOT NULL, \
                expires_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL, revoked_at INTEGER
                )
                """, """
                CREATE TABLE IF NOT EXISTS device_sessions (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    token_hash BLOB NOT NULL UNIQUE, device_name TEXT NOT NULL, \
                created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL, \
                revoked_at INTEGER
                )
                """, """
                CREATE TABLE IF NOT EXISTS device_refresh_history (
                    token_hash BLOB PRIMARY KEY,
                    device_session_id TEXT NOT NULL REFERENCES device_sessions(id) ON \
                DELETE CASCADE,
                    used_at INTEGER NOT NULL
                )
                """, """
                CREATE TABLE IF NOT EXISTS game_tickets (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    token_hash BLOB NOT NULL UNIQUE, server_scope TEXT NOT NULL,
                    created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, used_at \
                INTEGER
                )
                """, """
                CREATE TABLE IF NOT EXISTS account_tokens (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    type TEXT NOT NULL CHECK(type IN ('EMAIL_VERIFY','PASSWORD_RESET')),
                    token_hash BLOB NOT NULL UNIQUE, code_hash BLOB, code_expires_at \
                INTEGER,
                    attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, used_at INTEGER
                )
                """, """
                CREATE TABLE IF NOT EXISTS email_change_requests (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    new_email TEXT NOT NULL, token_hash BLOB NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, used_at \
                INTEGER
                )
                """, """
                CREATE TABLE IF NOT EXISTS textures (
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    type TEXT NOT NULL CHECK(type IN ('skin','cape')), hash TEXT NOT NULL,
                    storage_path TEXT NOT NULL, model TEXT CHECK(model IN \
                ('classic','slim')),
                    PRIMARY KEY(user_id,type)
                )
                """, """
                CREATE TABLE IF NOT EXISTS yggdrasil_joins (
                    server_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES \
                users(id) ON DELETE CASCADE,
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
                )
                """, """
                CREATE TABLE IF NOT EXISTS account_sanctions (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON \
                DELETE CASCADE,
                    by_mod INTEGER NOT NULL DEFAULT 0 CHECK(by_mod IN (0,1)), reason TEXT,
                    created_at INTEGER NOT NULL, expires_at INTEGER,
                    created_by TEXT REFERENCES users(id) ON DELETE SET NULL, resolved_at \
                INTEGER,
                    resolution TEXT CHECK(resolution IN \
                ('LIFTED','BANNED','AUTO_EXPIRED'))
                )
                """, """
                CREATE TABLE IF NOT EXISTS mod_violation_evidence (
                    id TEXT PRIMARY KEY,
                    suspension_id TEXT NOT NULL REFERENCES account_sanctions(id) ON \
                DELETE CASCADE,
                    file_name TEXT NOT NULL, mod_name TEXT NOT NULL, sha1 TEXT NOT NULL, \
                detected_at INTEGER NOT NULL
                )
                """, """
                CREATE TABLE IF NOT EXISTS mods (
                    id TEXT PRIMARY KEY, minecraft_version TEXT NOT NULL, loader TEXT \
                NOT NULL,
                    content_type TEXT NOT NULL DEFAULT 'mod' CHECK(content_type IN \
                ('mod','resourcepack')),
                    mod_id TEXT NOT NULL, mod_version TEXT NOT NULL,
                    artifact_type TEXT NOT NULL CHECK(artifact_type IN \
                ('release','sourcecode','javadoc','installer')),
                    name TEXT NOT NULL, sha1 TEXT NOT NULL, storage_path TEXT NOT NULL, \
                content_sha1 TEXT,
                    allowed INTEGER CHECK(allowed IN (0,1)), required INTEGER NOT NULL \
                DEFAULT 0 CHECK(required IN (0,1)),
                    UNIQUE(content_type,minecraft_version,loader,mod_id,mod_version,arti\
                fact_type)
                )
                """, """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id TEXT PRIMARY KEY, actor_user_id TEXT REFERENCES users(id) ON \
                DELETE SET NULL,
                    target_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                    action TEXT NOT NULL, details TEXT NOT NULL, created_at INTEGER NOT \
                NULL
                )
                """
        };
        try (Statement sql = connection.createStatement()) {
            for (String table : tables) sql.executeUpdate(table);
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS sessions_user_idx ON "
                    + "sessions(user_id)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS device_sessions_user_idx "
                    + "ON device_sessions(user_id)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS account_tokens_user_idx "
                    + "ON account_tokens(user_id,type)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS textures_hash_idx ON "
                    + "textures(hash)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS mods_sha1_idx ON mods(sha1)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS "
                    + "account_sanctions_user_idx ON "
                    + "account_sanctions(user_id,resolution,expires_at)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS "
                    + "mod_evidence_suspension_idx ON "
                    + "mod_violation_evidence(suspension_id)");
        }
    }

    // =========================================================================
    // COMPACT SCHEMA UPGRADES
    // =========================================================================

    private static void migrateContentTypes(Connection connection) throws SQLException {
        migrateWithForeignKeysDisabled(connection, current -> {
            try (Statement sql = current.createStatement()) {
                sql.executeUpdate("ALTER TABLE mods RENAME TO mods_before_content_types");
                createCurrentSchema(current);
                sql.executeUpdate( """
                        INSERT INTO \
                        mods(id,minecraft_version,loader,content_type,mod_id,mod_version,
                                         \
                        artifact_type,name,sha1,storage_path,allowed,required)
                        SELECT id,minecraft_version,loader,'mod',mod_id,mod_version,
                               artifact_type,name,sha1,storage_path,allowed,required
                        FROM mods_before_content_types
                        """);
                sql.executeUpdate("DROP TABLE mods_before_content_types");
            }
            return null;
        });
    }

    private static void compactLegacySchema(Connection connection) throws SQLException {
        migrateWithForeignKeysDisabled(connection, current -> {
            try (Statement sql = current.createStatement()) {
                sql.executeUpdate("ALTER TABLE account_tokens ADD COLUMN code_hash BLOB");
                sql.executeUpdate("ALTER TABLE account_tokens ADD COLUMN "
                        + "code_expires_at INTEGER");
                sql.executeUpdate("ALTER TABLE account_tokens ADD COLUMN attempts "
                        + "INTEGER NOT NULL DEFAULT 0");
                sql.executeUpdate( """
                        UPDATE account_tokens AS t SET code_hash=c.code_hash, \
                        code_expires_at=c.expires_at, attempts=c.attempts
                        FROM email_verification_codes c WHERE c.account_token_id=t.id
                        """);
                sql.executeUpdate("DROP TABLE email_verification_codes");

                sql.executeUpdate("ALTER TABLE textures RENAME TO textures_legacy");
                sql.executeUpdate("ALTER TABLE yggdrasil_joins RENAME TO "
                        + "yggdrasil_joins_legacy");
                sql.executeUpdate("ALTER TABLE account_sanctions RENAME TO "
                        + "account_sanctions_legacy");
                sql.executeUpdate("ALTER TABLE mod_violation_evidence RENAME TO "
                        + "mod_violation_evidence_legacy");
                sql.executeUpdate("ALTER TABLE mods RENAME TO mod_identities_legacy");
                createCurrentSchema(current);
                sql.executeUpdate( """
                        INSERT INTO textures(user_id,type,hash,storage_path,model)
                        SELECT user_id,'skin',skin_hash,
                               substr(skin_hash,1,2)||'/'||substr(skin_hash,3,2)||'/'||s\
                        kin_hash,skin_model
                        FROM minecraft_profiles WHERE skin_hash IS NOT NULL
                        UNION ALL
                        SELECT user_id,'cape',cape_hash,
                               substr(cape_hash,1,2)||'/'||substr(cape_hash,3,2)||'/'||c\
                        ape_hash,NULL
                        FROM minecraft_profiles WHERE cape_hash IS NOT NULL
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        yggdrasil_joins(server_id,profile_id,session_id,created_at,expir\
                        es_at)
                        SELECT j.server_id,p.user_id,j.session_id,j.created_at,j.expires\
                        _at
                        FROM yggdrasil_joins_legacy j JOIN minecraft_profiles p ON \
                        p.id=j.profile_id
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        account_sanctions(id,user_id,by_mod,reason,created_at,expires_at\
                        ,created_by,resolved_at,resolution)
                        SELECT s.id,s.user_id,CASE WHEN s.sha1 IS NOT NULL OR EXISTS(
                            SELECT 1 FROM mod_violation_evidence_legacy e WHERE \
                        e.suspension_id=s.id) THEN 1 ELSE 0 END,
                            CASE WHEN s.created_by IS NOT NULL OR (s.sha1 IS NULL AND \
                        NOT EXISTS(
                                SELECT 1 FROM mod_violation_evidence_legacy e WHERE \
                        e.suspension_id=s.id))
                                THEN s.reason ELSE NULL END,
                            s.created_at,s.expires_at,s.created_by,s.resolved_at,s.resol\
                        ution
                        FROM account_sanctions_legacy s
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        mod_violation_evidence(id,suspension_id,file_name,mod_name,sha1,\
                        detected_at)
                        SELECT id,suspension_id,file_name,mod_name,sha1,detected_at FROM \
                        mod_violation_evidence_legacy
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        mod_violation_evidence(id,suspension_id,file_name,mod_name,sha1,\
                        detected_at)
                        SELECT 'migrated-'||s.id,s.id,'','Mod \
                        capturado',s.sha1,s.created_at
                        FROM account_sanctions_legacy s WHERE s.sha1 IS NOT NULL
                          AND NOT EXISTS(SELECT 1 FROM mod_violation_evidence e WHERE \
                        e.suspension_id=s.id AND e.sha1=s.sha1)
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        mods(id,minecraft_version,loader,mod_id,mod_version,artifact_typ\
                        e,name,sha1,storage_path,allowed,required)
                        SELECT f.id,f.minecraft_version,f.loader,f.mod_id,f.mod_version,\
                        f.artifact_type,
                               f.display_name,f.sha1,a.storage_path,
                               (SELECT MIN(r.allowed) FROM server_mod_rules r WHERE \
                        r.sha1=f.sha1),
                               COALESCE((SELECT MAX(r.required) FROM server_mod_rules r \
                        WHERE r.sha1=f.sha1),0)
                        FROM mod_files f JOIN mod_artifacts a ON a.sha1=f.sha1
                        """);
                sql.executeUpdate( """
                        INSERT OR IGNORE INTO \
                        mods(id,minecraft_version,loader,mod_id,mod_version,artifact_type,
                                                   \
                        name,sha1,storage_path,allowed,required)
                        SELECT r.id,s.minecraft_version,s.loader,'legacy_'||substr(r.sha\
                        1,1,16),'unspecified','release',
                               r.display_name,r.sha1,a.storage_path,
                               (SELECT MIN(x.allowed) FROM server_mod_rules x WHERE \
                        x.sha1=r.sha1),
                               (SELECT MAX(x.required) FROM server_mod_rules x WHERE \
                        x.sha1=r.sha1)
                        FROM server_mod_rules r JOIN mod_policy_scopes s ON \
                        s.id=r.scope_id
                        JOIN mod_artifacts a ON a.sha1=r.sha1
                        WHERE NOT EXISTS(SELECT 1 FROM mods m WHERE m.sha1=r.sha1
                            AND m.minecraft_version=s.minecraft_version AND \
                        m.loader=s.loader)
                        """);
                sql.executeUpdate( """
                        INSERT INTO \
                        mods(id,minecraft_version,loader,mod_id,mod_version,artifact_type,
                                         name,sha1,storage_path)
                        SELECT lower(hex(randomblob(16))),'unspecified','default','legac\
                        y_'||substr(a.sha1,1,16),
                               'unspecified','release','Archivo \
                        migrado',a.sha1,a.storage_path
                        FROM mod_artifacts a WHERE NOT EXISTS(SELECT 1 FROM mods m WHERE \
                        m.sha1=a.sha1)
                        """);
                sql.executeUpdate("UPDATE mods SET required=0 WHERE allowed=0");
                for (String table : new String[] {
                    "mod_violation_evidence_legacy", "account_sanctions_legacy",
                            "yggdrasil_joins_legacy", "minecraft_profiles",
                            "textures_legacy", "server_mod_rules", "mod_policy_scopes",
                            "mod_files", "mod_artifacts", "mod_identities_legacy"
                }) {
                    sql.executeUpdate("DROP TABLE " + table);
                }
            }
            return null;
        });
    }

    // =========================================================================
    // LEGACY UPGRADE INPUT
    // =========================================================================

    // Only used to upgrade databases from the previous releases before compacting them.
    private void initializeLegacy() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        email TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        password_hash TEXT NOT NULL,
                        role TEXT NOT NULL CHECK (role IN ('USER', 'MODERATOR', 'ADMIN')),
                        status TEXT NOT NULL CHECK (status IN ('PENDING_VERIFICATION', \
                    'ACTIVE', 'SUSPENDED')),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        password_changed_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        token_hash BLOB NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        revoked_at INTEGER
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS sessions_user_idx ON "
                    + "sessions(user_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON "
                    + "sessions(expires_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS device_sessions (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        token_hash BLOB NOT NULL UNIQUE,
                        device_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        last_used_at INTEGER NOT NULL,
                        revoked_at INTEGER
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS device_sessions_user_idx "
                    + "ON device_sessions(user_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS "
                    + "device_sessions_expiry_idx ON " + "device_sessions(expires_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS device_refresh_history (
                        token_hash BLOB PRIMARY KEY,
                        device_session_id TEXT NOT NULL REFERENCES device_sessions(id) \
                    ON DELETE CASCADE,
                        used_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS game_tickets (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE \
                    CASCADE,
                        token_hash BLOB NOT NULL UNIQUE,
                        server_scope TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        used_at INTEGER
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS game_tickets_expiry_idx "
                    + "ON game_tickets(expires_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS account_tokens (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        type TEXT NOT NULL CHECK (type IN ('EMAIL_VERIFY', \
                    'PASSWORD_RESET')),
                        token_hash BLOB NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        used_at INTEGER
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS account_tokens_user_idx "
                    + "ON account_tokens(user_id, type)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS email_verification_codes (
                        account_token_id TEXT PRIMARY KEY REFERENCES account_tokens(id) \
                    ON DELETE CASCADE,
                        code_hash BLOB NOT NULL,
                        expires_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS email_change_requests (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        new_email TEXT NOT NULL,
                        token_hash BLOB NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        used_at INTEGER
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS email_change_requests_user_idx "
                    + "ON email_change_requests(user_id, used_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS textures (
                        hash TEXT PRIMARY KEY,
                        byte_size INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS minecraft_profiles (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE \
                    CASCADE,
                        name TEXT NOT NULL,
                        name_normalized TEXT NOT NULL UNIQUE,
                        skin_hash TEXT REFERENCES textures(hash),
                        skin_model TEXT NOT NULL DEFAULT 'classic'
                            CHECK (skin_model IN ('classic', 'slim')),
                        cape_hash TEXT REFERENCES textures(hash),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS minecraft_profiles_name_idx "
                    + "ON minecraft_profiles(name_normalized)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS yggdrasil_joins (
                        server_id TEXT PRIMARY KEY,
                        profile_id TEXT NOT NULL REFERENCES minecraft_profiles(id) ON \
                    DELETE CASCADE,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE \
                    CASCADE,
                        username_normalized TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS yggdrasil_joins_expiry_idx "
                    + "ON yggdrasil_joins(expires_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS account_sanctions (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        sha1 TEXT,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        created_by TEXT REFERENCES users(id) ON DELETE SET NULL,
                        policy_id TEXT NOT NULL,
                        resolved_at INTEGER,
                        resolution TEXT CHECK (resolution IN ('LIFTED', 'BANNED', \
                    'AUTO_EXPIRED'))
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS account_sanctions_user_idx "
                    + "ON account_sanctions(user_id, resolution, expires_at)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS mod_violation_evidence (
                        id TEXT PRIMARY KEY,
                        suspension_id TEXT NOT NULL REFERENCES account_sanctions(id) ON \
                    DELETE CASCADE,
                        file_name TEXT NOT NULL,
                        mod_name TEXT NOT NULL,
                        sha1 TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        detected_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS mod_evidence_suspension_idx "
                    + "ON mod_violation_evidence(suspension_id)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS mod_artifacts (
                        sha1 TEXT PRIMARY KEY,
                        storage_path TEXT NOT NULL UNIQUE,
                        byte_size INTEGER NOT NULL,
                        original_file_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        uploaded_by TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS mods (
                        id TEXT PRIMARY KEY,
                        slug TEXT NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS mod_files (
                        id TEXT PRIMARY KEY,
                        mod_uuid TEXT NOT NULL REFERENCES mods(id) ON DELETE RESTRICT,
                        minecraft_version TEXT NOT NULL,
                        loader TEXT NOT NULL,
                        mod_id TEXT NOT NULL,
                        mod_version TEXT NOT NULL,
                        artifact_type TEXT NOT NULL
                            CHECK (artifact_type IN ('release', 'sourcecode', 'javadoc', \
                    'installer')),
                        display_name TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        sha1 TEXT NOT NULL REFERENCES mod_artifacts(sha1) ON DELETE \
                    RESTRICT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        uploaded_by TEXT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
                        UNIQUE(minecraft_version, loader, mod_id, mod_version, \
                    artifact_type)
                    )
                    """);
            migrateModFileUuids(connection);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mod_files_sha1_idx ON "
                    + "mod_files(sha1)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mod_files_mod_uuid_idx "
                    + "ON mod_files(mod_uuid)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS mod_policy_scopes (
                        id TEXT PRIMARY KEY,
                        server_scope TEXT NOT NULL,
                        minecraft_version TEXT NOT NULL,
                        loader TEXT NOT NULL,
                        revision INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(server_scope, minecraft_version, loader)
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS mod_policy_scopes_server_idx "
                    + "ON mod_policy_scopes(server_scope)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS server_mod_rules (
                        id TEXT PRIMARY KEY,
                        scope_id TEXT NOT NULL REFERENCES mod_policy_scopes(id) ON \
                    DELETE CASCADE,
                        sha1 TEXT NOT NULL REFERENCES mod_artifacts(sha1) ON DELETE \
                    RESTRICT,
                        allowed INTEGER NOT NULL CHECK (allowed IN (0, 1)),
                        display_name TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        required INTEGER NOT NULL DEFAULT 0 CHECK (required IN (0, 1)),
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(scope_id, sha1)
                    )
                    """);
            migrateModRulesBoolean(connection);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS server_mod_rules_scope_idx "
                    + "ON server_mod_rules(scope_id, allowed)");
            statement.executeUpdate( """
                    CREATE TABLE IF NOT EXISTS audit_events (
                        id TEXT PRIMARY KEY,
                        actor_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                        target_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                        action TEXT NOT NULL,
                        details TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            migrateUserColumns(connection);
            migrateAccountSanctions(connection);
        }
    }

    // =========================================================================
    // LEGACY ACCOUNT AND SANCTION UPGRADES
    // =========================================================================

    private static void migrateUserColumns(Connection connection) throws SQLException {
        if (!hasColumn(connection, "users", "username_normalized") && !hasColumn(
                connection, "email_change_requests", "new_email_normalized")) return;
        migrateWithForeignKeysDisabled(connection, current -> {
            try (Statement statement = current.createStatement()) {
                if (hasColumn(current, "users", "username_normalized")) {
                    statement.executeUpdate( """
                            CREATE TABLE users_rebuilt (
                                id TEXT PRIMARY KEY,
                                username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                                email TEXT NOT NULL COLLATE NOCASE UNIQUE,
                                password_hash TEXT NOT NULL,
                                role TEXT NOT NULL CHECK (role IN ('USER', 'MODERATOR', \
                            'ADMIN')),
                                status TEXT NOT NULL CHECK (status IN \
                            ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL,
                                password_changed_at INTEGER NOT NULL
                            )
                            """);
                    statement.executeUpdate( """
                            INSERT INTO users_rebuilt
                            SELECT id, username, email, password_hash, role, status,
                                   created_at, updated_at, password_changed_at FROM users
                            """);
                    statement.executeUpdate("DROP TABLE users");
                    statement.executeUpdate("ALTER TABLE users_rebuilt RENAME TO users");
                }
                if (hasColumn(current, "email_change_requests", "new_email_normalized")) {
                    statement.executeUpdate(
                            "ALTER TABLE email_change_requests DROP COLUMN "
                            + "new_email_normalized");
                }
            }
            return null;
        });
    }

    private static void migrateAccountSanctions(
            Connection connection) throws SQLException {
        if (!hasColumn(connection, "account_suspensions", "id")
                && !hasColumn(connection, "account_bans", "id")) return;
        migrateWithForeignKeysDisabled(connection, current -> {
            try (Statement statement = current.createStatement()) {
                if (hasColumn(current, "account_suspensions", "id")) {
                    statement.executeUpdate( """
                            INSERT INTO account_sanctions(id, user_id, sha1, reason, \
                            created_at,
                                                          expires_at, created_by, \
                            policy_id, resolved_at, resolution)
                            SELECT s.id, s.user_id,
                                   (SELECT e.sha1 FROM mod_violation_evidence e
                                    WHERE e.suspension_id=s.id ORDER BY e.detected_at, \
                            e.id LIMIT 1),
                                   s.reason, s.created_at,
                                   CASE WHEN s.resolution='BANNED' THEN NULL ELSE \
                            s.expires_at END,
                                   s.resolved_by, s.policy_id, s.resolved_at, s.resolution
                            FROM account_suspensions s
                            """);
                }
                if (hasColumn(current, "account_bans", "id")) {
                    statement.executeUpdate( """
                            UPDATE account_sanctions AS s
                            SET reason=b.reason, expires_at=NULL,
                                created_by=b.created_by,
                                resolved_at=COALESCE(b.lifted_at, b.created_at),
                                resolution=CASE WHEN b.lifted_at IS NULL THEN 'BANNED' \
                            ELSE 'LIFTED' END
                            FROM account_bans b WHERE b.suspension_id=s.id
                            """);
                    statement.executeUpdate( """
                            INSERT INTO account_sanctions(id, user_id, sha1, reason, \
                            created_at,
                                                          expires_at, created_by, \
                            policy_id, resolved_at, resolution)
                            SELECT b.id, b.user_id, NULL, b.reason, b.created_at, NULL, \
                            b.created_by,
                                   'manual', COALESCE(b.lifted_at, b.created_at),
                                   CASE WHEN b.lifted_at IS NULL THEN 'BANNED' ELSE \
                            'LIFTED' END
                            FROM account_bans b WHERE b.suspension_id IS NULL
                                OR NOT EXISTS (SELECT 1 FROM account_sanctions s WHERE \
                            s.id=b.suspension_id)
                            """);
                }
                statement.executeUpdate( """
                        CREATE TABLE mod_violation_evidence_rebuilt (
                            id TEXT PRIMARY KEY,
                            suspension_id TEXT NOT NULL REFERENCES account_sanctions(id) \
                        ON DELETE CASCADE,
                            file_name TEXT NOT NULL, mod_name TEXT NOT NULL, sha1 TEXT \
                        NOT NULL,
                            reason TEXT NOT NULL, detected_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate(
                        "INSERT INTO mod_violation_evidence_rebuilt SELECT * "
                        + "FROM mod_violation_evidence");
                statement.executeUpdate("DROP TABLE mod_violation_evidence");
                statement.executeUpdate(
                        "ALTER TABLE mod_violation_evidence_rebuilt RENAME "
                        + "TO mod_violation_evidence");
                statement.executeUpdate("CREATE INDEX mod_evidence_suspension_idx ON "
                        + "mod_violation_evidence(suspension_id)");
                statement.executeUpdate("DROP TABLE IF EXISTS account_bans");
                statement.executeUpdate("DROP TABLE IF EXISTS account_suspensions");
            }
            return null;
        });
    }

    // =========================================================================
    // FOREIGN-KEY-SAFE CONTENT UPGRADES
    // =========================================================================

    private static void migrateWithForeignKeysDisabled(Connection connection,
            SqlWork<Void> migration) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            try {
                migration.execute(connection);
                try (ResultSet violations = statement.executeQuery(
                        "PRAGMA foreign_key_check")) {
                    if (violations.next()) throw new SQLException(
                            "La migración dejó referencias inválidas.");
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sql) throw sql;
                throw new SQLException("No se pudo migrar la base de datos.", exception);
            } finally {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys=ON");
            }
        }
    }

    private static void migrateModRulesBoolean(
            Connection connection) throws SQLException {
        if (hasColumn(connection, "server_mod_rules", "allowed")
                || !hasColumn(connection, "server_mod_rules", "list_type")) {
            return;
        }
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS server_mod_rules_scope_idx");
            statement.executeUpdate("ALTER TABLE server_mod_rules RENAME TO "
                    + "server_mod_rules_legacy");
            statement.executeUpdate( """
                    CREATE TABLE server_mod_rules (
                        id TEXT PRIMARY KEY,
                        scope_id TEXT NOT NULL REFERENCES mod_policy_scopes(id) ON \
                    DELETE CASCADE,
                        sha1 TEXT NOT NULL REFERENCES mod_artifacts(sha1) ON DELETE \
                    RESTRICT,
                        allowed INTEGER NOT NULL CHECK (allowed IN (0, 1)),
                        display_name TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        required INTEGER NOT NULL DEFAULT 0 CHECK (required IN (0, 1)),
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(scope_id, sha1)
                    )
                    """);
            statement.executeUpdate( """
                    INSERT INTO server_mod_rules(id, scope_id, sha1, allowed, \
                    display_name,
                                                 file_name, required, reason, \
                    created_at, updated_at)
                    SELECT id, scope_id, sha1,
                           CASE WHEN list_type='ALLOWED' THEN 1 ELSE 0 END,
                           display_name, file_name, required, reason, created_at, \
                    updated_at
                    FROM server_mod_rules_legacy
                    """);
            statement.executeUpdate("DROP TABLE server_mod_rules_legacy");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void migrateModFileUuids(Connection connection) throws SQLException {
        if (!hasColumn(connection, "mod_files", "mod_uuid")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE mod_files ADD COLUMN mod_uuid TEXT "
                        + "REFERENCES mods(id)");
            }
        }
        try (PreparedStatement slugs = connection.prepareStatement(
                "SELECT DISTINCT mod_id FROM mod_files");
                ResultSet rows = slugs.executeQuery()) {
            while (rows.next()) {
                String slug = rows.getString(1);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR IGNORE INTO mods(id, slug, created_at) "
                        + "VALUES (?, ?, ?)");
                        PreparedStatement update = connection.prepareStatement(
                        ("UPDATE mod_files SET mod_uuid=(SELECT id FROM mods "
                        + "WHERE slug=?) ") + "WHERE mod_id=? AND mod_uuid IS NULL")) {
                    insert.setString(1, java.util.UUID.randomUUID().toString());
                    insert.setString(2, slug);
                    insert.setLong(3, java.time.Instant.now().toEpochMilli());
                    insert.executeUpdate();
                    update.setString(1, slug);
                    update.setString(2, slug);
                    update.executeUpdate();
                }
            }
        }
    }

    private static boolean hasColumn(Connection connection,
            String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                "PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (rows.getString("name").equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    // =========================================================================
    // CONNECTIONS AND TRANSACTIONS
    // =========================================================================

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl,
                connectionProperties);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    public <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new SQLException("Falló una transacción", exception);
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws Exception;
    }
}

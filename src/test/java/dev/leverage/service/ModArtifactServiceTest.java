package dev.leverage.service;

import dev.leverage.db.Database;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModArtifactServiceTest {
    @TempDir
    Path temporaryDirectory;

    private Database database;
    private AuthService.AuthenticatedUser admin;
    private ModArtifactService artifacts;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        database.initialize();
        RecoveryMailer mailer = new RecoveryMailer() {
            @Override
            public void sendEmailVerification(String email, String username,
                    String token, Instant expiresAt) {
            }

            @Override
            public void sendPasswordReset(String email, String username,
                    String token, Instant expiresAt) {
            }
        };
        AuthService auth = new AuthService(database, new PasswordHasher(10_000),
                mailer, Clock.systemUTC(),
                Duration.ofHours(12), Duration.ofHours(24), Duration.ofMinutes(30), true);
        auth.createBootstrapAdmin("Admin", "admin@example.test", "admin-password-long");
        admin = auth.authenticate(auth.login("Admin",
                "admin-password-long").accessToken());
        artifacts = new ModArtifactService(database,
                temporaryDirectory.resolve("mods/sha1"), Clock.systemUTC());
    }

    @Test
    void resourcepacksUseTheSameStorageRulesAndSha1Verification() throws Exception {
        byte[] data = jar("pack.mcmeta",
                "{\"pack\":{\"pack_format\":46,\"description\":\"Example\"}}");
        var file = artifacts.publish(admin, new ByteArrayInputStream(data), data.length,
                ModArtifactService.ContentType.RESOURCEPACK, "1.21.4",
                null, "example", "1.0", "release");
        assertEquals("resourcepack", file.type());
        assertEquals("default", file.loader());
        assertEquals("example-1.0.zip", file.fileName());
        assertEquals("/resourcepack/1.21.4/example/1.0/release", file.downloadPath());
        assertEquals(file.sha1(), artifacts.download(
                ModArtifactService.ContentType.RESOURCEPACK,
                "1.21.4", null, "example", "1.0", "release").sha1());
        assertThrows(ServiceException.class, () -> artifacts.download("1.21.4",
                "default", "example", "1.0", "release"));

        ModPolicyService policies = new ModPolicyService(database,
                temporaryDirectory.resolve("unused.json"),
                Clock.systemUTC(), Duration.ofDays(30), artifacts);
        assertEquals("UNKNOWN", policies.checkHash(admin, "survival",
                "1.21.4", "fabric", file.sha1()).status());
        artifacts.setRule(admin, file.sha1(), null, "1.21.4", null, true,
                true, null, ModArtifactService.ContentType.RESOURCEPACK);
        assertEquals("ALLOWED", policies.checkHash(admin, "survival",
                "1.21.4", "fabric", file.sha1()).status());
        assertEquals("REJECTED", policies.verifyInventoryHashes(admin, "survival",
                "1.21.4", "fabric", java.util.List.of()).status());
        assertEquals("APPROVED", policies.verifyInventoryHashes(admin, "survival",
                "1.21.4", "fabric", java.util.List.of(file.sha1())).status());
        assertEquals(0, artifacts.catalog(null, "1.21.4", "default").allowed().size());
        assertEquals(1, artifacts.catalog(null, "1.21.4", null,
                ModArtifactService.ContentType.RESOURCEPACK).allowed().size());
        artifacts.setRule(admin, file.sha1(), null, "1.21.4", null, false,
                false, null, ModArtifactService.ContentType.RESOURCEPACK);
        assertEquals("SUSPENDED", policies.checkHash(admin, "other-server",
                "26.3", "forge", file.sha1()).status());
        try (var connection = database.open(); var sql = connection.createStatement();
             var rows = sql.executeQuery(
                "SELECT content_type,allowed,required FROM mods")) {
            assertTrue(rows.next());
            assertEquals("resourcepack", rows.getString(1));
            assertEquals(0, rows.getInt(2));
            assertEquals(0, rows.getInt(3));
            org.junit.jupiter.api.Assertions.assertFalse(rows.next());
        }
    }

    @Test
    void identicalContentSharesStorageAndGlobalDecisionAcrossBothTypes(
            ) throws Exception {
        byte[] data = jar("sample.txt", "identical data");
        var mod = artifacts.publish(admin, new ByteArrayInputStream(data), data.length,
                "1.21.4", "fabric", "example", "1.0", "release");
        var pack = artifacts.publish(admin, new ByteArrayInputStream(data), data.length,
                ModArtifactService.ContentType.RESOURCEPACK, "1.21.4",
                null, "example", "1.0", "release");
        assertNotEquals(mod.uuid(), pack.uuid());
        assertEquals(mod.sha1(), pack.sha1());
        artifacts.setRule(admin, mod.sha1(), null, "1.21.4",
                "fabric", false, false, null);
        assertEquals(1, artifacts.catalog(null, "1.21.4", null,
                ModArtifactService.ContentType.RESOURCEPACK).blocked().size());
        artifacts.setRule(admin, pack.sha1(), null, "1.21.4", null, true,
                false, null, ModArtifactService.ContentType.RESOURCEPACK);
        assertEquals(1, artifacts.catalog(null, "1.21.4", "fabric").allowed().size());
        assertEquals(artifacts.download("1.21.4", "fabric",
                "example", "1.0", "release").path(),
                artifacts.download(ModArtifactService.ContentType.RESOURCEPACK,
                "1.21.4", null, "example", "1.0", "release").path());
    }

    @Test
    void storesBySha1AndDatabaseRulesDriveThePolicy() throws Exception {
        byte[] allowedJar = jar("fabric.mod.json", "{\"id\":\"allowed\"}");
        byte[] blockedJar = jar("META-INF/mods.toml", "modLoader=\"javafml\"");

        var allowed = artifacts.upload(admin, new ByteArrayInputStream(
                allowedJar), allowedJar.length,
                "survival", "26.3", "fabric", true, "Allowed Mod", "allowed.jar",
                true, "");
        var blocked = artifacts.upload(admin, new ByteArrayInputStream(
                blockedJar), blockedJar.length,
                "survival", "26.3", "fabric", false, "Blocked Mod", "blocked.jar",
                false, "No permitido");

        assertNotEquals(allowed.sha1(), blocked.sha1());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("mods/sha1")
                .resolve(allowed.storagePath())));
        var catalog = artifacts.catalog("survival", "26.3", "fabric");
        assertEquals(1, catalog.allowed().size());
        assertEquals(1, catalog.blocked().size());
        assertEquals(allowed.sha1(), artifacts.download(allowed.sha1(),
                "survival", "26.3", "fabric").sha1());
        assertThrows(ServiceException.class,
                () -> artifacts.download(blocked.sha1(), "survival", "26.3", "fabric"));

        ModPolicyService policies = new ModPolicyService(database,
                temporaryDirectory.resolve("missing.json"),
                Clock.systemUTC(), Duration.ofDays(30), artifacts);
        var policy = policies.policy("survival", "26.3", "fabric");
        assertEquals(allowed.sha1(), policy.allowed().getFirst().sha1());
        assertEquals(blocked.sha1(), policy.blocked().getFirst().sha1());
    }

    @Test
    void publishesMetadataAddressedFilesAndSetsPolicySeparately() throws Exception {
        byte[] sourcesJar = jar("net/example/Sodium.java", "class Sodium {}");
        var sources = artifacts.publish(admin,
                new ByteArrayInputStream(sourcesJar), sourcesJar.length,
                "1.21.4", "fabric", "sodium", "0.6.5", "sourcecode");
        assertEquals("sodium-0.6.5-sources.zip", sources.fileName());

        byte[] releaseJar = "opaque release bytes".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var release = artifacts.publish(admin,
                new ByteArrayInputStream(releaseJar), releaseJar.length,
                "1.21.4", "fabric", "sodium", "0.6.5", "release");

        assertEquals("sodium", release.modId());
        assertNotEquals(sources.uuid(), release.uuid());
        assertEquals("/mod/1.21.4/fabric/sodium/0.6.5/release", release.downloadPath());
        assertEquals(release.sha1(), artifacts.download(
                "1.21.4", "fabric", "sodium", "0.6.5", "release").sha1());

        var rule = artifacts.setRule(admin, release.sha1(),
                "survival", "1.21.4", "fabric",
                true, true, "");
        assertTrue(rule.allowed());
        assertEquals(release.downloadPath(), rule.downloadPath());
        assertEquals(release.downloadPath(), artifacts.catalog("survival",
                "1.21.4", "fabric")
                .allowed().getFirst().downloadPath());
    }

    private static byte[] jar(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void globalHashDecisionNeedsNoReasonAndDownloadNameIsGenerated() throws Exception {
        byte[] data = jar("fabric.mod.json", "{\"id\":\"sodium\"}");
        var file = artifacts.publish(admin, new ByteArrayInputStream(data), data.length,
                "1.21.4", "fabric", "sodium", "0.6.5", "release");
        assertTrue(artifacts.catalog(null, "1.21.4", "fabric").allowed().isEmpty());
        artifacts.setRule(admin, file.sha1(), null, "1.21.4",
                "fabric", true, false, null);
        assertEquals("sodium-0.6.5.jar", artifacts.download("1.21.4",
                "fabric", "sodium", "0.6.5", "release").fileName());
        assertTrue(artifacts.policy("another-server", "26.3",
                "fabric").orElseThrow().allowed().stream()
                .anyMatch(rule -> rule.sha1().equals(file.sha1())));
        String allowedPolicy = artifacts.catalog(null, "1.21.4", "fabric").policyId();
        artifacts.setRule(admin, file.sha1(), null, "1.21.4",
                "fabric", false, false, null);
        assertNotEquals(allowedPolicy, artifacts.catalog(null,
                "1.21.4", "fabric").policyId());
        assertEquals(1, artifacts.catalog("any-server", "1.21.4",
                "fabric").blocked().size());
        assertThrows(ServiceException.class, ()->artifacts.download(file.sha1(),
                null, "1.21.4", "fabric"));
        try (var connection=database.open(); var sql=connection.createStatement();
                var rows=sql.executeQuery("SELECT COUNT(*) FROM mods")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void oneTableDrivesHashChecksAndAutomaticSuspensionWithoutPerModReasons(
            ) throws Exception {
        byte[] data = jar("fabric.mod.json", "{\"id\":\"blocked\"}");
        var file = artifacts.publish(admin, new ByteArrayInputStream(data), data.length,
                "1.21.4", "fabric", "blocked", "1.0", "release");
        artifacts.setRule(admin, file.sha1(), null, "1.21.4",
                "fabric", false, false, null);
        ModPolicyService policies = new ModPolicyService(database,
                temporaryDirectory.resolve("unused.json"),
                Clock.systemUTC(), Duration.ofDays(30), artifacts);
        var result = policies.checkHash(admin, "another-server",
                "26.3", "fabric", file.sha1());
        assertEquals("SUSPENDED", result.status());
        assertEquals(file.sha1(), result.blockedMods().getFirst().sha1());
        try (var connection=database.open(); var query=connection.createStatement();
             var rows=query.executeQuery("SELECT by_mod,reason FROM account_sanctions")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            org.junit.jupiter.api.Assertions.assertNull(rows.getString(2));
        }
    }
}

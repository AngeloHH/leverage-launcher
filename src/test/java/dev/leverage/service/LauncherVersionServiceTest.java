package dev.leverage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherVersionServiceTest {
    private static final String CURRENT = "1111111111111111111111111111111111111111";
    private static final String OPTIONAL = "2222222222222222222222222222222222222222";
    private static final String REQUIRED = "3333333333333333333333333333333333333333";

    @TempDir
    Path temporaryDirectory;

    private LauncherVersionService service;

    @BeforeEach
    void setUp() throws Exception {
        Path policy = temporaryDirectory.resolve("launcher-versions.json");
        Files.writeString(policy,
        """
                {"schemaVersion":1,"channels":[{
                  "name":"stable","latestVersion":"3.0.0","releases":[
                    {"version":"3.0.0","sha1":"%s","downloadUrl":"https://example.test/3\
                .jar","allowLaunch":true},
                    {"version":"2.0.0","sha1":"%s","downloadUrl":"https://example.test/2\
                .jar","allowLaunch":true},
                    {"version":"1.0.0","sha1":"%s","downloadUrl":"https://example.test/1\
                .jar","allowLaunch":false}
                  ]
                }]}
                """
        .formatted(CURRENT, OPTIONAL, REQUIRED));
        service = new LauncherVersionService(policy);
    }

    @Test
    void serverClassifiesKnownAndUnknownBuildsByHash() {
        var current = service.check("stable", CURRENT);
        assertEquals(LauncherVersionService.Status.CURRENT, current.status());
        assertTrue(current.canContinue());

        var optional = service.check("stable", OPTIONAL);
        assertEquals(LauncherVersionService.Status.UPDATE_AVAILABLE, optional.status());
        assertTrue(optional.canContinue());

        var required = service.check("stable", REQUIRED);
        assertEquals(LauncherVersionService.Status.UPDATE_REQUIRED, required.status());
        assertFalse(required.canContinue());
        assertTrue(required.recognized());

        var unknown = service.check("stable",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(LauncherVersionService.Status.UPDATE_REQUIRED, unknown.status());
        assertFalse(unknown.canContinue());
        assertFalse(unknown.recognized());
    }
}

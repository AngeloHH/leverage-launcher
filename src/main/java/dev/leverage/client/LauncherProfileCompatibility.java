package dev.leverage.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

// Minimal official-launcher profile container required by external client installers.
public final class LauncherProfileCompatibility {
    private LauncherProfileCompatibility() { }

    public static void ensure(Path minecraftDirectory) throws IOException {
        Path root = minecraftDirectory.toAbsolutePath().normalize();
        Path profile = root.resolve("launcher_profiles.json");
        // Leave existing profiles and symbolic links untouched.
        if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, ".launcher_profiles-", ".tmp");
        try {
            Files.writeString(temporary, "{\n  \"profiles\": {}\n}\n",
                    StandardCharsets.UTF_8);
            try {
                // No replacement: an intervening installer's file must win.
                Files.move(temporary, profile);
            } catch (FileAlreadyExistsException existing) {
                // Another launcher/installer created the profile while we prepared ours.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

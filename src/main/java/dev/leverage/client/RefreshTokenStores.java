package dev.leverage.client;

import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import static dev.leverage.i18n.I18n.t;

// Select by OS; open native keychains lazily, not during launcher startup.
final class RefreshTokenStores {
    private RefreshTokenStores() { }

    static RefreshTokenStore forDirectory(Path directory) throws IOException {
        if (Platform.isWindows()) return new WindowsDpapiTokenStore(directory);
        String service = service(directory);
        if (Platform.isMac()) return new MacKeychainTokenStore(service);
        if (Platform.isLinux()) return new LinuxSecretServiceTokenStore(service);
        return new RefreshTokenStore() {
            public void write(String id, String token) throws IOException {
                throw new IOException(t("credentials.secureUnavailable"));
            }
            public Optional<String> read(String id) { return Optional.empty(); }
            public void delete(String id) { }
        };
    }

    private static String service(Path directory) {
        try {
            byte[] path = directory.toAbsolutePath().normalize().toString().getBytes(
                    StandardCharsets.UTF_8);
            return "dev.leverage.launcher." + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(path));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}"))
                throw new IllegalArgumentException(t("credentials.invalidId"));
    }
}

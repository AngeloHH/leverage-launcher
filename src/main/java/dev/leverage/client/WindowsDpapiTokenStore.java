package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class WindowsDpapiTokenStore implements RefreshTokenStore {
    private final Path directory;

    public WindowsDpapiTokenStore(Path leverageDirectory) throws IOException {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException(t("credentials.windowsOnly"));
        }
        this.directory = leverageDirectory.toAbsolutePath().normalize().resolve(
                "credentials");
        Files.createDirectories(directory);
    }

    @Override public void write(String credentialId,
            String refreshToken) throws IOException {
        Path destination = credentialPath(credentialId);
        byte[] clear = refreshToken.getBytes(StandardCharsets.UTF_8);
        byte[] protectedValue;
        try {
            protectedValue = Crypt32Util.cryptProtectData(clear);
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException(t("credentials.encrypt"), exception);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
        Path temporary = destination.resolveSibling(destination.getFileName()
                + "." + UUID.randomUUID() + ".part");
        try {
            Files.write(temporary, protectedValue);
            moveAtomically(temporary, destination);
        } finally {
            Arrays.fill(protectedValue, (byte) 0);
            Files.deleteIfExists(temporary);
        }
    }

    @Override public Optional<String> read(String credentialId) throws IOException {
        Path source = credentialPath(credentialId);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        byte[] protectedValue = Files.readAllBytes(source);
        byte[] clear = null;
        try {
            clear = Crypt32Util.cryptUnprotectData(protectedValue);
            return Optional.of(new String(clear, StandardCharsets.UTF_8));
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException(t("credentials.decrypt"), exception);
        } finally {
            Arrays.fill(protectedValue, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    @Override public void delete(String credentialId) throws IOException {
        Files.deleteIfExists(credentialPath(credentialId));
    }

    private Path credentialPath(String credentialId) {
        if (credentialId == null || !credentialId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException(t("credentials.invalidId"));
        }
        Path result = directory.resolve(credentialId + ".bin").normalize();
        if (!result.startsWith(directory)) {
            throw new IllegalArgumentException(t("credentials.invalidId"));
        }
        return result;
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

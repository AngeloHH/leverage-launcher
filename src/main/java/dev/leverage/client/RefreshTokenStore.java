package dev.leverage.client;

import java.io.IOException;
import java.util.Optional;

// Metadata stores a lookup ID; secret bytes belong to the selected OS store.
public interface RefreshTokenStore {
    void write(String credentialId, String refreshToken) throws IOException;

    Optional<String> read(String credentialId) throws IOException;

    void delete(String credentialId) throws IOException;
}

package dev.leverage.client;

import static dev.leverage.i18n.I18n.t;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class RememberedAccountManager {
    private final LocalAccountStore accounts;
    private final RefreshTokenStore tokens;

    public RememberedAccountManager(LocalAccountStore accounts,
            RefreshTokenStore tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    public synchronized void remember(UUID userId, String username,
            String host, String refreshToken) throws IOException {
        String credentialId = userId.toString();
        tokens.write(credentialId, refreshToken);
        try {
            accounts.remember(new LocalAccountStore.Account(userId,
                    username, host, credentialId, Instant.now()));
        } catch (IOException failure) {
            try {
                tokens.delete(credentialId);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public synchronized Optional<SavedLogin> active() throws IOException {
        Optional<LocalAccountStore.Account> account = accounts.active();
        if (account.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> token = tokens.read(account.get().credentialId());
        return token.map(value -> new SavedLogin(account.get(), value));
    }

    // Public account metadata only; does not decrypt or expose the refresh token.
    public synchronized Optional<LocalAccountStore.Account> account() throws IOException {
        return accounts.active();
    }

    public synchronized void rotate(String refreshToken) throws IOException {
        LocalAccountStore.Account current = accounts.active().orElseThrow(
                () -> new IOException(t("credentials.noActiveAccount")));
        tokens.write(current.credentialId(), refreshToken);
        accounts.remember(new LocalAccountStore.Account(current.userId(),
                current.username(), current.host(),
                current.credentialId(), Instant.now()));
    }

    public synchronized void forgetActive() throws IOException {
        Optional<LocalAccountStore.Account> current = accounts.active();
        if (current.isEmpty()) return;
        // Unavailable keychains must not leave auto-sign-in metadata behind.
        try { tokens.delete(current.get().credentialId()); }
        finally { accounts.remove(current.get().userId()); }
    }

    // Change metadata only for the account/server that authorized the operation.
    public synchronized void updateUsername(UUID userId, String host,
            String username) throws IOException {
        var current = accounts.active();
        if (current.isEmpty() || !matches(current.get(), userId, host)) return;
        var account = current.get();
        accounts.remember(new LocalAccountStore.Account(account.userId(), username,
                account.host(), account.credentialId(), account.lastUsedAt()));
    }

    public synchronized void forget(UUID userId, String host) throws IOException {
        var current = accounts.active();
        if (current.isPresent() && matches(current.get(), userId, host)) forgetActive();
    }

    private static boolean matches(LocalAccountStore.Account account,
            UUID userId, String host) {
        return account.userId().equals(userId) && LauncherPreferences.validateServerUrl(
                account.host()).equals(LauncherPreferences.validateServerUrl(host));
    }

    public record SavedLogin(LocalAccountStore.Account account, String refreshToken) {
    }
}

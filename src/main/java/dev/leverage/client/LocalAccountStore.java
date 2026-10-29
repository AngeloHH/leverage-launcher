package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// JSON holds account metadata and a lookup ID, never the refresh token itself.
public final class LocalAccountStore {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(Instant.class,
            (JsonSerializer<Instant>)(value, type, context) -> new JsonPrimitive(
            value.toString())).registerTypeAdapter(Instant.class,
            (JsonDeserializer<Instant>)(value, type, context) -> Instant.parse(
            value.getAsString())).setPrettyPrinting().create();
    private final Path file;

    public LocalAccountStore(Path minecraftDirectory) throws IOException {
        Path directory = minecraftDirectory.toAbsolutePath().normalize().resolve(
                "leverage");
        Files.createDirectories(directory);
        this.file = directory.resolve("accounts.json");
    }

    public Path leverageDirectory() {
        return file.getParent();
    }

    public synchronized Optional<Account> active() throws IOException {
        Data data = read();
        return data.activeAccountId == null ? Optional.empty()
                : Optional.ofNullable(data.accounts.get(data.activeAccountId));
    }

    public synchronized void remember(Account account) throws IOException {
        Data data = read();
        data.accounts.put(account.userId().toString(), account);
        data.activeAccountId = account.userId().toString();
        write(data);
    }

    public synchronized void remove(UUID userId) throws IOException {
        Data data = read();
        Account removed = data.accounts.remove(userId.toString());
        if (removed != null && userId.toString().equals(data.activeAccountId)) {
            data.activeAccountId = null;
        }
        write(data);
    }

    private Data read() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Data();
        }
        try (var reader = Files.newBufferedReader(file)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null || data.schemaVersion != 1 || data.accounts == null) {
                throw new IOException(I18n.t("prefs.invalidFile", file));
            }
            return data;
        } catch (RuntimeException exception) {
            throw new IOException(I18n.t("prefs.readFile", file), exception);
        }
    }

    private void write(Data data) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(temporary, GSON.toJson(data));
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Account(UUID userId, String username, String host,
            String credentialId, Instant lastUsedAt) {
        public Account {
            if (userId == null || username == null || username.isBlank()
                    || host == null || host.isBlank() || credentialId == null
                    || credentialId.isBlank() || lastUsedAt == null) {
                throw new IllegalArgumentException(t("credentials.localIncomplete"));
            }
        }
    }

    private static final class Data {
        private int schemaVersion = 1;
        private String activeAccountId;
        private Map<String, Account> accounts = new LinkedHashMap<>();
    }
}

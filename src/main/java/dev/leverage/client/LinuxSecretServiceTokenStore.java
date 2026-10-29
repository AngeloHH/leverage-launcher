package dev.leverage.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import static dev.leverage.i18n.I18n.t;

// Secret Service via libsecret's helper. Only non-secret lookup attributes enter argv.
final class LinuxSecretServiceTokenStore implements RefreshTokenStore {
    private final String service;
    LinuxSecretServiceTokenStore(String service) { this.service = service; }

    @Override public void write(String id, String token) throws IOException {
        byte[] clear = token.getBytes(StandardCharsets.UTF_8);
        try (Result result = run(id, List.of("store", "--label=Leverage"), clear)) {
            if (result.code != 0) throw failure();
        } finally { Arrays.fill(clear, (byte) 0); }
    }
    @Override public Optional<String> read(String id) throws IOException {
        try (Result result = run(id, List.of("lookup"), null)) {
            if (result.code == 1 && !result.error && result.output.length == 0)
                    return Optional.empty();
            if (result.code != 0 || result.output.length == 0) throw failure();
            return Optional.of(new String(result.output, StandardCharsets.UTF_8));
        }
    }
    @Override public void delete(String id) throws IOException {
        try (Result result = run(id, List.of("clear"), null)) {
            if (result.code != 0 && !(result.code == 1 && !result.error)) throw failure();
        }
    }

    private Result run(String id, List<String> operation,
            byte[] secret) throws IOException {
        RefreshTokenStores.validateId(id);
        Path executable = List.of(Path.of("/usr/bin/secret-tool"),
                Path.of("/bin/secret-tool")).stream().filter(
                Files::isExecutable).findFirst().orElseThrow(
                LinuxSecretServiceTokenStore::failure);
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(operation);
        command.addAll(List.of("application", service, "account", id));
        Process process = new ProcessBuilder(command).start();
        // Bound and drain both pipes; never log secret helper output.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = executor.submit(() -> bounded(
                    process.getInputStream()));
            Future<byte[]> errors = executor.submit(() -> bounded(
                    process.getErrorStream()));
            byte[] value = null, error = null;
            try {
                try (var stdin = process.getOutputStream()) {
                    if (secret != null) stdin.write(secret);
                }
                if (!process.waitFor(30, TimeUnit.SECONDS))
                        throw new IOException(t("credentials.secureTimeout"));
                value = output.get();
                error = errors.get();
                Result result = new Result(process.exitValue(), value, error.length != 0);
                value = null;
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(t("credentials.secureUnavailable"), interrupted);
            } catch (ExecutionException failed) {
                throw new IOException(t("credentials.secretServiceFailure"),
                        failed.getCause());
            } finally {
                if (value != null) Arrays.fill(value, (byte) 0);
                if (error != null) Arrays.fill(error, (byte) 0);
                process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    private static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(16385);
        if (bytes.length > 16384) {
            Arrays.fill(bytes, (byte) 0);
            throw failure();
        }
        return bytes;
    }
    private static IOException failure() {
        return new IOException(t("credentials.secretServiceFailure"));
    }
    private record Result(int code, byte[] output,
            boolean error) implements AutoCloseable {
        @Override public void close() { Arrays.fill(output, (byte) 0); }
    }
}

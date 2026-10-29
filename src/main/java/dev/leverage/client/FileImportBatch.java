package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// One rejected file must not cancel the rest of a selected batch.
public final class FileImportBatch {
    private FileImportBatch() { }
    @FunctionalInterface public interface Importer<T> {
        T read(Path source) throws IOException;
    }
    public record Failure(Path source, String reason) { }
    public record Result<T>(List<T> added, List<Failure> failures) {
        public Result {
            added = List.copyOf(added);
            failures = List.copyOf(failures);
        }
        public boolean hasErrors() { return !failures.isEmpty(); }
        public String message() {
            String text = I18n.t("import.count", added.size(),
                    (added.size() + failures.size()));
            if (failures.isEmpty()) return text;
            String failed = failures.stream().limit(3).map(
                    failure -> failure.source().getFileName() + " (" + failure.reason()
                    + ")").collect(java.util.stream.Collectors.joining("; "));
            if (failures.size() > 3) failed = I18n.t("import.more",
                    failed, failures.size() - 3);
            return I18n.t("import.failures", text, failed);
        }
    }
    public static <T> Result<T> run(List<Path> sources,
            int capacity, Importer<T> importer) {
        if (capacity < 0) throw new IllegalArgumentException("Negative import capacity");
        List<T> added = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Path source : List.copyOf(sources)) {
            // Rejections do not consume capacity or stop the rest of the selected batch.
            if (added.size() >= capacity) {
                failures.add(new Failure(source, t("import.limit")));
                continue;
            }
            try { added.add(importer.read(source)); }
            catch (IOException | RuntimeException failure) {
                String reason = failure instanceof FileAlreadyExistsException
                        ? t("import.exists") : failure.getMessage() == null
                        ? t("import.readCopyError") : failure.getMessage();
                failures.add(new Failure(source, reason));
            }
        }
        return new Result<>(added, failures);
    }
}

package dev.leverage.client;

import dev.leverage.i18n.I18n;

import static dev.leverage.i18n.I18n.t;

import dev.leverage.service.ModPolicyService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class ModDirectoryScanner {
    private static final int BUFFER_SIZE = 64 * 1024;

    public List<ModPolicyService.ClientMod> scan(Path modsDirectory) throws IOException {
        Path root = directory(modsDirectory);
        List<ModPolicyService.ClientMod> result = new ArrayList<>();
        for (Path file : files(root, true)) if (file.getFileName().toString().toLowerCase(
                Locale.ROOT).endsWith(".jar")) result.add(relative(root, file));
        return List.copyOf(result);
    }

    // ZIPs and unpacked packs use the same server artifact policy.
    public List<ModPolicyService.ClientMod> scanResourcePacks(
            Path directory) throws IOException {
        Path root = directory(directory);
        List<Path> packs = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path folder,
                    BasicFileAttributes attributes) throws IOException {
                normal(root, folder);
                if (!folder.equals(root) && Files.exists(folder.resolve("pack.mcmeta"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    packs.add(folder);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!folder.equals(root) && folder.getFileName().toString().equals(
                        ".leverage-removed")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                normal(root, file);
                if (file.getFileName().toString().toLowerCase(
                        Locale.ROOT).endsWith(".zip")) packs.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,
                    IOException error) throws IOException {
                throw error;
            }
        });
        packs.sort(Comparator.comparing(path -> relativeName(root, path)));
        List<ModPolicyService.ClientMod> result = new ArrayList<>();
        for (Path pack : packs) {
            var content = resourcePack(pack);
            result.add(new ModPolicyService.ClientMod(relativeName(root,
                    pack), content.sha1(), content.size()));
        }
        return List.copyOf(result);
    }

    public List<ModPolicyService.ClientMod> scanGameInstance(
            Path instance) throws IOException {
        List<ModPolicyService.ClientMod> result = new ArrayList<>(
                scan(instance.resolve("mods")));
        result.addAll(scanResourcePacks(instance.resolve("resourcepacks")));
        return List.copyOf(result);
    }

    public ModPolicyService.ClientMod scanFile(Path file) throws IOException {
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) return scanFolder(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) throw new IOException(
                I18n.t("files.linksNamed", file.getFileName()));
        long size = Files.size(file);
        var modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
        String hash = sha1(file);
        if (size != Files.size(file) || !modified.equals(Files.getLastModifiedTime(file,
                LinkOption.NOFOLLOW_LINKS))) throw new IOException(
                I18n.t("files.changedScan", file.getFileName()));
        return new ModPolicyService.ClientMod(file.getFileName().toString(), hash, size);
    }

    public static dev.leverage.content.ResourcePackFingerprint.Snapshot resourcePack(
            Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException(t("resources.noLinks"));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException(t("resources.normalFile"));
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                ? dev.leverage.content.ResourcePackFingerprint.directory(path)
                : dev.leverage.content.ResourcePackFingerprint.zip(path);
    }

    private static Path directory(Path path) throws IOException {
        Path directory = path.toAbsolutePath().normalize();
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) throw new IOException(
                I18n.t("files.notDirectory", directory));
        return directory;
    }

    private ModPolicyService.ClientMod relative(Path root, Path path) throws IOException {
        var file = scanFile(path);
        return new ModPolicyService.ClientMod(relativeName(root,
                path), file.sha1(), file.size());
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void normal(Path root, Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException(I18n.t("files.linksNamed", path.getFileName()));
    }

    private static List<Path> files(Path root, boolean skipRemoved) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path folder,
                    BasicFileAttributes attributes) throws IOException {
                normal(root, folder);
                return skipRemoved && !folder.equals(root)
                        && folder.getFileName().toString().equals(".leverage-removed")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                normal(root, file);
                result.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,
                    IOException error) throws IOException {
                throw error;
            }
        });
        result.sort(Comparator.comparing(path -> relativeName(root, path)));
        return List.copyOf(result);
    }

    // Hash tree content, relative names and sizes, not timestamps or root names.
    private ModPolicyService.ClientMod scanFolder(Path folder) throws IOException {
        var content = dev.leverage.content.ResourcePackFingerprint.directory(folder);
        return new ModPolicyService.ClientMod(folder.getFileName().toString(),
                content.sha1(), content.size());
    }

    private static String sha1(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("La JVM no admite SHA-1.", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

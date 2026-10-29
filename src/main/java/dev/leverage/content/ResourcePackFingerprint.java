package dev.leverage.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipFile;

// A ZIP and its unpacked tree share one identity; archives are never extracted.
public final class ResourcePackFingerprint {
    private static final int MAX_FILES = 100_000;
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private ResourcePackFingerprint() { }
    public record Snapshot(String sha1, long size) { }
    private record Entry(long size, byte[] hash) { }

    public static Snapshot zip(Path archive) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        Set<String> names = new HashSet<>();
        long size = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                if (++count > MAX_FILES) throw new IOException(
                        "El paquete tiene demasiadas entradas.");
                String name = name(entry.isDirectory()
                        && entry.getName().endsWith("/") ? entry.getName().substring(0,
                        entry.getName().length() - 1) : entry.getName());
                if (!names.add(name)) throw new IOException(
                        "Ruta duplicada en el paquete: " + name);
                if (entry.isDirectory()) continue;
                Entry content;
                try (InputStream input = zip.getInputStream(entry)) {
                    content = read(input, MAX_BYTES - size);
                }
                if (entry.getSize() >= 0 && entry.getSize() != content.size())
                        throw new IOException("Entrada incompleta en el paquete: "
                        + name);
                size += content.size();
                entries.put(name, content);
            }
        }
        return finish(entries, size);
    }

    public static Snapshot directory(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root,
                LinkOption.NOFOLLOW_LINKS)) throw new IOException(
                "La carpeta del paquete no es válida.");
        List<Path> before = files(root);
        Map<Path, BasicFileAttributes> stamps = new HashMap<>();
        Map<String, Entry> entries = new TreeMap<>();
        long size = 0;
        for (Path file : before) {
            var stamp = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            Entry content;
            try (InputStream input = Files.newInputStream(file,
                    LinkOption.NOFOLLOW_LINKS)) {
                content = read(input, MAX_BYTES - size);
            }
            stamps.put(file, stamp);
            size += content.size();
            entries.put(name(root.relativize(file).toString().replace('\\',
                    '/')), content);
        }
        // Membership and file identities must remain stable while hashing.
        if (!before.equals(files(root))) throw new IOException(
                "El contenido del paquete cambió durante la comprobación.");
        for (var item : stamps.entrySet()) {
            var after = Files.readAttributes(item.getKey(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var beforeStamp = item.getValue();
            if (!after.isRegularFile() || beforeStamp.size() != after.size()
                    || !beforeStamp.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(beforeStamp.fileKey(),
                    after.fileKey())) throw new IOException(
                    "Un archivo del paquete cambió durante la comprobación.");
        }
        return finish(entries, size);
    }

    private static List<Path> files(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Path realRoot = root.toRealPath();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(dir) || !dir.toRealPath().startsWith(realRoot))
                        throw new IOException("No se permiten enlaces en el paquete.");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
                        || !file.toRealPath().startsWith(realRoot)) throw new IOException(
                        "No se permiten enlaces ni archivos especiales en el paqu"
                        + "ete.");
                if (files.size() >= MAX_FILES) throw new IOException(
                        "El paquete tiene demasiados archivos.");
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,
                    IOException error) throws IOException {
                throw error;
            }
        });
        files.sort(Comparator.comparing(file -> root.relativize(
                file).toString().replace('\\', '/')));
        return List.copyOf(files);
    }

    private static Entry read(InputStream input, long available) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[65536];
        long size = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            size += count;
            if (size > available) throw new IOException(
                    "El contenido del paquete supera los 2 GiB.");
            digest.update(buffer, 0, count);
        }
        return new Entry(size, digest.digest());
    }

    private static Snapshot finish(Map<String, Entry> entries,
            long size) throws IOException {
        if (!entries.containsKey("pack.mcmeta")) throw new IOException(
                "El paquete no contiene pack.mcmeta en su raíz.");
        MessageDigest digest = digest();
        digest.update("leverage-resourcepack-tree-v1\0".getBytes(StandardCharsets.UTF_8));
        // Ignore ZIP order, compression and timestamps in the content identity.
        for (var entry : entries.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(
                    entry.getValue().size()).array());
            digest.update(entry.getValue().hash());
        }
        return new Snapshot(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static String name(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.indexOf('\0') >= 0)
                throw new IOException("Ruta inválida dentro del paquete.");
        for (String part : path.split("/", -1)) if (part.isEmpty()
                || part.equals(".") || part.equals(".."))
                throw new IOException("Ruta inválida dentro del paquete.");
        return path;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}

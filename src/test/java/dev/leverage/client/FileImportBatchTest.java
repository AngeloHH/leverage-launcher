package dev.leverage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileImportBatchTest {
    @TempDir Path root;

    @Test void modsAndPacksContinueAfterCollisionsAndInvalidFilesWithoutOverwriting(
            ) throws Exception {
        for (boolean packs:new boolean[] { false, true }) {
            String extension=packs?".zip":".jar";
            Path target=Files.createDirectory(root.resolve(packs?"packs":"mods"));
            Path first=Files.writeString(root.resolve("first"+extension), "first");
            Path collision=Files.writeString(root.resolve("existing"
                    +extension), "replacement");
            Path existing=Files.writeString(target.resolve(
                    collision.getFileName()), "keep");
            Path invalid=Files.writeString(root.resolve("wrong.txt"), "wrong");
            Path last=Files.writeString(root.resolve("last"+extension), "last");
            var catalog=new InstalledModCatalog();
            var result=FileImportBatch.run(List.of(first, collision,
                    invalid, last), Integer.MAX_VALUE,
                    source->catalog.install(target, source, packs));
            assertEquals(2, result.added().size());
            assertEquals(2, result.failures().size());
            assertTrue(result.hasErrors());
            assertTrue(result.message().contains("2 de 4"));
            assertTrue(result.message().contains(collision.getFileName().toString()));
            assertEquals("keep", Files.readString(existing));
            assertEquals("last", Files.readString(target.resolve(last.getFileName())));
            assertTrue(Files.exists(first));
            assertTrue(Files.exists(last));
            assertThrows(UnsupportedOperationException.class, ()->result.added().clear());
        }
    }

    @Test void badSkinsDoNotConsumeCapacityAndAllRejectedFilesAreReported() {
        var result=FileImportBatch.run(List.of(Path.of("bad.png"),
                Path.of("first.png"), Path.of("overflow.png")), 1,
                path-> {
                    if (path.startsWith("bad.png"))throw new IOException("PNG inválido");
                    return path;
        });
        assertEquals(List.of(Path.of("first.png")), result.added());
        assertEquals(2, result.failures().size());
        assertTrue(result.failures().getLast().reason().contains("límite"));
        var failed=FileImportBatch.run(List.of(Path.of("bad1"), Path.of("bad2")), 10,
                path-> {
                    throw new IOException("inválido");
        });
        assertTrue(failed.added().isEmpty());
        assertEquals(2, failed.failures().size());
        assertTrue(failed.message().contains("0 de 2"));
    }
}

package ru.amereco.amerecolauncher.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Атомарная запись файла: сначала во временный {@code *.part}, затем перенос на место.
 * Обрыв на середине не оставляет полуфабрикат по целевому пути — тот же приём, что в
 * {@link Downloader#downloadToPath(java.net.URI, Path, String, long)}.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /** Создаёт родительские каталоги и атомарно записывает содержимое. */
    public static void write(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.write(tmp, content);
            move(tmp, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** Атомарный перенос с откатом на обычный, если файловая система атомарный не поддерживает. */
    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Рекурсивно удаляет дерево, не поднимая шума на отсутствующем каталоге. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(p);
            }
        }
    }
}

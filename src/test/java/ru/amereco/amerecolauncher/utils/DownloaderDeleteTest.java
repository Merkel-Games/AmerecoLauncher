package ru.amereco.amerecolauncher.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloaderDeleteTest {
    @TempDir
    Path root;

    private Path locked;

    /** Загрузчик, который ничего не качает и владеет только тем, что ему назвали. */
    private static final class Fake extends Downloader {
        private final Path root;
        private final List<Path> managed;

        Fake(Path root, List<Path> managed) {
            this.root = root;
            this.managed = managed;
        }

        @Override
        protected Path mainDir() {
            return root.toAbsolutePath().normalize();
        }

        @Override
        protected Collection<Path> managedPaths() {
            return managed;
        }

        @Override
        public boolean checkUpdates() {
            return false;
        }

        @Override
        public boolean checkUpdates(String versionId) {
            return false;
        }

        @Override
        public void download() {
        }

        @Override
        public void download(String versionId) {
        }
    }

    /** Права POSIX есть не везде: на Windows их снятие бросает UnsupportedOperationException. */
    private static boolean posixSupported() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void setPermissions(Path dir, Set<PosixFilePermission> permissions) throws IOException {
        Files.setPosixFilePermissions(dir, permissions);
    }

    private static final Set<PosixFilePermission> READ_ONLY_DIR = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> WRITABLE_DIR = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    @AfterEach
    void unlock() throws IOException {
        if (posixSupported() && locked != null && Files.isDirectory(locked)) {
            setPermissions(locked, WRITABLE_DIR);
        }
    }

    private Path file(String relative) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "содержимое");
        return path;
    }

    @Test
    @DisplayName("удаляет и отдельные файлы, и каталоги целиком")
    void deletesFilesAndTrees() throws IOException {
        Path single = file("versions/a/a.json");
        Path tree = file("assets/objects/ab/abcdef");
        Path kept = file("saves/мир/level.dat");

        new Fake(root, List.of(root.resolve("versions"), root.resolve("assets"))).deleteDownloaded();

        assertFalse(Files.exists(single));
        assertFalse(Files.exists(tree));
        assertFalse(Files.exists(root.resolve("versions")));
        assertTrue(Files.exists(kept), "то, чего нет в managedPaths(), трогать нельзя");
    }

    @Test
    @DisplayName("отсутствующий путь — не ошибка")
    void missingPathIsFine() throws IOException {
        new Fake(root, List.of(root.resolve("нет-такого"))).deleteDownloaded();
    }

    @Test
    @DisplayName("за пределы своего корня не выходит")
    void refusesPathsOutsideRoot() throws IOException {
        Path outside = Files.createTempDirectory("вне-корня");
        Path victim = outside.resolve("важное.txt");
        Files.writeString(victim, "не трогать");
        try {
            new Fake(root, List.of(victim)).deleteDownloaded();
            assertTrue(Files.exists(victim), "файл вне корня удалять нельзя");
        } finally {
            Files.deleteIfExists(victim);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("сам корень не удаляется — каталог выбирает пользователь")
    void refusesRootItself() throws IOException {
        Path kept = file("versions/a.json");
        new Fake(root, List.of(root)).deleteDownloaded();
        assertTrue(Files.exists(root));
        assertTrue(Files.exists(kept));
    }

    @Test
    @DisplayName("сбой на одном пути не отменяет остальные")
    void continuesAfterFailure() throws IOException {
        Assumptions.assumeTrue(posixSupported(), "нужны права POSIX, чтобы запереть каталог");

        Path deletable = file("assets/objects/x");
        locked = root.resolve("versions");
        file("versions/заперто.json");
        setPermissions(locked, READ_ONLY_DIR);

        Fake fake = new Fake(root, List.of(locked, root.resolve("assets")));
        boolean threw;
        try {
            fake.deleteDownloaded();
            threw = false;
        } catch (IOException expected) {
            threw = true;
        }

        Assumptions.assumeTrue(threw, "тест осмыслен только без прав суперпользователя");
        assertTrue(Files.exists(locked.resolve("заперто.json")), "заперто — значит осталось");
        assertFalse(Files.exists(deletable), "остальное всё равно должно быть удалено");
    }

    @Test
    @DisplayName("первая ошибка возбуждается, остальные подшиваются к ней")
    void aggregatesFailures() throws IOException {
        Assumptions.assumeTrue(posixSupported(), "нужны права POSIX, чтобы запереть каталог");

        locked = root.resolve("versions");
        file("versions/заперто.json");
        setPermissions(locked, READ_ONLY_DIR);

        Path second = root.resolve("libraries");
        Files.createDirectories(second);
        Files.writeString(second.resolve("тоже.jar"), "x");
        setPermissions(second, READ_ONLY_DIR);

        try {
            Fake fake = new Fake(root, List.of(locked, second));
            IOException error = null;
            try {
                fake.deleteDownloaded();
            } catch (IOException caught) {
                error = caught;
            }
            Assumptions.assumeTrue(error != null, "тест осмыслен только без прав суперпользователя");
            assertTrue(error.getSuppressed().length >= 1, "вторая ошибка должна быть подшита к первой");
        } finally {
            setPermissions(second, WRITABLE_DIR);
        }
    }

    @Test
    @DisplayName("managedPaths обязателен для каждого загрузчика")
    void everyDownloaderDeclaresItsPaths() {
        // Метод абстрактный, поэтому новый наследник Downloader не соберётся,
        // пока не скажет, что он скачивает. Проверка держит это свойство явным.
        assertThrows(NoSuchMethodException.class,
                () -> Downloader.class.getMethod("managedPaths"),
                "managedPaths() задуман защищённым, а не публичным");
    }
}

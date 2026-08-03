package ru.amereco.amerecolauncher.httpsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.amereco.amerecolauncher.utils.AtomicFiles;

/**
 * Служебное состояние слияния на диске.
 *
 * <pre>
 * &lt;basePath&gt;/.amereco/base/&lt;профиль&gt;/&lt;путь&gt;       эталон: последняя синхронизированная версия
 * &lt;basePath&gt;/.amereco/incoming/&lt;профиль&gt;/&lt;путь&gt;   временный каталог загрузок под слияние
 * </pre>
 *
 * <p>Подкаталог на профиль обязателен: {@code rpcraft}, {@code rpcraft-admin} и
 * {@code rpcraft-ultra} пишут в один и тот же корень инстанса, и общий эталон заставил бы
 * каждый профиль принимать чужой снимок за свой.
 *
 * <p>Снимки намеренно живут отдельно от локального индекса и переживают его удаление
 * обработчиком сбоя — благодаря этому сорвавшаяся синхронизация не понижает следующее
 * слияние до двустороннего.
 */
public final class MergeState {
    /** Каталог состояния внутри корня инстанса. Записи манифеста внутрь него не допускаются. */
    public static final String DIR_NAME = ".amereco";

    private final Path baseRoot;
    private final Path stagingRoot;

    public MergeState(Path stateDir, String profileName) {
        Path root = stateDir.toAbsolutePath().normalize();
        this.baseRoot = root.resolve("base").resolve(profileName);
        this.stagingRoot = root.resolve("incoming").resolve(profileName);
    }

    public Path baseOf(String relative) throws IOException {
        return resolveInside(baseRoot, relative);
    }

    public Path stagingOf(String relative) throws IOException {
        return resolveInside(stagingRoot, relative);
    }

    /** Содержимое эталона или {@code null}, если снимка нет либо он не читается. */
    public byte[] readBase(String relative) {
        try {
            Path path = baseOf(relative);
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    public void writeBase(String relative, byte[] content) throws IOException {
        AtomicFiles.write(baseOf(relative), content);
    }

    public void deleteBase(String relative) throws IOException {
        Files.deleteIfExists(baseOf(relative));
    }

    /** Убирает временный каталог целиком — вызывается до и после синхронизации. */
    public void clearStaging() {
        try {
            AtomicFiles.deleteRecursively(stagingRoot);
        } catch (IOException leftovers) {
            // Мусор во временном каталоге безвреден: следующий запуск попробует снова.
        }
    }

    /** Снимает профиль с учёта целиком — при отключении профиля. */
    public void purgeAll() throws IOException {
        AtomicFiles.deleteRecursively(baseRoot);
        AtomicFiles.deleteRecursively(stagingRoot);
    }

    /** Каталоги состояния этого профиля — для тех, кто удаляет его файлы снаружи. */
    public java.util.List<Path> stateRoots() {
        return java.util.List.of(baseRoot, stagingRoot);
    }

    /**
     * Куда класть серверную версию при конфликте.
     *
     * <p>Имя всегда строится от пути из манифеста, а не от уже существующего файла,
     * поэтому {@code .new.new} возникнуть не может.
     */
    public static Path conflictSibling(Path target) {
        return target.resolveSibling(target.getFileName() + ".new");
    }

    private static Path resolveInside(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("path traversal blocked: " + relative);
        }
        return target;
    }
}

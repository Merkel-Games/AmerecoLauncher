package ru.amereco.amerecolauncher.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Разбор следов прошлого запуска.
 *
 * <p>Есть два класса падений, которые невозможно поймать изнутри процесса:
 * <ul>
 *   <li><b>Фатальная ошибка JVM</b> — SIGSEGV в драйвере видеокарты (самая частая причина
 *       у игроков), {@code Runtime.halt()}, внутренняя ошибка виртуальной машины. Java-код
 *       после такого не выполняется вообще: ни перехватчик исключений, ни shutdown hook.
 *       Единственный след — файл {@code hs_err_pid*.log}, который JVM успевает записать.</li>
 *   <li><b>Убийство процесса</b> — SIGKILL, «снять задачу», выключение по кнопке, OOM-killer.
 *       Здесь нет даже {@code hs_err}.</li>
 * </ul>
 *
 * <p>Первое ловится сканированием каталога с дампами при следующем запуске, второе —
 * файлом-маркером: создаём при старте, удаляем при штатном завершении. Маркер, найденный
 * на старте, означает, что прошлый запуск умер не по-человечески.
 *
 * <p>Маркер срабатывает и там, где {@code hs_err} не записался, поэтому механизма два,
 * а не один. Чтобы не отправлять два отчёта об одном событии, при найденном дампе
 * маркер считается объяснённым.
 */
public final class PostMortem {

    /** Подкаталог, куда JVM складывает дампы. Совпадает с {@code -XX:ErrorFile}. */
    private static final String CRASH_SUBDIR = "crash";

    /** Файл-маркер живого процесса. */
    private static final String SENTINEL = "running.lock";

    /** Сколько байт дампа отправляем: интересна шапка, дальше идут карты памяти. */
    private static final int MAX_DUMP_BYTES = 128 * 1024;

    /** Дампы старше этого срока разбирать поздно — просто удаляем. */
    private static final long MAX_DUMP_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private PostMortem() {
    }

    /**
     * Каталог для дампов JVM. Путь передаётся игре через {@code -XX:ErrorFile},
     * поэтому он должен существовать до запуска.
     */
    public static Path crashDir(Path diagBaseDir) {
        return diagBaseDir.resolve(CRASH_SUBDIR);
    }

    /**
     * Разбирает следы прошлого запуска и ставит маркер для текущего.
     * Вызывается один раз при старте из {@link CrashReporter#install()}.
     */
    public static void scan(Path diagBaseDir) {
        boolean explained = false;
        try {
            explained = scanDumps(diagBaseDir);
        } catch (Throwable t) {
            CrashReporter.reportQuietly("не удалось разобрать дампы JVM", t);
        }
        try {
            handleSentinel(diagBaseDir, explained);
        } catch (Throwable t) {
            CrashReporter.reportQuietly("не удалось обработать маркер запуска", t);
        }
    }

    /**
     * Находит и отправляет дампы JVM.
     *
     * @return {@code true} если хотя бы один дамп найден — значит грязное завершение
     *         прошлого запуска уже объяснено и отдельный отчёт по маркеру не нужен
     */
    private static boolean scanDumps(Path diagBaseDir) {
        Path dir = crashDir(diagBaseDir);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        List<Path> dumps;
        try (Stream<Path> files = Files.list(dir)) {
            dumps = files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("hs_err") && name.endsWith(".log");
                    })
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            return false;
        }
        if (dumps.isEmpty()) {
            return false;
        }

        long cutoff = System.currentTimeMillis() - MAX_DUMP_AGE_MS;
        boolean found = false;

        for (Path dump : dumps) {
            found = true;
            try {
                boolean fresh = Files.getLastModifiedTime(dump).toMillis() >= cutoff;
                if (fresh && CrashReporter.withinRunLimit()) {
                    submitDump(dump);
                }
            } catch (Throwable t) {
                CrashReporter.reportQuietly("дамп " + dump.getFileName() + " не разобран", t);
            } finally {
                // Удаляем в любом случае: иначе один и тот же дамп будет уезжать
                // при каждом запуске до скончания века.
                deleteQuietly(dump);
            }
        }
        return found;
    }

    /** Читает дамп, вырезает из него командную строку с токеном и ставит в очередь. */
    private static void submitDump(Path dump) throws IOException {
        String raw = readHead(dump);
        if (raw == null || raw.isBlank()) {
            return;
        }
        // Именно stripCommandLine, а не обычный apply: в командной строке JVM токен
        // повторяется в разных формах и строка переносится — построчные регулярки
        // такое пропускают, поэтому секция удаляется целиком.
        String safe = Redactor.stripCommandLine(raw);

        CrashReport report = CrashReporter.base(CrashReport.Kind.JVM_FATAL);
        report.message = extractProblem(safe);
        report.fingerprint = Fingerprint.ofKey("jvm_fatal:" + report.message);
        report.stack = CrashReporter.topLines(safe, CrashReport.MAX_STACK_FRAMES);

        CrashReporter.submit(report, CrashReporter.gzipLog(safe));
    }

    /**
     * Отмечает грязное завершение прошлого запуска и ставит маркер для текущего.
     *
     * @param explained найден дамп JVM, причина уже известна — второй отчёт не нужен
     */
    private static void handleSentinel(Path diagBaseDir, boolean explained) throws IOException {
        Files.createDirectories(diagBaseDir);
        Path sentinel = diagBaseDir.resolve(SENTINEL);

        if (Files.exists(sentinel) && !explained && CrashReporter.withinRunLimit()) {
            CrashReport report = CrashReporter.base(CrashReport.Kind.DIRTY_SHUTDOWN);
            report.fingerprint = Fingerprint.ofKey(
                    "dirty_shutdown:" + report.launcherVersion + ":" + report.os);
            report.message = "Прошлый запуск лаунчера завершился аварийно "
                    + "без дампа JVM (убит процесс, отключение питания или "
                    + "фатальная ошибка без записи файла)";
            CrashReporter.submit(report, null);
        }

        Files.writeString(sentinel, String.valueOf(ProcessHandle.current().pid()),
                StandardCharsets.UTF_8);

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> deleteQuietly(sentinel), "diag-sentinel"));
    }

    /**
     * Первые {@link #MAX_DUMP_BYTES} байт файла — дальше в дампе только карты памяти.
     *
     * <p>Читаем именно первые байты, а не файл целиком: дамп после падения
     * с исчерпанием памяти бывает в десятки мегабайт, а нужна только шапка.
     */
    private static String readHead(Path file) throws IOException {
        byte[] buffer = new byte[MAX_DUMP_BYTES];
        int read = 0;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            int chunk;
            while (read < buffer.length
                    && (chunk = in.read(buffer, read, buffer.length - read)) > 0) {
                read += chunk;
            }
        }
        return new String(buffer, 0, read, StandardCharsets.UTF_8);
    }

    /**
     * Достаёт из дампа строку с описанием проблемы.
     *
     * <p>Формат: {@code # SIGSEGV (0xb) at pc=0x00007f..., pid=1234, tid=5678}.
     * Адреса и идентификаторы процесса из неё убираются — иначе каждый краш
     * получит уникальный отпечаток и склейки не будет.
     */
    private static String extractProblem(String dump) {
        for (String raw : dump.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("#") && (line.contains("SIG") || line.contains("Error")
                    || line.contains("fatal error") || line.contains("EXCEPTION_"))) {
                String cleaned = line.replaceFirst("^#\\s*", "")
                        .replaceAll("0x[0-9a-fA-F]+", "0x…")
                        .replaceAll("\\b(pid|tid)=\\d+", "$1=…")
                        .strip();
                if (!cleaned.isBlank()) {
                    return cleaned.length() > CrashReport.MAX_MESSAGE
                            ? cleaned.substring(0, CrashReport.MAX_MESSAGE)
                            : cleaned;
                }
            }
        }
        return "Фатальная ошибка JVM";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}

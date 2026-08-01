package ru.amereco.amerecolauncher.minecraft;

import ru.amereco.amerecolauncher.Log;
import ru.amereco.amerecolauncher.diag.CrashReporter;
import ru.amereco.amerecolauncher.diag.Redactor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Наблюдатель за выводом дочернего процесса игры: ищет признаки падения
 * и после завершения собирает отчёт.
 *
 * <h3>Почему нельзя обойтись кодом возврата</h3>
 * <ul>
 *   <li>{@code exitCode == 0} <b>не</b> означает, что всё хорошо: обработчики
 *       модлоадеров ловят исключение, печатают краш-репорт и выходят нормально.</li>
 *   <li>{@code exitCode != 0} без краш-репорта — это может быть и падение, и
 *       снятие процесса пользователем, и OOM-killer (код 137 на Linux).</li>
 * </ul>
 * Поэтому основной признак — маркер, который Minecraft печатает сама, а код возврата
 * лишь уточняет картину.
 *
 * <h3>Почему вывод больше не наследуется</h3>
 * Раньше стоял {@code Redirect.INHERIT}: вывод игры шёл прямо в файловые дескрипторы
 * лаунчера, минуя Java. Прочитать его было нельзя, то есть маркер краша было нечем
 * поймать. Теперь вывод читается через пайп — но <b>не</b> уходит в {@code launcher.log}:
 * там мегабайты отладки модов и, что важнее, чужой игровой чат.
 */
public final class GameCrashDetector {

    /** Строка, которую Minecraft печатает при сохранении краш-репорта. */
    private static final Pattern CRASH_MARKER = Pattern.compile(
            "#@!@#\\s*Game crashed!\\s*Crash report saved to:\\s*#@!@#\\s*(?<path>.+)");

    /** Признаки того, что JVM не смогла даже стартовать. */
    private static final String[] JVM_STARTUP_FAILURES = {
        "Could not create the Java Virtual Machine",
        "Error occurred during initialization of VM",
        "Unrecognized option",
        "Unrecognized VM option",
    };

    /** Потолок файла с выводом игры. Дальше пайп читаем, но на диск не пишем. */
    private static final long MAX_OUTPUT_BYTES = 16L * 1024 * 1024;

    /** Сколько хвоста лога игры прикладываем к отчёту. */
    private static final int LOG_TAIL_BYTES = 400 * 1024;

    /** Потолок размера читаемого краш-репорта. Обычно он куда меньше. */
    private static final int MAX_CRASH_REPORT_BYTES = 200 * 1024;

    private final Path gameDir;
    private final Path outputFile;
    private final long startedAt = System.currentTimeMillis();

    /** Путь из маркера краша; {@code null}, если маркера не было. */
    private volatile String markerCrashPath;

    /** Строка с ошибкой запуска JVM, если она была. */
    private volatile String jvmStartupError;

    /**
     * @param gameDir рабочий каталог игры — там лежат {@code logs/} и {@code crash-reports/}
     * @param diagDir каталог диагностики, туда пишется копия вывода
     */
    public GameCrashDetector(Path gameDir, Path diagDir) {
        this.gameDir = gameDir;
        this.outputFile = diagDir != null ? diagDir.resolve("game-output.log") : null;
    }

    /**
     * Читает вывод процесса до конца потока. Блокирующий — вызывать в отдельном потоке.
     *
     * <p>Читать обязательно, даже если содержимое не нужно: заполненный буфер пайпа
     * останавливает игру намертво.
     */
    public void pump(InputStream in) {
        PrintStream console = Log.console();
        long written = 0;

        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8));
             PrintStream out = openOutputFile()) {

            String line;
            while ((line = reader.readLine()) != null) {
                // В консоль — как есть: разработчику нужен живой вывод игры.
                // В launcher.log при этом ничего не попадает.
                console.println(line);

                // Разбираем оригинал: маркер краша содержит путь, который вычистка
                // от имени пользователя ОС испортила бы.
                inspect(line);

                if (out != null && written < MAX_OUTPUT_BYTES && !isChat(line)) {
                    // На диск — только вычищенное. Файл лежит в папке данных игрока
                    // и может уехать на сервер по запасной ветке readGameLog(),
                    // поэтому чужой чат и токены не должны попадать в него вовсе.
                    String safe = Redactor.apply(line);
                    out.println(safe);
                    written += safe.length() + 1;
                }
            }
        } catch (IOException e) {
            // Поток оборвался вместе с процессом — это нормальное завершение.
        } catch (Throwable t) {
            CrashReporter.warn("сбой чтения вывода игры", t);
        }
    }

    /** Ищет в строке признаки падения. */
    private void inspect(String line) {
        Matcher matcher = CRASH_MARKER.matcher(line);
        if (matcher.find()) {
            markerCrashPath = matcher.group("path").trim();
            return;
        }
        if (jvmStartupError == null) {
            for (String needle : JVM_STARTUP_FAILURES) {
                if (line.contains(needle)) {
                    jvmStartupError = line.trim();
                    return;
                }
            }
        }
    }

    /**
     * Разбирает завершение процесса и, если это падение, ставит отчёт в очередь.
     *
     * @param exitCode код возврата процесса игры
     */
    public void afterExit(int exitCode) {
        try {
            String crashReport = readCrashReport();

            if (crashReport == null && exitCode == 0 && jvmStartupError == null) {
                return; // штатный выход из игры — докладывать не о чем
            }
            if (crashReport == null && jvmStartupError != null) {
                // JVM не стартовала: краш-репорта нет и не будет, но причина известна.
                Log.info("Игра не запустилась: " + jvmStartupError);
            }
            CrashReporter.reportGameCrash(exitCode, crashReport, readGameLog());
        } catch (Throwable t) {
            CrashReporter.warn("не удалось разобрать падение игры", t);
        }
    }

    /**
     * Читает краш-репорт: сначала по пути из маркера, иначе — самый свежий файл
     * в {@code crash-reports/}.
     *
     * @return содержимое или {@code null}, если краш-репорта нет
     */
    private String readCrashReport() {
        Path file = crashReportFile();
        if (file == null) {
            return null;
        }
        String text = CrashReporter.tailOfFile(file, MAX_CRASH_REPORT_BYTES);
        if (text != null) {
            Log.info("Найден краш-репорт игры: " + file.getFileName());
        }
        return text;
    }

    /** Путь к краш-репорту этого запуска или {@code null}. */
    private Path crashReportFile() {
        String fromMarker = markerCrashPath;
        if (fromMarker != null) {
            Path path = Path.of(fromMarker);
            // Minecraft печатает абсолютный путь, но на всякий случай
            // поддерживаем и относительный — от рабочего каталога игры.
            if (!path.isAbsolute()) {
                path = gameDir.resolve(path);
            }
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return newestCrashReport();
    }

    /**
     * Самый свежий {@code crash-reports/crash-*.txt}, созданный <b>после</b> старта
     * этого процесса.
     *
     * <p>Проверка времени обязательна: без неё лаунчер отправил бы прошлогодний
     * краш-репорт как свежий при каждом штатном закрытии игры.
     */
    private Path newestCrashReport() {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("crash-") && name.endsWith(".txt");
                    })
                    .filter(this::modifiedAfterStart)
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .limit(1)
                    .toList();
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean modifiedAfterStart(Path file) {
        // Небольшой запас назад: часы файловой системы и системные часы
        // могут разойтись на секунду, и свежий репорт был бы отброшен.
        return lastModified(file) >= startedAt - 5000;
    }

    private long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Хвост {@code logs/latest.log} — контекст того, что происходило до падения. */
    private String readGameLog() {
        Path latest = gameDir.resolve("logs").resolve("latest.log");
        String text = CrashReporter.tailOfFile(latest, LOG_TAIL_BYTES);
        if (text != null) {
            return dropChat(text);
        }
        // Лога игры нет (не запустилась, либо он не прочитался) — отдаём то,
        // что успели перехватить сами. dropChat здесь тоже обязателен: путь
        // достижим на раздутом latest.log, и без фильтра уехал бы чужой чат.
        if (outputFile == null) {
            return null;
        }
        String captured = CrashReporter.tailOfFile(outputFile, LOG_TAIL_BYTES);
        return captured == null ? null : dropChat(captured);
    }

    /**
     * Выбрасывает строки игрового чата.
     *
     * <p>Это переписка <b>других</b> игроков: они не соглашались на её отправку,
     * а оператор отвечает за такие данные ровно так же, как за свои.
     */
    private static String dropChat(String log) {
        StringBuilder sb = new StringBuilder(log.length());
        int dropped = 0;
        for (String line : log.split("\\R")) {
            if (isChat(line)) {
                dropped++;
                continue;
            }
            sb.append(line).append('\n');
        }
        if (dropped > 0) {
            sb.append("[вырезано строк переписки: ").append(dropped).append("]\n");
        }
        return sb.toString();
    }

    /**
     * Похожа ли строка на переписку игроков.
     *
     * <p>Список маркеров шире одного {@code [CHAT]}: ванильный клиент, моды чата
     * и голосовой чат помечают строки по-разному, а цена пропуска — чужая переписка
     * на сервере разработчика. Здесь лучше вырезать лишнее: диагностическая ценность
     * этих строк близка к нулю.
     */
    private static boolean isChat(String line) {
        return line.contains("[CHAT]")
                || line.contains("[System] [CHAT]")
                || line.contains("<") && line.contains("> ") && line.contains("[Render thread/INFO]")
                || line.contains("[Not Secure]")
                || line.contains("whispers to you")
                || line.contains("шепчет вам");
    }

    /** Открывает файл для копии вывода; {@code null}, если писать некуда. */
    private PrintStream openOutputFile() {
        if (outputFile == null) {
            return null;
        }
        try {
            Files.createDirectories(outputFile.getParent());
            return new PrintStream(Files.newOutputStream(outputFile),
                    false, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}

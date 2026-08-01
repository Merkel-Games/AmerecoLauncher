package ru.amereco.amerecolauncher.diag;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ru.amereco.amerecolauncher.Config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Очередь отчётов на диске.
 *
 * <p>Зачем вообще очередь, а не отправка сразу: в момент краша сеть может отсутствовать
 * (а часто именно её отсутствие краш и вызвало), сайт может быть недоступен, да и просто
 * умирающий поток не должен ждать HTTP-таймаута. Поэтому отчёт синхронно пишется на диск,
 * а уезжает — отдельным фоновым потоком при следующем удобном случае.
 *
 * <p>Очередь ограничена и по числу записей, и по возрасту: диагностика не должна
 * бесконечно расти на диске игрока, который месяц не запускал лаунчер.
 *
 * <h3>Троттлинг</h3>
 * Одна и та же ошибка отправляется не чаще {@link #MAX_PER_FINGERPRINT_PER_DAY} раз в сутки.
 * Без этого один зациклившийся баг у одного игрока за вечер забьёт и очередь, и сервер,
 * не добавив ни байта новой информации.
 */
public final class ReportQueue {

    /** Сколько отчётов держим в очереди. Старые вытесняются новыми. */
    private static final int MAX_ENTRIES = 20;

    /** Через сколько дней недоставленный отчёт становится неинтересен. */
    private static final int MAX_AGE_DAYS = 14;

    /** Потолок отправок одного и того же отпечатка в сутки. */
    private static final int MAX_PER_FINGERPRINT_PER_DAY = 3;

    private static final Gson GSON = new Gson();
    private static final Type SEEN_TYPE = new TypeToken<Map<String, Seen>>() { }.getType();

    private final Path dir;
    private final Path queueDir;
    private final Path seenFile;

    /** Разводит имена отчётов, попавших в одну секунду. */
    private static final java.util.concurrent.atomic.AtomicInteger sequence =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Счётчик отправок по отпечатку за текущие сутки. */
    private static final class Seen {
        int count;
        long day;
    }

    /** Одна запись очереди: JSON отчёта и, возможно, приложенный к нему лог. */
    public record Entry(Path reportFile, Path logFile) {

        /** Читает отчёт с диска; {@code null}, если файл повреждён. */
        public CrashReport read() {
            try {
                String json = Files.readString(reportFile, StandardCharsets.UTF_8);
                return GSON.fromJson(json, CrashReport.class);
            } catch (Exception e) {
                return null;
            }
        }

        /** Читает приложенный лог; {@code null}, если его нет. */
        public byte[] readLog() {
            if (logFile == null || !Files.exists(logFile)) {
                return null;
            }
            try {
                return Files.readAllBytes(logFile);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /**
     * @param name подкаталог внутри {@code diag/}. Их два:
     *             {@code queue} — отчёты, которые разрешено отправлять, и
     *             {@code pending} — те, по которым игрок ещё не принял решение.
     *             Разделение по каталогам, а не по флагу внутри JSON: так
     *             неодобренный отчёт физически не может попасть в отправку
     *             из-за ошибки в одном {@code if}.
     */
    public ReportQueue(String name) {
        this.dir = Path.of(Config.projectDir.dataLocalDir, "diag");
        this.queueDir = dir.resolve(name);
        this.seenFile = dir.resolve("seen.json");
    }

    /** Каталог диагностики — там же лежат дампы JVM и маркер запуска. */
    public Path baseDir() {
        return dir;
    }

    /**
     * Можно ли отправлять эту ошибку прямо сейчас, или суточный лимит выбран.
     * Побочный эффект: увеличивает счётчик, если разрешил.
     */
    public synchronized boolean allow(String fingerprint) {
        try {
            Map<String, Seen> seen = readSeen();
            long today = System.currentTimeMillis() / 86_400_000L;

            Seen entry = seen.get(fingerprint);
            if (entry == null || entry.day != today) {
                entry = new Seen();
                entry.day = today;
                entry.count = 0;
                seen.put(fingerprint, entry);
            }
            if (entry.count >= MAX_PER_FINGERPRINT_PER_DAY) {
                return false;
            }
            entry.count++;

            // Чистим записи прошлых суток, чтобы файл не рос вечно.
            seen.entrySet().removeIf(e -> e.getValue().day < today);
            writeSeen(seen);
            return true;
        } catch (Throwable t) {
            // Не смогли посчитать — лучше отправить лишнее, чем потерять первый же краш.
            return true;
        }
    }

    /**
     * Кладёт отчёт в очередь. Вызывается в момент краша, поэтому синхронно,
     * без сети и без исключений наружу.
     *
     * @param gzipLog сжатый лог или {@code null}
     * @return {@code true} если запись легла на диск
     */
    public synchronized boolean enqueue(CrashReport report, byte[] gzipLog) {
        try {
            Files.createDirectories(queueDir);
            evictOverflow(1);

            // Имя: <секунды, дополненные нулями>-<8 знаков отпечатка>-<счётчик>.
            // Нули обязательны — list() сортирует лексикографически, и без выравнивания
            // по ширине сбитые часы игрока ломают хронологический порядок.
            // Счётчик разводит два краша, случившихся в одну секунду с одной ошибкой:
            // без него второй молча затирал бы первый.
            String stem = String.format("%012d-%s-%d",
                    Math.max(0, report.occurredAt),
                    shortFingerprint(report.fingerprint),
                    sequence.incrementAndGet());
            Path reportFile = queueDir.resolve(stem + ".json");

            report.hasLog = gzipLog != null && gzipLog.length > 0;

            // Пишем во временный файл и переименовываем: если процесс умрёт посреди
            // записи, в очереди не останется обрезанного JSON, который никогда не уйдёт.
            Path tmp = queueDir.resolve(stem + ".json.tmp");
            Files.writeString(tmp, GSON.toJson(report), StandardCharsets.UTF_8);
            Files.move(tmp, reportFile, StandardCopyOption.REPLACE_EXISTING);

            if (report.hasLog) {
                Path logTmp = queueDir.resolve(stem + ".log.gz.tmp");
                Files.write(logTmp, gzipLog);
                Files.move(logTmp, queueDir.resolve(stem + ".log.gz"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Все записи очереди, от самой старой к самой новой. */
    public synchronized List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(queueDir)) {
            return entries;
        }
        try (Stream<Path> files = Files.list(queueDir)) {
            List<Path> reports = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

            for (Path report : reports) {
                String name = report.getFileName().toString();
                String stem = name.substring(0, name.length() - ".json".length());
                Path log = queueDir.resolve(stem + ".log.gz");
                entries.add(new Entry(report, Files.exists(log) ? log : null));
            }
        } catch (IOException ignored) {
        }
        return entries;
    }

    /** Удаляет запись — после успешной отправки или когда сервер её отверг. */
    public synchronized void remove(Entry entry) {
        deleteQuietly(entry.reportFile());
        deleteQuietly(attemptsFile(entry));
        if (entry.logFile() != null) {
            deleteQuietly(entry.logFile());
        }
    }

    /**
     * Отмечает неудачную попытку отправки и возвращает их общее число.
     *
     * <p>Счётчик лежит в отдельном файле рядом с отчётом, а не в памяти: перезапуск
     * лаунчера не должен обнулять историю, иначе отчёт, который сервер никогда
     * не примет, будет вечно возвращаться в голову очереди.
     */
    public synchronized int recordFailure(Entry entry) {
        Path file = attemptsFile(entry);
        int attempts = 1;
        try {
            if (Files.exists(file)) {
                attempts = Integer.parseInt(Files.readString(file).trim()) + 1;
            }
        } catch (Exception ignored) {
            // Битый счётчик — начинаем заново, это лучше, чем сорваться на разборе.
        }
        try {
            Files.writeString(file, String.valueOf(attempts));
        } catch (IOException ignored) {
        }
        return attempts;
    }

    private static Path attemptsFile(Entry entry) {
        String name = entry.reportFile().getFileName().toString();
        String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return entry.reportFile().resolveSibling(stem + ".attempts");
    }

    /** Удаляет все записи разом — «не отправлять» для всей накопившейся пачки. */
    public synchronized void clear() {
        for (Entry entry : list()) {
            remove(entry);
        }
    }

    /**
     * Удаляет приложенный лог, оставляя сам отчёт.
     *
     * <p>Нужно, когда игрок разрешил отправку отчёта, но запретил прикладывать логи.
     * Снимается и отметка {@code hasLog} в JSON — иначе сервер будет запрашивать
     * файл, которого уже нет.
     */
    public synchronized void dropLog(Entry entry) {
        if (entry.logFile() != null) {
            deleteQuietly(entry.logFile());
        }
        try {
            CrashReport report = entry.read();
            if (report != null && report.hasLog) {
                report.hasLog = false;
                Files.writeString(entry.reportFile(), GSON.toJson(report), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Не переписался JSON — не страшно: лога на диске уже нет, и отправка
            // просто не найдёт файл.
        }
    }

    /**
     * Переносит запись в другую очередь — из {@code pending} в {@code queue},
     * когда игрок разрешил отправку.
     *
     * <p>Перенос, а не копирование с удалением: при сбое посреди операции отчёт
     * останется ровно в одной очереди, а не задвоится и не пропадёт.
     *
     * <p>Лог переносится <b>первым</b>. Обратный порядок при сбое посреди операции
     * оставил бы лог сиротой в исходной очереди: отчёт уже уехал, а искать файл
     * по нему больше некому.
     */
    public synchronized boolean moveTo(Entry entry, ReportQueue target) {
        try {
            Files.createDirectories(target.queueDir);

            if (entry.logFile() != null) {
                Files.move(entry.logFile(),
                        target.queueDir.resolve(entry.logFile().getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(entry.reportFile(),
                    target.queueDir.resolve(entry.reportFile().getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Выбрасывает просроченные записи и обрезает очередь до {@link #MAX_ENTRIES}.
     * Вызывается перед каждой постановкой в очередь и при старте.
     */
    public synchronized void prune() {
        long cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 86_400_000L;
        for (Entry entry : list()) {
            try {
                if (Files.getLastModifiedTime(entry.reportFile()).toMillis() < cutoff) {
                    remove(entry);
                }
            } catch (IOException ignored) {
            }
        }
        purgeStrays();
        // Освобождать место под новую запись здесь не нужно — ничего не добавляем.
        evictOverflow(0);
    }

    /**
     * Убирает самые старые записи, оставляя место под {@code incoming} новых.
     *
     * @param incoming сколько записей вот-вот добавят: 1 при постановке в очередь,
     *                 0 при плановой чистке. Раньше «+1» стоял в формуле всегда,
     *                 и плановая чистка полной очереди выбрасывала одну живую запись
     *                 без всякой причины.
     */
    private void evictOverflow(int incoming) {
        List<Entry> entries = list();
        int excess = entries.size() + incoming - MAX_ENTRIES;
        for (int i = 0; i < excess && i < entries.size(); i++) {
            remove(entries.get(i));
        }
    }

    /**
     * Удаляет мусор, оставшийся от прерванных записей: временные файлы, счётчики
     * попыток без отчёта и логи без отчёта.
     *
     * <p>Процесс может умереть между {@code write} и {@code move} — как раз в момент
     * краша, который мы и записываем. Без уборки такие обрывки копятся вечно.
     */
    private void purgeStrays() {
        if (!Files.isDirectory(queueDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(queueDir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    deleteQuietly(path);
                    continue;
                }
                if (name.endsWith(".log.gz") || name.endsWith(".attempts")) {
                    String stem = name.endsWith(".log.gz")
                            ? name.substring(0, name.length() - ".log.gz".length())
                            : name.substring(0, name.length() - ".attempts".length());
                    if (!Files.exists(queueDir.resolve(stem + ".json"))) {
                        deleteQuietly(path);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Map<String, Seen> readSeen() {
        try {
            if (Files.exists(seenFile)) {
                String json = Files.readString(seenFile, StandardCharsets.UTF_8);
                Map<String, Seen> parsed = GSON.fromJson(json, SEEN_TYPE);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
        }
        return new HashMap<>();
    }

    private void writeSeen(Map<String, Seen> seen) throws IOException {
        Files.createDirectories(dir);
        Path tmp = seenFile.resolveSibling("seen.json.tmp");
        Files.writeString(tmp, GSON.toJson(seen, SEEN_TYPE), StandardCharsets.UTF_8);
        Files.move(tmp, seenFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) {
            return "00000000";
        }
        return fingerprint.substring(0, 8);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}

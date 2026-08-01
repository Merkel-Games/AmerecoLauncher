package ru.amereco.amerecolauncher.diag;

import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * Сбор и отправка отчётов об ошибках лаунчера и игры.
 *
 * <h3>Главное правило</h3>
 * Крашрепортер не имеет права уронить или подвесить лаунчер. Любое его собственное
 * исключение проглатывается, любая сетевая операция уходит в фоновый поток, любой отказ
 * тихий. Диагностика — вспомогательная функция, ради неё нельзя ломать основную.
 *
 * <h3>Согласие</h3>
 * По умолчанию не отправляется ничего. Есть два режима:
 * <ul>
 *   <li><b>Автоотправка выключена</b> (по умолчанию) — отчёт кладётся в {@code pending},
 *       и лаунчер спрашивает разрешение диалогом: сейчас, если интерфейс жив, или
 *       при следующем запуске, если краш случился до его появления.</li>
 *   <li><b>Автоотправка включена</b> — отчёт сразу уходит в {@code queue} и отправляется
 *       фоном, без вопросов.</li>
 * </ul>
 *
 * <h3>Что откуда берётся</h3>
 * <ul>
 *   <li>Исключения не-FX потоков — {@code Thread.setDefaultUncaughtExceptionHandler}.</li>
 *   <li>Исключения FX-потока — его собственный per-thread handler; общий до них не доходит.</li>
 *   <li>Жёсткие краши JVM — файлы {@code hs_err_pid*.log}, см. {@link PostMortem}.</li>
 *   <li>Краши игры — код возврата и {@code crash-reports/} дочернего процесса.</li>
 * </ul>
 */
public final class CrashReporter {

    /** Потолок отчётов за один запуск — страховка от шторма одинаковых ошибок. */
    private static final int MAX_REPORTS_PER_RUN = 10;

    /**
     * Сколько раз пытаемся отправить один отчёт, прежде чем выбросить его.
     *
     * <p>Нужен именно потолок, а не бесконечные попытки: отчёт, который сервер
     * стабильно не принимает, иначе встанет в голову очереди и заблокирует все
     * остальные — доставка выродится в ноль из-за одной битой записи.
     */
    private static final int MAX_ATTEMPTS = 5;

    /** Сколько хвоста лога отправляем. До сжатия; gzip обычно ужимает раз в десять. */
    private static final int LOG_TAIL_BYTES = 512 * 1024;

    /**
     * Потолок сжатого вложения.
     *
     * <p>В контейнере WordPress {@code upload_max_filesize = 2M}, и превышение там
     * выглядит не как ошибка, а как пустой {@code $_FILES} — отлаживать это неприятно.
     * Миллион байт оставляет запас и на служебные части multipart.
     */
    private static final int MAX_GZIP_BYTES = 1_000_000;

    private static final AtomicInteger reportsThisRun = new AtomicInteger();
    private static final AtomicBoolean sending = new AtomicBoolean();

    /** Готов ли интерфейс показать диалог. Выставляется в {@code App.start()}. */
    private static volatile boolean uiReady;

    /** Что делать, когда появился неодобренный отчёт. Ставит {@code App}. */
    private static volatile Runnable pendingListener;

    private static final ReportQueue approved = new ReportQueue("queue");
    private static final ReportQueue pending = new ReportQueue("pending");

    private CrashReporter() {
    }

    // ─── Установка ──────────────────────────────────────────────────────────────

    /**
     * Вешает перехватчики и разбирает следы прошлого запуска.
     * Вызывать из {@code Main.main()} как можно раньше, сразу после {@code Log.init()}.
     */
    public static void install() {
        try {
            Thread.setDefaultUncaughtExceptionHandler(
                    (thread, error) -> report(error, CrashReport.Kind.LAUNCHER_EXCEPTION, thread));

            approved.prune();
            pending.prune();
            purgeGameOutput();

            PostMortem.scan(approved.baseDir());
        } catch (Throwable t) {
            quiet("не удалось поставить перехватчики", t);
        }
    }

    /**
     * Удаляет копию вывода игры, оставшуюся от прошлого запуска.
     *
     * <p>Она нужна только в пределах одной сессии — разбор падения игры происходит
     * сразу после её завершения. Между запусками это просто мегабайты чужой
     * переписки и отладки в папке данных игрока, поэтому не храним.
     */
    private static void purgeGameOutput() {
        try {
            Path dir = approved.baseDir();
            Files.deleteIfExists(dir.resolve("game-output.log"));
        } catch (Throwable ignored) {
        }
    }

    /** Сообщает, что интерфейс поднялся и диалог показать уже можно. */
    public static void setUiReady(boolean ready) {
        uiReady = ready;
    }

    /** Регистрирует обработчик, который покажет диалог по неодобренным отчётам. */
    public static void setPendingListener(Runnable listener) {
        pendingListener = listener;
    }

    // ─── Приём событий ──────────────────────────────────────────────────────────

    /** Необработанное исключение. */
    public static void report(Throwable error, String kind) {
        report(error, kind, Thread.currentThread());
    }

    /** Необработанное исключение с явным указанием потока. */
    public static void report(Throwable error, String kind, Thread thread) {
        try {
            // В лог пишем всегда, независимо от согласия: это локальный файл игрока.
            Log.error("Необработанная ошибка в потоке "
                    + (thread != null ? thread.getName() : "?"), error);

            if (!withinRunLimit()) {
                return;
            }
            CrashReport report = base(kind);
            fillFromThrowable(report, error);
            report.thread = thread != null ? thread.getName() : null;

            submit(report, collectLauncherLog());
        } catch (Throwable t) {
            quiet("сбой при подготовке отчёта", t);
        }
    }

    /**
     * Падение игры.
     *
     * @param exitCode     код возврата процесса
     * @param crashReport  содержимое {@code crash-reports/crash-*.txt} или {@code null}
     * @param gameLog      содержимое {@code logs/latest.log} или {@code null}
     */
    public static void reportGameCrash(int exitCode, String crashReport, String gameLog) {
        try {
            if (!withinRunLimit()) {
                return;
            }
            boolean hasCrashReport = crashReport != null && !crashReport.isBlank();

            CrashReport report = base(hasCrashReport
                    ? CrashReport.Kind.MC_CRASH
                    : CrashReport.Kind.DIRTY_EXIT);
            report.exitCode = exitCode;

            if (hasCrashReport) {
                String safe = Redactor.apply(crashReport);
                report.fingerprint = Fingerprint.ofCrashReport(safe);
                report.message = firstLine(describeCrash(safe));
                report.stack = topLines(safe, CrashReport.MAX_STACK_FRAMES);
            } else {
                // Без краш-репорта единственное, что отличает одно падение от другого, —
                // код возврата. Отдельный отпечаток на код, иначе всё склеится в одну кучу.
                report.fingerprint = Fingerprint.ofKey("dirty_exit:" + exitCode);
                report.message = describeExitCode(exitCode);
            }

            byte[] attachment = gzipLog(combineGameLogs(crashReport, gameLog));
            submit(report, attachment);
        } catch (Throwable t) {
            quiet("сбой при подготовке отчёта о краше игры", t);
        }
    }

    /**
     * Ручная отправка — игрок сам нажал кнопку, потому что «что-то не работает».
     * Согласие здесь не проверяется: нажатие кнопки и есть согласие на этот отчёт.
     */
    public static void reportManual(String comment, boolean attachLog) {
        try {
            CrashReport report = base(CrashReport.Kind.MANUAL);
            report.fingerprint = Fingerprint.ofKey("manual:" + System.currentTimeMillis());
            report.message = comment == null ? "" : truncate(Redactor.apply(comment),
                    CrashReport.MAX_MESSAGE);
            report.userComment = report.message;

            approved.enqueue(report, attachLog ? collectLauncherLog() : null);
            flushAsync();
        } catch (Throwable t) {
            quiet("сбой при ручной отправке", t);
        }
    }

    /**
     * Разводит готовый отчёт по очередям в зависимости от согласия.
     *
     * <p>Троттлинг проверяется здесь, а не при отправке: смысл в том, чтобы одинаковые
     * ошибки не копились на диске, а не только чтобы не улетали в сеть.
     */
    static void submit(CrashReport report, byte[] attachment) {
        if (!approved.allow(report.fingerprint)) {
            Log.info("Отчёт подавлен: эта ошибка уже отправлялась сегодня");
            return;
        }
        if (reportsThisRun.incrementAndGet() > MAX_REPORTS_PER_RUN) {
            return; // шторм ошибок — дальше молчим до перезапуска
        }
        // safe(), а не get(): падение могло случиться внутри самой загрузки конфига,
        // и обычный get() в этот момент ушёл бы в рекурсию.
        Config config = Config.safe();
        byte[] log = config != null && config.diagSendLogs ? attachment : null;

        if (config != null && config.diagConsent) {
            approved.enqueue(report, log);
            flushAsync();
            return;
        }

        // Согласия нет — решает игрок. Лог кладём рядом, потому что второй раз собрать
        // хвост лога будет уже неоткуда: к моменту ответа он затрётся следующим запуском.
        // Но это ещё не разрешение его отправлять — решение принимается в approvePending(),
        // где сверяется актуальное состояние галки.
        pending.enqueue(report, attachment);

        Runnable listener = pendingListener;
        if (uiReady && listener != null) {
            try {
                listener.run();
            } catch (Throwable t) {
                quiet("не удалось показать диалог отчёта", t);
            }
        }
        // Интерфейса нет — спросим при следующем запуске, отчёт уже на диске.
    }

    // ─── Очередь неодобренных ───────────────────────────────────────────────────

    /** Отчёты, ожидающие решения игрока. */
    public static List<ReportQueue.Entry> pendingReports() {
        return pending.list();
    }

    /**
     * Игрок разрешил отправку накопившихся отчётов.
     *
     * <p>Галка «приложить логи» проверяется <b>здесь</b>, а не при постановке в очередь:
     * в момент краша её состояние ещё неизвестно, игрок выставляет её в том же диалоге,
     * которым разрешает отправку. Если логи отправлять запрещено — файл удаляется
     * с диска и отметка {@code hasLog} в отчёте снимается, чтобы сервер его не запрашивал.
     */
    public static void approvePending() {
        try {
            Config config = Config.safe();
            boolean withLogs = config != null && config.diagSendLogs;

            for (ReportQueue.Entry entry : pending.list()) {
                if (!withLogs) {
                    pending.dropLog(entry);
                }
                pending.moveTo(entry, approved);
            }
            flushAsync();
        } catch (Throwable t) {
            quiet("не удалось одобрить отчёты", t);
        }
    }

    /** Игрок отказался — удаляем, больше не спрашиваем. */
    public static void discardPending() {
        try {
            pending.clear();
        } catch (Throwable t) {
            quiet("не удалось удалить отчёты", t);
        }
    }

    // ─── Отправка ───────────────────────────────────────────────────────────────

    /** Пробует отправить очередь в фоне. Возврат немедленный. */
    public static void flushAsync() {
        if (!sending.compareAndSet(false, true)) {
            return; // отправка уже идёт
        }
        Thread worker = new Thread(() -> {
            try {
                drain();
            } catch (Throwable t) {
                quiet("сбой отправки отчётов", t);
            } finally {
                sending.set(false);
            }
        }, "diag-sender");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Пробует дослать очередь и ждёт не дольше указанного времени.
     * Вызывается при выходе, чтобы не терять отчёт, снятый прямо перед закрытием.
     */
    public static void flush(long timeoutMs) {
        // Тот же замок, что и у flushAsync: иначе два drain() пойдут по одной очереди
        // одновременно и отправят каждый отчёт дважды.
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        // Ждать нечего, если очередь пуста — не задерживаем закрытие лаунчера.
        if (approved.list().isEmpty()) {
            sending.set(false);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                drain();
            } catch (Throwable ignored) {
            } finally {
                sending.set(false);
                done.countDown();
            }
        }, "diag-sender-final");
        worker.setDaemon(true);
        worker.start();
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Последовательно отправляет всё, что лежит в одобренной очереди.
     *
     * <p>Отчёт, который не удалось отправить, остаётся в очереди — но не навсегда:
     * счётчик неудач хранится рядом с ним, и после {@link #MAX_ATTEMPTS} попыток
     * запись выбрасывается. Без этого один отчёт, который сервер стабильно не берёт,
     * встал бы в голову очереди и заблокировал все остальные до скончания века.
     */
    private static void drain() {
        List<ReportQueue.Entry> entries = approved.list();
        if (entries.isEmpty()) {
            return;
        }
        DiagClient client = new DiagClient(diagUrl());
        String token = accessToken();

        // Флаг читается один раз на проход: перещёлкивание галки посреди отправки
        // не должно приводить к тому, что часть логов уехала, а часть нет.
        Config config = Config.safe();
        boolean maySendLogs = config != null && config.diagSendLogs;

        for (ReportQueue.Entry entry : entries) {
            CrashReport report = entry.read();
            if (report == null) {
                approved.remove(entry); // битый файл — толку от него не будет никогда
                continue;
            }
            DiagClient.SendResult result = client.sendReport(report, token);

            if (result.retryable()) {
                if (approved.recordFailure(entry) >= MAX_ATTEMPTS) {
                    Log.info("Отчёт выброшен после " + MAX_ATTEMPTS
                            + " неудачных попыток: " + result.detail());
                    approved.remove(entry);
                    continue; // следующий может уйти нормально
                }
                Log.info("Отчёт отложен (" + result.detail() + ")");
                return; // сеть или сервер недоступны — остальные тоже не уйдут
            }

            // Лог досылаем, только если сервер его попросил И игрок это разрешил.
            // Вторая проверка дублирует approvePending() намеренно: это последний
            // рубеж перед отправкой файла, и цена ошибки здесь — нарушенное согласие.
            if (result.delivered() && result.wantLog() && result.reportId() > 0 && maySendLogs) {
                byte[] log = entry.readLog();
                if (log != null) {
                    DiagClient.SendResult logResult = client.sendLog(
                            result.reportId(), result.attachToken(), report.clientId,
                            log, "launcher.log.gz", token);
                    if (!logResult.delivered()) {
                        // Сам отчёт уже принят, повторять его нельзя — иначе задвоится.
                        // Лог просто теряется, и это меньшее из зол.
                        Log.info("Лог к отчёту не доставлен: " + logResult.detail());
                    }
                }
            }
            approved.remove(entry);
        }
    }

    // ─── Сборка отчёта ──────────────────────────────────────────────────────────

    /** Заполняет поля окружения, одинаковые для любого события. */
    static CrashReport base(String kind) {
        CrashReport report = new CrashReport();
        report.kind = kind;
        report.occurredAt = System.currentTimeMillis() / 1000L;
        report.launcherVersion = Config.properties.getProperty("version", "?");
        report.javaVersion = System.getProperty("java.version");
        report.os = System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
        report.mcVersion = Config.properties.getProperty("minecraftVersion");
        report.loader = Config.properties.getProperty("fabricVersion");
        report.consentVersion = Config.properties.getProperty("diagConsentVersion");

        try {
            Config config = Config.safe();
            if (config == null) {
                return report; // конфиг ещё не читался — отчёт без этих полей тоже полезен
            }
            report.clientId = config.clientId;
            report.claimedUserName = config.userName;
            report.claimedUuid = config.uuid;
            report.maxMemoryMb = config.maxMemoryMb;
            report.gcProfile = config.gcProfile;
            report.profile = activeProfile(config);
        } catch (Throwable ignored) {
            // Конфиг может быть ещё не загружен — отчёт без этих полей всё равно полезен.
        }
        return report;
    }

    private static void fillFromThrowable(CrashReport report, Throwable error) {
        Throwable root = Fingerprint.rootCause(error);
        report.fingerprint = Fingerprint.of(error);
        report.exception = root.getClass().getName();
        // Исключение без сообщения — обычное дело (NullPointerException и подобные).
        // String.valueOf() записал бы туда литеральное "null", которое потом
        // неотличимо от настоящего сообщения со словом null.
        String message = root.getMessage();
        report.message = message == null
                ? null
                : truncate(Redactor.apply(message), CrashReport.MAX_MESSAGE);

        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        report.stack = topLines(Redactor.apply(sw.toString()), CrashReport.MAX_STACK_FRAMES);
    }

    /** Первый включённый профиль сборки из настроек. */
    private static String activeProfile(Config config) {
        if (config.features == null) {
            return null;
        }
        if (Boolean.TRUE.equals(config.features.get("profile_rpcraft_ultra"))) return "rpcraft-ultra";
        if (Boolean.TRUE.equals(config.features.get("profile_rpcraft_admin"))) return "rpcraft-admin";
        if (Boolean.TRUE.equals(config.features.get("profile_rpcraft"))) return "rpcraft";
        return null;
    }

    // ─── Логи ───────────────────────────────────────────────────────────────────

    /** Хвост {@code launcher.log}, вычищенный и сжатый; {@code null} если лога нет. */
    private static byte[] collectLauncherLog() {
        try {
            String tail = Log.tail(LOG_TAIL_BYTES);
            return tail == null ? null : gzipLog(tail);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Склеивает краш-репорт игры и хвост её лога в один текст. */
    private static String combineGameLogs(String crashReport, String gameLog) {
        StringBuilder sb = new StringBuilder();
        if (crashReport != null && !crashReport.isBlank()) {
            sb.append("===== crash-report =====\n").append(crashReport).append("\n\n");
        }
        if (gameLog != null && !gameLog.isBlank()) {
            sb.append("===== latest.log (хвост) =====\n").append(gameLog).append('\n');
        }
        String launcherLog = Log.tail(LOG_TAIL_BYTES / 2);
        if (launcherLog != null) {
            sb.append("\n===== launcher.log (хвост) =====\n").append(launcherLog).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Сжимает текст, предварительно прогнав через {@link Redactor}.
     *
     * <p>Если сжатое всё равно не влезает в лимит, текст режется <b>с начала</b>:
     * причина падения всегда в конце файла, начало — это загрузка модов.
     */
    public static byte[] gzipLog(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String safe = Redactor.apply(text);
            byte[] gz = gzip(safe);

            for (int attempt = 0; gz.length > MAX_GZIP_BYTES && attempt < 4; attempt++) {
                safe = safe.substring(safe.length() / 2);
                gz = gzip("[…начало обрезано…]\n" + safe);
            }
            return gz.length > MAX_GZIP_BYTES ? null : gz;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /**
     * Каталог диагностики — очереди, дампы, копия вывода игры.
     *
     * @return существующий каталог или {@code null}, если создать не удалось
     */
    public static Path diagDir() {
        try {
            Path dir = approved.baseDir();
            Files.createDirectories(dir);
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Каталог, куда JVM складывает дампы фатальных ошибок.
     *
     * <p>Передаётся игре через {@code -XX:ErrorFile}, поэтому создаётся заранее:
     * в момент падения JVM каталоги уже не создаёт и дамп просто теряется.
     *
     * @return существующий каталог или {@code null}, если создать не удалось
     */
    public static Path crashDumpDir() {
        try {
            Path dir = PostMortem.crashDir(approved.baseDir());
            Files.createDirectories(dir);
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Читает хвост файла — для краш-репортов и логов игры.
     *
     * <p>Читается ровно нужный кусок, а не файл целиком. Разница принципиальна:
     * этот метод вызывается в том числе при {@code OutOfMemoryError}, и на
     * раздутом до гигабайта {@code latest.log} чтение целиком гарантированно
     * провалится — как раз тогда, когда лог нужнее всего.
     */
    public static String tailOfFile(Path file, int maxBytes) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            long from = Math.max(0, size - maxBytes);
            int length = (int) Math.min(size, maxBytes);

            byte[] buffer = new byte[length];
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                channel.position(from);
                ByteBuffer target = ByteBuffer.wrap(buffer);
                while (target.hasRemaining() && channel.read(target) > 0) {
                    // читаем, пока не наберём хвост целиком
                }
                length = target.position();
            }
            String text = new String(buffer, 0, length, StandardCharsets.UTF_8);
            return from > 0 ? "[…начало обрезано…]\n" + text : text;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── Предпросмотр ───────────────────────────────────────────────────────────

    /**
     * Человекочитаемый текст того, что уедет на сервер.
     * Показывается по кнопке «Посмотреть, что будет отправлено».
     */
    public static String preview(CrashReport report) {
        StringBuilder sb = new StringBuilder();

        // Показываем ВСЕ поля, которые уходят на сервер. Расхождение между
        // предпросмотром и реальным запросом — это обман: игрок соглашается
        // на одно, а отправляется другое.
        line(sb, "Тип события", report.kind);
        line(sb, "Отпечаток ошибки", report.fingerprint);
        line(sb, "Время события", report.occurredAt == 0 ? null
                : java.time.Instant.ofEpochSecond(report.occurredAt).toString());
        line(sb, "Версия лаунчера", report.launcherVersion);
        line(sb, "Java", report.javaVersion);
        line(sb, "ОС", report.os);
        line(sb, "Версия игры", report.mcVersion);
        line(sb, "Модлоадер", report.loader);
        line(sb, "Профиль сборки", report.profile);
        line(sb, "Память игры, МБ", report.maxMemoryMb == 0 ? "авто"
                : String.valueOf(report.maxMemoryMb));
        line(sb, "Профиль сборщика мусора", report.gcProfile);
        line(sb, "Код возврата игры", report.exitCode == null ? null
                : String.valueOf(report.exitCode));
        line(sb, "Поток", report.thread);
        line(sb, "Идентификатор установки", report.clientId);
        line(sb, "Ник", report.claimedUserName);
        line(sb, "UUID", report.claimedUuid);
        line(sb, "Версия согласия", report.consentVersion);

        sb.append('\n');
        line(sb, "Ошибка", report.exception);
        line(sb, "Сообщение", report.message);
        line(sb, "Комментарий", report.userComment);

        if (report.stack != null && !report.stack.isEmpty()) {
            sb.append('\n').append("Стек вызовов:").append('\n');
            for (String stackLine : report.stack) {
                sb.append("  ").append(stackLine).append('\n');
            }
        }
        return sb.toString();
    }

    /** Строка предпросмотра; пустые значения не показываем — они и не отправляются. */
    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    // ─── Мелочи ─────────────────────────────────────────────────────────────────

    /**
     * Не выбран ли потолок отчётов за запуск.
     *
     * <p>Только проверка, без побочных эффектов: счётчик увеличивается в
     * {@link #submit}, когда отчёт действительно попал в очередь. Иначе десять
     * подряд подавленных дедупликацией падений выбирали бы лимит целиком,
     * и одиннадцатая — уже новая — ошибка потерялась бы.
     */
    static boolean withinRunLimit() {
        return reportsThisRun.get() < MAX_REPORTS_PER_RUN;
    }

    /** Сообщение об ошибке диагностики — доступно соседям по пакету. */
    static void reportQuietly(String what, Throwable t) {
        quiet(what, t);
    }

    /**
     * То же для кода вне пакета: сбой самой диагностики отмечается, но никогда
     * не превращается в исключение для вызывающего.
     */
    public static void warn(String what, Throwable t) {
        quiet(what, t);
    }

    private static String diagUrl() {
        return Config.properties.getProperty("diagUrl",
                "https://amereco.ru/wp-json/amereco-diag/v1/");
    }

    /** Живой access-токен или {@code null}, если игрок не залогинен. */
    private static String accessToken() {
        Config config = Config.safe();
        if (config == null) {
            return null;
        }
        String token = config.accessToken;
        return token == null || token.isBlank() ? null : token;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return truncate((nl < 0 ? text : text.substring(0, nl)).strip(), CrashReport.MAX_MESSAGE);
    }

    /** Первые {@code limit} непустых строк — для стека из текстового краш-репорта. */
    static List<String> topLines(String text, int limit) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            lines.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines;
    }

    /** Достаёт строку {@code Description:} из краш-репорта Minecraft. */
    private static String describeCrash(String crashReport) {
        for (String raw : crashReport.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Description:")) {
                return line.substring("Description:".length()).strip();
            }
        }
        return firstLine(crashReport);
    }

    /** Человеческая расшифровка кодов возврата процесса игры. */
    private static String describeExitCode(int exitCode) {
        return switch (exitCode) {
            case 1 -> "Игра завершилась с ошибкой (код 1)";
            case 137 -> "Процесс игры убит системой — не хватило оперативной памяти (код 137)";
            case 139 -> "Аварийное завершение процесса игры, SIGSEGV (код 139)";
            case 143 -> "Процесс игры остановлен извне (код 143)";
            default -> "Игра завершилась с кодом " + exitCode;
        };
    }

    /**
     * Внутренняя ошибка самой диагностики.
     *
     * <p>Пишем напрямую в консоль, а не через {@code Log.error}: если сломался редактор
     * или поток вывода, повторный заход в ту же машинерию сделает только хуже.
     */
    private static void quiet(String what, Throwable t) {
        try {
            System.err.println("[diag] " + what + ": " + t);
        } catch (Throwable ignored) {
        }
    }
}

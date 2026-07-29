package ru.amereco.amerecolauncher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Файловый лог лаунчера.
 *
 * <p>Пишется в {@code <dataLocalDir>/launcher.log} и <b>перезаписывается при каждом
 * запуске</b>: нужен последний сеанс, а не история за всё время.
 *
 * <p>{@link #init()} подменяет {@code System.out} и {@code System.err} так, чтобы
 * вывод шёл и в консоль, и в файл. Благодаря этому в лог попадают все существующие
 * {@code println()} и {@code printStackTrace()} по коду — переписывать их не нужно.
 * В файл каждая строка уходит с отметкой времени и меткой потока вывода.
 *
 * <p>Лог не критичен для работы: если файл открыть не удалось (нет прав, диск
 * заполнен), лаунчер продолжает работать с обычным выводом в консоль.
 */
public final class Log {

    /** Имя файла лога внутри {@code projectDir.dataLocalDir}. */
    private static final String FILE_NAME = "launcher.log";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Поток файла лога; {@code null} — файл открыть не удалось. */
    private static volatile PrintStream file;

    private static Path path;

    private Log() {
    }

    /**
     * Открывает лог заново и перенаправляет в него вывод.
     * Вызывать как можно раньше при старте; повторные вызовы игнорируются.
     */
    public static synchronized void init() {
        if (file != null) {
            return;
        }
        PrintStream consoleOut = System.out;
        PrintStream consoleErr = System.err;
        try {
            Path dir = Path.of(Config.projectDir.dataLocalDir);
            Files.createDirectories(dir);
            path = dir.resolve(FILE_NAME);

            OutputStream raw = Files.newOutputStream(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            file = new PrintStream(new BufferedOutputStream(raw, 8192),
                    false, StandardCharsets.UTF_8);

            System.setOut(new PrintStream(
                    new TeeStream(consoleOut, "   "), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(
                    new TeeStream(consoleErr, "ERR"), true, StandardCharsets.UTF_8));

            Runtime.getRuntime().addShutdownHook(new Thread(Log::flush, "log-flush"));

            writeHeader();
        } catch (Exception e) {
            // Логирование не должно ронять запуск: откатываемся на чистую консоль.
            file = null;
            System.setOut(consoleOut);
            System.setErr(consoleErr);
            consoleErr.println("Не удалось открыть файл лога: " + e);
        }
    }

    /** Путь к файлу лога или {@code null}, если лог не открыт. */
    public static Path path() {
        return file != null ? path : null;
    }

    /** Пишет строку в консоль и в файл. */
    public static void info(String message) {
        System.out.println(message);
    }

    /** Пишет сообщение об ошибке вместе со стектрейсом. */
    public static void error(String message, Throwable cause) {
        System.err.println(message);
        if (cause != null) {
            cause.printStackTrace();
        }
    }

    /** Сбрасывает буфер на диск. Вызывается автоматически при завершении процесса. */
    public static void flush() {
        PrintStream out = file;
        if (out != null) {
            out.flush();
        }
    }

    private static void writeHeader() {
        info("=== AmerecoLauncher " + Config.properties.getProperty("version", "?")
                + " — запуск " + LocalDateTime.now().format(DATE_TIME) + " ===");
        info("ОС: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        info("Java: " + System.getProperty("java.version")
                + " — " + System.getProperty("java.home"));
        info("Данные: " + Config.projectDir.dataLocalDir);
        info("Лог: " + path);
    }

    /**
     * Дублирует поток вывода: в консоль — как есть, в файл — с отметкой времени
     * и меткой ({@code ERR} для {@code System.err}) в начале каждой строки.
     */
    private static final class TeeStream extends OutputStream {

        private final OutputStream console;
        private final String tag;
        private boolean lineStart = true;

        TeeStream(OutputStream console, String tag) {
            this.console = console;
            this.tag = tag;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            console.write(b);

            PrintStream out = file;
            if (out == null) {
                return;
            }
            if (lineStart) {
                out.print(LocalDateTime.now().format(TIME) + " " + tag + " ");
                lineStart = false;
            }
            out.write(b);
            if (b == '\n') {
                lineStart = true;
                out.flush();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            Log.flush();
        }
    }
}

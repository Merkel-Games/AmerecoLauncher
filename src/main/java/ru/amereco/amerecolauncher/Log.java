package ru.amereco.amerecolauncher;

import ru.amereco.amerecolauncher.diag.Redactor;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Файловый лог лаунчера.
 *
 * <p>Пишется в {@code <dataLocalDir>/launcher.log}. Лог прошлого запуска не теряется:
 * при старте он переименовывается в {@code launcher.log.1}. Это существенно — лаунчер,
 * который упал, игрок обычно тут же запускает снова, и без ротации свидетельство
 * падения затиралось бы прежде, чем его успели прочитать или отправить.
 *
 * <p>{@link #init()} подменяет {@code System.out} и {@code System.err} так, чтобы
 * вывод шёл и в консоль, и в файл. Благодаря этому в лог попадают все существующие
 * {@code println()} и {@code printStackTrace()} по коду — переписывать их не нужно.
 * В файл каждая строка уходит с отметкой времени и меткой потока вывода.
 *
 * <p>Каждая строка перед записью в файл проходит через {@link Redactor}. Поэтому
 * безопасен и сам файл: игрок может выложить его в чат, не раздав заодно свой
 * access-токен. В консоль вывод идёт как есть — она нужна для отладки и никуда
 * не пересылается.
 *
 * <p>Лог не критичен для работы: если файл открыть не удалось (нет прав, диск
 * заполнен), лаунчер продолжает работать с обычным выводом в консоль.
 */
public final class Log {

    /** Имя файла лога внутри {@code projectDir.dataLocalDir}. */
    private static final String FILE_NAME = "launcher.log";

    /** Лог предыдущего запуска. */
    private static final String PREVIOUS_FILE_NAME = FILE_NAME + ".1";

    /**
     * Потолок длины строки в байтах.
     *
     * <p>Строка копится в памяти до перевода каретки, а его может и не быть:
     * прогресс-бар, печатающий {@code \r}, или заглючивший мод способны гнать
     * поток без единого {@code \n}. Потолок гарантирует, что буфер не съест память.
     */
    private static final int MAX_LINE_BYTES = 64 * 1024;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Поток файла лога; {@code null} — файл открыть не удалось. */
    private static volatile PrintStream file;

    /**
     * Настоящая консоль процесса, до подмены {@code System.out}.
     *
     * <p>Нужна тем, кому надо напечатать что-то разработчику, не засоряя
     * {@code launcher.log} — например выводу дочернего процесса игры, где
     * помимо мегабайт отладки бывает и чужой игровой чат.
     */
    private static volatile PrintStream console;

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
        console = consoleOut;
        try {
            Path dir = Path.of(Config.projectDir.dataLocalDir);
            Files.createDirectories(dir);
            path = dir.resolve(FILE_NAME);

            rotate(dir);

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
        } catch (Throwable e) {
            // Throwable, а не Exception: writeHeader() трогает Config, чья статическая
            // инициализация при недоступном application.properties бросает
            // ExceptionInInitializerError. Он пролетал мимо catch, и лаунчер умирал
            // ещё до установки перехватчиков — то есть самый ранний класс отказов
            // не попадал ни в лог, ни в отчёт.
            // Логирование не должно ронять запуск: откатываемся на чистую консоль.
            file = null;
            System.setOut(consoleOut);
            System.setErr(consoleErr);
            consoleErr.println("Не удалось открыть файл лога: " + e);
        }
    }

    /**
     * Сохраняет лог прошлого запуска под именем {@link #PREVIOUS_FILE_NAME}.
     * Глубже одного поколения не храним: интересен последний сеанс и предыдущий.
     */
    private static void rotate(Path dir) {
        try {
            if (Files.exists(path)) {
                Files.move(path, dir.resolve(PREVIOUS_FILE_NAME),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Не смогли переименовать — ничего страшного, файл будет перезаписан.
        }
    }

    /** Путь к файлу лога или {@code null}, если лог не открыт. */
    public static Path path() {
        return file != null ? path : null;
    }

    /** Путь к логу прошлого запуска или {@code null}, если его нет. */
    public static Path previousPath() {
        if (path == null) {
            return null;
        }
        Path previous = path.resolveSibling(PREVIOUS_FILE_NAME);
        return Files.exists(previous) ? previous : null;
    }

    /**
     * Настоящая консоль процесса — вывод туда <b>не</b> попадает в {@code launcher.log}.
     * Если лог не инициализирован, возвращается текущий {@code System.out}.
     */
    public static PrintStream console() {
        PrintStream out = console;
        return out != null ? out : System.out;
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

    /**
     * Последние {@code maxBytes} байт лога — то, что прикладывается к отчёту об ошибке.
     *
     * <p>Режется именно хвост: причина падения всегда в конце файла, начало занято
     * загрузкой ресурсов. Содержимое уже вычищено при записи.
     *
     * @return текст или {@code null}, если лога нет либо прочитать не удалось
     */
    public static String tail(int maxBytes) {
        Path current = path;
        if (current == null) {
            return null;
        }
        flush();
        // Читаем ровно хвост, а не файл целиком: метод вызывается в том числе
        // при OutOfMemoryError, когда лишней памяти под копию лога уже нет.
        return ru.amereco.amerecolauncher.diag.CrashReporter.tailOfFile(current, maxBytes);
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
     * Дублирует поток вывода: в консоль — как есть, в файл — построчно, с отметкой
     * времени, меткой ({@code ERR} для {@code System.err}) и вычищенными секретами.
     *
     * <p>Байты копятся до перевода строки, потому что {@link Redactor} работает
     * с текстом: посимвольно вырезать токен невозможно. Побочный плюс — многобайтные
     * символы UTF-8 больше не рискуют развалиться на границе записи.
     */
    private static final class TeeStream extends OutputStream {

        private final OutputStream console;
        private final String tag;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        TeeStream(OutputStream console, String tag) {
            this.console = console;
            this.tag = tag;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            console.write(b);

            if (file == null) {
                return;
            }
            if (b == '\n') {
                emitLine();
                return;
            }
            // '\r' в буфер не кладём: перевод строки Windows иначе оставил бы
            // в файле висячий символ в конце каждой строки.
            if (b != '\r') {
                lineBuffer.write(b);
            }
            if (lineBuffer.size() >= MAX_LINE_BYTES) {
                emitLine();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        /** Отдаёт накопленную строку в файл — вычищенную и с отметкой времени. */
        private void emitLine() {
            PrintStream out = file;
            if (out == null) {
                lineBuffer.reset();
                return;
            }
            String line = lineBuffer.toString(StandardCharsets.UTF_8);
            lineBuffer.reset();

            out.print(LocalDateTime.now().format(TIME));
            out.print(' ');
            out.print(tag);
            out.print(' ');
            out.println(Redactor.apply(line));
            out.flush();
        }

        @Override
        public synchronized void flush() throws IOException {
            console.flush();
            // Недописанную строку тоже сбрасываем: иначе последнее сообщение перед
            // падением, напечатанное через print() без перевода строки, потеряется.
            if (lineBuffer.size() > 0) {
                emitLine();
            }
            Log.flush();
        }
    }
}

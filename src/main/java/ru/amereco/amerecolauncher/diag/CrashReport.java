package ru.amereco.amerecolauncher.diag;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Структурированный отчёт об ошибке — то, что уезжает на сервер как JSON.
 *
 * <p>Принцип: сюда попадает <b>только перечисленное здесь</b> (allowlist). Сырой лог
 * едет отдельным запросом и только с отдельного согласия. Так проще доказать игроку,
 * что именно отправляется, и невозможно случайно прихватить лишнее.
 *
 * <p>Все текстовые поля перед отправкой проходят через {@link Redactor}.
 */
public class CrashReport {

    /** Версия формата. Сервер отвергает то, чего не понимает. */
    @SerializedName("schema")
    public int schema = 1;

    /** Тип события. Значения перечислены в {@link Kind}. */
    @SerializedName("kind")
    public String kind;

    /** Ключ склейки одинаковых ошибок, см. {@link Fingerprint}. */
    @SerializedName("fingerprint")
    public String fingerprint;

    /** Полное имя класса исключения; для крашей игры — {@code null}. */
    @SerializedName("exception")
    public String exception;

    /** Первая строка сообщения, обрезанная до {@link #MAX_MESSAGE}. */
    @SerializedName("message")
    public String message;

    /** Стек вызовов построчно, не длиннее {@link #MAX_STACK_FRAMES} кадров. */
    @SerializedName("stack")
    public List<String> stack = new ArrayList<>();

    /** В каком потоке произошло — {@code JavaFX Application Thread} и т.п. */
    @SerializedName("thread")
    public String thread;

    // ── Окружение ────────────────────────────────────────────────────────────────

    @SerializedName("launcher_version")
    public String launcherVersion;

    @SerializedName("java_version")
    public String javaVersion;

    @SerializedName("os")
    public String os;

    @SerializedName("mc_version")
    public String mcVersion;

    @SerializedName("loader")
    public String loader;

    /** Выбранный профиль сборки: rpcraft / rpcraft-admin / rpcraft-ultra. */
    @SerializedName("profile")
    public String profile;

    @SerializedName("max_memory_mb")
    public int maxMemoryMb;

    @SerializedName("gc_profile")
    public String gcProfile;

    /** Код выхода процесса игры; {@code null} для событий лаунчера. */
    @SerializedName("exit_code")
    public Integer exitCode;

    // ── Идентификация ────────────────────────────────────────────────────────────

    /**
     * Стабильный идентификатор установки из {@code config.json}.
     * Склеивает отчёты одного игрока, не раскрывая личность.
     */
    @SerializedName("client_id")
    public String clientId;

    /**
     * Ник из конфига. Сервер помечает его как <b>неподтверждённый</b>, если
     * в запросе не было валидного access-токена: подделать может кто угодно.
     */
    @SerializedName("claimed_user_name")
    public String claimedUserName;

    /** UUID из конфига, с той же оговоркой, что и {@link #claimedUserName}. */
    @SerializedName("claimed_uuid")
    public String claimedUuid;

    // ── Прочее ───────────────────────────────────────────────────────────────────

    /** Unix-время события в секундах. */
    @SerializedName("occurred_at")
    public long occurredAt;

    /** Комментарий игрока — заполняется только при ручной отправке. */
    @SerializedName("user_comment")
    public String userComment;

    /** Версия текста согласия, под которым отчёт отправлен. */
    @SerializedName("consent_version")
    public String consentVersion;

    /** Есть ли у клиента лог, который он готов дослать вторым запросом. */
    @SerializedName("has_log")
    public boolean hasLog;

    // ── Ограничения ──────────────────────────────────────────────────────────────

    /** Потолок длины сообщения об ошибке. */
    public static final int MAX_MESSAGE = 500;

    /** Потолок числа кадров стека. Глубже полезной информации уже нет. */
    public static final int MAX_STACK_FRAMES = 60;

    /**
     * Типы событий. Держать в соответствии с {@code ENUM} в таблице на сервере.
     */
    public static final class Kind {
        /** Необработанное исключение в самом лаунчере. */
        public static final String LAUNCHER_EXCEPTION = "launcher_exception";
        /** Жёсткий краш JVM — найден {@code hs_err_pid*.log}. */
        public static final String JVM_FATAL = "jvm_fatal";
        /** Игра упала и оставила {@code crash-report}. */
        public static final String MC_CRASH = "mc_crash";
        /** Процесс игры завершился ненулевым кодом без краш-репорта. */
        public static final String DIRTY_EXIT = "dirty_exit";
        /** Прошлый запуск лаунчера умер, не выполнив shutdown hook. */
        public static final String DIRTY_SHUTDOWN = "dirty_shutdown";
        /** Игрок нажал «отправить отчёт» сам. */
        public static final String MANUAL = "manual";

        private Kind() {
        }
    }
}

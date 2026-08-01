package ru.amereco.amerecolauncher.diag;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вычистка секретов и персональных данных из текста перед записью в лог
 * и перед отправкой на сервер.
 *
 * <p>Применяется <b>дважды</b>:
 * <ol>
 *   <li>в {@link ru.amereco.amerecolauncher.Log} при записи каждой строки — тогда даже
 *       локальный {@code launcher.log}, который игрок сам выложит в чат, уже безопасен;</li>
 *   <li>перед отправкой отчёта — это ловит логи самой игры, которые пишет не наш {@code Log}.</li>
 * </ol>
 *
 * <p>Два механизма дополняют друг друга:
 * <ul>
 *   <li><b>Регулярки</b> ловят секреты по имени ключа ({@code --accessToken abc},
 *       {@code "accessToken":"abc"}) — работают даже для чужих токенов из логов игры.</li>
 *   <li><b>Литеральные секреты</b> ({@link #addSecret}) ловят наш живой токен, попавший
 *       в текст без всякого ключа — например внутри сообщения исключения. Регуляркой
 *       такое не поймать в принципе.</li>
 * </ul>
 *
 * <h3>Почему класс не обращается к {@code Config}</h3>
 * Редактор вызывается из потока вывода. Если бы он дёргал {@code Config.get()}, то первый
 * же вызов во время загрузки конфига ушёл бы в рекурсию: {@code Config.load()} печатает
 * ошибку → {@code System.err} → редактор → {@code Config.get()} → … Поэтому секреты
 * кладутся снаружи через {@link #addSecret}, а сам класс ни от чего не зависит.
 */
public final class Redactor {

    /** Чем заменяем найденный секрет. */
    private static final String MASK = "[REDACTED]";

    /**
     * Короче этого секреты не маскируем литерально.
     *
     * <p>Пустая или односимвольная строка в списке секретов превратила бы каждую строку
     * лога в кашу из {@code [REDACTED]}. Реальные токены — 32 hex-символа.
     */
    private static final int MIN_SECRET_LENGTH = 8;

    /**
     * Живые секреты этого запуска: access-токен, clientId, uuid.
     * Пополняется по мере загрузки конфига и авторизации.
     */
    private static final Set<String> secrets = new CopyOnWriteArraySet<>();

    /**
     * Значения, которые вырезаются только как отдельное слово.
     *
     * <p>Отдельно от {@link #secrets}, потому что здесь лежат короткие строки —
     * прежде всего имя пользователя ОС. Типичные {@code lanode}, {@code admin},
     * {@code user} короче порога для литеральной замены и раньше не маскировались
     * вовсе. Но и подстрокой их вырезать нельзя: {@code user} превратил бы каждое
     * {@code Runtime}, {@code userName} и {@code getUser} в кашу. Границы слова
     * решают обе задачи разом.
     */
    private static final Set<Pattern> wordSecrets = new CopyOnWriteArraySet<>();

    /** Короче этого не маскируем даже по границам слова — слишком много ложных срабатываний. */
    private static final int MIN_WORD_SECRET_LENGTH = 3;

    /** Пара «что искать» → «чем заменить». */
    private record Rule(Pattern pattern, String replacement) { }

    private static final Rule[] RULES = {
        // ── Секреты ──────────────────────────────────────────────────────────────
        // Аргументы командной строки Minecraft: --accessToken <val>
        rule("(--(?:accessToken|session|clientToken|xuid))\\s+\\S+", "$1 " + MASK),
        // JSON-поля токенов в ответах authlib и в логах игры
        rule("(\"(?:access|client|refresh|auth|id)[Tt]oken\"\\s*:\\s*)\"[^\"]*\"", "$1\"" + MASK + "\""),
        // Строка, которую печатает сам Minecraft при старте
        rule("\\(Session ID is [^)]*\\)", "(Session ID is " + MASK + ")"),
        // HTTP-заголовки
        rule("(?i)(Authorization:\\s*)\\S+", "$1" + MASK),
        rule("(?i)(X-Amereco-Token:\\s*)\\S+", "$1" + MASK),
        // OAuth device flow — встречается в логах модов авторизации
        rule("(\"(?:device_code|refresh_token|access_token)\"\\s*:\\s*)\"[^\"]*\"", "$1\"" + MASK + "\""),

        // ── Пути с именем пользователя ОС ────────────────────────────────────────
        // Работают и для чужих путей из логов игры, где нашего user.name нет.
        rule("(?i)([A-Za-z]:[\\\\/]Users[\\\\/])[^\\\\/\\r\\n\"']+", "$1%USER%"),
        rule("(/home/)[^/\\r\\n\"' ]+", "$1%USER%"),
        rule("(/Users/)[^/\\r\\n\"' ]+", "$1%USER%"),
        rule("(?i)(USERNAME=)\\S+", "$1%USER%"),

        // ── Прочие ПДн ───────────────────────────────────────────────────────────
        rule("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]"),
    };

    /**
     * IPv4-адреса. Отдельно от {@link #RULES}, потому что маскируются не все:
     * loopback и приватные диапазоны диагностически полезны и ПДн не являются.
     */
    private static final Pattern IPV4 =
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private Redactor() {
    }

    private static Rule rule(String regex, String replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }

    /**
     * Добавляет литеральную строку, которую надо вырезать из любого текста.
     *
     * <p>Вызывать сразу как значение стало известно: после загрузки конфига,
     * после успешного логина, после обновления токена. Короткие и пустые строки
     * игнорируются — иначе замена превратит лог в кашу.
     */
    public static void addSecret(String secret) {
        if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
            secrets.add(secret);
        }
    }

    /**
     * Добавляет значение, которое надо вырезать как отдельное слово.
     *
     * <p>Для коротких строк вроде имени пользователя ОС: подстрокой их вырезать
     * нельзя, а не вырезать совсем — значит оставить в логе имя человека.
     */
    public static void addWordSecret(String value) {
        if (value == null || value.length() < MIN_WORD_SECRET_LENGTH) {
            return;
        }
        try {
            wordSecrets.add(Pattern.compile(
                    "\\b" + Pattern.quote(value) + "\\b", Pattern.CASE_INSENSITIVE));
        } catch (Exception ignored) {
            // Экзотическое имя, из которого не собрался шаблон, — пропускаем.
        }
    }

    /** Забывает ранее добавленный секрет (например, после разлогина). */
    public static void removeSecret(String secret) {
        if (secret != null) {
            secrets.remove(secret);
        }
    }

    /**
     * Прогоняет текст через все правила.
     *
     * <p>Никогда не бросает исключение: вычистка вызывается из потока вывода, и её
     * падение утащило бы за собой всё логирование. При любой ошибке возвращается
     * заглушка, а не исходный текст — иначе сбой в редакторе стал бы утечкой.
     */
    public static String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            String result = text;

            // Литеральные секреты — первыми: они самые опасные, и после них
            // регуляркам достаётся уже безопасный текст.
            for (String secret : secrets) {
                if (result.contains(secret)) {
                    result = result.replace(secret, MASK);
                }
            }

            for (Rule r : RULES) {
                result = r.pattern().matcher(result).replaceAll(r.replacement());
            }

            // Границы слова — после правил с путями: те уже заменили
            // /home/<имя> на /home/%USER%, здесь остаётся имя в свободном тексте.
            for (Pattern word : wordSecrets) {
                result = word.matcher(result).replaceAll("%USER%");
            }

            result = maskPublicIps(result);
            return result;
        } catch (Throwable t) {
            return "[REDACTION FAILED — строка вырезана целиком]";
        }
    }

    /**
     * Маскирует внешние IPv4, оставляя loopback и локальные сети.
     *
     * <p>{@code 127.0.0.1} и {@code 192.168.x.x} в логе полезны при разборе проблем
     * с сетью и ничьей личности не раскрывают, а вот белый адрес игрока — раскрывает.
     */
    private static String maskPublicIps(String text) {
        Matcher m = IPV4.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String ip = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(isLocal(ip) ? ip : "[IP]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Loopback, приватные диапазоны RFC 1918, link-local и «нулевой» адрес. */
    private static boolean isLocal(String ip) {
        if (ip.startsWith("127.") || ip.startsWith("10.")
                || ip.startsWith("192.168.") || ip.startsWith("169.254.")
                || ip.equals("0.0.0.0") || ip.equals("255.255.255.255")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            int dot = ip.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Заголовки секций дампа JVM, которые вырезаются целиком.
     *
     * <p>В каждой из них access-токен встречается в открытом виде, причём строка
     * бывает очень длинной и переносится — построчные регулярки такое пропускают.
     * Поэтому секции удаляются целиком, а не чистятся.
     *
     * <p>{@code Environment Variables} нужен не меньше остальных: туда попадают
     * {@code USERNAME}, {@code USERPROFILE} и всё, что игрок положил в окружение.
     */
    private static final String[] DUMP_SECTIONS = {
        "Command Line:", "java_command:", "java_class_path", "jvm_args:",
        "VM Arguments:", "Environment Variables:", "launcher_type:",
    };

    /**
     * Вырезает из дампа JVM ({@code hs_err_pid*.log}) секции, содержащие секреты.
     *
     * <p>Список секций — {@link #DUMP_SECTIONS}. Всё остальное дополнительно
     * прогоняется через обычные правила.
     */
    public static String stripCommandLine(String hsErrContent) {
        if (hsErrContent == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(hsErrContent.length());
        boolean skipping = false;
        for (String line : hsErrContent.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            String section = matchedSection(trimmed);
            if (section != null) {
                skipping = true;
                out.append(section).append(' ').append(MASK).append('\n');
                continue;
            }
            // Секция заканчивается на первой строке, начинающейся не с пробела:
            // продолжения длинных значений отбиты отступом.
            if (skipping) {
                if (line.isBlank() || !Character.isWhitespace(line.charAt(0))) {
                    skipping = false;
                } else {
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return apply(out.toString());
    }

    private static String matchedSection(String trimmedLine) {
        for (String section : DUMP_SECTIONS) {
            if (trimmedLine.startsWith(section)) {
                return section;
            }
        }
        return null;
    }
}

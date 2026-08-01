package ru.amereco.amerecolauncher.diag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Отпечаток ошибки — ключ, по которому одинаковые падения склеиваются в одну запись.
 *
 * <p>Без него каждый отчёт становится отдельной строкой в админке: сто игроков поймали
 * один и тот же баг — сто записей, и читать это невозможно. С ним получается
 * «ошибка X, 100 раз, последний раз сегодня» — то, ради чего вообще ставят Sentry.
 *
 * <p>Номера строк из стека выбрасываются намеренно: иначе правка комментария в файле
 * меняет отпечаток, и известная ошибка после пересборки выглядит как новая.
 */
public final class Fingerprint {

    /** Сколько верхних кадров стека участвуют в отпечатке. */
    private static final int FRAMES = 5;

    /** {@code (App.java:64)} и подобное — отрезаем, чтобы не зависеть от номеров строк. */
    private static final Pattern LINE_NUMBER = Pattern.compile("\\(.*\\)$");

    /**
     * Пакеты, чьи кадры не указывают на виновника краша игры.
     * Ищем первый кадр <b>вне</b> этого списка — обычно это и есть проблемный мод.
     */
    private static final String[] NOISE_PACKAGES = {
        "net.minecraft.", "java.", "javax.", "jdk.", "sun.", "com.sun.",
        "org.spongepowered.asm.", "net.fabricmc.", "cpw.mods.", "net.minecraftforge.",
    };

    private Fingerprint() {
    }

    /** Отпечаток по исключению: класс + верхние кадры стека без номеров строк. */
    public static String of(Throwable t) {
        if (t == null) {
            return sha256("null");
        }
        // Берём корневую причину: обёртки вроде RuntimeException(IOException)
        // у одной и той же поломки бывают разными.
        Throwable root = rootCause(t);

        StringBuilder sb = new StringBuilder(root.getClass().getName());
        StackTraceElement[] stack = root.getStackTrace();
        for (int i = 0; i < Math.min(FRAMES, stack.length); i++) {
            sb.append('|').append(normalize(stack[i]));
        }
        return sha256(sb.toString());
    }

    /**
     * Отпечаток по тексту краш-репорта Minecraft.
     *
     * <p>Формат {@code crash-*.txt} нам не принадлежит и меняется от версии к версии,
     * поэтому опираемся на две устойчивые вещи: строку описания и первые кадры стека.
     */
    public static String ofCrashReport(String crashReport) {
        if (crashReport == null || crashReport.isBlank()) {
            return sha256("empty-crash-report");
        }
        StringBuilder sb = new StringBuilder();
        int frames = 0;
        for (String raw : crashReport.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Description:")) {
                sb.append(line).append('|');
                continue;
            }
            if (line.startsWith("at ")) {
                if (isNoise(line.substring(3))) {
                    continue;
                }
                sb.append(LINE_NUMBER.matcher(line).replaceFirst("")).append('|');
                if (++frames >= FRAMES) {
                    break;
                }
            }
        }
        // Ничего осмысленного не нашли — хешируем первые строки как есть,
        // это всё равно лучше, чем один отпечаток на все краши сразу.
        if (sb.length() == 0) {
            String[] lines = crashReport.split("\\R");
            for (int i = 0; i < Math.min(10, lines.length); i++) {
                sb.append(lines[i].strip()).append('|');
            }
        }
        return sha256(sb.toString());
    }

    /** Отпечаток по произвольному ключу — для событий без стека (грязный выход и т.п.). */
    public static String ofKey(String key) {
        return sha256(key == null ? "" : key);
    }

    /**
     * Корневая причина цепочки {@code caused by}.
     * Защищено от циклических ссылок — они в природе встречаются.
     */
    static Throwable rootCause(Throwable t) {
        Throwable current = t;
        for (int guard = 0; guard < 32; guard++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String normalize(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    private static boolean isNoise(String frame) {
        for (String prefix : NOISE_PACKAGES) {
            if (frame.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 есть в любой JVM; ветка недостижима, но лучше отпечаток
            // похуже, чем упавший крашрепортер.
            return Integer.toHexString(input.hashCode());
        }
    }
}

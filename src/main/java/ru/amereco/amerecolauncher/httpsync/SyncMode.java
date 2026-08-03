package ru.amereco.amerecolauncher.httpsync;

import java.util.Locale;

/**
 * Как применять обновление конкретного файла. Значение приходит в записи манифеста
 * ключом {@code "mode"}.
 *
 * <p>Ключ необязателен: отсутствующее, пустое и любое незнакомое значение означает
 * {@link #REPLACE}. Поэтому старые манифесты работают без изменений, а опечатка в
 * генераторе приводит к сегодняшнему поведению, а не к отказу синхронизации.
 */
public enum SyncMode {
    /** Полная замена файла — поведение по умолчанию. */
    REPLACE,
    /** Серверные изменения вливаются в файл игрока, его правки сохраняются. */
    MERGE;

    public static SyncMode parse(String raw) {
        if (raw == null) {
            return REPLACE;
        }
        return "merge".equals(raw.trim().toLowerCase(Locale.ROOT)) ? MERGE : REPLACE;
    }
}

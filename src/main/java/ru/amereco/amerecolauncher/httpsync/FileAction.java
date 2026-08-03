package ru.amereco.amerecolauncher.httpsync;

/**
 * Что случилось с файлом между локальным индексом и манифестом сервера.
 *
 * @param path       путь относительно корня инстанса
 * @param kind       направление изменения
 * @param mode       режим применения; для {@link Kind#DELETE} берётся из <b>локального</b> индекса
 * @param remoteHash MD5 серверной версии; {@code null} для {@link Kind#DELETE}
 * @param remoteSize размер по манифесту или {@code -1}
 * @param indexHash  MD5 последней синхронизированной версии; {@code null}, если пути не было в индексе
 */
public record FileAction(
        String path,
        Kind kind,
        SyncMode mode,
        String remoteHash,
        long remoteSize,
        String indexHash) {

    public enum Kind {
        /** Пути не было в локальном индексе. */
        CREATE,
        /** Хеш в манифесте отличается от записанного в индексе. */
        UPDATE,
        /** Путь пропал из манифеста. */
        DELETE,
        /**
         * Хеши совпали. Такие записи попадают в список только для {@link SyncMode#MERGE} —
         * им может потребоваться эталонный снимок, даже когда обновлять нечего.
         */
        UNCHANGED
    }
}

package ru.amereco.amerecolauncher.httpsync;

import java.nio.file.Path;

/**
 * Решение по одному файлу: что скачивать, куда и что делать после.
 *
 * @param staging путь во временный каталог; заполнен только для планов, которым нужна загрузка «в сторону»
 */
public record PlannedFile(FileAction action, Plan plan, Path target, Path staging) {

    public enum Plan {
        /** Скачать поверх целевого файла — сегодняшнее поведение для режима замены. */
        WRITE_DIRECT,
        /** Скачать поверх целевого файла и снять с него эталонный снимок. */
        WRITE_WITH_BASE,
        /** Скачать во временный каталог, затем слить с локальным файлом. */
        STAGE_FOR_MERGE,
        /** Скачать во временный каталог только ради эталонного снимка; файл игрока не трогать. */
        BACKFILL_BASE,
        /** Снять снимок с локального файла — он и есть последняя серверная версия. Без сети. */
        SEED_BASE_FROM_LOCAL,
        /** Удалить файл, снимок и оставшийся рядом {@code .new}. */
        DELETE,
        /** Файл убран с сервера, но изменён игроком: оставить, снять с учёта. */
        KEEP_LOCAL,
        /** Делать нечего. */
        NOOP
    }

    /** Нужна ли загрузка во временный каталог. */
    public boolean staged() {
        return plan == Plan.STAGE_FOR_MERGE || plan == Plan.BACKFILL_BASE;
    }

    /** Нужна ли загрузка вообще. */
    public boolean downloads() {
        return staged() || plan == Plan.WRITE_DIRECT || plan == Plan.WRITE_WITH_BASE;
    }
}

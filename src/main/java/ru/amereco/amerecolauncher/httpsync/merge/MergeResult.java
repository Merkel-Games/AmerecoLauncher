package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.List;

/**
 * Результат слияния.
 *
 * @param outcome   удалось ли слить вообще
 * @param content   содержимое для записи; {@code null} при {@link Outcome#NOT_MERGEABLE}
 * @param changed   отличается ли результат от текущего локального файла
 * @param conflicts места, где сохранено локальное значение вопреки серверному
 * @param applied   сколько серверных изменений перенесено (ключи взяты, добавлены, убраны)
 * @param threeWay  было ли слияние трёхсторонним, то есть нашёлся ли эталонный снимок
 * @param format    формат, по которому шло слияние
 * @param reason    причина отказа для {@link Outcome#NOT_MERGEABLE}, иначе {@code null}
 */
public record MergeResult(
        Outcome outcome,
        byte[] content,
        boolean changed,
        List<String> conflicts,
        int applied,
        boolean threeWay,
        TextFormat format,
        String reason) {

    public enum Outcome {
        /** Слито. Разногласия, если были, перечислены в {@link #conflicts()}. */
        MERGED,
        /** Слить нельзя: двоичный файл, не UTF-8, слишком большой или сбой разбора. */
        NOT_MERGEABLE
    }

    public static MergeResult notMergeable(TextFormat format, String reason) {
        return new MergeResult(Outcome.NOT_MERGEABLE, null, false, List.of(), 0, false, format, reason);
    }

    public static MergeResult merged(byte[] content, boolean changed, List<String> conflicts,
                                     int applied, boolean threeWay, TextFormat format) {
        return new MergeResult(Outcome.MERGED, content, changed, List.copyOf(conflicts),
                applied, threeWay, format, null);
    }

    public boolean mergeable() {
        return outcome == Outcome.MERGED;
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}

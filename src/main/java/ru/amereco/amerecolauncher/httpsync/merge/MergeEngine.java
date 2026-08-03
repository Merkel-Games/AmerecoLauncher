package ru.amereco.amerecolauncher.httpsync.merge;

import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

/**
 * Точка входа в слияние: определяет формат, применяет ограничения и выбирает алгоритм.
 *
 * <p>По содержимому не бросает исключений — на любой непонятный вход отвечает
 * {@link MergeResult.Outcome#NOT_MERGEABLE}, а вызывающая сторона решает, что с этим делать.
 */
public final class MergeEngine {
    /** Больше этого сливать не пытаемся: самый крупный реальный кандидат — {@code options.txt} на 10 КБ. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Потолок для построчного слияния: НОП квадратична по числу строк. */
    private static final int MAX_LINES = 20_000;

    private static final TextMerger JSON_MERGER = new JsonStructuralMerger();
    private static final TextMerger PROPERTIES_MERGER = new KeyValueMerger(KeyValueSyntax.PROPERTIES);
    private static final TextMerger OPTIONS_MERGER = new KeyValueMerger(KeyValueSyntax.OPTIONS_TXT);
    private static final TextMerger LINE_MERGER = new LineMerger();

    private MergeEngine() {
    }

    public static MergeResult merge(MergeInput input) {
        TextFormat declared = TextFormat.byPath(input.path());

        if (input.local().length > MAX_BYTES || input.remote().length > MAX_BYTES) {
            return MergeResult.notMergeable(declared, "файл больше " + (MAX_BYTES / 1024) + " КБ");
        }

        TextCodec.Text local = asText(input.local());
        if (local == null) {
            return MergeResult.notMergeable(declared, "локальный файл не является текстом UTF-8");
        }
        TextCodec.Text remote = asText(input.remote());
        if (remote == null) {
            return MergeResult.notMergeable(declared, "серверный файл не является текстом UTF-8");
        }

        // Непригодный эталон не повод отказываться от слияния — просто станет двусторонним.
        TextCodec.Text base = null;
        if (input.base() != null && input.base().length <= MAX_BYTES) {
            base = asText(input.base());
        }

        TextFormat format = effectiveFormat(declared, base, local, remote);
        if (format == TextFormat.LINES) {
            int lines = Math.max(countLines(local.content), countLines(remote.content));
            if (lines > MAX_LINES) {
                return MergeResult.notMergeable(format, "больше " + MAX_LINES + " строк");
            }
        }

        TextMergeOutcome outcome;
        try {
            outcome = mergerFor(format).merge(
                    base == null ? null : base.content, local.content, remote.content);
        } catch (RuntimeException failure) {
            // Разбор мог сломаться на том, что не поймали проверки выше: слияние отменяется,
            // но синхронизация в целом продолжается.
            return MergeResult.notMergeable(format,
                    "сбой разбора: " + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }

        byte[] content = TextCodec.encode(outcome.content(), local.style);
        boolean changed = !Arrays.equals(content, input.local());
        return MergeResult.merged(content, changed, outcome.conflicts(), outcome.applied(),
                base != null, format);
    }

    /**
     * Уточняет формат по содержимому. Структурное слияние JSON включается при двух условиях.
     *
     * <p>Первое: все участвующие версии — строгий JSON. Круговой прогон через Gson иначе
     * вырезал бы комментарии и превращал висячую запятую в {@code null}-элемент массива.
     *
     * <p>Второе: локальный файл воспроизводится нашим писателем байт в байт. Оформление,
     * которое {@link JsonText} повторить не умеет, иначе переписывалось бы при каждом
     * обновлении — а файл принадлежит игроку, и трогать его без нужды нельзя.
     *
     * <p>Проверяется именно локальный файл: результат сериализуется в его стиле, поэтому
     * непривычное оформление серверной версии на итог не влияет.
     */
    private static TextFormat effectiveFormat(TextFormat declared, TextCodec.Text base,
                                              TextCodec.Text local, TextCodec.Text remote) {
        if (declared != TextFormat.JSON && declared != TextFormat.JSON5) {
            return declared;
        }
        boolean structural = JsonText.isStrictJson(local.content)
                && JsonText.isStrictJson(remote.content)
                && (base == null || JsonText.isStrictJson(base.content))
                && JsonText.roundTripsExactly(local.content);
        return structural ? declared : TextFormat.LINES;
    }

    private static TextMerger mergerFor(TextFormat format) {
        switch (format) {
            case JSON:
            case JSON5:
                return JSON_MERGER;
            case PROPERTIES:
                return PROPERTIES_MERGER;
            case OPTIONS_TXT:
                return OPTIONS_MERGER;
            default:
                return LINE_MERGER;
        }
    }

    private static TextCodec.Text asText(byte[] bytes) {
        if (TextCodec.hasNulByte(bytes)) {
            return null;
        }
        try {
            return TextCodec.decode(bytes);
        } catch (CharacterCodingException notText) {
            return null;
        }
    }

    private static int countLines(String content) {
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}

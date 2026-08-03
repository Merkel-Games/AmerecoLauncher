package ru.amereco.amerecolauncher.httpsync.merge;

/**
 * Слияние трёх версий текста. Реализации не трогают ни файловую систему, ни JavaFX —
 * только строки, что и делает их пригодными для обычных модульных тестов.
 */
interface TextMerger {
    /**
     * @param base   последняя серверная версия; {@code null} ⇒ двустороннее слияние
     * @param local  версия игрока
     * @param remote новая серверная версия
     */
    TextMergeOutcome merge(String base, String local, String remote);
}

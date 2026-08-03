package ru.amereco.amerecolauncher.httpsync.merge;

/**
 * Вход слияния: три версии одного файла.
 *
 * @param path   путь из манифеста — по нему определяется формат
 * @param base   последняя синхронизированная серверная версия; {@code null} ⇒ двустороннее слияние
 * @param local  файл на диске у игрока
 * @param remote новая версия с сервера
 */
public record MergeInput(String path, byte[] base, byte[] local, byte[] remote) {
}

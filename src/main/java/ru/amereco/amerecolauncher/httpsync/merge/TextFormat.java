package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.Locale;

/**
 * Формат содержимого, определяющий способ слияния.
 *
 * <p>Определяется по расширению пути, а затем уточняется по самому содержимому:
 * непарсимый как строгий JSON файл {@code .json5} уходит на построчное слияние,
 * а любой файл с байтом NUL или невалидным UTF-8 становится {@link #BINARY}.
 */
public enum TextFormat {
    /** Строгий JSON — структурное слияние по ключам. */
    JSON,
    /** JSON5/JSONC — структурно, только если текст оказался строгим JSON. */
    JSON5,
    /** {@code key=value} с комментариями и переносами строк. */
    PROPERTIES,
    /** Minecraft {@code options.txt}: {@code key:value}, без комментариев. */
    OPTIONS_TXT,
    /** Любой другой текст — построчное трёхстороннее слияние. */
    LINES,
    /** Не текст — слиянию не подлежит. */
    BINARY;

    /** Формат по расширению пути из манифеста. Содержимое здесь не смотрим. */
    public static TextFormat byPath(String path) {
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.equals("options.txt")) {
            return OPTIONS_TXT;
        }
        if (lower.endsWith(".json")) {
            return JSON;
        }
        if (lower.endsWith(".json5") || lower.endsWith(".jsonc")) {
            return JSON5;
        }
        if (lower.endsWith(".properties") || lower.endsWith(".lang")) {
            return PROPERTIES;
        }
        if (lower.endsWith(".toml") || lower.endsWith(".cfg") || lower.endsWith(".conf")
                || lower.endsWith(".ini") || lower.endsWith(".txt") || lower.endsWith(".yml")
                || lower.endsWith(".yaml") || lower.endsWith(".md") || lower.endsWith(".csv")
                || lower.endsWith(".xml") || lower.endsWith(".hjson")) {
            return LINES;
        }
        // Неизвестное расширение: текстовость проверит TextCodec, до тех пор считаем построчным.
        return LINES;
    }

    /** Разбирается ли формат по ключам (в отличие от построчного). */
    public boolean isStructural() {
        return this == JSON || this == JSON5 || this == PROPERTIES || this == OPTIONS_TXT;
    }
}

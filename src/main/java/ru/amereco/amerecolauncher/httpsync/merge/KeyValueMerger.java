package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Трёхстороннее слияние построчных форматов «ключ — значение».
 *
 * <p>Файл собирается по локальному: его строки идут в исходном порядке, комментарии, пустые и
 * неразбираемые строки выводятся дословно и не трогаются никогда. Когда побеждает серверное
 * значение, переписывается <b>только часть после разделителя</b> — текст ключа, ведущие пробелы
 * и сам разделитель ({@code :} или {@code =}) остаются локальными. Ключи, добавленные на сервере,
 * дописываются в конец в том виде, в каком пришли.
 */
final class KeyValueMerger implements TextMerger {
    private final KeyValueSyntax syntax;

    KeyValueMerger(KeyValueSyntax syntax) {
        this.syntax = syntax;
    }

    @Override
    public TextMergeOutcome merge(String base, String local, String remote) {
        List<Entry> localEntries = parse(local);
        List<Entry> remoteEntries = parse(remote);
        Map<String, String> baseValues = base == null ? null : values(parse(base));
        Map<String, String> localValues = values(localEntries);
        Map<String, String> remoteValues = values(remoteEntries);

        List<String> out = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int applied = 0;

        for (Entry entry : localEntries) {
            if (!entry.isPair()) {
                out.add(entry.raw);
                continue;
            }
            boolean inBase = baseValues != null && baseValues.containsKey(entry.key);
            String baseValue = inBase ? baseValues.get(entry.key) : null;

            if (!remoteValues.containsKey(entry.key)) {
                if (inBase && entry.value.equals(baseValue)) {
                    // Сервер убрал ключ, игрок его не трогал — убираем строку.
                    applied++;
                } else {
                    out.add(entry.raw);
                }
                continue;
            }

            String remoteValue = remoteValues.get(entry.key);
            if (entry.value.equals(remoteValue)) {
                out.add(entry.raw);
            } else if (inBase && entry.value.equals(baseValue)) {
                out.add(entry.prefix + entry.valueIndent + remoteValue);
                applied++;
            } else if (inBase && remoteValue.equals(baseValue)) {
                out.add(entry.raw);
            } else {
                out.add(entry.raw);
                conflicts.add(entry.key);
            }
        }

        for (Entry entry : remoteEntries) {
            if (!entry.isPair() || localValues.containsKey(entry.key)) {
                continue;
            }
            if (baseValues != null && baseValues.containsKey(entry.key)) {
                // Ключ был в эталоне и пропал локально — игрок удалил его намеренно.
                continue;
            }
            out.add(entry.raw);
            applied++;
        }

        return new TextMergeOutcome(String.join("\n", out), conflicts, applied);
    }

    /** Последнее вхождение ключа побеждает — так же, как в {@link java.util.Properties}. */
    private Map<String, String> values(List<Entry> entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.isPair()) {
                map.put(entry.key, entry.value);
            }
        }
        return map;
    }

    List<Entry> parse(String content) {
        List<Entry> entries = new ArrayList<>();
        List<String> lines = TextCodec.lines(content);
        for (int i = 0; i < lines.size(); i++) {
            StringBuilder raw = new StringBuilder(lines.get(i));
            while (syntax.continuations && endsWithContinuation(raw) && i + 1 < lines.size()) {
                i++;
                raw.append('\n').append(lines.get(i));
            }
            entries.add(parseEntry(raw.toString()));
        }
        return entries;
    }

    private Entry parseEntry(String raw) {
        int i = 0;
        while (i < raw.length() && KeyValueSyntax.isBlank(raw.charAt(i))) {
            i++;
        }
        if (i >= raw.length() || syntax.isComment(raw.charAt(i))) {
            return Entry.plain(raw);
        }

        int keyStart = i;
        int sepStart = -1;
        int sepEnd = -1;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\') {
                // Экранированный символ (в том числе \: и \=) частью разделителя быть не может.
                i += 2;
                continue;
            }
            if (syntax.isSeparator(c)) {
                sepStart = i;
                sepEnd = i + 1;
                break;
            }
            if (syntax.whitespaceSeparator && KeyValueSyntax.isBlank(c)) {
                sepStart = i;
                int j = i;
                while (j < raw.length() && KeyValueSyntax.isBlank(raw.charAt(j))) {
                    j++;
                }
                if (j < raw.length() && syntax.isSeparator(raw.charAt(j))) {
                    j++;
                }
                sepEnd = j;
                break;
            }
            i++;
        }

        if (sepStart < 0 || sepStart == keyStart) {
            // Ни разделителя, ни ключа — строка выводится дословно.
            return Entry.plain(raw);
        }

        String key = raw.substring(keyStart, sepStart);
        String prefix = raw.substring(0, sepEnd);
        int valueStart = sepEnd;
        while (valueStart < raw.length() && KeyValueSyntax.isBlank(raw.charAt(valueStart))) {
            valueStart++;
        }
        String valueIndent = raw.substring(sepEnd, valueStart);
        String value = raw.substring(valueStart);
        return new Entry(raw, key, prefix, valueIndent, value);
    }

    private static boolean endsWithContinuation(CharSequence line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Одна запись файла. Ключ хранится ровно в том виде, в каком записан в файле (вместе с
     * экранированием): базовый, локальный и серверный файлы порождает один и тот же инструмент,
     * поэтому сопоставлять их можно напрямую, не разворачивая escape-последовательности.
     */
    static final class Entry {
        final String raw;
        final String key;
        final String prefix;
        final String valueIndent;
        final String value;

        private Entry(String raw, String key, String prefix, String valueIndent, String value) {
            this.raw = raw;
            this.key = key;
            this.prefix = prefix;
            this.valueIndent = valueIndent;
            this.value = value;
        }

        static Entry plain(String raw) {
            return new Entry(raw, null, null, null, null);
        }

        boolean isPair() {
            return key != null;
        }
    }
}

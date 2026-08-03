package ru.amereco.amerecolauncher.httpsync.merge;

/** Синтаксис построчного формата «ключ — значение». */
final class KeyValueSyntax {
    /**
     * {@code .properties} по правилам {@link java.util.Properties}: комментарии {@code #} и
     * {@code !}, перенос строки обратным слешем, разделитель — {@code =}, {@code :} или пробелы.
     */
    static final KeyValueSyntax PROPERTIES = new KeyValueSyntax(true, true, true, "=:");

    /**
     * Minecraft {@code options.txt}: строго {@code ключ:значение}, по первому двоеточию.
     * Значения законно содержат двоеточия ({@code key_key.attack:key.mouse.left}), комментариев
     * и переносов в формате нет.
     */
    static final KeyValueSyntax OPTIONS_TXT = new KeyValueSyntax(false, false, false, ":");

    final boolean comments;
    final boolean continuations;
    final boolean whitespaceSeparator;
    private final String separators;

    private KeyValueSyntax(boolean comments, boolean continuations,
                           boolean whitespaceSeparator, String separators) {
        this.comments = comments;
        this.continuations = continuations;
        this.whitespaceSeparator = whitespaceSeparator;
        this.separators = separators;
    }

    boolean isSeparator(char c) {
        return separators.indexOf(c) >= 0;
    }

    boolean isComment(char c) {
        return comments && (c == '#' || c == '!');
    }

    static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }
}

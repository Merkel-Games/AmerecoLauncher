package ru.amereco.amerecolauncher.httpsync.merge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Загрузка настоящих конфигов сборки из {@code src/test/resources/fixtures}. */
final class Fixtures {
    private Fixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("нет фикстуры: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Содержимое фикстуры как текст.
     *
     * <p>Фикстуры сравниваются побайтово, поэтому git не должен подменять в них переводы
     * строк — за это отвечает {@code -text} в {@code .gitattributes}. Если правило потеряется,
     * тесты воспроизведения формата упадут с километровым диффом; лучше сказать прямо.
     */
    static String text(String name) {
        String content = new String(bytes(name), StandardCharsets.UTF_8);
        if (content.indexOf('\r') >= 0) {
            throw new IllegalStateException("фикстура " + name + " пришла с CRLF: git подменил"
                    + " переводы строк. Проверьте правило -text в .gitattributes"
                    + " и перевыньте файл (git rm --cached + checkout).");
        }
        return content;
    }
}

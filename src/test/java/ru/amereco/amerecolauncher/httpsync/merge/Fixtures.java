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

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}

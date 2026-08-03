package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MergeEngineTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static MergeResult merge(String path, String base, String local, String remote) {
        return MergeEngine.merge(new MergeInput(path,
                base == null ? null : b(base), b(local), b(remote)));
    }

    @Nested
    @DisplayName("выбор формата")
    class Routing {
        @Test
        void jsonGoesStructural() {
            MergeResult result = merge("config/mod.json", "{\"a\":1}", "{\"a\":1}", "{\"a\":2}");
            assertEquals(TextFormat.JSON, result.format());
            assertEquals("{\"a\":2}", s(result.content()));
        }

        @Test
        @DisplayName("строгий json5 разбирается структурно")
        void strictJson5GoesStructural() {
            MergeResult result = merge("config/maplink/general.json5",
                    "{\"a\":1}", "{\"a\":1}", "{\"a\":2}");
            assertEquals(TextFormat.JSON5, result.format());
        }

        @Test
        @DisplayName("json5 с комментариями уходит на построчное слияние и комментарии остаются")
        void commentedJson5FallsBackToLines() {
            String base = "{\n\t// пояснение мода\n\t\"a\": 1\n}";
            String local = "{\n\t// пояснение мода\n\t\"a\": 9\n}";
            String remote = "{\n\t// пояснение мода\n\t\"a\": 1,\n\t\"b\": 2\n}";

            MergeResult result = merge("config/mod.json5", base, local, remote);
            assertEquals(TextFormat.LINES, result.format());
            assertTrue(s(result.content()).contains("// пояснение мода"), "комментарий вырезан");
            assertTrue(s(result.content()).contains("\"a\": 9"), "правка игрока стёрта");
        }

        @Test
        @DisplayName("висячая запятая тоже уводит на построчное — Gson вставил бы null")
        void trailingCommaFallsBackToLines() {
            String json = "{\n\t\"a\": [1, 2,]\n}";
            MergeResult result = merge("config/mod.json", json, json, json);
            assertEquals(TextFormat.LINES, result.format());
            assertFalse(s(result.content()).contains("null"));
        }

        @Test
        @DisplayName("невоспроизводимое оформление уводит на построчное слияние и остаётся нетронутым")
        void unreproducibleStyleFallsBackToLines() {
            // Стиль Jackson: пробел перед двоеточием. Встречается в конфигах Valkyrien Skies.
            String base = "{\n  \"a\" : 1,\n  \"b\" : 2\n}";
            String local = "{\n  \"a\" : 9,\n  \"b\" : 2\n}";
            String remote = "{\n  \"a\" : 1,\n  \"b\" : 3\n}";

            MergeResult result = merge("config/vs_client.json", base, local, remote);
            assertEquals(TextFormat.LINES, result.format());
            assertTrue(s(result.content()).contains("\"a\" : 9"), () -> s(result.content()));
            assertTrue(s(result.content()).contains("\"b\" : 3"), () -> s(result.content()));
        }

        @Test
        @DisplayName("слияние без изменений не переписывает файл с чужим оформлением")
        void unreproducibleStyleIsNotRewritten() {
            String json = "{\n  \"a\" : \"Pos\\u003d0\"\n}";
            MergeResult result = merge("config/axiom.json", json, json, json);
            assertFalse(result.changed(), "файл игрока переписан без нужды");
        }

        @Test
        void optionsTxtGoesKeyValue() {
            MergeResult result = merge("options.txt", "fov:70", "fov:90", "fov:70");
            assertEquals(TextFormat.OPTIONS_TXT, result.format());
            assertEquals("fov:90", s(result.content()));
        }

        @Test
        void propertiesGoesKeyValue() {
            MergeResult result = merge("config/a.properties", "a=1", "a=1", "a=2");
            assertEquals(TextFormat.PROPERTIES, result.format());
        }

        @Test
        void tomlGoesLines() {
            MergeResult result = merge("config/a.toml", "x = 1", "x = 1", "x = 2");
            assertEquals(TextFormat.LINES, result.format());
            assertEquals("x = 2", s(result.content()));
        }
    }

    @Nested
    @DisplayName("отказ от слияния")
    class NotMergeable {
        @Test
        void binaryLocal() {
            MergeResult result = MergeEngine.merge(new MergeInput("servers.dat",
                    null, new byte[]{0x0A, 0, 0, 9}, b("текст")));
            assertFalse(result.mergeable());
            assertNotNull(result.reason());
        }

        @Test
        void binaryRemote() {
            MergeResult result = MergeEngine.merge(new MergeInput("options.txt",
                    null, b("fov:70"), new byte[]{0x0A, 0, 0, 9}));
            assertFalse(result.mergeable());
        }

        @Test
        void tooBig() {
            byte[] huge = new byte[MergeEngine.MAX_BYTES + 1];
            java.util.Arrays.fill(huge, (byte) 'a');
            MergeResult result = MergeEngine.merge(new MergeInput("config/a.toml",
                    null, huge, b("x")));
            assertFalse(result.mergeable());
        }

        @Test
        @DisplayName("непригодный эталон не отменяет слияние — оно становится двусторонним")
        void unusableBaseDegradesToTwoWay() {
            MergeResult result = MergeEngine.merge(new MergeInput("options.txt",
                    new byte[]{0, 0, 0}, b("fov:90"), b("fov:70\nnew:1")));
            assertTrue(result.mergeable());
            assertFalse(result.threeWay());
            assertEquals("fov:90\nnew:1", s(result.content()));
        }
    }

    @Nested
    @DisplayName("итог слияния")
    class Outcome {
        @Test
        @DisplayName("результат совпал с локальным файлом — переписывать нечего")
        void unchangedIsReported() {
            MergeResult result = merge("options.txt", "fov:70", "fov:90", "fov:70");
            assertTrue(result.mergeable());
            assertFalse(result.changed());
        }

        @Test
        void changedIsReported() {
            MergeResult result = merge("options.txt", "fov:70", "fov:70", "fov:80");
            assertTrue(result.changed());
            assertEquals("fov:80", s(result.content()));
        }

        @Test
        @DisplayName("конфликты перечисляются поимённо")
        void conflictsListed() {
            MergeResult result = merge("options.txt", "fov:70", "fov:90", "fov:100");
            assertTrue(result.hasConflicts());
            assertEquals(java.util.List.of("fov"), result.conflicts());
        }

        @Test
        @DisplayName("трёхстороннее отличается от двустороннего")
        void threeWayFlag() {
            assertTrue(merge("options.txt", "fov:70", "fov:90", "fov:100").threeWay());
            assertFalse(merge("options.txt", null, "fov:90", "fov:100").threeWay());
        }

        @Test
        @DisplayName("оформление локального файла не меняется")
        void stylePreserved() {
            MergeResult result = MergeEngine.merge(new MergeInput("options.txt",
                    b("fov:70\r\n"), b("fov:70\r\n"), b("fov:80\n")));
            assertEquals("fov:80\r\n", s(result.content()));
        }
    }
}

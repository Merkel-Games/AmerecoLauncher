package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Калитка структурного слияния. Ложное «да» здесь означает вырезанные комментарии или
 * висячую запятую, превращённую Gson в {@code null}-элемент массива — то есть порчу
 * конфига игрока. Ложное «нет» всего лишь отправляет файл на построчное слияние.
 */
class JsonTextTest {

    @Nested
    @DisplayName("строгий JSON распознаётся")
    class Strict {
        @Test
        void plainObject() {
            assertTrue(JsonText.isStrictJson("{\"a\": 1, \"b\": [1, 2], \"c\": {\"d\": null}}"));
        }

        @Test
        void emptyContainers() {
            assertTrue(JsonText.isStrictJson("{\"markers\": [], \"nested\": {}}"));
        }

        @Test
        @DisplayName("двойной слеш внутри строки — не комментарий")
        void doubleSlashInsideString() {
            // Ровно этот случай есть в живом config/maplink/general.json5.
            assertTrue(JsonText.isStrictJson("{\"link\": \"https://amereco.ru/map/\"}"));
        }

        @Test
        @DisplayName("решётка и звёздочка внутри строки — не комментарий")
        void commentCharsInsideString() {
            assertTrue(JsonText.isStrictJson("{\"a\": \"#ff00ff\", \"b\": \"/* not a comment */\"}"));
            assertTrue(JsonText.isStrictJson("{\"fmt\": \"&7Sent at &e{hh:mm:ss a}&7.\"}"));
        }

        @Test
        @DisplayName("экранированная кавычка не закрывает строку")
        void escapedQuote() {
            assertTrue(JsonText.isStrictJson("{\"a\": \"он сказал \\\" // не комментарий\"}"));
        }

        @Test
        void realFixturesAreStrictJson() {
            assertTrue(JsonText.isStrictJson(Fixtures.text("general.json5")));
            assertTrue(JsonText.isStrictJson(Fixtures.text("chatplus.json")));
        }
    }

    @Nested
    @DisplayName("нестрогий JSON5 отклоняется")
    class Lenient {
        @Test
        void lineComment() {
            assertFalse(JsonText.isStrictJson("{\n// пояснение\n\"a\": 1\n}"));
        }

        @Test
        void hashComment() {
            assertFalse(JsonText.isStrictJson("{\n# пояснение\n\"a\": 1\n}"));
        }

        @Test
        void blockComment() {
            assertFalse(JsonText.isStrictJson("{/* пояснение */\"a\": 1}"));
        }

        @Test
        void trailingCommaInArray() {
            // Gson в ленивом режиме превратил бы это в [1, 2, null].
            assertFalse(JsonText.isStrictJson("{\"a\": [1, 2,]}"));
        }

        @Test
        void trailingCommaInObject() {
            assertFalse(JsonText.isStrictJson("{\"a\": 1,}"));
        }

        @Test
        void singleQuotedString() {
            assertFalse(JsonText.isStrictJson("{'a': 1}"));
        }

        @Test
        void unquotedName() {
            assertFalse(JsonText.isStrictJson("{a: 1}"));
        }

        @Test
        void garbageAfterDocument() {
            assertFalse(JsonText.isStrictJson("{\"a\": 1} мусор"));
        }

        @Test
        void notJsonAtAll() {
            assertFalse(JsonText.isStrictJson("[general]\nkey = value\n"));
            assertFalse(JsonText.isStrictJson(""));
        }

        @Test
        @DisplayName("висячая запятая правда портится Gson — ради этого и нужна калитка")
        void gsonReallyCorruptsTrailingComma() {
            var parsed = JsonParser.parseString("{\"a\": [1, 2,]}");
            assertEquals(3, parsed.getAsJsonObject().getAsJsonArray("a").size());
            assertTrue(parsed.getAsJsonObject().getAsJsonArray("a").get(2).isJsonNull());
        }
    }

    @Nested
    @DisplayName("определение отступа")
    class Indent {
        @Test
        void tabs() {
            assertEquals("\t", JsonText.detectIndent(Fixtures.text("general.json5")));
        }

        @Test
        void spaces() {
            assertEquals("    ", JsonText.detectIndent(Fixtures.text("chatplus.json")));
        }

        @Test
        void compactStaysCompact() {
            assertEquals("", JsonText.detectIndent("{\"a\":1}"));
        }

        @Test
        void multilineWithoutIndentFallsBackToTwoSpaces() {
            assertEquals("  ", JsonText.detectIndent("{\n\"a\": 1\n}"));
        }
    }

    @Nested
    @DisplayName("круговой прогон не меняет файл")
    class RoundTrip {
        @Test
        void generalJson5() {
            assertRoundTrips("general.json5");
        }

        @Test
        void chatplusJson() {
            assertRoundTrips("chatplus.json");
        }

        private void assertRoundTrips(String fixture) {
            String original = Fixtures.text(fixture);
            String written = JsonText.write(JsonParser.parseString(original),
                    JsonText.detectIndent(original));
            assertEquals(original, written,
                    "круговой прогон " + fixture + " обязан быть побайтово точным");
        }

        @Test
        @DisplayName("кириллица не превращается в \\uXXXX, спецсимволы HTML не экранируются")
        void noEscaping() {
            String source = "{\n  \"привет\": \"мир <b>&</b> 'кавычки'\"\n}";
            assertEquals(source, JsonText.write(JsonParser.parseString(source), "  "));
        }

        @Test
        @DisplayName("числа сохраняют исходную запись")
        void numbersKeepTheirForm() {
            String source = "{\n  \"a\": 1.50,\n  \"b\": 10,\n  \"c\": 1e3\n}";
            assertEquals(source, JsonText.write(JsonParser.parseString(source), "  "));
        }
    }

    @Nested
    @DisplayName("оформление, которое мы не умеем повторить, признаётся таковым")
    class RoundTripGate {
        @Test
        void ourOwnStyleIsReproducible() {
            assertTrue(JsonText.roundTripsExactly(Fixtures.text("general.json5")));
            assertTrue(JsonText.roundTripsExactly(Fixtures.text("chatplus.json")));
            assertTrue(JsonText.roundTripsExactly("{\"a\":1}"));
        }

        @Test
        @DisplayName("пробел перед двоеточием (стиль Jackson) — как в конфигах Valkyrien Skies")
        void jacksonSpacingIsNotReproducible() {
            assertTrue(JsonText.isStrictJson("{\n  \"a\" : 1\n}"),
                    "это строгий JSON, поэтому одной проверки строгости мало");
            assertFalse(JsonText.roundTripsExactly("{\n  \"a\" : 1\n}"));
        }

        @Test
        @DisplayName("htmlSafe-экранирование Gson — как в config/axiom/axiom.json")
        void htmlEscapedOutputIsNotReproducible() {
            assertTrue(JsonText.isStrictJson("{\n  \"a\": \"Pos\\u003d0\"\n}"));
            assertFalse(JsonText.roundTripsExactly("{\n  \"a\": \"Pos\\u003d0\"\n}"));
        }

        @Test
        @DisplayName("комментарии и висячие запятые тоже не воспроизводятся")
        void lenientConstructsRejected() {
            assertFalse(JsonText.roundTripsExactly("{\n  // c\n  \"a\": 1\n}"));
            assertFalse(JsonText.roundTripsExactly("{\"a\": [1,]}"));
            assertFalse(JsonText.roundTripsExactly("вовсе не json"));
        }
    }
}

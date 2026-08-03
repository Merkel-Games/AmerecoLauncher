package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonStructuralMergerTest {
    private final JsonStructuralMerger merger = new JsonStructuralMerger();

    private TextMergeOutcome merge(String base, String local, String remote) {
        return merger.merge(base, local, remote);
    }

    private void assertJson(String expected, String actual) {
        assertTrue(JsonValues.equal(JsonParser.parseString(expected), JsonParser.parseString(actual)),
                () -> "ожидалось " + expected + ", получено " + actual);
    }

    private List<String> keys(String json) {
        return new ArrayList<>(JsonParser.parseString(json).getAsJsonObject().keySet());
    }

    @Nested
    @DisplayName("трёхстороннее слияние, значение есть у всех")
    class ThreeWayPresent {
        @Test
        @DisplayName("менял только сервер — берём серверное")
        void serverOnly() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":1}", "{\"a\":2}");
            assertJson("{\"a\":2}", out.content());
            assertEquals(1, out.applied());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("менял только игрок — оставляем локальное")
        void playerOnly() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":2}", "{\"a\":1}");
            assertJson("{\"a\":2}", out.content());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("оба поменяли одинаково — не конфликт")
        void sameChange() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":2}", "{\"a\":2}");
            assertJson("{\"a\":2}", out.content());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("оба поменяли по-разному — локальное побеждает, конфликт записан")
        void bothChanged() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":2}", "{\"a\":3}");
            assertJson("{\"a\":2}", out.content());
            assertIterableEquals(List.of("a"), out.conflicts());
        }
    }

    @Nested
    @DisplayName("трёхстороннее слияние, ключа где-то нет")
    class ThreeWayMissing {
        @Test
        @DisplayName("сервер убрал ключ, игрок его не трогал — убираем")
        void removedUntouched() {
            TextMergeOutcome out = merge("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":2}", "{\"a\":1}");
            assertJson("{\"a\":1}", out.content());
            assertEquals(1, out.applied());
        }

        @Test
        @DisplayName("сервер убрал ключ, но игрок его менял — оставляем")
        void removedButEdited() {
            TextMergeOutcome out = merge("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":99}", "{\"a\":1}");
            assertJson("{\"a\":1,\"b\":99}", out.content());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("игрок удалил ключ намеренно — не возвращаем")
        void deletedByPlayerStaysDeleted() {
            TextMergeOutcome out = merge("{\"a\":1,\"b\":2}", "{\"a\":1}", "{\"a\":1,\"b\":5}");
            assertJson("{\"a\":1}", out.content());
        }

        @Test
        @DisplayName("собственный ключ игрока сохраняется")
        void playerOwnKeyKept() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":1,\"мой\":true}", "{\"a\":1}");
            assertJson("{\"a\":1,\"мой\":true}", out.content());
        }

        @Test
        @DisplayName("новый серверный ключ добавляется — ради этого всё и делается")
        void newServerKeyAdded() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":1,\"мой\":true}", "{\"a\":1,\"новый\":\"x\"}");
            assertJson("{\"a\":1,\"мой\":true,\"новый\":\"x\"}", out.content());
            assertEquals(1, out.applied());
        }
    }

    @Nested
    @DisplayName("двустороннее слияние без эталона")
    class TwoWay {
        @Test
        void equalValuesKept() {
            assertJson("{\"a\":1}", merge(null, "{\"a\":1}", "{\"a\":1}").content());
        }

        @Test
        @DisplayName("расхождение без эталона — локальное побеждает и это конфликт")
        void differingValuesConflict() {
            TextMergeOutcome out = merge(null, "{\"a\":2}", "{\"a\":3}");
            assertJson("{\"a\":2}", out.content());
            assertIterableEquals(List.of("a"), out.conflicts());
        }

        @Test
        void localOnlyKept() {
            assertJson("{\"a\":1,\"мой\":2}", merge(null, "{\"a\":1,\"мой\":2}", "{\"a\":1}").content());
        }

        @Test
        void remoteOnlyAdded() {
            TextMergeOutcome out = merge(null, "{\"a\":1}", "{\"a\":1,\"новый\":2}");
            assertJson("{\"a\":1,\"новый\":2}", out.content());
            assertEquals(1, out.applied());
        }
    }

    @Nested
    @DisplayName("вложенность и типы")
    class Structure {
        @Test
        @DisplayName("вложенные объекты сливаются рекурсивно, путь конфликта — через точку")
        void nestedRecursion() {
            TextMergeOutcome out = merge(
                    "{\"g\":{\"x\":1,\"y\":1}}",
                    "{\"g\":{\"x\":9,\"y\":1}}",
                    "{\"g\":{\"x\":7,\"y\":2}}");
            assertJson("{\"g\":{\"x\":9,\"y\":2}}", out.content());
            assertIterableEquals(List.of("g.x"), out.conflicts());
        }

        @Test
        @DisplayName("объект против скаляра — неделимое сравнение, а не рекурсия")
        void typeMismatch() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":{\"b\":2}}", "{\"a\":3}");
            assertJson("{\"a\":{\"b\":2}}", out.content());
            assertIterableEquals(List.of("a"), out.conflicts());
        }

        @Test
        @DisplayName("массив неделим: сервер обновил, игрок не трогал — игрок получает обновление")
        void arrayTakenFromServer() {
            TextMergeOutcome out = merge(
                    "{\"layers\":[\"a\",\"b\"]}",
                    "{\"layers\":[\"a\",\"b\"]}",
                    "{\"layers\":[\"a\",\"b\",\"c\"]}");
            assertJson("{\"layers\":[\"a\",\"b\",\"c\"]}", out.content());
        }

        @Test
        @DisplayName("массив неделим: пустой чёрный список игрока не воскрешается объединением")
        void emptyBlacklistStaysEmpty() {
            TextMergeOutcome out = merge(
                    "{\"markers\":[\"x\"]}",
                    "{\"markers\":[]}",
                    "{\"markers\":[\"x\"]}");
            assertJson("{\"markers\":[]}", out.content());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("1 и 1.0 — одно значение, ложного конфликта нет")
        void numberNormalisation() {
            TextMergeOutcome out = merge("{\"a\":1}", "{\"a\":1.0}", "{\"a\":1.00}");
            assertTrue(out.conflicts().isEmpty());
        }
    }

    @Nested
    @DisplayName("оформление")
    class Formatting {
        @Test
        @DisplayName("порядок ключей локальный, серверные новинки — в конец")
        void keyOrder() {
            TextMergeOutcome out = merge(null,
                    "{\"z\":1,\"a\":2}",
                    "{\"a\":2,\"z\":1,\"новый\":3}");
            assertIterableEquals(List.of("z", "a", "новый"), keys(out.content()));
        }

        @Test
        @DisplayName("отступ берётся у локального файла")
        void indentFollowsLocal() {
            TextMergeOutcome out = merge(null, "{\n\t\"a\": 1\n}", "{\n  \"a\": 1,\n  \"b\": 2\n}");
            assertTrue(out.content().contains("\n\t\"a\": 1"),
                    () -> "ожидался таб-отступ, получено:\n" + out.content());
        }

        @Test
        @DisplayName("слияние настоящего конфига с самим собой ничего не меняет")
        void realConfigIsStable() {
            String general = Fixtures.text("general.json5");
            assertEquals(general, merge(general, general, general).content());

            String chatplus = Fixtures.text("chatplus.json");
            assertEquals(chatplus, merge(chatplus, chatplus, chatplus).content());
        }

        @Test
        @DisplayName("правка игрока в настоящем конфиге переживает серверное обновление")
        void realConfigKeepsPlayerEdit() {
            String base = Fixtures.text("chatplus.json");
            String local = base.replace("\"maxMessages\": -1", "\"maxMessages\": 500");
            String remote = base.replace("\"maxCommandSuggestions\": 15", "\"maxCommandSuggestions\": 25");

            TextMergeOutcome out = merge(base, local, remote);
            assertTrue(out.content().contains("\"maxMessages\": 500"), "правка игрока стёрта");
            assertTrue(out.content().contains("\"maxCommandSuggestions\": 25"), "серверное изменение не применено");
            assertTrue(out.conflicts().isEmpty());
        }
    }
}

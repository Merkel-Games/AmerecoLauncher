package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KeyValueMergerTest {
    private final KeyValueMerger options = new KeyValueMerger(KeyValueSyntax.OPTIONS_TXT);
    private final KeyValueMerger properties = new KeyValueMerger(KeyValueSyntax.PROPERTIES);

    @Nested
    @DisplayName("options.txt")
    class Options {
        @Test
        @DisplayName("серверное изменение применяется, правка игрока сохраняется")
        void mergesBothSides() {
            String base = "fov:70\nguiScale:2\nrenderDistance:12";
            String local = "fov:90\nguiScale:2\nrenderDistance:12";
            String remote = "fov:70\nguiScale:3\nrenderDistance:12";

            TextMergeOutcome out = options.merge(base, local, remote);
            assertEquals("fov:90\nguiScale:3\nrenderDistance:12", out.content());
            assertTrue(out.conflicts().isEmpty());
        }

        @Test
        @DisplayName("оба поменяли одно поле — остаётся локальное, конфликт записан")
        void conflictKeepsLocal() {
            TextMergeOutcome out = options.merge("fov:70", "fov:90", "fov:100");
            assertEquals("fov:90", out.content());
            assertIterableEquals(List.of("fov"), out.conflicts());
        }

        @Test
        @DisplayName("новый серверный ключ дописывается в конец")
        void serverKeyAppended() {
            TextMergeOutcome out = options.merge("fov:70", "fov:90", "fov:70\nnewOption:true");
            assertEquals("fov:90\nnewOption:true", out.content());
            assertEquals(1, out.applied());
        }

        @Test
        @DisplayName("собственная строка игрока не трогается")
        void playerKeyKept() {
            TextMergeOutcome out = options.merge("fov:70", "fov:70\nmyOwn:1", "fov:80");
            assertEquals("fov:80\nmyOwn:1", out.content());
        }

        @Test
        @DisplayName("значение с двоеточиями режется только по первому")
        void valueWithColons() {
            String base = "key_key.attack:key.mouse.left";
            String local = "key_key.attack:key.mouse.right";
            TextMergeOutcome out = options.merge(base, local, base);
            assertEquals(local, out.content());
        }

        @Test
        @DisplayName("список ресурспаков — одно неделимое значение, порядок игрока не ломается")
        void resourcePacksAreAtomic() {
            String base = "resourcePacks:[\"vanilla\",\"file/a.zip\"]";
            String local = "resourcePacks:[\"file/a.zip\",\"vanilla\"]";
            String remote = "resourcePacks:[\"vanilla\",\"file/a.zip\",\"file/b.zip\"]";

            TextMergeOutcome out = options.merge(base, local, remote);
            assertEquals(local, out.content());
            assertIterableEquals(List.of("resourcePacks"), out.conflicts());
        }

        @Test
        @DisplayName("строка без двоеточия выводится дословно")
        void nonPairLineKeptVerbatim() {
            TextMergeOutcome out = options.merge("мусор\nfov:70", "мусор\nfov:70", "мусор\nfov:80");
            assertEquals("мусор\nfov:80", out.content());
        }

        @Test
        @DisplayName("сервер убрал ключ: не тронутый игроком — убираем, тронутый — оставляем")
        void removal() {
            assertEquals("fov:70",
                    options.merge("fov:70\nold:1", "fov:70\nold:1", "fov:70").content());
            assertEquals("fov:70\nold:9",
                    options.merge("fov:70\nold:1", "fov:70\nold:9", "fov:70").content());
        }

        @Test
        @DisplayName("без эталона расхождение — конфликт, а добавления сервера применяются")
        void twoWay() {
            TextMergeOutcome out = options.merge(null, "fov:90\nmyOwn:1", "fov:70\nnewOption:2");
            assertEquals("fov:90\nmyOwn:1\nnewOption:2", out.content());
            assertIterableEquals(List.of("fov"), out.conflicts());
        }
    }

    @Nested
    @DisplayName(".properties")
    class Properties {
        @Test
        @DisplayName("комментарии, пустые строки и порядок сохраняются дословно")
        void commentsAndOrderPreserved() {
            String base = "# заголовок\n\na=1\n! другое\nb=2";
            String local = "# заголовок\n\na=1\n! другое\nb=2";
            String remote = "# ДРУГОЙ заголовок\n\na=5\nb=2";

            TextMergeOutcome out = properties.merge(base, local, remote);
            assertEquals("# заголовок\n\na=5\n! другое\nb=2", out.content());
        }

        @Test
        @DisplayName("разделитель и пробелы вокруг него остаются локальными")
        void separatorStyleKept() {
            TextMergeOutcome out = properties.merge("a = 1", "a = 1", "a=2");
            assertEquals("a = 2", out.content());
        }

        @Test
        @DisplayName("двоеточие как разделитель")
        void colonSeparator() {
            TextMergeOutcome out = properties.merge("a:1", "a:1", "a:2");
            assertEquals("a:2", out.content());
        }

        @Test
        @DisplayName("пробел как разделитель")
        void whitespaceSeparator() {
            TextMergeOutcome out = properties.merge("a 1", "a 1", "a 2");
            assertEquals("a 2", out.content());
        }

        @Test
        @DisplayName("экранированный разделитель внутри ключа не разрывает строку")
        void escapedSeparatorInKey() {
            TextMergeOutcome out = properties.merge("a\\:b=1", "a\\:b=1", "a\\:b=2");
            assertEquals("a\\:b=2", out.content());
        }

        @Test
        @DisplayName("перенос строки обратным слешем — одна запись")
        void continuation() {
            String base = "a=один \\\nдва\nb=3";
            String local = "a=один \\\nдва\nb=3";
            String remote = "a=один \\\nдва\nb=4";

            TextMergeOutcome out = properties.merge(base, local, remote);
            assertEquals("a=один \\\nдва\nb=4", out.content());
        }

        @Test
        @DisplayName("значение со знаком = режется по первому разделителю")
        void equalsInsideValue() {
            TextMergeOutcome out = properties.merge("a=x=y", "a=x=y", "a=x=z");
            assertEquals("a=x=z", out.content());
        }

        @Test
        @DisplayName("дубль ключа: побеждает последнее вхождение, как в java.util.Properties")
        void duplicateKeyLastWins() {
            TextMergeOutcome out = properties.merge("a=1\na=2", "a=1\na=2", "a=1\na=3");
            assertTrue(out.content().endsWith("a=3"), () -> "получено: " + out.content());
        }
    }
}

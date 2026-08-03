package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Diff3Test {
    private static List<String> lines(String text) {
        return TextCodec.lines(text);
    }

    private static String merge3(String base, String local, String remote) {
        return TextCodec.join(Diff3.merge3(lines(base), lines(local), lines(remote)).lines);
    }

    private static Diff3.Result result3(String base, String local, String remote) {
        return Diff3.merge3(lines(base), lines(local), lines(remote));
    }

    @Nested
    @DisplayName("трёхстороннее")
    class ThreeWay {
        @Test
        void nothingChanged() {
            assertEquals("a\nb\nc", merge3("a\nb\nc", "a\nb\nc", "a\nb\nc"));
        }

        @Test
        @DisplayName("менял только сервер — изменение применяется")
        void serverOnly() {
            assertEquals("a\nB\nc", merge3("a\nb\nc", "a\nb\nc", "a\nB\nc"));
        }

        @Test
        @DisplayName("менял только игрок — правка сохраняется")
        void playerOnly() {
            assertEquals("a\nX\nc", merge3("a\nb\nc", "a\nX\nc", "a\nb\nc"));
        }

        @Test
        @DisplayName("правки в разных местах сливаются обе")
        void disjointEdits() {
            assertEquals("X\nb\nZ", merge3("a\nb\nc", "X\nb\nc", "a\nb\nZ"));
        }

        @Test
        @DisplayName("правки в одном месте — остаётся локальное, конфликт записан")
        void overlappingEdits() {
            Diff3.Result out = result3("a\nb\nc", "a\nX\nc", "a\nY\nc");
            assertEquals("a\nX\nc", TextCodec.join(out.lines));
            assertEquals(1, out.conflicts.size());
        }

        @Test
        @DisplayName("оба удалили одно и то же — не конфликт")
        void bothDeleted() {
            assertEquals("a\nc", merge3("a\nb\nc", "a\nc", "a\nc"));
        }

        @Test
        @DisplayName("сервер дописал в конец, игрок — в начало")
        void insertionsAtBothEnds() {
            assertEquals("начало\na\nb\nконец", merge3("a\nb", "начало\na\nb", "a\nb\nконец"));
        }

        @Test
        @DisplayName("номер спорной строки попадает в описание конфликта")
        void conflictCarriesLineNumber() {
            Diff3.Result out = result3("a\nb\nc", "a\nX\nc", "a\nY\nc");
            assertIterableEquals(List.of("строка 2"), out.conflicts);
        }

        @Test
        void emptyInputs() {
            assertEquals("", merge3("", "", ""));
            assertEquals("новое", merge3("", "", "новое"));
            assertEquals("моё", merge3("", "моё", ""));
        }

        @Test
        @DisplayName("настоящий toml: серверный ключ добавлен, правка игрока цела")
        void tomlLike() {
            String base = "[general]\nenabled = true\nradius = 64\n\n[render]\nquality = 1";
            String local = "[general]\nenabled = true\nradius = 128\n\n[render]\nquality = 1";
            String remote = "[general]\nenabled = true\nradius = 64\nnewOption = 5\n\n[render]\nquality = 1";

            String merged = merge3(base, local, remote);
            assertTrue(merged.contains("radius = 128"), () -> merged);
            assertTrue(merged.contains("newOption = 5"), () -> merged);
        }
    }

    @Nested
    @DisplayName("двустороннее, без эталона")
    class TwoWay {
        private String merge2(String local, String remote) {
            return TextCodec.join(Diff3.merge2(lines(local), lines(remote)).lines);
        }

        @Test
        void identical() {
            assertEquals("a\nb", merge2("a\nb", "a\nb"));
        }

        @Test
        @DisplayName("дописанное сервером добавляется")
        void serverAddition() {
            assertEquals("a\nb\nc", merge2("a\nb", "a\nb\nc"));
        }

        @Test
        @DisplayName("дописанное игроком остаётся")
        void playerAddition() {
            assertEquals("a\nмоё\nb", merge2("a\nмоё\nb", "a\nb"));
        }

        @Test
        @DisplayName("расхождение в одном месте — локальное побеждает")
        void divergence() {
            Diff3.Result out = Diff3.merge2(lines("a\nX\nc"), lines("a\nY\nc"));
            assertEquals("a\nX\nc", TextCodec.join(out.lines));
            assertEquals(1, out.conflicts.size());
        }
    }

    @Nested
    @DisplayName("случайные входы: сторона, ничего не менявшая, не влияет на итог")
    class Invariants {
        /**
         * Два сильнейших свойства трёхстороннего слияния. Они ловят ошибки в согласовании
         * курсоров по трём последовательностям — то, что на подобранных примерах не видно.
         */
        @Test
        void unchangedSideDoesNotAffectResult() {
            java.util.Random random = new java.util.Random(20260803L);
            for (int round = 0; round < 500; round++) {
                List<String> base = randomLines(random, 12);
                List<String> edited = randomEdit(random, base);

                assertIterableEquals(edited, Diff3.merge3(base, base, edited).lines,
                        "игрок не менял — результат обязан совпасть с серверной версией");
                assertIterableEquals(edited, Diff3.merge3(base, edited, base).lines,
                        "сервер не менял — результат обязан совпасть с версией игрока");
                assertTrue(Diff3.merge3(base, base, edited).conflicts.isEmpty());
                assertTrue(Diff3.merge3(base, edited, base).conflicts.isEmpty());
            }
        }

        @Test
        @DisplayName("одинаковая правка с обеих сторон не создаёт конфликт")
        void identicalEditsNeverConflict() {
            java.util.Random random = new java.util.Random(7L);
            for (int round = 0; round < 200; round++) {
                List<String> base = randomLines(random, 10);
                List<String> edited = randomEdit(random, base);
                Diff3.Result out = Diff3.merge3(base, edited, edited);
                assertIterableEquals(edited, out.lines);
                assertTrue(out.conflicts.isEmpty());
            }
        }

        private List<String> randomLines(java.util.Random random, int max) {
            int count = random.nextInt(max);
            List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(String.valueOf((char) ('a' + random.nextInt(5))));
            }
            return out;
        }

        private List<String> randomEdit(java.util.Random random, List<String> source) {
            List<String> out = new java.util.ArrayList<>(source);
            int edits = random.nextInt(4);
            for (int i = 0; i < edits; i++) {
                int op = random.nextInt(3);
                if (op == 0 || out.isEmpty()) {
                    out.add(random.nextInt(out.size() + 1), "новая" + random.nextInt(3));
                } else if (op == 1) {
                    out.remove(random.nextInt(out.size()));
                } else {
                    out.set(random.nextInt(out.size()), "правка" + random.nextInt(3));
                }
            }
            return out;
        }
    }

    @Nested
    @DisplayName("маркеры конфликта никогда не пишутся в файл")
    class NoMarkers {
        @Test
        void noGitStyleMarkers() {
            String merged = merge3("a\nb\nc", "a\nX\nc", "a\nY\nc");
            assertTrue(merged.indexOf("<<<<<<<") < 0, () -> merged);
            assertTrue(merged.indexOf("=======") < 0, () -> merged);
            assertTrue(merged.indexOf(">>>>>>>") < 0, () -> merged);
        }
    }
}

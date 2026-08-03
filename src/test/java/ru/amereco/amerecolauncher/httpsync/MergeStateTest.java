package ru.amereco.amerecolauncher.httpsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeStateTest {
    @TempDir
    Path base;

    private MergeState state;

    @BeforeEach
    void setUp() {
        state = new MergeState(base.resolve(MergeState.DIR_NAME), "rpcraft");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("снимок пишется и читается")
    void baseRoundTrip() throws IOException {
        state.writeBase("config/a.json", utf8("{}"));
        assertArrayEquals(utf8("{}"), state.readBase("config/a.json"));
    }

    @Test
    @DisplayName("отсутствующий снимок — это null, а не исключение")
    void missingBaseIsNull() {
        assertNull(state.readBase("нет/такого"));
    }

    @Test
    void baseIsDeletable() throws IOException {
        state.writeBase("a", utf8("x"));
        state.deleteBase("a");
        assertNull(state.readBase("a"));
        // Повторное удаление не должно падать.
        state.deleteBase("a");
    }

    @Test
    @DisplayName("снимки разных профилей не пересекаются")
    void profilesAreIsolated() throws IOException {
        MergeState admin = new MergeState(base.resolve(MergeState.DIR_NAME), "rpcraft-admin");
        state.writeBase("mods/x.jar", utf8("базовый"));
        admin.writeBase("mods/x.jar", utf8("оверлей"));

        assertArrayEquals(utf8("базовый"), state.readBase("mods/x.jar"));
        assertArrayEquals(utf8("оверлей"), admin.readBase("mods/x.jar"));
    }

    @Test
    @DisplayName("состояние лежит внутри корня инстанса, в скрытом каталоге")
    void layout() throws IOException {
        state.writeBase("options.txt", utf8("x"));
        Path expected = base.resolve(MergeState.DIR_NAME).resolve("base")
                .resolve("rpcraft").resolve("options.txt");
        assertTrue(Files.isRegularFile(expected), () -> "нет файла " + expected);
        assertEquals(expected, state.baseOf("options.txt"));
    }

    @Test
    void stagingIsCleared() throws IOException {
        Path staging = state.stagingOf("mods/a.jar");
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, "мусор");

        state.clearStaging();
        assertFalse(Files.exists(staging));
        // Очистка пустого каталога тоже проходит молча.
        state.clearStaging();
    }

    @Test
    @DisplayName("purgeAll убирает и снимки, и временные файлы профиля")
    void purgeAll() throws IOException {
        state.writeBase("a", utf8("x"));
        Path staging = state.stagingOf("b");
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, "y");

        state.purgeAll();
        assertNull(state.readBase("a"));
        assertFalse(Files.exists(staging));
    }

    @Test
    @DisplayName("выход за пределы каталога состояния заблокирован")
    void traversalBlocked() {
        assertThrows(IOException.class, () -> state.baseOf("../../../etc/passwd"));
        assertThrows(IOException.class, () -> state.stagingOf("../вне"));
    }

    @Test
    @DisplayName("имя .new строится от пути и не может удвоиться")
    void conflictSibling() {
        Path target = base.resolve("config/a.json");
        assertEquals(base.resolve("config/a.json.new"), MergeState.conflictSibling(target));
        // Имя всегда берётся от целевого пути, поэтому .new.new не появится.
        assertEquals(base.resolve("config/a.json.new"),
                MergeState.conflictSibling(base.resolve("config/a.json")));
    }
}

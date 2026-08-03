package ru.amereco.amerecolauncher.httpsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.amereco.amerecolauncher.utils.Hashes;

/** Матрица решений по merge-файлу: что планировщик делает при каждом сочетании состояний. */
class SyncPlannerTest {
    private static final String PATH = "options.txt";

    @TempDir
    Path base;

    private Path configPath;
    private MergeState mergeState;
    private SyncPlanner planner;

    @BeforeEach
    void setUp() {
        configPath = base.resolve("rpcraft.json");
        mergeState = new MergeState(base.resolve(MergeState.DIR_NAME), "rpcraft");
        planner = new SyncPlanner(base, configPath, mergeState);
    }

    private static JSONObject entry(String path, String hash, String mode) {
        JSONObject file = new JSONObject().put("path", path).put("hash", hash).put("size", 1);
        if (mode != null) {
            file.put("mode", mode);
        }
        return file;
    }

    private static JSONObject index(JSONObject... files) {
        JSONArray array = new JSONArray();
        for (JSONObject file : files) {
            array.put(file);
        }
        return new JSONObject().put("files", array).put("totalHash", "t");
    }

    private String writeLocal(String content) throws IOException {
        Path target = base.resolve(PATH);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return Hashes.md5(content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBase(String content) throws IOException {
        mergeState.writeBase(PATH, content.getBytes(StandardCharsets.UTF_8));
    }

    private PlannedFile planOnly(JSONObject remote, JSONObject local) throws IOException {
        List<FileAction> actions =
                planner.actions(remote, local, SyncPlanner.Progress.silent());
        List<PlannedFile> planned = planner.plan(actions);
        assertEquals(1, planned.size(), "ожидалось ровно одно решение");
        return planned.get(0);
    }

    @Nested
    @DisplayName("сервер файл не менял")
    class Unchanged {
        @Test
        @DisplayName("снимок уже есть — делать нечего")
        void baseAlreadyPresent() throws IOException {
            String hash = writeLocal("fov:90");
            writeBase("fov:70");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "srv", "merge")),
                    index(entry(PATH, "srv", "merge")));
            assertEquals(PlannedFile.Plan.NOOP, planned.plan());
            assertTrue(hash.length() == 32);
        }

        @Test
        @DisplayName("снимка нет, файл не тронут — снимок берём с диска, без сети")
        void seedsBaseFromPristineLocal() throws IOException {
            String hash = writeLocal("fov:70");
            PlannedFile planned = planOnly(
                    index(entry(PATH, hash, "merge")),
                    index(entry(PATH, hash, "merge")));
            assertEquals(PlannedFile.Plan.SEED_BASE_FROM_LOCAL, planned.plan());
        }

        @Test
        @DisplayName("снимка нет, файл изменён — докачиваем эталон")
        void backfillsBaseForModifiedFile() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "srv", "merge")),
                    index(entry(PATH, "srv", "merge")));
            assertEquals(PlannedFile.Plan.BACKFILL_BASE, planned.plan());
        }

        @Test
        @DisplayName("в режиме замены неизменившийся файл вообще не попадает в план")
        void replaceModeProducesNoAction() throws IOException {
            writeLocal("fov:70");
            List<FileAction> actions = planner.actions(
                    index(entry(PATH, "srv", null)),
                    index(entry(PATH, "srv", null)),
                    SyncPlanner.Progress.silent());
            assertTrue(actions.isEmpty());
        }
    }

    @Nested
    @DisplayName("сервер файл обновил")
    class Updated {
        @Test
        @DisplayName("локального файла нет — обычная запись плюс снимок")
        void missingLocal() throws IOException {
            PlannedFile planned = planOnly(
                    index(entry(PATH, "new", "merge")),
                    index(entry(PATH, "old", "merge")));
            assertEquals(PlannedFile.Plan.WRITE_WITH_BASE, planned.plan());
        }

        @Test
        @DisplayName("игрок файл не трогал — полная замена, слияние не нужно")
        void pristineLocalIsReplaced() throws IOException {
            String hash = writeLocal("fov:70");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "new", "merge")),
                    index(entry(PATH, hash, "merge")));
            assertEquals(PlannedFile.Plan.WRITE_WITH_BASE, planned.plan());
        }

        @Test
        @DisplayName("на диске уже новая серверная версия — только фиксируем снимок")
        void localAlreadyMatchesRemote() throws IOException {
            String hash = writeLocal("fov:80");
            PlannedFile planned = planOnly(
                    index(entry(PATH, hash, "merge")),
                    index(entry(PATH, "old", "merge")));
            assertEquals(PlannedFile.Plan.SEED_BASE_FROM_LOCAL, planned.plan());
        }

        @Test
        @DisplayName("файл изменён игроком, снимок есть — трёхстороннее слияние")
        void modifiedWithBase() throws IOException {
            writeLocal("fov:90");
            writeBase("fov:70");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "new", "merge")),
                    index(entry(PATH, "old", "merge")));
            assertEquals(PlannedFile.Plan.STAGE_FOR_MERGE, planned.plan());
        }

        @Test
        @DisplayName("файл изменён игроком, снимка нет — всё равно сливаем, но двусторонне")
        void modifiedWithoutBase() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "new", "merge")),
                    index(entry(PATH, "old", "merge")));
            assertEquals(PlannedFile.Plan.STAGE_FOR_MERGE, planned.plan());
        }

        @Test
        @DisplayName("пути нет в индексе, но файл на диске есть — сливаем, а не затираем")
        void missingFromIndexButPresentOnDisk() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(index(entry(PATH, "new", "merge")), index());
            assertEquals(PlannedFile.Plan.STAGE_FOR_MERGE, planned.plan());
        }

        @Test
        @DisplayName("режим замены остаётся полной заменой")
        void replaceModeUnaffected() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(
                    index(entry(PATH, "new", null)),
                    index(entry(PATH, "old", null)));
            assertEquals(PlannedFile.Plan.WRITE_DIRECT, planned.plan());
        }
    }

    @Nested
    @DisplayName("сервер убрал файл")
    class Deleted {
        @Test
        @DisplayName("режим замены — удаляем как раньше")
        void replaceModeDeletes() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(index(), index(entry(PATH, "old", null)));
            assertEquals(PlannedFile.Plan.DELETE, planned.plan());
        }

        @Test
        @DisplayName("merge-файл не тронут игроком — удаляем")
        void mergeModeUnmodifiedDeletes() throws IOException {
            String hash = writeLocal("fov:70");
            PlannedFile planned = planOnly(index(), index(entry(PATH, hash, "merge")));
            assertEquals(PlannedFile.Plan.DELETE, planned.plan());
        }

        @Test
        @DisplayName("merge-файл изменён игроком — оставляем, это его данные")
        void mergeModeModifiedIsKept() throws IOException {
            writeLocal("fov:90");
            PlannedFile planned = planOnly(index(), index(entry(PATH, "old", "merge")));
            assertEquals(PlannedFile.Plan.KEEP_LOCAL, planned.plan());
        }
    }

    @Nested
    @DisplayName("чтение режима из манифеста")
    class ModeParsing {
        @Test
        void absentMeansReplace() {
            assertEquals(SyncMode.REPLACE, SyncMode.parse(null));
        }

        @Test
        void unknownValueMeansReplace() {
            assertEquals(SyncMode.REPLACE, SyncMode.parse("3-way"));
            assertEquals(SyncMode.REPLACE, SyncMode.parse(""));
            assertEquals(SyncMode.REPLACE, SyncMode.parse("replace"));
        }

        @Test
        void caseAndSpacesTolerated() {
            assertEquals(SyncMode.MERGE, SyncMode.parse(" MERGE "));
            assertEquals(SyncMode.MERGE, SyncMode.parse("Merge"));
        }

        @Test
        @DisplayName("число вместо строки не роняет синхронизацию")
        void numericModeDegradesToReplace() throws IOException {
            writeLocal("fov:90");
            JSONObject weird = new JSONObject().put("path", PATH).put("hash", "new").put("mode", 5);
            PlannedFile planned = planOnly(
                    new JSONObject().put("files", new JSONArray().put(weird)),
                    index(entry(PATH, "old", null)));
            assertEquals(PlannedFile.Plan.WRITE_DIRECT, planned.plan());
        }

        @Test
        @DisplayName("режим удалённого файла берётся из локального индекса")
        void deleteModeComesFromIndex() {
            List<FileAction> actions = planner.actions(
                    index(), index(entry(PATH, "old", "merge")), SyncPlanner.Progress.silent());
            assertEquals(SyncMode.MERGE, actions.get(0).mode());
            assertEquals(FileAction.Kind.DELETE, actions.get(0).kind());
        }
    }

    @Nested
    @DisplayName("защита путей")
    class PathSafety {
        @Test
        void traversalBlocked() {
            assertThrows(IOException.class, () -> planner.resolveSafe("../../evil"));
        }

        @Test
        @DisplayName("каталог состояния слияния недоступен манифесту")
        void stateDirBlocked() {
            assertThrows(IOException.class,
                    () -> planner.resolveSafe(MergeState.DIR_NAME + "/base/rpcraft/options.txt"));
        }

        @Test
        @DisplayName("локальный индекс нельзя перезаписать записью манифеста")
        void indexOverwriteBlocked() {
            assertThrows(IOException.class, () -> planner.resolveSafe("rpcraft.json"));
        }

        @Test
        @DisplayName("служебные суффиксы загрузчика зарезервированы")
        void reservedSuffixesBlocked() {
            assertThrows(IOException.class, () -> planner.resolveSafe("mods/a.jar.part"));
            assertThrows(IOException.class, () -> planner.resolveSafe("options.txt.new"));
        }

        @Test
        void ordinaryPathsPass() throws IOException {
            assertEquals(base.resolve("config/maplink/general.json5").toAbsolutePath().normalize(),
                    planner.resolveSafe("config/maplink/general.json5"));
        }
    }
}

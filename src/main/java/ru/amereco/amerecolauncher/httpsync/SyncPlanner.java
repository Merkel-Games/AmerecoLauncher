package ru.amereco.amerecolauncher.httpsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.amereco.amerecolauncher.utils.Hashes;

/**
 * Сравнение манифеста с локальным индексом и выбор действия по каждому файлу.
 *
 * <p>Вынесено из {@link HTTPSync} отдельным классом сознательно: здесь нет ни сети, ни пула
 * потоков, ни {@code Config}, поэтому все ветки решений проверяются обычными тестами на
 * временном каталоге.
 *
 * <p>Ключевое наблюдение, на котором держится миграция: хеш в локальном индексе — это MD5
 * последней <b>серверной</b> версии файла. Значит, посчитав MD5 файла на диске и сравнив с
 * индексом, можно точно сказать, трогал игрок файл или нет — даже когда эталонного снимка
 * ещё нет. Не тронутые файлы обновляются полной заменой, как раньше, и слияние им не нужно.
 */
final class SyncPlanner {
    /** Приёмник хода работ. Позволяет планировщику ничего не знать про UI. */
    interface Progress {
        void stage(String stage);

        void reset(int total);

        void step(String step);

        static Progress silent() {
            return new Progress() {
                @Override
                public void stage(String stage) {
                }

                @Override
                public void reset(int total) {
                }

                @Override
                public void step(String step) {
                }
            };
        }
    }

    private final Path basePath;
    private final Path configPath;
    private final MergeState mergeState;

    SyncPlanner(Path basePath, Path configPath, MergeState mergeState) {
        this.basePath = basePath;
        this.configPath = configPath;
        this.mergeState = mergeState;
    }

    List<FileAction> actions(JSONObject remoteConfig, JSONObject localConfig, Progress progress) {
        JSONArray remoteFiles = filesOf(remoteConfig);
        JSONArray localFiles = filesOf(localConfig);

        progress.stage("Вычисление разницы файлов: Пометка файлов");
        progress.reset(remoteFiles.length() + localFiles.length());

        Map<String, JSONObject> indexed = new LinkedHashMap<>();
        for (int i = 0; i < localFiles.length(); i++) {
            JSONObject file = localFiles.getJSONObject(i);
            String path = file.getString("path");
            progress.step(path);
            indexed.put(path, file);
        }

        progress.stage("Вычисление разницы файлов: Сравнение");
        List<FileAction> actions = new ArrayList<>();
        Map<String, Boolean> covered = new LinkedHashMap<>();

        for (int i = 0; i < remoteFiles.length(); i++) {
            JSONObject remoteFile = remoteFiles.getJSONObject(i);
            String path = remoteFile.getString("path");
            progress.step(path);
            covered.put(path, Boolean.TRUE);

            String remoteHash = remoteFile.getString("hash");
            long remoteSize = remoteFile.optLong("size", -1);
            SyncMode mode = SyncMode.parse(remoteFile.optString("mode", null));

            JSONObject localFile = indexed.get(path);
            if (localFile == null) {
                actions.add(new FileAction(path, FileAction.Kind.CREATE, mode, remoteHash, remoteSize, null));
                continue;
            }

            String indexHash = localFile.optString("hash", null);
            if (!remoteHash.equals(indexHash)) {
                actions.add(new FileAction(path, FileAction.Kind.UPDATE, mode, remoteHash, remoteSize, indexHash));
            } else if (mode == SyncMode.MERGE) {
                // Обновлять нечего, но эталонного снимка может не быть — решит plan().
                actions.add(new FileAction(path, FileAction.Kind.UNCHANGED, mode, remoteHash, remoteSize, indexHash));
            }
        }

        for (Map.Entry<String, JSONObject> entry : indexed.entrySet()) {
            if (covered.containsKey(entry.getKey())) {
                continue;
            }
            JSONObject file = entry.getValue();
            // Режим удалённого файла известен только из индекса — это копия прошлого манифеста.
            SyncMode mode = SyncMode.parse(file.optString("mode", null));
            actions.add(new FileAction(entry.getKey(), FileAction.Kind.DELETE, mode,
                    null, -1, file.optString("hash", null)));
        }

        progress.stage("Сравнение окончено");
        return actions;
    }

    List<PlannedFile> plan(List<FileAction> actions) throws IOException {
        List<PlannedFile> planned = new ArrayList<>(actions.size());
        for (FileAction action : actions) {
            planned.add(planOne(action));
        }
        return planned;
    }

    private PlannedFile planOne(FileAction action) throws IOException {
        Path target = resolveSafe(action.path());
        Path staging = mergeState.stagingOf(action.path());

        if (action.kind() == FileAction.Kind.DELETE) {
            boolean keep = action.mode() == SyncMode.MERGE
                    && isLocallyModified(target, action.indexHash());
            return new PlannedFile(action,
                    keep ? PlannedFile.Plan.KEEP_LOCAL : PlannedFile.Plan.DELETE, target, staging);
        }

        if (action.mode() != SyncMode.MERGE) {
            PlannedFile.Plan plan = action.kind() == FileAction.Kind.UNCHANGED
                    ? PlannedFile.Plan.NOOP
                    : PlannedFile.Plan.WRITE_DIRECT;
            return new PlannedFile(action, plan, target, staging);
        }

        String localHash = Hashes.md5OrNull(target);

        if (action.kind() == FileAction.Kind.UNCHANGED) {
            if (Files.isRegularFile(mergeState.baseOf(action.path()))) {
                return new PlannedFile(action, PlannedFile.Plan.NOOP, target, staging);
            }
            if (localHash != null && localHash.equalsIgnoreCase(action.indexHash())) {
                // Файл на диске и есть последняя серверная версия — снимок берём с него.
                return new PlannedFile(action, PlannedFile.Plan.SEED_BASE_FROM_LOCAL, target, staging);
            }
            // Файл изменён, снимка нет, но серверная версия равна последней синхронизированной —
            // значит её можно скачать и сделать эталоном.
            return new PlannedFile(action, PlannedFile.Plan.BACKFILL_BASE, target, staging);
        }

        if (localHash == null) {
            // Файла нет (не скачан или удалён игроком) — обычная запись плюс снимок.
            return new PlannedFile(action, PlannedFile.Plan.WRITE_WITH_BASE, target, staging);
        }
        if (localHash.equalsIgnoreCase(action.remoteHash())) {
            // На диске уже ровно то, что на сервере: остаётся зафиксировать снимок.
            return new PlannedFile(action, PlannedFile.Plan.SEED_BASE_FROM_LOCAL, target, staging);
        }
        if (localHash.equalsIgnoreCase(action.indexHash())) {
            // Игрок файл не трогал — полная замена, как и раньше. Слияние не требуется,
            // и эталонный снимок для неё тоже не нужен: это путь миграции старых установок.
            return new PlannedFile(action, PlannedFile.Plan.WRITE_WITH_BASE, target, staging);
        }
        // Файл изменён игроком: трёхстороннее слияние, если снимок есть, иначе двустороннее.
        return new PlannedFile(action, PlannedFile.Plan.STAGE_FOR_MERGE, target, staging);
    }

    /**
     * Резолвит путь из манифеста, не давая ему выйти за корень инстанса и задеть служебные файлы.
     *
     * <p>Помимо {@code ../} закрыты: каталог состояния слияния, сам локальный индекс и суффиксы
     * {@code .part} и {@code .new} — временные имена загрузчика и файлы с серверной версией
     * при конфликте.
     */
    Path resolveSafe(String relative) throws IOException {
        Path root = basePath.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("path traversal blocked: " + relative);
        }
        if (target.startsWith(root.resolve(MergeState.DIR_NAME))) {
            throw new IOException("reserved path blocked: " + relative);
        }
        if (configPath != null && target.equals(configPath.toAbsolutePath().normalize())) {
            throw new IOException("index overwrite blocked: " + relative);
        }
        if (relative.endsWith(".part") || relative.endsWith(".new")) {
            throw new IOException("reserved suffix blocked: " + relative);
        }
        return target;
    }

    /** Отличается ли файл на диске от версии, записанной в индексе. */
    static boolean isLocallyModified(Path target, String indexHash) {
        if (indexHash == null || indexHash.isEmpty()) {
            return false;
        }
        String localHash = Hashes.md5OrNull(target);
        return localHash != null && !localHash.equalsIgnoreCase(indexHash);
    }

    static JSONArray filesOf(JSONObject config) {
        JSONArray files = config.optJSONArray("files");
        return files != null ? files : new JSONArray();
    }
}

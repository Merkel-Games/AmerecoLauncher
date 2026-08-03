package ru.amereco.amerecolauncher.httpsync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Трёхстороннее слияние JSON по ключам.
 *
 * <p>Порядок ключей берётся у локального файла — сначала идут его собственные ключи в исходном
 * порядке, затем дописываются добавленные на сервере. Так файл игрока не перетасовывается.
 *
 * <p><b>Массивы считаются неделимыми значениями.</b> Поэлементное слияние массивов разной длины
 * даёт мусор, который мод не прочитает, а объединение воскресило бы ровно то, что игрок удалил:
 * в {@code config/maplink/general.json5} поля {@code markers} и {@code areaMarkers} — чёрные
 * списки, и пустота в них осмысленна. Неделимое сравнение предсказуемо: сервер обновил список,
 * игрок его не трогал — игрок получит обновление; трогали оба — останется локальный вариант,
 * а серверный ляжет рядом в {@code .new}.
 */
final class JsonStructuralMerger implements TextMerger {
    private static final String ROOT = "<корень>";

    @Override
    public TextMergeOutcome merge(String base, String local, String remote) {
        JsonElement baseTree = base == null ? null : JsonParser.parseString(base);
        JsonElement localTree = JsonParser.parseString(local);
        JsonElement remoteTree = JsonParser.parseString(remote);

        Stats stats = new Stats();
        JsonElement merged = mergeElement(baseTree, localTree, remoteTree, "", stats);

        String text = JsonText.write(merged, JsonText.detectIndent(local));
        return new TextMergeOutcome(text, stats.conflicts, stats.applied);
    }

    private JsonElement mergeElement(JsonElement base, JsonElement local, JsonElement remote,
                                     String path, Stats stats) {
        if (local != null && remote != null && local.isJsonObject() && remote.isJsonObject()) {
            return mergeObject(base, local.getAsJsonObject(), remote.getAsJsonObject(), path, stats);
        }
        return pickValue(base, local, remote, path.isEmpty() ? ROOT : path, stats);
    }

    private JsonObject mergeObject(JsonElement base, JsonObject local, JsonObject remote,
                                   String path, Stats stats) {
        JsonObject baseObject = (base != null && base.isJsonObject()) ? base.getAsJsonObject() : null;
        JsonObject out = new JsonObject();

        for (Map.Entry<String, JsonElement> entry : local.entrySet()) {
            String key = entry.getKey();
            JsonElement localValue = entry.getValue();
            boolean inBase = baseObject != null && baseObject.has(key);
            JsonElement baseValue = inBase ? baseObject.get(key) : null;
            String childPath = path.isEmpty() ? key : path + "." + key;

            if (!remote.has(key)) {
                if (inBase && JsonValues.equal(localValue, baseValue)) {
                    // Сервер убрал ключ, игрок его не трогал — убираем и у себя.
                    stats.applied++;
                } else {
                    // Ключ игрока либо им же изменён: оставляем.
                    out.add(key, localValue);
                }
                continue;
            }

            JsonElement remoteValue = remote.get(key);
            if (localValue.isJsonObject() && remoteValue.isJsonObject()) {
                out.add(key, mergeObject(baseValue, localValue.getAsJsonObject(),
                        remoteValue.getAsJsonObject(), childPath, stats));
            } else {
                out.add(key, pickValue(baseValue, localValue, remoteValue, childPath, stats));
            }
        }

        for (Map.Entry<String, JsonElement> entry : remote.entrySet()) {
            String key = entry.getKey();
            if (local.has(key)) {
                continue;
            }
            if (baseObject != null && baseObject.has(key)) {
                // Ключ был в эталоне, но локально отсутствует — игрок удалил его намеренно.
                continue;
            }
            out.add(key, entry.getValue());
            stats.applied++;
        }

        return out;
    }

    /** Правило для неделимого значения: массива, скаляра или пары объект↔не-объект. */
    private JsonElement pickValue(JsonElement base, JsonElement local, JsonElement remote,
                                  String path, Stats stats) {
        if (JsonValues.equal(local, remote)) {
            return local;
        }
        if (base != null) {
            if (JsonValues.equal(local, base)) {
                // Менял только сервер.
                stats.applied++;
                return remote;
            }
            if (JsonValues.equal(remote, base)) {
                // Менял только игрок.
                return local;
            }
        }
        stats.conflicts.add(path);
        return local;
    }

    private static final class Stats {
        final List<String> conflicts = new ArrayList<>();
        int applied;
    }
}

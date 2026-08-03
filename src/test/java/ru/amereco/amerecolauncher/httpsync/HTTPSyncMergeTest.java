package ru.amereco.amerecolauncher.httpsync;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.amereco.amerecolauncher.utils.Hashes;

/**
 * Синхронизация целиком: настоящий HTTP, настоящий пул загрузок, настоящая файловая система.
 *
 * <p>Проверяет то, ради чего фича и делалась: правки игрока в конфиге переживают обновление
 * на сервере.
 */
class HTTPSyncMergeTest {
    private static final String PROFILE = "rpcraft";
    private static final String OPTIONS = "options.txt";
    private static final String MOD = "mods/a.jar";

    @TempDir
    Path instance;

    private WireMockServer server;
    private String baseUrl;
    private String configUrl;
    private Path configPath;
    private MergeState mergeState;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        baseUrl = "http://localhost:" + server.port() + "/instances/" + PROFILE + "/";
        configUrl = baseUrl + PROFILE + ".json";
        configPath = instance.resolve(PROFILE + ".json");
        mergeState = new MergeState(instance.resolve(MergeState.DIR_NAME), PROFILE);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    /** Публикует манифест и содержимое файлов; хеши считаются из самого содержимого. */
    private void publish(String version, Map<String, String> files, Map<String, String> modes) {
        JSONArray entries = new JSONArray();
        files.forEach((path, content) -> {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            JSONObject entry = new JSONObject()
                    .put("path", path)
                    .put("hash", Hashes.md5(bytes))
                    .put("size", bytes.length);
            String mode = modes.get(path);
            if (mode != null) {
                entry.put("mode", mode);
            }
            entries.put(entry);

            server.stubFor(get(urlEqualTo("/instances/" + PROFILE + "/" + path))
                    .willReturn(aResponse().withStatus(200).withBody(bytes)));
        });

        JSONObject manifest = new JSONObject()
                .put("pathRules", new JSONObject().put("bootstrap", new JSONArray().put("")))
                .put("files", entries)
                .put("totalHash", version);

        server.stubFor(get(urlEqualTo("/instances/" + PROFILE + "/" + PROFILE + ".json"))
                .willReturn(aResponse().withStatus(200).withBody(manifest.toString())));
    }

    private void sync() throws IOException, InterruptedException {
        new HTTPSync(configUrl, baseUrl, configPath, instance, 5000, 1000, PROFILE).synchronize();
    }

    private String read(String relative) throws IOException {
        return Files.readString(instance.resolve(relative));
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("правка игрока переживает серверное обновление конфига")
    void playerEditSurvivesServerUpdate() throws Exception {
        publish("v1",
                map(OPTIONS, "fov:70\nguiScale:2\nrenderDistance:12\n", MOD, "jar-v1"),
                map(OPTIONS, "merge"));
        sync();

        assertEquals("fov:70\nguiScale:2\nrenderDistance:12\n", read(OPTIONS));
        assertEquals("jar-v1", read(MOD));
        assertEquals("fov:70\nguiScale:2\nrenderDistance:12\n",
                new String(mergeState.readBase(OPTIONS), StandardCharsets.UTF_8),
                "после первой синхронизации должен появиться эталонный снимок");

        // Игрок правит своё поле и дописывает собственную строку.
        Files.writeString(instance.resolve(OPTIONS),
                "fov:90\nguiScale:2\nrenderDistance:12\nmyOwnKey:мое\n");

        // Сервер меняет другое поле и добавляет новое.
        publish("v2",
                map(OPTIONS, "fov:70\nguiScale:3\nrenderDistance:12\nnewOption:true\n", MOD, "jar-v1"),
                map(OPTIONS, "merge"));
        sync();

        String merged = read(OPTIONS);
        assertTrue(merged.contains("fov:90"), () -> "правка игрока стёрта:\n" + merged);
        assertTrue(merged.contains("myOwnKey:мое"), () -> "своя строка игрока стёрта:\n" + merged);
        assertTrue(merged.contains("guiScale:3"), () -> "серверное изменение не применено:\n" + merged);
        assertTrue(merged.contains("newOption:true"), () -> "новый серверный ключ не добавлен:\n" + merged);
        assertFalse(Files.exists(instance.resolve(OPTIONS + ".new")), "конфликта не было, .new не нужен");

        assertEquals("fov:70\nguiScale:3\nrenderDistance:12\nnewOption:true\n",
                new String(mergeState.readBase(OPTIONS), StandardCharsets.UTF_8),
                "эталон обязан обновиться до новой серверной версии");
    }

    @Test
    @DisplayName("конфликт: побеждает локальное, серверная версия ложится рядом в .new")
    void conflictKeepsLocalAndWritesSibling() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();

        Files.writeString(instance.resolve(OPTIONS), "fov:90\n");
        publish("v2", map(OPTIONS, "fov:100\n"), map(OPTIONS, "merge"));
        sync();

        assertEquals("fov:90\n", read(OPTIONS), "должна была остаться версия игрока");
        assertEquals("fov:100\n", read(OPTIONS + ".new"), "серверная версия должна лежать рядом");
    }

    @Test
    @DisplayName("файл в режиме замены обновляется полностью, как раньше")
    void replaceModeStillOverwrites() throws Exception {
        publish("v1", map(MOD, "jar-v1"), map());
        sync();

        Files.writeString(instance.resolve(MOD), "локальная поделка");
        publish("v2", map(MOD, "jar-v2"), map());
        sync();

        assertEquals("jar-v2", read(MOD));
    }

    @Test
    @DisplayName("миграция: файл под слиянием, изменённый до появления снимков, не теряет правок")
    void migrationWithoutBaseSnapshot() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();

        // Имитируем установку со старой версией лаунчера: снимка нет, файл изменён.
        mergeState.purgeAll();
        Files.writeString(instance.resolve(OPTIONS), "fov:90\nmyOwnKey:1\n");

        publish("v2", map(OPTIONS, "fov:70\nnewOption:true\n"), map(OPTIONS, "merge"));
        sync();

        String merged = read(OPTIONS);
        assertTrue(merged.contains("fov:90"), () -> merged);
        assertTrue(merged.contains("myOwnKey:1"), () -> merged);
        assertTrue(merged.contains("newOption:true"), () -> merged);
    }

    @Test
    @DisplayName("не тронутый игроком merge-файл просто заменяется")
    void pristineMergeFileIsReplaced() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();

        publish("v2", map(OPTIONS, "fov:80\nnewOption:true\n"), map(OPTIONS, "merge"));
        sync();

        assertEquals("fov:80\nnewOption:true\n", read(OPTIONS));
    }

    @Test
    @DisplayName("вложенный json: серверные новинки применяются, значения игрока целы")
    void nestedJsonMerge() throws Exception {
        String path = "config/mod/settings.json";
        String v1 = "{\n  \"a\": 1,\n  \"nested\": {\n    \"x\": 10,\n    \"y\": 20\n  }\n}";
        server.stubFor(get(urlEqualTo("/instances/" + PROFILE + "/config/mod/settings.json"))
                .willReturn(aResponse().withStatus(200).withBody(v1)));
        publish("v1", map(path, v1), map(path, "merge"));
        sync();

        Files.writeString(instance.resolve(path),
                "{\n  \"a\": 1,\n  \"nested\": {\n    \"x\": 99,\n    \"y\": 20\n  }\n}");

        String v2 = "{\n  \"a\": 2,\n  \"nested\": {\n    \"x\": 10,\n    \"y\": 20,\n    \"z\": 30\n  }\n}";
        publish("v2", map(path, v2), map(path, "merge"));
        sync();

        String merged = read(path);
        assertTrue(merged.contains("\"x\": 99"), () -> "правка игрока стёрта:\n" + merged);
        assertTrue(merged.contains("\"a\": 2"), () -> "серверное изменение не применено:\n" + merged);
        assertTrue(merged.contains("\"z\": 30"), () -> "новый вложенный ключ не добавлен:\n" + merged);
        assertTrue(merged.startsWith("{\n  \"a\""), () -> "отступ локального файла не сохранён:\n" + merged);
    }

    @Test
    @DisplayName("двоичный файл, ошибочно помеченный merge, обновляется полной заменой")
    void binaryMarkedMergeFallsBackToReplace() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();

        Files.write(instance.resolve(OPTIONS), new byte[]{0x0A, 0, 0, 9});
        publish("v2", map(OPTIONS, "fov:80\n"), map(OPTIONS, "merge"));
        sync();

        assertEquals("fov:80\n", read(OPTIONS));
    }

    @Test
    @DisplayName("сервер убрал merge-файл: не тронутый удаляется, изменённый остаётся")
    void removalRespectsPlayerEdits() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n", MOD, "jar-v1"), map(OPTIONS, "merge"));
        sync();

        publish("v2", map(MOD, "jar-v1"), map());
        sync();
        assertFalse(Files.exists(instance.resolve(OPTIONS)), "не тронутый файл должен был удалиться");

        publish("v3", map(OPTIONS, "fov:70\n", MOD, "jar-v1"), map(OPTIONS, "merge"));
        sync();
        Files.writeString(instance.resolve(OPTIONS), "fov:90\n");

        publish("v4", map(MOD, "jar-v1"), map());
        sync();
        assertEquals("fov:90\n", read(OPTIONS), "изменённый игроком файл удалять нельзя");
    }

    @Test
    @DisplayName("повторная синхронизация без изменений на сервере ничего не трогает")
    void repeatedSyncIsIdempotent() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();
        Files.writeString(instance.resolve(OPTIONS), "fov:90\nmyOwnKey:1\n");

        sync();
        sync();

        assertEquals("fov:90\nmyOwnKey:1\n", read(OPTIONS));
        assertFalse(Files.exists(instance.resolve(OPTIONS + ".new")));
    }

    @Test
    @DisplayName("deleteDownloaded убирает скачанное, но не трогает миры игрока")
    void deleteDownloadedRemovesOnlyDownloadedFiles() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n", MOD, "jar-v1"), map(OPTIONS, "merge"));
        sync();

        // Конфликт, чтобы рядом появился .new — он тоже наш артефакт.
        Files.writeString(instance.resolve(OPTIONS), "fov:90\n");
        publish("v2", map(OPTIONS, "fov:100\n", MOD, "jar-v1"), map(OPTIONS, "merge"));
        sync();
        assertTrue(Files.exists(instance.resolve(OPTIONS + ".new")));

        // То, что лаунчер никогда не качал.
        Path save = instance.resolve("saves/мир/level.dat");
        Files.createDirectories(save.getParent());
        Files.writeString(save, "мир игрока");

        new HTTPSync(configUrl, baseUrl, configPath, instance, 5000, 1000, PROFILE)
                .deleteDownloaded();

        assertFalse(Files.exists(instance.resolve(OPTIONS)), "скачанный конфиг должен исчезнуть");
        assertFalse(Files.exists(instance.resolve(MOD)), "скачанный мод должен исчезнуть");
        assertFalse(Files.exists(instance.resolve(OPTIONS + ".new")), "серверная версия рядом — тоже наш файл");
        assertFalse(Files.exists(configPath), "индекс профиля должен исчезнуть");
        assertFalse(Files.exists(instance.resolve(MergeState.DIR_NAME).resolve("base").resolve(PROFILE)),
                "эталонные снимки должны исчезнуть");

        assertTrue(Files.exists(save), "мир игрока трогать нельзя");
        assertEquals("мир игрока", Files.readString(save));
    }

    @Test
    @DisplayName("без индекса удалять нечего — вслепую каталог инстанса не сносится")
    void deleteDownloadedWithoutIndexKeepsEverything() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();
        Files.delete(configPath);

        new HTTPSync(configUrl, baseUrl, configPath, instance, 5000, 1000, PROFILE)
                .deleteDownloaded();

        assertTrue(Files.exists(instance.resolve(OPTIONS)),
                "без индекса лаунчер не знает, что качал, и ничего не удаляет");
    }

    @Test
    @DisplayName("временный каталог загрузок не остаётся после синхронизации")
    void stagingIsCleanedUp() throws Exception {
        publish("v1", map(OPTIONS, "fov:70\n"), map(OPTIONS, "merge"));
        sync();
        Files.writeString(instance.resolve(OPTIONS), "fov:90\n");
        publish("v2", map(OPTIONS, "fov:70\nnew:1\n"), map(OPTIONS, "merge"));
        sync();

        // Профиль убирает за собой свой подкаталог; общий incoming/ остаётся — его делят
        // все профили, которые синхронизируются последовательно.
        assertFalse(Files.exists(instance.resolve(MergeState.DIR_NAME)
                        .resolve("incoming").resolve(PROFILE)),
                "временный каталог профиля должен быть убран");
    }
}

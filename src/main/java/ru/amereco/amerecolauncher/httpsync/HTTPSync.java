package ru.amereco.amerecolauncher.httpsync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;
import ru.amereco.amerecolauncher.Log;
import ru.amereco.amerecolauncher.httpsync.merge.MergeEngine;
import ru.amereco.amerecolauncher.httpsync.merge.MergeInput;
import ru.amereco.amerecolauncher.httpsync.merge.MergeResult;
import ru.amereco.amerecolauncher.utils.AtomicFiles;
import ru.amereco.amerecolauncher.utils.Downloader;
import ru.amereco.amerecolauncher.utils.Hashes;

public class HTTPSync extends Downloader {
    private final String configURL;
    private final String baseURL;
    private final Path configPath;
    private final Path basePath;
    private final int timeout;
    private final int retryDelay;

    private final MergeState mergeState;
    private final SyncPlanner planner;
    private final HttpClient httpClient;

    public HTTPSync(String configURL, String baseURL, Path configPath, Path basePath,
                   int timeout, int retryDelay) {
        this(configURL, baseURL, configPath, basePath, timeout, retryDelay,
                profileNameOf(configPath));
    }

    public HTTPSync(String configURL, String baseURL, Path configPath, Path basePath,
                   int timeout, int retryDelay, String profileName) {
        this.configURL = configURL;
        this.baseURL = baseURL;
        this.configPath = configPath;
        this.basePath = basePath;
        this.timeout = timeout;
        this.retryDelay = retryDelay;

        this.mergeState = new MergeState(basePath.resolve(MergeState.DIR_NAME), profileName);
        this.planner = new SyncPlanner(basePath, configPath, mergeState);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(timeout))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** Имя профиля — это имя файла индекса без {@code .json}. */
    private static String profileNameOf(Path configPath) {
        String name = configPath.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
    }

    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        updateStage("Проверка обновлений...");

        JSONObject remoteConfig = getConfig(configURL);
        JSONObject localConfig = getLocalConfig(configPath);

        String remoteHash = remoteConfig.optString("totalHash", "");
        String localHash = localConfig.optString("totalHash", "");
        boolean hasUpdates = !remoteHash.equals(localHash);

        updateStage("Обновления проверены: " +
            (hasUpdates ? "Есть обновления" : "Нет обновлений"));

        return hasUpdates;
    }

    @Override
    public boolean checkUpdates(String versionId) throws IOException, InterruptedException {
        return checkUpdates();
    }

    private JSONObject getLocalConfig(Path path) throws IOException {
        if (Files.exists(path)) {
            String content = Files.readString(path);
            return new JSONObject(content);
        } else {
            JSONObject emptyConfig = new JSONObject();
            emptyConfig.put("files", new JSONArray());
            emptyConfig.put("totalHash", "");
            return emptyConfig;
        }
    }

    private JSONObject getConfig(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Config server returned HTTP " + response.statusCode() + " for " + url);
        }

        return new JSONObject(response.body());
    }

    @Override
    public void download() throws IOException, InterruptedException {
        synchronize();
    }

    @Override
    public void download(String versionId) throws IOException, InterruptedException {
        download();
    }

    public void synchronize() throws IOException, InterruptedException {
        updateStage("Загрузка обновлений...");
        setFailDownloadHandler(((t) -> {
            try {
                t.printStackTrace();
                updateStage("Ошибка скачивания HTTPSync");
                updateStep(t.getMessage());
                // Индекс сбрасывается, чтобы следующий запуск начал заново. Эталонные снимки
                // при этом намеренно остаются: они переживают сбой и не дают следующему
                // слиянию скатиться до двустороннего.
                Files.deleteIfExists(configPath);
            } catch (Exception exc) {
            }
        }));

        mergeState.clearStaging();
        List<PlannedFile> plan = planner.plan(getFileActions());
        try {
            performFileActions(plan);
            waitUntilDownload();
            performMerges(plan);
            updateConfig();
        } finally {
            mergeState.clearStaging();
        }
        waitUntilDownload();
        updateStage("Обновления загружены");
    }

    private List<FileAction> getFileActions() throws IOException, InterruptedException {
        updateStage("Загрузка конфигов в память");

        JSONObject remoteConfig = getConfig(configURL);
        JSONObject localConfig = getLocalConfig(configPath);

        return planner.actions(remoteConfig, localConfig, new SyncPlanner.Progress() {
            @Override
            public void stage(String stage) {
                updateStage(stage);
            }

            @Override
            public void reset(int total) {
                resetProgress(total);
            }

            @Override
            public void step(String step) {
                updateStepAndIncProgress(step);
            }
        });
    }

    private void performFileActions(List<PlannedFile> plan) throws IOException, InterruptedException {
        updateStage("Синхронизация");
        resetProgress(plan.size());

        for (PlannedFile planned : plan) {
            String path = planned.action().path();
            updateStep(path);

            switch (planned.plan()) {
                case WRITE_DIRECT:
                case WRITE_WITH_BASE:
                    // Прогресс по таким файлам тикает пул после завершения загрузки.
                    downloadFile(remoteUrlOf(path), ensureParent(planned.target()));
                    break;

                case STAGE_FOR_MERGE:
                case BACKFILL_BASE:
                    downloadFile(remoteUrlOf(path), ensureParent(planned.staging()));
                    break;

                case SEED_BASE_FROM_LOCAL:
                    mergeState.writeBase(path, Files.readAllBytes(planned.target()));
                    updateStepAndIncProgress(path);
                    break;

                case DELETE:
                    Files.deleteIfExists(planned.target());
                    mergeState.deleteBase(path);
                    Files.deleteIfExists(MergeState.conflictSibling(planned.target()));
                    updateStepAndIncProgress(path);
                    break;

                case KEEP_LOCAL:
                    mergeState.deleteBase(path);
                    Log.info("Слияние: " + path
                            + " — изменён вами, файл оставлен, хотя убран с сервера");
                    updateStepAndIncProgress("Оставлен изменённый файл: " + path);
                    break;

                case NOOP:
                default:
                    updateStepAndIncProgress(path);
                    break;
            }
        }
    }

    /**
     * Слияние. Выполняется на вызывающем потоке уже после того, как все загрузки завершились,
     * и до обновления индекса: сбой на этом шаге оставляет индекс прежним, и следующий запуск
     * повторит синхронизацию.
     */
    private void performMerges(List<PlannedFile> plan) throws IOException, InterruptedException {
        List<PlannedFile> pending = new ArrayList<>();
        for (PlannedFile planned : plan) {
            if (planned.plan() == PlannedFile.Plan.STAGE_FOR_MERGE
                    || planned.plan() == PlannedFile.Plan.WRITE_WITH_BASE
                    || planned.plan() == PlannedFile.Plan.BACKFILL_BASE) {
                pending.add(planned);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        updateStage("Объединение изменений...");
        resetProgress(pending.size());

        for (PlannedFile planned : pending) {
            String path = planned.action().path();
            updateStep(path);
            try {
                switch (planned.plan()) {
                    case WRITE_WITH_BASE:
                        // Целевой файл только что заменён серверным — с него и снимаем эталон.
                        mergeState.writeBase(path, Files.readAllBytes(planned.target()));
                        break;
                    case BACKFILL_BASE:
                        mergeState.writeBase(path, Files.readAllBytes(planned.staging()));
                        break;
                    default:
                        mergeOne(planned);
                        break;
                }
            } catch (IOException | RuntimeException failure) {
                keepLocalAfterFailure(planned, failure);
            }
            updateStepAndIncProgress(path);
        }
    }

    private void mergeOne(PlannedFile planned) throws IOException {
        String path = planned.action().path();
        byte[] remote = Files.readAllBytes(planned.staging());

        // Первая в проекте проверка целостности загрузки. Здесь она дешёвая: сливаемые файлы
        // измеряются килобайтами, а слить битый файл в конфиг игрока — необратимо.
        String actual = Hashes.md5(remote);
        if (!actual.equalsIgnoreCase(planned.action().remoteHash())) {
            throw new IOException("MD5 mismatch for " + path
                    + ": expected " + planned.action().remoteHash() + ", got " + actual);
        }

        byte[] local = Files.readAllBytes(planned.target());
        byte[] base = mergeState.readBase(path);

        MergeResult result = MergeEngine.merge(new MergeInput(path, base, local, remote));

        if (!result.mergeable()) {
            // Пометка merge на двоичном файле — ошибка на сервере. Заморозить локальную копию
            // навсегда хуже, чем вернуться к сегодняшнему поведению: полной замене.
            Log.error("Слияние: " + path + " — не поддаётся объединению ("
                    + result.reason() + "), применена полная замена", null);
            updateStep("Полная замена: " + path);
            AtomicFiles.write(planned.target(), remote);
        } else {
            if (result.changed()) {
                AtomicFiles.write(planned.target(), result.content());
            }
            if (result.hasConflicts()) {
                if (!Arrays.equals(result.content(), remote)) {
                    AtomicFiles.write(MergeState.conflictSibling(planned.target()), remote);
                }
                updateStep("Конфликт в " + path
                        + " — сохранена ваша версия, серверная рядом (.new)");
                Log.info("Слияние: " + path + " — конфликтов: " + result.conflicts().size()
                        + " (ваши значения сохранены), серверная версия: " + path + ".new; "
                        + String.join(", ", result.conflicts()));
            } else {
                Log.info("Слияние: " + path + " — "
                        + (result.threeWay() ? "трёхстороннее" : "двустороннее (эталон отсутствует)")
                        + ", перенесено изменений: " + result.applied() + ", конфликтов нет");
            }
        }

        mergeState.writeBase(path, remote);
    }

    /**
     * Локальный файл не читается или разбор сорвался. Ничего не трогаем, серверную версию
     * кладём рядом и фиксируем её эталоном — иначе попытка повторялась бы при каждом запуске.
     */
    private void keepLocalAfterFailure(PlannedFile planned, Exception failure) {
        String path = planned.action().path();
        Log.error("Слияние: " + path + " — не удалось ("
                + failure.getMessage() + "), локальный файл оставлен без изменений", failure);
        updateStep("Не удалось объединить " + path + " — файл оставлен как есть");
        try {
            if (Files.isRegularFile(planned.staging())) {
                byte[] remote = Files.readAllBytes(planned.staging());
                AtomicFiles.write(MergeState.conflictSibling(planned.target()), remote);
                mergeState.writeBase(path, remote);
            }
        } catch (IOException unrecoverable) {
            Log.error("Слияние: " + path + " — не удалось сохранить серверную версию рядом",
                    unrecoverable);
        }
    }

    private String remoteUrlOf(String path) {
        return baseURL + URLEncoderChromium.encode(path);
    }

    private static Path ensureParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        return target;
    }

    private void downloadFile(String url, Path outputPath) {
        downloadToPathInThread(URI.create(url), outputPath);
    }

    private void updateConfig() throws IOException {
        Path configDir = configPath.getParent();
        if (configDir != null && !Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        downloadFile(configURL, configPath);
    }

    /**
     * Удаляет файлы отключённого профиля по локальному индексу вместе с состоянием слияния.
     *
     * <p>Файлы в режиме {@link SyncMode#MERGE}, которые игрок успел изменить, остаются на диске:
     * после правки это его данные — набор клавиш, настройки миникарты, — и стирать их при
     * отключении оверлея значит ровно ту потерю, ради предотвращения которой всё и затевалось.
     */
    public void purge() throws IOException, InterruptedException {
        updateStage("Очистка файлов отключённого профиля...");
        JSONObject localConfig = getLocalConfig(configPath);
        JSONArray files = SyncPlanner.filesOf(localConfig);
        int total = files.length();
        resetProgress(total);
        for (int i = 0; i < total; i++) {
            JSONObject file = files.getJSONObject(i);
            String path = file.getString("path");
            updateStepAndIncProgress(path);

            Path target = planner.resolveSafe(path);
            SyncMode mode = SyncMode.parse(file.optString("mode", null));
            if (mode == SyncMode.MERGE
                    && SyncPlanner.isLocallyModified(target, file.optString("hash", null))) {
                Log.info("Слияние: " + path
                        + " — изменён вами, файл оставлен при отключении профиля");
                continue;
            }
            Files.deleteIfExists(target);
        }
        mergeState.purgeAll();
        Files.deleteIfExists(configPath);
        updateStage("Файлы профиля удалены");
    }

    /**
     * Файлы этого профиля по локальному индексу: сами файлы, серверные версии,
     * оставшиеся рядом после конфликтов, сам индекс и состояние слияния.
     *
     * <p>Всё это и есть скачанное. Миры, скриншоты, логи и шейдерпаки игрока в индексе
     * не значатся и потому здесь не появляются.
     *
     * <p>Индекс — единственный источник знания о том, что было скачано. Если его нет,
     * список пуст: удалять вслепую весь каталог инстанса нельзя, там лежат сохранения.
     */
    @Override
    protected Collection<Path> managedPaths() throws IOException {
        List<Path> paths = new ArrayList<>();
        JSONArray files = SyncPlanner.filesOf(getLocalConfig(configPath));
        for (int i = 0; i < files.length(); i++) {
            Path target = planner.resolveSafe(files.getJSONObject(i).getString("path"));
            paths.add(target);
            paths.add(MergeState.conflictSibling(target));
        }
        paths.add(configPath);
        paths.addAll(mergeState.stateRoots());
        return paths;
    }

    /** Профиль работает внутри каталога инстанса и за его пределы не удаляет. */
    @Override
    protected Path deletionRoot() {
        return basePath.toAbsolutePath().normalize();
    }

    public int getRetryDelay() {
        return retryDelay;
    }

    public static class URLEncoderChromium {
        private static boolean isUnreserved(char c) {
            return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
        }

        /**
         * Percent-кодирование пути посегментно: '/' сохраняется как разделитель,
         * но reserved-символы ('?', '#', '&', '=', ...) внутри сегментов экранируются,
         * чтобы имя файла из индекса не могло изменить структуру URL.
         */
        public static String encode(String path) {
            String[] segments = path.split("/", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(encodeSegment(segments[i]));
            }
            return sb.toString();
        }

        private static String encodeSegment(String s) {
            StringBuilder result = new StringBuilder();
            byte[] utf8Bytes = s.getBytes(StandardCharsets.UTF_8);
            for (byte b : utf8Bytes) {
                char c = (char) (b & 0xFF);
                if (isUnreserved(c)) {
                    result.append(c);
                } else {
                    result.append(String.format("%%%02X", b & 0xFF));
                }
            }
            return result.toString();
        }
    }
}

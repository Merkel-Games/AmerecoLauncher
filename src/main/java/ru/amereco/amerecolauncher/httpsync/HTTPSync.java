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
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONArray;
import ru.amereco.amerecolauncher.utils.Downloader;

public class HTTPSync extends Downloader {
    private final String configURL;
    private final String baseURL;
    private final Path configPath;
    private final Path basePath;
    private final int timeout;
    private final int retryDelay;

    private final HttpClient httpClient;

    public HTTPSync(String configURL, String baseURL, Path configPath, Path basePath,
                   int timeout, int retryDelay) {
        this.configURL = configURL;
        this.baseURL = baseURL;
        this.configPath = configPath;
        this.basePath = basePath;
        this.timeout = timeout;
        this.retryDelay = retryDelay;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(timeout))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
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

    private JSONObject getLocalConfig(Path path) throws IOException, InterruptedException {
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

    private static JSONArray filesOf(JSONObject config) {
        JSONArray files = config.optJSONArray("files");
        return files != null ? files : new JSONArray();
    }

    /**
     * Резолвит путь из удалённого индекса в локальный, гарантируя, что он остаётся
     * внутри basePath (защита от path traversal: "../..", абсолютные пути).
     */
    private Path resolveSafe(String relative) throws IOException {
        Path root = basePath.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("path traversal blocked: " + relative);
        }
        return target;
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
                Files.deleteIfExists(configPath);
            } catch (Exception exc) {
            }
        }));
        Map<String, String> fileActions = getFileActions();
        try {
            performFileActions(fileActions);
            waitUntilDownload();
            updateConfig();
        } finally {
            fileActions.clear();
        }
        waitUntilDownload();
        updateStage("Обновления загружены");
    }

    private Map<String, String> getFileActions() throws IOException, InterruptedException {
        updateStage("Загрузка конфигов в память");

        JSONObject remoteConfig = getConfig(configURL);
        JSONObject localConfig = getLocalConfig(configPath);

        JSONArray remoteFiles = filesOf(remoteConfig);
        JSONArray localFiles = filesOf(localConfig);

        Map<String, String> actions = new HashMap<>();

        updateStage("Вычисление разницы файлов: Пометка файлов");
        resetProgress(remoteFiles.length() + localFiles.length());

        // Mark local files for deletion
        for (int i = 0; i < localFiles.length(); i++) {
            JSONObject file = localFiles.getJSONObject(i);
            String path = file.getString("path");
            updateStepAndIncProgress(path);
            actions.put(path, "D");
        }

        updateStage("Вычисление разницы файлов: Сравнение");
        for (int i = 0; i < remoteFiles.length(); i++) {
            JSONObject remoteFile = remoteFiles.getJSONObject(i);
            String path = remoteFile.getString("path");
            updateStepAndIncProgress(path);

            boolean found = false;
            for (int j = 0; j < localFiles.length(); j++) {
                JSONObject localFile = localFiles.getJSONObject(j);
                if (path.equals(localFile.getString("path"))) {
                    found = true;
                    if (!remoteFile.getString("hash").equals(localFile.getString("hash"))) {
                        actions.put(path, "U");
                    } else {
                        actions.remove(path);
                    }
                    break;
                }
            }

            if (!found) {
                actions.put(path, "C");
            }
        }

        updateStage("Сравнение окончено");
        return actions;
    }

    private void performFileActions(Map<String, String> actions) throws IOException, InterruptedException {
        updateStage("Синхронизация");
        resetProgress(actions.size());

        for (Map.Entry<String, String> entry : actions.entrySet()) {
            updateStep(entry.getKey());
            String action = entry.getValue();
            Path target = resolveSafe(entry.getKey());

            if (action.equals("C") || action.equals("U")) {
                String remoteUrl = baseURL + URLEncoderChromium.encode(entry.getKey());
                Path dirPath = target.getParent();
                if (dirPath != null && !Files.exists(dirPath)) {
                    Files.createDirectories(dirPath);
                }
                downloadFile(remoteUrl, target.toString());
            } else if (action.equals("D")) {
                Files.deleteIfExists(target);
                updateStepAndIncProgress(entry.getKey());
            }
        }
    }

    private void downloadFile(String url, String outputPath) {
        downloadToPathInThread(URI.create(url), Path.of(outputPath));
    }

    private void updateConfig() throws IOException {
        Path configDir = configPath.getParent();
        if (configDir != null && !Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        downloadFile(configURL, configPath.toString());
    }

    /**
     * Purges all files listed in the local index and deletes the index file itself.
     * Used when a profile is disabled — removes its overlay files without network access.
     */
    public void purge() throws IOException, InterruptedException {
        updateStage("Очистка файлов отключённого профиля...");
        JSONObject localConfig = getLocalConfig(configPath);
        JSONArray files = filesOf(localConfig);
        int total = files.length();
        resetProgress(total);
        for (int i = 0; i < total; i++) {
            JSONObject file = files.getJSONObject(i);
            String path = file.getString("path");
            updateStepAndIncProgress(path);
            Files.deleteIfExists(resolveSafe(path));
        }
        Files.deleteIfExists(configPath);
        updateStage("Файлы профиля удалены");
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

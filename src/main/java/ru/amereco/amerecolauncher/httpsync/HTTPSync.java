package ru.amereco.amerecolauncher.httpsync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.UnsupportedEncodingException;
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
            .build();
    }

    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        updateStage("Проверка обновлений...");
        
        JSONObject remoteConfig = getConfig(configURL);
        JSONObject localConfig = getLocalConfig(configPath);
        
        boolean hasUpdates = !remoteConfig.getString("totalHash")
            .equals(localConfig.getString("totalHash"));
        
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
            .GET()
            .build();
            
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
            
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
                Files.deleteIfExists(configPath);
            } catch (Exception exc) {
            }
        }));
        Map<String, String> fileActions = getFileActions();
        try {
            performFileActions(fileActions);
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
        
        Map<String, String> actions = new HashMap<>();
        
        updateStage("Вычисление разницы файлов: Пометка файлов");
        maxProgress = remoteConfig.getJSONArray("files").length() + 
                      localConfig.getJSONArray("files").length();
        progress = 0;
        
        // Mark local files for deletion
        for (int i = 0; i < localConfig.getJSONArray("files").length(); i++) {
            JSONObject file = localConfig.getJSONArray("files").getJSONObject(i);
            String path = file.getString("path");
            updateStepAndIncProgress(path);
            actions.put(path, "D");
        }
        
        updateStage("Вычисление разницы файлов: Сравнение");
        for (int i = 0; i < remoteConfig.getJSONArray("files").length(); i++) {
            JSONObject remoteFile = remoteConfig.getJSONArray("files").getJSONObject(i);
            String path = remoteFile.getString("path");
            updateStepAndIncProgress(path);
            
            boolean found = false;
            for (int j = 0; j < localConfig.getJSONArray("files").length(); j++) {
                JSONObject localFile = localConfig.getJSONArray("files").getJSONObject(j);
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
        maxProgress = actions.size();
        progress = 0;
        
        for (Map.Entry<String, String> entry : actions.entrySet()) {
            updateStep(entry.getKey());
            String action = entry.getValue();
            String localPath = basePath.resolve(entry.getKey()).toString();
            
            if (action.equals("C") || action.equals("U")) {
                String remoteUrl = baseURL + URLEncoderChromium.encode(entry.getKey());
                Path dirPath = Paths.get(localPath).getParent();
                if (!Files.exists(dirPath)) {
                    Files.createDirectories(dirPath);
                }
                downloadFile(remoteUrl, localPath);
            } else if (action.equals("D")) {
                Files.deleteIfExists(Paths.get(localPath));
                updateStepAndIncProgress(entry.getKey());
            }
        }
    }

    private void downloadFile(String url, String outputPath) {
        downloadToPathInThread(URI.create(url), Path.of(outputPath));
    }

    private void updateConfig() throws IOException {
        Path configDir = configPath.getParent();
        if (!Files.exists(configDir)) {
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
        JSONArray files = localConfig.getJSONArray("files");
        int total = files.length();
        progress = 0;
        maxProgress = total;
        for (int i = 0; i < total; i++) {
            JSONObject file = files.getJSONObject(i);
            String path = file.getString("path");
            updateStepAndIncProgress(path);
            Files.deleteIfExists(basePath.resolve(path));
        }
        Files.deleteIfExists(configPath);
        updateStage("Файлы профиля удалены");
    }

    public int getRetryDelay() {
        return retryDelay;
    }

    public static class URLEncoderChromium {
        private static final String ALLOWED_CHARS = 
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "0123456789-_.~;/?:@&=+$,!()*#";

        public static String encode(String s) {
            StringBuilder result = new StringBuilder();
            try {
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (ALLOWED_CHARS.indexOf(c) != -1) {
                        result.append(c);
                    } else {
                        byte[] utf8Bytes = String.valueOf(c).getBytes("UTF-8");
                        for (byte b : utf8Bytes) {
                            result.append(String.format("%%%02X", b & 0xFF));
                        }
                    }
                }
                return result.toString();
            } catch (UnsupportedEncodingException e) {
                return s;
            }
        }
    }
}

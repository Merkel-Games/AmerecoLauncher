package ru.amereco.amerecolauncher;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import ru.amereco.amerecolauncher.utils.Downloader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Обновлятор самого лаунчера.
 *
 * <p>Наследует {@link Downloader} и, следовательно, {@link ru.amereco.amerecolauncher.utils.ProgressSupplier},
 * поэтому полностью совместим с {@link ru.amereco.amerecolauncher.utils.ProgressData} и
 * методами {@code setOnProgress()} / {@code updateStage()} / {@code updateStep()}.
 *
 * <h3>Поддерживаемые платформы и форматы:</h3>
 * <ul>
 *   <li>Windows — {@code exe}, {@code msi}</li>
 *   <li>macOS   — {@code dmg}, {@code pkg}</li>
 *   <li>Linux   — {@code AppImage}, {@code deb}, {@code rpm}</li>
 * </ul>
 *
 * <h3>Отключение автообновления (любым из трёх способов):</h3>
 * <ol>
 *   <li>JVM-свойство: {@code -Damerecо.launcher.update.disabled=true}</li>
 *   <li>Переменная окружения: {@code AMERECO_UPDATE_DISABLED=true}</li>
 *   <li>Файл конфига {@code config.json} — поле {@code "launcherUpdateDisabled": true}
 *       (добавьте поле в {@link Config} при необходимости)</li>
 * </ol>
 *
 * <h3>Использование:</h3>
 * <pre>{@code
 * LauncherUpdater updater = new LauncherUpdater();
 * updater.setOnProgress(data -> {
 *     stageLabel.setText(data.stage());
 *     progressBar.setProgress((double) data.progress() / data.maxProgress());
 * });
 *
 * // В фоновом потоке:
 * if (updater.checkUpdates()) {
 *     updater.askToUpdate();  // показывает Alert на FX-потоке
 * }
 * }</pre>
 */
public class LauncherUpdater extends Downloader {

    // ─── Константы ──────────────────────────────────────────────────────────────

    private static final String GITHUB_API_LATEST =
            "https://api.github.com/repos/Merkel-Games/AmerecoLauncher/releases/latest";

    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/Merkel-Games/AmerecoLauncher/releases/latest/download/AmerecoLauncher.%s";

    // ─── Тип установщика для текущей платформы ──────────────────────────────────

    /**
     * Поддерживаемые форматы установщиков.
     * Расширение {@link #ext} подставляется в URL загрузки.
     */
    public enum InstallerType {
        EXE("exe"),
        MSI("msi"),
        DMG("dmg"),
        PKG("pkg"),
        APP_IMAGE("AppImage"),
        DEB("deb"),
        RPM("rpm");

        public final String ext;

        InstallerType(String ext) {
            this.ext = ext;
        }
    }

    // ─── Поля ───────────────────────────────────────────────────────────────────

    private final InstallerType installerType;
    private final boolean updateDisabled;

    // ─── Конструктор ────────────────────────────────────────────────────────────

    public LauncherUpdater() {
        this.installerType = detectInstallerType();
        this.updateDisabled = isUpdateDisabled();
    }

    // ─── Публичный API (реализация абстрактных методов Downloader) ───────────────

    /**
     * Проверяет, есть ли на GitHub версия новее текущей.
     *
     * <p>Если автообновление отключено — сразу возвращает {@code false}.
     *
     * @return {@code true} если доступна новая версия
     */
    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        if (updateDisabled) {
            updateStage("Автообновление отключено");
            return false;
        }

        updateStage("Проверка обновлений лаунчера...");

        String latestVersion = fetchLatestVersion();
        String currentVersion = currentVersion();

        boolean hasUpdate = isNewer(latestVersion, currentVersion);

        if (hasUpdate) {
            updateStage("Доступна новая версия: " + latestVersion);
        } else {
            updateStage("Лаунчер актуален (" + currentVersion + ")");
        }

        return hasUpdate;
    }

    /**
     * Не используется для лаунчера — делегирует в {@link #checkUpdates()}.
     */
    @Override
    public boolean checkUpdates(String versionId) throws IOException, InterruptedException {
        return checkUpdates();
    }

    /**
     * Скачивает установщик новой версии и запускает его.
     *
     * <p>Использует {@link #downloadToPath(URI, Path)} из {@link Downloader}
     * (с {@code followRedirects(ALWAYS)}) и {@link #updateStage}/{@link #updateStep}
     * из {@link ru.amereco.amerecolauncher.utils.ProgressSupplier}.
     *
     * <p>После успешного запуска установщика лаунчер завершает процесс через
     * {@code System.exit(0)}, чтобы не блокировать файлы на Windows.
     */
    @Override
    public void download() throws IOException, InterruptedException {
        String url = String.format(DOWNLOAD_URL_TEMPLATE, installerType.ext);
        Path tmpDir = Files.createTempDirectory("amereco-update-");
        Path installer = tmpDir.resolve("AmerecoLauncher-update." + installerType.ext);

        maxProgress = 1;
        progress = 0;
        updateStage("Скачивание обновления лаунчера...");
        updateStep(installerType.ext);

        // Используем downloadToPath из Downloader — уже умеет followRedirects(ALWAYS)
        downloadToPath(URI.create(url), installer);

        updateStepAndIncProgress("Установщик скачан: " + installer.getFileName());
        updateStage("Запуск установщика...");

        // Права на выполнение для Unix-форматов
        grantExecutePermission(installer);

        Process process = launchInstaller(installer);

        // Ждём подтверждения, что процесс установщика запущен (до 5 секунд)
        for (int i = 0; i < 50; i++) {
            if (process.isAlive()) {
                System.exit(0);
            }
            Thread.sleep(100);
        }
        // Процесс не стартовал за 5 секунд или сразу завершился — выходим с ошибкой
        System.exit(1);
    }

    /**
     * Не используется — делегирует в {@link #download()}.
     */
    @Override
    public void download(String versionId) throws IOException, InterruptedException {
        download();
    }

    // ─── Дополнительные публичные методы ────────────────────────────────────────

    /**
     * Показывает JavaFX-диалог с предложением обновиться.
     * Вызывает {@link #download()} в фоновом потоке при согласии пользователя.
     *
     * <p>Метод потокобезопасен: внутри использует {@code Platform.runLater}.
     */
    public void askToUpdate() {
        Platform.runLater(() -> {
            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Доступно обновление лаунчера!\nОбновить сейчас?",
                    ButtonType.NO,
                    ButtonType.YES
            );
            alert.setTitle("Обновление AmerecoLauncher");
            alert.setHeaderText(null);

            alert.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    new Thread(() -> {
                        try {
                            download();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Platform.runLater(() -> {
                                Alert err = new Alert(Alert.AlertType.ERROR,
                                        "Ошибка при обновлении:\n" + e.getMessage(),
                                        ButtonType.OK);
                                err.showAndWait();
                            });
                        }
                    }, "launcher-updater").start();
                }
            });
        });
    }

    /**
     * Полный цикл: проверка → диалог → загрузка.
     * Запускается в отдельном потоке, безопасно вызывать из FX-потока.
     */
    public void checkAndUpdate() {
        if (updateDisabled) return;

        new Thread(() -> {
            try {
                if (checkUpdates()) {
                    askToUpdate();
                }
            } catch (Exception e) {
                e.printStackTrace();
                // Не ломаем лаунчер из-за ошибки проверки обновлений
            }
        }, "launcher-update-check").start();
    }

    /** Возвращает тип установщика, выбранный для текущей платформы. */
    public InstallerType getInstallerType() {
        return installerType;
    }

    /** Возвращает {@code true} если автообновление отключено. */
    public boolean isUpdateDisabled() {
        // 1. JVM-свойство
        if ("true".equalsIgnoreCase(System.getProperty("amereco.launcher.update.disabled"))) {
            return true;
        }
        // 2. Переменная окружения
        if ("true".equalsIgnoreCase(System.getenv("AMERECO_UPDATE_DISABLED"))) {
            return true;
        }
        // 3. application.properties (удобно для пакетов из репозитория)
        String prop = Config.properties.getProperty("launcher.update.disabled", "false");
        return "true".equalsIgnoreCase(prop);
    }

    // ─── Приватные вспомогательные методы ───────────────────────────────────────

    /**
     * Запрашивает GitHub Releases API и возвращает тег последней версии
     * (без префикса "v").
     */
    private String fetchLatestVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_LATEST))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API вернул HTTP " + response.statusCode());
        }

        String tag = extractJsonField(response.body(), "tag_name");
        if (tag == null) {
            throw new IOException("Не удалось прочитать tag_name из ответа GitHub");
        }
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /**
     * Возвращает текущую версию лаунчера из {@code application.properties}.
     * Свойство: {@code launcher.version}.
     */
    private String currentVersion() {
        return Config.properties.getProperty("launcher.version", "0.0.0");
    }

    /**
     * Определяет тип установщика на основе текущей ОС.
     * На Linux дополнительно проверяет наличие пакетных менеджеров.
     */
    private InstallerType detectInstallerType() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            return InstallerType.EXE;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return InstallerType.DMG;
        } else {
            // Linux / FreeBSD / прочие Unix
            return detectLinuxInstaller();
        }
    }

    /**
     * Выбирает формат пакета для Linux:
     * deb → rpm → AppImage (универсальный, не требует root).
     */
    private InstallerType detectLinuxInstaller() {
        if (commandExists("dpkg") || commandExists("apt")) {
            return InstallerType.DEB;
        } else if (commandExists("rpm") || commandExists("dnf") || commandExists("yum")) {
            return InstallerType.RPM;
        } else {
            return InstallerType.APP_IMAGE;
        }
    }

    /**
     * Проверяет наличие команды в PATH.
     */
    private boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Выдаёт права на выполнение для Unix-платформ.
     * На Windows — no-op.
     */
    private void grantExecutePermission(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows не поддерживает POSIX-разрешения — игнорируем
        }
    }

    /**
     * Запускает скачанный установщик нативной командой для каждого формата.
     */
    private Process launchInstaller(Path installer) throws IOException {
        String path = installer.toAbsolutePath().toString();
        ProcessBuilder pb;

        switch (installerType) {
            case EXE ->
                pb = new ProcessBuilder(path);

            case MSI ->
                // /qb — тихая установка с полосой прогресса
                pb = new ProcessBuilder("msiexec", "/i", path, "/qb");

            case DMG ->
                // Монтируем образ, macOS сам откроет Finder
                pb = new ProcessBuilder("hdiutil", "attach", path, "-nobrowse");

            case PKG ->
                // open запросит пароль через системный GUI
                pb = new ProcessBuilder("open", path);

            case APP_IMAGE ->
                pb = new ProcessBuilder(path);

            case DEB -> {
                if (commandExists("pkexec")) {
                    // pkexec показывает graphical sudo без терминала
                    pb = new ProcessBuilder("pkexec", "apt", "install", "-y", path);
                } else {
                    pb = new ProcessBuilder(
                            "sh", "-c",
                            "xterm -e 'sudo dpkg -i \"" + path + "\" && sudo apt-get install -f -y'"
                    );
                }
            }

            case RPM -> {
                if (commandExists("pkexec")) {
                    pb = new ProcessBuilder("pkexec", "rpm", "-Uvh", path);
                } else {
                    pb = new ProcessBuilder(
                            "sh", "-c",
                            "xterm -e 'sudo rpm -Uvh \"" + path + "\"'"
                    );
                }
            }

            default -> throw new UnsupportedOperationException(
                    "Неизвестный тип установщика: " + installerType);
        }

        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * Сравнивает две семантические версии (MAJOR.MINOR.PATCH).
     *
     * @return {@code true} если {@code candidate} новее {@code current}
     */
    static boolean isNewer(String candidate, String current) {
        int[] c = parseVersion(candidate);
        int[] cur = parseVersion(current);
        int len = Math.max(c.length, cur.length);
        for (int i = 0; i < len; i++) {
            int cv  = i < c.length   ? c[i]   : 0;
            int curv = i < cur.length ? cur[i] : 0;
            if (cv != curv) return cv > curv;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        // Убираем суффиксы типа "-beta", "+build" → заменяем на точку
        String[] parts = v.replaceAll("[^0-9.]", ".").split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { nums[i] = 0; }
        }
        return nums;
    }

    /**
     * Минимальный парсер одного поля из JSON-ответа GitHub API.
     * Не требует сторонних библиотек — Gson в проекте есть, но здесь
     * достаточно простого поиска подстроки.
     */
    private static String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
package ru.amereco.amerecolauncher;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import ru.amereco.amerecolauncher.utils.Downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обновлятор самого лаунчера.
 *
 * <p>Наследует {@link Downloader} и, следовательно, {@link ru.amereco.amerecolauncher.utils.ProgressSupplier},
 * поэтому полностью совместим с {@link ru.amereco.amerecolauncher.utils.ProgressData} и
 * методами {@code setOnProgress()} / {@code updateStage()} / {@code updateStep()}.
 *
 * <h3>Поддерживаемые платформы и форматы:</h3>
 * <ul>
 *   <li>Windows x64 — {@code exe}</li>
 *   <li>macOS arm64 — {@code pkg}</li>
 *   <li>Linux x64   — {@code deb} / {@code rpm} (по пакетному менеджеру)</li>
 * </ul>
 *
 * <p>Установка из AppImage или запуск голого jar не обновляются пакетом —
 * для них открывается прямая ссылка на загрузку в браузере
 * (см. {@link UpdateMode} и {@link #askToUpdateAuto()}).
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
 *     updater.askToUpdateAuto();  // показывает Alert на FX-потоке
 * }
 * }</pre>
 */
public class LauncherUpdater extends Downloader {

    // ─── Константы ──────────────────────────────────────────────────────────────

    private static final String GITHUB_API_LATEST =
            "https://api.github.com/repos/Merkel-Games/AmerecoLauncher/releases/latest";

    /** Схема имён ассетов: {@code AmerecoLauncher-<os>-<arch>.<ext>}. */
    private static final String ASSET_NAME_TEMPLATE = "AmerecoLauncher-%s-%s.%s";

    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/Merkel-Games/AmerecoLauncher/releases/latest/download/%s";

    /** Файл с контрольными суммами всех ассетов релиза (формат {@code sha256sum}). */
    private static final String CHECKSUMS_FILE = "SHA256SUMS";

    /** Сколько ждём, прежде чем считать, что установщик успешно стартовал. */
    private static final int LAUNCH_CHECK_SECONDS = 5;

    /** Потолок ожидания установки там, где мы дожидаемся её завершения. */
    private static final int INSTALL_TIMEOUT_MINUTES = 15;

    /** Через сколько временная папка обновления считается мусором и удаляется. */
    private static final long STALE_TMP_AGE_MS = 24L * 60 * 60 * 1000;

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

    // ─── URL страницы релиза для открытия в браузере ────────────────────────────

    private static final String RELEASES_PAGE_URL =
            "https://github.com/Merkel-Games/AmerecoLauncher/releases/latest";

    // ─── Поля ───────────────────────────────────────────────────────────────────

    /** Как именно обновляется эта установка лаунчера. */
    public enum UpdateMode {
        /** Скачать установщик и запустить его. */
        INSTALLER,
        /** Открыть прямую ссылку в браузере — пользователь ставит сам. */
        BROWSER
    }

    private final InstallerType installerType;
    private final boolean updateDisabled;

    /** Слаг ОС в имени ассета: {@code linux} / {@code windows} / {@code mac}. */
    private final String platformSlug;

    /** Слаг архитектуры в имени ассета: {@code x64} / {@code aarch64}. */
    private final String archSlug;

    private final UpdateMode updateMode;

    /** Версия, найденная при последнем вызове {@link #checkUpdates()}.
     *  volatile: пишется из фонового потока проверки, читается из FX-потока. */
    private volatile String latestVersion = null;

    /** Версия, от которой пользователь отказался — по ней больше не спрашиваем. */
    private volatile String declinedVersion = null;

    /** Диалог уже показан или обновление идёт — второй по таймеру не открываем. */
    private final AtomicBoolean asking = new AtomicBoolean(false);

    // ─── Конструктор ────────────────────────────────────────────────────────────

    public LauncherUpdater() {
        this.installerType = detectInstallerType();
        this.updateDisabled = isUpdateDisabled();
        this.platformSlug = detectPlatformSlug();
        this.archSlug = detectArchSlug();
        this.updateMode = detectUpdateMode();
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
        this.latestVersion = latestVersion;
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
     * Скачивает установщик новой версии, сверяет его SHA-256 с {@code SHA256SUMS}
     * из релиза и запускает.
     *
     * <p>Использует {@link #downloadToPath(URI, Path)} из {@link Downloader}
     * и {@link #updateStage}/{@link #updateStep}
     * из {@link ru.amereco.amerecolauncher.utils.ProgressSupplier}.
     *
     * <p>Любая проблема (нет ассета под платформу, не сошлась контрольная сумма,
     * отказ в правах, ошибка пакетного менеджера) бросает {@link IOException} —
     * вызывающий код показывает пользователю ошибку.
     *
     * <p>Для deb/rpm метод дожидается конца установки и сообщает результат.
     * На Windows и macOS ждать нельзя (заблокированные файлы), поэтому лаунчер
     * выходит сразу после подтверждённого старта установщика.
     *
     * @throws IOException если самообновление недоступно или что-то пошло не так
     */
    @Override
    public void download() throws IOException, InterruptedException {
        if (updateMode == UpdateMode.BROWSER) {
            throw new IOException("Для этого способа установки самообновление недоступно — "
                    + "скачайте пакет вручную");
        }
        if (!hasAssetForThisPlatform()) {
            throw new IOException("Нет сборки для " + platformSlug + "-" + archSlug
                    + " — скачайте пакет вручную");
        }

        purgeStaleTempDirs();

        Path tmpDir = Files.createTempDirectory("amereco-update-");
        Path installer = tmpDir.resolve(assetName());

        boolean launched = false;
        try {
            resetProgress(2);
            updateStage("Скачивание обновления лаунчера...");
            updateStep(assetName());

            downloadToPath(URI.create(assetUrl()), installer);
            updateStepAndIncProgress("Установщик скачан: " + assetName());

            updateStage("Проверка целостности...");
            verifyChecksum(installer);
            updateStepAndIncProgress("SHA-256 совпадает");

            updateStage("Запуск установщика...");
            grantExecutePermission(installer);
            launchInstallerChecked(installer);
            launched = true;
        } finally {
            // Установщик не стартовал — за собой убираем сразу.
            if (!launched) {
                deleteQuietly(tmpDir);
            }
        }

        if (waitsForInstaller()) {
            // Установка уже завершилась — временные файлы больше не нужны.
            deleteQuietly(tmpDir);
            updateStage("Обновление установлено");
            showInstalledDialog();
        }
        // Иначе установщик всё ещё работает с файлом внутри tmpDir и удалять его
        // сейчас нельзя; остатки подчистит purgeStaleTempDirs() при следующем запуске.
        System.exit(0);
    }

    /**
     * Сообщает, что новая версия установлена, и ждёт закрытия диалога,
     * чтобы пользователь успел его прочитать до выхода.
     */
    private static void showInstalledDialog() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Alert done = new Alert(Alert.AlertType.INFORMATION,
                        "Новая версия установлена.\nЗапустите лаунчер заново.",
                        ButtonType.OK);
                done.setTitle("Обновление AmerecoLauncher");
                done.setHeaderText(null);
                done.showAndWait();
            } finally {
                closed.countDown();
            }
        });
        // Ограничение по времени — чтобы не зависнуть навсегда, если FX-поток недоступен.
        closed.await(2, TimeUnit.MINUTES);
    }

    /** Имя ассета для текущей ОС/архитектуры: {@code AmerecoLauncher-<os>-<arch>.<ext>}. */
    private String assetName() {
        return String.format(ASSET_NAME_TEMPLATE, platformSlug, archSlug, installerType.ext);
    }

    /** Прямая ссылка на ассет в последнем релизе. */
    private String assetUrl() {
        return String.format(DOWNLOAD_URL_TEMPLATE, assetName());
    }

    /**
     * Есть ли в релизах сборка под текущую пару ОС/архитектура.
     * Держать в соответствии с матрицей в {@code .github/workflows/publish-packages.yml}.
     */
    private boolean hasAssetForThisPlatform() {
        return switch (platformSlug) {
            case "windows", "linux" -> "x64".equals(archSlug);   // ubuntu-latest / windows-latest
            case "mac" -> "aarch64".equals(archSlug);            // macos-latest = Apple Silicon
            default -> false;
        };
    }

    /**
     * Сверяет SHA-256 скачанного файла с {@code SHA256SUMS} из релиза.
     *
     * <p>Проверка fail-closed: нет файла сумм или нет строки под наш ассет —
     * установка не начинается. Без этого лаунчер запускал бы непроверенный
     * бинарник, на Linux — ещё и от root через pkexec.
     */
    private void verifyChecksum(Path installer) throws IOException, InterruptedException {
        String expected = fetchExpectedSha256(assetName());
        String actual = sha256(installer);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("Контрольная сумма не совпала для " + assetName()
                    + "\nожидалась: " + expected + "\nполучена:  " + actual);
        }
    }

    /** Достаёт ожидаемую сумму для конкретного ассета из {@code SHA256SUMS}. */
    private String fetchExpectedSha256(String asset) throws IOException, InterruptedException {
        String sums;
        try {
            sums = httpGet(URI.create(String.format(DOWNLOAD_URL_TEMPLATE, CHECKSUMS_FILE)));
        } catch (IOException e) {
            throw new IOException("Не удалось получить " + CHECKSUMS_FILE
                    + " — установка непроверенного файла отменена", e);
        }
        for (String line : sums.split("\\R")) {
            // формат sha256sum: "<hex>  <имя>" ('*' перед именем — бинарный режим)
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[1].replaceFirst("^\\*", "").equals(asset)) {
                return parts[0];
            }
        }
        throw new IOException("В " + CHECKSUMS_FILE + " нет записи для " + asset);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) { /* streaming digest */ }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 недоступен", e);
        }
    }

    /**
     * Запускает установщик.
     *
     * <p>Для deb/rpm ждём полного завершения установки — на Linux файлы запущенного
     * лаунчера не блокируются, поэтому спешить с выходом незачем, зато можно поймать
     * отказ polkit, отсутствие терминала и ошибки пакетного менеджера и показать их
     * пользователю.
     *
     * <p>На Windows и macOS ждать нельзя (заблокированные файлы, установщик сам
     * попросит закрыть приложение), поэтому проверяем только, что процесс не упал
     * сразу: жив по истечении {@link #LAUNCH_CHECK_SECONDS} — установщик работает;
     * код 0 — команда-обёртка ({@code open}) передала управление системе;
     * ненулевой код — ошибка.
     */
    private void launchInstallerChecked(Path installer) throws IOException, InterruptedException {
        Path log = installer.resolveSibling("installer.log");
        Process process = launchInstaller(installer, log);

        if (waitsForInstaller()) {
            updateStage("Установка... подтвердите права администратора");
            if (!process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroy();
                throw new IOException("Установка не завершилась за "
                        + INSTALL_TIMEOUT_MINUTES + " мин" + tailOf(log));
            }
            int code = process.exitValue();
            if (code != 0) {
                throw new IOException(describeInstallExit(code) + tailOf(log));
            }
            return;
        }

        if (process.waitFor(LAUNCH_CHECK_SECONDS, TimeUnit.SECONDS)
                && process.exitValue() != 0) {
            throw new IOException("Установщик завершился с кодом " + process.exitValue()
                    + tailOf(log));
        }
    }

    /**
     * Можно ли дождаться окончания установки, не мешая ей.
     *
     * <p>Только для пакетных менеджеров Linux: на Windows файлы запущенного процесса
     * заблокированы, на macOS {@code open} возвращает управление сразу и ждать нечего.
     */
    private boolean waitsForInstaller() {
        return installerType == InstallerType.DEB || installerType == InstallerType.RPM;
    }

    /** Расшифровывает коды выхода pkexec / оболочки в человеческий текст. */
    private static String describeInstallExit(int code) {
        return switch (code) {
            case 126 -> "Установка отменена: права администратора не подтверждены";
            case 127 -> "Не удалось запустить установщик: команда недоступна";
            default -> "Установщик завершился с кодом " + code;
        };
    }

    /** Последние строки лога установщика — для текста ошибки. */
    private static String tailOf(Path log) {
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return "";
            }
            return ":\n" + (text.length() > 500 ? text.substring(text.length() - 500) : text);
        } catch (Exception e) {
            return "";
        }
    }

    /** Удаляет забытые каталоги прошлых обновлений из системного temp. */
    private static void purgeStaleTempDirs() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."));
        long cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS;
        try (java.util.stream.Stream<Path> s = Files.list(tmp)) {
            s.filter(p -> p.getFileName().toString().startsWith("amereco-update-"))
             .filter(Files::isDirectory)
             .filter(p -> {
                 try {
                     return Files.getLastModifiedTime(p).toMillis() < cutoff;
                 } catch (IOException e) {
                     return false;
                 }
             })
             .forEach(LauncherUpdater::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
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
        if (!beginAsking()) return;

        Platform.runLater(() -> {
            String versionInfo = latestVersion != null ? " (" + latestVersion + ")" : "";
            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Доступно обновление лаунчера" + versionInfo + "!\nОбновить сейчас?",
                    ButtonType.NO,
                    ButtonType.YES
            );
            alert.setTitle("Обновление AmerecoLauncher");
            alert.setHeaderText(null);

            var answer = alert.showAndWait();
            if (answer.isPresent() && answer.get() == ButtonType.YES) {
                Thread t = new Thread(() -> {
                    try {
                        download();
                    } catch (Exception e) {
                        e.printStackTrace();
                        // Обновление сорвалось — разрешаем спросить снова.
                        asking.set(false);
                        Platform.runLater(() -> {
                            Alert err = new Alert(Alert.AlertType.ERROR,
                                    "Ошибка при обновлении:\n" + e.getMessage(),
                                    ButtonType.OK);
                            err.showAndWait();
                        });
                    }
                }, "launcher-updater");
                t.setDaemon(true);
                t.start();
            } else {
                endAsking(true);
            }
        });
    }

    /**
     * Резервирует право показать диалог.
     *
     * @return {@code false} если диалог уже открыт, обновление идёт или от этой
     *         версии уже отказались — тогда показывать ничего не нужно
     */
    private boolean beginAsking() {
        String version = latestVersion;
        if (version != null && version.equals(declinedVersion)) {
            return false;
        }
        return asking.compareAndSet(false, true);
    }

    /** Освобождает диалог; {@code declined} — запомнить отказ по текущей версии. */
    private void endAsking(boolean declined) {
        if (declined) {
            declinedVersion = latestVersion;
        }
        asking.set(false);
    }

    /**
     * «Холостой» вариант обновления: лаунчер <b>не скачивает</b> установщик сам.
     *
     * <p>Показывает диалог с предложением обновиться. При согласии:
     * <ol>
     *   <li>Открывает страницу релиза в системном браузере — браузер автоматически
     *       начнёт скачивание нужного установщика (прямая ссылка на файл).</li>
     *   <li>Закрывает лаунчер через {@code System.exit(0)}.</li>
     * </ol>
     *
     * <p>Удобно использовать когда встроенная загрузка нежелательна или недоступна.
     * Метод потокобезопасен.
     */
    public void askToUpdateBrowser() {
        if (!beginAsking()) return;

        Platform.runLater(() -> {
            String versionInfo = latestVersion != null ? " (" + latestVersion + ")" : "";
            String what = hasAssetForThisPlatform()
                    ? "Скачать " + assetName() + " и закрыть лаунчер?"
                    : "Открыть страницу релиза в браузере?";
            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Доступно обновление лаунчера" + versionInfo + "!\n" + what,
                    ButtonType.NO,
                    ButtonType.YES
            );
            alert.setTitle("Обновление AmerecoLauncher");
            alert.setHeaderText(null);

            var answer = alert.showAndWait();
            if (answer.isPresent() && answer.get() == ButtonType.YES) {
                openDownloadInBrowser();
                System.exit(0);
            } else {
                endAsking(true);
            }
        });
    }

    /**
     * Открывает в системном браузере прямую ссылку на пакет для текущей
     * ОС/архитектуры — скачивание начнётся сразу, без промежуточных страниц.
     *
     * <p>Если сборки под эту платформу нет, открывается страница релиза,
     * чтобы пользователь выбрал файл сам.
     */
    public void openDownloadInBrowser() {
        String url = hasAssetForThisPlatform() ? assetUrl() : RELEASES_PAGE_URL;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac") || os.contains("darwin")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Показывает диалог обновления способом, подходящим для этой установки:
     * пакетную ставим сами, AppImage/jar отправляем в браузер.
     *
     * <p>Это точка входа по умолчанию — используйте её вместо
     * {@link #askToUpdate()} / {@link #askToUpdateBrowser()}.
     */
    public void askToUpdateAuto() {
        if (updateMode == UpdateMode.BROWSER || !hasAssetForThisPlatform()) {
            askToUpdateBrowser();
        } else {
            askToUpdate();
        }
    }

    /**
     * Полный цикл: проверка → диалог → обновление подходящим способом.
     * Запускается в отдельном потоке, безопасно вызывать из FX-потока.
     */
    public void checkAndUpdate() {
        if (updateDisabled) return;

        Thread t = new Thread(() -> {
            try {
                if (checkUpdates()) {
                    askToUpdateAuto();
                }
            } catch (Exception e) {
                e.printStackTrace();
                // Не ломаем лаунчер из-за ошибки проверки обновлений
            }
        }, "launcher-update-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Полный цикл (холостой режим): проверка → диалог → браузер → закрытие.
     * Запускается в отдельном потоке, безопасно вызывать из FX-потока.
     *
     * <p>Используйте этот метод если хотите, чтобы лаунчер <b>не скачивал</b>
     * установщик самостоятельно, а перенаправлял пользователя в браузер.
     */
    public void checkAndUpdateBrowser() {
        if (updateDisabled) return;

        Thread t = new Thread(() -> {
            try {
                if (checkUpdates()) {
                    askToUpdateBrowser();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "launcher-update-check-browser");
        t.setDaemon(true);
        t.start();
    }

    /** Возвращает последнюю версию, найденную при последнем вызове {@link #checkUpdates()},
     *  или {@code null} если проверка ещё не выполнялась. */
    public String getLatestVersion() {
        return latestVersion;
    }

    /** Возвращает тип установщика, выбранный для текущей платформы. */
    public InstallerType getInstallerType() {
        return installerType;
    }

    /** Возвращает способ обновления, определённый для этой установки. */
    public UpdateMode getUpdateMode() {
        return updateMode;
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
     * Возвращает тег последней версии (без префикса «v»).
     *
     * <p>Основной путь — редирект {@code /releases/latest → /releases/tag/vX.Y.Z}.
     * Это обычная страница github.com без лимита на неавторизованные запросы,
     * в отличие от api.github.com с его 60 запросами в час на IP: за общим NAT
     * (общежитие, мобильный оператор, CGNAT) такой лимит выбирается чужими
     * запросами независимо от нашего интервала проверок.
     *
     * <p>API остаётся запасным вариантом на случай, если формат ссылки изменится.
     */
    private String fetchLatestVersion() throws IOException, InterruptedException {
        String tag = fetchTagFromRedirect();
        if (tag == null) {
            tag = fetchTagFromApi();
        }
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /** Тег из заголовка Location; {@code null}, если ответ не тот, что ожидали. */
    private String fetchTagFromRedirect() throws InterruptedException {
        final String marker = "/releases/tag/";
        try {
            HttpClient noRedirect = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_PAGE_URL))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = noRedirect.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 3) {
                return null;
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null) {
                return null;
            }
            int at = location.lastIndexOf(marker);
            if (at < 0) {
                return null;
            }
            String tag = URLDecoder.decode(
                    location.substring(at + marker.length()), StandardCharsets.UTF_8);
            return tag.isBlank() ? null : tag;
        } catch (IOException e) {
            return null;
        }
    }

    /** Запасной путь: GitHub Releases API. */
    private String fetchTagFromApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_LATEST))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(30))
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
        return tag;
    }

    /**
     * Возвращает текущую версию лаунчера из {@code application.properties}.
     * Свойство: {@code launcher.version}.
     */
    private String currentVersion() {
        // Ключ в application.properties называется "version" (launcher.version не существует).
        return Config.properties.getProperty("version", "0.0.0");
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
            // PKG, а не DMG: это настоящий установщик, который сам положит
            // приложение в /Applications. DMG требует ручного перетаскивания.
            return InstallerType.PKG;
        } else if (System.getenv("APPIMAGE") != null) {
            // Запущены из AppImage — обновляться нужно тем же форматом.
            return InstallerType.APP_IMAGE;
        } else {
            // Linux / FreeBSD / прочие Unix
            return detectLinuxInstaller();
        }
    }

    /**
     * Определяет, можно ли обновлять эту установку самостоятельно.
     *
     * <p>AppImage и голый jar ставить пакетом нельзя: установщик создаст вторую
     * копию рядом с той, из которой запущен лаунчер, а запущенный файл так и
     * останется старым. Для них — только загрузка через браузер.
     */
    private UpdateMode detectUpdateMode() {
        if (System.getenv("APPIMAGE") != null) {
            return UpdateMode.BROWSER;
        }
        if (isPlainJarRun()) {
            return UpdateMode.BROWSER;
        }
        // deb/rpm ставим через pkexec. Без него графической авторизации нет,
        // а запуск в терминале не даёт достоверного кода возврата.
        if ((installerType == InstallerType.DEB || installerType == InstallerType.RPM)
                && !commandExists("pkexec")) {
            return UpdateMode.BROWSER;
        }
        return UpdateMode.INSTALLER;
    }

    /** Страхует ветки, требующие графической авторизации. */
    private void requirePkexec() throws IOException {
        if (!commandExists("pkexec")) {
            throw new IOException("Не найден pkexec — скачайте пакет и установите вручную");
        }
    }

    /**
     * {@code true}, если лаунчер запущен как обычный jar на системной JVM.
     *
     * <p>У пакетной установки JRE лежит внутри каталога приложения
     * ({@code /opt/app/{jre,app.jar}}, {@code AmerecoLauncher.app/Contents/{Home,Resources/app.jar}}),
     * у запуска через {@code java -jar} — где-то в системе.
     * Если определить не удалось, считаем установку пакетной (прежнее поведение).
     */
    private static boolean isPlainJarRun() {
        try {
            var source = LauncherUpdater.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return false;
            }
            Path javaHome = Path.of(System.getProperty("java.home", "")).toRealPath();
            Path code = Path.of(source.getLocation().toURI()).toRealPath();

            Path dir = code.getParent();
            for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParent()) {
                if (javaHome.startsWith(dir)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Слаг ОС для имени ассета — должен совпадать с {@code project.platform} из pom.xml
     * ({@code linux} / {@code windows} / {@code mac}).
     */
    private static String detectPlatformSlug() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        return "linux";
    }

    /**
     * Слаг архитектуры для имени ассета — должен совпадать с {@code project.arch} из pom.xml
     * ({@code x64} / {@code aarch64}).
     */
    private static String detectArchSlug() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
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
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
     *
     * <p>Вывод уходит в файл, а не в пайп: лаунчер завершается сразу после старта
     * установщика, и незакрытый пайп убивал бы его по SIGPIPE на первой же записи.
     */
    private Process launchInstaller(Path installer, Path log) throws IOException {
        String path = installer.toAbsolutePath().toString();
        ProcessBuilder pb;

        switch (installerType) {
            case EXE ->
                pb = new ProcessBuilder(path);

            case MSI ->
                // /qb — тихая установка с полосой прогресса
                pb = new ProcessBuilder("msiexec", "/i", path, "/qb");

            case DMG ->
                // Монтируем образ и показываем том в Finder — без -nobrowse,
                // иначе пользователь не увидит вообще ничего.
                pb = new ProcessBuilder("hdiutil", "attach", path);

            case PKG ->
                // open запросит пароль через системный GUI
                pb = new ProcessBuilder("open", path);

            case APP_IMAGE ->
                pb = new ProcessBuilder(path);

            // Ветки с xterm убраны намеренно: xterm есть далеко не везде, а его код
            // возврата не отражает результат команды внутри — провалившаяся установка
            // выглядела бы успешной. Без pkexec установка идёт через браузер вручную.
            case DEB -> {
                requirePkexec();
                // pkexec показывает graphical sudo без терминала
                pb = new ProcessBuilder("pkexec", "apt", "install", "-y", path);
            }

            case RPM -> {
                requirePkexec();
                pb = new ProcessBuilder("pkexec", "rpm", "-Uvh", path);
            }

            default -> throw new UnsupportedOperationException(
                    "Неизвестный тип установщика: " + installerType);
        }

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
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
        // Отрезаем pre-release/build суффикс от первого '-' или '+',
        // затем берём только числовые сегменты MAJOR.MINOR.PATCH.
        // Иначе "2.0.0-rc1" → {2,0,0,1} ошибочно считался НОВЕЕ стабильного "2.0.0".
        String core = v.trim().replaceFirst("[-+].*$", "");
        String[] parts = core.split("\\.");
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            try { nums.add(Integer.parseInt(p.trim())); }
            catch (NumberFormatException ignored) { }
        }
        int[] out = new int[nums.size()];
        for (int i = 0; i < out.length; i++) out[i] = nums.get(i);
        return out;
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
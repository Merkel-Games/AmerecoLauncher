package ru.amereco.amerecolauncher;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.amereco.amerecolauncher.httpsync.HTTPSync;
import ru.amereco.amerecolauncher.minecraft.MinecraftLauncher;
import ru.amereco.amerecolauncher.minecraft.MinecraftSession;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.WindowEvent;
import ru.amereco.amerecolauncher.minecraft.Loader;
import ru.amereco.amerecolauncher.minecraft.MinecraftDownloader;
import ru.amereco.amerecolauncher.minecraft.fabric.FabricDownloader;
import ru.amereco.amerecolauncher.utils.ProgressData;
import ru.amereco.amerecolauncher.LauncherUpdater;
import ru.amereco.amerecolauncher.minecraft.authlibinjector.AuthlibInjectorDownloader;

public class MainController {
    public enum UpdateNeeded {
        MINECRAFT, FABRIC, AUTHLIB_INJECTOR, HTTPSYNC, LAUNCHER;
        public static final EnumSet<UpdateNeeded> ALL_OPTS = EnumSet.allOf(UpdateNeeded.class);
    }

    private final Config config = Config.get();
    private MinecraftDownloader minecraftDownloader;
    private FabricDownloader fabricDownloader;
    private AuthlibInjectorDownloader authlibInjectorDownloader;
    private LauncherUpdater launcherUpdater;

    // Пересоздаётся на каждой проверке; присваивается только на FX-потоке.
    private volatile EnumSet<UpdateNeeded> updateNeeded = EnumSet.noneOf(UpdateNeeded.class);
    // Не даём двум проверкам обновлений идти параллельно.
    private final AtomicBoolean checking = new AtomicBoolean(false);

    @FXML private StackPane root;
    @FXML private Button mainButton;
    @FXML private Button settingsBtn;
    @FXML private Button logoutBtn;
    @FXML private Button quitBtn;
    @FXML private ProgressBar progressBar;
    @FXML private Label stageLabel;
    @FXML private Label stepLabel;
    @FXML private Label progressLabel;

    private static String prop(String key) {
        return Config.properties.getProperty(key);
    }

    /**
     * Returns the list of active profile names based on config features.
     * Base profile (rpcraft) is always first, then overlays in order.
     */
    private List<String> getActiveProfiles() {
        List<String> profiles = new ArrayList<>();
        if (config.features.getOrDefault("profile_rpcraft", true))
            profiles.add("rpcraft");
        if (config.features.getOrDefault("profile_rpcraft_admin", false))
            profiles.add("rpcraft-admin");
        if (config.features.getOrDefault("profile_rpcraft_ultra", false))
            profiles.add("rpcraft-ultra");
        return profiles;
    }

    /**
     * Creates an HTTPSync instance for the given profile name.
     */
    private HTTPSync createSyncForProfile(String profileName) {
        String baseUrl = "https://amereco.ru/client_dist/instances/" + profileName + "/";
        String configUrl = "https://amereco.ru/client_dist/instances/" + profileName + "/" + profileName + ".json";
        Path basePath = Path.of(config.mainDir, "instances/rpcraft/");
        Path configPath = Path.of(config.mainDir, "instances/rpcraft/" + profileName + ".json");
        HTTPSync sync = new HTTPSync(configUrl, baseUrl, configPath, basePath, 5000, 3000);
        sync.setOnProgress(this::handleProgressUpdate);
        return sync;
    }

    /**
     * Purges files of overlay profiles that are no longer active.
     * Runs on every checkUpdates, so disabling a profile triggers cleanup immediately.
     */
    private void purgeDisabledProfiles() throws IOException, InterruptedException {
        List<String> activeProfiles = getActiveProfiles();
        String[] knownOverlayProfiles = {"rpcraft-admin", "rpcraft-ultra"};
        for (String overlay : knownOverlayProfiles) {
            if (!activeProfiles.contains(overlay)) {
                Path indexFile = Path.of(config.mainDir, "instances/rpcraft/" + overlay + ".json");
                if (Files.exists(indexFile)) {
                    HTTPSync sync = createSyncForProfile(overlay);
                    sync.purge();
                }
            }
        }
    }

    @FXML
    public void initialize() {
        try {
            Files.createDirectories(Path.of(config.mainDir));
        } catch (IOException exception) {
            System.out.println("Can't create path "+config.mainDir);
            exitProgram();
        }

        minecraftDownloader = new MinecraftDownloader();
        minecraftDownloader.setOnProgress(this::handleProgressUpdate);
        fabricDownloader = new FabricDownloader();
        fabricDownloader.setOnProgress(this::handleProgressUpdate);
        authlibInjectorDownloader = new AuthlibInjectorDownloader();
        authlibInjectorDownloader.setOnProgress(this::handleProgressUpdate);

        launcherUpdater = new LauncherUpdater();
        launcherUpdater.setOnProgress(this::handleProgressUpdate);

        hideProgress();

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((o2, oldWindow, newWindow) -> {
                    if (newWindow != null) {
                        newWindow.addEventHandler(WindowEvent.WINDOW_SHOWN, ev -> {
                            // Runs once, immediately after window appears
                            onOpen();
                        });
                    }
                });
            }
        });
        checkUpdates();

        var session = MinecraftSession.getInstance();
        if (session.isRunning()) {
            session.setOnStopped(() -> javafx.application.Platform.runLater(() -> {
                mainButton.setText("Играть");
                mainButton.setOnAction(e -> launchMinecraft());
            }));
            javafx.application.Platform.runLater(() -> {
                mainButton.setText("Остановить");
                mainButton.setOnAction(e -> stopMinecraft());
            });
        }
    }

    public void onOpen() {
        checkUpdates();
    }

    private void checkUpdates() {
        if (!checking.compareAndSet(false, true)) {
            return; // проверка уже идёт
        }
        showProgress();
        mainButton.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                EnumSet<UpdateNeeded> needed = EnumSet.noneOf(UpdateNeeded.class);

                if (minecraftDownloader.checkUpdates(prop("minecraftVersion")))
                    needed.add(UpdateNeeded.MINECRAFT);
                if (fabricDownloader.checkUpdates(prop("fabricVersion")))
                    needed.add(UpdateNeeded.FABRIC);
                if (authlibInjectorDownloader.checkUpdates(prop("authlibInjectorVersion")))
                    needed.add(UpdateNeeded.AUTHLIB_INJECTOR);

                // Purge disabled overlay profiles before checking updates
                purgeDisabledProfiles();

                // Check all active profiles for updates
                for (String profileName : getActiveProfiles()) {
                    HTTPSync sync = createSyncForProfile(profileName);
                    if (sync.checkUpdates("")) {
                        needed.add(UpdateNeeded.HTTPSYNC);
                        break; // one update is enough to trigger the update button
                    }
                }

                // if (launcherUpdater.checkUpdates())
                    // needed.add(UpdateNeeded.LAUNCHER);

                javafx.application.Platform.runLater(() -> {
                    updateNeeded = needed;
                    hideProgress();
                    if (MinecraftSession.getInstance().isRunning()) {
                        mainButton.setDisable(false);
                        return;
                    }
                    if (!needed.isEmpty()) {
                        mainButton.setText("Обновить");
                        mainButton.setOnAction(e -> startUpdate());
                    } else {
                        mainButton.setText("Играть");
                        mainButton.setOnAction(e -> launchMinecraft());
                    }
                    mainButton.setDisable(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                javafx.application.Platform.runLater(() -> {
                    stageLabel.setText("Ошибка проверки обновлений");
                    stepLabel.setText(e.getMessage());
                    mainButton.setDisable(false);
                });
            } finally {
                checking.set(false);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void startUpdate() {
        final EnumSet<UpdateNeeded> needed = updateNeeded.isEmpty()
                ? EnumSet.noneOf(UpdateNeeded.class)
                : EnumSet.copyOf(updateNeeded);
        mainButton.setDisable(true);
        showProgress();
        Thread t = new Thread(() -> {
            try {
                // Обновление лаунчера — первым, так как System.exit(0) убьёт процесс
                if (needed.contains(UpdateNeeded.LAUNCHER)) {
                    launcherUpdater.askToUpdate();
                    return;
                }
                if (needed.contains(UpdateNeeded.MINECRAFT))
                    minecraftDownloader.download(prop("minecraftVersion"));
                if (needed.contains(UpdateNeeded.FABRIC))
                    fabricDownloader.download(prop("fabricVersion"));
                if (needed.contains(UpdateNeeded.AUTHLIB_INJECTOR))
                    authlibInjectorDownloader.download(prop("authlibInjectorVersion"));

                // Sync profiles in order: base first, then overlays
                if (needed.contains(UpdateNeeded.HTTPSYNC)) {
                    for (String profileName : getActiveProfiles()) {
                        HTTPSync sync = createSyncForProfile(profileName);
                        sync.download("");
                    }
                }

                javafx.application.Platform.runLater(() -> {
                    updateNeeded = EnumSet.noneOf(UpdateNeeded.class); // сброс после успешного обновления
                    hideProgress();
                    mainButton.setText("Играть");
                    mainButton.setOnAction(e -> launchMinecraft());
                    mainButton.setDisable(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                javafx.application.Platform.runLater(() -> {
                    stageLabel.setText("Ошибка обновления");
                    stepLabel.setText(e.getMessage());
                    mainButton.setDisable(false);
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void handleProgressUpdate(ProgressData progress) {
        javafx.application.Platform.runLater(() -> {
            stageLabel.setText(progress.stage());
            stepLabel.setText(progress.step());
            progressBar.setProgress(progress.maxProgress() > 0 ?
                (double)progress.progress() / progress.maxProgress() : 0);
            progressLabel.setText(String.format("%d / %d",
                progress.progress(), progress.maxProgress()));
        });
    }

    private void hideProgress() {
        stepLabel.setVisible(false);
        stageLabel.setVisible(false);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
    }

    private void showProgress() {
        stepLabel.setVisible(true);
        stageLabel.setVisible(true);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
    }

    private void launchMinecraft() {
        var session = MinecraftSession.getInstance();
        if (session.isRunning()) return;

        try {
            String mainDir = config.mainDir;
            String gameDir = Paths.get(mainDir, "instances/rpcraft").toString();

            mainButton.setText("Остановить");
            mainButton.setOnAction(e -> stopMinecraft());

            session.setOnStopped(() -> javafx.application.Platform.runLater(() -> {
                mainButton.setText("Играть");
                mainButton.setOnAction(e -> launchMinecraft());
                checkUpdates();
            }));

            session.launch(() -> {
                try {
                    MinecraftLauncher minecraftLauncher = new MinecraftLauncher(
                        Path.of(mainDir),
                        Path.of(gameDir)
                    );

                    minecraftLauncher.uuid = config.uuid;
                    minecraftLauncher.accessToken = config.accessToken;
                    minecraftLauncher.clientId = config.clientId;
                    if (config.userName != null && !config.userName.isBlank())
                        minecraftLauncher.userName = config.userName;

                    try {
                        AuthServer auth = new AuthServer();
                        var resp = auth.refresh(config.accessToken, config.clientId, null);
                        config.accessToken = resp.accessToken();
                        minecraftLauncher.accessToken = resp.accessToken();
                        if (resp.selectedProfile() != null) {
                            minecraftLauncher.userName = resp.selectedProfile().name();
                            config.userName = resp.selectedProfile().name();
                        }
                        config.save();
                    } catch (Exception e) {
                        // proceed — authlib-injector can resolve profile from token
                        e.printStackTrace();
                    }

                    Loader loader = new Loader(minecraftLauncher);
                    loader.loadPatch(prop("authlibInjectorVersion"));
                    loader.loadFull(prop("minecraftVersion"));
                    loader.loadPatch(prop("fabricVersion"));
                    minecraftLauncher.launch();
                } catch (Exception exc) {
                    exc.printStackTrace();
                    javafx.application.Platform.runLater(() -> {
                        stageLabel.setText("Ошибка запуска");
                        stepLabel.setText(exc.getMessage());
                    });
                }
            });
        } catch (Exception exc) {
            stageLabel.setText("Ошибка инициализации");
            stepLabel.setText(exc.getMessage());
        }
    }

    @FXML
    private void onLogoutPressed() throws IOException{
        stopMinecraft();
        try {
            AuthServer auth = new AuthServer();
            auth.invalidate(config.accessToken, config.clientId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        config.accessToken = "";
        config.uuid = "";
        config.userName = null;
        config.save();
        switchToAuth();
    }

    public void stopMinecraft() {
        MinecraftSession.getInstance().stop();
    }

    @FXML
    private void playOrUpdate() {
        // Реальный обработчик назначается через mainButton.setOnAction(...)
        // после завершения checkUpdates(); до этого кнопка disabled.
    }

    @FXML
    private void switchToAuth() throws IOException {
        App.setRoot("auth");
    }

    @FXML
    private void switchToSettings() throws IOException {
        App.setRoot("settings");
    }

    @FXML
    private void exitProgram() {
        Platform.exit();
    }
}

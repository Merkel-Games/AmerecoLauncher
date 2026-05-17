package ru.amereco.amerecolauncher;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import ru.amereco.amerecolauncher.httpsync.HTTPSync;
import ru.amereco.amerecolauncher.minecraft.MinecraftLauncher;
import ru.amereco.amerecolauncher.minecraft.MinecraftSession;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.WindowEvent;
import ru.amereco.amerecolauncher.minecraft.Loader;
import ru.amereco.amerecolauncher.minecraft.MinecraftDownloader;
import ru.amereco.amerecolauncher.minecraft.fabric.FabricDownloader;
import ru.amereco.amerecolauncher.utils.ProgressData;
import ru.amereco.amerecolauncher.minecraft.authlibinjector.AuthlibInjectorDownloader;

public class MainController {
    public enum UpdateNeeded {
        MINECRAFT, FABRIC, AUTHLIB_INJECTOR, HTTPSYNC;
        public static final EnumSet<UpdateNeeded> ALL_OPTS = EnumSet.allOf(UpdateNeeded.class);
    }
    
    private final Config config = Config.get();
    private HTTPSync httpSync;
    private MinecraftDownloader minecraftDownloader;
    private FabricDownloader fabricDownloader;
    private AuthlibInjectorDownloader authlibInjectorDownloader;

    private EnumSet<UpdateNeeded> updateNeeded = EnumSet.noneOf(UpdateNeeded.class);
    
    @FXML private StackPane root;
    @FXML private Button mainButton;
    @FXML private Button settingsBtn;
    @FXML private Button logoutBtn;
    @FXML private Button quitBtn;
    @FXML private ProgressBar progressBar;
    @FXML private Label stageLabel;
    @FXML private Label stepLabel;
    @FXML private Label progressLabel;
    @FXML private ImageView backgroundImage;

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
        
        // Initialize HTTPSync
        String baseUrl = "https://lanode.augmeneco.ru/rpcraft_dist/instances/rpcraft/";
        String configUrl = "https://lanode.augmeneco.ru/rpcraft_dist/instances/rpcraft/rpcraft.json";
        Path basePath = Path.of(config.mainDir, "instances/rpcraft/");
        Path configPath = Path.of(config.mainDir, "instances/rpcraft/rpcraft.json");
        httpSync = new HTTPSync(configUrl, baseUrl, configPath, basePath, 5000, 3000);
        httpSync.setOnProgress(this::handleProgressUpdate);
        
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
        showProgress();
        new Thread(() -> {
            try {
                if (minecraftDownloader.checkUpdates("1.20.1"))
                    updateNeeded.add(UpdateNeeded.MINECRAFT);
                if (fabricDownloader.checkUpdates("1.20.1-fabric-0.19.1"))
                    updateNeeded.add(UpdateNeeded.FABRIC);
                if (authlibInjectorDownloader.checkUpdates("authlib-injector-1.2.7"))
                    updateNeeded.add(UpdateNeeded.AUTHLIB_INJECTOR);
                if (httpSync.checkUpdates("")) 
                    updateNeeded.add(UpdateNeeded.HTTPSYNC);
                                     
                javafx.application.Platform.runLater(() -> {
                    hideProgress();
                    if (MinecraftSession.getInstance().isRunning()) {
                        return;
                    }
                    if (!updateNeeded.isEmpty()) {
                        mainButton.setText("Обновить");
                        mainButton.setOnAction(e -> startUpdate());
                    } else {
                        mainButton.setText("Играть");
                        mainButton.setOnAction(e -> launchMinecraft());
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                javafx.application.Platform.runLater(() -> {
                    stageLabel.setText("Ошибка проверки обновлений");
                    stepLabel.setText(e.getMessage());
                });
            }
        }).start();
    }

    private void startUpdate() {
        mainButton.setDisable(true);
        showProgress();
        new Thread(() -> {
            try {
                if (updateNeeded.contains(UpdateNeeded.MINECRAFT))
                    minecraftDownloader.download("1.20.1");
                if (updateNeeded.contains(UpdateNeeded.FABRIC))
                    fabricDownloader.download("1.20.1-fabric-0.19.1");
                if (updateNeeded.contains(UpdateNeeded.AUTHLIB_INJECTOR))
                    authlibInjectorDownloader.download("authlib-injector-1.2.7");
                if (updateNeeded.contains(UpdateNeeded.HTTPSYNC))
                    httpSync.download("");
                
                javafx.application.Platform.runLater(() -> {
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
        }).start();
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
            }));

            session.launch(() -> {
                try {
                    MinecraftLauncher minecraftLauncher = new MinecraftLauncher(
                        Path.of(mainDir),
                        Path.of(gameDir)
                    );

                    try {
                        AuthServer auth = new AuthServer();
                        var resp = auth.refresh(config.accessToken, config.clientId, null);
                        config.accessToken = resp.accessToken();
                        if (resp.selectedProfile() != null)
                            minecraftLauncher.userName = resp.selectedProfile().name();
                        config.save();
                    } catch (Exception e) {
                        // proceed — authlib-injector can resolve profile from token
                    }

                    minecraftLauncher.uuid = config.uuid;
                    minecraftLauncher.accessToken = config.accessToken;
                    minecraftLauncher.clientId = config.clientId;

                    Loader loader = new Loader(minecraftLauncher);
                    loader.loadPatch("authlib-injector-1.2.7");
                    loader.loadFull("1.20.1");
                    loader.loadPatch("1.20.1-fabric-0.19.1");
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
            // proceed — authlib-injector can resolve profile from token
        }
        config.accessToken = "";
        config.uuid = "";
        config.save();
        switchToAuth();
    }

    public void stopMinecraft() {
        MinecraftSession.getInstance().stop();
    }
    
    @FXML
    private void playOrUpdate() {
        // Will be implemented based on current state
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

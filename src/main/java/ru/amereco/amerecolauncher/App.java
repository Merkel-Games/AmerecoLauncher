package ru.amereco.amerecolauncher;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import javafx.scene.image.Image;

/**
 * JavaFX App
 */
public class App extends Application {

    /**
     * Как часто перепроверять наличие новой версии лаунчера.
     *
     * <p>30 минут вместо 5: проверка ходит на github.com, и хотя основной путь
     * (редирект {@code /releases/latest}) лимитов не имеет, запасной — через
     * api.github.com с его 60 запросами в час на IP. При получасовом интервале
     * даже запасной путь даёт 2 запроса в час на пользователя, то есть под общим
     * NAT лимит выдержит три десятка одновременно запущенных лаунчеров.
     */
    private static final Duration LAUNCHER_CHECK_INTERVAL = Duration.minutes(30);

    private static Scene scene;

    /**
     * Один обновлятор на всё приложение: проверка идёт по таймеру независимо от
     * того, какой экран открыт, поэтому владеть им должен App, а не контроллер.
     */
    private static LauncherUpdater launcherUpdater;

    /**
     * Общий обновлятор лаунчера. Контроллер экрана может подписаться на его
     * прогресс через {@code setOnProgress()}, пока этот экран открыт.
     */
    public static synchronized LauncherUpdater launcherUpdater() {
        if (launcherUpdater == null) {
            launcherUpdater = new LauncherUpdater();
        }
        return launcherUpdater;
    }

    @Override
    public void start(Stage stage) throws IOException {
        stage.setMinHeight(550);
        stage.setMinWidth(640);
        stage.setTitle("Amereco Launcher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("images/logo.png")));
        
        Config config = Config.get();

        scene = new Scene(loadFXML("auth"), 500, 500);
        stage.setScene(scene);
        stage.show();

        startLauncherUpdateChecks();
    }

    /**
     * Проверяет обновление лаунчера сразу после запуска и дальше каждые
     * {@link #LAUNCHER_CHECK_INTERVAL} — чтобы постоянно открытый лаунчер
     * не оставался на старой версии.
     *
     * <p>Таймер живёт на уровне приложения и не зависит от текущего экрана.
     * {@code checkAndUpdate()} сразу уходит в фоновый поток, так что FX-поток
     * не блокируется, а повторные диалоги по одной и той же версии
     * {@link LauncherUpdater} подавляет сам.
     */
    private void startLauncherUpdateChecks() {
        launcherUpdater().checkAndUpdate();

        Timeline checks = new Timeline(new KeyFrame(LAUNCHER_CHECK_INTERVAL, e -> {
            // Не предлагаем перезапуск посреди скачивания игровых файлов.
            if (MainController.isUpdatingAssets()) {
                return;
            }
            launcherUpdater().checkAndUpdate();
        }));
        checks.setCycleCount(Animation.INDEFINITE);
        checks.play();
    }

    @Override
    public void stop() {
        // Останавливаем сессию Minecraft при выходе, чтобы не оставить процесс-сироту.
        try {
            ru.amereco.amerecolauncher.minecraft.MinecraftSession.getInstance().stop();
        } catch (Exception ignored) {
        }
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }

}
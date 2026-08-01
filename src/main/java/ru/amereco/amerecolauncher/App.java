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
import ru.amereco.amerecolauncher.diag.CrashDialog;
import ru.amereco.amerecolauncher.diag.CrashReport;
import ru.amereco.amerecolauncher.diag.CrashReporter;

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

        installFxCrashHandler();

        scene = new Scene(loadFXML("auth"), 500, 500);
        stage.setScene(scene);
        stage.show();

        startLauncherUpdateChecks();
        startDiagnostics();
    }

    /**
     * Вешает перехватчик исключений на поток JavaFX.
     *
     * <p>Общего {@code Thread.setDefaultUncaughtExceptionHandler} здесь недостаточно:
     * исключения, вылетевшие из обработчиков событий и нативных callback'ов JavaFX,
     * идут через <b>персональный</b> обработчик потока, а не через общий по умолчанию.
     * Без этой строки падения в обработчиках кнопок остались бы незамеченными.
     *
     * <p>Этого обработчика достаточно и для всех {@code Platform.runLater} по коду:
     * вопреки расхожему мнению, {@code runLater} исключения не глотает — обёртка
     * {@code PlatformImpl.lambda$runLater$11} их перебрасывает (проверено по
     * байткоду javafx-graphics 21.0.8). Глотает только {@code runAndWait}, вызванный
     * из FX-потока, но его в проекте нет.
     */
    private void installFxCrashHandler() {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) ->
                CrashReporter.report(error, CrashReport.Kind.LAUNCHER_EXCEPTION, thread));
    }

    /**
     * Включает диагностику после появления окна.
     *
     * <p>Порядок важен: сначала сообщаем, что интерфейс готов, потом досылаем очередь
     * и только затем спрашиваем про накопившиеся отчёты. Иначе диалог попытался бы
     * открыться раньше главного окна.
     */
    private void startDiagnostics() {
        CrashReporter.setUiReady(true);
        CrashReporter.setPendingListener(CrashDialog::promptForPending);
        CrashReporter.flushAsync();

        if (!CrashReporter.pendingReports().isEmpty()) {
            CrashDialog.promptForPending();
        }
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
        // Отчёт, снятый прямо перед закрытием, иначе уехал бы только в следующий
        // запуск. Ждём недолго: закрытие лаунчера важнее доставки диагностики.
        try {
            CrashReporter.setUiReady(false);
            CrashReporter.flush(3000);
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
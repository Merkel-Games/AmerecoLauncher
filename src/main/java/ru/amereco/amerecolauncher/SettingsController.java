package ru.amereco.amerecolauncher;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import ru.amereco.amerecolauncher.diag.CrashReporter;
import ru.amereco.amerecolauncher.minecraft.JvmTuning;
import ru.amereco.amerecolauncher.minecraft.MinecraftDownloader;
import ru.amereco.amerecolauncher.minecraft.authlibinjector.AuthlibInjectorDownloader;
import ru.amereco.amerecolauncher.minecraft.fabric.FabricDownloader;
import ru.amereco.amerecolauncher.utils.AtomicFiles;
import ru.amereco.amerecolauncher.utils.Downloader;

/**
 * FXML Controller class
 *
 * @author lanode
 */
public class SettingsController implements Initializable {

    private Config config;

    @FXML private TextField mainDirInput;
    @FXML private CheckBox quickPlayCheckbox;
    @FXML private CheckBox rpcraftCheckbox;
    @FXML private CheckBox rpcraftAdminCheckbox;
    @FXML private CheckBox rpcraftUltraCheckbox;
    @FXML private CheckBox memoryAutoCheckbox;
    @FXML private Slider memorySlider;
    @FXML private Label memoryLabel;
    @FXML private CheckBox diagConsentCheckbox;
    @FXML private CheckBox diagLogsCheckbox;

    private void load() {
        mainDirInput.setText(config.mainDir);
        quickPlayCheckbox.setSelected(config.features.getOrDefault("is_quick_play_multiplayer", true));
        rpcraftCheckbox.setSelected(config.features.getOrDefault("profile_rpcraft", true));
        rpcraftAdminCheckbox.setSelected(config.features.getOrDefault("profile_rpcraft_admin", false));
        rpcraftUltraCheckbox.setSelected(config.features.getOrDefault("profile_rpcraft_ultra", false));

        // Потолок — половина физической ОЗУ, но не больше 8 ГБ.
        memorySlider.setMax(Math.max(memorySlider.getMin(), JvmTuning.maxAllowedHeapMb()));

        boolean auto = config.maxMemoryMb <= 0;
        memoryAutoCheckbox.setSelected(auto);
        memorySlider.setValue(auto ? JvmTuning.recommendedHeapMb() : config.maxMemoryMb);
        memorySlider.setDisable(auto);
        updateMemoryLabel();

        diagConsentCheckbox.setSelected(config.diagConsent);
        diagLogsCheckbox.setSelected(config.diagSendLogs);
        diagLogsCheckbox.setDisable(!config.diagConsent);
    }

    private void save() {
        config.mainDir = mainDirInput.getText();
        config.features.put("is_quick_play_multiplayer", quickPlayCheckbox.isSelected());
        config.features.put("profile_rpcraft", rpcraftCheckbox.isSelected());
        config.features.put("profile_rpcraft_admin", rpcraftAdminCheckbox.isSelected());
        config.features.put("profile_rpcraft_ultra", rpcraftUltraCheckbox.isSelected());
        // 0 — признак «считать автоматически», см. JvmTuning.heapMb
        config.maxMemoryMb = memoryAutoCheckbox.isSelected() ? 0 : (int) memorySlider.getValue();

        boolean consent = diagConsentCheckbox.isSelected();
        // Отметку времени и версию текста ставим только в момент включения:
        // это доказательство того, под какой редакцией согласия оно дано.
        if (consent && !config.diagConsent) {
            config.diagConsentVersion = Config.properties.getProperty("diagConsentVersion");
            config.diagConsentTs = System.currentTimeMillis() / 1000L;
        }
        config.diagConsent = consent;
        config.diagSendLogs = consent && diagLogsCheckbox.isSelected();
    }

    private void updateMemoryLabel() {
        int mb = (int) memorySlider.getValue();
        memoryLabel.setText(memoryAutoCheckbox.isSelected()
                ? "Автоматически: " + mb + " МБ"
                : mb + " МБ");
    }

    /**
     * Initializes the controller class.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        config = Config.get();

        memoryAutoCheckbox.selectedProperty().addListener((obs, was, isAuto) -> {
            memorySlider.setDisable(isAuto);
            if (isAuto) {
                memorySlider.setValue(JvmTuning.recommendedHeapMb());
            }
            updateMemoryLabel();
        });
        memorySlider.valueProperty().addListener((obs, was, now) -> updateMemoryLabel());

        // Логи без согласия на отчёты не отправляются — тумблер вложенный.
        diagConsentCheckbox.selectedProperty().addListener((obs, was, on) -> {
            diagLogsCheckbox.setDisable(!on);
            if (!on) {
                diagLogsCheckbox.setSelected(false);
            }
        });

        load();
    }

    /**
     * Ручная отправка отчёта: игрок описывает проблему сам.
     *
     * <p>Отдельный канал от автоматического: он нужен, когда лаунчер не падает,
     * но ведёт себя не так, и работает независимо от согласия на автоотправку —
     * нажатие кнопки и есть согласие на этот конкретный отчёт.
     */
    @FXML
    private void sendManualReport() {
        TextArea comment = new TextArea();
        comment.setPromptText("Что произошло? Что вы делали перед этим?");
        comment.setWrapText(true);
        comment.setPrefRowCount(6);

        CheckBox attachLog = new CheckBox("Приложить лог лаунчера");
        attachLog.setSelected(true);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Отчёт разработчику");
        dialog.setHeaderText("Опишите проблему — отчёт уйдёт на amereco.ru");
        dialog.getDialogPane().setContent(new VBox(10, comment, attachLog));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String text = comment.getText();
        if (text == null || text.isBlank()) {
            new Alert(Alert.AlertType.WARNING,
                    "Пустой отчёт отправлять нет смысла — опишите проблему.",
                    ButtonType.OK).showAndWait();
            return;
        }
        CrashReporter.reportManual(text, attachLog.isSelected());
        new Alert(Alert.AlertType.INFORMATION,
                "Спасибо, отчёт поставлен в очередь на отправку.",
                ButtonType.OK).showAndWait();
    }

    @FXML
    private void switchToMain() throws IOException {
        String dir = mainDirInput.getText();
        if (dir == null || dir.isBlank()) {
            new Alert(Alert.AlertType.ERROR, "Укажите папку установки", ButtonType.OK).showAndWait();
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Недоступный путь: " + e.getMessage(), ButtonType.OK).showAndWait();
            return;
        }
        save();
        config.save();
        App.setRoot("main");
    }

    @FXML
    private void selectMainDir() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Choose a folder");
        directoryChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        Stage stage = (Stage) mainDirInput.getScene().getWindow();
        File selectedFolder = directoryChooser.showDialog(stage);
        if (selectedFolder != null) {
            mainDirInput.setText(selectedFolder.getAbsolutePath());
        }
    }

    @FXML
    private void resetResources() {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "Перезагрузить все данные заново?\n\n"
                + "Будет удалено всё, что скачал лаунчер: сборка, моды, конфиги,\n"
                + "ресурспаки, библиотеки и файлы Minecraft.\n"
                + "Миры, скриншоты и логи останутся на месте.",
                ButtonType.YES, ButtonType.NO);
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }

        Thread t = new Thread(() -> {
            IOException failure = null;
            for (Downloader downloader : allDownloaders()) {
                try {
                    downloader.deleteDownloaded();
                } catch (IOException error) {
                    // Один загрузчик не смог — остальные всё равно чистим,
                    // иначе половина сборки осталась бы на диске.
                    Log.error("Удаление ресурсов: " + downloader.getClass().getSimpleName()
                            + " не справился", error);
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }

            final IOException reported = failure;
            Platform.runLater(() -> {
                if (reported == null) {
                    new Alert(Alert.AlertType.INFORMATION,
                            "Все скачанные файлы удалены.", ButtonType.OK).showAndWait();
                } else {
                    new Alert(Alert.AlertType.ERROR,
                            "Удалено не всё: " + reported.getMessage(),
                            ButtonType.OK).showAndWait();
                }
            });
        }, "reset-resources");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Сносит каталог лаунчера целиком, вместе с мирами, скриншотами и логами.
     *
     * <p>В отличие от {@link #resetResources()} тут не спрашивают, что откуда взялось:
     * удаляется всё содержимое {@code mainDir}. Это то, что нужно, когда каталог надо
     * вернуть в чистое состояние, а не просто перекачать сборку.
     *
     * <p>Каталог лаунчера выбирает пользователь, поэтому перед удалением проверяется,
     * что это не домашний каталог и не корень файловой системы: рекурсивное удаление
     * по такому пути — не «чистая установка», а потеря всех личных файлов.
     */
    @FXML
    private void wipeGameDirectory() {
        Path dir = Path.of(Config.get().mainDir).toAbsolutePath().normalize();

        String refusal = refuseToWipe(dir);
        if (refusal != null) {
            new Alert(Alert.AlertType.ERROR,
                    "Не буду удалять " + dir + ":\n" + refusal
                    + "\n\nУкажите отдельный каталог для игры и повторите.",
                    ButtonType.OK).showAndWait();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.WARNING,
                "Стереть каталог целиком?\n\n" + dir + "\n\n"
                + "Удалится всё его содержимое, включая миры, скриншоты и логи.\n"
                + "Это необратимо.",
                ButtonType.YES, ButtonType.NO);
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }

        Thread t = new Thread(() -> {
            try {
                AtomicFiles.deleteRecursively(dir);
                Files.createDirectories(dir);
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                        "Каталог очищен.", ButtonType.OK).showAndWait());
            } catch (IOException error) {
                Log.error("Полная очистка " + dir + " не удалась", error);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Не удалось стереть каталог: " + error.getMessage(),
                        ButtonType.OK).showAndWait());
            }
        }, "wipe-game-directory");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Почему по этому пути нельзя рекурсивно удалять, или {@code null}, если можно.
     *
     * <p>Отсекаются только очевидно катастрофические случаи — те, которые никто не имеет
     * в виду под «стереть каталог игры».
     */
    static String refuseToWipe(Path dir) {
        if (dir.getParent() == null) {
            return "это корень файловой системы";
        }
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        if (dir.equals(home)) {
            return "это ваш домашний каталог";
        }
        for (String name : new String[]{"Desktop", "Documents", "Downloads", "Рабочий стол", "Документы", "Загрузки"}) {
            if (dir.equals(home.resolve(name))) {
                return "это личный каталог «" + name + "»";
            }
        }
        return null;
    }

    /**
     * Все загрузчики лаунчера. Каждый знает, что скачал он сам, и удаляет только это —
     * поэтому каталог лаунчера, который выбирает пользователь, не сносится целиком
     * вместе с тем, что он мог туда положить.
     *
     * <p>Профили берутся все, а не только включённые: у отключённого оверлея файлы
     * тоже могли остаться.
     */
    private List<Downloader> allDownloaders() {
        List<Downloader> downloaders = new ArrayList<>();
        downloaders.add(new MinecraftDownloader());
        downloaders.add(new FabricDownloader());
        downloaders.add(new AuthlibInjectorDownloader());
        for (String profile : Profiles.all()) {
            downloaders.add(Profiles.sync(config, profile));
        }
        return downloaders;
    }
}

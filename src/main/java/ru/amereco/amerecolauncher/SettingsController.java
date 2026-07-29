package ru.amereco.amerecolauncher;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import ru.amereco.amerecolauncher.minecraft.JvmTuning;

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
    }

    private void save() {
        config.mainDir = mainDirInput.getText();
        config.features.put("is_quick_play_multiplayer", quickPlayCheckbox.isSelected());
        config.features.put("profile_rpcraft", rpcraftCheckbox.isSelected());
        config.features.put("profile_rpcraft_admin", rpcraftAdminCheckbox.isSelected());
        config.features.put("profile_rpcraft_ultra", rpcraftUltraCheckbox.isSelected());
        // 0 — признак «считать автоматически», см. JvmTuning.heapMb
        config.maxMemoryMb = memoryAutoCheckbox.isSelected() ? 0 : (int) memorySlider.getValue();
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

        load();
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
                "Перезагрузить все данные заного?\nВсе файлы будут удалены.",
                ButtonType.YES, ButtonType.NO);
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }

        Path dir = Path.of(Config.get().mainDir);
        Thread t = new Thread(() -> {
            try {
                deleteRecursively(dir);
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                        "Все ресурсы удалены.", ButtonType.OK).showAndWait());
            } catch (IOException e) {
                e.printStackTrace();
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Не удалось удалить ресурсы: " + e.getMessage(), ButtonType.OK).showAndWait());
            }
        }, "reset-resources");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

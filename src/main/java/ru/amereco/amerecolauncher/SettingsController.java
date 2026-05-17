/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/javafx/FXMLController.java to edit this template
 */
package ru.amereco.amerecolauncher;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * FXML Controller class
 *
 * @author lanode
 */
public class SettingsController implements Initializable {
    
    private Config config;

    @FXML private TextField mainDirInput;
    @FXML private CheckBox quickPlayCheckbox;

    private void load() {
        mainDirInput.setText(config.mainDir);
        quickPlayCheckbox.setSelected(config.features.getOrDefault("is_quick_play_multiplayer", true));
    }

    private void save() {
        config.mainDir = mainDirInput.getText();
        config.features.put("is_quick_play_multiplayer", quickPlayCheckbox.isSelected());
    }

    /**
     * Initializes the controller class.
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        config = Config.get();
        load();
    }

    @FXML
    private void switchToMain() throws IOException {
        save();
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
    private void resetResources() throws IOException {
        Alert alert = new Alert(Alert.AlertType.WARNING, "Перезагрузить все данные заного?\nВсе файлы будут удалены.", ButtonType.YES, ButtonType.NO);
        if (alert.showAndWait().get() == ButtonType.YES) {
            Files.deleteIfExists(Path.of(Config.get().mainDir));    
        }
    }
}

package ru.amereco.amerecolauncher;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class AuthController {
    Config config = Config.get();
    
    @FXML private TextField loginInput;
    @FXML private TextField passwordInput;
    
    @FXML
    public void initialize() throws IOException {
        if (config.username != null && !config.username.isEmpty())
            javafx.application.Platform.runLater(() -> {
                try {
                    switchToMain();
                } catch (Exception exc) {
                    exc.printStackTrace();
                }
            });
    }
    
    @FXML
    private void onLoginPressed() throws IOException {
        config.username = loginInput.getText();
        config.save();
        switchToMain();
    }
    
    // @FXML
    // private login()

    @FXML
    private void switchToMain() throws IOException {
        App.setRoot("main");
    }
}

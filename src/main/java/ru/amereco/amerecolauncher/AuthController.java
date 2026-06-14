package ru.amereco.amerecolauncher;

import java.io.IOException;
import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

public class AuthController {
    Config config = Config.get();

    @FXML private TextField loginInput;
    @FXML private TextField passwordInput;
    @FXML private Label errorLabel;
    @FXML private Button primaryButton;

    @FXML
    public void initialize() {
        if (config.accessToken != null && !config.accessToken.isEmpty()) {
            try {
                AuthServer auth = new AuthServer();
                auth.validate(config.accessToken, config.clientId);
            } catch (Exception e) {
                return;
            }
            Platform.runLater(() -> {
                try {
                    switchToMain();
                } catch (Exception exc) {
                    exc.printStackTrace();
                }
            });
        }

        loginInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                onLoginPressed();
            }
        });

        passwordInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                onLoginPressed();
            }
        });

        primaryButton.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                onLoginPressed();
            }
        });
    }

    @FXML
    private void onLoginPressed() {
        String username = loginInput.getText().trim();
        String password = passwordInput.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter login and password");
            return;
        }

        primaryButton.setDisable(true);
        errorLabel.setVisible(false);

        new Thread(() -> {
            try {
                AuthServer auth = new AuthServer();
                var resp = auth.authenticate(username, password, config.clientId);

                Platform.runLater(() -> {
                    config.accessToken = resp.accessToken();
                    config.uuid = resp.selectedProfile() != null
                            ? resp.selectedProfile().id() : null;
                    config.save();

                    try {
                        switchToMain();
                    } catch (IOException e) {
                        showError("Failed to switch screen: " + e.getMessage());
                    }
                });
            } catch (AuthServer.YggdrasilException e) {
                Platform.runLater(() -> showError(e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showError("Connection error: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> primaryButton.setDisable(false));
            }
        }).start();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    @FXML
    private void switchToMain() throws IOException {
        App.setRoot("main");
    }
}

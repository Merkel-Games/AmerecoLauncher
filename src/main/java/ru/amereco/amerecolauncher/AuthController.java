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

        // Проверку токена выполняем в фоне, а не на FX-потоке, иначе медленный/зависший
        // сервер подвешивает весь UI при запуске (окно не появляется).
        if (config.accessToken != null && !config.accessToken.isEmpty()) {
            new Thread(() -> {
                try {
                    new AuthServer().validate(config.accessToken, config.clientId);
                    Platform.runLater(this::trySwitchToMain);
                } catch (AuthServer.YggdrasilException e) {
                    // Токен невалиден/просрочен — пробуем refresh, иначе уводим на логин.
                    try {
                        var resp = new AuthServer().refresh(config.accessToken, config.clientId, null);
                        config.accessToken = resp.accessToken();
                        if (resp.selectedProfile() != null) {
                            config.uuid = resp.selectedProfile().id();
                            config.userName = resp.selectedProfile().name();
                        }
                        config.save();
                        Platform.runLater(this::trySwitchToMain);
                    } catch (Exception ex) {
                        config.accessToken = "";
                        config.uuid = "";
                        config.save();
                        Platform.runLater(() -> showError("Сессия истекла, войдите снова"));
                    }
                } catch (Exception e) {
                    // Сетевая ошибка — НЕ чистим токен, показываем сообщение.
                    Platform.runLater(() -> showError("Ошибка соединения: " + e.getMessage()));
                }
            }, "auth-validate").start();
        }
    }

    private void trySwitchToMain() {
        try {
            switchToMain();
        } catch (Exception exc) {
            exc.printStackTrace();
        }
    }

    @FXML
    private void onLoginPressed() {
        if (primaryButton.isDisable()) return; // защита от повторной отправки (в т.ч. по ENTER)

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
                    config.userName = resp.selectedProfile() != null
                            ? resp.selectedProfile().name() : null;
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

package ru.amereco.amerecolauncher;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import javafx.scene.image.Image;

/**
 * JavaFX App
 */
public class App extends Application {

    private static Scene scene;

    @Override
    public void start(Stage stage) throws IOException {
        stage.setMinHeight(550);
        stage.setMinWidth(640);
        stage.setTitle("Amereco Launcher");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("images/logo.png")));
        
        Config config = Config.get();
        
//        LauncherUpdater.checkAndUpdate();
        
        scene = new Scene(loadFXML("auth"), 500, 500);
        stage.setScene(scene);
        stage.show();
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
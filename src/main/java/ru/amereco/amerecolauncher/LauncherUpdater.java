/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import ru.amereco.amerecolauncher.httpsync.HTTPSync;

/**
 *
 * @author lanode
 */
public class LauncherUpdater {
    private static final String baseUrl = "https://amereco.ru/client_dist/launcher/";
    private static final String configUrl = "https://amereco.ru/client_dist/launcher/meta.json";
    private static final Path basePath = Path.of(Config.projectDir.dataLocalDir, "launcher/");
    private static final Path configPath = Path.of(Config.projectDir.dataLocalDir, "launcher/meta.json");
    private static final HTTPSync httpSync = new HTTPSync(configUrl, baseUrl, configPath, basePath, 5000, 3000);
    
    private static void restartApplication() throws IOException, URISyntaxException
    {
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
//        final File currentJar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
//
//        /* is it a jar file? */
//        if(!currentJar.getName().endsWith(".jar"))
//          return;

        /* Build command: java -jar application.jar */
        final ArrayList<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
//        command.add(currentJar.getPath());
        command.add(basePath.resolve("AmerecoLauncher.jar").toString());

        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.start();
        System.exit(0);
    }
    
    private static void update() {
        new Thread(() -> {
            try {
                httpSync.download();
                restartApplication();
            } catch (Exception exc) {
                exc.printStackTrace();
            }
        }).start();
    }
    
    private static void askToUpdate() {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(
            Alert.AlertType.CONFIRMATION, 
         "Доступно обновление лаунчера!\nОбновить?", 
            ButtonType.NO,
            ButtonType.YES
            );
            var response = alert.showAndWait();
            if (response.isPresent() && response.get()==ButtonType.YES) {
                update();
            }
        });
    }
    
    private static void install() {
        
    }
    
    public static void checkAndUpdate() {
        if (Files.exists(configPath)) {
            new Thread(() -> {
                try {
                    boolean needsUpdate = httpSync.checkUpdates();
                    if (needsUpdate) {
                        askToUpdate();
                    }
                } catch (Exception exc) {
                    exc.printStackTrace();
                }
            }).start();
        } else {
            
        }
    }
}

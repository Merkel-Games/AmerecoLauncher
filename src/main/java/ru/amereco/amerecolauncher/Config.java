package ru.amereco.amerecolauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import dev.dirs.ProjectDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class Config {

    private static final String CONFIG_FILE = "config.json";
    private static final Gson gson = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .setPrettyPrinting()
            .create();
    public static final ProjectDirectories projectDir = ProjectDirectories.from("ru", "amereco", "AmerecoLauncher");

    private static Config instance;

    @Expose
    public String mainDir;
    @Expose
    public String accessToken;
    @Expose
    public String clientId;
    @Expose
    public String javaDir;
    @Expose
    public int downloadThreadsCount;
    @Expose
    public String uuid;
    @Expose
    public String authlibApiUrl;

    private Config() {
        // Значения по умолчанию
        this.mainDir = Path.of(projectDir.dataLocalDir, ".minceraft").toString();
        this.accessToken = null;
        this.clientId = UUID.randomUUID().toString().replace("-", "");
        this.javaDir = System.getProperty("java.home");
        this.downloadThreadsCount = Runtime.getRuntime().availableProcessors();
        this.authlibApiUrl = "https://amereco.ru/wp-json/authlib-api/v1/yggdrasil/";
    }

    public static Config get() {
        if (instance == null) {
            load(); // lazy-load
        }
        return instance;
    }

    public static void load() {
        Path configPath = Path.of(projectDir.configDir, CONFIG_FILE);
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                instance = gson.fromJson(json, Config.class);
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
                instance = new Config(); // fallback
            }
        } else {
            instance = new Config(); // default config
        }
    }

    public void save() {
        if (instance == null) {
            instance = new Config();
        }

        Path configPath = Path.of(projectDir.configDir, CONFIG_FILE);
        try {
            Files.createDirectories(configPath.getParent());
            String json = gson.toJson(instance);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }
}

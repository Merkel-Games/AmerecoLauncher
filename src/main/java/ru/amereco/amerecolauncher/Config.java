package ru.amereco.amerecolauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import dev.dirs.ProjectDirectories;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class Config {

    private static final String CONFIG_FILE = "config.json";
    private static final Gson gson = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .setPrettyPrinting()
            .create();
    public static final ProjectDirectories projectDir = ProjectDirectories.from("ru", "amereco", "AmerecoLauncher");

    public static final Properties properties = new Properties();
    static {
        try (InputStream is = Config.class.getClassLoader()
                .getResourceAsStream("ru/amereco/amerecolauncher/application.properties")) {
            if (is == null) {
                throw new RuntimeException("Properties file not found in classpath");
            }
            properties.load(is);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    

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
    public Map<String, Boolean> features;

    private Config() {
        // Значения по умолчанию
        this.mainDir = Path.of(projectDir.dataLocalDir, ".minceraft").toString();
        this.uuid = null;
        this.accessToken = null;
        this.clientId = UUID.randomUUID().toString().replace("-", "");
        this.javaDir = System.getProperty("java.home");
        this.downloadThreadsCount = Runtime.getRuntime().availableProcessors();
        this.features = new HashMap<>();
        this.features.put("is_quick_play_multiplayer", true);
        this.features.put("profile_rpcraft", true);
        this.features.put("profile_rpcraft_admin", false);
        this.features.put("profile_rpcraft_ultra", false);
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

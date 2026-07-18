package ru.amereco.amerecolauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import dev.dirs.ProjectDirectories;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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
    public String userName;
    @Expose
    public Map<String, Boolean> features;

    private Config() {
        // Значения по умолчанию
        this.mainDir = Path.of(projectDir.dataLocalDir, ".minceraft").toString();
        this.uuid = null;
        this.accessToken = null;
        this.userName = null;
        this.clientId = UUID.randomUUID().toString().replace("-", "");
        this.javaDir = System.getProperty("java.home");
        this.downloadThreadsCount = Runtime.getRuntime().availableProcessors();
        this.features = new HashMap<>();
        normalize();
    }

    /**
     * Заполняет отсутствующие/некорректные поля значениями по умолчанию.
     *
     * <p>Gson инстанцирует объект в обход конструктора (через Unsafe), поэтому дефолты
     * из конструктора при загрузке НЕ применяются. Этот метод восстанавливает инварианты
     * после {@code fromJson}: без него старый/частичный/правленый {@code config.json}
     * приводит к null-полям и {@code downloadThreadsCount == 0}
     * (→ {@code newFixedThreadPool(0)} бросает исключение).
     */
    private void normalize() {
        if (mainDir == null || mainDir.isBlank()) {
            mainDir = Path.of(projectDir.dataLocalDir, ".minceraft").toString();
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = UUID.randomUUID().toString().replace("-", "");
        }
        if (javaDir == null || javaDir.isBlank()) {
            javaDir = System.getProperty("java.home");
        }
        if (downloadThreadsCount <= 0) {
            downloadThreadsCount = Runtime.getRuntime().availableProcessors();
        }
        if (features == null) {
            features = new HashMap<>();
        }
        features.putIfAbsent("is_quick_play_multiplayer", true);
        features.putIfAbsent("profile_rpcraft", true);
        features.putIfAbsent("profile_rpcraft_admin", false);
        features.putIfAbsent("profile_rpcraft_ultra", false);
    }

    public static synchronized Config get() {
        if (instance == null) {
            load(); // lazy-load
        }
        return instance;
    }

    public static synchronized void load() {
        Path configPath = Path.of(projectDir.configDir, CONFIG_FILE);
        Config loaded = null;
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                loaded = gson.fromJson(json, Config.class);
            } catch (Exception e) {
                // Ловим не только IOException, но и JsonSyntaxException (битый JSON) —
                // иначе повреждённый config.json роняет запуск лаунчера.
                System.err.println("Failed to load config: " + e.getMessage());
            }
        }
        if (loaded == null) {
            loaded = new Config(); // fallback (конструктор уже нормализует)
        } else {
            loaded.normalize(); // backfill отсутствующих полей после Gson
        }
        instance = loaded;
    }

    public synchronized void save() {
        if (instance == null) {
            instance = new Config();
        }

        Path configPath = Path.of(projectDir.configDir, CONFIG_FILE);
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(CONFIG_FILE + ".tmp");
            String json = gson.toJson(instance);
            Files.writeString(tmp, json);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(configPath);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Ограничивает доступ к файлу конфига до владельца (rw-------) на POSIX-системах.
     * Файл содержит живой access-токен, поэтому не должен быть world-readable (0644).
     * На не-POSIX ФС (например Windows) — тихо игнорируется.
     */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // не-POSIX ФС — пропускаем
        }
    }
}

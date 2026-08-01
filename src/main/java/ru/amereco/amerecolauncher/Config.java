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


    /** volatile: {@link #safe()} читает поле без блокировки. */
    private static volatile Config instance;

    /**
     * Идёт ли прямо сейчас {@link #load()} в этом потоке.
     *
     * <p>Нужно крашрепортеру: если исключение вылетело <b>внутри</b> загрузки конфига,
     * обращение к {@code get()} из обработчика ушло бы в бесконечную рекурсию
     * ({@code get} → {@code load} → падение → {@code get} → …).
     */
    private static final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
    /** Размер кучи игры в мегабайтах. {@code 0} — считать автоматически. */
    @Expose
    public int maxMemoryMb;
    /** Профиль сборщика мусора для игры: {@code auto}, {@code shenandoah}, {@code g1}, {@code off}. */
    @Expose
    public String gcProfile;
    /** Дополнительные аргументы JVM, через пробел. */
    @Expose
    public String extraJvmArgs;

    /**
     * Разрешена ли автоматическая отправка отчётов об ошибках.
     * По умолчанию {@code false}: согласие должно быть явным (opt-in), а не
     * подразумеваемым — иначе это не согласие.
     */
    @Expose
    public boolean diagConsent;

    /** Прикладывать ли к отчёту логи лаунчера и игры. Отдельный тумблер. */
    @Expose
    public boolean diagSendLogs;

    /**
     * Версия текста согласия, под которым игрок дал разрешение.
     * Если текст изменится, старое согласие перестанет считаться действующим.
     */
    @Expose
    public String diagConsentVersion;

    /** Когда согласие было дано, unix-время в секундах. */
    @Expose
    public long diagConsentTs;

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
        this.maxMemoryMb = 0;   // 0 => авто, normalize() оставит как есть
        this.gcProfile = "auto";
        this.extraJvmArgs = "";
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
        if (maxMemoryMb < 0) {
            maxMemoryMb = 0;   // отрицательное значение из правленого конфига => авто
        }
        if (gcProfile == null || gcProfile.isBlank()) {
            gcProfile = "auto";
        }
        if (extraJvmArgs == null) {
            extraJvmArgs = "";
        }
        features.putIfAbsent("is_quick_play_multiplayer", true);
        features.putIfAbsent("profile_rpcraft", true);
        features.putIfAbsent("profile_rpcraft_admin", false);
        features.putIfAbsent("profile_rpcraft_ultra", false);

        // Согласие, данное под другой редакцией текста, недействительно:
        // игрок соглашался на другой состав данных.
        String currentConsent = properties.getProperty("diagConsentVersion");
        if (diagConsent && currentConsent != null
                && !currentConsent.equals(diagConsentVersion)) {
            diagConsent = false;
            diagSendLogs = false;
        }

        registerSecrets();
    }

    /**
     * Сообщает {@link ru.amereco.amerecolauncher.diag.Redactor} значения, которые
     * нельзя выпускать ни в лог, ни в отчёт об ошибке.
     *
     * <p>Регулярки ловят секреты по имени ключа ({@code --accessToken abc}), но голый
     * токен внутри текста исключения так не поймать — только сравнением с живым
     * значением. Поэтому список пополняется здесь, при каждой загрузке и сохранении.
     */
    private void registerSecrets() {
        ru.amereco.amerecolauncher.diag.Redactor.addSecret(accessToken);
        ru.amereco.amerecolauncher.diag.Redactor.addSecret(clientId);
        ru.amereco.amerecolauncher.diag.Redactor.addSecret(uuid);
        // Имя пользователя ОС — отдельным механизмом: оно обычно короче порога
        // для литеральной замены, а подстрокой его вырезать нельзя.
        ru.amereco.amerecolauncher.diag.Redactor.addWordSecret(System.getProperty("user.name"));
    }

    public static synchronized Config get() {
        if (instance == null) {
            load(); // lazy-load
        }
        return instance;
    }

    /**
     * Конфиг, если он уже загружен, иначе попытка загрузить.
     *
     * <p>Отличие от {@link #get()} — метод никогда не бросает и никогда не уходит
     * в рекурсию: вызванный из обработчика ошибки, случившейся посреди загрузки,
     * он вернёт {@code null}, а не свалится в {@code StackOverflowError}.
     *
     * @return конфиг или {@code null}, если его сейчас нельзя получить
     */
    public static Config safe() {
        Config current = instance;
        if (current != null) {
            return current;
        }
        if (Boolean.TRUE.equals(loading.get())) {
            return null;
        }
        try {
            return get();
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void load() {
        loading.set(Boolean.TRUE);
        try {
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
        } finally {
            loading.set(Boolean.FALSE);
        }
    }

    public synchronized void save() {
        if (instance == null) {
            instance = new Config();
        }
        // Токен мог смениться после логина или обновления сессии — редактор должен
        // узнать новое значение раньше, чем оно попадёт в чей-нибудь стектрейс.
        instance.registerSecrets();

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

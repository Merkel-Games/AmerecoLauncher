package ru.amereco.amerecolauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.amereco.amerecolauncher.httpsync.HTTPSync;

/**
 * Профили клиентской сборки: какие бывают, где лежат и как до них достучаться.
 *
 * <p>Базовый профиль {@code rpcraft} — сама сборка, остальные накладываются поверх него
 * в тот же каталог инстанса, добавляя свои моды. Каждый профиль хранит собственный индекс
 * рядом с игровыми файлами.
 *
 * <p>Знание об этом собрано здесь, потому что нужно двум экранам сразу: главному
 * (проверка и загрузка обновлений) и настройкам (полное удаление скачанного).
 */
final class Profiles {
    /** Базовый профиль. Включён по умолчанию, остальные — нет. */
    static final String BASE = "rpcraft";

    private static final List<String> OVERLAYS = List.of("rpcraft-admin", "rpcraft-ultra");
    private static final String INSTANCE_DIR = "instances/rpcraft";
    private static final String DIST_URL = "https://amereco.ru/client_dist/instances/";

    private Profiles() {
    }

    /** Все известные профили: базовый первым, затем оверлеи в порядке наложения. */
    static List<String> all() {
        List<String> profiles = new ArrayList<>();
        profiles.add(BASE);
        profiles.addAll(OVERLAYS);
        return profiles;
    }

    /** Только оверлеи — те, что можно отключить. */
    static List<String> overlays() {
        return OVERLAYS;
    }

    /** Включённые профили в порядке наложения. */
    static List<String> active(Config config) {
        List<String> profiles = new ArrayList<>();
        for (String profile : all()) {
            if (isActive(config, profile)) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    static boolean isActive(Config config, String profile) {
        return config.features.getOrDefault(featureKey(profile), BASE.equals(profile));
    }

    /** Ключ в {@link Config#features}: {@code rpcraft-admin} → {@code profile_rpcraft_admin}. */
    static String featureKey(String profile) {
        return "profile_" + profile.replace('-', '_');
    }

    /** Каталог инстанса — общий у всех профилей. */
    static Path instanceDir(Config config) {
        return Path.of(config.mainDir, INSTANCE_DIR);
    }

    /** Локальный индекс профиля лежит рядом с игровыми файлами. */
    static Path indexPath(Config config, String profile) {
        return instanceDir(config).resolve(profile + ".json");
    }

    static HTTPSync sync(Config config, String profile) {
        String baseUrl = DIST_URL + profile + "/";
        String configUrl = baseUrl + profile + ".json";
        return new HTTPSync(configUrl, baseUrl, indexPath(config, profile),
                instanceDir(config), 5000, 3000, profile);
    }
}

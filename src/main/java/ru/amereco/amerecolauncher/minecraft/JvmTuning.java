package ru.amereco.amerecolauncher.minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.Log;

/**
 * Подбор JVM-аргументов для запуска игры.
 *
 * <p>Лаунчер исторически не задавал игре ни одного флага: работала эргономика JVM,
 * которая берёт под кучу четверть физической ОЗУ и никогда её не возвращает.
 * По одиннадцати краш-репортам клиента живой хип держался в пределах 0.5–2.1 ГБ,
 * тогда как закоммичено было от 3 до 8 ГБ.
 *
 * <p>Отсюда цель профилей: маленький {@code -Xms}, явный потолок {@code -Xmx}
 * и сборщик, который умеет возвращать неиспользуемые регионы операционной системе.
 *
 * <h3>Профили</h3>
 * <ul>
 *   <li>{@code shenandoah} — предпочтительный: uncommit через минуту простоя;</li>
 *   <li>{@code g1} — фолбэк, тот же эффект через периодический GC (JEP 346);</li>
 *   <li>{@code off} — не добавлять ничего, прежнее поведение.</li>
 * </ul>
 *
 * <p>Доступность сборщика зависит от сборки JRE и платформы, а нераспознанный флаг
 * означает не предупреждение, а отказ JVM стартовать. Поэтому набор флагов
 * проверяется предполётным запуском {@code java ... -version}, и при отказе
 * происходит переход к следующему профилю.
 */
public final class JvmTuning {

    /** Сколько ждём завершения пробного {@code java -version}. */
    private static final int PROBE_TIMEOUT_SECONDS = 20;

    /** Начальный размер кучи. Намеренно маленький — дальше растёт по потребности. */
    private static final String XMS = "-Xms512M";

    private static final List<String> SHENANDOAH = List.of(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseShenandoahGC",
            // Дефолт — 5 минут. Минута заметно быстрее возвращает память системе.
            "-XX:ShenandoahUncommitDelay=60000",
            // Без периодических циклов регионы не освобождаются и uncommit не наступает.
            "-XX:ShenandoahGuaranteedGCInterval=30000",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+PerfDisableSharedMem"
    );

    private static final List<String> G1 = List.of(
            // G1NewSizePercent — экспериментальный флаг, без разблокировки
            // JVM отказывается стартовать, а не просто предупреждает.
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20",
            "-XX:+ParallelRefProcEnabled",
            // JEP 346: в меню и при простое куча ужимается, память уходит ОС.
            "-XX:+G1PeriodicGCInvokesConcurrent",
            "-XX:G1PeriodicGCInterval=60000",
            "-XX:G1PeriodicGCSystemLoadThreshold=0",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:+PerfDisableSharedMem"
    );

    private JvmTuning() {
    }

    /**
     * Собирает аргументы JVM для игры, проверив их работоспособность на этой машине.
     *
     * @param config     конфиг лаунчера (профиль, память, дополнительные флаги)
     * @param executable путь к {@code java}, которым будет запущена игра
     * @return готовый список аргументов; пустой, если выбран профиль {@code off}
     *         или ни один набор не завёлся
     */
    public static List<String> build(Config config, String executable) {
        String requested = config.gcProfile == null ? "auto" : config.gcProfile.trim().toLowerCase();
        if ("off".equals(requested)) {
            Log.info("JVM: профиль off — аргументы не добавляются");
            return List.of();
        }

        int heapMb = heapMb(config);
        List<String> extra = parseExtraArgs(config.extraJvmArgs);

        for (String candidate : order(requested)) {
            List<String> flags = flagsOf(candidate, heapMb);
            if (flags.isEmpty()) {
                continue;
            }

            List<String> withExtra = new ArrayList<>(flags);
            withExtra.addAll(extra);

            if (probe(executable, withExtra)) {
                Log.info("JVM: профиль " + candidate + ", -Xmx" + heapMb + "M"
                        + (extra.isEmpty() ? "" : ", доп. аргументы: " + String.join(" ", extra)));
                return withExtra;
            }
            // Возможно, дело не в профиле, а в пользовательских аргументах.
            if (!extra.isEmpty() && probe(executable, flags)) {
                Log.info("JVM: профиль " + candidate + ", -Xmx" + heapMb
                        + "M; дополнительные аргументы отклонены JVM и не применены");
                return flags;
            }
            Log.info("JVM: профиль " + candidate + " не поддерживается этой JVM, пробуем следующий");
        }

        Log.info("JVM: ни один профиль не подошёл, запуск без дополнительных аргументов");
        return List.of();
    }

    /** Порядок перебора профилей для запрошенного значения. */
    private static List<String> order(String requested) {
        return switch (requested) {
            case "g1" -> List.of("g1");
            case "shenandoah" -> List.of("shenandoah", "g1");
            default -> List.of("shenandoah", "g1");
        };
    }

    private static List<String> flagsOf(String profile, int heapMb) {
        List<String> base = switch (profile) {
            case "shenandoah" -> SHENANDOAH;
            case "g1" -> G1;
            default -> List.of();
        };
        if (base.isEmpty()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        flags.add(XMS);
        flags.add("-Xmx" + heapMb + "M");
        errorFileFlag().ifPresent(flags::add);
        flags.addAll(base);
        return flags;
    }

    /**
     * Флаг {@code -XX:ErrorFile} — куда JVM игры запишет дамп фатальной ошибки.
     *
     * <p>Самые тяжёлые падения клиента (SIGSEGV в драйвере видеокарты) не оставляют
     * ни краш-репорта Minecraft, ни записи в {@code latest.log}: процесс умирает
     * мгновенно. Единственный след — этот дамп, и без явного пути он ляжет в рабочий
     * каталог игры, где лаунчер его не ищет.
     *
     * <p>{@code %p} подставляет PID, поэтому дампы разных запусков не затирают друг друга.
     */
    private static java.util.Optional<String> errorFileFlag() {
        java.nio.file.Path dir = ru.amereco.amerecolauncher.diag.CrashReporter.crashDumpDir();
        if (dir == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                "-XX:ErrorFile=" + dir.resolve("hs_err_pid%p.log").toAbsolutePath());
    }

    /** Размер кучи из настроек, либо авторасчёт, если пользователь не задавал. */
    public static int heapMb(Config config) {
        return config.maxMemoryMb > 0 ? config.maxMemoryMb : recommendedHeapMb();
    }

    /**
     * Рекомендуемый размер кучи по объёму физической памяти.
     *
     * <p>Замеренный пик живого хипа этой сборки модов — 2.1 ГБ, так что даже
     * верхняя ступень даёт почти двукратный запас.
     */
    public static int recommendedHeapMb() {
        long gb = physicalMemoryBytes() / (1024L * 1024 * 1024);
        int mb;
        if (gb <= 4) {
            mb = 1536;
        } else if (gb <= 8) {
            mb = 2560;
        } else if (gb <= 16) {
            mb = 3584;
        } else {
            mb = 4096;
        }
        return Math.min(mb, maxAllowedHeapMb());
    }

    /**
     * Потолок для настройки памяти: половина физической ОЗУ, но не больше 8 ГБ.
     * Игре нужна память и вне кучи — текстуры, буферы GL, нативные библиотеки.
     */
    public static int maxAllowedHeapMb() {
        long halfMb = physicalMemoryBytes() / (1024L * 1024) / 2;
        return (int) Math.max(1024, Math.min(8192, halfMb));
    }

    private static long physicalMemoryBytes() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                if (total > 0) {
                    return total;
                }
            }
        } catch (Throwable ignored) {
            // Нестандартная JVM — уходим на дефолт ниже.
        }
        return 8L * 1024 * 1024 * 1024;
    }

    private static List<String> parseExtraArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.trim().split("\\s+"));
    }

    /**
     * Проверяет, что JVM принимает набор флагов: запускает {@code java <флаги> -version}.
     *
     * <p>Нужно именно так, а не по списку известных платформ: наличие Shenandoah
     * зависит от конкретной сборки JRE, а пользователь может подменить {@code javaDir}.
     */
    private static boolean probe(String executable, List<String> flags) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(flags);
        command.add("-version");

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            // Вывод обязательно вычитываем: иначе переполнение буфера пайпа
            // подвесит и пробу, и лаунчер вместе с ней.
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }

            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}

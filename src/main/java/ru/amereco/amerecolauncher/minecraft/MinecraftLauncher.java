package ru.amereco.amerecolauncher.minecraft;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;
import ru.amereco.amerecolauncher.Config;

public class MinecraftLauncher {
    private Config config = Config.get();

    public Path mainDir;

    public String executable = Path.of(
        config.javaDir,
        "bin",
        (System.getProperty("os.name").toLowerCase().contains("windows")) ? "java.exe" : "java"
    ).toString();
    public List<Path> classPaths = new ArrayList<>();
    public String mainClass;
    public String userName = "pithrilla";
    public String userType = "mojang";
    public String version;
    public String versionType;
    public Path assetsDir;
    public String assetIndex;
    public Path gameDir;
    public Path nativesDir;
    public String uuid;
    public String accessToken;
    public String clientId;
    public List<String> gameArguments = new ArrayList<>();
    public List<String> jvmArguments = new ArrayList<>();

    public MinecraftLauncher(String executable, List<Path> classPaths, String mainClass,
            Path assetsDir, String assetIndex, Path gameDir, Path nativesDir) {
        this.executable = executable;
        this.classPaths = classPaths;
        this.mainClass = mainClass;
        this.assetsDir = assetsDir;
        this.assetIndex = assetIndex;
        this.gameDir = gameDir;
        this.nativesDir = nativesDir;
    }

    public MinecraftLauncher(Path mainDir, Path gameDir) throws IOException {
        this.mainDir = mainDir.toAbsolutePath();
        this.gameDir = gameDir.toAbsolutePath();
    }

    private static void put(Map<String, String> map, String key, String value) {
        // Не кладём null: иначе containsKey==true и в команду попадёт "null"
        // либо (для пар флаг+значение) значение теряется молча.
        if (value != null) {
            map.put(key, value);
        }
    }

    private List<String> getArgumentsString(List<String> arguments, Map<String, String> substitutes) {
        List<String> result = new ArrayList<>();
        Pattern placeholder = Pattern.compile("\\$\\{([^}]+)\\}"); // ловит любой ${key}

        for (int i = 0; i < arguments.size(); i++) {
            String current = arguments.get(i);

            // Пара: флаг (без ${…}) + следующий элемент — чистый плейсхолдер ${…}
            if (!placeholder.matcher(current).find()
                    && i + 1 < arguments.size()
                    && arguments.get(i + 1).matches("\\$\\{[^}]+\\}")) {

                String next = arguments.get(i + 1);
                Matcher m = placeholder.matcher(next);
                if (m.find()) {
                    String key = m.group(1);
                    if (substitutes.containsKey(key)) {
                        result.add(current);               // флаг
                        result.add(substitutes.get(key));  // значение
                    }
                    // если ключ неизвестен — ничего не добавляем (пара удаляется)
                }
                i++; // пропускаем обработанный плейсхолдер
                continue;
            }

            // Все остальные случаи: одиночный аргумент (может содержать ${…} внутри)
            Matcher matcher = placeholder.matcher(current);
            StringBuffer sb = new StringBuffer();
            boolean allKnown = true;
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!substitutes.containsKey(key)) {
                    allKnown = false;
                    break;  // нашли неразрешимый ключ – аргумент будет удалён
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(substitutes.get(key)));
            }
            if (allKnown) {
                matcher.appendTail(sb);
                result.add(sb.toString());
            }
            // иначе аргумент пропускается (удаляется)
        }
        return result;
    }

    public void launch() throws IOException, InterruptedException {
        Files.createDirectories(gameDir);

        Map<String, String> substitutes = new HashMap<>();
        put(substitutes, "classpath", classPaths.stream()
                                    .map(Path::toString)
                                    .collect(Collectors.joining(File.pathSeparator)));
        put(substitutes, "auth_player_name", userName);
        put(substitutes, "user_type", userType);
        put(substitutes, "version_name", version);
        put(substitutes, "version_type", versionType);
        put(substitutes, "assets_root", assetsDir != null ? assetsDir.toString() : null);
        put(substitutes, "assets_index_name", assetIndex);
        put(substitutes, "game_directory", gameDir.toString());
        put(substitutes, "natives_directory", nativesDir != null ? nativesDir.toString() : null);
        put(substitutes, "library_directory", mainDir != null ? mainDir.resolve("libraries").toString() : null);
        put(substitutes, "auth_uuid", uuid);
        put(substitutes, "auth_access_token", accessToken);
        put(substitutes, "clientid", clientId);
        put(substitutes, "launcher_name", Config.properties.getProperty("name"));
        put(substitutes, "launcher_version", Config.properties.getProperty("version"));
        put(substitutes, "quickPlayMultiplayer", Config.properties.getProperty("quickPlayMultiplayer"));

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(getArgumentsString(jvmArguments, substitutes));
        // Наши флаги идут ПОСЛЕ аргументов из version.json: при дублирующихся
        // -Xmx/-XX: побеждает последнее вхождение, значит настройки лаунчера
        // перекроют то, что могло прийти от Mojang или от патча authlib-injector.
        command.addAll(JvmTuning.build(config, executable));
        command.add(mainClass);
        command.addAll(getArgumentsString(gameArguments, substitutes));

        // Логируем команду, маскируя access-токен, чтобы он не утёк в stdout/логи.
        final String tokenVal = accessToken;
        System.out.println(command.stream()
                .map(a -> (tokenVal != null && !tokenVal.isEmpty()) ? a.replace(tokenVal, "***") : a)
                .collect(Collectors.joining("\n")));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir.toString()));

        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = builder.start();
        int exit = 0;
        try {
            exit = process.waitFor();
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        } finally {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            System.out.println("Minecraft terminated with exit code " + exit);
        }
    }
}

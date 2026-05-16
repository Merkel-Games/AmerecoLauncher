package ru.amereco.amerecolauncher.minecraft;

import java.io.*;
import java.nio.file.*;
import java.util.*;
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
        Properties props = new Properties();
        props.load(getClass().getClassLoader().getResourceAsStream("ru/amereco/amerecolauncher/application.properties"));

        Map<String, String> substitutes = new HashMap<>();
        substitutes.put("classpath", classPaths.stream()
                                    .map(Path::toString)
                                    // .map((p) -> "\""+p+"\"")
                                    .collect(Collectors.joining(File.pathSeparator)));
        substitutes.put("auth_player_name", userName);
        substitutes.put("user_type", userType);
        substitutes.put("version_name", version);
        substitutes.put("version_type", versionType);
        substitutes.put("assets_root", assetsDir.toString());
        substitutes.put("assets_index_name", assetIndex);
        substitutes.put("game_directory", gameDir.toString());
        substitutes.put("natives_directory", nativesDir.toString());
        substitutes.put("auth_uuid", uuid);
        substitutes.put("auth_access_token", accessToken);
        substitutes.put("clientid", clientId);
        substitutes.put("launcher_name", props.getProperty("name"));
        substitutes.put("launcher_version", props.getProperty("version"));
        substitutes.put("quickPlayMultiplayer", "lanode.augmeneco.ru:25565");

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(getArgumentsString(jvmArguments, substitutes));
        command.add(mainClass);
        command.addAll(getArgumentsString(gameArguments, substitutes));
        
        System.out.println(command.stream().collect(Collectors.joining("\n")));
        
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir.toString()));

        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
        
        Process process = builder.start();
        try {
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException exc) {
        
        } finally {
            process.destroy();
            System.out.println("Minecraft terminated!");
        }
    }
}

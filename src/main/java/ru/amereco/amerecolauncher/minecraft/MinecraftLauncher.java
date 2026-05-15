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

    private List<String> getArgumentsString(List<String> arguments, Map<String, String> subtitutes) {
        List<String> result = new ArrayList<>();
        String keysPattern = subtitutes.keySet().stream()
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));
        Pattern re = Pattern.compile("\\${(" + keysPattern + ")}");
        for (int i=0; i<arguments.size(); i++) {
            Matcher m1 = re.matcher(arguments.get(i));
            if (m1.find() && m1.start() > 0) {
                String key = m1.group(1);
                result.add(arguments.get(i).replace(key, subtitutes.get(key)));
            } else if (i+1 < arguments.size()) {
                Matcher m2 = re.matcher(arguments.get(i+1));
                if (m2.matches()) {
                    result.add(arguments.get(i));
                    result.add(subtitutes.get(m2.group(1)));
                }
            }
        }
        return result;
    }

    public void launch() throws IOException, InterruptedException {
        Properties props = new Properties();
        props.load(getClass().getClassLoader().getResourceAsStream("application.properties"));

        Map<String, String> subtitutes = new HashMap<>();
        subtitutes.put("classpath", classPaths.stream()
                                    .map(Path::toString)
                                    .map((p) -> "\""+p+"\"")
                                    .collect(Collectors.joining(File.pathSeparator)));
        subtitutes.put("auth_player_name", userName);
        subtitutes.put("user_type", userType);
        subtitutes.put("version_name", version);
        subtitutes.put("version_type", versionType);
        subtitutes.put("assets_root", assetsDir.toString());
        subtitutes.put("assets_index_name", assetIndex);
        subtitutes.put("game_directory", gameDir.toString());
        subtitutes.put("natives_directory", nativesDir.toString());
        subtitutes.put("auth_uuid", uuid);
        subtitutes.put("auth_access_token", accessToken);
        // subtitutes.put("clientid", clientId);
        subtitutes.put("clientid", "sssssss");
        subtitutes.put("launcher_name", props.getProperty("name"));
        subtitutes.put("launcher_version", props.getProperty("version"));

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(getArgumentsString(jvmArguments, subtitutes));
        command.add(mainClass);
        command.addAll(getArgumentsString(gameArguments, subtitutes));
        
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

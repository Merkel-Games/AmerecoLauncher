/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.amereco.amerecolauncher.minecraft.models.Version;
import ru.amereco.amerecolauncher.minecraft.models.Library;
import ru.amereco.amerecolauncher.minecraft.models.Rule;

/**
 *
 * @author lanode
 */
public class Loader {
    private Gson gson;
    private MinecraftLauncher minecraftLauncher;

    Path versionsDir;
    Path librariesDir;
    Path assetsDir;
    Path nativesDir;

    public Loader(MinecraftLauncher minecraftLauncher) {
        gson = new GsonBuilder().create();
        this.minecraftLauncher = minecraftLauncher;
        versionsDir = minecraftLauncher.mainDir.resolve("versions");
        librariesDir = minecraftLauncher.mainDir.resolve("libraries");
        assetsDir = minecraftLauncher.mainDir.resolve("assets");
        nativesDir = minecraftLauncher.mainDir.resolve("natives");
    }

    private List<Path> getClassPaths(Version version, Path librariesDir) {
        List<Path> paths = new ArrayList<>();

        for (Library library : version.libraries()) {
            if (!library.shouldUse()) continue;

            if (library.downloads() != null && library.downloads().artifact() != null) {
                String path = library.downloads().artifact().path();
                paths.add(librariesDir.resolve(path));
            }
        }
        return paths;
    }

    private Version loadVersion(Path versionsDir, String versionId) {
        Path jsonPath = versionsDir.resolve(versionId).resolve(versionId + ".json");
        try (JsonReader reader = new JsonReader(
                Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8))) {
            return gson.fromJson(reader, Version.class);
        } catch (IOException exc) {
            throw new RuntimeException("Failed to load version " + versionId, exc);
        }
    }

    private void loadClassPaths(Version version) {
        if (version.libraries() == null) return;
        List<Path> classPaths = getClassPaths(version, librariesDir);
        minecraftLauncher.classPaths.addAll(classPaths);
    }

    private void loadArguments(List<JsonElement> source, List<String> target) {
        if (source == null) return;

        for (JsonElement element : source) {
            if (element.isJsonPrimitive()) {
                target.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("rules")) {
                    List<Rule> rules = gson.fromJson(obj.get("rules"), new TypeToken<List<Rule>>(){}.getType());
                    if (!Rule.isAllowed(rules)) continue;
                }

                JsonElement value = obj.get("value");
                if (value.isJsonPrimitive()) {
                    target.add(value.getAsString());
                } else if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        target.add(item.getAsString());
                    }
                }
            }
        }
    }

    private void loadJvmArguments(Version version) {
        if (version.arguments() == null) return;
        loadArguments(version.arguments().jvm(), minecraftLauncher.jvmArguments);
    }

    private void loadGameArguments(Version version) {
        if (version.arguments() == null) return;
        loadArguments(version.arguments().game(), minecraftLauncher.gameArguments);
    }

    public void loadPatch(String versionId) {
        Version version = loadVersion(versionsDir, versionId);

        loadJvmArguments(version);
        loadGameArguments(version);
        loadClassPaths(version);
        if (version.mainClass() != null)
            minecraftLauncher.mainClass = version.mainClass();
    }

    public void loadFull(String versionId) throws IOException {
        Version version = loadVersion(versionsDir, versionId);

        loadJvmArguments(version);
        loadGameArguments(version);
        loadClassPaths(version);
        minecraftLauncher.classPaths.add(versionsDir.resolve(version.id()).resolve(version.id() + ".jar"));
        minecraftLauncher.mainClass = version.mainClass();
        minecraftLauncher.assetsDir = assetsDir;
        minecraftLauncher.assetIndex = version.assetIndex().id();
        minecraftLauncher.nativesDir = nativesDir;
        minecraftLauncher.version = version.id();
        minecraftLauncher.versionType = version.type();

        extractNatives(version);
    }

    /**
     * Распаковывает нативные библиотеки (classifier-артефакты) выбранные по текущей ОС/арх
     * в {@code nativesDir}. Для формата 1.19+ (нативы как обычные artifact-библиотеки)
     * поле {@code natives} отсутствует и метод ничего не делает.
     */
    private void extractNatives(Version version) throws IOException {
        if (version.libraries() == null) return;
        String osToken = osToken();
        if (osToken == null) return;

        Files.createDirectories(nativesDir);
        Path nativesRoot = nativesDir.toAbsolutePath().normalize();

        for (Library lib : version.libraries()) {
            if (!lib.shouldUse()) continue;
            if (lib.natives() == null) continue;
            String key = lib.natives().get(osToken);
            if (key == null) continue;
            key = key.replace("${arch}", archBits());

            if (lib.downloads() == null || lib.downloads().classifiers() == null) continue;
            Library.Downloads.LibraryDownload cl = lib.downloads().classifiers().get(key);
            if (cl == null || cl.path() == null) continue;

            Path jar = librariesDir.resolve(cl.path());
            if (!Files.exists(jar)) continue;

            List<String> exclude = (lib.extract() != null && lib.extract().exclude() != null)
                    ? lib.extract().exclude() : List.of();

            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (name.startsWith("META-INF/")) continue;
                    boolean skip = false;
                    for (String ex : exclude) {
                        if (name.startsWith(ex)) { skip = true; break; }
                    }
                    if (skip) continue;

                    Path out = nativesRoot.resolve(name).normalize();
                    if (!out.startsWith(nativesRoot)) continue; // защита от zip-slip
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static String osToken() {
        if (org.apache.commons.exec.OS.isFamilyWindows()) return "windows";
        if (org.apache.commons.exec.OS.isFamilyMac()) return "osx";
        if (org.apache.commons.exec.OS.isFamilyUnix()) return "linux";
        return null;
    }

    private static String archBits() {
        return System.getProperty("os.arch", "").contains("64") ? "64" : "32";
    }
}

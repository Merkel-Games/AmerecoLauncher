/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.fabric;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.utils.Downloader;
import ru.amereco.amerecolauncher.minecraft.fabric.models.FabricMeta;
import ru.amereco.amerecolauncher.minecraft.models.Library;
import ru.amereco.amerecolauncher.minecraft.models.Version;
import java.util.Collection;

/**
 *
 * @author lanode
 */
public class FabricDownloader extends Downloader {
    private static final String FABRIC_MAVEN_URL = "https://maven.fabricmc.net/";

    public List<FabricMeta> getFabricMeta(String versionId) throws IOException, InterruptedException {
        String response = httpGet(URI.create("https://meta.fabricmc.net/v2/versions/loader/"+versionId));
        FabricMeta[] mcArray = gson.fromJson(response, FabricMeta[].class);
        return new ArrayList<>(List.of(mcArray));
    }

    /**
     * Строит относительный путь артефакта по maven-координатам.
     * Всегда использует '/' как разделитель, чтобы одинаково работать на всех ОС
     * (иначе на Windows в version JSON попадали бы бэкслеши).
     * Example: "net.fabricmc:sponge-mixin:0.11.4+mixin.0.8.5"
     */
    private String getLibraryPath(String name) {
        String[] parts = name.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid library name format: " + name);

        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];

        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }

    public <T> List<T> union(List<T> list1, List<T> list2) {
        // LinkedHashSet: сохраняем порядок client→common и детерминированный classpath
        Set<T> set = new LinkedHashSet<>();
        set.addAll(list1);
        set.addAll(list2);
        return new ArrayList<>(set);
    }

    public Version metaToVersion(FabricMeta fabricMeta) {
        Version version = new Version(
            null,
            null,
            null,
            new ArrayList<>(),
            fabricMeta.launcherMeta().mainClass().client(),
            null,
            null
        );

        version.libraries().add(makeLibrary(fabricMeta.loader().maven()));
        version.libraries().add(makeLibrary(fabricMeta.intermediary().maven()));

        var librariesToDownload = union(fabricMeta.launcherMeta().libraries().client(),
                fabricMeta.launcherMeta().libraries().common());
        for (var lib : librariesToDownload) {
            version.libraries().add(makeLibrary(lib.name()));
        }

        return version;
    }

    private Library makeLibrary(String maven) {
        String path = getLibraryPath(maven);
        return new Library(
                maven,
                new Library.Downloads(
                        new Library.Downloads.LibraryDownload(
                                path,
                                null,
                                null,
                                URI.create(FABRIC_MAVEN_URL + path)
                        ),
                        null
                ),
                null,
                null,
                null
        );
    }

    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public boolean checkUpdates(String versionId) throws IOException, InterruptedException {
        Path mainDir = Path.of(Config.get().mainDir);
        return !Files.exists(mainDir.resolve("versions").resolve(versionId).resolve(versionId+".json"));
    }

    @Override
    public void download() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void download(String versionId) throws IOException, InterruptedException {
        updateStage("Загрузка Fabric");
        resetProgress(1);
        updateStepAndIncProgress(versionId+".json");

        var versionParts = versionId.split("-");
        if (versionParts.length < 3) {
            throw new IOException("Invalid Fabric version id (expected <mc>-fabric-<loader>): " + versionId);
        }
        String mainDir = Config.get().mainDir;

        var fabricMetas = getFabricMeta(versionParts[0]);
        var fabricMeta = fabricMetas.stream()
                .filter(e -> e.loader().version().equals(versionParts[2]))
                .findFirst()
                .orElseThrow(() -> new IOException("Fabric loader " + versionParts[2] + " not found"));

        updateStep("Преобразование FabricMeta в Version");

        var version = metaToVersion(fabricMeta);
        Path versionJsonPath = Path.of(mainDir, "versions", versionId, versionId+".json");
        Files.createDirectories(versionJsonPath.getParent());
        Files.writeString(versionJsonPath, gson.toJson(version), StandardCharsets.UTF_8);

        setFailDownloadHandler(((t) -> {
            try {
                t.printStackTrace();
                updateStage("Ошибка скачивания Fabric");
                updateStep(t.getMessage());
                Files.deleteIfExists(versionJsonPath);
            } catch (Exception exc) {
            }
        }));

        var librariesToDownload = union(fabricMeta.launcherMeta().libraries().client(),
                fabricMeta.launcherMeta().libraries().common());

        resetProgress(librariesToDownload.size()+2);

        var loaderPath = getLibraryPath(fabricMeta.loader().maven());
        downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+loaderPath), Path.of(mainDir, "libraries", loaderPath));

        var intermediaryPath = getLibraryPath(fabricMeta.intermediary().maven());
        downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+intermediaryPath), Path.of(mainDir, "libraries", intermediaryPath));

        for (var library : librariesToDownload) {
            var libraryPath = getLibraryPath(library.name());
            downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+libraryPath), Path.of(mainDir, "libraries", libraryPath));
        }

        waitUntilDownload();
    }

    /**
     * Fabric кладёт свой профиль версии и джарники в те же каталоги, что и ванильный
     * загрузчик. Пересечение намеренное: удаление идемпотентно, а точный список путей
     * без обращения к сети всё равно не построить.
     */
    @Override
    protected Collection<Path> managedPaths() {
        Path root = mainDir();
        return List.of(root.resolve("versions"), root.resolve("libraries"));
    }
}

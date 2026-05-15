/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.fabric;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.utils.Downloader;
import ru.amereco.amerecolauncher.minecraft.fabric.models.FabricMeta;
import ru.amereco.amerecolauncher.minecraft.models.Library;
import ru.amereco.amerecolauncher.minecraft.models.Version;

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
    
    private Path getLibraryPath(String name) {
        // Example: "net.fabricmc:sponge-mixin:0.11.4+mixin.0.8.5"
        String[] parts = name.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid library name format: " + name);

        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];

        String groupPath = group.replace('.', File.separatorChar);
        Path path = Paths.get(groupPath, artifact, version, artifact + "-" + version + ".jar");

        return path;
    }
    
    public <T> List<T> union(List<T> list1, List<T> list2) {
        Set<T> set = new HashSet<>();

        set.addAll(list1);
        set.addAll(list2);

        return new ArrayList<>(set);
    }
    
    public Version metaToVersion(FabricMeta fabricMeta) {
        Version version = new Version(
            null,
            null, 
            null, 
            new ArrayList(), 
            fabricMeta.launcherMeta().mainClass().client(),
            null, 
            null
        );
        
        version.libraries().add(new Library(
                fabricMeta.loader().maven(), 
                new Library.Downloads(
                    new Library.Downloads.LibraryDownload(
                        getLibraryPath(fabricMeta.loader().maven()).toString(),
                        null,
                        null,
                        URI.create(FABRIC_MAVEN_URL+getLibraryPath(fabricMeta.loader().maven()).toString().replace("\\", "/"))
                    ),
                    null
                ),
                null
        ));
        
        version.libraries().add(new Library(
                fabricMeta.intermediary().maven(), 
                new Library.Downloads(
                    new Library.Downloads.LibraryDownload(
                        getLibraryPath(fabricMeta.intermediary().maven()).toString(),
                        null,
                        null,
                        URI.create(FABRIC_MAVEN_URL+getLibraryPath(fabricMeta.intermediary().maven()).toString().replace("\\", "/"))
                    ),
                    null
                ),
                null
        ));
        
        var librariesToDownload = union(fabricMeta.launcherMeta().libraries().client(), fabricMeta.launcherMeta().libraries().common());
        for (var lib : librariesToDownload) {
            version.libraries().add(new Library(
                lib.name(), 
                new Library.Downloads(
                    new Library.Downloads.LibraryDownload(
                        getLibraryPath(lib.name()).toString(),
                        null,
                        null,
                        URI.create(FABRIC_MAVEN_URL+getLibraryPath(lib.name()).toString().replace("\\", "/"))
                    ),
                    null
                ),
                null
            ));
        }
        
        return version;
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
        maxProgress = 1;
        progress = 0;
        updateStepAndIncProgress(versionId+".json");
        
        var versionParts = versionId.split("-");
        String mainDir = Config.get().mainDir;
        
        var fabricMetas = getFabricMeta(versionParts[0]);
        var fabricMeta = fabricMetas.stream().filter(e -> e.loader().version().equals(versionParts[2])).findFirst().get();
        
        updateStep("Преобразование FabricMeta в Version");
        
        var version = metaToVersion(fabricMeta);
        Path versionJsonPath = Path.of(mainDir, "versions", versionId, versionId+".json");
        Files.createDirectories(versionJsonPath.getParent());
        Files.createFile(versionJsonPath);
        try (FileWriter out = new FileWriter(versionJsonPath.toString(), false)) {
            out.write(gson.toJson(version));
        }
        
        setFailDownloadHandler(((t) -> {
            try {
                t.printStackTrace();
                updateStage("Ошибка скачивания Fabric");
                updateStep(t.getMessage());
                Files.deleteIfExists(versionJsonPath);
            } catch (Exception exc) {
            }
        }));
        
        var librariesToDownload = union(fabricMeta.launcherMeta().libraries().client(), fabricMeta.launcherMeta().libraries().common());
        
        maxProgress = librariesToDownload.size()+2;
        progress = 0;
        
//        updateStepAndIncProgress(fabricMeta.loader().maven());
        var loaderPath = getLibraryPath(fabricMeta.loader().maven()).toString();
        downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+loaderPath.replace("\\", "/")), Path.of(mainDir, "libraries", loaderPath));
        
//        updateStepAndIncProgress(fabricMeta.intermediary().maven());
        var intermediaryPath = getLibraryPath(fabricMeta.intermediary().maven()).toString();
        downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+intermediaryPath.replace("\\", "/")), Path.of(mainDir, "libraries", intermediaryPath));
        
        for (var library : librariesToDownload) {
//            updateStepAndIncProgress(library.name());
            var libraryPath = getLibraryPath(library.name()).toString();
            downloadToPathInThread(URI.create(FABRIC_MAVEN_URL+libraryPath.replace("\\", "/")), Path.of(mainDir, "libraries", libraryPath));
        }
        
        waitUntilDownload();
    }
}

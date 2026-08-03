/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft;

import ru.amereco.amerecolauncher.utils.Downloader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.minecraft.models.Library;
import ru.amereco.amerecolauncher.minecraft.models.Version;
import ru.amereco.amerecolauncher.minecraft.models.VersionManifest;
import ru.amereco.amerecolauncher.minecraft.models.AssetIndex;
import java.util.Collection;
import java.util.List;

/**
 *
 * @author lanode
 */
public class MinecraftDownloader extends Downloader {
    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    public VersionManifest getVersionManifest() throws IOException, InterruptedException {
        String response = httpGet(URI.create(VERSION_MANIFEST_URL));
        return gson.fromJson(response, VersionManifest.class);
    }

    public Version getVersion(URI versionUrl) throws IOException, InterruptedException {
        String response = httpGet(versionUrl);
        return gson.fromJson(response, Version.class);
    }

    public AssetIndex getAssetIndex(URI assetIndexUrl) throws IOException, InterruptedException {
        String response = httpGet(assetIndexUrl);
        return gson.fromJson(response, AssetIndex.class);
    }

    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        var versionManifest = getVersionManifest();
        return checkUpdates(versionManifest.latest().release());
    }

    @Override
    public boolean checkUpdates(String versionId) throws IOException, InterruptedException {
        Path mainDir = Path.of(Config.get().mainDir);
        return !Files.exists(mainDir.resolve("versions").resolve(versionId).resolve(versionId+".json"));
    }

    @Override
    public void download() throws IOException, InterruptedException {
        var versionManifest = getVersionManifest();
        download(versionManifest.latest().release());
    }

    @Override
    public void download(String versionId) throws IOException, InterruptedException {
        updateStage("Загрузка Minecraft: библиотеки");
        resetProgress(1);
        updateStepAndIncProgress(versionId+".json");

        String mainDir = Config.get().mainDir;

        var versionManifest = getVersionManifest();
        var versionInfo = versionManifest.versions().stream()
                .filter(e -> e.id().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new IOException("Minecraft version not found: " + versionId));

        var version = getVersion(versionInfo.url());

        resetProgress(version.libraries().size()+3);

        Path versionPath = Path.of(mainDir, "versions", versionInfo.id(), versionInfo.id()+".json");
        setFailDownloadHandler(((t) -> {
            try {
                t.printStackTrace();
                updateStage("Ошибка скачивания Minecraft");
                updateStep(t.getMessage());
                Files.deleteIfExists(versionPath);
            } catch (Exception exc) {
            }
        }));

        downloadToPathInThread(versionInfo.url(), versionPath);

        var client = version.downloads().client();
        downloadToPathInThread(client.url(),
                Path.of(mainDir, "versions", versionId, versionId+".jar"),
                client.sha1(), client.size());

        for (var library : version.libraries()) {
            if (!library.shouldUse()) continue;

            if (library.downloads() != null && library.downloads().artifact() != null) {
                var art = library.downloads().artifact();
                updateStepAndIncProgress(library.name());
                downloadToPathInThread(art.url(),
                        Path.of(mainDir, "libraries", art.path()),
                        art.sha1(), art.size() == null ? -1 : art.size());
            }

            // Нативы старого формата (classifier-артефакты для текущей ОС/арх)
            if (library.natives() != null
                    && library.downloads() != null
                    && library.downloads().classifiers() != null) {
                String key = library.natives().get(osToken());
                if (key != null) {
                    key = key.replace("${arch}", archBits());
                    Library.Downloads.LibraryDownload cl = library.downloads().classifiers().get(key);
                    if (cl != null && cl.url() != null) {
                        downloadToPathInThread(cl.url(),
                                Path.of(mainDir, "libraries", cl.path()),
                                cl.sha1(), cl.size() == null ? -1 : cl.size());
                    }
                }
            }
        }

        updateStage("Загрузка Minecraft: ассеты");
        var assetIdx = version.assetIndex();
        downloadToPathInThread(assetIdx.url(),
                Path.of(mainDir, "assets", "indexes", assetIdx.id()+".json"),
                assetIdx.sha1(), assetIdx.size());

        var assetIndex = getAssetIndex(assetIdx.url());

        resetProgress(assetIndex.objects().size());

        for (Map.Entry<String, AssetIndex.Asset> entry : assetIndex.objects().entrySet()) {
            var hash = entry.getValue().hash();
            var folderName = hash.substring(0, 2);
            downloadToPathInThread(URI.create("https://resources.download.minecraft.net/"+folderName+"/"+hash),
                           Path.of(mainDir, "assets", "objects", folderName, hash),
                           hash, entry.getValue().size());
        }

        waitUntilDownload();
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

    /**
     * Ванильный загрузчик владеет всей игровой раскладкой: профили версий, ресурсы,
     * библиотеки и распакованные нативные библиотеки.
     */
    @Override
    protected Collection<Path> managedPaths() {
        Path root = mainDir();
        return List.of(
                root.resolve("versions"),
                root.resolve("assets"),
                root.resolve("libraries"),
                root.resolve("natives"));
    }
}

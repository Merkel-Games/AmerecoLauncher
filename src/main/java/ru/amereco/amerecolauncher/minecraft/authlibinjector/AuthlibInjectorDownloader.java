/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.authlibinjector;

import com.google.gson.JsonPrimitive;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ru.amereco.amerecolauncher.Config;
import ru.amereco.amerecolauncher.utils.Downloader;
import ru.amereco.amerecolauncher.minecraft.models.Version;

/**
 *
 * @author lanode
 */
public class AuthlibInjectorDownloader extends Downloader {
    private static final String AUTHLIB_INJECTOR_URL = "https://github.com/yushijinhun/authlib-injector/releases/download/v%s/authlib-injector-%s.jar";
    private static final String LIBRARY_PATH = "moe/yushi/authlib-injector/%s/authlib-injector-%s.jar";
    private static final String LIBRARY_NAME = "moe.yushi:authlib-injector:%s";

    @Override
    public boolean checkUpdates() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean checkUpdates(String versionId) throws IOException, InterruptedException {
        Path mainDir = Path.of(Config.get().mainDir);
        return !Files.exists(mainDir.resolve("versions").resolve(versionId).resolve(versionId + ".json"));
    }

    @Override
    public void download() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void download(String versionId) throws IOException, InterruptedException {
        updateStage("Загрузка Authlib-Injector");
        maxProgress = 1;
        progress = 0;
        updateStepAndIncProgress(versionId + ".json");

        String mainDir = Config.get().mainDir;
        String version = versionId.replace("authlib-injector-", "");

        String downloadUrl = String.format(AUTHLIB_INJECTOR_URL, version, version);
        String libraryPath = String.format(LIBRARY_PATH, version, version);
        String jarAbsolutePath = Path.of(mainDir, "libraries", libraryPath).toString();
        String apiUrl = Config.properties.getProperty("authlibApiUrl");
        Version versionObj = new Version(
            new Version.Arguments(
                null,
                List.of(
                    new JsonPrimitive("-javaagent:" + jarAbsolutePath + "=" + apiUrl),
                    new JsonPrimitive("-Dauthlibinjector.debug=verbose,authlib")
                )
            ),
            null,
            null,
            null,
            // List.of(new Library(
            //     String.format(LIBRARY_NAME, version),
            //     new Library.Downloads(
            //         new Library.Downloads.LibraryDownload(
            //             libraryPath,
            //             null,
            //             null,
            //             URI.create(downloadUrl)
            //         ),
            //         null
            //     ),
            //     null
            // )),
            null,
            null,
            null
        );

        Path versionJsonPath = Path.of(mainDir, "versions", versionId, versionId + ".json");
        Files.createDirectories(versionJsonPath.getParent());
        Files.createFile(versionJsonPath);
        try (FileWriter out = new FileWriter(versionJsonPath.toString(), false)) {
            out.write(gson.toJson(versionObj));
        }

        setFailDownloadHandler(((t) -> {
            try {
                t.printStackTrace();
                updateStage("Ошибка скачивания Authlib-Injector");
                updateStep(t.getMessage());
                Files.deleteIfExists(versionJsonPath);
            } catch (Exception exc) {
            }
        }));

        downloadToPathInThread(URI.create(downloadUrl), Path.of(mainDir, "libraries", libraryPath));

        waitUntilDownload();
    }
}

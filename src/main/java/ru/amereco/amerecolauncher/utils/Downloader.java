/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package ru.amereco.amerecolauncher.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import ru.amereco.amerecolauncher.Config;

/**
 *
 * @author lanode
 */
public abstract class Downloader extends ProgressSupplier {
    private Config config = Config.get();
    protected Gson gson;
    protected HttpClient httpClient;
    ThreadPoolExecutor executor;
    Consumer<Exception> failDownloadHandler;
    
    protected void setFailDownloadHandler(Consumer<Exception> handler) {
        failDownloadHandler = handler;
    }
    
    public Downloader() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(5000))
            .build();
        gson = new GsonBuilder().create();
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(config.downloadThreadsCount);
    }
    
    public abstract boolean checkUpdates() throws IOException, InterruptedException;
    public abstract boolean checkUpdates(String versionId) throws IOException, InterruptedException;
    public abstract void download() throws IOException, InterruptedException;
    public abstract void download(String versionId) throws IOException, InterruptedException;

    protected String httpGet(URI url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(url).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    protected void downloadToPath(URI url, Path path) throws IOException, InterruptedException {
        Files.createDirectories(path.getParent());
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
            .send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofFile(path));
    }
    
    protected void downloadToPathInThread(URI url, Path path) {
        executor.submit(() -> {
            try {
                downloadToPath(url, path);
                javafx.application.Platform.runLater(() -> {
                    updateStepAndIncProgress(path.toString());
                });
            } catch (Exception exc) {
                javafx.application.Platform.runLater(() -> {
                    executor.shutdownNow();
                    if (failDownloadHandler != null)
                        failDownloadHandler.accept(exc);
                });
            }
        });
    }
    
    protected void waitUntilDownload() {
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
        }
    }
}

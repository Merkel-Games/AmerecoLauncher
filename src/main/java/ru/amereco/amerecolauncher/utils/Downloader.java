/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package ru.amereco.amerecolauncher.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import ru.amereco.amerecolauncher.Config;

/**
 *
 * @author lanode
 */
public abstract class Downloader extends ProgressSupplier {
    private final Config config = Config.get();
    protected Gson gson;
    protected HttpClient httpClient;

    private ThreadPoolExecutor executor;
    private final AtomicReference<Exception> firstFailure = new AtomicReference<>();
    Consumer<Exception> failDownloadHandler;

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    protected void setFailDownloadHandler(Consumer<Exception> handler) {
        failDownloadHandler = handler;
    }

    public Downloader() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            // NORMAL (не ALWAYS) — не следуем за редиректом с https на http (даунгрейд).
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        gson = new GsonBuilder().create();
    }

    public abstract boolean checkUpdates() throws IOException, InterruptedException;
    public abstract boolean checkUpdates(String versionId) throws IOException, InterruptedException;
    public abstract void download() throws IOException, InterruptedException;
    public abstract void download(String versionId) throws IOException, InterruptedException;

    protected String httpGet(URI url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(url).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    protected void downloadToPath(URI url, Path path) throws IOException, InterruptedException {
        downloadToPath(url, path, null, -1);
    }

    /**
     * Скачивает файл с проверкой HTTP-статуса, размера и SHA-1.
     * Пишет во временный {@code *.part} и атомарно переносит на место только после
     * успешной проверки — обрыв соединения не оставляет битый файл на финальном пути.
     * Повторяет попытки при временных ошибках.
     */
    protected void downloadToPath(URI url, Path path, String expectedSha1, long expectedSize)
            throws IOException, InterruptedException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Path> resp = httpClient.send(
                    HttpRequest.newBuilder(url).timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                }
                if (expectedSize >= 0) {
                    long actual = Files.size(tmp);
                    if (actual != expectedSize) {
                        throw new IOException("Size mismatch for " + url
                                + ": expected " + expectedSize + ", got " + actual);
                    }
                }
                if (expectedSha1 != null && !expectedSha1.isEmpty()) {
                    String actual = sha1(tmp);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        throw new IOException("SHA1 mismatch for " + url
                                + ": expected " + expectedSha1 + ", got " + actual);
                    }
                }
                try {
                    Files.move(tmp, path,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tmp);
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw last != null ? last : new IOException("Download failed: " + url);
    }

    private synchronized ThreadPoolExecutor executor() {
        // Ленивое (пере)создание пула: после waitUntilDownload() пул завершён (shutdown),
        // поэтому повторный download() на том же экземпляре получает свежий пул
        // вместо RejectedExecutionException.
        if (executor == null || executor.isShutdown()) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                    Math.max(1, config.downloadThreadsCount));
            firstFailure.set(null); // начало новой пачки загрузок
        }
        return executor;
    }

    protected void downloadToPathInThread(URI url, Path path) {
        downloadToPathInThread(url, path, null, -1);
    }

    protected void downloadToPathInThread(URI url, Path path, String sha1, long size) {
        executor().submit(() -> {
            try {
                downloadToPath(url, path, sha1, size);
                javafx.application.Platform.runLater(() -> updateStepAndIncProgress(path.toString()));
            } catch (Exception exc) {
                // Запоминаем первый сбой, чтобы waitUntilDownload() его пробросил
                // (не глушим ошибку — download() не должен «успешно» завершиться с битыми файлами).
                firstFailure.compareAndSet(null, exc);
                javafx.application.Platform.runLater(() -> {
                    if (failDownloadHandler != null)
                        failDownloadHandler.accept(exc);
                });
            }
        });
    }

    protected void waitUntilDownload() throws IOException, InterruptedException {
        ThreadPoolExecutor ex;
        synchronized (this) {
            ex = executor;
        }
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(1, TimeUnit.HOURS)) {
                ex.shutdownNow();
                throw new IOException("Download timed out");
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
            throw e;
        }
        Exception f = firstFailure.getAndSet(null);
        if (f != null) {
            if (f instanceof IOException io) throw io;
            if (f instanceof InterruptedException ie) throw ie;
            throw new IOException(f);
        }
    }

    private static String sha1(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(p);
                 DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) { /* streaming digest */ }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }
}

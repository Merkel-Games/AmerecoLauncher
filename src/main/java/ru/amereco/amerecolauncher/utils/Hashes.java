package ru.amereco.amerecolauncher.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Подсчёт MD5 — того же алгоритма, которым посчитаны хеши в индексе HTTPSync.
 *
 * <p>MD5 здесь — проверка тождества содержимого против манифеста, который мы уже получили
 * поверх TLS, а не криптографический примитив. Менять на SHA-256 нельзя: формат индекса
 * задан генератором на сервере.
 */
public final class Hashes {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    /** MD5 содержимого файла в нижнем регистре hex. Читает потоково — файл целиком в память не грузится. */
    public static String md5(Path path) throws IOException {
        MessageDigest md = md5Digest();
        try (InputStream in = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(in, md)) {
            byte[] buf = new byte[8192];
            while (dis.read(buf) != -1) { /* streaming digest */ }
        }
        return hex(md.digest());
    }

    /** MD5 массива байт в нижнем регистре hex. */
    public static String md5(byte[] bytes) {
        return hex(md5Digest().digest(bytes));
    }

    /**
     * MD5 файла или {@code null}, если файла нет или он не читается.
     * Удобно там, где отсутствие файла — штатная ветка логики, а не ошибка.
     */
    public static String md5OrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? md5(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // MD5 обязателен для любой реализации JRE — сюда попасть нельзя.
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}

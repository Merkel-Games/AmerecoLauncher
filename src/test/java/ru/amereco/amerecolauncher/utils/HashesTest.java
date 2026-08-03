package ru.amereco.amerecolauncher.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashesTest {
    @TempDir
    Path dir;

    @Test
    @DisplayName("контрольные векторы MD5")
    void knownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Hashes.md5(new byte[0]));
        assertEquals("0cc175b9c0f1b6a831c399e269772661",
                Hashes.md5("a".getBytes(StandardCharsets.UTF_8)));
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                Hashes.md5("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("хеш файла совпадает с хешем его содержимого")
    void fileMatchesBytes() throws IOException {
        Path file = dir.resolve("a.txt");
        byte[] content = "настройки игрока".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);
        assertEquals(Hashes.md5(content), Hashes.md5(file));
    }

    @Test
    @DisplayName("файл длиннее буфера читается целиком")
    void largeFile() throws IOException {
        byte[] content = new byte[100_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Path file = dir.resolve("big.bin");
        Files.write(file, content);
        assertEquals(Hashes.md5(content), Hashes.md5(file));
    }

    @Test
    void md5OrNullOnMissingFile() {
        assertNull(Hashes.md5OrNull(dir.resolve("нет.txt")));
        assertNull(Hashes.md5OrNull(dir));
    }

    @Test
    void hexIsLowercaseAndFixedLength() {
        String hash = Hashes.md5("что угодно".getBytes(StandardCharsets.UTF_8));
        assertEquals(32, hash.length());
        assertEquals(hash.toLowerCase(java.util.Locale.ROOT), hash);
    }

    @Test
    @DisplayName("атомарная запись оставляет либо старое, либо новое содержимое")
    void atomicWriteLeavesNoPartFile() throws IOException {
        Path target = dir.resolve("nested/deep/a.txt");
        AtomicFiles.write(target, "первое".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("первое".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        AtomicFiles.write(target, "второе".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("второе".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        assertFalse(Files.exists(target.resolveSibling("a.txt.part")), "остался временный файл");
    }

    @Test
    void deleteRecursivelyTolerantToMissing() throws IOException {
        AtomicFiles.deleteRecursively(dir.resolve("нет"));

        Path tree = dir.resolve("t/a/b");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("f.txt"), "x");
        AtomicFiles.deleteRecursively(dir.resolve("t"));
        assertTrue(Files.notExists(dir.resolve("t")));
    }
}

package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextCodecTest {
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("перевод строки CRLF сохраняется")
    void crlfPreserved() throws CharacterCodingException {
        TextCodec.Text text = TextCodec.decode(utf8("a\r\nb\r\n"));
        assertEquals("a\nb", text.content);
        assertEquals("\r\n", text.style.eol);
        assertTrue(text.style.trailingNewline);
        assertArrayEquals(utf8("a\r\nb\r\n"), TextCodec.encode(text.content, text.style));
    }

    @Test
    @DisplayName("отсутствие завершающего перевода строки сохраняется")
    void missingTrailingNewlinePreserved() throws CharacterCodingException {
        TextCodec.Text text = TextCodec.decode(utf8("a\nb"));
        assertFalse(text.style.trailingNewline);
        assertArrayEquals(utf8("a\nb"), TextCodec.encode(text.content, text.style));
    }

    @Test
    @DisplayName("BOM сохраняется и не попадает в содержимое")
    void bomPreserved() throws CharacterCodingException {
        byte[] source = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        TextCodec.Text text = TextCodec.decode(source);
        assertEquals("a", text.content);
        assertTrue(text.style.bom);
        assertArrayEquals(source, TextCodec.encode(text.content, text.style));
    }

    @Test
    @DisplayName("нулевой байт означает двоичный файл")
    void nulByteIsBinary() {
        assertTrue(TextCodec.hasNulByte(new byte[]{'a', 0, 'b'}));
        assertFalse(TextCodec.isText(new byte[]{'a', 0, 'b'}));
    }

    @Test
    @DisplayName("битый UTF-8 текстом не считается")
    void invalidUtf8IsNotText() {
        assertFalse(TextCodec.isText(new byte[]{(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    @DisplayName("настоящий servers.dat — двоичный NBT")
    void nbtIsBinary() {
        // Первые байты живого servers.dat: 0a 00 00 09.
        byte[] nbt = new byte[]{0x0A, 0x00, 0x00, 0x09, 0x00, 0x07, 's', 'e', 'r', 'v'};
        assertFalse(TextCodec.isText(nbt));
    }

    @Test
    @DisplayName("кириллица — валидный текст")
    void cyrillicIsText() {
        assertTrue(TextCodec.isText(utf8("настройки: включено")));
    }

    @Test
    void linesAndJoinAreInverse() {
        assertIterableEquals(List.of("a", "b", "c"), TextCodec.lines("a\nb\nc"));
        assertIterableEquals(List.of(), TextCodec.lines(""));
        assertIterableEquals(List.of("a", ""), TextCodec.lines("a\n"));
        assertEquals("a\nb", TextCodec.join(List.of("a", "b")));
    }

    @Test
    @DisplayName("пустой файл переживает круговой прогон")
    void emptyFile() throws CharacterCodingException {
        TextCodec.Text text = TextCodec.decode(new byte[0]);
        assertEquals("", text.content);
        assertArrayEquals(new byte[0], TextCodec.encode(text.content, text.style));
    }
}

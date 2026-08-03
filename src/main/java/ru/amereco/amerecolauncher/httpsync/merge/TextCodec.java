package ru.amereco.amerecolauncher.httpsync.merge;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Перевод байтов в текст и обратно с сохранением стиля файла.
 *
 * <p>Стиль (BOM, перевод строки, наличие завершающего перевода) берётся у <b>локального</b>
 * файла: слияние не должно менять привычное игроку оформление. Внутри работы содержимое
 * всегда нормализовано к {@code \n} и без BOM.
 */
public final class TextCodec {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextCodec() {
    }

    /** Стиль оформления текстового файла. */
    public static final class Style {
        public final String eol;
        public final boolean bom;
        public final boolean trailingNewline;

        public Style(String eol, boolean bom, boolean trailingNewline) {
            this.eol = eol;
            this.bom = bom;
            this.trailingNewline = trailingNewline;
        }

        public static Style unixDefault() {
            return new Style("\n", false, true);
        }
    }

    /** Разобранный текст: нормализованное содержимое плюс стиль исходника. */
    public static final class Text {
        public final String content;
        public final Style style;

        Text(String content, Style style) {
            this.content = content;
            this.style = style;
        }
    }

    /** Признак бинарного содержимого: нулевой байт в начале файла. */
    public static boolean hasNulByte(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** Можно ли считать содержимое текстом UTF-8. */
    public static boolean isText(byte[] bytes) {
        if (hasNulByte(bytes)) {
            return false;
        }
        try {
            strictDecode(bytes, 0);
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * Разбирает байты как UTF-8 в строгом режиме.
     *
     * @throws CharacterCodingException если это не валидный UTF-8 (значит, файл бинарный)
     */
    public static Text decode(byte[] bytes) throws CharacterCodingException {
        boolean bom = startsWithBom(bytes);
        String raw = strictDecode(bytes, bom ? UTF8_BOM.length : 0);

        boolean crlf = raw.indexOf("\r\n") >= 0;
        String normalized = crlf ? raw.replace("\r\n", "\n") : raw;
        boolean trailingNewline = normalized.endsWith("\n");
        if (trailingNewline) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new Text(normalized, new Style(crlf ? "\r\n" : "\n", bom, trailingNewline));
    }

    /** Собирает байты из нормализованного содержимого по заданному стилю. */
    public static byte[] encode(String content, Style style) {
        String body = "\n".equals(style.eol) ? content : content.replace("\n", style.eol);
        if (style.trailingNewline) {
            body = body + style.eol;
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (!style.bom) {
            return payload;
        }
        byte[] out = new byte[UTF8_BOM.length + payload.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(payload, 0, out, UTF8_BOM.length, payload.length);
        return out;
    }

    /**
     * Режет нормализованное содержимое на строки.
     * Завершающий перевод строки уже снят в {@link #decode}, поэтому пустого хвоста не возникает.
     */
    public static List<String> lines(String content) {
        List<String> out = new ArrayList<>();
        if (content.isEmpty()) {
            return out;
        }
        int from = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                out.add(content.substring(from, i));
                from = i + 1;
            }
        }
        out.add(content.substring(from));
        return out;
    }

    /** Обратная операция к {@link #lines}. */
    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2];
    }

    private static String strictDecode(byte[] bytes, int offset) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return decoded.toString();
    }
}

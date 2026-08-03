package ru.amereco.amerecolauncher.httpsync.merge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Разбор и запись JSON с сохранением оформления файла.
 *
 * <p>Структурное слияние допускается <b>только</b> для текста, который является строгим JSON.
 * Причина в асимметрии Gson: читает он лениво (понимает {@code //}, {@code #}, {@code /* *}{@code /},
 * одинарные кавычки, неэкранированные имена и висячие запятые), но записать ничего из этого не может.
 * Круговой прогон конфига с комментариями молча вырезал бы все пояснения, а висячая запятая
 * {@code [1,2,]} превратилась бы в {@code [1,2,null]} — это уже порча данных, а не потеря
 * форматирования. Поэтому такие файлы уходят на построчное слияние, которое ничего не теряет
 * по построению.
 */
public final class JsonText {
    /** htmlSafe выключен, чтобы {@code <}, {@code >}, {@code &}, {@code =} не превращались в \\uXXXX. */
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private JsonText() {
    }

    /** Является ли текст строгим JSON — то есть безопасен ли круговой прогон через Gson. */
    public static boolean isStrictJson(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        try {
            consume(reader);
            return reader.peek() == JsonToken.END_DOCUMENT;
        } catch (IOException | RuntimeException notStrict) {
            return false;
        }
    }

    /**
     * Определяет единицу отступа по тексту: содержимое первой строки с ведущими пробелами
     * или табуляциями — это ровно один уровень вложенности.
     *
     * <p>Компактный однострочный JSON даёт пустой отступ (и такая же компактная запись обратно).
     * Многострочный без отступов — редкий случай, для него берём два пробела.
     */
    public static String detectIndent(String text) {
        int lineStart = 0;
        boolean multiline = false;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                if (i > lineStart) {
                    char first = text.charAt(lineStart);
                    if (first == ' ' || first == '\t') {
                        int end = lineStart;
                        while (end < i && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
                            end++;
                        }
                        return text.substring(lineStart, end);
                    }
                }
                if (i < text.length()) {
                    multiline = true;
                }
                lineStart = i + 1;
            }
        }
        return multiline ? "  " : "";
    }

    /**
     * Воспроизводит ли наш писатель этот файл байт в байт.
     *
     * <p>Строгого JSON мало: оформление бывает и таким, какого {@link JsonWriter} не умеет.
     * Реальные примеры из сборки — {@code config/axiom/axiom.json}, где {@code =} записан как
     * {@code =} (Gson с включённым htmlSafe), и конфиги Valkyrien Skies, где Jackson ставит
     * пробел перед двоеточием: {@code "key" : value}. Разбор с последующей записью не потерял бы
     * там ни одного значения, но переписал бы весь файл игрока при каждом обновлении.
     *
     * <p>Проверка общая и самопроверяющаяся: она ловит любое оформление, которое мы не умеем
     * повторить, включая ещё не встреченное. Не прошедший её файл уходит на построчное слияние,
     * которое оформление не трогает вовсе.
     */
    public static boolean roundTripsExactly(String text) {
        try {
            return write(JsonParser.parseString(text), detectIndent(text)).equals(text);
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    /** Записывает дерево с заданной единицей отступа. Завершающий перевод строки не добавляется. */
    public static String write(JsonElement element, String indent) {
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        writer.setIndent(indent);
        // toJson сам выставит htmlSafe и serializeNulls из настроек GSON.
        GSON.toJson(element, writer);
        return out.toString();
    }

    private static void consume(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                reader.beginObject();
                while (reader.peek() != JsonToken.END_OBJECT) {
                    reader.nextName();
                    consume(reader);
                }
                reader.endObject();
                break;
            case BEGIN_ARRAY:
                reader.beginArray();
                while (reader.peek() != JsonToken.END_ARRAY) {
                    consume(reader);
                }
                reader.endArray();
                break;
            case STRING:
            case NUMBER:
                // nextString отдаёт число текстом, без потери точности при разборе.
                reader.nextString();
                break;
            case BOOLEAN:
                reader.nextBoolean();
                break;
            case NULL:
                reader.nextNull();
                break;
            default:
                throw new IOException("unexpected token: " + token);
        }
    }
}

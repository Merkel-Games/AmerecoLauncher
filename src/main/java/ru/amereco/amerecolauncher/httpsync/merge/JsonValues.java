package ru.amereco.amerecolauncher.httpsync.merge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Глубокое сравнение значений Gson.
 *
 * <p>Собственный {@code equals} у {@link JsonPrimitive} сводит числа к {@code double}, из-за чего
 * длинные целые сравниваются неточно. Здесь числа сравниваются через {@link BigDecimal}, так что
 * {@code 1} и {@code 1.0} равны (иначе круговой прогон через Gson порождал бы ложные конфликты),
 * а два различных 19-значных целых — нет.
 */
public final class JsonValues {
    private JsonValues() {
    }

    public static boolean equal(JsonElement a, JsonElement b) {
        if (a == b) {
            return true;
        }
        if (isNull(a) || isNull(b)) {
            return isNull(a) && isNull(b);
        }
        if (a.isJsonObject() && b.isJsonObject()) {
            return objectsEqual(a.getAsJsonObject(), b.getAsJsonObject());
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            return arraysEqual(a.getAsJsonArray(), b.getAsJsonArray());
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            return primitivesEqual(a.getAsJsonPrimitive(), b.getAsJsonPrimitive());
        }
        return false;
    }

    private static boolean isNull(JsonElement e) {
        return e == null || e.isJsonNull();
    }

    private static boolean objectsEqual(JsonObject a, JsonObject b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : a.entrySet()) {
            if (!b.has(entry.getKey())) {
                return false;
            }
            if (!equal(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean arraysEqual(JsonArray a, JsonArray b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean primitivesEqual(JsonPrimitive a, JsonPrimitive b) {
        if (a.isNumber() && b.isNumber()) {
            return numbersEqual(a.getAsString(), b.getAsString());
        }
        if (a.isBoolean() && b.isBoolean()) {
            return a.getAsBoolean() == b.getAsBoolean();
        }
        if (a.isString() && b.isString()) {
            return a.getAsString().equals(b.getAsString());
        }
        // Разные типы: строка "1" и число 1 — не одно и то же.
        return false;
    }

    private static boolean numbersEqual(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException notDecimal) {
            // NaN, Infinity и прочая нестандартная запись — сравниваем как есть.
            return false;
        }
    }
}

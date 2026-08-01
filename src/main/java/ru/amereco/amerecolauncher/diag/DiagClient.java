package ru.amereco.amerecolauncher.diag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Транспорт диагностики: два POST-запроса к WordPress-плагину на amereco.ru.
 *
 * <ul>
 *   <li>{@code POST <base>report} — JSON со структурированным отчётом. Всегда маленький.</li>
 *   <li>{@code POST <base>log} — {@code multipart/form-data} с gzip-логом. Только если
 *       сервер в ответе на первый запрос сказал, что лог ему нужен.</li>
 * </ul>
 *
 * <p>Логи ездят отдельным запросом и именно multipart-ом, а не base64 внутри JSON:
 * так тело остаётся маленьким там, где это важно, а большой файл не упирается
 * в лимиты на размер JSON-тела.
 *
 * <h3>Аутентификация</h3>
 * Приём открытый — отчёт возьмут и без токена. Но если игрок залогинен, лаунчер
 * прикладывает свой Yggdrasil access-токен, и сервер помечает отчёт как достоверный.
 * Токен дублируется в {@code X-Amereco-Token}, потому что заголовок {@code Authorization}
 * на части конфигураций PHP до приложения не доходит.
 */
public final class DiagClient {

    private static final Gson GSON = new Gson();

    /** Разделитель частей multipart-тела. Не должен встречаться в данных. */
    private static final String BOUNDARY = "----AmerecoDiag7f3a91c2b8e4";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Отдельный клиент, а не общий на приложение: диагностика не должна ни делить
     * пул соединений с загрузчиком, ни наследовать его таймауты.
     */
    /**
     * Редиректы намеренно не выполняются.
     *
     * <p>{@code Redirect.NORMAL} перенёс бы заголовки {@code Authorization}
     * и {@code X-Amereco-Token} на новый адрес, а редирект может прийти куда угодно —
     * от перехваченного DNS до подменённой страницы провайдера. Access-токен игрока
     * этого не стоит. Плюс POST при редиректе превращается в GET, и «успешный»
     * ответ чужой страницы выглядел бы как доставленный отчёт.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;

    /**
     * @param baseUrl база роутов, обязательно со слешем на конце,
     *                например {@code https://amereco.ru/wp-json/amereco-diag/v1/}
     */
    public DiagClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * Результат попытки отправки.
     *
     * @param delivered  сервер принял
     * @param retryable  не принял, но имеет смысл попробовать позже (сеть, 5xx, 429)
     * @param reportId   идентификатор записи на сервере — нужен для досылки лога
     * @param wantLog    сервер просит прислать лог; если {@code false} — не тратим трафик
     * @param detail     краткое описание для локального лога
     */
    public record SendResult(boolean delivered, boolean retryable,
                             long reportId, boolean wantLog,
                             String attachToken, String detail) {

        static SendResult retry(String detail) {
            return new SendResult(false, true, 0, false, null, detail);
        }

        static SendResult drop(String detail) {
            return new SendResult(false, false, 0, false, null, detail);
        }
    }

    /** Отправляет структурированный отчёт. */
    public SendResult sendReport(CrashReport report, String accessToken) {
        String json = GSON.toJson(report);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "report"))
                .timeout(REQUEST_TIMEOUT)
                // Java не проставляет Content-Type сама, а без него WordPress
                // не разберёт тело и get_json_params() вернёт null.
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", userAgent())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        withToken(builder, accessToken);
        return execute(builder.build());
    }

    /**
     * Досылает gzip-лог к уже созданному отчёту.
     *
     * @param reportId  идентификатор, полученный из {@link #sendReport}
     * @param gzipped   содержимое лога, уже сжатое
     * @param fileName  имя для удобства чтения в админке; путь на диске сервер
     *                  генерирует сам и это имя нигде в путь не подставляет
     */
    public SendResult sendLog(long reportId, String attachToken, String clientId,
                              byte[] gzipped, String fileName, String accessToken) {
        byte[] body;
        try {
            body = multipart(reportId, attachToken, clientId, gzipped, fileName);
        } catch (IOException e) {
            return SendResult.drop("не удалось собрать multipart: " + e.getMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "log"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("User-Agent", userAgent())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        withToken(builder, accessToken);
        return execute(builder.build());
    }

    private void withToken(HttpRequest.Builder builder, String accessToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
            builder.header("X-Amereco-Token", accessToken);
        }
    }

    private static String userAgent() {
        return "AmerecoLauncher/"
                + ru.amereco.amerecolauncher.Config.properties.getProperty("version", "?");
    }

    /**
     * Выполняет запрос и переводит ответ в {@link SendResult}.
     *
     * <p>Ничего не бросает наружу: крашрепортер не имеет права стать причиной краша.
     */
    private SendResult execute(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();

            if (code / 100 == 2) {
                return parseOk(response.body());
            }
            // 429 — попали в лимит частоты; 5xx — сервер прилёг. И то и другое лечится ожиданием.
            if (code == 429 || code / 100 == 5) {
                return SendResult.retry("HTTP " + code);
            }
            // 3xx — адрес приёмника настроен неверно (редиректы мы не выполняем).
            // Повторять смысла нет, но и молча удалять отчёт неправильно: пусть
            // причина будет видна в логе.
            if (code / 100 == 3) {
                return SendResult.drop("HTTP " + code + " — приёмник отвечает редиректом, "
                        + "проверьте diagUrl");
            }
            // 4xx — сервер отказал по существу (протух формат, отчёт не нужен).
            // Повторять бессмысленно, иначе очередь никогда не рассосётся.
            return SendResult.drop("HTTP " + code + " " + shorten(response.body()));
        } catch (IOException e) {
            return SendResult.retry("сеть: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.retry("прервано");
        } catch (Throwable t) {
            return SendResult.drop("неожиданная ошибка: " + t);
        }
    }

    /**
     * Разбирает успешный ответ.
     *
     * <p>Ответ обязан быть нашим JSON с полем {@code ok}. Код 200 сам по себе
     * ничего не гарантирует: его отдаёт и страница-заглушка провайдера, и главная
     * страница сайта при неверном адресе роута. Считать такое доставкой — значит
     * молча стирать отчёты, которые никуда не дошли.
     */
    private static SendResult parseOk(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                return SendResult.drop("сервер ответил не нашим форматом");
            }
            long id = json.has("id") ? json.get("id").getAsLong() : 0L;
            boolean wantLog = json.has("want_log") && json.get("want_log").getAsBoolean();
            String attach = json.has("attach_token") && !json.get("attach_token").isJsonNull()
                    ? json.get("attach_token").getAsString() : null;
            return new SendResult(true, false, id, wantLog, attach, "принят");
        } catch (Exception e) {
            return SendResult.drop("не удалось разобрать ответ сервера");
        }
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        String stripped = body.strip();
        return stripped.length() > 200 ? stripped.substring(0, 200) + "…" : stripped;
    }

    /**
     * Собирает тело {@code multipart/form-data}.
     *
     * <p>Поля: {@code report_id} — к какому отчёту приложить, {@code attach_token} —
     * одноразовый пропуск, который сервер выдал в ответе на сам отчёт (без него
     * перебором идентификаторов можно было бы приложить свой лог к чужому отчёту),
     * {@code client_id} — для счётчиков частоты, и сам {@code file}.
     */
    private static byte[] multipart(long reportId, String attachToken, String clientId,
                                    byte[] file, String fileName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 512);
        String dashBoundary = "--" + BOUNDARY + "\r\n";

        writeField(out, dashBoundary, "report_id", String.valueOf(reportId));
        if (attachToken != null && !attachToken.isBlank()) {
            writeField(out, dashBoundary, "attach_token", attachToken);
        }
        if (clientId != null && !clientId.isBlank()) {
            writeField(out, dashBoundary, "client_id", clientId);
        }

        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + sanitizeFileName(fileName) + "\"\r\n"
                + "Content-Type: application/gzip\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Пишет одно текстовое поле multipart-формы. */
    private static void writeField(ByteArrayOutputStream out, String dashBoundary,
                                   String name, String value) throws IOException {
        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Оставляет в имени файла только безобидные символы.
     *
     * <p>Сервер всё равно генерирует имя сам, но кавычка или перевод строки здесь
     * поломали бы разбор multipart-заголовка ещё на входе.
     */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "log.gz";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(cleaned.length() - 64) : cleaned;
    }
}

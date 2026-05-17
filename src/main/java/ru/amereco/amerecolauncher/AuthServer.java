package ru.amereco.amerecolauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class AuthServer {

    // ── Request / Response DTOs ───────────────────────────────────────

    public record Agent(@Expose String name, @Expose int version) {}

    public record AuthenticateRequest(@Expose String username, @Expose String password,
            @Expose String clientToken, @Expose boolean requestUser, @Expose Agent agent) {}

    public record AuthenticateResponse(@Expose String accessToken, @Expose String clientToken,
            @Expose List<Profile> availableProfiles, @Expose Profile selectedProfile, @Expose User user) {}

    public record Profile(@Expose String id, @Expose String name, @Expose List<ProfileProperty> properties) {
        public record ProfileProperty(@Expose String name, @Expose String value, @Expose String signature) {}
    }

    public record User(@Expose String id, @Expose List<UserProperty> properties) {
        public record UserProperty(@Expose String name, @Expose String value) {}
    }

    public record RefreshRequest(@Expose String accessToken, @Expose String clientToken,
            @Expose boolean requestUser, @Expose Profile selectedProfile) {}

    public record RefreshResponse(@Expose String accessToken, @Expose String clientToken,
            @Expose Profile selectedProfile, @Expose User user) {}

    public record ValidateRequest(@Expose String accessToken, @Expose String clientToken) {}

    public record InvalidateRequest(@Expose String accessToken, @Expose String clientToken) {}

    public record SignoutRequest(@Expose String username, @Expose String password) {}

    public record YggdrasilError(@Expose String error, @Expose String errorMessage, @Expose String cause) {}

    // ── Exception ─────────────────────────────────────────────────────

    public static class YggdrasilException extends RuntimeException {
        private final int statusCode;
        private final String errorType;

        public YggdrasilException(int statusCode, String errorType, String message) {
            super(message);
            this.statusCode = statusCode;
            this.errorType = errorType;
        }

        public int getStatusCode() { return statusCode; }
        public String getErrorType() { return errorType; }
    }

    // ── Implementation ────────────────────────────────────────────────

    private static final Gson gson = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .create();

    private final HttpClient httpClient;
    private final String baseUrl;

    public AuthServer() {
        String url = Config.properties.getProperty("authlibApiUrl");
        this.baseUrl = (url.endsWith("/") ? url : url + "/") + "authserver/";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    // ── Yggdrasil API methods ─────────────────────────────────────────

    public AuthenticateResponse authenticate(String username, String password,
            String clientToken) throws IOException, InterruptedException {
        var request = new AuthenticateRequest(username, password, clientToken, true,
                new Agent("Minecraft", 1));
        String json = post("authenticate", gson.toJson(request));
        return gson.fromJson(json, AuthenticateResponse.class);
    }

    public RefreshResponse refresh(String accessToken, String clientToken,
            Profile selectedProfile) throws IOException, InterruptedException {
        var request = new RefreshRequest(accessToken, clientToken, true, selectedProfile);
        String json = post("refresh", gson.toJson(request));
        return gson.fromJson(json, RefreshResponse.class);
    }

    public void validate(String accessToken, String clientToken)
            throws IOException, InterruptedException {
        var request = new ValidateRequest(accessToken, clientToken);
        postNoContent("validate", gson.toJson(request));
    }

    public void invalidate(String accessToken, String clientToken)
            throws IOException, InterruptedException {
        var request = new InvalidateRequest(accessToken, clientToken);
        postNoContent("invalidate", gson.toJson(request));
    }

    public void signout(String username, String password)
            throws IOException, InterruptedException {
        var request = new SignoutRequest(username, password);
        postNoContent("signout", gson.toJson(request));
    }

    // ── HTTP helpers ──────────────────────────────────────────────────

    private String post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        }
        throw buildException(response.statusCode(), response.body());
    }

    private void postNoContent(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<Void> response = httpClient.send(request,
                HttpResponse.BodyHandlers.discarding());

        if (response.statusCode() != 204) {
            throw buildException(response.statusCode(), "");
        }
    }

    private YggdrasilException buildException(int statusCode, String body) {
        try {
            YggdrasilError error = gson.fromJson(body, YggdrasilError.class);
            return new YggdrasilException(statusCode, error.error(), error.errorMessage());
        } catch (Exception e) {
            return new YggdrasilException(statusCode, null, "HTTP " + statusCode + ": " + body);
        }
    }
}

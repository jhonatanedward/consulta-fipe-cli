package br.com.edward.consulta_fipe_cli.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class HttpClientWrapper implements IHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpClientWrapper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public <T> T get(String uri, Map<String, String> headers, Class<T> responseType) {
        HttpRequest request = buildRequest(HttpMethod.GET, uri, null, mergeWithDefaults(headers, false));
        return send(request, responseType);
    }

    @Override
    public <T> T post(String uri, Map<String, String> headers, Object requestBody, Class<T> responseType) {
        HttpRequest request = buildRequest(HttpMethod.POST, uri, requestBody, mergeWithDefaults(headers, true));
        return send(request, responseType);
    }

    @Override
    public <T> T put(String uri, Map<String, String> headers, Object requestBody, Class<T> responseType) {
        HttpRequest request = buildRequest(HttpMethod.PUT, uri, requestBody, mergeWithDefaults(headers, true));
        return send(request, responseType);
    }

    @Override
    public void delete(String uri, Map<String, String> headers) {
        HttpRequest request = buildRequest(uri, HttpMethod.DELETE, null, mergeWithDefaults(headers, false));
        send(request, Void.class);
    }

    public Map<String, String> mergeWithDefaults(Map<String, String> headers, Boolean hasBody) {
        Map<String, String> merged = new HashMap<>();
        merged.put("Content-Type", "application/json");
        if(hasBody) {
            merged.put("Accept", "application/json");
        }
        if(headers != null) {
            merged.putAll(headers);
        }

        return merged;
    }

    private HttpRequest buildRequest(String method, String uri, Object body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(10));

        if(body != null) {
            try {
                String json = objectMapper.writeValueAsString(body);
                builder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        headers.forEach(builder::header);
        return builder.build();
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if(responseType == Void.class) return null;
                return objectMapper.readValue(response.body(), responseType);
            }else {
                throw new RuntimeException("HTTP error: " + response.statusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }
}

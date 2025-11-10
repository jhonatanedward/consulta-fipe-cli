package br.com.edward.consulta_fipe_cli.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class HttpClientWrapper implements IHttpClient {

    private static final String FIPE_RETRY = "FipeRetry";

    private static final String FIPE_RATE_LIMITER = "FipeRateLimiter";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Retry retry;
    private final RateLimiter rateLimiter;

    public HttpClientWrapper() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        retry = createRetry();
        rateLimiter = createRateLimiter();
        this.retry.getEventPublisher().onRetry(event ->
                System.out.println("RETRY EVENT: Tentativa " + event.getNumberOfRetryAttempts() +
                        " após falha: " + event.getLastThrowable().getMessage()));
    }

    private Retry createRetry() {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(5)
                .waitDuration(Duration.ofSeconds(1))
                .build();
        return Retry.of(FIPE_RETRY, retryConfig);
    }

    private RateLimiter createRateLimiter() {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(1))
                .build();
        return RateLimiter.of(FIPE_RATE_LIMITER, rateLimiterConfig);
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
        Supplier<T> httpCallSupplier = () -> {
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
        };

        Supplier<T> decoratedSupplier = Decorators.ofSupplier(httpCallSupplier)
                .withRateLimiter(rateLimiter)
                .withRetry(retry)
                .decorate();

        try {
            return decoratedSupplier.get();
        } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
            // Exceção lançada quando o Rate Limiter não consegue adquirir uma permissão a tempo
            throw new RuntimeException("Rate limit excedido: Demasiadas requisições em pouco tempo", e);
        } catch (Exception e) {
            // Captura qualquer exceção que venha após as 3 tentativas de Retry
            throw new RuntimeException("HTTP request failed após todas as tentativas de Retry/Resiliência", e);
        }
    }
}

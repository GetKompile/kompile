/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Common contract for model discovery.
 *
 * <p>A discovery failure is deliberately different from a successful empty
 * response. Callers can then preserve a configured model or offer a manual
 * model entry instead of treating an outage as "this provider has no models".</p>
 */
public final class ModelDiscovery {
    private ModelDiscovery() {
    }

    public enum Status {
        SUCCESS,
        SUCCESS_EMPTY,
        AUTH_REQUIRED,
        FORBIDDEN,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UNAVAILABLE,
        TIMEOUT,
        UNSUPPORTED
    }

    public record Context(
            String providerId,
            String baseUrl,
            String transientApiKey,
            OAuthProviderFlow.RequestAuth auth,
            HttpClient httpClient,
            Duration timeout) {
        public Context {
            timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
            httpClient = httpClient == null
                    ? HttpClient.newBuilder().connectTimeout(timeout).build()
                    : httpClient;
        }
    }

    public record Result(
            Status status,
            List<LiveModelDiscovery.Model> models,
            String message,
            List<String> attemptedEndpoints) {
        public Result {
            status = status == null ? Status.UNAVAILABLE : status;
            models = models == null ? List.of() : List.copyOf(models);
            message = message == null ? "" : message;
            attemptedEndpoints = attemptedEndpoints == null
                    ? List.of() : List.copyOf(attemptedEndpoints);
        }

        public boolean hasModels() {
            return !models.isEmpty();
        }

        public boolean isUsable() {
            return status == Status.SUCCESS && hasModels();
        }

        public static Result success(List<LiveModelDiscovery.Model> models, List<String> endpoints) {
            List<LiveModelDiscovery.Model> values = models == null ? List.of() : models;
            return new Result(values.isEmpty() ? Status.SUCCESS_EMPTY : Status.SUCCESS,
                    values, "", endpoints);
        }

        public static Result failure(Status status, String message, List<String> endpoints) {
            return new Result(status, List.of(), message, endpoints);
        }
    }

    @FunctionalInterface
    public interface Strategy {
        Result discover(Context context);
    }
}

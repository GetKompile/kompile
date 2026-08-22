/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.util.List;

/**
 * Provider-specific model-list routes.
 *
 * <p>Each descriptor binds one of these strategies explicitly. The endpoint
 * knowledge therefore stays reviewable per provider instead of growing a
 * central "try every common URL" switch.</p>
 */
final class ProviderModelDiscoveryStrategies {
    private ProviderModelDiscoveryStrategies() {
    }

    static ModelDiscovery.Strategy openAi() {
        return context -> one(context, versionedModels(context));
    }

    static ModelDiscovery.Strategy openAiCodex() {
        return CodexAppServerModelDiscovery::discover;
    }

    static ModelDiscovery.Strategy kompileLocal() {
        return context -> ModelDiscovery.Result.failure(
                ModelDiscovery.Status.UNSUPPORTED,
                "Kompile local model inventory depends on the installed runtime; enter a model id manually",
                List.of("local:kompile-local"));
    }

    static ModelDiscovery.Strategy anthropic() {
        return context -> one(context, versionedModels(context));
    }

    static ModelDiscovery.Strategy gemini() {
        return context -> one(context,
                ModelDiscoveryHttp.endpointWithoutSuffix(context.baseUrl(), "/openai", "/models"));
    }

    static ModelDiscovery.Strategy ollama() {
        return context -> one(context,
                ModelDiscoveryHttp.endpointWithoutSuffix(context.baseUrl(), "/v1", "/api/tags"));
    }

    static ModelDiscovery.Strategy openRouter() {
        return context -> one(context, versionedModels(context));
    }

    static ModelDiscovery.Strategy xai() {
        return context -> one(context, versionedModels(context));
    }

    static ModelDiscovery.Strategy githubCopilot() {
        return context -> one(context, ModelDiscoveryHttp.endpoint(context.baseUrl(), "/models"));
    }

    static ModelDiscovery.Strategy radius() {
        return context -> one(context, versionedRoute(context, "/config"));
    }

    static ModelDiscovery.Strategy deepSeek() {
        return context -> one(context, versionedModels(context));
    }

    static ModelDiscovery.Strategy groq() {
        return context -> one(context, versionedModels(context));
    }

    private static ModelDiscovery.Result one(ModelDiscovery.Context context, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.UNSUPPORTED,
                    "Provider has no model discovery endpoint", List.of());
        }
        return ModelDiscoveryHttp.discover(context, List.of(endpoint));
    }

    private static String versionedModels(ModelDiscovery.Context context) {
        return versionedRoute(context, "/models");
    }

    private static String versionedRoute(ModelDiscovery.Context context, String route) {
        String base = context.baseUrl();
        if (base != null && (base.endsWith("/v1") || base.endsWith("/openai/v1"))) {
            return ModelDiscoveryHttp.endpoint(base, route);
        }
        return ModelDiscoveryHttp.endpoint(base, "/v1" + route);
    }
}

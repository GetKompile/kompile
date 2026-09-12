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
        return ProviderModelCatalogs.strategy("openai");
    }

    static ModelDiscovery.Strategy openAiCodex() {
        return CodexAppServerModelDiscovery::discover;
    }

    static ModelDiscovery.Strategy kompileLocal() {
        return context -> {
            List<LiveModelDiscovery.Model> models = KompileLocalModels.discover();
            if (models.isEmpty()) {
                return ModelDiscovery.Result.failure(
                        ModelDiscovery.Status.SUCCESS_EMPTY,
                        "No installed Kompile chat models found yet — choose Download from "
                                + "HuggingFace or a local path below",
                        List.of("local:kompile-local"));
            }
            return ModelDiscovery.Result.success(models, List.of("local:kompile-local"));
        };
    }

    static ModelDiscovery.Strategy anthropic() {
        return ProviderModelCatalogs.strategy("anthropic");
    }

    static ModelDiscovery.Strategy gemini() {
        return ProviderModelCatalogs.strategy("gemini");
    }

    static ModelDiscovery.Strategy ollama() {
        return ProviderModelCatalogs.strategy("ollama");
    }

    static ModelDiscovery.Strategy openRouter() {
        return ProviderModelCatalogs.strategy("openrouter");
    }

    static ModelDiscovery.Strategy xai() {
        return ProviderModelCatalogs.strategy("xai");
    }

    static ModelDiscovery.Strategy zai() {
        return ProviderModelCatalogs.strategy("zai");
    }

    static ModelDiscovery.Strategy githubCopilot() {
        return ProviderModelCatalogs.strategy("github-copilot");
    }

    static ModelDiscovery.Strategy radius() {
        return ProviderModelCatalogs.strategy("radius");
    }

    static ModelDiscovery.Strategy deepSeek() {
        return ProviderModelCatalogs.strategy("deepseek");
    }

    static ModelDiscovery.Strategy groq() {
        return ProviderModelCatalogs.strategy("groq");
    }
}

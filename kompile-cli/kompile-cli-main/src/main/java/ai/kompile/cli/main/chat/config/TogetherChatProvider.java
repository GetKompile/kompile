/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Together AI API-key provider.
 *
 * <p>Together natively serves the OpenAI Chat Completions protocol under
 * {@code /v1}. Keys are managed credentials only — there is intentionally
 * no environment-variable fallback.</p>
 */
public final class TogetherChatProvider extends StaticChatProvider {
    public static final String BASE_URL = "https://api.together.xyz/v1";

    public TogetherChatProvider() {
        super("together", "Together AI", BASE_URL, null);
    }

    @Override
    public String apiKeyAuthLabel() {
        return "API key (Together)";
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.together();
    }
}

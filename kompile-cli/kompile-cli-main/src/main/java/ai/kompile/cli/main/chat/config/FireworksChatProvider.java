/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Fireworks AI API-key provider.
 *
 * <p>Fireworks natively serves the OpenAI Chat Completions protocol under
 * {@code /inference/v1}. Keys are managed credentials only — there is
 * intentionally no environment-variable fallback.</p>
 */
public final class FireworksChatProvider extends StaticChatProvider {
    public static final String BASE_URL = "https://api.fireworks.ai/inference/v1";

    public FireworksChatProvider() {
        super("fireworks", "Fireworks AI", BASE_URL, null);
    }

    @Override
    public String apiKeyAuthLabel() {
        return "API key (Fireworks)";
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.fireworks();
    }
}

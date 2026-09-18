/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** Z.AI API-key provider with subscription and credit-based endpoints. */
public final class ZaiChatProvider extends StaticChatProvider {
    public static final String SUBSCRIPTION_BASE_URL = "https://api.z.ai/api/coding/paas/v4";
    public static final String CREDITS_BASE_URL = "https://api.z.ai/api/paas/v4";

    public ZaiChatProvider() {
        super("zai", "Z.AI", SUBSCRIPTION_BASE_URL, "ZAI_API_KEY");
    }

    @Override
    public String apiKeyAuthLabel() {
        return "API key (subscription)";
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.zai();
    }
}

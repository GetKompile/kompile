/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** Direct standard-chat provider for the Z.AI GLM Coding Plan. */
public final class ZaiChatProvider extends StaticChatProvider {
    public ZaiChatProvider() {
        super("zai", "Z.AI GLM Coding Plan",
                "https://api.z.ai/api/coding/paas/v4", "ZAI_API_KEY");
    }

    @Override
    public String apiKeyAuthLabel() {
        return "GLM Coding Plan subscription API key";
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.zai();
    }
}

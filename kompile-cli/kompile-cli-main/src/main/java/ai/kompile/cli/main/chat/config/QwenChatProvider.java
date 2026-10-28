/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Qwen Cloud (Alibaba Model Studio / DashScope) API-key provider.
 *
 * <p>DashScope natively speaks the OpenAI Chat Completions protocol under
 * {@code /compatible-mode/v1}; the international endpoint is the default and
 * the China endpoint is available as an explicit base-URL override. Keys are
 * managed credentials only — there is intentionally no environment-variable
 * fallback.</p>
 */
public final class QwenChatProvider extends StaticChatProvider {
    public static final String INTERNATIONAL_BASE_URL =
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
    public static final String CHINA_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public QwenChatProvider() {
        super("qwen", "Qwen Cloud", INTERNATIONAL_BASE_URL, null);
    }

    @Override
    public String apiKeyAuthLabel() {
        return "API key (DashScope)";
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.qwen();
    }
}

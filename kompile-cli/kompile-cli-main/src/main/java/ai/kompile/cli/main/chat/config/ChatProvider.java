/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/**
 * Runtime descriptor for a direct Kompile Chat provider.
 *
 * <p>The descriptor owns connection/authentication metadata and the model
 * discovery strategy for that provider. Model ids and thinking variants are
 * never maintained as an application-wide catalog.</p>
 */
public interface ChatProvider {
    String id();

    String displayName();

    default String defaultBaseUrl() {
        return null;
    }

    default String environmentVariable() {
        return null;
    }

    default boolean supportsApiKey() {
        return true;
    }

    /**
     * Returns the provider-owned model discovery implementation.
     *
     * <p>The default keeps existing third-party descriptors source-compatible
     * while the built-in providers migrate to explicit strategies.</p>
     */
    default ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ModelDiscoveryHttp::legacy;
    }

    /**
     * Whether discovery needs an HTTP base URL and request credentials.
     * Native provider runtimes override this so a provider switch cannot route
     * their catalog request through another provider's endpoint.
     */
    default boolean modelDiscoveryRequiresBaseUrl() {
        return true;
    }

    /**
     * Resolves thinking controls from the selected model's live metadata, then
     * from that provider's explicitly sourced documented fallback resource.
     */
    default ThinkingCapabilityProvider thinkingCapabilityProvider() {
        return ProviderThinkingCapabilities.forProvider(id());
    }

    /**
     * Context management advertised by this provider. Model-aware gateways may
     * refine this at route resolution time; the default preserves source and
     * binary compatibility for third-party ServiceLoader descriptors.
     */
    default ProviderCompactionCapabilities compactionCapabilities() {
        return ProviderCompactionCapabilities.forProvider(id());
    }

    default boolean localOnly() {
        return false;
    }
}

abstract class StaticChatProvider implements ChatProvider {
    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final String environmentVariable;

    StaticChatProvider(String id, String displayName, String defaultBaseUrl,
                       String environmentVariable) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.environmentVariable = environmentVariable;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    @Override
    public String environmentVariable() {
        return environmentVariable;
    }

}

final class OpenAiCodexChatProvider extends StaticChatProvider {
    OpenAiCodexChatProvider() {
        super("openai-codex", "OpenAI Codex", "https://chatgpt.com/backend-api", null);
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.openAiCodex();
    }

    @Override
    public boolean modelDiscoveryRequiresBaseUrl() {
        return false;
    }
}

final class KompileLocalChatProvider extends StaticChatProvider {
    KompileLocalChatProvider() {
        super("kompile-local", "Kompile local model", null, null);
    }

    @Override
    public boolean localOnly() {
        return true;
    }

    @Override
    public boolean supportsApiKey() {
        return false;
    }

    @Override
    public ModelDiscovery.Strategy modelDiscoveryStrategy() {
        return ProviderModelDiscoveryStrategies.kompileLocal();
    }

    @Override
    public boolean modelDiscoveryRequiresBaseUrl() {
        return false;
    }
}

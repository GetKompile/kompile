/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.util.Locale;

/** Provider-level prompt-cache behavior used by the direct chat transport. */
public record ProviderPromptCacheCapabilities(
        Activation activation,
        UsageDialect usageDialect,
        SessionAffinity sessionAffinity,
        RetentionControl retentionControl) {

    public enum Activation {
        NONE,
        IMPLICIT,
        EXPLICIT,
        ROUTED,
        DELEGATED,
        LOCAL
    }

    public enum UsageDialect {
        NONE,
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC,
        PI_MESSAGES
    }

    public enum SessionAffinity {
        NONE,
        OPENAI_PROMPT_CACHE_KEY,
        XAI_CONVERSATION_HEADER,
        OPENROUTER_SESSION_HEADER,
        PI_MESSAGES_OPTION,
        PROVIDER_SESSION
    }

    public enum RetentionControl {
        NONE,
        OPENAI,
        ANTHROPIC,
        OPENROUTER,
        PI_MESSAGES
    }

    public enum Retention {
        NONE("none"),
        SHORT("short"),
        LONG("long");

        private final String wireValue;

        Retention(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static Retention from(String value) {
            if (value == null || value.isBlank()) return SHORT;
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "none", "off", "disabled" -> NONE;
                case "long", "1h", "24h" -> LONG;
                default -> SHORT;
            };
        }
    }

    public static ProviderPromptCacheCapabilities none() {
        return new ProviderPromptCacheCapabilities(
                Activation.NONE, UsageDialect.NONE,
                SessionAffinity.NONE, RetentionControl.NONE);
    }

    public static ProviderPromptCacheCapabilities forProvider(String providerId) {
        String provider = providerId == null ? ""
                : providerId.strip().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "openai" -> new ProviderPromptCacheCapabilities(
                    Activation.IMPLICIT, UsageDialect.OPENAI_CHAT,
                    SessionAffinity.OPENAI_PROMPT_CACHE_KEY, RetentionControl.OPENAI);
            case "openai-codex" -> new ProviderPromptCacheCapabilities(
                    Activation.IMPLICIT, UsageDialect.OPENAI_RESPONSES,
                    SessionAffinity.OPENAI_PROMPT_CACHE_KEY, RetentionControl.NONE);
            case "anthropic" -> new ProviderPromptCacheCapabilities(
                    Activation.EXPLICIT, UsageDialect.ANTHROPIC,
                    SessionAffinity.NONE, RetentionControl.ANTHROPIC);
            case "xai" -> new ProviderPromptCacheCapabilities(
                    Activation.IMPLICIT, UsageDialect.OPENAI_CHAT,
                    SessionAffinity.XAI_CONVERSATION_HEADER, RetentionControl.NONE);
            case "openrouter" -> new ProviderPromptCacheCapabilities(
                    Activation.ROUTED, UsageDialect.OPENAI_CHAT,
                    SessionAffinity.OPENROUTER_SESSION_HEADER, RetentionControl.OPENROUTER);
            case "radius" -> new ProviderPromptCacheCapabilities(
                    Activation.ROUTED, UsageDialect.PI_MESSAGES,
                    SessionAffinity.PI_MESSAGES_OPTION, RetentionControl.PI_MESSAGES);
            case "opencode" -> new ProviderPromptCacheCapabilities(
                    Activation.DELEGATED, UsageDialect.NONE,
                    SessionAffinity.PROVIDER_SESSION, RetentionControl.NONE);
            case "kompile-local" -> new ProviderPromptCacheCapabilities(
                    Activation.LOCAL, UsageDialect.NONE,
                    SessionAffinity.NONE, RetentionControl.NONE);
            case "gemini", "deepseek", "groq", "zai", "ollama", "custom" ->
                    new ProviderPromptCacheCapabilities(
                            Activation.IMPLICIT, UsageDialect.OPENAI_CHAT,
                            SessionAffinity.NONE, RetentionControl.NONE);
            case "github-copilot" -> new ProviderPromptCacheCapabilities(
                    Activation.DELEGATED, UsageDialect.NONE,
                    SessionAffinity.NONE, RetentionControl.NONE);
            default -> none();
        };
    }
}

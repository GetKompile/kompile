/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** Provider/model capabilities used by the context compaction coordinator. */
public record ProviderCompactionCapabilities(
        NativeCompaction nativeCompaction,
        TokenCounting tokenCounting,
        HistoryOwnership historyOwnership) {

    public enum NativeCompaction {
        NONE,
        ANTHROPIC_MESSAGES,
        OPENAI_RESPONSES,
        OPENCODE_SESSION
    }

    public enum TokenCounting {
        NONE,
        ANTHROPIC_MESSAGES,
        OPENAI_RESPONSES,
        GEMINI
    }

    public enum HistoryOwnership {
        CLIENT,
        PROVIDER
    }

    public static ProviderCompactionCapabilities generic() {
        return new ProviderCompactionCapabilities(
                NativeCompaction.NONE, TokenCounting.NONE, HistoryOwnership.CLIENT);
    }

    public static ProviderCompactionCapabilities forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.strip().toLowerCase();
        return switch (provider) {
            case "anthropic" -> new ProviderCompactionCapabilities(
                    NativeCompaction.ANTHROPIC_MESSAGES,
                    TokenCounting.ANTHROPIC_MESSAGES,
                    HistoryOwnership.CLIENT);
            case "openai-codex" -> new ProviderCompactionCapabilities(
                    NativeCompaction.OPENAI_RESPONSES,
                    TokenCounting.OPENAI_RESPONSES,
                    HistoryOwnership.CLIENT);
            case "gemini" -> new ProviderCompactionCapabilities(
                    NativeCompaction.NONE,
                    TokenCounting.GEMINI,
                    HistoryOwnership.CLIENT);
            case "opencode" -> new ProviderCompactionCapabilities(
                    NativeCompaction.OPENCODE_SESSION,
                    TokenCounting.NONE,
                    HistoryOwnership.PROVIDER);
            default -> generic();
        };
    }
}

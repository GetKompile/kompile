/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Loads direct-chat provider descriptors and native chat providers at runtime. */
public final class ChatProviderRegistry {
    private static final ChatProvider OPENAI_CODEX_ALIAS = new OpenAiCodexChatProvider();
    private static final ChatProvider KOMPILE_LOCAL_ALIAS = new KompileLocalChatProvider();

    private ChatProviderRegistry() {
    }

    public static List<ChatProvider> all() {
        Map<String, ChatProvider> providers = new LinkedHashMap<>();
        try {
            ServiceLoader.load(ChatProvider.class).stream()
                    .map(provider -> {
                        try {
                            return provider.get();
                        } catch (ServiceConfigurationError error) {
                            return null;
                        }
                    })
                    .filter(provider -> provider != null)
                    .forEach(provider -> providers.putIfAbsent(normalize(provider.id()), provider));
        } catch (ServiceConfigurationError ignored) {
            // A broken optional provider must not prevent the remaining providers from loading.
        }

        for (AgentProvider agent : CliAgentRegistry.loadAll()) {
            if (!agent.isChatProvider() || agent.getCommand() == null || agent.getCommand().isBlank()) {
                continue;
            }
            ChatProvider nativeProvider = new NativeChatProvider(
                    agent.getCommand(), agent.getDisplayName(), agent.getDescription());
            providers.putIfAbsent(normalize(nativeProvider.id()), nativeProvider);
        }
        return List.copyOf(providers.values());
    }

    public static List<ChatProvider> directProviders() {
        return all().stream().filter(provider -> !provider.localOnly()).toList();
    }

    public static List<ChatProvider> localProviders() {
        return all().stream().filter(ChatProvider::localOnly).toList();
    }

    public static ChatProvider find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        String normalized = normalize(providerId);
        if ("openai-codex".equals(normalized)) {
            return OPENAI_CODEX_ALIAS;
        }
        if ("kompile-local".equals(normalized)) {
            return KOMPILE_LOCAL_ALIAS;
        }
        return all().stream()
                .filter(provider -> normalized.equals(normalize(provider.id())))
                .findFirst()
                .orElse(null);
    }

    public static String label(String providerId) {
        ChatProvider provider = find(providerId);
        return provider == null ? humanize(providerId) : provider.displayName();
    }

    public static String defaultBaseUrl(String providerId) {
        ChatProvider provider = find(providerId);
        return provider == null ? null : provider.defaultBaseUrl();
    }

    public static String environmentVariable(String providerId) {
        ChatProvider provider = find(providerId);
        return provider == null ? null : provider.environmentVariable();
    }

    public static boolean supportsApiKey(String providerId) {
        ChatProvider provider = find(providerId);
        return provider != null && provider.supportsApiKey();
    }

    public static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (ChatProvider provider : all()) {
            descriptions.put(provider.id(), provider.displayName());
        }
        return Map.copyOf(descriptions);
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    private static String humanize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "Unknown provider";
        }
        String[] parts = providerId.trim().replace('_', '-').split("-");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
            }
        }
        return String.join(" ", words);
    }

    private record NativeChatProvider(String id, String displayName, String description)
            implements ChatProvider {
        @Override
        public String displayName() {
            return displayName == null || displayName.isBlank() ? id : displayName;
        }

        @Override
        public boolean supportsApiKey() {
            return false;
        }
    }
}

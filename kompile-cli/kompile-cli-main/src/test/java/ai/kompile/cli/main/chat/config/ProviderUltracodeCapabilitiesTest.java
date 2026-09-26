/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderUltracodeCapabilitiesTest {
    @Test
    void onlyTheClaudeCodeProviderDeclaresTheDocumentedWireContract() {
        ProviderUltracodeCapabilities anthropic = ProviderUltracodeCapabilities.forProvider("anthropic");
        assertTrue(anthropic.declared());
        assertEquals("ultracode", anthropic.effort());
        assertEquals("xhigh", anthropic.requiresEffort());
        assertTrue(anthropic.sourceUrl().startsWith("https://"));
        assertFalse(anthropic.notice().isBlank());
        assertTrue(ProviderUltracodeCapabilities.forProvider(" Anthropic ").declared());
        for (String provider : List.of("openai", "openai-codex", "custom", "gemini", "openrouter", "opencode")) {
            assertFalse(ProviderUltracodeCapabilities.forProvider(provider).declared(), provider);
        }
        assertFalse(ProviderUltracodeCapabilities.forProvider(null).declared());
        assertFalse(ProviderUltracodeCapabilities.forProvider("../../anthropic").declared());
    }

    @Test
    void eligibilityFollowsTheEffortLevelsTheModelLists() {
        ProviderUltracodeCapabilities anthropic = ProviderUltracodeCapabilities.forProvider("anthropic");
        assertTrue(anthropic.supportsEffortOptions(List.of("", "low", "high", "xhigh", "max")));
        assertTrue(anthropic.supportsEffortOptions(List.of("XHIGH")));
        assertFalse(anthropic.supportsEffortOptions(List.of("", "low", "high", "max")));
        assertFalse(anthropic.supportsEffortOptions(null));
        assertFalse(ProviderUltracodeCapabilities.none().supportsEffortOptions(List.of("xhigh")));
    }
}

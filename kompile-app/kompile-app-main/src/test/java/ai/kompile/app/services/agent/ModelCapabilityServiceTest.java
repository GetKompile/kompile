/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelCapabilityServiceTest {

    private ModelCapabilityService service;

    @BeforeEach
    void setUp() {
        service = new ModelCapabilityService(null);
    }

    // --- supportsVision: vision-capable models ---

    @Test
    void claudeSonnet4SupportsVision() {
        assertTrue(service.supportsVision("claude-sonnet-4"));
    }

    @Test
    void gpt4oSupportsVision() {
        assertTrue(service.supportsVision("gpt-4o"));
    }

    @Test
    void gpt41SupportsVision() {
        assertTrue(service.supportsVision("gpt-4.1"));
    }

    @Test
    void gemini25ProSupportsVision() {
        assertTrue(service.supportsVision("gemini-2.5-pro"));
    }

    @Test
    void o4MiniSupportsVision() {
        assertTrue(service.supportsVision("o4-mini"));
    }

    // --- supportsVision: text-only models ---

    @Test
    void deepseekChatDoesNotSupportVision() {
        assertFalse(service.supportsVision("deepseek-chat"));
    }

    @Test
    void gpt4DoesNotSupportVision() {
        assertFalse(service.supportsVision("gpt-4"));
    }

    @Test
    void unknownModelDoesNotSupportVision() {
        assertFalse(service.supportsVision("llama-3-70b"));
    }

    // --- supportsVision: OpenRouter prefix stripping ---

    @Test
    void openRouterAnthropicPrefixStripped() {
        assertTrue(service.supportsVision("anthropic/claude-sonnet-4"));
    }

    @Test
    void openRouterGooglePrefixStripped() {
        assertTrue(service.supportsVision("google/gemini-2.5-flash"));
    }

    @Test
    void openRouterDeepseekStillTextOnly() {
        assertFalse(service.supportsVision("deepseek/deepseek-chat"));
    }

    // --- supportsVision: Ollama tag stripping ---

    @Test
    void ollamaTagStripped() {
        // e.g. "qwen2.5-coder:32b" → "qwen2.5-coder" — not a vision model
        assertFalse(service.supportsVision("qwen2.5-coder:32b"));
    }

    // --- supportsVision: versioned model IDs ---

    @Test
    void versionedClaudeMatchesByPrefix() {
        assertTrue(service.supportsVision("claude-sonnet-4-20250514"));
    }

    @Test
    void versionedGpt4oMatchesByPrefix() {
        assertTrue(service.supportsVision("gpt-4o-2024-11-20"));
    }

    // --- supportsVision: edge cases ---

    @Test
    void nullReturnsFalse() {
        assertFalse(service.supportsVision(null));
    }

    @Test
    void emptyReturnsFalse() {
        assertFalse(service.supportsVision(""));
    }

    @Test
    void blankReturnsFalse() {
        assertFalse(service.supportsVision("   "));
    }

    // --- getCapabilities ---

    @Test
    void capabilitiesForKnownVisionModel() {
        var caps = service.getCapabilities("claude-sonnet-4");
        assertEquals("claude-sonnet-4", caps.modelId());
        assertTrue(caps.supportsVision());
        assertEquals(200_000, caps.contextWindow());
        assertEquals(16_000, caps.maxOutputTokens());
    }

    @Test
    void capabilitiesForKnownTextOnlyModel() {
        var caps = service.getCapabilities("deepseek-chat");
        assertEquals("deepseek-chat", caps.modelId());
        assertFalse(caps.supportsVision());
        assertEquals(64_000, caps.contextWindow());
        assertEquals(8_192, caps.maxOutputTokens());
    }

    @Test
    void capabilitiesForUnknownModelReturnsDefaults() {
        var caps = service.getCapabilities("some-random-model");
        assertFalse(caps.supportsVision());
        assertEquals(128_000, caps.contextWindow());
        assertEquals(8_192, caps.maxOutputTokens());
    }

    @Test
    void capabilitiesForNullModelReturnsDefaults() {
        var caps = service.getCapabilities(null);
        assertFalse(caps.supportsVision());
        assertEquals(128_000, caps.contextWindow());
    }

    @Test
    void capabilitiesForOpenRouterPrefixedModel() {
        var caps = service.getCapabilities("anthropic/claude-opus-4");
        // Original model ID preserved
        assertEquals("anthropic/claude-opus-4", caps.modelId());
        assertTrue(caps.supportsVision());
        assertEquals(200_000, caps.contextWindow());
    }

    @Test
    void capabilitiesForVersionedModel() {
        var caps = service.getCapabilities("gpt-4o-2024-11-20");
        assertTrue(caps.supportsVision());
        assertEquals(128_000, caps.contextWindow());
    }
}

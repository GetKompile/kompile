/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The descriptor is the documentation gate behind the CHAT_MODEL json_schema
 * capability (NativeChatModels.Selection.supports and
 * DirectLlmClient.applyChatCompletionsJsonOutput both consult it).
 */
class ProviderStructuredOutputCapabilitiesTest {

    @Test
    void documentedChatCompletionsProvidersDeclareJsonSchema() {
        for (String provider : new String[] {"zai", "openai", "groq", "xai", "ollama", "openrouter"}) {
            ProviderStructuredOutputCapabilities caps = ProviderStructuredOutputCapabilities.forProvider(provider);
            assertTrue(caps.supportsJsonSchema(), provider + " must declare documented json_schema support");
            assertTrue(caps.sourceUrl().startsWith("https://"), provider + " source must be https");
            assertFalse(caps.notice().isBlank(), provider + " source must carry a notice");
        }
    }

    @Test
    void zaiIsObjectModeOnlyAndOthersAreNot() {
        // Z.AI documents response_format.type as text|json_object only; json_schema must
        // never be sent there (it yields prose instead of JSON on glm-5.3-flash).
        assertTrue(ProviderStructuredOutputCapabilities.forProvider("zai").isJsonObjectOnly());
        for (String provider : new String[] {"openai", "groq", "xai", "ollama", "openrouter"}) {
            assertFalse(ProviderStructuredOutputCapabilities.forProvider(provider).isJsonObjectOnly(),
                    provider + " documents native json_schema and must not be forced into object mode");
        }
        assertFalse(ProviderStructuredOutputCapabilities.none().isJsonObjectOnly());
    }

    @Test
    void responsesProtocolAndUndeclaredProvidersResolveToNone() {
        // openai-codex rides the Responses protocol (text.format), gated before this descriptor.
        assertFalse(ProviderStructuredOutputCapabilities.forProvider("openai-codex").supportsJsonSchema());
        // No resource / no structuredOutput block / unknown provider → disabled, never an exception.
        for (String provider : new String[] {"anthropic", "gemini", "custom", "github-copilot", "", null}) {
            assertFalse(ProviderStructuredOutputCapabilities.forProvider(provider).supportsJsonSchema(),
                    provider + " must not inherit another provider's capability");
        }
    }

    @Test
    void caseAndWhitespaceInsensitiveProviderLookup() {
        assertTrue(ProviderStructuredOutputCapabilities.forProvider(" ZAI ").supportsJsonSchema());
        assertFalse(ProviderStructuredOutputCapabilities.forProvider("Z.AI").supportsJsonSchema());
    }
}

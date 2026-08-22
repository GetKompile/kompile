package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderCompactionCapabilitiesTest {

    @Test
    void builtInProvidersAdvertiseOnlySupportedNativeFeatures() {
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.ANTHROPIC_MESSAGES,
                ProviderCompactionCapabilities.forProvider("anthropic").nativeCompaction());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.OPENAI_RESPONSES,
                ProviderCompactionCapabilities.forProvider("openai-codex").nativeCompaction());
        assertEquals(ProviderCompactionCapabilities.TokenCounting.GEMINI,
                ProviderCompactionCapabilities.forProvider("gemini").tokenCounting());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.NONE,
                ProviderCompactionCapabilities.forProvider("openrouter").nativeCompaction());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.NONE,
                ProviderCompactionCapabilities.forProvider("github-copilot").nativeCompaction(),
                "a proxy model name must not imply beta endpoint support");
    }

    @Test
    void routeResolutionSeparatesProtocolShapeFromProxyCapabilities() {
        DirectLlmClient anthropic = client("anthropic", "claude-test");
        assertEquals(DirectLlmClient.WireProtocol.ANTHROPIC_MESSAGES,
                anthropic.resolveRoute(null).protocol());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.ANTHROPIC_MESSAGES,
                anthropic.compactionCapabilities(null).nativeCompaction());

        DirectLlmClient codex = client("openai-codex", "gpt-test");
        assertEquals(DirectLlmClient.WireProtocol.OPENAI_RESPONSES,
                codex.resolveRoute(null).protocol());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.OPENAI_RESPONSES,
                codex.compactionCapabilities(null).nativeCompaction());

        DirectLlmClient copilot = client("github-copilot", "claude-sonnet-test");
        assertEquals(DirectLlmClient.WireProtocol.ANTHROPIC_MESSAGES,
                copilot.resolveRoute(null).protocol());
        assertEquals(ProviderCompactionCapabilities.NativeCompaction.NONE,
                copilot.compactionCapabilities(null).nativeCompaction());

        DirectLlmClient gemini = client("gemini", "gemini-test");
        assertEquals(DirectLlmClient.WireProtocol.OPENAI_CHAT,
                gemini.resolveRoute(null).protocol());
        assertEquals(ProviderCompactionCapabilities.TokenCounting.GEMINI,
                gemini.compactionCapabilities(null).tokenCounting());
    }

    private static DirectLlmClient client(String provider, String model) {
        return new DirectLlmClient(
                new ChatConfig(provider, "test", model, ChatConfig.getDefaultBaseUrl(provider)),
                new ObjectMapper());
    }
}

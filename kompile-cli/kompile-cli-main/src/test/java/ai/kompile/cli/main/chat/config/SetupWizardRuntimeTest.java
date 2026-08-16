package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupWizardRuntimeTest {

    @Test
    void standardChatPutsFirstPartyLocalServingBeforeExternalAndInstanceRoutes() {
        List<String> options = SetupWizard.standardRuntimeOptions();

        assertEquals(4, options.size());
        assertTrue(options.get(SetupWizard.StandardRuntime.KOMPILE_LOCAL.ordinal())
                .contains("first-party serving subprocess"));
        assertTrue(options.get(SetupWizard.StandardRuntime.EXTERNAL_LOCAL.ordinal())
                .contains("Ollama"));
        assertTrue(options.get(SetupWizard.StandardRuntime.DIRECT.ordinal()).contains("no Kompile instance"));
        assertTrue(options.get(SetupWizard.StandardRuntime.KOMPILE.ordinal()).contains("Kompile instance"));
    }

    @Test
    void externalLocalRouteOffersOllamaAndOpenAiCompatibleSeparately() {
        assertEquals(List.of("Ollama", "OpenAI-compatible endpoint"),
                SetupWizard.externalLocalOptions());
    }

    @Test
    void directVendorMenuCannotSelectAnInstanceOrDuplicateOpenAiRoutes() {
        List<String> vendors = SetupWizard.directVendorOrder();

        assertFalse(vendors.contains("kompile"));
        assertFalse(vendors.contains("ollama"));
        assertFalse(vendors.contains("openai-codex"));
        assertEquals(1, vendors.stream().filter("openai"::equals).count());
        assertTrue(vendors.contains("anthropic"));
    }

    @Test
    void vendorAuthenticationResolvesToTheCompatibleInternalProvider() {
        assertEquals(List.of("OAuth / subscription sign-in", "API key"),
                SetupWizard.authOptions("openai"));
        assertEquals("openai-codex",
                SetupWizard.resolveProviderForAuth("openai", SetupWizard.AuthMethod.OAUTH));
        assertEquals("openai",
                SetupWizard.resolveProviderForAuth("openai", SetupWizard.AuthMethod.API_KEY));

        assertEquals(List.of("OAuth / subscription sign-in"),
                SetupWizard.authOptions("github-copilot"));
        assertEquals(List.of("API key"), SetupWizard.authOptions("gemini"));
    }

    @Test
    void standardChatOnlyOffersCredentialsMatchingTheSelectedAuthMethod() {
        List<CredentialStore.CredentialInfo> credentials = List.of(
                new CredentialStore.CredentialInfo("anthropic", "personal", "api_key", true),
                new CredentialStore.CredentialInfo("anthropic", "work", "oauth", false));

        assertEquals(List.of("personal"), SetupWizard.compatibleCredentials(
                        credentials, SetupWizard.AuthMethod.API_KEY).stream()
                .map(CredentialStore.CredentialInfo::credentialName)
                .toList());
        assertEquals(List.of("work"), SetupWizard.compatibleCredentials(
                        credentials, SetupWizard.AuthMethod.OAUTH).stream()
                .map(CredentialStore.CredentialInfo::credentialName)
                .toList());
    }

    @Test
    void reasoningModelsUseExactVendorAndModelTerminology() {
        assertTrue(SetupWizard.supportsThinkingSelection("openai-codex", "gpt-5.6-terra"));
        assertTrue(SetupWizard.supportsThinkingSelection("github-copilot", "gpt-5.4"));
        assertTrue(SetupWizard.supportsThinkingSelection("openai", "o4-mini"));
        assertFalse(SetupWizard.supportsThinkingSelection("openai", "gpt-4o"));
        assertFalse(SetupWizard.supportsThinkingSelection("anthropic", "claude-sonnet-4-20250514"));

        List<SetupWizard.ThinkingOption> terra =
                SetupWizard.thinkingOptions("openai-codex", "gpt-5.6-terra");
        assertEquals(List.of("", "low", "medium", "high", "xhigh", "max", "ultra"),
                terra.stream().map(SetupWizard.ThinkingOption::value).toList());
        assertTrue(terra.get(0).label().contains("medium"));
        assertEquals("xhigh", terra.get(4).label());
        assertEquals("ultra", terra.get(terra.size() - 1).label());

        List<SetupWizard.ThinkingOption> sol =
                SetupWizard.thinkingOptions("openai-codex", "gpt-5.6-sol");
        assertTrue(sol.get(0).label().contains("low"));

        assertEquals(List.of("", "low", "medium", "high"),
                SetupWizard.thinkingOptions("xai", "grok-4")
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertEquals(List.of("", "low", "medium", "high"),
                SetupWizard.thinkingOptions("openai", "o4-mini")
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
    }

    @Test
    void blankRuntimeUrlAcceptsItsLocalDefault() {
        assertEquals("http://localhost:11434/v1",
                SetupWizard.valueOrDefault("", "http://localhost:11434/v1"));
        assertEquals("http://localhost:8000/v1",
                SetupWizard.valueOrDefault("  http://localhost:8000/v1  ", "unused"));
    }
}

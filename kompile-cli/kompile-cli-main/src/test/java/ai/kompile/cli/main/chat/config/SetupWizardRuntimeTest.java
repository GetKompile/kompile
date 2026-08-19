package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        assertTrue(vendors.contains("opencode"));
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
    void pickerReusesSetupVendorAuthenticationAndModelSources() {
        List<String> pickerProviders = SetupWizard.providerPickerOrder();
        assertEquals("ollama", pickerProviders.get(0));
        assertEquals("custom", pickerProviders.get(1));
        assertTrue(pickerProviders.containsAll(SetupWizard.directVendorOrder()));
        assertFalse(pickerProviders.contains("openai-codex"));

        assertEquals("openai", SetupWizard.vendorForProvider("openai-codex"));
        assertEquals(SetupWizard.AuthMethod.OAUTH,
                SetupWizard.authMethodForProvider("openai-codex"));
        assertEquals(List.of(SetupWizard.AuthMethod.NONE),
                SetupWizard.authMethodsForPicker("ollama"));
        assertTrue(pickerProviders.contains("opencode"));
        assertEquals(List.of(SetupWizard.AuthMethod.NATIVE),
                SetupWizard.authMethodsForPicker("opencode"));
        assertEquals(List.of("OpenCode native auth (OAuth/API)"),
                SetupWizard.authOptions("opencode"));
        assertEquals(SetupWizard.AuthMethod.NATIVE,
                SetupWizard.authMethodForProvider("opencode"));
        assertEquals(List.of(ChatConfig.getDefaultModels("openai")),
                SetupWizard.modelOptions("openai"));
        assertEquals(List.of(ChatConfig.getDefaultModels("xai")),
                SetupWizard.modelOptions("xai"));
        assertEquals(List.of(ChatConfig.getDefaultModels("github-copilot")),
                SetupWizard.modelOptions("github-copilot"));
        assertTrue(SetupWizard.modelOptions("openai-codex").contains("gpt-5.6-terra"));
        assertTrue(SetupWizard.modelOptions("openai-codex").contains("gpt-5.6-sol"));

        ChatConfig config = new ChatConfig("openai", null, "gpt-4o", null);
        config.addModelToCatalog("openai", "new-model-id");
        assertTrue(SetupWizard.modelOptions("openai", config).contains("new-model-id"));
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
    void legacyOpenAiCodexAccessTokenRemainsASelectableSubscription() {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-test\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        ManagedCredential legacy = ManagedCredential.apiKey("header." + payload + ".signature");

        assertTrue(OAuthCredentialManager.isLegacyOpenAiCodexApiKey("openai-codex", legacy));
        assertFalse(OAuthCredentialManager.isLegacyOpenAiCodexApiKey("openai", legacy));
        assertFalse(OAuthCredentialManager.isLegacyOpenAiCodexApiKey(
                "openai-codex", ManagedCredential.apiKey("not-a-codex-token")));
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

        List<SetupWizard.ThinkingOption> opencode =
                SetupWizard.thinkingOptions("opencode", "opencode-go/deepseek-v4-pro");
        assertEquals("", opencode.get(0).value());
        assertTrue(opencode.size() > 1);
        assertTrue(SetupWizard.isCustomThinkingSelection("opencode", opencode.get(1).value()));
    }

    @Test
    void blankRuntimeUrlAcceptsItsLocalDefault() {
        assertEquals("http://localhost:11434/v1",
                SetupWizard.valueOrDefault("", "http://localhost:11434/v1"));
        assertEquals("http://localhost:8000/v1",
                SetupWizard.valueOrDefault("  http://localhost:8000/v1  ", "unused"));
    }
}

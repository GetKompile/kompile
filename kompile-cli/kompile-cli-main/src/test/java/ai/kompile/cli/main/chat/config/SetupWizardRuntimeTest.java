package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupWizardRuntimeTest {

    @Test
    void chatModeMenuOffersSingleAndBatchResumeActions() {
        List<String> options = SetupWizard.chatModeOptions();

        assertEquals(4, options.size());
        assertTrue(options.get(2).contains("Resume Previous"));
        assertTrue(options.get(3).contains("Resume All"));
        assertTrue(options.get(3).contains("last 30 minutes"));
        assertEquals("--active-within 30", SetupWizard.resumeAllArguments());
        assertEquals(List.of("standard", "passthrough", "resume", "resume-all"),
                SetupWizard.chatModeValues());
    }

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
        // OAuth-only integration vendors are not LLM vendors and must never
        // appear in the chat vendor menus.
        for (ai.kompile.cli.main.auth.oauth.OAuthProviderFlow flow
                : new ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry().flows()) {
            String integrationVendor = flow.userFacingProviderId();
            if (ChatProviderRegistry.find(integrationVendor) == null) {
                assertFalse(vendors.contains(integrationVendor),
                        integrationVendor + " owns no chat provider and must not appear "
                                + "in the LLM vendor menu");
            }
        }
        assertEquals(1, vendors.stream().filter("openai"::equals).count());
        assertTrue(vendors.contains("anthropic"));
        assertTrue(vendors.contains("zai"));
        assertEquals("Z.AI GLM Coding Plan", SetupWizard.vendorLabel("zai"));
        assertTrue(vendors.contains("opencode"));
        assertTrue(SetupWizard.vendorLabel("opencode")
                .contains("requires installed 'opencode' CLI"));
        assertTrue(SetupWizard.vendorLabel("pi")
                .contains("requires installed 'pi' CLI"));
        assertTrue(ChatConfig.getPassthroughAgents().get("opencode")
                .contains("requires installed 'opencode' CLI"));
        assertTrue(ChatConfig.getPassthroughAgents().get("pi")
                .contains("requires installed 'pi' CLI"));
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
        assertEquals(List.of("GLM Coding Plan subscription API key"),
                SetupWizard.authOptions("zai"));
        assertEquals("zai",
                SetupWizard.resolveProviderForAuth("zai", SetupWizard.AuthMethod.API_KEY));
        assertEquals("https://api.z.ai/api/coding/paas/v4",
                ChatConfig.getDefaultBaseUrl("zai"));
        assertEquals(List.of("None", "API key"), SetupWizard.authOptions("custom"));
        assertEquals("custom",
                SetupWizard.resolveProviderForAuth("custom", SetupWizard.AuthMethod.API_KEY));
    }

    @Test
    void pickerReusesSetupVendorAuthenticationAndModelSources() {
        List<String> pickerProviders = SetupWizard.providerPickerOrder();
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
        assertEquals(List.of("Native CLI authentication (requires installed provider CLI)"),
                SetupWizard.authOptions("opencode"));
        assertEquals(SetupWizard.AuthMethod.NATIVE,
                SetupWizard.authMethodForProvider("opencode"));
        assertEquals(0, ChatConfig.getDefaultModels("openai").length);
        assertTrue(ChatProviderRegistry.directProviders().stream()
                .anyMatch(provider -> "opencode".equals(provider.id())));

        ChatConfig config = new ChatConfig("openai", null, "gpt-4o", null);
        assertFalse(config.addModelToCatalog("openai", "new-model-id"));
        assertTrue(config.getModelCatalog().isEmpty());
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
    void thinkingControlsPreferLiveMetadataAndUseDocumentedProviderFallbacks() {
        assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                SetupWizard.thinkingOptions("openai-codex", "gpt-5.6-terra")
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                SetupWizard.thinkingOptions("github-copilot", "gpt-5.6-sol")
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertEquals(List.of("", "low", "medium", "high"),
                SetupWizard.thinkingOptions("openai", "o3")
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertTrue(SetupWizard.thinkingOptions("unknown-provider", "unknown-model").isEmpty());

        ModelDiscovery.Result liveVariants = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model(
                        "dynamic-model",
                        List.of("wire-low", "wire-medium"),
                        Map.of("wire-low", "Low", "wire-medium", "Medium"),
                        "wire-medium")),
                List.of("https://example.test/v1/models"));
        List<SetupWizard.ThinkingOption> options = SetupWizard.thinkingOptions(
                "openai", "dynamic-model", null, null, liveVariants);

        assertEquals(List.of("", "wire-low", "wire-medium"),
                options.stream().map(SetupWizard.ThinkingOption::value).toList());
        assertEquals(List.of(
                        "provider/model default (wire-medium, recommended)",
                        "Low",
                        "Medium"),
                options.stream().map(SetupWizard.ThinkingOption::label).toList());
        assertEquals("wire-medium", SetupWizard.compatibleThinking(
                "openai", "dynamic-model", "wire-medium", liveVariants));
        assertNull(SetupWizard.compatibleThinking(
                "openai", "dynamic-model", "invented", liveVariants));

        ModelDiscovery.Result noThinking = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("gpt-5.6-terra", List.of())),
                List.of("https://example.test/v1/models"));
        assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                SetupWizard.thinkingOptions(
                                "openai-codex", "gpt-5.6-terra", null, null, noThinking)
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertTrue(SetupWizard.thinkingOptions(
                "openai", "gpt-4o", null, null, noThinking).isEmpty());
    }

    @Test
    void modelOptionsContainOnlyLiveProviderCatalogEntries() {
        ModelDiscovery.Result live = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("live-model", List.of())),
                List.of("https://example.test/v1/models"));
        assertEquals(List.of("live-model"),
                SetupWizard.modelOptions(live, "configured-model"));

        ModelDiscovery.Result outage = ModelDiscovery.Result.failure(
                ModelDiscovery.Status.UNAVAILABLE, "offline", List.of());
        assertEquals(List.of(),
                SetupWizard.modelOptions(outage, "configured-model"));
    }

    @Test
    void openAiPickersAlwaysIncludeCurrentDocumentedModels() {
        ModelDiscovery.Result live = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("account-specific-model", List.of())),
                List.of("native:codex app-server/model/list"));

        assertEquals(List.of(
                        "gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                        "gpt-5.3-codex-spark", "account-specific-model"),
                SetupWizard.modelOptions("openai-codex", live, null));
        assertEquals(List.of(
                        "gpt-6-astra", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                        "account-specific-model"),
                SetupWizard.modelOptions("openai", live, null));
    }

    @Test
    void blankRuntimeUrlAcceptsItsLocalDefault() {
        assertEquals("http://localhost:11434/v1",
                SetupWizard.valueOrDefault("", "http://localhost:11434/v1"));
        assertEquals("http://localhost:8000/v1",
                SetupWizard.valueOrDefault("  http://localhost:8000/v1  ", "unused"));
    }
}

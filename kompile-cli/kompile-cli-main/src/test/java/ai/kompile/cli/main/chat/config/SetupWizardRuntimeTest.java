package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.core.llm.CliModelCatalog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupWizardRuntimeTest {

    private static final String THINKING_CATALOG_PROP = "kompile.cli.modelCatalogPaths";

    /** Point the catalog at an absent file so documented-fallback assertions are deterministic. */
    private static void isolateThinkingCatalog() throws Exception {
        System.setProperty(THINKING_CATALOG_PROP,
                java.nio.file.Path.of("nonexistent-thinking-catalog.json").toAbsolutePath().toString());
        java.lang.reflect.Method invalidate = CliModelCatalog.class
                .getDeclaredMethod("invalidateCacheForTest");
        invalidate.setAccessible(true);
        invalidate.invoke(null);
    }

    private static void restoreThinkingCatalog() throws Exception {
        System.clearProperty(THINKING_CATALOG_PROP);
        java.lang.reflect.Method invalidate = CliModelCatalog.class
                .getDeclaredMethod("invalidateCacheForTest");
        invalidate.setAccessible(true);
        invalidate.invoke(null);
    }

    private static org.jline.reader.LineReader reader(String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (org.jline.reader.LineReader) java.lang.reflect.Proxy.newProxyInstance(
                org.jline.reader.LineReader.class.getClassLoader(),
                new Class<?>[]{org.jline.reader.LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new org.jline.reader.EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }

    @Test
    void chatModeMenuOffersWorkflowAndSingleAndBatchResumeActions() {
        List<String> options = SetupWizard.chatModeOptions();

        assertEquals(5, options.size());
        assertTrue(options.get(2).contains("Workflow"));
        assertTrue(options.get(3).contains("Resume Previous"));
        assertTrue(options.get(4).contains("Resume All"));
        assertEquals("--active-within 30", SetupWizard.resumeAllArguments());
        assertEquals(List.of("standard", "passthrough", "workflow", "resume", "resume-all"),
                SetupWizard.chatModeValues());
    }

    @Test
    void resumeAllWizardOffersTheFixedIntervalLadderAndMapsItToMinutes() {
        assertEquals(List.of("30 minutes", "1 hour", "2 hours", "4 hours", "8 hours", "24 hours"),
                SetupWizard.RESUME_ALL_INTERVAL_LABELS);
        assertEquals(List.of(30, 60, 120, 240, 480, 1440),
                SetupWizard.RESUME_ALL_INTERVAL_MINUTES);

        // Selection 1 = 30 minutes, selection 6 = 24 hours; cancel returns null
        // so nothing is launched.
        assertEquals(30, SetupWizard.selectResumeAllWindow(reader("1")));
        assertEquals(60, SetupWizard.selectResumeAllWindow(reader("2")));
        assertEquals(120, SetupWizard.selectResumeAllWindow(reader("3")));
        assertEquals(240, SetupWizard.selectResumeAllWindow(reader("4")));
        assertEquals(480, SetupWizard.selectResumeAllWindow(reader("5")));
        assertEquals(1440, SetupWizard.selectResumeAllWindow(reader("6")));
        assertNull(SetupWizard.selectResumeAllWindow(reader("cancel")));
        assertEquals("--active-within 480", SetupWizard.resumeAllArguments(480));
        assertEquals("30 minutes", SetupWizard.describeResumeAllWindow(30));
        assertEquals("2 hours", SetupWizard.describeResumeAllWindow(120));
        assertEquals("24 hours", SetupWizard.describeResumeAllWindow(1440));
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
        assertEquals("Z.AI", SetupWizard.vendorLabel("zai"));
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
        assertEquals(List.of("API key (subscription)", "API key (credits)"),
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
    void anthropicOauthStaysExternallyManagedWhileOtherOauthVendorsKeepTheirWireProvider() {
        // Anthropic OAuth is the user's Claude Code subscription login: the vendor
        // id is the provider and Kompile never resolves a managed credential for it.
        assertEquals("anthropic",
                SetupWizard.resolveProviderForAuth("anthropic", SetupWizard.AuthMethod.OAUTH));

        // Every other OAuth vendor keeps the registry mapping (openai's OAuth
        // credential wire id is openai-codex; identity flows map to themselves).
        assertEquals("openai-codex",
                SetupWizard.resolveProviderForAuth("openai", SetupWizard.AuthMethod.OAUTH));
        assertEquals("github-copilot",
                SetupWizard.resolveProviderForAuth("github-copilot", SetupWizard.AuthMethod.OAUTH));
        assertEquals("xai",
                SetupWizard.resolveProviderForAuth("xai", SetupWizard.AuthMethod.OAUTH));
        assertEquals("openrouter",
                SetupWizard.resolveProviderForAuth("openrouter", SetupWizard.AuthMethod.OAUTH));
        assertEquals("radius",
                SetupWizard.resolveProviderForAuth("radius", SetupWizard.AuthMethod.OAUTH));
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
    void thinkingControlsPreferLiveMetadataAndUseDocumentedProviderFallbacks() throws Exception {
        // These assertions verify the documented-fallback ladder (and live-vs-fallback
        // precedence); the catalog stage must be isolated so the real models.dev file
        // on this machine cannot shadow the fallback being tested.
        try {
            isolateThinkingCatalog();

            assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                    SetupWizard.thinkingOptions("openai-codex", "gpt-5.6-terra")
                            .stream().map(SetupWizard.ThinkingOption::value).toList());
            assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                    SetupWizard.thinkingOptions("github-copilot", "gpt-5.6-sol")
                            .stream().map(SetupWizard.ThinkingOption::value).toList());
            assertEquals(List.of("", "low", "medium", "high"),
                    SetupWizard.thinkingOptions("openai", "o3")
                            .stream().map(SetupWizard.ThinkingOption::value).toList());
        } finally {
            restoreThinkingCatalog();
        }
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
    void claudeSubscriptionRouteOffersTheEffortLevelsItsListingReports() {
        // The wizard and /model picker read effort levels from the Claude Code
        // route's own discovery result; the chosen value reaches claude -p as
        // --effort.
        ModelDiscovery.Result discovery = ModelDiscoveryHttp.claudeCliResult(
                LiveModelDiscovery.parseModelsApiResponse("""
                        {"data":[
                          {"id":"claude-opus-5-5","capabilities":{"effort":{"supported":true,
                            "low":{"supported":true},"medium":{"supported":true},
                            "high":{"supported":true},"xhigh":{"supported":true},
                            "max":{"supported":true}}}},
                          {"id":"claude-sonnet-4-6","capabilities":{"effort":{"supported":true,
                            "low":{"supported":true},"medium":{"supported":true},
                            "high":{"supported":true},"xhigh":{"supported":false},
                            "max":{"supported":true}}}}
                        ]}
                        """));

        assertEquals(List.of("", "low", "medium", "high", "xhigh", "max"),
                SetupWizard.thinkingOptions("anthropic", "claude-opus-5-5", null, null, discovery)
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertEquals(List.of("", "low", "medium", "high", "max"),
                SetupWizard.thinkingOptions("anthropic", "claude-sonnet-4-6", null, null, discovery)
                        .stream().map(SetupWizard.ThinkingOption::value).toList());
        assertTrue(SetupWizard.supportsThinkingSelection(
                "anthropic", "claude-opus-5-5", null, null, discovery));
        assertEquals("xhigh", SetupWizard.compatibleThinking(
                "anthropic", "claude-opus-5-5", "xhigh", discovery));
        // A level the newly selected model does not list is dropped rather
        // than carried over from the previous model.
        assertNull(SetupWizard.compatibleThinking(
                "anthropic", "claude-sonnet-4-6", "xhigh", discovery));
        // Ultracode runs at xhigh, so only a model whose listing carries it offers the toggle.
        assertEquals(List.of("off", "on"),
                SetupWizard.ultracodeOptions("anthropic", "claude-opus-5-5", discovery));
        assertTrue(SetupWizard.ultracodeOptions("anthropic", "claude-sonnet-4-6", discovery).isEmpty());
        assertTrue(SetupWizard.ultracodeOptions("openai-codex", "claude-opus-5-5", discovery).isEmpty());
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
    void openAiPickersRenderOnlyTheLiveProviderCatalog() {
        ModelDiscovery.Result live = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("account-specific-model", List.of())),
                List.of("native:codex app-server/model/list"));

        // Live discovery is authoritative for the OpenAI providers too. The
        // compiled-in documented-models prefix used to shadow day-one releases
        // and leak stale ids into the persisted last-known-good store.
        assertEquals(List.of("account-specific-model"),
                SetupWizard.modelOptions("openai-codex", live, null));
        assertEquals(List.of("account-specific-model"),
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

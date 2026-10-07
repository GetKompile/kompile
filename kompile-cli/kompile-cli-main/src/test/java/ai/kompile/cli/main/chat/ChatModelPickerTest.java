package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscoveryHttp;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real /model modal and credential resolution; external runtimes/discovery are isolated. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatModelPickerTest {
    @TempDir Path directory;
    private String previousHome;
    private CredentialStore store;

    @BeforeEach
    void isolateCredentials() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(directory.resolve("home")).toString());
        store = CredentialStore.create();
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void modelOnlyRequiresOneAnswerAndKeepsInlineKeyAndEndpoint() throws Exception {
        ChatConfig config = config("custom", "inline-key", "api-key", "http://endpoint.invalid/v1");
        try (Picker picker = new Picker(config, models("next-model"))) {
            // Also cover an explicit key supplied after the session was bound.
            picker.repl.getChatConfig().setApiKey("inline-key");
            picker.choose("/model", answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("next-model", active.getModel());
            assertEquals("inline-key", active.getApiKey());
            assertEquals("http://endpoint.invalid/v1", active.getBaseUrl());
            assertEquals("api-key", active.getAuthenticationMethod());
            assertEquals("inline-key", picker.calls.get(0).auth().token());
            assertEquals("next-model", ChatConfig.loadSession("model-picker-test").getModel());
        }
    }

    @Test
    void modelOnlyKeepsSessionAccountInsteadOfGlobalOrLastUsedAccount() throws Exception {
        store.putApiKey("openai", "session-account", "session-key", true);
        store.putApiKey("openai", "other-account", "other-key", true);
        ChatConfig config = config("openai", null, "api-key", null);
        config.setCredentialName("session-account");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/model", answer("picker model", "next-model"));
            assertEquals("session-account", picker.repl.getChatConfig().getCredentialName());
            assertEquals("session-key", picker.calls.get(0).auth().token());
            assertEquals("other-account", store.activeCredentialName("openai"));
            assertEquals(2, store.list("openai").size(), "no new credential is created by switching models");
        }
    }

    @Test
    void returningToActiveVendorKeepsOriginalCredentialsAndEndpoint() throws Exception {
        store.putApiKey("zai", "target", "target-key", true);
        ChatConfig config = config("custom", "source-key", "api-key", "http://endpoint.invalid/v1");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.repl.getChatConfig().setApiKey("source-key");
            picker.choose("/model", answer("picker model", "provider"),
                    answer("picker provider", "zai"), answer("picker model", "provider"),
                    answer("picker provider", "custom"), answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("custom", active.getProvider());
            assertEquals("next-model", active.getModel());
            assertEquals("source-key", active.getApiKey());
            assertEquals("http://endpoint.invalid/v1", active.getBaseUrl());
            assertEquals("source-key", picker.calls.get(2).auth().token());
            assertEquals(active.getBaseUrl(), picker.calls.get(2).baseUrl());
        }
    }

    @Test
    void vendorSwitchReusesRememberedCredentialsWithoutAuthenticationPrompts() throws Exception {
        store.putApiKey("zai", "global-account", "global-key", true);
        store.putApiKey("zai", "remembered-account", "remembered-key", false);
        store.recordUsed("zai", "remembered-account");
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/model", answer("picker model", "provider"),
                    answer("picker provider", "zai"), answer("picker model", "1"));
            assertEquals("zai", picker.repl.getChatConfig().getProvider());
            assertEquals("remembered-account", picker.repl.getChatConfig().getCredentialName());
            assertEquals("remembered-key", picker.calls.get(1).auth().token());
            assertEquals("global-account", store.activeCredentialName("zai"));
        }
    }

    @Test
    void authCommandStartsAtVendorAndReusesTargetCredentials() throws Exception {
        store.putApiKey("zai", "target", "target-key", true);
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("target-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", ""), answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("zai", active.getProvider());
            assertEquals("target-model", active.getModel());
            assertEquals("target", active.getCredentialName());
            assertEquals(1, picker.calls.size(), "only the selected vendor's catalog is requested");
            assertEquals("zai", picker.calls.get(0).provider());
            assertEquals("target-key", picker.calls.get(0).auth().token());
            ChatConfig saved = ChatConfig.loadSession("model-picker-test");
            assertEquals("zai", saved.getProvider());
            assertEquals("target-model", saved.getModel());
            picker.choose("/model", answer("picker model", "1"));
            assertEquals("zai", picker.calls.get(1).provider());
        }
    }

    @Test
    void cancellingAuthVendorPickerLeavesSessionUnchanged() throws Exception {
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "cancel"));
            assertTrue(picker.calls.isEmpty());
            assertEquals("openai", picker.repl.getChatConfig().getProvider());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void cancellingAuthAfterVendorSelectionLeavesSessionUnchanged() throws Exception {
        store.putApiKey("zai", "target", "target-key", true);
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", ""), answer("picker model", "cancel"));
            assertEquals("openai", picker.repl.getChatConfig().getProvider());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
            assertEquals("openai", ChatConfig.loadSession("model-picker-test").getProvider());
        }
    }

    @Test
    void authListRemainsNonInteractive() throws Exception {
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth list");
            verify(picker.reader, never()).readLine(anyString());
            assertTrue(picker.calls.isEmpty());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void providerCommandStartsAtVendorAndKeepsTargetSessionPin() throws Exception {
        store.putApiKey("zai", "pinned", "pinned-key", true);
        store.putApiKey("zai", "global", "global-key", true);
        ChatConfig config = config("zai", null, "api-key", null);
        config.setCredentialName("pinned");
        config.setProvider("custom");
        config.setAuthenticationMethod("none");
        config.setBaseUrl("http://endpoint.invalid/v1");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/provider", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", ""), answer("picker model", "1"));
            assertEquals("pinned", picker.repl.getChatConfig().getCredentialName());
            assertEquals("pinned-key", picker.calls.get(0).auth().token());
            assertEquals("global", store.activeCredentialName("zai"));
        }
    }

    @Test
    void authCommandChoosesWhichStoredCredentialTheSessionUses() throws Exception {
        store.putApiKey("zai", "first", "first-key", true);
        store.putApiKey("zai", "second", "second-key", false);
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", "2"), answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("zai", active.getProvider());
            assertEquals("second", active.getCredentialName());
            assertEquals("second-key", picker.calls.get(0).auth().token());
            assertEquals("first", store.activeCredentialName("zai"), "a session choice keeps the global default");
            assertEquals("second", ChatConfig.loadSession("model-picker-test").getCredentialName());
        }
    }

    @Test
    void authCommandAddsAnotherCredentialForThisSession() throws Exception {
        store.putApiKey("custom", "existing", "existing-key", true);
        ChatConfig config = config("custom", null, "api-key", "http://endpoint.invalid/v1");
        config.setCredentialName("existing");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/auth", answer("picker provider", ""), answer("picker auth", ""),
                    answer(">", "2"), answer("  API key", "fixture-added-key"), answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("custom", active.getProvider());
            assertTrue(active.getCredentialName().startsWith("session-"), active.getCredentialName());
            assertEquals("fixture-added-key", picker.calls.get(0).auth().token());
            assertEquals("http://endpoint.invalid/v1", active.getBaseUrl());
            assertEquals(2, store.list("custom").size());
            assertEquals("existing", store.activeCredentialName("custom"));
            assertEquals(active.getCredentialName(), ChatConfig.loadSession("model-picker-test").getCredentialName());
        }
    }

    @Test
    void leavingTheCredentialPageReturnsToTheRoutePage() throws Exception {
        store.putApiKey("zai", "target", "target-key", true);
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", "q"), answer("picker auth", "cancel"));
            assertTrue(picker.calls.isEmpty());
            assertEquals("openai", picker.repl.getChatConfig().getProvider());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void backReturnsFromTheModelThroughTheAuthenticationPagesToTheVendor() throws Exception {
        store.putApiKey("zai", "target", "target-key", true);
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""), answer(">", ""),
                    answer("picker model", "back"), answer("picker auth", "back"), answer("picker provider", "back"));
            assertEquals(1, picker.calls.size());
            assertEquals("openai", picker.repl.getChatConfig().getProvider());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void globalScopeAuthMakesTheChosenCredentialTheVendorDefault() throws Exception {
        store.putApiKey("zai", "first", "first-key", true);
        store.putApiKey("zai", "second", "second-key", false);
        ChatConfig config = config("openai", "source-key", "api-key", null);
        config.setAuthenticationScope("global");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/auth", answer("picker provider", "zai"), answer("picker auth", ""),
                    answer(">", "2"), answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("zai", active.getProvider());
            assertNull(active.getCredentialName());
            assertEquals("second", store.activeCredentialName("zai"));
            assertEquals("second-key", picker.calls.get(0).auth().token());
        }
    }

    @Test
    void authCommandUsesTheClaudeCodeLoginWithoutACredentialPage() throws Exception {
        try (Picker picker = new Picker(config("openai", "source-key", "api-key", null), models("claude-opus-5-5"))) {
            picker.choose("/auth", answer("picker provider", "anthropic"), answer("picker auth", "1"),
                    answer("picker model", "1"));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("anthropic", active.getProvider());
            assertEquals("oauth", active.getAuthenticationMethod());
            assertEquals("claude-opus-5-5", active.getModel());
            assertTrue(picker.calls.isEmpty(), "the Claude Code route needs no API-key discovery");
            picker.discovery.verify(ModelDiscoveryHttp::discoverClaudeCliResult);
            assertTrue(store.list("anthropic").isEmpty(), "Claude Code owns authentication");
        }
    }

    @Test
    void vendorSwitchUsesSavedOAuthRouteWithoutFlatteningProviderHeaders() throws Exception {
        store.put("openai-codex", "subscription", ManagedCredential.oauth(
                "codex-access", "refresh-token", Long.MAX_VALUE, Map.of("accountId", "account-123")), true);
        try (Picker picker = new Picker(config("custom", null, "none", "http://endpoint.invalid/v1"),
                models("gpt-5.5"))) {
            picker.choose("/model", answer("picker model", "provider"),
                    answer("picker provider", "openai"), answer("picker model", "1"), answer("picker thinking", ""));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("openai-codex", active.getProvider());
            assertEquals("oauth", active.getAuthenticationMethod());
            assertEquals("subscription", active.getCredentialName());
            assertTrue(active.resolveRequestAuth().oauth());
            assertEquals("account-123", picker.calls.get(1).auth().headers().get("chatgpt-account-id"));
        }
    }

    @Test
    void credentialsAreAnExplicitOptionAndDoNotChangeGlobalSelection() throws Exception {
        store.putApiKey("custom", "first", "first-key", true);
        store.putApiKey("custom", "second", "second-key", false);
        ChatConfig config = config("custom", null, "api-key", "http://endpoint.invalid/v1");
        config.setCredentialName("first");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/model", answer("picker model", "credentials"),
                    answer("picker auth", "2"), answer(">", "2"), answer("picker model", "1"));
            assertEquals("second", picker.repl.getChatConfig().getCredentialName());
            assertEquals("second-key", picker.calls.get(1).auth().token());
            assertEquals("first", store.activeCredentialName("custom"));
            assertEquals("http://endpoint.invalid/v1", picker.repl.getChatConfig().getBaseUrl());
        }
    }

    @Test
    void cancellingAfterOptionalCredentialSelectionLeavesActiveConfigUnchanged() throws Exception {
        store.putApiKey("custom", "first", "first-key", true);
        store.putApiKey("custom", "second", "second-key", false);
        ChatConfig config = config("custom", null, "api-key", "http://endpoint.invalid/v1");
        config.setCredentialName("first");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/model", answer("picker model", "credentials"),
                    answer("picker auth", "2"), answer(">", "2"), answer("picker model", "cancel"));
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
            assertEquals("first", picker.repl.getChatConfig().getCredentialName());
            assertEquals("first-key", picker.repl.getChatConfig().getApiKey());
            assertEquals("first", ChatConfig.loadSession("model-picker-test").getCredentialName());
        }
    }

    @Test
    void missingCredentialsOfferRecoveryWithoutForcingLogin() throws Exception {
        ChatConfig config = config("custom", null, "api-key", "http://endpoint.invalid/v1");
        try (Picker picker = new Picker(config, models("next-model"))) {
            picker.choose("/model", answer("Authentication failed", "cancel"));
            assertTrue(picker.calls.isEmpty());
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void deletedTargetSessionPinFailsClosedInsteadOfSilentlyChoosingAnotherAccount() throws Exception {
        store.putApiKey("zai", "other-account", "other-key", true);
        ChatConfig config = config("zai", null, "api-key", null);
        config.setCredentialName("missing-account");
        config.setProvider("custom");
        ChatConfig target = SetupWizard.modelPickerConfigForVendor(config, "zai");
        assertEquals("missing-account", target.getCredentialName());
        assertEquals(ModelDiscovery.Status.AUTH_REQUIRED, SetupWizard.modelDiscovery("zai", null, target).status());
        assertEquals("other-account", store.activeCredentialName("zai"));
    }

    @Test
    void globalScopeReusesGlobalAccountRatherThanSessionDefault() throws Exception {
        store.putApiKey("zai", "global", "global-key", true);
        store.putApiKey("zai", "last-used", "last-key", false);
        store.recordUsed("zai", "last-used");
        ChatConfig config = config("openai", "source-key", "api-key", null);
        config.setAuthenticationScope("global");
        ChatConfig target = SetupWizard.modelPickerConfigForVendor(config, "zai");
        assertEquals("global", target.getAuthenticationScope());
        assertNull(target.getCredentialName());
        assertEquals("global-key", target.resolveRequestAuth().token());
    }

    @Test
    void sameVendorKeepsBillingEndpointAndAuthenticationRoute() throws Exception {
        ChatConfig config = config("zai", "inline-key", "api-key", "https://api.z.ai/api/paas/v4");
        config.setThinking("high");
        ChatConfig target = SetupWizard.modelPickerConfigForVendor(config, "zai");
        assertNotSame(config, target);
        assertEquals(config.getBaseUrl(), target.getBaseUrl());
        assertEquals(config.getAuthenticationMethod(), target.getAuthenticationMethod());
        assertEquals(config.getModel(), target.getModel());
        assertEquals("inline-key", target.getApiKey());
        assertEquals("high", target.getThinking());
    }

    @Test
    void claudeCodeModelOnlyKeepsCompatibleSettingsWithEffortPageWithoutCredentials() throws Exception {
        ChatConfig config = config("anthropic", null, "oauth", null);
        config.setModel("claude-opus-5-5");
        config.setThinking("high");
        config.setUltracode(true);
        ModelDiscovery.Result models = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("claude-opus-5-5", List.of("high", "xhigh")),
                new LiveModelDiscovery.Model("claude-opus-5-6", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config, models)) {
            picker.choose("/model", answer("picker model", "2"), answer("picker thinking", ""));
            assertEquals("claude-opus-5-6", picker.repl.getChatConfig().getModel());
            assertEquals("oauth", picker.repl.getChatConfig().getAuthenticationMethod());
            assertEquals("high", picker.repl.getChatConfig().getThinking());
            assertTrue(picker.repl.getChatConfig().isUltracode());
            picker.discovery.verify(ModelDiscoveryHttp::discoverClaudeCliResult);
            assertTrue(store.list("anthropic").isEmpty(), "Claude Code owns authentication");
        }
    }

    @Test
    void oauthModelOnlyKeepsFastModeAndEffortWhenThinkingIsBlank() throws Exception {
        store.put("openai-codex", "subscription", ManagedCredential.oauth(
                "codex-access", "refresh-token", Long.MAX_VALUE, Map.of("accountId", "account-123")), true);
        ChatConfig config = config("openai-codex", null, "oauth", null);
        config.setCredentialName("subscription");
        config.setModel("gpt-5.5");
        config.setFastMode(true);
        config.setThinking("high");
        ModelDiscovery.Result models = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("gpt-5.6-sol", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config, models)) {
            picker.choose("/model", answer("picker model", "1"), answer("picker thinking", ""));
            ChatConfig active = picker.repl.getChatConfig();
            assertEquals("subscription", active.getCredentialName());
            assertEquals("oauth", active.getAuthenticationMethod());
            assertTrue(active.isFastMode());
            assertEquals("high", active.getThinking());
            assertTrue(picker.calls.get(0).auth().oauth());
            assertEquals("account-123", picker.calls.get(0).auth().headers().get("chatgpt-account-id"));
        }
    }

    @Test
    void modelPickerSelectsAndPersistsThinkingFromDiscovery() throws Exception {
        ModelDiscovery.Result discovery = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("reasoning-model", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config("openai", "key", "api-key", null), discovery)) {
            picker.choose("/model", answer("picker model", "1"), answer("picker thinking", "bogus"),
                    answer("picker thinking", "xhigh"));
            assertEquals("xhigh", picker.repl.getChatConfig().getThinking());
            assertEquals("xhigh", ChatConfig.loadSession("model-picker-test").getThinking());
            assertEquals(1, picker.calls.size(), "effort selection reuses the model discovery result");
        }
    }

    @Test
    void modelPickerCanClearThinkingToProviderDefault() throws Exception {
        ChatConfig config = config("openai", "key", "api-key", null);
        config.setThinking("high");
        ModelDiscovery.Result discovery = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("reasoning-model", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config, discovery)) {
            picker.choose("/model", answer("picker model", "1"), answer("picker thinking", "1"));
            assertNull(picker.repl.getChatConfig().getThinking());
            assertNull(ChatConfig.loadSession("model-picker-test").getThinking());
        }
    }

    @Test
    void cancellingThinkingLeavesModelCredentialsAndEffortUnchanged() throws Exception {
        ChatConfig config = config("openai", "key", "api-key", null);
        config.setThinking("high");
        ModelDiscovery.Result discovery = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("reasoning-model", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config, discovery)) {
            picker.choose("/model", answer("picker model", "1"), answer("picker thinking", "cancel"));
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
            assertEquals("high", picker.repl.getChatConfig().getThinking());
            assertEquals("original-model", ChatConfig.loadSession("model-picker-test").getModel());
        }
    }

    @Test
    void thinkingBackReturnsToModelsWithoutApplyingTheCandidate() throws Exception {
        ModelDiscovery.Result discovery = ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("reasoning-model", List.of("high", "xhigh"))), List.of());
        try (Picker picker = new Picker(config("openai", "key", "api-key", null), discovery)) {
            picker.choose("/model", answer("picker model", "1"), answer("picker thinking", "back"),
                    answer("picker model", "cancel"));
            assertEquals("original-model", picker.repl.getChatConfig().getModel());
        }
    }

    @Test
    void thinkingCommandValidatesAndPersistsWithoutRediscoveringModels() throws Exception {
        ChatConfig config = config("openai", "key", "api-key", null);
        config.setModel("gpt-5.5");
        try (Picker picker = new Picker(config, models("gpt-5.5"))) {
            picker.choose("/thinking HIGH");
            assertEquals("high", picker.repl.getChatConfig().getThinking());
            assertEquals("high", ChatConfig.loadSession("model-picker-test").getThinking());
            picker.choose("/thinking invalid");
            assertEquals("high", picker.repl.getChatConfig().getThinking());
            picker.choose("/thinking default");
            assertNull(picker.repl.getChatConfig().getThinking());
            assertNull(ChatConfig.loadSession("model-picker-test").getThinking());
            assertTrue(picker.calls.isEmpty());
        }
    }

    private static ChatConfig config(String provider, String key, String authMethod, String url) {
        ChatConfig config = new ChatConfig(provider, key, "original-model", url);
        config.setChatMode("standard");
        config.setAuthenticationMethod(authMethod);
        return config;
    }

    private static ModelDiscovery.Result models(String model) {
        return ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model(model, List.of())), List.of());
    }

    private static Answer answer(String promptPrefix, String value) { return new Answer(promptPrefix, value); }
    private record Answer(String promptPrefix, String value) {}
    private record DiscoveryCall(String provider, RequestAuth auth, String baseUrl) {}

    private final class Picker implements AutoCloseable {
        final ChatUiSession ui = new ChatUiSession();
        final List<DiscoveryCall> calls = new ArrayList<>();
        final MockedStatic<ModelDiscoveryHttp> discovery = mockStatic(ModelDiscoveryHttp.class);
        final MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
        final MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
        final MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class);
        final LineDisciplineTerminal terminal;
        final LineReader reader = mock(LineReader.class);
        final ChatRepl repl;

        Picker(ChatConfig config, ModelDiscovery.Result result) throws Exception {
            HarnessConfig disabled = new HarnessConfig();
            disabled.setEnabled(false);
            disabled.setJudgeEnabled(false);
            disabled.setJudgeGlobalEnabled(false);
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                    any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> new ToolRegistry(invocation.getArgument(0)));
            McpBundleToolLoader loader = mock(McpBundleToolLoader.class);
            when(loader.dashboardConfig()).thenReturn(Optional.empty());
            mcp.when(() -> McpBundleToolLoader.loadInteractive(any(), any(), anyString())).thenReturn(loader);
            discovery.when(() -> ModelDiscoveryHttp.refreshResultWithAuth(anyString(), nullable(RequestAuth.class), nullable(String.class)))
                    .thenAnswer(invocation -> {
                        calls.add(new DiscoveryCall(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
                        return result;
                    });
            discovery.when(ModelDiscoveryHttp::discoverClaudeCliResult).thenReturn(result);
            terminal = new LineDisciplineTerminal("model-picker-test", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8);
            terminal.setSize(new Size(120, 40));
            when(reader.getTerminal()).thenReturn(terminal);
            try (var ignored = ui.bind()) {
                repl = new ChatRepl(null, null, "model-picker-test", false, "coder", false,
                        config, Files.createDirectories(directory.resolve("project")), new TerminalRenderer(true));
            }
            Field field = ChatRepl.class.getDeclaredField("activeReader");
            field.setAccessible(true);
            field.set(repl, reader);
        }

        void choose(String command, Answer... answers) throws Exception {
            ArrayDeque<Answer> remaining = new ArrayDeque<>(List.of(answers));
            doAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                assertFalse(remaining.isEmpty(), "Unexpected extra wizard prompt: " + prompt);
                Answer answer = remaining.removeFirst();
                assertTrue(prompt.startsWith(answer.promptPrefix()), "Unexpected wizard page: " + prompt);
                return answer.value();
            }).when(reader).readLine(anyString());
            Field field = ChatRepl.class.getDeclaredField("commandRouter");
            field.setAccessible(true);
            ChatCommandRouter router = (ChatCommandRouter) field.get(repl);
            ui.capture(() -> assertTrue(router.handleSlashCommand(command))).run();
            assertTrue(remaining.isEmpty(), "Not all expected wizard prompts were reached");
        }

        @Override public void close() throws Exception {
            try {
                ui.capture(repl::close).run();
            } finally {
                ui.close();
                terminal.close();
                mcp.close();
                tools.close();
                harness.close();
                discovery.close();
            }
        }
    }
}

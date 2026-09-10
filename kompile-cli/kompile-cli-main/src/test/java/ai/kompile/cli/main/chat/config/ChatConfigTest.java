package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChatConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void passthroughConfigIsValidWithoutProviderCredentialsAndDoesNotPersistComputedGetters() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.toString());
        System.setProperty("user.dir", tempDir.toString());
        try {
            ChatConfig config = passthroughConfig("opencode", true);

            assertTrue(config.isValid());
            config.save();

            String json = Files.readString(tempDir.resolve(".kompile").resolve("chat-config.json"));
            assertFalse(json.contains("\"valid\""));
            assertFalse(json.contains("\"kompileServer\""));
            assertFalse(json.contains("\"anthropicFormat\""));
            assertFalse(json.contains("\"openAiCompatible\""));

            ChatConfig loaded = ChatConfig.loadOrFromEnv();
            assertNotNull(loaded);
            assertEquals("passthrough", loaded.getChatMode());
            assertEquals("opencode", loaded.getPassthroughAgent());
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void projectConfigTakesPrecedenceOverGlobalConfig() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        try {
            passthroughConfig("opencode", true).saveGlobal();
            passthroughConfig("codex", false).saveProject(project);

            ChatConfig loaded = ChatConfig.loadOrFromEnv();

            assertNotNull(loaded);
            assertEquals("codex", loaded.getPassthroughAgent());
            assertFalse(loaded.isPassthroughManaged());
            assertEquals(ChatConfig.projectConfigPath(project).toAbsolutePath().normalize(), loaded.getLoadedFrom());
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void globalConfigIsFallbackWhenProjectConfigIsMissing() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("fallback-home");
        Path project = tempDir.resolve("fallback-project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        try {
            passthroughConfig("opencode", true).saveGlobal();

            ChatConfig loaded = ChatConfig.loadOrFromEnv();

            assertNotNull(loaded);
            assertEquals("opencode", loaded.getPassthroughAgent());
            assertEquals(ChatConfig.globalConfigPath().toAbsolutePath().normalize(), loaded.getLoadedFrom());
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void expiredProjectOauthDoesNotFallBackToGlobalAccount() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            new ChatConfig("ollama", null, "global-model", null).saveGlobal();
            ChatConfig project = new ChatConfig("anthropic", null, "project-model", null);
            project.setAuthenticationMethod("oauth");
            project.saveProject(tempDir);
            ai.kompile.cli.main.auth.CredentialStore.create().putOAuth("anthropic", "expired", "", 1L);
            ChatConfig loaded = ChatConfig.loadOrFromEnv(tempDir);
            assertEquals("anthropic", loaded.getProvider());
            assertFalse(loaded.isValid());
            assertThrows(ChatConfig.AuthenticationException.class, loaded::resolveRequestAuth);
            assertEquals(ModelDiscovery.Status.AUTH_REQUIRED,
                    ModelDiscoveryHttp.refreshResult("anthropic", null, null).status());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void localStoreFailureDistinguishesPreparationFromRecoveryWithoutClaimingRefresh() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            Path store = tempDir.resolve(".kompile/auth.json");
            Files.createDirectories(store.getParent());
            Files.writeString(store, "invalid-json-secret-token");
            ChatConfig config = new ChatConfig("openai-codex", null, "test", null);
            config.setAuthenticationMethod("oauth");
            var preparation = assertThrows(ChatConfig.AuthenticationException.class, config::resolveRequestAuth);
            var rejected = ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth
                    .oauth("synthetic", null, java.util.Map.of()).withCredential("work", "identity");
            var recovery = assertThrows(ChatConfig.AuthenticationException.class,
                    () -> config.refreshRequestAuthAfterUnauthorized(rejected));
            assertTrue(preparation.getMessage().startsWith("Could not prepare credentials for openai-codex."));
            assertTrue(recovery.getMessage().startsWith("Credential recovery after HTTP 401 failed for openai-codex."));
            for (var error : java.util.List.of(preparation, recovery)) {
                assertEquals(ai.kompile.cli.main.auth.oauth.CredentialFailure.Kind.LOCAL_OR_PROTOCOL,
                        error.failure().kind());
                assertFalse(error.getMessage().contains("refresh"));
                assertFalse(error.getMessage().contains("auth login"));
                assertFalse(error.getMessage().contains("secret-token"));
                assertNull(error.getCause());
            }
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void saveProjectWritesUnderProjectDotKompile() throws Exception {
        Path project = tempDir.resolve("project-save");
        ChatConfig config = passthroughConfig("codex", false);

        config.saveProject(project);

        assertTrue(Files.isRegularFile(project.resolve(".kompile").resolve("chat-config.json")));
        assertEquals(ChatConfig.projectConfigPath(project).toAbsolutePath().normalize(), config.getLoadedFrom());
    }

    @Test
    void standardChatThinkingEffortPersistsWithTheSelectedModel() throws Exception {
        Path project = tempDir.resolve("thinking-project");
        ChatConfig config = new ChatConfig("ollama", null, "reasoning-model", null);
        config.setThinking("high");

        config.saveProject(project);
        ChatConfig loaded = ChatConfig.loadProject(project);

        assertNotNull(loaded);
        assertEquals("reasoning-model", loaded.getModel());
        assertEquals("high", loaded.getThinking());
    }

    @Test
    void fastModeIsOptInPersistedAndCopiedWithSupportedLlmSettings() throws Exception {
        Path project = tempDir.resolve("fast-mode-project");
        ChatConfig config = new ChatConfig("openai-codex", null, "gpt-5.5", null);
        assertFalse(config.isFastMode());
        config.setThinking("high");
        config.setFastMode(true);
        config.saveProject(project);
        ChatConfig loaded = ChatConfig.loadProject(project);
        assertNotNull(loaded);
        assertTrue(loaded.isFastMode());
        assertTrue(loaded.useFastMode("gpt-5.5"));
        assertFalse(loaded.useFastMode("gpt-5.3-codex-spark"));
        assertEquals("high", loaded.getThinking());
        loaded.setFastMode(false);
        loaded.saveLoadedOrGlobal();
        assertFalse(ChatConfig.loadProject(project).isFastMode());

        loaded.applyLlmSettingsFrom(config);
        assertTrue(loaded.isFastMode());
        ChatConfig unsupported = new ChatConfig("custom", null, "gpt-5.5", "http://unused");
        unsupported.setFastMode(true);
        loaded.applyLlmSettingsFrom(unsupported);
        assertFalse(loaded.isFastMode());
    }

    @Test
    void fastModeJsonIsBackwardCompatibleAndIndependentOfPropertyOrder() throws Exception {
        var mapper = ai.kompile.cli.common.util.JsonUtils.standardMapper();
        ChatConfig legacy = mapper.readValue("{\"provider\":\"openai-codex\",\"model\":\"gpt-5.5\"}", ChatConfig.class);
        assertFalse(legacy.isFastMode());
        ChatConfig reordered = mapper.readValue(
                "{\"fastMode\":true,\"model\":\"gpt-5.5\",\"provider\":\"openai-codex\"}", ChatConfig.class);
        assertTrue(reordered.useFastMode("gpt-5.5"));
    }

    @Test
    void promptCacheRetentionIsNormalizedPersistedAndSessionScoped() throws Exception {
        Path project = tempDir.resolve("prompt-cache-project");
        ChatConfig active = new ChatConfig("openai", null, "gpt-5.4", null);
        assertEquals("short", active.getPromptCacheRetention());

        active.setPromptCacheRetention("24h");
        active.saveProject(project);
        ChatConfig loaded = ChatConfig.loadProject(project);
        assertNotNull(loaded);
        assertEquals("long", loaded.getPromptCacheRetention());

        ChatConfig selected = new ChatConfig("anthropic", null, "claude-sonnet-4", null);
        selected.setPromptCacheRetention("none");
        loaded.applyLlmSettingsFrom(selected);
        assertEquals("long", loaded.getPromptCacheRetention(),
                "provider switching must not silently replace the session cache policy");
    }

    @Test
    void providerDescriptorsAdvertisePromptCacheBehavior() {
        assertEquals(ProviderPromptCacheCapabilities.Activation.EXPLICIT,
                ChatProviderRegistry.find("anthropic").promptCacheCapabilities().activation());
        assertEquals(ProviderPromptCacheCapabilities.RetentionControl.NONE,
                ChatProviderRegistry.find("openai-codex")
                        .promptCacheCapabilities().retentionControl());
        assertEquals(ProviderPromptCacheCapabilities.SessionAffinity.XAI_CONVERSATION_HEADER,
                ChatProviderRegistry.find("xai").promptCacheCapabilities().sessionAffinity());
        assertEquals(ProviderPromptCacheCapabilities.UsageDialect.OPENAI_CHAT,
                ChatProviderRegistry.find("deepseek").promptCacheCapabilities().usageDialect());
        assertEquals(ProviderPromptCacheCapabilities.SessionAffinity.PI_MESSAGES_OPTION,
                ChatProviderRegistry.find("radius").promptCacheCapabilities().sessionAffinity());
    }

    @Test
    void liveProviderSwitchUpdatesSharedLlmSettingsWithoutReplacingSessionPreferences() {
        ChatConfig active = new ChatConfig("openai", "old-key", "gpt-4o", "https://old.example/v1");
        active.setThinking("low");
        active.setCancelKey("Ctrl+Q");
        active.setAutoCompactEnabled(false);
        active.setAutoCompactThreshold(0.72d);
        active.setCompactionReserveTokens(12_000);
        active.setContextWindowTokens(111_000);
        active.setMaxOutputTokens(11_000);

        ChatConfig selected = new ChatConfig(
                "anthropic", "new-key", "claude-sonnet-4-20250514", "https://new.example/v1");
        selected.setThinking("high");
        selected.setContextWindowTokens(222_000);
        selected.setMaxOutputTokens(22_000);

        active.applyLlmSettingsFrom(selected);

        assertEquals("anthropic", active.getProvider());
        assertEquals("new-key", active.getApiKey());
        assertEquals("claude-sonnet-4-20250514", active.getModel());
        assertEquals("high", active.getThinking());
        assertEquals("https://new.example/v1", active.getBaseUrl());
        assertEquals("Ctrl+Q", active.getCancelKey(),
                "session-level controls must survive a provider switch");
        assertFalse(active.isAutoCompactEnabled());
        assertEquals(0.72d, active.getAutoCompactThreshold());
        assertEquals(12_000, active.getCompactionReserveTokens());
        assertEquals(222_000, active.getContextWindowTokens(),
                "provider/model overrides must switch with the model");
        assertEquals(22_000, active.getMaxOutputTokens());
    }

    @Test
    void compactionPolicyPersistsAtTheLoadedScope() throws Exception {
        Path project = tempDir.resolve("compaction-project");
        ChatConfig config = new ChatConfig("openai", null, "gpt-5.4", null);
        config.setAutoCompactEnabled(false);
        config.setAutoCompactThreshold(0.74d);
        config.setCompactionReserveTokens(30_000);
        config.setContextWindowTokens(350_000);
        config.setMaxOutputTokens(100_000);
        config.saveProject(project);

        ChatConfig loaded = ChatConfig.loadProject(project);
        assertNotNull(loaded);
        loaded.setAutoCompactEnabled(true);
        loaded.saveLoadedOrGlobal();

        ChatConfig reloaded = ChatConfig.loadProject(project);
        assertNotNull(reloaded);
        assertTrue(reloaded.isAutoCompactEnabled());
        assertEquals(0.74d, reloaded.getAutoCompactThreshold());
        assertEquals(30_000, reloaded.getCompactionReserveTokens());
        assertEquals(350_000, reloaded.getContextWindowTokens());
        assertEquals(100_000, reloaded.getMaxOutputTokens());
    }

    @Test
    void manualModelCatalogEntriesAreRejectedAndNotPersisted() throws Exception {
        Path project = tempDir.resolve("model-catalog-project");
        ChatConfig config = new ChatConfig("openai-codex", null, null, null);

        assertFalse(config.addModelToCatalog("openai-codex", "manual-model-id"));
        assertTrue(config.getModelCatalog().isEmpty());
        assertFalse(config.getConfiguredModels("openai-codex").contains("manual-model-id"));

        config.saveProject(project);
        ChatConfig loaded = ChatConfig.loadProject(project);

        assertNotNull(loaded);
        assertTrue(loaded.getModelCatalog().isEmpty());
        assertFalse(Files.readString(ChatConfig.projectConfigPath(project))
                .contains("manual-model-id"));
    }

    @Test
    void providerSwitchCarriesLiveLlmSettingsWithoutAUserModelOverlay() {
        ChatConfig active = new ChatConfig("ollama", null, null, null);
        ChatConfig selected = new ChatConfig("custom", null, "selected-model", "http://localhost:9000/v1");

        active.applyLlmSettingsFrom(selected);

        assertEquals("custom", active.getProvider());
        assertEquals("selected-model", active.getModel());
        assertEquals("http://localhost:9000/v1", active.getBaseUrl());
        assertTrue(active.getModelCatalog().isEmpty());
    }

    @Test
    void customStandardProviderRequiresAnExplicitEndpoint() {
        assertFalse(new ChatConfig("custom", null, "model", null).isValid());
        assertTrue(new ChatConfig(
                "custom", null, "model", "http://localhost:9000/v1").isValid());
    }

    @Test
    void explicitNoAuthDoesNotReuseAnOlderCustomApiKey() {
        ChatConfig config = new ChatConfig(
                "custom", "old-key", "model", "http://localhost:9000/v1");
        config.setAuthenticationMethod("none");

        assertNull(config.resolveRequestAuth());

        config.setAuthenticationMethod("oauth");
        assertThrows(ChatConfig.AuthenticationException.class, config::resolveRequestAuth,
                "an API key must not be reclassified as an OAuth subscription");

        config.setAuthenticationMethod("api-key");
        assertEquals("old-key", config.resolveRequestAuth().token());
    }

    @Test
    void opencodeStandardConfigUsesItsNativeProviderLifecycle() {
        ChatConfig config = new ChatConfig("opencode", null,
                "opencode-go/deepseek-v4-pro", null);

        assertTrue(config.isValid());
        assertTrue(config.isOpenCodeNative());
        assertFalse(config.isOpenAiCompatible());
        assertNull(ChatConfig.getDefaultBaseUrl("opencode"));
        assertArrayEquals(new String[0], ChatConfig.getDefaultModels("opencode"));
    }

    @Test
    void zaiCodingPlanUsesItsDedicatedSubscriptionEndpoint() {
        ChatProvider provider = ChatProviderRegistry.find("zai");

        assertNotNull(provider);
        assertEquals("Z.AI GLM Coding Plan", provider.displayName());
        assertEquals("https://api.z.ai/api/coding/paas/v4", provider.defaultBaseUrl());
        assertEquals("ZAI_API_KEY", provider.environmentVariable());
        assertEquals("GLM Coding Plan subscription API key", provider.apiKeyAuthLabel());
    }

    @Test
    void standardProvidersResolveThroughOneProviderNeutralRouteMatrix() {
        Object[][] routes = {
                {"openai", "gpt-4.1", DirectLlmClient.WireProtocol.OPENAI_CHAT, false},
                {"openai-codex", "gpt-5.6-terra",
                        DirectLlmClient.WireProtocol.OPENAI_RESPONSES, true},
                {"anthropic", "claude-sonnet-4",
                        DirectLlmClient.WireProtocol.ANTHROPIC_MESSAGES, false},
                {"radius", "anthropic/claude-sonnet-4",
                        DirectLlmClient.WireProtocol.PI_MESSAGES, false},
                {"opencode", "opencode-go/deepseek-v4-pro",
                        DirectLlmClient.WireProtocol.OPENCODE, false},
                {"kompile-local", "local-model",
                        DirectLlmClient.WireProtocol.KOMPILE_LOCAL, false},
                {"ollama", "llama3.3", DirectLlmClient.WireProtocol.OPENAI_CHAT, false},
                {"zai", "fixture-model", DirectLlmClient.WireProtocol.OPENAI_CHAT, false},
                {"custom", "custom-model", DirectLlmClient.WireProtocol.OPENAI_CHAT, false}
        };

        for (Object[] route : routes) {
            ChatConfig config = new ChatConfig(
                    (String) route[0], "test-key", (String) route[1], null);
            try (DirectLlmClient client = new DirectLlmClient(
                    config, new com.fasterxml.jackson.databind.ObjectMapper(), tempDir)) {
                DirectLlmClient.ResolvedRoute resolved = client.resolveRoute(null);
                assertEquals(route[2], resolved.protocol(), route[0].toString());
                assertEquals(route[3], resolved.codexBackend(), route[0].toString());
                assertEquals(tempDir.toAbsolutePath().normalize(), client.getWorkingDirectory());
            }
        }
    }

    @Test
    void passthroughAgentOrderIncludesRegistryAndManagedOneShotAgents() {
        assertTrue(ChatConfig.getPassthroughAgentOrder().contains("codex"),
                "setup wizard must be able to offer Codex when codex is on PATH");
        assertEquals("Codex", ChatConfig.getPassthroughAgents().get("codex"));
        assertTrue(ChatConfig.getPassthroughAgentOrder().contains("dsh"),
                "passthrough setup must offer the DeepSeek Harness when dsh is on PATH");
        assertTrue(ChatConfig.getPassthroughAgents().get("dsh").contains("managed one-shot"));
    }

    private static ChatConfig passthroughConfig(String agent, boolean managed) {
        ChatConfig config = new ChatConfig(null, null, null, null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent(agent);
        config.setPassthroughManaged(managed);
        return config;
    }
}

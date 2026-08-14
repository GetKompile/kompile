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
    void passthroughAgentOrderComesFromPackagedCliAgentRegistry() {
        assertTrue(ChatConfig.getPassthroughAgentOrder().contains("codex"),
                "setup wizard must be able to offer Codex when codex is on PATH");
        assertEquals("Codex", ChatConfig.getPassthroughAgents().get("codex"));
    }

    private static ChatConfig passthroughConfig(String agent, boolean managed) {
        ChatConfig config = new ChatConfig(null, null, null, null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent(agent);
        config.setPassthroughManaged(managed);
        return config;
    }
}

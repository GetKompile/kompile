package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatConfigCredentialMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void newChatConfigStoresSecretInManagedAuthFileOnly() throws Exception {
        withTemporaryHome(() -> {
            ChatConfig config = new ChatConfig("openai", "sk-managed-secret", "gpt-4o", null);

            config.saveGlobal();

            String configJson = Files.readString(ChatConfig.globalConfigPath());
            assertFalse(configJson.contains("sk-managed-secret"));
            assertFalse(configJson.contains("\"apiKey\""));

            CredentialStore store = CredentialStore.create();
            assertEquals("sk-managed-secret", store.read("openai").getKey());

            ChatConfig loaded = ChatConfig.loadGlobal();
            assertNotNull(loaded);
            assertTrue(loaded.isValid());
            assertEquals("sk-managed-secret", loaded.getApiKey());
        });
    }

    @Test
    void loadingLegacyConfigMigratesAndScrubsPlaintextApiKey() throws Exception {
        withTemporaryHome(() -> {
            Path configPath = ChatConfig.globalConfigPath();
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, """
                    {
                      "provider": "anthropic",
                      "apiKey": "sk-ant-legacy-secret",
                      "model": "claude-sonnet-4-20250514",
                      "chatMode": "standard"
                    }
                    """);

            ChatConfig loaded = ChatConfig.loadGlobal();

            assertNotNull(loaded);
            assertEquals("sk-ant-legacy-secret", loaded.getApiKey());
            assertEquals("sk-ant-legacy-secret",
                    CredentialStore.create().read("anthropic").getKey());

            String scrubbed = Files.readString(configPath);
            assertFalse(scrubbed.contains("sk-ant-legacy-secret"));
            assertFalse(scrubbed.contains("\"apiKey\""));
        });
    }

    @Test
    void storedAnthropicOauthResolvesBearerHeadersWithoutLeakingToConfig() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore.create().putOAuth(
                    "anthropic",
                    "sk-ant-oat-managed",
                    "refresh-token",
                    System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(1));
            ChatConfig config = new ChatConfig(
                    "anthropic",
                    null,
                    "claude-sonnet-4-20250514",
                    null);

            var auth = config.resolveRequestAuth();

            assertNotNull(auth);
            assertTrue(auth.oauth());
            assertEquals("sk-ant-oat-managed", auth.token());
            assertEquals("Bearer sk-ant-oat-managed", auth.headers().get("Authorization"));
            assertEquals("https://api.anthropic.com", config.resolveBaseUrl(auth));
        });
    }

    @Test
    void oauthOnlyProviderMakesStandardChatValidWithoutApiKey() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore.create().put(
                    "openai-codex",
                    ManagedCredential.oauth(
                            "codex-access",
                            "refresh-token",
                            System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(1),
                            java.util.Map.of("accountId", "account-123")));
            ChatConfig config = new ChatConfig(
                    "openai-codex",
                    null,
                    "gpt-5.6-terra",
                    null);
            config.setChatMode("standard");

            assertTrue(config.isValid());
            var auth = config.resolveRequestAuth();
            assertNotNull(auth);
            assertTrue(auth.oauth());
            assertEquals("Bearer codex-access", auth.headers().get("Authorization"));
            assertEquals("account-123", auth.headers().get("chatgpt-account-id"));
        });
    }

    @Test
    void oauthCredentialCanOverrideProviderBaseUrlAtRequestTime() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore.create().putOAuth(
                    "xai",
                    "xai-access",
                    "xai-refresh",
                    System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(1));
            ChatConfig config = new ChatConfig("xai", null, "grok-4", null);

            var auth = config.resolveRequestAuth();

            assertNotNull(auth);
            assertEquals("https://api.x.ai/v1", config.resolveBaseUrl(auth));
        });
    }

    private void withTemporaryHome(ThrowingRunnable body) throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        try {
            body.run();
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

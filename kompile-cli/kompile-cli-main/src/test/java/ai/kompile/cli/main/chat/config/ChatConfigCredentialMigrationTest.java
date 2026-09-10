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

    @Test
    void sessionsPinAccountsWhileGlobalModeFollowsVendorSelection() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("openai", "first", "secret-first", true);
            store.putApiKey("openai", "second", "secret-second", false);
            ChatConfig defaults = new ChatConfig("openai", null, "model-a", null);
            defaults.saveGlobal();
            ChatConfig first = defaults.copy();
            first.bindSession("first-session");
            ChatConfig second = defaults.copy();
            second.setCredentialName("second");
            second.setModel("model-b");
            second.bindSession("second-session");
            ChatConfig global = defaults.copy();
            global.setAuthenticationScope("global");
            assertEquals("secret-first", global.getApiKey());
            store.switchCredential("openai", "second");
            assertEquals("secret-first", first.getApiKey());
            assertEquals("secret-second", second.getApiKey());
            assertEquals("secret-second", global.getApiKey());
            assertEquals("model-a", ChatConfig.loadGlobal().getModel());
            ChatConfig resumed = ChatConfig.loadSession("first-session");
            assertEquals("first", resumed.getCredentialName());
            assertEquals("secret-first", resumed.getApiKey());
            assertFalse(Files.readString(ChatConfig.sessionConfigPath("first-session")).contains("secret-first"));
            ChatConfig copy = resumed.copy();
            copy.setCredentialName("second");
            assertEquals("first", resumed.getCredentialName());
            store.deleteCredential("openai", "first");
            assertThrows(ChatConfig.AuthenticationException.class, resumed::resolveRequestAuth);
        });
    }

    @Test
    void environmentAccountRemainsPinnedAfterGlobalLogin() throws Exception {
        withTemporaryHome(() -> {
            ChatConfig session = new ChatConfig("openai", null, "model", null);
            session.pinActiveCredential(variable -> "environment-secret");
            session.bindSession("environment-session");
            CredentialStore.create().putApiKey("openai", "other", "other-secret", true);
            assertEquals("environment-secret", session.getApiKey());
            assertEquals("environment-secret", ChatConfig.loadSession("environment-session").getApiKey());
        });
    }

    @Test
    void sessionPickerSelectsAccountWithoutChangingVendorDefault() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("openai", "first", "first-key", true);
            store.putApiKey("openai", "second", "second-key", false);
            org.jline.reader.LineReader reader = (org.jline.reader.LineReader) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{org.jline.reader.LineReader.class},
                    (proxy, method, args) -> method.getName().equals("readLine") ? "second" : null);
            var selected = SetupWizard.authenticateSession(reader, "openai", SetupWizard.AuthMethod.API_KEY);
            assertNotNull(selected);
            assertEquals("second", selected.credentialName());
            assertNull(selected.apiKey());
            assertEquals("first", store.activeCredentialName("openai"));
        });
    }

    @Test
    void sessionApiKeyDoesNotOverwriteGlobalAccountAndSurvivesResume() throws Exception {
        withTemporaryHome(() -> {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("openai", "global", "global-secret", true);
            ChatConfig config = new ChatConfig("openai", "session-secret", "model", null);
            config.bindSession("isolated");
            assertEquals("global-secret", store.resolveApiKey("openai"));
            assertEquals("session-secret", ChatConfig.loadSession("isolated").getApiKey());
            assertFalse(Files.readString(ChatConfig.sessionConfigPath("isolated")).contains("session-secret"));
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

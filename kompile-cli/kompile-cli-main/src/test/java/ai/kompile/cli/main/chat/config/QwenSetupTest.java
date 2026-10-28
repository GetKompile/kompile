package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QwenSetupTest {
    @TempDir Path tempDir;

    @Test
    void registersUnderQwenIdWithDashScopeOpenAiCompatibleEndpoint() {
        QwenChatProvider provider = new QwenChatProvider();
        assertEquals("qwen", provider.id());
        assertEquals("Qwen Cloud", provider.displayName());
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                provider.defaultBaseUrl());
        assertNull(provider.environmentVariable(),
                "keys are managed credentials only — no environment fallback");
        assertTrue(provider.supportsApiKey());
        assertEquals("Qwen Cloud", ChatConfig.PROVIDERS.get("qwen"));
    }

    @Test
    void offersApiKeyOnlyWithoutBillingSwitchOrNativeRoute() {
        assertEquals(List.of("API key (DashScope)"), SetupWizard.authOptions("qwen"));
        // A single auth option auto-selects without prompting, so the reader is never polled.
        assertEquals(SetupWizard.AuthMethod.API_KEY,
                SetupWizard.selectAuthMethod(readerThatMustNotBeRead(), "qwen"));
        assertNull(SetupWizard.baseUrlForAuth("qwen", SetupWizard.AuthMethod.API_KEY),
                "no billing switch: the international endpoint is the default base URL");
        assertFalse(SetupWizard.requiresBaseUrl("qwen"));
    }

    @Test
    void reusesManagedCredentialAndPersistsConfigWithoutPlaintextKey() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try (var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                "qwen-setup", "xterm", new java.io.ByteArrayOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("qwen", "shared", "test-qwen-key", true);
            ChatConfig config = new ChatConfig("qwen", null, "qwen3-max", null);
            assertEquals(QwenChatProvider.INTERNATIONAL_BASE_URL, config.resolveBaseUrl());
            assertEquals(SetupWizard.AuthMethod.API_KEY,
                    SetupWizard.authMethodForProvider("qwen", config));

            SetupWizard.AuthenticationSelection authentication =
                    SetupWizard.authenticateSession(reader(terminal, "1"), "qwen",
                            SetupWizard.AuthMethod.API_KEY);
            assertNotNull(authentication);
            assertEquals("qwen", authentication.provider());
            assertEquals(SetupWizard.AuthMethod.API_KEY, authentication.authMethod());
            assertEquals("shared", authentication.credentialName());
            assertNull(authentication.apiKey(), "reuse the stored key without prompting for another");

            config.setCredentialName(authentication.credentialName());
            config.setAuthenticationMethod(authentication.authMethod().configValue());
            config.saveProject(tempDir);
            ChatConfig reloaded = ChatConfig.loadProject(tempDir);
            assertNotNull(reloaded);
            assertEquals("api-key", reloaded.getAuthenticationMethod());
            assertEquals("test-qwen-key", reloaded.resolveRequestAuth().token(),
                    "token resolves from the managed credential store");
            assertFalse(reloaded.resolveRequestAuth().oauth());
            assertFalse(Files.readString(ChatConfig.projectConfigPath(tempDir)).contains("test-qwen-key"),
                    "the key must never be written to the config file in plaintext");
            assertEquals(1, CredentialStore.create().list("qwen").size());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void chinaEndpointRemainsAnExplicitOverride() {
        ChatConfig china = new ChatConfig("qwen", null, "qwen3-max",
                QwenChatProvider.CHINA_BASE_URL);
        assertEquals(QwenChatProvider.CHINA_BASE_URL, china.resolveBaseUrl());
        assertEquals(SetupWizard.AuthMethod.API_KEY, SetupWizard.authMethodForProvider("qwen", china));
    }

    private static LineReader reader(String... answers) {
        return reader(null, answers);
    }

    private static LineReader readerThatMustNotBeRead() {
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                    throw new AssertionError("Single-option auth must auto-select without prompting");
                });
    }

    private static LineReader reader(org.jline.terminal.Terminal terminal, String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTerminal")) return terminal;
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }
}

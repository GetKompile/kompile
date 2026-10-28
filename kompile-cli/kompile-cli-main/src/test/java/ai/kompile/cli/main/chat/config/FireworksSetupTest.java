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

class FireworksSetupTest {
    @TempDir Path tempDir;

    @Test
    void registersUnderFireworksIdWithOpenAiCompatibleEndpoint() {
        FireworksChatProvider provider = new FireworksChatProvider();
        assertEquals("fireworks", provider.id());
        assertEquals("Fireworks AI", provider.displayName());
        assertEquals("https://api.fireworks.ai/inference/v1", provider.defaultBaseUrl());
        assertNull(provider.environmentVariable(),
                "keys are managed credentials only — no environment fallback");
        assertTrue(provider.supportsApiKey());
        assertEquals("Fireworks AI", ChatConfig.PROVIDERS.get("fireworks"));
    }

    @Test
    void offersApiKeyOnlyAndAutoSelectsWithoutPrompting() {
        assertEquals(List.of("API key (Fireworks)"), SetupWizard.authOptions("fireworks"));
        assertEquals(SetupWizard.AuthMethod.API_KEY,
                SetupWizard.selectAuthMethod(readerThatMustNotBeRead(), "fireworks"));
        assertNull(SetupWizard.baseUrlForAuth("fireworks", SetupWizard.AuthMethod.API_KEY));
        assertFalse(SetupWizard.requiresBaseUrl("fireworks"));
    }

    @Test
    void reusesManagedCredentialAndPersistsConfigWithoutPlaintextKey() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try (var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                "fireworks-setup", "xterm", new java.io.ByteArrayOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("fireworks", "shared", "test-fireworks-key", true);
            ChatConfig config = new ChatConfig("fireworks", null, "accounts/fireworks/models/kimi-k2-instruct", null);
            assertEquals(FireworksChatProvider.BASE_URL, config.resolveBaseUrl());
            assertEquals(SetupWizard.AuthMethod.API_KEY,
                    SetupWizard.authMethodForProvider("fireworks", config));

            SetupWizard.AuthenticationSelection authentication =
                    SetupWizard.authenticateSession(reader(terminal, "1"), "fireworks",
                            SetupWizard.AuthMethod.API_KEY);
            assertNotNull(authentication);
            assertEquals("fireworks", authentication.provider());
            assertEquals(SetupWizard.AuthMethod.API_KEY, authentication.authMethod());
            assertEquals("shared", authentication.credentialName());
            assertNull(authentication.apiKey(), "reuse the stored key without prompting for another");

            config.setCredentialName(authentication.credentialName());
            config.setAuthenticationMethod(authentication.authMethod().configValue());
            config.saveProject(tempDir);
            ChatConfig reloaded = ChatConfig.loadProject(tempDir);
            assertNotNull(reloaded);
            assertEquals("api-key", reloaded.getAuthenticationMethod());
            assertEquals("test-fireworks-key", reloaded.resolveRequestAuth().token(),
                    "token resolves from the managed credential store");
            assertFalse(reloaded.resolveRequestAuth().oauth());
            assertFalse(Files.readString(ChatConfig.projectConfigPath(tempDir)).contains("test-fireworks-key"),
                    "the key must never be written to the config file in plaintext");
            assertEquals(1, CredentialStore.create().list("fireworks").size());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
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

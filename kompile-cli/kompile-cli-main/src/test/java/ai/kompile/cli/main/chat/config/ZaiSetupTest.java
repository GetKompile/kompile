package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ZaiSetupTest {
    @TempDir Path tempDir;

    @Test
    void setupOffersBothBillingRoutesUnderOneVendor() {
        assertEquals(List.of("API key (subscription)", "API key (credits)"), SetupWizard.authOptions("zai"));
        assertEquals(SetupWizard.AuthMethod.API_KEY, SetupWizard.selectAuthMethod(reader("1"), "zai"));
        assertEquals(SetupWizard.AuthMethod.API_KEY_CREDITS, SetupWizard.selectAuthMethod(reader("2"), "zai"));
        assertNull(SetupWizard.selectAuthMethod(reader("cancel"), "zai"));
        assertThrows(IllegalArgumentException.class, () -> SetupWizard.resolveProviderForAuth(
                "openai", SetupWizard.AuthMethod.API_KEY_CREDITS));
    }

    @Test
    void switchesBillingEndpointAndPersistsItWithoutReplacingTheKey() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            CredentialStore store = CredentialStore.create();
            store.putApiKey("zai", "shared", "test-zai-key", true);
            ChatConfig config = new ChatConfig("zai", null, "glm-5", null);
            assertEquals(ZaiChatProvider.SUBSCRIPTION_BASE_URL, config.resolveBaseUrl());
            assertEquals(SetupWizard.AuthMethod.API_KEY,
                    SetupWizard.authMethodForProvider("zai", config));

            for (String choice : List.of("2", "1")) {
                SetupWizard.AuthMethod method = SetupWizard.selectAuthMethod(reader(choice), "zai");
                SetupWizard.AuthenticationSelection authentication =
                        SetupWizard.authenticateSession(reader("1"), "zai", method);
                assertNotNull(authentication);
                assertEquals("shared", authentication.credentialName());
                assertNull(authentication.apiKey(), "reuse the stored key without prompting for another");
                assertEquals("zai", authentication.provider());
                String endpoint = choice.equals("2")
                        ? "https://api.z.ai/api/paas/v4" : "https://api.z.ai/api/coding/paas/v4";
                config.setBaseUrl(SetupWizard.baseUrlForAuth(authentication.provider(), method));
                config.setCredentialName(authentication.credentialName());
                config.setAuthenticationMethod(method.configValue());
                config.saveProject(tempDir);
                config = ChatConfig.loadProject(tempDir);
                assertNotNull(config);
                assertEquals(endpoint, config.getBaseUrl());
                assertEquals(endpoint, config.resolveBaseUrl());
                assertEquals("api-key", config.getAuthenticationMethod());
                assertEquals("test-zai-key", config.resolveRequestAuth().token());
                assertFalse(config.resolveRequestAuth().oauth());
                assertEquals(method, SetupWizard.authMethodForProvider("zai", config));
                assertEquals(1, CredentialStore.create().list("zai").size());
                assertFalse(Files.readString(ChatConfig.projectConfigPath(tempDir)).contains("test-zai-key"));
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void creditsEndpointRecognitionDoesNotChangeOtherProviders() {
        ChatConfig credits = new ChatConfig("zai", null, "glm-5", ZaiChatProvider.CREDITS_BASE_URL + "/");
        assertEquals(SetupWizard.AuthMethod.API_KEY_CREDITS,
                SetupWizard.authMethodForProvider("zai", credits));
        assertEquals(SetupWizard.AuthMethod.API_KEY, SetupWizard.authMethodForProvider("gemini", credits));
        assertNull(SetupWizard.baseUrlForAuth("gemini", SetupWizard.AuthMethod.API_KEY));
        assertEquals(List.of("API key"), SetupWizard.authOptions("gemini"));
    }

    private static LineReader reader(String... answers) {
        ArrayDeque<String> input = new ArrayDeque<>(List.of(answers));
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        if (input.isEmpty()) throw new EndOfFileException();
                        return input.removeFirst();
                    }
                    throw new AssertionError("Unexpected reader call: " + method);
                });
    }
}

package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthWizardTest {
    @TempDir
    Path tempDir;

    @Test
    void loginWizardCollectsANamedMaskedApiKeyAndActivationChoice() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("login-auth.json"));
        store.putApiKey("openai", "personal", "personal-secret", true);
        ScriptedPrompter prompter = new ScriptedPrompter()
                .selecting("OpenAI — API key", "Paste an API key")
                .answering("work")
                .secrets("work-secret")
                .confirming(true);

        AuthWizard.LoginRequest request;
        try (AuthWizard wizard = AuthWizard.using(prompter)) {
            request = wizard.promptForLogin(new OAuthProviderRegistry(), store);
        }

        assertNotNull(request);
        assertEquals("openai", request.providerId());
        assertEquals("work", request.credentialName());
        assertEquals(AuthWizard.LoginKind.API_KEY, request.kind());
        assertEquals("work-secret", request.storedValue());
        assertTrue(request.activate());
        assertTrue(prompter.messages.stream().noneMatch(message -> message.contains("work-secret")));
    }

    @Test
    void switchWizardSelectsOneCredentialWithinTheProvider() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("switch-auth.json"));
        store.putApiKey("openai", "personal", "personal-secret", true);
        store.putApiKey("openai", "work", "work-secret", false);
        ScriptedPrompter prompter = new ScriptedPrompter().selecting("work");

        AuthWizard.SwitchRequest request;
        try (AuthWizard wizard = AuthWizard.using(prompter)) {
            request = wizard.promptForSwitch(store);
        }

        assertEquals(new AuthWizard.SwitchRequest("openai", "work"), request);
    }

    @Test
    void logoutWizardOffersNamedProviderAndGlobalScopes() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("logout-auth.json"));
        store.putApiKey("openai", "personal", "personal-secret", true);
        store.putApiKey("openai", "work", "work-secret", false);
        store.putApiKey("anthropic", "default", "anthropic-secret", true);
        ScriptedPrompter prompter = new ScriptedPrompter()
                .selecting("all 2 credentials for openai")
                .confirming(true);

        AuthWizard.LogoutRequest request;
        try (AuthWizard wizard = AuthWizard.using(prompter)) {
            request = wizard.promptForLogout(store);
        }

        assertEquals(AuthWizard.LogoutScope.PROVIDER, request.scope());
        assertEquals("openai", request.providerId());
        assertNull(request.credentialName());
    }

    @Test
    void cancellingTheFirstWizardMenuLeavesTheStoreUntouched() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("cancel-auth.json"));
        ScriptedPrompter prompter = new ScriptedPrompter().selecting("__cancel__");

        try (AuthWizard wizard = AuthWizard.using(prompter)) {
            assertNull(wizard.promptForLogin(new OAuthProviderRegistry(), store));
        }

        assertTrue(store.list().isEmpty());
    }

    private static final class ScriptedPrompter implements AuthWizard.Prompter {
        private final Deque<String> selections = new ArrayDeque<>();
        private final Deque<String> answers = new ArrayDeque<>();
        private final Deque<String> secretAnswers = new ArrayDeque<>();
        private final Deque<Boolean> confirmations = new ArrayDeque<>();
        private final List<String> messages = new ArrayList<>();

        private ScriptedPrompter selecting(String... values) {
            selections.addAll(List.of(values));
            return this;
        }

        private ScriptedPrompter answering(String... values) {
            answers.addAll(List.of(values));
            return this;
        }

        private ScriptedPrompter secrets(String... values) {
            secretAnswers.addAll(List.of(values));
            return this;
        }

        private ScriptedPrompter confirming(Boolean... values) {
            confirmations.addAll(List.of(values));
            return this;
        }

        @Override
        public void header(String title, String subtitle) {
            messages.add(title);
            messages.add(subtitle);
        }

        @Override
        public int select(String title, List<String> items) {
            String wanted = selections.removeFirst();
            if ("__cancel__".equals(wanted)) {
                return -1;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).toLowerCase().contains(wanted.toLowerCase())) {
                    return i;
                }
            }
            fail("No wizard option contains '" + wanted + "': " + items);
            return -1;
        }

        @Override
        public String text(String label, String defaultValue) {
            return answers.isEmpty() ? defaultValue : answers.removeFirst();
        }

        @Override
        public String secret(String label) {
            return secretAnswers.removeFirst();
        }

        @Override
        public boolean confirm(String question, boolean defaultYes) {
            return confirmations.isEmpty() ? defaultYes : confirmations.removeFirst();
        }

        @Override
        public void message(String message) {
            messages.add(message);
        }
    }
}

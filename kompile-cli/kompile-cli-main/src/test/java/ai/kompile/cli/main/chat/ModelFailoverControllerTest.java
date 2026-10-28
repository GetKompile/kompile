package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient.FailureKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ModelFailoverControllerTest {
    @TempDir Path home;

    @Test void explicitNamedCredentialAndThinkingOverrideInlinePrimaryWithoutMutatingIt() throws Exception {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            CredentialStore.create().putApiKey("openai", "backup-account", "backup-test-secret", false);
            ChatConfig primary = new ChatConfig("openai", "primary-test-secret", "gpt-primary", null);
            primary.setThinking("low");
            ChatConfig backup = ModelFailoverController.candidate(primary,
                    new SessionModelLadder.ModelRung("openai", "gpt-backup", "backup-account", null, "high", "api-key"));
            assertEquals("backup-test-secret", backup.resolveRequestAuth().token());
            assertEquals("high", backup.getThinking());
            assertEquals("primary-test-secret", primary.getApiKey());
            assertEquals("low", primary.getThinking());
            assertEquals("gpt-primary", primary.getModel());
        } finally { System.setProperty("user.home", original); }
    }

    @Test void explicitNativeLoginDoesNotInheritTheExhaustedSavedToken() throws Exception {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            ChatConfig primary = new ChatConfig("anthropic", null, "claude-sonnet-4-6", null);
            primary.setAuthenticationMethod("oauth");
            primary.setCredentialName("exhausted-account");
            ChatConfig backup = ModelFailoverController.candidate(primary,
                    new SessionModelLadder.ModelRung("anthropic", "claude-sonnet-4-6", null, null, "high", "native"));
            assertNull(backup.getCredentialName());
            assertNull(backup.resolveClaudeCodeToken());
            assertEquals("exhausted-account", primary.getCredentialName());
        } finally { System.setProperty("user.home", original); }
    }

    @Test void skipsUnavailableCredentialsAndRetainsHealthyTargetOnResume() throws Exception {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var missing = new SessionModelLadder.ModelRung("openai", "gpt-missing", "nonexistent", null, "high", "api-key");
            var healthy = new SessionModelLadder.ModelRung("custom", "backup", null, null, "medium");
            var policy = SessionModelLadder.forSubagent("subagent-policy", home, List.of(missing, healthy), true);
            AtomicReference<ChatConfig> current = new AtomicReference<>(new ChatConfig("custom", null, "primary", "http://localhost:1"));
            var controller = new ModelFailoverController(policy, current::get,
                    candidate -> { current.set(candidate); return true; }, ignored -> {});
            assertTrue(controller.tryFailover(FailureKind.QUOTA_EXHAUSTED, "weekly usage limit resets_in_seconds=604800", true));
            assertEquals("backup", current.get().getModel());
            assertEquals("medium", current.get().getThinking());
            assertTrue(policy.isInCooldown("custom|primary|"));
            assertFalse(policy.isInCooldown("custom|backup|"));
            var resumed = SessionModelLadder.forSubagent("subagent-policy", home, List.of(missing, healthy), true);
            assertEquals(healthy, resumed.activeRung().orElseThrow());
            assertFalse(resumed.isInCooldown("custom|backup|"));
        } finally { System.setProperty("user.home", original); }
    }

    @Test void refusalUnsafeAndDisabledNeverApplyFallback() throws Exception {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var policy = SessionModelLadder.forSubagent("subagent-guards", home,
                    List.of(new SessionModelLadder.ModelRung("custom", "backup")), true);
            ChatConfig primary = new ChatConfig("custom", null, "primary", "http://localhost:1");
            var controller = new ModelFailoverController(policy, () -> primary,
                    candidate -> { fail("unexpected activation"); return true; }, ignored -> {});
            assertFalse(controller.tryFailover(FailureKind.REFUSAL, "refusal", true));
            assertFalse(controller.tryFailover(FailureKind.QUOTA_EXHAUSTED, "limit", false));
            policy.setEnabled(false);
            assertFalse(controller.tryFailover(FailureKind.QUOTA_EXHAUSTED, "limit", true));
        } finally { System.setProperty("user.home", original); }
    }
}

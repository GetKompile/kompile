/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under a The Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.ClaudeCodeOAuthFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Claude Code route selects setup tokens as named {@code claude-code}
 * credentials, exactly as other vendors select theirs: a session pins one by
 * name, else uses the session default, and global scope follows the active
 * one. The selected token reaches only that chat's claude process
 * ({@link ClaudeCliClient#withSetupToken}).
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ClaudeSetupTokenInjectionTest {
    private static final String WORK = "sk-ant-oat01-AbCdEfGh1234567890_-IjKlMnOp";
    private static final String PERSONAL = "sk-ant-oat01-ZyXwVuTs0987654321_-RqPoNmLk";
    private static final String ENV_VAR = ClaudeCodeOAuthFlow.OAUTH_TOKEN_ENV;

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void redirectHomeToTemp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void theSelectedTokenIsSetForTheProcessAndNoSelectionLeavesItsEnvironmentAlone() {
        ProcessBuilder selected = new ProcessBuilder("claude", "-p", "hi");
        selected.environment().put(ENV_VAR, "inherited");
        ClaudeCliClient.withSetupToken(selected, WORK);
        // A selected credential comes before the environment, as for every other vendor.
        assertEquals(WORK, selected.environment().get(ENV_VAR));

        ProcessBuilder unselected = new ProcessBuilder("claude", "-p", "hi");
        unselected.environment().remove(ENV_VAR);
        ClaudeCliClient.withSetupToken(unselected, null);
        assertFalse(unselected.environment().containsKey(ENV_VAR));
    }

    @Test
    void eachSessionRunsOnTheSetupTokenItPinnedByName() throws Exception {
        storeTokens();

        ChatConfig work = claudeRoute("session");
        work.setCredentialName("work");
        ChatConfig personal = claudeRoute("session");
        personal.setCredentialName("personal");

        assertEquals(ClaudeCodeOAuthFlow.PROVIDER_ID, work.credentialProvider());
        assertEquals("work", work.getCredentialName());
        assertEquals(WORK, work.resolveClaudeCodeToken());
        assertEquals(PERSONAL, personal.resolveClaudeCodeToken());
        // A pin is a credential name only; the global selection is untouched.
        assertEquals("personal", CredentialStore.create().activeCredentialName(ClaudeCodeOAuthFlow.PROVIDER_ID));
    }

    @Test
    void anUnpinnedSessionUsesTheSessionDefaultAndGlobalScopeTheActiveToken() throws Exception {
        storeTokens();
        CredentialStore.create().recordUsed(ClaudeCodeOAuthFlow.PROVIDER_ID, "work");

        assertEquals(WORK, claudeRoute("session").resolveClaudeCodeToken());
        assertEquals(PERSONAL, claudeRoute("global").resolveClaudeCodeToken());
    }

    @Test
    void bindingASessionPinsTheRoutesDefaultToken() throws Exception {
        storeTokens();
        CredentialStore.create().recordUsed(ClaudeCodeOAuthFlow.PROVIDER_ID, "work");
        ChatConfig session = claudeRoute("session");

        session.pinActiveCredential();

        assertEquals("work", session.getCredentialName());
        // Later activations elsewhere do not move a pinned session.
        CredentialStore.create().recordUsed(ClaudeCodeOAuthFlow.PROVIDER_ID, "personal");
        assertEquals(WORK, session.resolveClaudeCodeToken());
    }

    @Test
    void claudeCodesOwnLoginGetsNoTokenEvenWithTokensStored() throws Exception {
        storeTokens();
        ChatConfig ownLogin = claudeRoute("session");
        ownLogin.setAuthenticationMethod("native");

        assertNull(ownLogin.resolveClaudeCodeToken());
        assertNull(claudeRouteWithoutTokens().resolveClaudeCodeToken(), "nothing stored: Claude Code's own login");
    }

    @Test
    void aPinnedTokenThatIsGoneFailsClosedInsteadOfUsingAnotherLogin() throws Exception {
        storeTokens();
        ChatConfig session = claudeRoute("session");
        session.setCredentialName("work");
        CredentialStore.create().deleteCredential(ClaudeCodeOAuthFlow.PROVIDER_ID, "work");

        assertThrows(ChatConfig.AuthenticationException.class, session::resolveClaudeCodeToken);
    }

    @Test
    void pinsAreKeptPerRouteSoAnApiKeyPinNeverSelectsASetupToken() throws Exception {
        storeTokens();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-sonnet", null);
        config.setAuthenticationScope("session");
        config.setAuthenticationMethod("api-key");
        assertEquals("anthropic", config.credentialProvider());
        config.setCredentialName("api-work");

        config.setAuthenticationMethod("oauth");
        assertNull(config.getCredentialName(), "the API-key pin stays with the API-key route");
        config.setCredentialName("personal");
        config.setAuthenticationMethod("api-key");
        assertEquals("api-work", config.getCredentialName());
    }

    @Test
    void theLoginCheckUsesTheSelectedTokenWithoutProbingClaudeCode() {
        String loggedOut = LiveModelDiscovery.parseClaudeAuthStatus("{\"loggedIn\":false}", true).describe();
        assertTrue(loggedOut.contains("setup token"), loggedOut);
        assertFalse(loggedOut.contains("kompile auth login claude"), loggedOut);
        AtomicInteger probes = new AtomicInteger();
        LiveModelDiscovery.useClaudeCodeLoginProbe(() -> {
            probes.incrementAndGet();
            return new LiveModelDiscovery.ClaudeCodeLogin(false, null, null, false);
        });
        try {
            LiveModelDiscovery.ClaudeCodeLogin selected = LiveModelDiscovery.claudeCodeLogin(WORK);
            assertTrue(selected.loggedIn(), "a selected token is the login in use");
            assertTrue(selected.managed());
            assertTrue(selected.describe().contains("CLAUDE_CODE_OAUTH_TOKEN"), selected.describe());
            assertEquals(0, probes.get());

            assertFalse(LiveModelDiscovery.claudeCodeLogin(null).loggedIn(), "no token: Claude Code's own login");
            assertEquals(1, probes.get());
        } finally {
            LiveModelDiscovery.useClaudeCodeLoginProbe(null);
        }
    }

    @Test
    void storedClaudeCodeOAuthDoesNotTripAnthropicGuardrail() throws Exception {
        // The "Kompile never sends Anthropic OAuth" guardrail keys on the
        // provider id "anthropic". The claude-code provider stays outside it.
        storeTokens();
        CredentialStore store = CredentialStore.create();
        ManagedCredential claudeCode = store.read(ClaudeCodeOAuthFlow.PROVIDER_ID);
        assertNotNull(claudeCode);
        assertTrue(claudeCode.isOAuth());
        assertNull(store.read("anthropic"));
        assertNull(ChatConfig.unusableCredentialReason(ClaudeCodeOAuthFlow.PROVIDER_ID, "work"));
    }

    private static ChatConfig claudeRoute(String scope) {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-sonnet", null);
        config.setAuthenticationScope(scope);
        config.setAuthenticationMethod("oauth");
        return config;
    }

    private ChatConfig claudeRouteWithoutTokens() throws IOException {
        CredentialStore.create().delete(ClaudeCodeOAuthFlow.PROVIDER_ID);
        return claudeRoute("session");
    }

    private static void storeTokens() throws IOException {
        CredentialStore store = CredentialStore.create();
        long expires = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365);
        store.put(ClaudeCodeOAuthFlow.PROVIDER_ID, "work", ManagedCredential.oauth(WORK, "", expires), false);
        store.put(ClaudeCodeOAuthFlow.PROVIDER_ID, "personal", ManagedCredential.oauth(PERSONAL, "", expires), true);
    }
}

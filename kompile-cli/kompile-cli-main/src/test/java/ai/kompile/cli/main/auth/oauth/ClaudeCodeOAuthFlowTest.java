/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClaudeCodeOAuthFlow}: capture, redaction, manual paste, no-refresh contract. */
class ClaudeCodeOAuthFlowTest {
    private static final String TOKEN = "sk-ant-oat01-AbCdEfGh1234567890_-IjKlMnOp";
    /** Real setup-token length: longer than the 80 columns Ink wraps at on a pipe. */
    private static final String LONG_TOKEN = "sk-ant-oat01-"
            + "Q1w2E3r4T5y6U7i8O9p0-AsDfGhJkL_zXcVbNm1234567890qwertyuiopASDFGHJKLzxcvbnm-_0987654321AA";
    private static final String HEAD = LONG_TOKEN.substring(0, 80);
    private static final String TAIL = LONG_TOKEN.substring(80);

    /**
     * One frame of Claude Code 2.1.282's setup-token success screen as it reaches a
     * pipe: Ink hard-wraps the token Text at 80 columns and colors each line.
     */
    private static String successFrame() {
        return "\033[2K\033[1A\033[2K\033[G\033[32m✓ Long-lived authentication token created successfully!\033[39m\n"
                + "\n"
                + "Your OAuth token (valid for 1 year):\n"
                + "\n"
                + "\033[33m" + HEAD + "\033[39m\n"
                + "\033[33m" + TAIL + "\033[39m\n"
                + "\n"
                + "\033[2mStore this token securely. You won't be able to see it again.\033[22m\n"
                + "\033[2mUse this token by setting: export CLAUDE_CODE_OAUTH_TOKEN=<token>\033[22m\n";
    }

    @Test
    void extractTokenRejoinsTheInkWrappedTokenAcrossColoredLines() {
        assertTrue(LONG_TOKEN.length() > 100, "fixture must wrap at 80 columns");
        assertEquals(LONG_TOKEN, ClaudeCodeOAuthFlow.extractToken(successFrame()));
        // Indented continuation lines (a padded box) still join.
        assertEquals(LONG_TOKEN, ClaudeCodeOAuthFlow.extractToken("  " + HEAD + "\n  " + TAIL + "\n\n  Store it."));
        // The blank line Ink puts after the token ends it.
        assertEquals(TOKEN, ClaudeCodeOAuthFlow.extractToken(TOKEN + "\n\nnext"));
        // A token followed by text on its own line is complete as printed.
        assertEquals(TOKEN, ClaudeCodeOAuthFlow.extractToken("export X=" + TOKEN + " # set\nnext"));
    }

    @Test
    void redactorHidesTheWrappedContinuationLine() {
        ClaudeCodeOAuthFlow.Redactor redactor = new ClaudeCodeOAuthFlow.Redactor();
        assertEquals(ClaudeCodeOAuthFlow.REDACTED, redactor.apply(HEAD));
        assertEquals("", redactor.apply(TAIL));
        assertEquals("", redactor.apply(""));
        assertEquals("Store this token securely.", redactor.apply("Store this token securely."));
        // After the token block ends, a token-character-only line is ordinary output again.
        assertEquals("done", redactor.apply("done"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void setupTokenCaptureStoresTheWholeWrappedTokenAndShowsNoPartOfIt() throws Exception {
        String frame = successFrame().replace("'", "'\\''");
        // Ink redraws: the same frame arrives twice.
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow(() -> List.of(
                "/bin/sh", "-c", "printf '%s' '" + frame + "'; printf '%s' '" + frame + "'"));
        RecordingInteraction interaction = new RecordingInteraction();
        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("setup-token", false, null, null), interaction);

        assertEquals(LONG_TOKEN, credential.getAccess());
        assertTrue(interaction.handedOffTerminal, "the child must run inside the terminal handoff");
        for (String message : interaction.messages) {
            assertFalse(message.contains(HEAD.substring(13)), "token head leaked: " + message);
            assertFalse(message.contains(TAIL), "wrapped token tail leaked: " + message);
            assertFalse(message.contains("\033"), "escape sequence relayed: " + message);
        }
        assertEquals(1, interaction.messages.stream()
                .filter(message -> message.startsWith("Store this token securely")).count(),
                "a redrawn frame is shown once");
    }

    @Test
    void manualPasteRejoinsATokenCopiedWithLineBreaksThroughTheMaskedPrompt() throws Exception {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        RecordingInteraction interaction = new RecordingInteraction();
        interaction.secretAnswer = HEAD + "\n" + TAIL + "\n";
        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("manual", true, null, null), interaction);

        assertEquals(LONG_TOKEN, credential.getAccess());
        assertTrue(interaction.secretPrompted, "a pasted token is read with the masked prompt");
    }

    @Test
    void providerIdentityUsesDedicatedWireProvider() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        assertEquals("claude-code", flow.providerId());
        // ONE vendor: the existing "anthropic" entry. This flow is its OAuth
        // method, not a separate surface.
        assertEquals("anthropic", flow.userFacingProviderId());
        assertFalse(flow.supportsApiKey(), "setup tokens are not ANTHROPIC_API_KEYs");
        assertEquals("setup-token", flow.defaultLoginMethod());
        assertTrue(flow.supportsMethod("setup-token"));
        assertTrue(flow.supportsMethod("manual"));
        assertFalse(flow.supportsMethod("device"));
    }

    @Test
    void registryMapsClaudeVendorToClaudeCodeWireProvider() {
        OAuthProviderRegistry registry =
                new OAuthProviderRegistry(List.of(new ClaudeCodeOAuthFlow()));
        // ONE vendor path: "anthropic" resolves to this flow's wire provider,
        // so the wizard offers OAuth (setup-token) and API key under one entry.
        assertEquals("claude-code", registry.oauthProviderForVendor("anthropic").orElse(null));
        assertEquals("claude-code", registry.oauthProviderForVendor("Anthropic").orElse(null));
        assertNotNull(registry.find("claude-code").orElse(null));
    }

    @Test
    void extractTokenAcceptsSetupTokenShape() {
        assertEquals(TOKEN, ClaudeCodeOAuthFlow.extractToken("token: " + TOKEN));
        assertEquals("sk-ant-oat-AbCdEfGh1234567890",
                ClaudeCodeOAuthFlow.extractToken("sk-ant-oat-AbCdEfGh1234567890"));
        assertNull(ClaudeCodeOAuthFlow.extractToken(null));
        assertNull(ClaudeCodeOAuthFlow.extractToken(""));
        // An API key is NOT a setup token.
        assertNull(ClaudeCodeOAuthFlow.extractToken("sk-ant-api01-AbCdEfGh1234567890"));
        assertNull(ClaudeCodeOAuthFlow.extractToken("no tokens here"));
    }

    @Test
    void redactionReplacesEveryTokenShapedMatch() {
        assertEquals("sk-ant-oat<redacted>", ClaudeCodeOAuthFlow.redact(TOKEN));
        assertEquals("before sk-ant-oat<redacted> after sk-ant-oat<redacted>",
                ClaudeCodeOAuthFlow.redact("before " + TOKEN + " after " + TOKEN));
        assertEquals("untouched line", ClaudeCodeOAuthFlow.redact("untouched line"));
        assertNull(ClaudeCodeOAuthFlow.redact(null));
        assertEquals("", ClaudeCodeOAuthFlow.redact(""));
    }

    @Test
    void manualPasteStoresOneYearNoRefreshCredential() throws Exception {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        RecordingInteraction interaction = new RecordingInteraction();
        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("manual", true, null, null),
                interaction.promptReturns(TOKEN));

        assertEquals(TOKEN, credential.getAccess());
        assertTrue(credential.getRefresh() == null || credential.getRefresh().isBlank(),
                "setup tokens have no refresh grant");
        assertEquals(
                System.currentTimeMillis() + ClaudeCodeOAuthFlow.ONE_YEAR_SECONDS * 1000L,
                credential.getExpires(),
                60_000L);
        assertEquals("setup-token", credential.getMetadata().get("tokenKind"));
        for (String message : interaction.messages) {
            assertFalse(message.contains(TOKEN), "token must never be echoed: " + message);
        }
    }

    @Test
    void manualPasteRejectsNonSetupTokenInput() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        RecordingInteraction interaction = new RecordingInteraction();
        IOException failure = assertThrows(IOException.class, () -> flow.login(
                new OAuthProviderFlow.LoginOptions("manual", true, null, null),
                interaction.promptReturns("sk-ant-api01-not-a-setup-token")));
        assertTrue(failure.getMessage().contains("sk-ant-oat"));
    }

    @Test
    void unsupportedMethodNamesTheMethod() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        IOException failure = assertThrows(IOException.class, () -> flow.login(
                new OAuthProviderFlow.LoginOptions("device", false, null, null),
                new RecordingInteraction()));
        assertTrue(failure.getMessage().contains("device"));
    }

    @Test
    void refreshExplainsThereIsNoRefreshGrant() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        IOException failure = assertThrows(IOException.class,
                () -> flow.refresh(ManagedCredential.oauth(
                        TOKEN, "", System.currentTimeMillis() + 60_000L)));
        assertTrue(failure.getMessage().contains("no refresh grant"));
        assertTrue(failure.getMessage().contains("claude.ai"));
    }

    @Test
    void toRequestAuthKeepsTokenOutOfToString() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow();
        OAuthProviderFlow.RequestAuth auth = flow.toRequestAuth(
                ManagedCredential.oauth(TOKEN, "", Long.MAX_VALUE / 2));
        assertTrue(auth.oauth());
        assertEquals(TOKEN, auth.token());
        assertFalse(auth.toString().contains(TOKEN));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void setupTokenMethodCapturesPrintedTokenAndRedactsOutput() throws Exception {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow(() -> List.of(
                "/bin/sh", "-c", "echo 'Browser opened. Paste token below.'; "
                        + "echo '" + TOKEN + "'; echo 'Copy it now.'"));
        RecordingInteraction interaction = new RecordingInteraction();
        ManagedCredential credential = flow.login(
                new OAuthProviderFlow.LoginOptions("setup-token", false, null, null),
                interaction);

        assertEquals(TOKEN, credential.getAccess());
        assertTrue(credential.getRefresh() == null || credential.getRefresh().isBlank());
        for (String message : interaction.messages) {
            assertFalse(message.contains(TOKEN), "token leaked via interaction: " + message);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void setupTokenMethodWithoutPrintedTokenStoresNothing() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow(() -> List.of(
                "/bin/sh", "-c", "echo 'aborted by user'"));
        IOException failure = assertThrows(IOException.class, () -> flow.login(
                new OAuthProviderFlow.LoginOptions("setup-token", false, null, null),
                new RecordingInteraction()));
        assertTrue(failure.getMessage().contains("Nothing was stored"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void missingBinaryExplainsThePasteAlternative() {
        ClaudeCodeOAuthFlow flow = new ClaudeCodeOAuthFlow(() ->
                List.of("/nonexistent-claude-binary-for-tests", "setup-token"));
        IOException failure = assertThrows(IOException.class, () -> flow.login(
                new OAuthProviderFlow.LoginOptions("setup-token", false, null, null),
                new RecordingInteraction()));
        assertTrue(failure.getMessage().contains("paste"));
    }

    @Test
    void claudeBinaryMatchesBareAndPathCommandsOnly() {
        assertTrue(ClaudeCodeOAuthFlow.isClaudeBinary("claude"));
        assertTrue(ClaudeCodeOAuthFlow.isClaudeBinary("/home/user/.local/bin/claude"));
        assertFalse(ClaudeCodeOAuthFlow.isClaudeBinary("claude-wrapper"));
        assertFalse(ClaudeCodeOAuthFlow.isClaudeBinary("/opt/notclaude"));
        assertFalse(ClaudeCodeOAuthFlow.isClaudeBinary("codex"));
        assertFalse(ClaudeCodeOAuthFlow.isClaudeBinary(null));
    }

    /** Interaction stub that records every message and returns a canned prompt answer. */
    private static final class RecordingInteraction implements OAuthProviderFlow.Interaction {
        final List<String> messages = new ArrayList<>();
        boolean handedOffTerminal;
        boolean secretPrompted;
        String secretAnswer;
        private String promptAnswer;

        @Override
        public String secret(String message) {
            secretPrompted = true;
            messages.add(message);
            return secretAnswer != null ? secretAnswer : promptAnswer;
        }

        @Override
        public <T> T withTerminal(OAuthProviderFlow.TerminalTask<T> task)
                throws IOException, InterruptedException {
            handedOffTerminal = true;
            return task.run();
        }

        RecordingInteraction promptReturns(String answer) {
            this.promptAnswer = answer;
            return this;
        }

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            messages.add(instructions);
            messages.add(url.toString());
        }

        @Override
        public void deviceCode(String userCode, URI verificationUri,
                Integer intervalSeconds, Integer expiresInSeconds) {
            messages.add(userCode);
        }

        @Override
        public String prompt(String message) {
            messages.add(message);
            return promptAnswer;
        }
    }
}

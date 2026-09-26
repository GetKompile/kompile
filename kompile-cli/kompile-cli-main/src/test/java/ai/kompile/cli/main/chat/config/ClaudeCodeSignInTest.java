/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives {@link ClaudeCodeSignIn} against a fake {@code claude} that follows the
 * real `claude auth login` contract: it prints a sign-in link that opens on any
 * device, then reads pasted codes from stdin. A code without '#' is reported on
 * stderr and Claude Code keeps waiting; a rejected code ends it with exit 1; a
 * good code signs in and exits 0. The login probe reports signed in once the
 * fake has accepted a code, standing in for `claude auth status`.
 */
class ClaudeCodeSignInTest {
    private static final String LINK = "https://claude.example/oauth/authorize?code=true&state=abc";

    /** `claude auth login`: the any-device link, then pasted codes until one is accepted or rejected. */
    private static final String AUTH_LOGIN = """
            echo "Opening browser to sign in…"
            echo "If the browser didn't open, visit: $LINK"
            printf 'Paste code here if prompted > '
            while IFS= read -r code; do
              printf '%s\\n' "$code" >> "$CODES"
              case "$code" in
                good-code#*) touch "$SIGNED_IN"; echo "Login successful."; exit 0 ;;
                *'#'*) echo "Login failed: Request failed with status code 400" >&2; exit 1 ;;
                *) echo "Invalid code. Please make sure the full code was copied." >&2 ;;
              esac
            done
            exit 1
            """;

    /** A browser on this machine finishes the sign-in: Claude Code exits on its own, no code pasted. */
    private static final String BROWSER_SIGN_IN = """
            echo "If the browser didn't open, visit: $LINK"
            printf 'Paste code here if prompted > '
            while [ ! -f "$BROWSER_DONE" ]; do sleep 0.05; done
            touch "$SIGNED_IN"
            echo "Login successful."
            exit 0
            """;

    @TempDir
    Path tempDir;

    private Path pidFile;
    private Path codes;
    private Path signedIn;
    private Path browserDone;
    private Path apiKeySeen;

    @BeforeEach
    void stubClaudeCodeLogin() {
        pidFile = tempDir.resolve("claude.pid");
        codes = tempDir.resolve("pasted-codes.log");
        signedIn = tempDir.resolve("signed-in");
        browserDone = tempDir.resolve("browser-done");
        apiKeySeen = tempDir.resolve("api-key-seen");
        LiveModelDiscovery.useClaudeCodeLoginProbe(() ->
                new LiveModelDiscovery.ClaudeCodeLogin(Files.exists(signedIn), "claude.ai", "max", false));
    }

    @AfterEach
    void restoreSeams() {
        LiveModelDiscovery.useClaudeCodeLoginProbe(null);
        ClaudeCodeSignIn.useClaudeBinary(null);
    }

    @Test
    void codePastedFromAnyDeviceSignsClaudeCodeIn() throws Exception {
        fakeClaude(AUTH_LOGIN);
        ScriptedInteraction interaction = new ScriptedInteraction("good-code#abc");

        LiveModelDiscovery.ClaudeCodeLogin login = ClaudeCodeSignIn.ensureSignedIn(interaction);

        assertTrue(login.loggedIn(), interaction.messages.toString());
        assertEquals(List.of(URI.create(LINK)), interaction.links);
        assertEquals(List.of("good-code#abc"), pastedCodes());
        assertEquals(1, interaction.prompts);
        assertFalse(Files.exists(apiKeySeen), "the Claude Code route runs without ANTHROPIC_API_KEY");
        assertSignInEnded();
    }

    @Test
    void incompleteCodeIsReportedAndTheNextPasteIsTried() throws Exception {
        fakeClaude(AUTH_LOGIN);
        ScriptedInteraction interaction = new ScriptedInteraction("good-code", "good-code#abc");

        assertTrue(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn(), interaction.messages.toString());
        assertTrue(interaction.said("Invalid code. Please make sure the full code was copied."),
                interaction.messages.toString());
        assertEquals(List.of("good-code", "good-code#abc"), pastedCodes());
        assertSignInEnded();
    }

    @Test
    void rejectedCodeEndsTheSignInWithClaudeCodesReason() throws Exception {
        fakeClaude(AUTH_LOGIN);
        ScriptedInteraction interaction = new ScriptedInteraction("expired-code#abc", "never-sent#abc");

        assertFalse(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertEquals(1, interaction.messages.stream()
                        .filter(message -> message.contains("Login failed: Request failed with status code 400"))
                        .count(),
                interaction.messages.toString());
        assertEquals(List.of("expired-code#abc"), pastedCodes());
        assertEquals(1, interaction.prompts);
        assertSignInEnded();
    }

    @Test
    void enterWhileClaudeCodeStillWaitsSaysSoAndCancelStopsIt() throws Exception {
        fakeClaude(AUTH_LOGIN);
        ScriptedInteraction interaction = new ScriptedInteraction("", "cancel");

        assertFalse(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertTrue(interaction.said("Claude Code is still waiting for the sign-in."), interaction.messages.toString());
        assertTrue(interaction.said("Claude Code sign-in cancelled."), interaction.messages.toString());
        assertEquals(List.of(), pastedCodes(), "neither Enter nor cancel is passed to Claude Code");
        assertSignInEnded();
    }

    @Test
    void endOfInputStopsTheSignIn() throws Exception {
        fakeClaude(AUTH_LOGIN);
        ScriptedInteraction interaction = new ScriptedInteraction();

        assertFalse(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertTrue(interaction.said("Claude Code sign-in cancelled."), interaction.messages.toString());
        assertSignInEnded();
    }

    @Test
    void enterAfterABrowserOnThisMachineSignedInFindsTheLogin() throws Exception {
        fakeClaude(BROWSER_SIGN_IN);
        // Each Enter gives Claude Code a moment to finish; a busy machine may need another.
        ScriptedInteraction interaction = new ScriptedInteraction("", "", "")
                .onPrompt(() -> assertDoesNotThrow(() -> Files.writeString(browserDone, "")));

        assertTrue(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn(), interaction.messages.toString());
        assertEquals(List.of(URI.create(LINK)), interaction.links);
        assertEquals(List.of(), pastedCodes());
        assertSignInEnded();
    }

    @Test
    void claudeCodeEndingBeforeItShowsALinkIsReported() throws Exception {
        fakeClaude("""
                echo "Login failed: network unreachable" >&2
                exit 1
                """);
        ScriptedInteraction interaction = new ScriptedInteraction("never-asked#abc");

        assertFalse(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertTrue(interaction.said("Login failed: network unreachable"), interaction.messages.toString());
        assertTrue(interaction.links.isEmpty());
        assertEquals(0, interaction.prompts);
        assertSignInEnded();
    }

    @Test
    void claudeCodeThatCannotStartFailsClosed() throws Exception {
        ClaudeCodeSignIn.useClaudeBinary(tempDir.resolve("missing-claude").toString());
        ScriptedInteraction interaction = new ScriptedInteraction("never-asked#abc");

        assertFalse(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertTrue(interaction.said("Could not start Claude Code's sign-in"), interaction.messages.toString());
        assertEquals(0, interaction.prompts);
    }

    @Test
    void alreadySignedInNeverStartsTheSignIn() throws Exception {
        fakeClaude(AUTH_LOGIN);
        Files.writeString(signedIn, "");
        ScriptedInteraction interaction = new ScriptedInteraction("never-asked#abc");

        assertTrue(ClaudeCodeSignIn.ensureSignedIn(interaction).loggedIn());
        assertFalse(Files.exists(pidFile), "`claude auth login` must not run for a signed-in Claude Code");
        assertTrue(interaction.messages.isEmpty(), interaction.messages.toString());
        assertEquals(0, interaction.prompts);
    }

    @Test
    void signInLinkIsTheFirstHttpsLinkWithoutTerminalHyperlinkEscapes() {
        assertEquals(URI.create(LINK), ClaudeCodeSignIn.signInLink("If the browser didn't open, visit: " + LINK));
        assertEquals(URI.create(LINK), ClaudeCodeSignIn.signInLink(
                "visit: \u001B]8;;" + LINK + "\u0007" + LINK + "\u001B]8;;\u0007"));
        assertNull(ClaudeCodeSignIn.signInLink("Opening browser to sign in…"));
        assertNull(ClaudeCodeSignIn.signInLink(null));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Writes an executable fake `claude` running {@code body} and makes the sign-in use it. */
    private void fakeClaude(String body) throws IOException {
        Path script = tempDir.resolve("claude-fake");
        Files.writeString(script, "#!/usr/bin/env bash\n"
                        + "LINK='" + LINK + "'\n"
                        + "CODES=" + codes + "\n"
                        + "SIGNED_IN=" + signedIn + "\n"
                        + "BROWSER_DONE=" + browserDone + "\n"
                        + "echo $$ > " + pidFile + "\n"
                        + "[ -z \"${ANTHROPIC_API_KEY:-}\" ] || touch " + apiKeySeen + "\n"
                        + body,
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        ClaudeCodeSignIn.useClaudeBinary(script.toString());
    }

    /** Every line the fake received on stdin, in order. */
    private List<String> pastedCodes() throws IOException {
        return Files.exists(codes) ? Files.readAllLines(codes, StandardCharsets.UTF_8) : List.of();
    }

    /** The fake `claude auth login` is no longer running once the sign-in returns. */
    private void assertSignInEnded() throws IOException {
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "`claude auth login` is still running");
    }

    /** Records what the sign-in shows and answers each prompt in order; null (end of input) once out of answers. */
    private static final class ScriptedInteraction implements OAuthProviderFlow.Interaction {
        private final Deque<String> answers;
        private final List<String> messages = new ArrayList<>();
        private final List<URI> links = new ArrayList<>();
        private Runnable onPrompt = () -> { };
        private int prompts;

        ScriptedInteraction(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        ScriptedInteraction onPrompt(Runnable action) {
            onPrompt = action;
            return this;
        }

        boolean said(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            links.add(url);
            messages.add(instructions);
        }

        @Override
        public void deviceCode(String userCode, URI verificationUri, Integer intervalSeconds,
                               Integer expiresInSeconds) {
            fail("Claude Code has no device-code grant");
        }

        @Override
        public String prompt(String message) {
            prompts++;
            onPrompt.run();
            return answers.pollFirst();
        }
    }
}

package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import ai.kompile.cli.main.chat.config.ClaudeCodeSignIn;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_IN")
@ResourceLock("SYSTEM_ERR")
class AuthCommandTest {
    private static final String CLAUDE_SIGN_IN_LINK = "https://claude.example/oauth/authorize?code=true&state=abc";

    @TempDir
    Path tempDir;

    @Test
    void mainCommandRegistersAuthAndLogoutRemovesOnlyRequestedProvider() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            CommandLine root = new CommandLine(new MainCommand());
            assertTrue(root.getSubcommands().containsKey("auth"));
            assertTrue(root.getSubcommands().get("auth").getSubcommands().containsKey("switch"));
            assertTrue(root.getSubcommands().get("auth").getSubcommands().containsKey("channel"));
            assertTrue(root.getSubcommands().get("auth").getSubcommands().containsKey("source"));
            CommandLine channel = root.getSubcommands().get("auth").getSubcommands().get("channel");
            assertTrue(channel.getSubcommands().keySet().containsAll(Set.of(
                    "providers", "connect", "list", "status", "configure", "rotate",
                    "enable", "disable", "test", "disconnect")));
            assertEquals(0, new CommandLine(new AuthCommand()).execute("channel", "--help"));
            assertEquals(0, new CommandLine(new AuthCommand()).execute("source", "--help"));

            CredentialStore store = CredentialStore.create();
            store.putApiKey("openai", "openai-secret");
            store.putApiKey("anthropic", "anthropic-secret");

            int exit = new CommandLine(new AuthCommand()).execute("logout", "openai");

            assertEquals(0, exit);
            assertNull(store.read("openai"));
            assertEquals("anthropic-secret", store.read("anthropic").getKey());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void namedLoginSwitchAndMultiVendorLogoutWorkThroughManagedAuthCommands() throws Exception {
        String originalHome = System.getProperty("user.home");
        InputStream originalInput = System.in;
        System.setProperty("user.home", tempDir.resolve("named-home").toString());
        try {
            CommandLine auth = new CommandLine(new AuthCommand());

            System.setIn(input("ignored-secret\n"));
            assertEquals(2, auth.execute("login", "openai", "--stdin", "--no-switch"));
            assertTrue(CredentialStore.create().list().isEmpty());

            System.setIn(input("personal-secret\n"));
            assertEquals(0, auth.execute(
                    "login", "openai", "--stdin", "--name", "personal"));

            var open = new ai.kompile.cli.main.chat.config.ChatConfig("openai", null, "model", null);
            open.bindSession("open-chat");
            var firstSelection = CredentialStore.create().sessionSelections().get("openai");
            assertNotNull(firstSelection);

            System.setIn(input("work-secret\n"));
            assertEquals(0, auth.execute(
                    "login", "openai", "--stdin", "--name", "work", "--no-switch"));

            CredentialStore store = CredentialStore.create();
            assertEquals(2, store.list("openai").size());
            assertEquals("personal", store.activeCredentialName("openai"));
            assertEquals("personal-secret", store.resolveApiKey("openai", name -> null));

            assertEquals(firstSelection, store.sessionSelections().get("openai"));
            assertEquals("personal-secret", open.getApiKey());
            assertEquals(0, auth.execute("switch", "openai", "work"));
            assertEquals("work-secret", store.resolveApiKey("openai", name -> null));
            assertEquals("work-secret", open.getApiKey());

            System.setIn(input("new-personal-secret\n"));
            assertEquals(0, auth.execute("login", "openai", "--stdin", "--name", "personal"));
            assertEquals("new-personal-secret", open.getApiKey());

            store.putApiKey("anthropic", "default", "anthropic-secret", true);
            assertEquals(0, auth.execute("logout", "openai", "work"));
            assertEquals("personal", store.activeCredentialName("openai"));
            assertEquals("anthropic-secret", store.read("anthropic").getKey());

            assertEquals(0, auth.execute("logout", "--all"));
            assertTrue(store.list().isEmpty());
        } finally {
            System.setIn(originalInput);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void directOpenAiOauthUsesTheCanonicalCodexCredentialProvider() {
        OAuthProviderRegistry registry = new OAuthProviderRegistry();

        assertEquals("openai-codex",
                AuthCommand.LoginCommand.oauthCredentialProviderId(registry, "openai"));
        assertEquals("openai-codex",
                AuthCommand.LoginCommand.oauthCredentialProviderId(registry, "openai-codex"));
        assertEquals("anthropic",
                AuthCommand.LoginCommand.oauthCredentialProviderId(registry, "anthropic"));
    }

    @Test
    void directOpenAiOauthCommandRoutesThroughTheCodexFlowBeforeNetworkAccess() {
        String originalHome = System.getProperty("user.home");
        PrintStream originalError = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("oauth-alias-home").toString());
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);

            int exit = new CommandLine(new AuthCommand()).execute(
                    "login", "openai", "--oauth", "--method", "unsupported");

            assertEquals(1, exit);
            String error = errors.toString(StandardCharsets.UTF_8);
            assertTrue(error.contains("Unsupported OpenAI Codex"));
            assertTrue(error.contains("login method: unsupported"));
        } finally {
            System.setErr(originalError);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void anthropicOauthLoginRunsClaudeCodesSignInAndStoresNoKompileCredential() throws Exception {
        String originalHome = System.getProperty("user.home");
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("anthropic-claude-code").toString());
        useFakeClaudeCode(tempDir.resolve("claude-signed-in"));
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            // The code the sign-in page shows, pasted on a machine without a browser.
            System.setIn(input("pasted-code#state\n"));
            System.setOut(captured);

            int exit = new CommandLine(new AuthCommand()).execute("login", "anthropic", "--oauth");

            String shown = output.toString(StandardCharsets.UTF_8);
            assertEquals(0, exit, shown);
            assertTrue(shown.contains(CLAUDE_SIGN_IN_LINK), shown);
            assertTrue(shown.contains("Claude Code login verified"), shown);
            // Claude Code keeps the login; no managed credential is minted for anthropic OAuth.
            assertTrue(CredentialStore.create().list("anthropic").isEmpty());
        } finally {
            restoreClaudeCode();
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void anthropicOauthLoginFailsClosedWhenClaudeCodeStaysSignedOut() throws Exception {
        String originalHome = System.getProperty("user.home");
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("anthropic-claude-code-ended").toString());
        useFakeClaudeCode(tempDir.resolve("claude-never-signed-in"));
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream capturedError = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setIn(input(""));
            System.setOut(capturedOut);
            System.setErr(capturedError);

            int exit = new CommandLine(new AuthCommand()).execute("login", "anthropic", "--oauth");

            assertEquals(1, exit);
            String shown = output.toString(StandardCharsets.UTF_8);
            assertTrue(shown.contains("Claude Code sign-in cancelled."), shown);
            String error = errors.toString(StandardCharsets.UTF_8);
            assertTrue(error.contains("Claude Code is still not signed in."), error);
            assertTrue(CredentialStore.create().list("anthropic").isEmpty());
        } finally {
            restoreClaudeCode();
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalError);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void kompileCredentialOptionsDoNotApplyToClaudeCodesLogin() throws Exception {
        String originalHome = System.getProperty("user.home");
        InputStream originalIn = System.in;
        PrintStream originalError = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("anthropic-claude-code-options").toString());
        useFakeClaudeCode(tempDir.resolve("claude-options-signed-in"));
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setIn(input(""));
            System.setErr(captured);

            int exit = new CommandLine(new AuthCommand()).execute(
                    "login", "anthropic", "--oauth", "--name", "work");

            assertEquals(2, exit);
            String error = errors.toString(StandardCharsets.UTF_8);
            assertTrue(error.contains("Kompile credential options do not apply"), error);
            assertTrue(CredentialStore.create().list("anthropic").isEmpty());
        } finally {
            restoreClaudeCode();
            System.setIn(originalIn);
            System.setErr(originalError);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_OUT)
    void storedAnthropicOauthSignInIsNeverActivatedAndIsMarkedInTheListing() throws Exception {
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("anthropic-oauth-use").toString());
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream capturedError = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedError);
            CredentialStore store = CredentialStore.create();
            store.putApiKey("anthropic", "key", "api-secret", true);
            store.putOAuth("anthropic", "subscription", "oauth-access", "refresh",
                    System.currentTimeMillis() + 3_600_000, false);

            int exit = new CommandLine(new AuthCommand()).execute("use", "anthropic", "subscription");

            // Activating it would pin open chats to a credential they can never send.
            assertEquals(2, exit);
            String error = errors.toString(StandardCharsets.UTF_8);
            assertTrue(error.contains("'subscription' is a stored OAuth sign-in"), error);
            assertEquals("key", store.activeCredentialName("anthropic"));
            assertEquals(0, new CommandLine(new AuthCommand()).execute("list", "anthropic"));
            String listing = output.toString(StandardCharsets.UTF_8);
            assertTrue(listing.lines().anyMatch(line -> line.contains("subscription")
                    && line.contains("never sent: Claude Code owns Anthropic OAuth")), listing);
            assertEquals(1, listing.lines().filter(line -> line.contains("never sent")).count(), listing);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalError);
            System.setProperty("user.home", originalHome);
        }
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stand in for Claude Code: a fake `claude auth login` that shows its sign-in
     * link and accepts a pasted code containing '#', and a `claude auth status`
     * probe that reports signed in once it has.
     */
    private void useFakeClaudeCode(Path signedIn) throws IOException {
        Path script = tempDir.resolve("claude-auth-login-" + signedIn.getFileName());
        Files.writeString(script, "#!/usr/bin/env bash\n"
                        + "echo \"If the browser didn't open, visit: " + CLAUDE_SIGN_IN_LINK + "\"\n"
                        + "printf 'Paste code here if prompted > '\n"
                        + "while IFS= read -r code; do\n"
                        + "  case \"$code\" in *'#'*) touch " + signedIn + "; echo 'Login successful.'; exit 0 ;; esac\n"
                        + "  echo 'Invalid code. Please make sure the full code was copied.' >&2\n"
                        + "done\n"
                        + "exit 1\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        ClaudeCodeSignIn.useClaudeBinary(script.toString());
        LiveModelDiscovery.useClaudeCodeLoginProbe(() ->
                new LiveModelDiscovery.ClaudeCodeLogin(Files.exists(signedIn), "claude.ai", "max", false));
    }

    private static void restoreClaudeCode() {
        ClaudeCodeSignIn.useClaudeBinary(null);
        LiveModelDiscovery.useClaudeCodeLoginProbe(null);
    }
}

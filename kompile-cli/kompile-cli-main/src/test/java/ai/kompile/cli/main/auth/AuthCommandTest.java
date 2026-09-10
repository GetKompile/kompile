package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_IN")
@ResourceLock("SYSTEM_ERR")
class AuthCommandTest {
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
            assertTrue(channel.getSubcommands().keySet().containsAll(java.util.Set.of(
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

            System.setIn(input("work-secret\n"));
            assertEquals(0, auth.execute(
                    "login", "openai", "--stdin", "--name", "work", "--no-switch"));

            CredentialStore store = CredentialStore.create();
            assertEquals(2, store.list("openai").size());
            assertEquals("personal", store.activeCredentialName("openai"));
            assertEquals("personal-secret", store.resolveApiKey("openai", name -> null));

            assertEquals(0, auth.execute("switch", "openai", "work"));
            assertEquals("work-secret", store.resolveApiKey("openai", name -> null));

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

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}

package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.MainCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
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
}

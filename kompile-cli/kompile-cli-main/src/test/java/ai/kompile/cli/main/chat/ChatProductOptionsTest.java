package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatProductOptionsTest {
    private ChatCommand parsed(String... flags) {
        ChatCommand command = new ChatCommand();
        new CommandLine(command).parseArgs(flags);
        return command;
    }

    @Test void explicitProductLaunchDoesNotNeedArbitraryRole() {
        ChatCommand command = parsed("--product-profile", "installed/product.json", "--web", "--single-chat", "--working-dir", ".");
        assertNull(command.productOptionError());
        assertNull(command.webOptionError());
        assertNull(parsed("--product-profile", "installed/product.json", "--web-session-worker", "--working-dir", ".", "--output-format", "stream-json").productOptionError());
    }

    @Test void productWorkerRejectsIdentityRoutingPermissionAndConfigEscapesBeforeBootstrap() {
        for (List<String> escape : List.of(
                List.of("--role", "coder"), List.of("--agent", "general"), List.of("--model", "other"),
                List.of("--provider", "custom"), List.of("--base-url", "http://foreign"), List.of("--mode", "passthrough"),
                List.of("--global-config"), List.of("--dangerously-skip-permissions"), List.of("--setup"),
                List.of("--workflow", "team"), List.of("--url", "http://server"), List.of("--prompt", "/role coder"),
                List.of("--input-format", "web-json"), List.of("--resume", "old-session"))) {
            List<String> flags = new ArrayList<>(List.of("--product-profile", "installed/product.json", "--web-session-worker", "--working-dir", "."));
            flags.addAll(escape);
            assertNotNull(parsed(flags.toArray(String[]::new)).productOptionError(), escape.toString());
        }
    }

    @Test void workerCannotLaunchServerOrUseOrdinaryTextControls() {
        assertNotNull(parsed("--web-session-worker", "--web", "--working-dir", ".").productOptionError());
        assertNotNull(parsed("--web-session-worker", "--working-dir", ".", "--output-format", "text").productOptionError());
        assertNotNull(parsed("--product-profile", "x", "--web", "--working-dir", ".").productOptionError());
        assertNotNull(parsed("--product-profile", "x", "--web-session-worker").productOptionError());
    }
}

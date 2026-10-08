package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.exec.WebModelCatalog;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ChatNativeWebRoutingTest {
    @TempDir Path directory;

    /** The framework's live list, without spawning the real {@code opencode models}. */
    @BeforeEach void stubFrameworkModelList() {
        WebModelCatalog.useDiscovery(config -> ModelDiscovery.Result.success(List.of(
                new LiveModelDiscovery.Model("zai/glm-5", List.of()), new LiveModelDiscovery.Model("zai/glm-4", List.of())),
                List.of("native:" + config.getPassthroughAgent())));
    }

    @AfterEach void restoreLiveDiscovery() { WebModelCatalog.useDiscovery(null); }

    private String execute(String input, String... args) {
        var oldIn = System.in;
        var oldOut = System.out;
        var bytes = new ByteArrayOutputStream();
        var options = new ArrayList<>(List.of("--working-dir", directory.toString()));
        options.addAll(List.of(args));
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(out);
            int exit = new CommandLine(new ChatCommand()).execute(options.toArray(String[]::new));
            assertEquals(0, exit, bytes.toString(StandardCharsets.UTF_8));
            return bytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
        }
    }

    @Test void nativeWebConfigQueryDoesNotNeedProviderCredentialsOrStartATurn() {
        String id = UUID.randomUUID().toString();
        String output = execute("{\"version\":1,\"configQuery\":true,\"sessionId\":\"" + id + "\"}\n",
                "--input-format", "web-json", "--output-format", "stream-json", "--web-controls",
                "--local", "--session-id", id, "--mode", "passthrough", "--agent", "opencode",
                "--model", "zai/glm-5", "-");
        assertTrue(output.contains("opencode"), output);
        assertTrue(output.contains("zai/glm-5"), output);
        assertNull(ChatConfig.loadSession(id), "A read-only snapshot must not pin a new chat");
    }

    @Test void quietModelCommandUsesSavedNativeRouteWithoutFolderConfiguration() throws Exception {
        String id = UUID.randomUUID().toString();
        ChatConfig config = new ChatConfig(null, null, "zai/glm-5", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("opencode");
        config.setPassthroughManaged(true);
        config.bindSession(id);
        String output = execute("{\"version\":1,\"rawInput\":\"/model zai/glm-4\",\"sessionId\":\"" + id + "\"}\n",
                "--input-format", "web-json", "--output-format", "stream-json", "--local", "-");
        assertTrue(output.contains("zai/glm-4"), output);
        assertEquals("opencode", ChatConfig.loadSession(id).getPassthroughAgent());
        assertEquals("zai/glm-4", ChatConfig.loadSession(id).getModel());
    }

    @Test void capabilitiesReflectSavedNativeSessionInsteadOfFolderDefault() throws Exception {
        String id = UUID.randomUUID().toString();
        ChatConfig config = new ChatConfig(null, null, "zai/glm-5", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("opencode");
        config.setPassthroughManaged(true);
        config.bindSession(id);
        String output = execute("", "--capabilities", "--resume", id);
        assertTrue(output.contains("opencode"), output);
        assertTrue(output.contains("passthrough"), output);
        assertTrue(output.contains("zai/glm-5"), output);
    }
}

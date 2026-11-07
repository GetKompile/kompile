package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.SharedProcessMirror;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shell call running in the MCP server reaches its parent chat's process
 * pane only through coordination state: the call's log must be published on its
 * first line, tailed live by the chat's mirror, and marked terminal at its end.
 */
class McpToolCallLiveLogTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path workDir;

    private CoordinationStateManager server;
    private CoordinationStateManager chat;
    private BackgroundProcessManager chatProcesses;
    private SharedProcessMirror mirror;

    @BeforeEach
    void setUp() {
        server = new CoordinationStateManager(workDir, "mcp-stdio-live", MAPPER);
        // As a chat's MCP server registers: a child of the chat that started it.
        server.registerAgent("MCP stdio tool session", "chat-session", "claude", 1, 0L, null, null);
        chat = new CoordinationStateManager(workDir, "chat-session", MAPPER);
        chatProcesses = new BackgroundProcessManager("chat-session", workDir);
        mirror = new SharedProcessMirror(chatProcesses, chat, "chat-session");
    }

    @AfterEach
    void tearDown() {
        mirror.close();
        chatProcesses.close();
        server.shutdown();
        chat.shutdown();
    }

    @Test
    void silentCallPublishesNothing() {
        var log = new McpToolCallLiveLog(server, workDir.resolve("tool-calls"), "true", null, "claude");
        log.finish(false);
        assertFalse(log.isPublished());
        assertTrue(server.snapshotProcesses().stream()
                .noneMatch(entry -> log.processId().equals(entry.getProcessId())));
    }

    @Test
    void printingCallIsTailedLiveByTheParentChatAndEndsTerminal() throws Exception {
        var log = new McpToolCallLiveLog(server, workDir.resolve("tool-calls"),
                "mvn -q test", "bash: run tests", "claude");
        log.append("compiling");
        assertTrue(log.isPublished(), "the first line publishes the call's log");

        ProcessCoordEntry published = server.snapshotProcesses().stream()
                .filter(entry -> log.processId().equals(entry.getProcessId()))
                .findFirst().orElseThrow();
        assertEquals(McpToolCallLiveLog.KIND, published.getKind());
        assertEquals("mvn -q test", published.getCommand(), "the client's command, so the chat can match it");
        assertEquals("chat-session", published.getParentSessionId());

        mirror.pollOnce();
        BackgroundProcessManager.ProcessEntry row = mirrored(log.processId());
        assertTrue(row.isRunning());
        assertEquals(McpToolCallLiveLog.KIND, row.getMetadata().get("kind"));
        assertEquals("chat-session", row.getMetadata().get("parentSessionId"));
        assertTrue(chatProcesses.readOutput(row.getId(), 50).contains("compiling"));

        log.append("Tests run: 3, Failures: 0");
        mirror.pollOnce();
        assertTrue(chatProcesses.readOutput(row.getId(), 50).contains("Tests run: 3"),
                "a line written while the call runs must reach the chat before the result");

        log.finish(false);
        log.append("late reader-thread line");
        mirror.pollOnce();
        assertFalse(row.isRunning(), "the call's end marks its row terminal");
        assertEquals(BackgroundProcessManager.ProcessState.COMPLETED, row.getState());
        assertFalse(chatProcesses.readOutput(row.getId(), 50).contains("late reader-thread line"));

        Path file = Path.of(published.getOutputFile());
        log.discard();
        assertFalse(Files.exists(file), "a withdrawn log is deleted");
        assertTrue(server.snapshotProcesses().stream()
                .noneMatch(entry -> log.processId().equals(entry.getProcessId())));
        mirror.pollOnce();
        assertNull(findMirrored(log.processId()), "a withdrawn log leaves the chat's pane");
    }

    @Test
    void failedCallEndsFailed() {
        var log = new McpToolCallLiveLog(server, workDir.resolve("tool-calls"), "false", null, "claude");
        log.append("error: boom");
        log.finish(true);
        mirror.pollOnce();
        assertEquals(BackgroundProcessManager.ProcessState.FAILED, mirrored(log.processId()).getState());
    }

    private BackgroundProcessManager.ProcessEntry mirrored(String processId) {
        BackgroundProcessManager.ProcessEntry entry = findMirrored(processId);
        assertNotNull(entry, "the chat must mirror " + processId);
        return entry;
    }

    private BackgroundProcessManager.ProcessEntry findMirrored(String processId) {
        return chatProcesses.listAll().stream()
                .filter(entry -> processId.equals(entry.getMetadata().get("sharedProcessId")))
                .findFirst().orElse(null);
    }
}

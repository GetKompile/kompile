package ai.kompile.e2e;

import ai.kompile.kclaw.service.SessionService;
import ai.kompile.kclaw.service.impl.JsonlSessionService;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.TokenUsage;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JsonlSessionService: JSONL persistence, message roundtrip with
 * ToolCall and TokenUsage fields, session lifecycle, compaction, and listing.
 */
@Tag("e2e")
@DisplayName("JSONL Session Service")
class JsonlSessionServiceTest {

    private JsonlSessionService sessionService;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("kclaw-session-test");
        sessionService = new JsonlSessionService(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        }
    }

    // ── Basic CRUD ──

    @Test
    @DisplayName("New session returns empty message list")
    void testLoadEmptySession() {
        List<ReActMessage> messages = sessionService.loadSession("nonexistent");
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("sessionExists returns false for new session")
    void testSessionExistsFalse() {
        assertFalse(sessionService.sessionExists("missing-session"));
    }

    @Test
    @DisplayName("Append and load single message roundtrip")
    void testAppendAndLoad() {
        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("Hello world")
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("test-session", msg);

        List<ReActMessage> loaded = sessionService.loadSession("test-session");
        assertEquals(1, loaded.size());
        assertEquals("Hello world", loaded.get(0).getContent());
        assertEquals(ReActMessage.Role.USER, loaded.get(0).getRole());
    }

    @Test
    @DisplayName("sessionExists returns true after append")
    void testSessionExistsTrue() {
        sessionService.appendMessage("exists-test", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("test")
                .timestamp(Instant.now())
                .build());

        assertTrue(sessionService.sessionExists("exists-test"));
    }

    @Test
    @DisplayName("Append multiple messages preserves order")
    void testAppendMultipleMessages() {
        ReActMessage msg1 = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("First")
                .timestamp(Instant.now())
                .build();

        ReActMessage msg2 = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.ASSISTANT)
                .content("Second")
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessages("order-test", List.of(msg1, msg2));

        List<ReActMessage> loaded = sessionService.loadSession("order-test");
        assertEquals(2, loaded.size());
        assertEquals("First", loaded.get(0).getContent());
        assertEquals("Second", loaded.get(1).getContent());
    }

    // ── ToolCall Roundtrip ──

    @Test
    @DisplayName("Roundtrip preserves toolCalls with id, name, and arguments")
    void testToolCallRoundtrip() {
        ToolCall toolCall = ToolCall.builder()
                .id("call-123")
                .name("run_command")
                .arguments(Map.of("command", "ls -la"))
                .rawArguments("{\"command\":\"ls -la\"}")
                .build();

        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.ASSISTANT)
                .content("Running command")
                .toolCalls(List.of(toolCall))
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("toolcall-test", msg);

        List<ReActMessage> loaded = sessionService.loadSession("toolcall-test");
        assertEquals(1, loaded.size());

        ReActMessage loadedMsg = loaded.get(0);
        assertNotNull(loadedMsg.getToolCalls());
        assertEquals(1, loadedMsg.getToolCalls().size());

        ToolCall loadedCall = loadedMsg.getToolCalls().get(0);
        assertEquals("call-123", loadedCall.getId());
        assertEquals("run_command", loadedCall.getName());
        assertEquals("ls -la", loadedCall.getArguments().get("command"));
        assertEquals("{\"command\":\"ls -la\"}", loadedCall.getRawArguments());
    }

    // ── Tool Result Fields ──

    @Test
    @DisplayName("Roundtrip preserves toolCallId, toolName, toolSuccess, toolError")
    void testToolResultFieldsRoundtrip() {
        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.TOOL)
                .content("file listing output")
                .toolCallId("call-456")
                .toolName("run_command")
                .toolSuccess(true)
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("toolresult-test", msg);

        List<ReActMessage> loaded = sessionService.loadSession("toolresult-test");
        assertEquals(1, loaded.size());

        ReActMessage loadedMsg = loaded.get(0);
        assertEquals("call-456", loadedMsg.getToolCallId());
        assertEquals("run_command", loadedMsg.getToolName());
        assertEquals(true, loadedMsg.getToolSuccess());
    }

    @Test
    @DisplayName("Roundtrip preserves toolError field")
    void testToolErrorRoundtrip() {
        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.TOOL)
                .content("Error output")
                .toolCallId("call-789")
                .toolName("run_command")
                .toolSuccess(false)
                .toolError("Permission denied")
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("toolerror-test", msg);

        List<ReActMessage> loaded = sessionService.loadSession("toolerror-test");
        ReActMessage loadedMsg = loaded.get(0);
        assertEquals("Permission denied", loadedMsg.getToolError());
        assertEquals(false, loadedMsg.getToolSuccess());
    }

    // ── TokenUsage Roundtrip ──

    @Test
    @DisplayName("Roundtrip preserves TokenUsage with prompt, completion, total tokens")
    void testTokenUsageRoundtrip() {
        TokenUsage usage = TokenUsage.builder()
                .promptTokens(100L)
                .completionTokens(50L)
                .totalTokens(150L)
                .build();

        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.ASSISTANT)
                .content("Response with usage")
                .usage(usage)
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("usage-test", msg);

        List<ReActMessage> loaded = sessionService.loadSession("usage-test");
        assertEquals(1, loaded.size());

        TokenUsage loadedUsage = loaded.get(0).getUsage();
        assertNotNull(loadedUsage);
        assertEquals(100L, loadedUsage.getPromptTokens());
        assertEquals(50L, loadedUsage.getCompletionTokens());
        assertEquals(150L, loadedUsage.getTotalTokens());
    }

    // ── Thought Field ──

    @Test
    @DisplayName("Roundtrip preserves thought field")
    void testThoughtRoundtrip() {
        ReActMessage msg = ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.ASSISTANT)
                .content("I'll search for that")
                .thought("User wants information about X, I should use the search tool")
                .timestamp(Instant.now())
                .build();

        sessionService.appendMessage("thought-test", msg);

        List<ReActMessage> loaded = sessionService.loadSession("thought-test");
        assertEquals("User wants information about X, I should use the search tool",
                loaded.get(0).getThought());
    }

    // ── Save (Overwrite) ──

    @Test
    @DisplayName("saveSession replaces existing messages")
    void testSaveSessionOverwrites() {
        sessionService.appendMessage("save-test", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("Old message")
                .timestamp(Instant.now())
                .build());

        List<ReActMessage> replacement = List.of(
                ReActMessage.builder()
                        .id(UUID.randomUUID())
                        .role(ReActMessage.Role.USER)
                        .content("New message")
                        .timestamp(Instant.now())
                        .build()
        );

        sessionService.saveSession("save-test", replacement);

        List<ReActMessage> loaded = sessionService.loadSession("save-test");
        assertEquals(1, loaded.size());
        assertEquals("New message", loaded.get(0).getContent());
    }

    // ── Clear ──

    @Test
    @DisplayName("clearSession removes the session file")
    void testClearSession() {
        sessionService.appendMessage("clear-test", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("To be cleared")
                .timestamp(Instant.now())
                .build());

        assertTrue(sessionService.sessionExists("clear-test"));

        sessionService.clearSession("clear-test");

        assertFalse(sessionService.sessionExists("clear-test"));
        assertTrue(sessionService.loadSession("clear-test").isEmpty());
    }

    // ── List Sessions ──

    @Test
    @DisplayName("listSessions returns all session keys")
    void testListSessions() {
        sessionService.appendMessage("session-a", ReActMessage.builder()
                .id(UUID.randomUUID()).role(ReActMessage.Role.USER)
                .content("a").timestamp(Instant.now()).build());

        sessionService.appendMessage("session-b", ReActMessage.builder()
                .id(UUID.randomUUID()).role(ReActMessage.Role.USER)
                .content("b").timestamp(Instant.now()).build());

        List<String> sessions = sessionService.listSessions();
        assertTrue(sessions.contains("session-a"));
        assertTrue(sessions.contains("session-b"));
    }

    // ── Token Estimation ──

    @Test
    @DisplayName("estimateTokenCount returns non-zero for non-empty session")
    void testEstimateTokenCount() {
        sessionService.appendMessage("token-test", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("This is some content that should be counted as tokens")
                .timestamp(Instant.now())
                .build());

        int count = sessionService.estimateTokenCount("token-test");
        assertTrue(count > 0);
    }

    @Test
    @DisplayName("estimateTokenCount returns 0 for empty session")
    void testEstimateTokenCountEmpty() {
        int count = sessionService.estimateTokenCount("empty");
        assertEquals(0, count);
    }

    // ── Compaction ──

    @Test
    @DisplayName("compactSession reduces message count when over threshold")
    void testCompactSession() {
        // Add many messages to exceed a low token threshold
        for (int i = 0; i < 20; i++) {
            sessionService.appendMessage("compact-test", ReActMessage.builder()
                    .id(UUID.randomUUID())
                    .role(ReActMessage.Role.USER)
                    .content("Message number " + i + " with enough content to add up tokens and trigger compaction")
                    .timestamp(Instant.now())
                    .build());
        }

        int beforeCount = sessionService.loadSession("compact-test").size();
        assertEquals(20, beforeCount);

        // Compact with a very low threshold to force compaction
        sessionService.compactSession("compact-test", 1);

        int afterCount = sessionService.loadSession("compact-test").size();
        assertTrue(afterCount < beforeCount);
        // First message should be the summary
        ReActMessage first = sessionService.loadSession("compact-test").get(0);
        assertTrue(first.getContent().contains("[Previous conversation summary]"));
    }

    @Test
    @DisplayName("compactSession does nothing when under threshold")
    void testCompactSessionNoOp() {
        sessionService.appendMessage("nocompact-test", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("Short")
                .timestamp(Instant.now())
                .build());

        sessionService.compactSession("nocompact-test", 100000);

        List<ReActMessage> loaded = sessionService.loadSession("nocompact-test");
        assertEquals(1, loaded.size());
        assertEquals("Short", loaded.get(0).getContent());
    }

    // ── Session Key Sanitization ──

    @Test
    @DisplayName("Session keys with special characters are sanitized")
    void testSessionKeySanitization() {
        sessionService.appendMessage("agent:session/1", ReActMessage.builder()
                .id(UUID.randomUUID())
                .role(ReActMessage.Role.USER)
                .content("sanitized key")
                .timestamp(Instant.now())
                .build());

        List<ReActMessage> loaded = sessionService.loadSession("agent:session/1");
        assertEquals(1, loaded.size());
        assertEquals("sanitized key", loaded.get(0).getContent());
    }

    // ── Workspace Relocation ──

    @Test
    @DisplayName("setWorkspace moves sessions directory")
    void testSetWorkspace() throws Exception {
        Path newDir = Files.createTempDirectory("kclaw-session-relocated");
        try {
            sessionService.setWorkspace(newDir.toString());

            sessionService.appendMessage("relocated-test", ReActMessage.builder()
                    .id(UUID.randomUUID())
                    .role(ReActMessage.Role.USER)
                    .content("in new workspace")
                    .timestamp(Instant.now())
                    .build());

            assertTrue(sessionService.sessionExists("relocated-test"));
            assertEquals("in new workspace",
                    sessionService.loadSession("relocated-test").get(0).getContent());
        } finally {
            Files.walk(newDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        }
    }
}

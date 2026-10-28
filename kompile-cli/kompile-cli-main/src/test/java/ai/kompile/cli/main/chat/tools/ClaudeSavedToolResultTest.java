package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.SubagentTranscript;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ClaudeSavedToolResultTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    @TempDir Path home;
    private String previousHome;
    private Path saved;
    private String payload;

    @BeforeEach
    void setup() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        saved = home.resolve(".claude/projects/project/session/tool-results/mcp-kompile-todowrite-123.txt");
        Files.createDirectories(saved.getParent());
        payload = McpToolResultSerializer.toMcpCallResult(mapper,
                ToolResult.success("Updated task #2", "updated", Map.of("todos", List.of(
                        Map.of("id", "1", "subject", "Finished", "status", "completed"),
                        Map.of("id", "2", "subject", "Working", "status", "in_progress")),
                        ToolResult.OUTPUT_STREAMED_METADATA, true)))
                .path("structuredContent").toString();
        Files.writeString(saved, payload);
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void savedSnapshotRendersInTranscriptAndInspectorInsteadOfStaleArguments() {
        ToolResult result = McpToolResultSerializer.fromStructuredContentText(mapper, notice(saved), false);
        assertNotNull(result);
        assertFalse(result.isOutputStreamed());
        assertFalse(result.isError());
        var renderer = new TerminalRenderer(false);
        String input = "{\"action\":\"update\",\"task_id\":\"2\",\"status\":\"in_progress\"}";
        String display = renderer.renderToolCallComplete("mcp__kompile__todowrite", input, result);
        assertTrue(display.contains("Tasks [1/2]"), display);
        assertTrue(display.contains("▶ #2 Working [current]"), display);
        assertFalse(display.contains("exceeds maximum"), display);
        assertEquals("todos", renderer.renderToolDisplaySections("mcp__kompile__todowrite",
                input, result, "", 128_000).get(0).kind());
    }

    @Test
    void subagentUsesTheSameSavedResultDecoding() {
        var transcript = new SubagentTranscript();
        transcript.toolStart("todo", "mcp__kompile__todowrite", "{}");
        transcript.toolComplete("todo", notice(saved), false);
        var block = transcript.blocks(new TerminalRenderer(false)).get(0);
        assertEquals("todos", block.kind());
        assertTrue(block.classic().contains("▶ #2 Working [current]"), block.classic());
        assertEquals("todos", block.sections().get(0).kind());
    }

    @Test
    void savedResultDoesNotEraseProviderError() {
        ToolResult result = McpToolResultSerializer.fromStructuredContentText(mapper, notice(saved), true);
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void missingMalformedAndNonStructuredFilesLeaveNoticeVisible() throws Exception {
        for (String body : List.of("{truncated", "plain output", "{\"output\":\"no metadata\"}")) {
            Files.writeString(saved, body);
            assertNull(McpToolResultSerializer.fromStructuredContentText(mapper, notice(saved), false));
        }
        assertNull(McpToolResultSerializer.fromStructuredContentText(mapper,
                notice(saved.resolveSibling("mcp-kompile-todoread-missing.txt")), false));
    }

    @Test
    void arbitraryPathsTraversalAndSymlinkEscapesAreNotRead() throws Exception {
        Path outside = home.resolve("mcp-kompile-todowrite-outside.txt");
        Files.writeString(outside, payload);
        Path link = saved.resolveSibling("mcp-kompile-todowrite-link.txt");
        Files.createSymbolicLink(link, outside);
        for (Path path : List.of(outside, link,
                saved.getParent().resolve("../../../../../mcp-kompile-todowrite-outside.txt"))) {
            assertNull(McpToolResultSerializer.fromStructuredContentText(mapper, notice(path), false), path.toString());
        }
    }

    @Test
    void onlyExactClaudeNoticeAndMcpSpillLayoutAreAccepted() throws Exception {
        assertNull(McpToolResultSerializer.fromStructuredContentText(mapper,
                "some tool output\n" + notice(saved), false));
        Path other = saved.resolveSibling("other.txt");
        Files.writeString(other, payload);
        assertNull(McpToolResultSerializer.fromStructuredContentText(mapper, notice(other), false));
        Path wrongDirectory = home.resolve(".claude/projects/project/session/mcp-kompile-todowrite.txt");
        Files.writeString(wrongDirectory, payload);
        assertNull(McpToolResultSerializer.fromStructuredContentText(mapper, notice(wrongDirectory), false));
    }

    @Test
    void oversizedFilesAreNotLoaded() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(saved.toFile(), "rw")) {
            file.setLength(8 * 1024 * 1024 + 1);
        }
        assertNull(McpToolResultSerializer.fromStructuredContentText(mapper, notice(saved), false));
    }

    private static String notice(Path path) {
        return "Error: result (61,623 characters) exceeds maximum allowed tokens. Output has been saved to "
                + path + ".\nFormat: JSON with schema: {output: string, metadata: {todos: [{...}]}}\n";
    }
}

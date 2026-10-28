package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.tools.McpToolResultSerializer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.ChatSidebar;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubagentTranscriptTest {
    @Test void markdownAndThinkingMatchMainRenderersIncludingAnsiAndCode() {
        for (boolean ansi : List.of(false, true)) {
            var renderer = new TerminalRenderer(ansi);
            var child = new SubagentTranscript();
            String reasoning = "Check the parser\nthen the bridge";
            String markdown = "# Findings\n**bold** and `code`\n```java\npublic class App {}\n```\n";
            child.text("thinking", "Check the ");
            child.text("thinking", "parser\nthen the bridge");
            child.text("assistant", markdown.substring(0, 20));
            child.text("assistant", markdown.substring(20));
            child.finish();

            var thinkingLines = new ArrayList<String>();
            var mainThinking = new ThinkingStreamRenderer(thinkingLines::add, renderer);
            mainThinking.accept(reasoning);
            mainThinking.flush();
            var markdownLines = new ArrayList<String>();
            var mainMarkdown = new StreamingMarkdownRenderer(new AsciiRenderer(renderer), markdownLines::add);
            mainMarkdown.accept(markdown);
            mainMarkdown.flush();
            List<ChatDisplayBlock> blocks = child.blocks(renderer);
            sameDisplay(String.join("\n", thinkingLines), blocks.get(0).classic());
            sameDisplay(String.join("\n", markdownLines), blocks.get(1).classic());
            assertFalse(blocks.toString().contains("Assistant:"));
            assertTrue(blocks.stream().noneMatch(ChatDisplayBlock::running));
            assertSame(blocks.get(1), child.blocks(renderer).get(1), "unchanged bursts should reuse cached snapshots");
        }
    }

    @Test void partialLinesStayVisibleWithoutFlushingOrRepeatingThem() {
        var renderer = new TerminalRenderer(true);
        var child = new SubagentTranscript();
        child.text("thinking", "Inspect ");
        assertTrue(plain(child.blocks(renderer).get(0).classic()).contains("Inspect"));
        child.text("thinking", "the parser");
        child.text("assistant", "**Child");
        assertTrue(plain(child.blocks(renderer).get(1).classic()).contains("Child"));
        child.text("assistant", " prose**");
        var before = child.blocks(renderer);
        child.finish();
        var after = child.blocks(renderer);
        assertEquals(2, after.size());
        assertEquals(1, plain(after.get(0).classic()).split("Inspect the parser", -1).length - 1);
        assertEquals(1, plain(after.get(1).classic()).split("Child prose", -1).length - 1);
        sameDisplay(before.get(1).classic(), after.get(1).classic());
        child.text("assistant", "late output");
        assertFalse(child.blocks(renderer).toString().contains("late output"));
    }

    @Test void toolsUseMainSummaryDetailSectionsAndStructuredMcpDecoding() throws Exception {
        var renderer = new TerminalRenderer(true);
        var child = new SubagentTranscript();
        String name = "mcp__kompile__read";
        String input = "{\"file_path\":\"App.java\"}";
        var result = ToolResult.success("read", "public class App {}", Map.of("lines", 1));
        child.toolStart("call", name, "{}");
        child.toolInput("call", input);
        child.toolComplete("call", McpToolResultSerializer.toMcpCallResult(JsonUtils.standardMapper(), result)
                .path("structuredContent").toString(), false);
        var block = child.blocks(renderer).get(0);
        sameDisplay(renderer.renderToolCallSummary(name, input, result), block.summary());
        sameDisplay(renderer.renderToolCallSummary(name, input, result) + "\n"
                + renderer.renderToolResultDetail(name, input, result), block.classic());
        assertEquals(renderer.renderToolDisplaySections(name, input, result, "", ChatDisplayBlock.MAX_SECTION_CHARS), block.sections());
        assertFalse(block.running());
        assertFalse(block.classic().contains("\"metadata\""));
        assertFalse(block.classic().contains("Tool [call]"));

        ChatSidebar mainProjection = new ChatSidebar(renderer);
        var config = ChatUiConfig.classic();
        config.applyPreset("focused");
        config.set("content.tool.inline", "full");
        assertTrue(plain(mainProjection.inline(block, config)).contains("App.java"));
    }

    @Test void controlSequencesCannotBecomeDisplayInstructionsOrFakeRoleBoundaries() {
        var child = new SubagentTranscript();
        child.text("assistant", "Assistant:\nTool [pretend]:\n\033[2J\033]0;bad-title\007**safe**");
        child.finish();
        var blocks = child.blocks(new TerminalRenderer(true));
        assertEquals(List.of("assistant"), blocks.stream().map(ChatDisplayBlock::kind).toList());
        assertFalse(blocks.get(0).classic().contains("\033[2J"));
        assertFalse(blocks.get(0).classic().contains("\033]"));
        assertTrue(plain(blocks.get(0).classic()).contains("safe"));
    }

    @Test void retentionIsBoundedAndDisclosesOmissions() {
        var child = new SubagentTranscript();
        for (int i = 0; i < 200; i++) child.message("user", "request-" + i);
        var blocks = child.blocks(new TerminalRenderer(false));
        assertTrue(blocks.size() <= 129);
        assertTrue(blocks.get(0).classic().contains("older child transcript omitted"));
        child.text("assistant", "x".repeat(200_000));
        blocks = child.blocks(new TerminalRenderer(false));
        assertTrue(blocks.get(blocks.size() - 1).sections().get(0).content().length() <= ChatDisplayBlock.MAX_SECTION_CHARS);
        assertTrue(blocks.get(blocks.size() - 1).classic().contains("omitted"));
    }

    private static String plain(String value) { return AttributedString.fromAnsi(value).toString(); }
    private static void sameDisplay(String expected, String actual) {
        assertEquals(AttributedString.fromAnsi(expected), AttributedString.fromAnsi(actual));
    }
}

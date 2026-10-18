package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatDisplayBlockTest {
    @Test
    void inspectorPreservesMultilineBeyondInlinePreviewAndIgnoresViewerStreamedFlag() {
        String output = "file line\n".repeat(200) + "after-inline-bound\n";
        ToolResult result = ToolResult.success("read", output,
                Map.of(ToolResult.OUTPUT_STREAMED_METADATA, true, "count", 201));
        List<ChatDisplayBlock.Section> detail = TerminalRenderer.toolDisplaySections(
                "read", "{\"file_path\":\"notes.txt\"}", result, output, 128_000);
        assertEquals(output, section(detail, "output").content());
        assertFalse(detail.stream().anyMatch(value -> value.id().equals("live-output")),
                "completed body must supersede identical live output");
        assertTrue(section(detail, "metadata").content().contains("201"));
        assertTrue(TerminalRenderer.toolResultDetailSections("read", "{}", result)
                .stream().anyMatch(TerminalRenderer.DetailSection::truncated));
    }

    @Test
    void complementaryLiveLogsRemainAndStreamedSubsetDoesNotDuplicate() {
        List<ChatDisplayBlock.Section> complementary = TerminalRenderer.toolDisplaySections(
                "bash", "{}", ToolResult.success("done"), "compile log\n", 5_000);
        assertEquals("compile log\n", section(complementary, "live-output").content());
        assertEquals("done", section(complementary, "output").content());
        List<ChatDisplayBlock.Section> overlapping = TerminalRenderer.toolDisplaySections(
                "bash", "{}", ToolResult.success("", "tail", Map.of("outputStreamed", true)),
                "prefix\ntail\n", 5_000);
        assertFalse(overlapping.stream().anyMatch(value -> value.id().equals("live-output")));
        assertEquals("prefix\ntail\n", section(overlapping, "output").content());
    }

    @Test
    void boundsAreExplicitSafeAndImmutableWithoutAnInlineLineCeiling() {
        String unsafe = "first\n\u001b[2J\u001b[31mred\u001b[0m\n\u0007last\tline\n";
        assertEquals("first\nred\nlast\tline\n",
                new ChatDisplayBlock.Section("body", "text", "", unsafe).content());
        String styled = TerminalRenderer.safeStyledDisplayText(unsafe);
        assertFalse(styled.contains("\u001b[2J"));
        assertFalse(styled.contains("\u0007"));
        assertTrue(styled.contains("\u001b[31mred\u001b[0m"));
        assertThrows(IllegalArgumentException.class, () -> TerminalRenderer.toolDisplaySections(
                "read", "", ToolResult.success("x"), "", 0));
        String bounded = ChatDisplayBlock.boundedPlainText("x".repeat(200_000), 2_000);
        assertTrue(bounded.length() <= 2_000);
        assertTrue(bounded.contains("omitted"));
        List<ChatDisplayBlock.Section> input = new ArrayList<>();
        input.add(new ChatDisplayBlock.Section("one", "output", "", "x".repeat(200_000)));
        input.add(new ChatDisplayBlock.Section("two", "output", "", "next"));
        ChatDisplayBlock block = new ChatDisplayBlock("tool:one", "tool", "read", "summary",
                "legacy rendering", input, false, false);
        input.clear();
        assertEquals("legacy rendering", block.classic());
        assertTrue(block.sections().stream().anyMatch(value -> value.content().contains("omitted")));
        assertThrows(UnsupportedOperationException.class, () -> block.sections().clear());
    }

    @Test
    void rawWriteContentAndEditDiffRemainAvailable() {
        String content = "new file line\n".repeat(100) + "last\n\n";
        var mapper = ai.kompile.cli.common.util.JsonUtils.standardMapper();
        String write = mapper.createObjectNode().put("file_path", "A.java").put("content", content).toString();
        assertEquals(content, section(TerminalRenderer.toolDisplaySections(
                "write", write, ToolResult.success("written"), "", 128_000), "content").content());
        String edit = mapper.createObjectNode().put("file_path", "A.java")
                .put("old_string", "old\n").put("new_string", content).toString();
        String diff = section(TerminalRenderer.toolDisplaySections(
                "edit", edit, ToolResult.success("edited"), "", 128_000), "diff").content();
        assertTrue(diff.contains("- old"));
        assertTrue(diff.contains("+ last"));
    }

    @Test
    void presentationRetainsOnlySafeSgrAndKeepsStyledBoundsAndRawContentSeparate() {
        String unsafe = "\033[2J\033[1;34mpublic\033[0m class App\033]0;hostile title\007\033[6n";
        var section = new ChatDisplayBlock.Section("body", "content", "Code", unsafe, unsafe);
        var block = new ChatDisplayBlock("styled", "tool", "read", unsafe, unsafe, List.of(section), false, false);
        assertEquals("public class App", section.content());
        assertTrue(section.formattedContent().contains("\033["));
        assertEquals("public class App", TerminalRenderer.printableToolOutput(block.summary()));
        for (String display : List.of(block.summary(), block.classic(), block.sections().get(0).formattedContent())) {
            assertFalse(display.contains("\033[2J")); assertFalse(display.contains("hostile title"));
            assertFalse(display.contains("\033[6n")); assertFalse(display.contains("\007"));
        }
        String styled = ChatDisplayBlock.boundedStyledText("\033[1;34m" + "x".repeat(5_000) + "\033[0m", 2_000);
        assertTrue(org.jline.utils.AttributedString.fromAnsi(styled).length() <= 2_000);
        assertTrue(styled.contains("omitted"));
        assertEquals(org.jline.utils.AttributedString.fromAnsi("\033[1;34mx\033[0m").styleAt(0),
                org.jline.utils.AttributedString.fromAnsi(styled).styleAt(0));
    }

    @Test
    void retainedSyntaxHighlightingExtendsBeyondTheInlinePreviewWithoutChangingRawBodies() {
        var renderer = new TerminalRenderer(true);
        String output = "public class App {}\n".repeat(200) + "public final class Tail {}";
        var result = ToolResult.success("read", output, Map.of("outputStreamed", true));
        var rendered = section(renderer.renderToolDisplaySections("read", "{\"file_path\":\"App.java\"}", result, output, 128_000), "output");
        assertEquals(output, rendered.content());
        assertEquals(output, TerminalRenderer.printableToolOutput(rendered.formattedContent()));
        var last = org.jline.utils.AttributedString.fromAnsi(rendered.formattedContent()).toString();
        assertTrue(last.contains("Tail"));
        var styledTail = org.jline.utils.AttributedString.fromAnsi(rendered.formattedContent());
        int keyword = styledTail.toString().lastIndexOf("public");
        var expectedKeyword = org.jline.utils.AttributedString.fromAnsi(new SyntaxHighlighter(renderer).highlight("public", "java"));
        assertEquals(expectedKeyword.styleAt(0), styledTail.styleAt(keyword));
        assertNotEquals(org.jline.utils.AttributedStyle.DEFAULT, styledTail.styleAt(keyword));
        assertTrue(TerminalRenderer.toolResultDetailSections("read", "{\"file_path\":\"App.java\"}", result).stream().anyMatch(TerminalRenderer.DetailSection::truncated));
    }

    private static ChatDisplayBlock.Section section(List<ChatDisplayBlock.Section> sections, String id) {
        return sections.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }
}

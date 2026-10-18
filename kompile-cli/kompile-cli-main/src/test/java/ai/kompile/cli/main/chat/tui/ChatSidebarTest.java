package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatSidebarTest {
    private static ChatUiConfig focused() { ChatUiConfig config = ChatUiConfig.classic(); config.applyPreset("focused"); return config; }
    private static ChatDisplayBlock tool(String key, String output, boolean error) {
        return new ChatDisplayBlock(key, "tool", "bash", "Build", "Build\n" + output,
                List.of(new ChatDisplayBlock.Section("output", "output", "Output", output),
                        new ChatDisplayBlock.Section("diff", "diff", "Diff", "+new\n-old")), error, false);
    }
    @Test void toolHeadingKeepsTranscriptStyleInSidebarDrawerAndInspector() {
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        var result = ai.kompile.cli.main.chat.tools.ToolResult.success("read", "public class App {}", java.util.Map.of());
        String input = "{\"file_path\":\"App.java\"}";
        String summary = renderer.renderToolCallSummary("read", input, result);
        ChatDisplayBlock block = new ChatDisplayBlock("read", "tool", "read", summary,
                renderer.renderToolCallComplete("read", input, result),
                ai.kompile.cli.main.chat.render.TerminalRenderer.toolDisplaySections("read", input, result, "", 128_000), false, false);
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused(); sidebar.upsert(block, config);
        var expected = org.jline.utils.AttributedString.fromAnsi(summary);
        for (List<String> view : List.of(sidebar.allRows(config, 100), sidebar.render(config, 100, 20), sidebar.inspectorLines())) {
            var row = view.stream().map(org.jline.utils.AttributedString::fromAnsi).filter(s -> s.toString().contains("Read")).findFirst().orElseThrow();
            assertEquals(expected.styleAt(expected.toString().indexOf("Read")), row.styleAt(row.toString().indexOf("Read")),
                    "Alternative view must preserve the transcript tool name's color/bold style");
        }
    }

    private static ChatDisplayBlock highlightedTool(String tool, String input, String output) {
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        var result = ai.kompile.cli.main.chat.tools.ToolResult.success(tool, output, java.util.Map.of());
        return new ChatDisplayBlock("tool:" + tool, "tool", tool, renderer.renderToolCallSummary(tool, input, result),
                renderer.renderToolCallComplete(tool, input, result), renderer.renderToolDisplaySections(tool, input, result, "", 128_000), false, false);
    }

    private static void sameStyle(List<String> actualLines, String expectedRendering, String token) {
        var expected = java.util.Arrays.stream(expectedRendering.split("\\R"))
                .map(org.jline.utils.AttributedString::fromAnsi).filter(line -> line.toString().contains(token)).findFirst().orElseThrow();
        var style = expected.styleAt(expected.toString().indexOf(token));
        assertNotEquals(org.jline.utils.AttributedStyle.DEFAULT, style, "Reference transcript must actually style " + token);
        assertTrue(actualLines.stream().map(org.jline.utils.AttributedString::fromAnsi)
                .filter(line -> line.toString().contains(token))
                .anyMatch(line -> style.equals(line.styleAt(line.toString().indexOf(token)))),
                () -> "Missing transcript style for " + token + " in " + actualLines);
    }

    @Test void codeReadWriteAndMixedToolOutputKeepSyntaxStylesInEveryLocation() {
        List<String[]> cases = List.of(
                new String[]{"read", "{\"file_path\":\"App.java\"}", "public class App { String message = \"hello\"; int value = 42; }", "public", "\"hello\"", "42"},
                new String[]{"write", "{\"file_path\":\"script.py\",\"content\":\"def greet():\\n    return \\\"hello\\\"\"}", "written", "def", "return", "\"hello\""},
                new String[]{"grep_batch", "{}", "src/App.java:7:public class App {\nsrc/script.py:8:def greet():\nsrc/config.json:9:{\"enabled\":true}", "public", "def", "true"},
                new String[]{"read_batch", "{}", "== App.java (lines 1-2 of 2)\npublic class App {}\n== script.py (lines 1-2 of 2)\ndef greet():", "public", "def"},
                new String[]{"bash", "{}", "src/main.cpp:3: static int n = 42;", "static", "int", "42"});
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        for (String[] test : cases) {
            ChatSidebar sidebar = new ChatSidebar(renderer); ChatUiConfig config = focused();
            config.set("content.tool.sidebar", "expanded"); config.set("content.tool.inline", "full");
            ChatDisplayBlock block = highlightedTool(test[0], test[1], test[2]); sidebar.upsert(block, config);
            String reference = renderer.renderToolResultDetail(test[0], test[1], ai.kompile.cli.main.chat.tools.ToolResult.success(test[2]), false);
            for (List<String> view : List.of(sidebar.allRows(config, 200), sidebar.render(config, 200, 100),
                    sidebar.inspectorLines(), List.of(sidebar.inline(block, config).split("\\R")))) {
                for (int i = 3; i < test.length; i++) sameStyle(view, reference, test[i]);
            }
        }
    }

    @Test void todoListAndCurrentItemStyleAreIdenticalInEveryView() {
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        var result = ai.kompile.cli.main.chat.tools.ToolResult.success("updated", "generic output",
                java.util.Map.of("todos", List.of(
                        java.util.Map.of("id", "1", "subject", "Working", "status", "in_progress"),
                        java.util.Map.of("id", "2", "subject", "Finished", "status", "completed"))));
        String reference = renderer.renderToolResultDetail("todowrite", "{}", result);
        ChatDisplayBlock block = new ChatDisplayBlock("tasks", "todos", "todowrite",
                renderer.renderToolCallSummary("todowrite", "{}", result),
                renderer.renderToolCallComplete("todowrite", "{}", result),
                renderer.renderToolDisplaySections("todowrite", "{}", result, "", 128_000), false, false);
        ChatSidebar sidebar = new ChatSidebar(renderer);
        ChatUiConfig config = focused();
        config.set("content.todos.sidebar", "expanded");
        config.set("content.todos.inline", "full");
        sidebar.upsert(block, config);
        for (List<String> view : List.of(sidebar.allRows(config, 100), sidebar.render(config, 100, 30),
                sidebar.inspectorLines(), List.of(sidebar.inline(block, config).split("\\R")))) {
            String plain = ai.kompile.cli.main.chat.render.TerminalRenderer.printableToolOutput(String.join("\n", view));
            assertTrue(plain.contains("Tasks [1/2]"), plain);
            assertTrue(plain.contains("▶ #1 Working [current]"), plain);
            assertFalse(plain.contains("generic output"), plain);
            for (String token : List.of("Working", "[current]", "Finished")) sameStyle(view, reference, token);
        }
    }

    @Test void editDiffStylesMatchTranscriptIncludingHeadersAndContext() {
        String input = "{\"file_path\":\"App.java\",\"old_string\":\"old\",\"new_string\":\"new\"}";
        ChatDisplayBlock block = highlightedTool("edit", input, "edited");
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("content.tool.sidebar", "expanded"); sidebar.upsert(block, config);
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        String reference = renderer.renderToolResultDetail("edit", input, ai.kompile.cli.main.chat.tools.ToolResult.success("edited"), false);
        for (List<String> view : List.of(sidebar.allRows(config, 120), sidebar.inspectorLines())) {
            for (String token : List.of("App.java", "- old", "+ new")) sameStyle(view, reference, token);
        }
    }

    @Test void unifiedPatchHeadersHunksAndContextUseTheSameTranscriptColors() {
        var mapper = ai.kompile.cli.common.util.JsonUtils.standardMapper();
        String input = mapper.createObjectNode().put("patch", "--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+new\n context").toString();
        ChatDisplayBlock block = highlightedTool("patch", input, "patched");
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused(); config.set("content.tool.sidebar", "expanded");
        sidebar.upsert(block, config);
        String reference = new ai.kompile.cli.main.chat.render.TerminalRenderer(true).renderToolResultDetail("patch", input, ai.kompile.cli.main.chat.tools.ToolResult.success("patched"), false);
        for (List<String> view : List.of(sidebar.allRows(config, 120), sidebar.inspectorLines())) {
            for (String token : List.of("--- a", "+++ b", "@@ -1", "-old", "+new", " context")) sameStyle(view, reference, token);
        }
    }

    @Test void wrapAndClipPreserveTokenStylesAndPlainRendererStaysUnstyled() {
        ChatDisplayBlock block = highlightedTool("read", "{\"file_path\":\"App.java\"}", "public class App { \"" + "x".repeat(100) + "\"; }");
        ChatUiConfig config = focused(); config.set("content.tool.sidebar", "expanded");
        var sidebar = new ChatSidebar(); sidebar.upsert(block, config);
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        String reference = renderer.renderToolResultDetail("read", "{\"file_path\":\"App.java\"}", ai.kompile.cli.main.chat.tools.ToolResult.success(block.sections().stream().filter(s -> s.id().equals("output")).findFirst().orElseThrow().content()), false);
        for (String wrap : List.of("true", "false")) {
            config.set("sidebar.wrap", wrap);
            List<String> rows = sidebar.allRows(config, 25);
            sameStyle(rows, reference, "public");
            assertTrue(rows.stream().allMatch(line -> org.jline.utils.AttributedString.fromAnsi(line).columnLength() <= 25));
        }
        var plain = new ChatSidebar(new ai.kompile.cli.main.chat.render.TerminalRenderer(false)); plain.upsert(block, config);
        assertFalse(String.join("\n", plain.allRows(config, 100)).contains("\033"));
        assertFalse(String.join("\n", plain.inspectorLines()).contains("\033"));
    }

    @Test void styledAssistantThinkingAndSubagentBodiesAreNotFlattened() {
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(true);
        for (String kind : List.of("assistant", "thinking", "subagent", "custom", "process")) {
            String formatted = renderer.bold(renderer.cyan("styled body"));
            if (kind.equals("thinking")) formatted = renderer.dim(renderer.italic("styled body"));
            ChatDisplayBlock block = new ChatDisplayBlock(kind, kind, "", "", formatted,
                    List.of(new ChatDisplayBlock.Section("body", kind, "", "styled body", formatted)), false, false);
            ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
            config.set("content." + kind + ".sidebar", "expanded"); sidebar.upsert(block, config);
            sameStyle(sidebar.allRows(config, 100), formatted, "styled body");
            sameStyle(sidebar.inspectorLines(), formatted, "styled body");
        }
    }

    @Test void routingPreservesClassicAndKeepsFocusedOutputOffMain() {
        ChatSidebar sidebar = new ChatSidebar(); ChatDisplayBlock block = tool("one", "secret output", false);
        assertEquals(block.classic(), sidebar.inline(block, ChatUiConfig.classic()));
        assertEquals("", sidebar.inline(block, focused()));
        assertEquals("Build", sidebar.inline(tool("failed", "failure", true), focused()));
        ChatUiConfig config = focused(); config.set("content.diff.inline", "full");
        assertTrue(sidebar.inline(block, config).contains("+new"));
        assertFalse(sidebar.inline(block, config).contains("secret output"));
    }
    @Test void updatesKeepCollapseSelectionAndRetainFullInspectorOutput() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        sidebar.upsert(tool("one", "x".repeat(10_000), false), config);
        sidebar.toggleSelected(config);
        sidebar.toggleSelected(config);
        sidebar.upsert(tool("one", "y".repeat(10_000), false), config);
        sidebar.upsert(tool("two", "new item", false), config);
        assertEquals("one", sidebar.selectedKey());
        assertFalse(String.join("\n", sidebar.allRows(config, 80)).contains("yyyy"));
        assertTrue(String.join("\n", sidebar.inspectorLines()).contains("y".repeat(10_000)));
        assertEquals(2, sidebar.blocks().size());
    }
    @Test void filtersCustomContentAndExplicitSectionPolicies() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        sidebar.upsert(tool("one", "output", false), config);
        sidebar.upsert(new ChatDisplayBlock("custom", "custom", "", "Notes", "Notes", List.of(new ChatDisplayBlock.Section("body", "custom", "Notes", "markdown body")), false, false), config);
        sidebar.setFilter("custom");
        String custom = String.join("\n", sidebar.allRows(config, 70));
        assertTrue(custom.contains("Notes")); assertFalse(custom.contains("Build"));
        sidebar.upsert(new ChatDisplayBlock("custom", "custom", "", "", "", List.of(), false, false), config);
        assertNull(sidebar.get("custom"));
    }
    @Test void sidebarHeaderStaysVisibleForEveryContentKindAndHeight() {
        for (String kind : List.of("tool", "process", "subagent", "assistant", "thinking", "custom")) {
            ChatSidebar sidebar = new ChatSidebar();
            ChatUiConfig config = focused();
            config.set("content." + kind + ".sidebar", "expanded");
            config.set("content.output.sidebar", "expanded");
            String body = "long process output\n".repeat(100);
            sidebar.upsert(new ChatDisplayBlock(kind, kind, "", "Heading", body,
                    List.of(new ChatDisplayBlock.Section("output", "output", "Output", body)), false, false), config);
            for (int height : List.of(0, 1, 2, 5, 20, 3)) {
                List<String> visible = sidebar.render(config, 35, height);
                assertTrue(visible.size() <= height);
                if (height > 0) assertTrue(visible.get(0).contains("Results"));
                sidebar.scroll(-3, config, 35, height);
                List<String> scrolled = sidebar.render(config, 35, height);
                if (height > 0) assertTrue(scrolled.get(0).contains("Results"));
                assertFalse(sidebar.clickRow(0, config, 35, height), "pinned heading is not a card");
            }
            assertTrue(String.join("\n", sidebar.inspectorLines()).contains(body.stripTrailing()));
            assertTrue(sidebar.render(config, 0, 5).isEmpty());
        }
    }

    @Test void manualScrollDoesNotJumpAndRowsRemainWithinWidth() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.follow", "manual");
        for (int i = 0; i < 20; i++) sidebar.upsert(tool("t" + i, "output", false), config);
        List<String> before = sidebar.render(config, 25, 6);
        sidebar.upsert(tool("new", "new output", false), config);
        assertEquals(before.subList(1, before.size()), sidebar.render(config, 25, 6).subList(1, before.size()));
        assertTrue(sidebar.allRows(config, 25).stream().allMatch(line -> org.jline.utils.AttributedString.fromAnsi(line).columnLength() <= 25));
    }
    @Test void configuredSectionExpansionAndPinningHaveVisibleEffects() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("content.output.sidebar", "expanded");
        sidebar.upsert(tool("one", "FIRST OUTPUT", false), config);
        sidebar.toggleSelected(config);
        assertTrue(String.join("\n", sidebar.allRows(config, 80)).contains("FIRST OUTPUT"));
        sidebar.toggleSection("output", config);
        assertFalse(String.join("\n", sidebar.allRows(config, 80)).contains("FIRST OUTPUT"));
        sidebar.upsert(tool("two", "SECOND OUTPUT", false), config);
        sidebar.selectKey("two"); sidebar.togglePin();
        String rows = String.join("\n", sidebar.allRows(config, 80));
        assertTrue(rows.indexOf("Build") < rows.lastIndexOf("Build"));
        sidebar.selectKey("one");
        assertTrue(sidebar.isPinned());
    }
    @Test void keyboardSelectionRemainsVisibleAndCachedRowsRefreshOnPolicyChanges() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.follow", "manual");
        for (int i = 0; i < 20; i++) sidebar.upsert(new ChatDisplayBlock("t" + i, "tool", "read", "READ-" + i, "", List.of(), false, false), config);
        sidebar.render(config, 40, 5);
        for (int i = 0; i < 12; i++) sidebar.select(1, config);
        assertTrue(String.join("\n", sidebar.render(config, 40, 5)).contains("READ-12"));
        config.set("content.tool.sidebar", "off");
        assertTrue(String.join("\n", sidebar.allRows(config, 40)).contains("No matching results"));
    }
    @Test void hiddenTextCannotEvictVisibleEditsAtTheItemLimit() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        sidebar.upsert(tool("selected", "selected result", false), config);
        sidebar.upsert(tool("edit", "retained edit", false), config);
        for (int i = 0; i < 260; i++) {
            sidebar.upsert(ChatDisplayBlock.text("text" + i, i % 2 == 0 ? "assistant" : "thinking", "hidden text"), config);
        }
        assertNotNull(sidebar.get("edit"), "Hidden text must be evicted before a configured visible edit");
        sidebar.selectKey("edit");
        assertTrue(String.join("\n", sidebar.inspectorLines()).contains("+new"));
        assertTrue(sidebar.blocks().size() <= 256);
    }

    @Test void hiddenTextCannotEvictVisibleEditsAtTheCharacterLimit() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        sidebar.upsert(tool("selected", "selected result", false), config);
        sidebar.upsert(tool("edit", "retained edit", false), config);
        String body = "hidden text\n".repeat(8_000);
        for (int i = 0; i < 12; i++) sidebar.upsert(ChatDisplayBlock.text("text" + i, "assistant", body), config);
        assertNotNull(sidebar.get("edit"), "Hidden assistant output must not consume visible edit history");
    }

    @Test void configuredCountEvictsOldestUnprotectedCardAndKeepsPinnedAndRunningCards() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.maxItems", "3");
        sidebar.upsert(tool("pinned", "pin", false), config);
        sidebar.togglePin();
        sidebar.upsert(tool("old", "old edit", false), config);
        sidebar.upsert(tool("selected", "selection", false), config);
        sidebar.selectKey("selected");
        sidebar.upsert(new ChatDisplayBlock("running", "tool", "edit", "Running", "", List.of(), false, true), config);
        sidebar.upsert(tool("new", "new edit", false), config);
        assertNull(sidebar.get("old"));
        for (String key : List.of("pinned", "selected", "running", "new")) assertNotNull(sidebar.get(key), key);
        config.set("sidebar.maxItems", "1");
        sidebar.trim(config);
        assertNull(sidebar.get("new"));
        assertNotNull(sidebar.get("pinned"));
        assertNotNull(sidebar.get("selected"));
        assertNotNull(sidebar.get("running"));
    }

    @Test void hiddenFirstMessageDoesNotAcquireSelectionProtectionAgainstVisibleEdits() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.maxChars", "1024");
        sidebar.upsert(ChatDisplayBlock.text("hidden", "assistant", "x".repeat(2_000)), config);
        sidebar.upsert(tool("edit", "retained edit", false), config);
        assertEquals("edit", sidebar.selectedKey());
        assertNotNull(sidebar.get("edit"));
        assertNull(sidebar.get("hidden"));
    }

    @Test void configuredCharacterBudgetEvictsOldestUnprotectedCard() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.maxChars", "1024");
        sidebar.upsert(tool("selected", "selection", false), config);
        sidebar.upsert(tool("old", "a".repeat(200), false), config);
        sidebar.upsert(tool("new", "b".repeat(200), false), config);
        assertNull(sidebar.get("old"));
        assertNotNull(sidebar.get("new"));
        assertNotNull(sidebar.get("selected"));
    }

    @Test void filtersAndHiddenSectionGroupsDoNotDiscardVisibleHistory() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        config.set("sidebar.maxItems", "3");
        config.set("sidebar.sections", "tools");
        config.set("toolOverrides.read.sidebar", "off");
        config.set("content.tool.sidebar", "off");
        config.set("content.diff.sidebar", "expanded");
        sidebar.upsert(tool("selected", "selection", false), config);
        sidebar.upsert(tool("edit", "retained edit", false), config);
        sidebar.setFilter("processes"); // A transient filter is not a retention policy.
        for (int i = 0; i < 5; i++) {
            sidebar.upsert(new ChatDisplayBlock("hidden" + i, "tool", "read", "Read", "",
                    List.of(new ChatDisplayBlock.Section("output", "output", "Output", "read text")), false, false), config);
            sidebar.upsert(ChatDisplayBlock.text("custom" + i, "custom", "hidden group"), config);
        }
        assertNotNull(sidebar.get("edit"));
        sidebar.setFilter("all");
        sidebar.selectKey("edit"); sidebar.toggleSelected(config);
        assertTrue(String.join("\n", sidebar.allRows(config, 80)).contains("+new"));
    }

    @Test void completedRetentionIsBoundedAndSelectedItemSurvives() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        for (int i = 0; i < 300; i++) sidebar.upsert(tool("t" + i, "data", false), config);
        assertTrue(sidebar.blocks().size() <= 256);
        assertNotNull(sidebar.get(sidebar.selectedKey()));
        assertTrue(String.join("\n", sidebar.allRows(config, 80)).contains("older cards omitted"));
    }

    @Test void latestShortcutTracksScrollingAwayFromAndBackToNewestCards() {
        ChatSidebar sidebar = new ChatSidebar(); ChatUiConfig config = focused();
        for (int i = 0; i < 40; i++) {
            sidebar.upsert(new ChatDisplayBlock("t" + i, "tool", "read", "diff " + i, "diff " + i,
                    List.of(), false, false), config);
        }
        int width = 40, height = 8;
        // Fresh view follows the tail: no shortcut, newest card visible.
        assertFalse(sidebar.isScrolledAwayFromLatest(config, width, height));
        assertTrue(sidebar.render(config, width, height).stream().anyMatch(row -> row.contains("diff 39")));
        // Wheel up detaches from the tail: shortcut appears, newest card no longer visible.
        sidebar.scroll(-5, config, width, height);
        assertTrue(sidebar.isScrolledAwayFromLatest(config, width, height));
        assertFalse(sidebar.render(config, width, height).stream().anyMatch(row -> row.contains("diff 39")));
        // A newer diff arriving while detached keeps the shortcut (no forced snap).
        sidebar.upsert(new ChatDisplayBlock("t40", "tool", "read", "diff 40", "diff 40",
                List.of(), false, false), config);
        assertTrue(sidebar.isScrolledAwayFromLatest(config, width, height));
        // Jump-to-latest restores tail following and shows the newest card again.
        sidebar.jumpToLatest(config, width, height);
        assertFalse(sidebar.isScrolledAwayFromLatest(config, width, height));
        assertTrue(sidebar.render(config, width, height).stream().anyMatch(row -> row.contains("diff 40")));
        // Scrolling back down to the bottom also re-engages following.
        sidebar.scroll(-1, config, width, height);
        sidebar.scroll(1, config, width, height);
        assertFalse(sidebar.isScrolledAwayFromLatest(config, width, height));
    }
}

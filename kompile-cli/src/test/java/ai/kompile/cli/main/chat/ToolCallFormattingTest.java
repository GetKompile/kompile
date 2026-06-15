/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for tool call formatting and rendering in {@link TerminalRenderer}.
 * <p>
 * Covers: {@code stripMcpPrefix}, {@code prettifyToolName}, {@code prettifyToolInput},
 * {@code renderToolCallStart}, {@code renderToolCallRunning}, {@code renderToolCallComplete},
 * {@code renderToolCallDenied}, {@code renderSubagentToolCall}, {@code renderSubagentStart},
 * {@code renderSubagentComplete}, {@code renderSubagentError}, {@code renderContextGroup},
 * {@code truncatePreview}, TOOL_ICONS mapping, and PRIMARY_PARAMS extraction.
 */
class ToolCallFormattingTest {

    /** ANSI disabled so assertions compare plain text without escape codes. */
    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TerminalRenderer(false);
    }

    // ===================================================================
    // stripMcpPrefix
    // ===================================================================

    @Nested
    class StripMcpPrefix {

        @Test
        void mcpKompileRead_shouldStripToRead() {
            assertEquals("read", TerminalRenderer.stripMcpPrefix("mcp__kompile__read"));
        }

        @Test
        void mcpKompileCodeSearch_shouldStripToCodeSearch() {
            assertEquals("code_search", TerminalRenderer.stripMcpPrefix("mcp__kompile__code_search"));
        }

        @Test
        void mcpWithMultipleSegments_shouldTakeLastSegment() {
            assertEquals("auth", TerminalRenderer.stripMcpPrefix("mcp__claude_ai_Gmail__auth"));
        }

        @Test
        void plainToolName_shouldLowercase() {
            assertEquals("read", TerminalRenderer.stripMcpPrefix("Read"));
        }

        @Test
        void alreadyLowercase_shouldReturnAsIs() {
            assertEquals("bash", TerminalRenderer.stripMcpPrefix("bash"));
        }

        @Test
        void nullInput_shouldReturnEmpty() {
            assertEquals("", TerminalRenderer.stripMcpPrefix(null));
        }

        @Test
        void emptyInput_shouldReturnEmpty() {
            assertEquals("", TerminalRenderer.stripMcpPrefix(""));
        }

        @Test
        void mcpPrefixOnly_shouldReturnLowercased() {
            // "mcp__" with no further segments — lastIndexOf("__") == 3 which is <= 4
            String result = TerminalRenderer.stripMcpPrefix("mcp__");
            assertEquals("mcp__", result);
        }

        @Test
        void mcpWithSingleSegment_shouldReturnToolName() {
            // mcp__server__tool → tool
            assertEquals("tool", TerminalRenderer.stripMcpPrefix("mcp__server__tool"));
        }

        @Test
        void upperCaseMixed_shouldLowercase() {
            assertEquals("toolsearch", TerminalRenderer.stripMcpPrefix("ToolSearch"));
        }
    }

    // ===================================================================
    // prettifyToolName
    // ===================================================================

    @Nested
    class PrettifyToolName {

        @Test
        void simpleLowercase_shouldCapitalize() {
            assertEquals("Read", TerminalRenderer.prettifyToolName("read"));
        }

        @Test
        void mcpPrefixed_shouldStripAndCapitalize() {
            assertEquals("Read", TerminalRenderer.prettifyToolName("mcp__kompile__read"));
        }

        @Test
        void underscoreSeparated_shouldTitleCase() {
            assertEquals("Code Search", TerminalRenderer.prettifyToolName("code_search"));
        }

        @Test
        void mcpPrefixedUnderscore_shouldStripAndTitleCase() {
            assertEquals("Code Search", TerminalRenderer.prettifyToolName("mcp__kompile__code_search"));
        }

        @Test
        void camelCase_shouldPreserve() {
            assertEquals("ToolSearch", TerminalRenderer.prettifyToolName("ToolSearch"));
        }

        @Test
        void lowerCamelCase_shouldCapitalizeFirst() {
            assertEquals("ToolSearch", TerminalRenderer.prettifyToolName("toolSearch"));
        }

        @Test
        void nullInput_shouldReturnUnknown() {
            assertEquals("unknown", TerminalRenderer.prettifyToolName(null));
        }

        @Test
        void emptyInput_shouldReturnUnknown() {
            assertEquals("unknown", TerminalRenderer.prettifyToolName(""));
        }

        @Test
        void singleChar_shouldCapitalize() {
            assertEquals("X", TerminalRenderer.prettifyToolName("x"));
        }

        @Test
        void multipleUnderscores_shouldTitleCaseAll() {
            assertEquals("Multi Task Runner", TerminalRenderer.prettifyToolName("multi_task_runner"));
        }

        @Test
        void exec_shouldCapitalize() {
            assertEquals("Exec", TerminalRenderer.prettifyToolName("exec"));
        }
    }

    // ===================================================================
    // prettifyToolInput — PRIMARY_PARAMS extraction
    // ===================================================================

    @Nested
    class PrettifyToolInput {

        @Test
        void readToolWithFilePath_shouldExtractPath() {
            String input = "{\"file_path\":\"/tmp/test.txt\",\"limit\":100}";
            String result = TerminalRenderer.prettifyToolInput("read", input, 80);
            assertEquals("/tmp/test.txt", result);
        }

        @Test
        void bashToolWithCommand_shouldExtractCommand() {
            String input = "{\"command\":\"ls -la /tmp\"}";
            String result = TerminalRenderer.prettifyToolInput("bash", input, 80);
            assertEquals("ls -la /tmp", result);
        }

        @Test
        void grepToolWithPatternAndPath_shouldJoinWithIn() {
            String input = "{\"pattern\":\"TODO\",\"path\":\"src/main\"}";
            String result = TerminalRenderer.prettifyToolInput("grep", input, 80);
            assertEquals("TODO in src/main", result);
        }

        @Test
        void grepToolWithPatternOnly_shouldReturnPattern() {
            String input = "{\"pattern\":\"import.*Stream\"}";
            String result = TerminalRenderer.prettifyToolInput("grep", input, 80);
            assertEquals("import.*Stream", result);
        }

        @Test
        void globToolWithPattern_shouldExtract() {
            String input = "{\"pattern\":\"**/*.java\"}";
            String result = TerminalRenderer.prettifyToolInput("glob", input, 80);
            assertEquals("**/*.java", result);
        }

        @Test
        void writeToolWithFilePath_shouldExtract() {
            String input = "{\"file_path\":\"/src/Main.java\",\"content\":\"public class Main {}\"}";
            String result = TerminalRenderer.prettifyToolInput("write", input, 80);
            assertEquals("/src/Main.java", result);
        }

        @Test
        void webSearchWithQuery_shouldExtract() {
            String input = "{\"query\":\"kompile framework documentation\"}";
            String result = TerminalRenderer.prettifyToolInput("websearch", input, 80);
            assertEquals("kompile framework documentation", result);
        }

        @Test
        void agentToolWithDescription_shouldExtract() {
            String input = "{\"description\":\"Investigate the failing test\"}";
            String result = TerminalRenderer.prettifyToolInput("agent", input, 80);
            assertEquals("Investigate the failing test", result);
        }

        @Test
        void unknownToolWithJson_shouldFallbackToKeyValuePairs() {
            String input = "{\"foo\":\"bar\",\"baz\":42}";
            String result = TerminalRenderer.prettifyToolInput("unknown_tool", input, 80);
            assertTrue(result.contains("foo=bar"));
            assertTrue(result.contains("baz=42"));
        }

        @Test
        void nonJsonPlainText_shouldReturnTruncated() {
            String input = "ls -la /tmp";
            String result = TerminalRenderer.prettifyToolInput("bash", input, 80);
            assertEquals("ls -la /tmp", result);
        }

        @Test
        void nullInput_shouldReturnEmpty() {
            assertEquals("", TerminalRenderer.prettifyToolInput("read", null, 80));
        }

        @Test
        void blankInput_shouldReturnEmpty() {
            assertEquals("", TerminalRenderer.prettifyToolInput("read", "   ", 80));
        }

        @Test
        void emptyJsonObject_shouldReturnEmpty() {
            // No primary params found, no fields → empty fallback
            String result = TerminalRenderer.prettifyToolInput("read", "{}", 80);
            assertEquals("", result);
        }

        @Test
        void longInput_shouldTruncate() {
            String longPath = "/very/long/deeply/nested/directory/structure/that/exceeds/the/limit/file.txt";
            String input = "{\"file_path\":\"" + longPath + "\"}";
            String result = TerminalRenderer.prettifyToolInput("read", input, 30);
            assertTrue(result.length() <= 30, "Should be truncated to maxLen");
            assertTrue(result.endsWith("..."), "Truncated output should end with ...");
        }

        @Test
        void jsonWithNullPrimaryParam_shouldSkip() {
            String input = "{\"file_path\":null,\"other\":\"val\"}";
            String result = TerminalRenderer.prettifyToolInput("read", input, 80);
            // file_path is null → skipped, falls back to key=value
            assertTrue(result.contains("other=val"));
        }

        @Test
        void memoryToolWithQueryKey_shouldExtract() {
            String input = "{\"query\":\"what is the user's role\"}";
            String result = TerminalRenderer.prettifyToolInput("memory", input, 80);
            assertEquals("what is the user's role", result);
        }

        @Test
        void memoryToolWithKeyKey_shouldExtract() {
            String input = "{\"key\":\"user_preferences\"}";
            String result = TerminalRenderer.prettifyToolInput("memory", input, 80);
            assertEquals("user_preferences", result);
        }
    }

    // ===================================================================
    // renderToolCallStart
    // ===================================================================

    @Nested
    class RenderToolCallStart {

        @Test
        void readToolWithJsonInput_shouldShowIconNameAndPath() {
            String output = renderer.renderToolCallStart("read", "{\"file_path\":\"/src/Main.java\"}");
            assertTrue(output.contains("Read"), "Should contain prettified name");
            assertTrue(output.contains("/src/Main.java"), "Should contain extracted file path");
        }

        @Test
        void bashToolWithCommand_shouldShowBoltIcon() {
            String output = renderer.renderToolCallStart("bash", "{\"command\":\"git status\"}");
            assertTrue(output.contains("Bash"), "Should contain prettified name");
            assertTrue(output.contains("git status"), "Should contain extracted command");
        }

        @Test
        void mcpPrefixedTool_shouldStripPrefix() {
            String output = renderer.renderToolCallStart("mcp__kompile__grep", "{\"pattern\":\"TODO\"}");
            assertTrue(output.contains("Grep"), "Should strip MCP prefix and prettify");
            assertTrue(output.contains("TODO"), "Should extract pattern");
        }

        @Test
        void emptyDescription_shouldOmitDescriptionPart() {
            String output = renderer.renderToolCallStart("read", "");
            assertTrue(output.contains("Read"), "Should still show tool name");
            // No trailing description text beyond the icon and name
        }

        @Test
        void nullDescription_shouldOmitDescriptionPart() {
            String output = renderer.renderToolCallStart("read", null);
            assertTrue(output.contains("Read"));
        }

        @Test
        void unknownTool_shouldUseFallbackIcon() {
            String output = renderer.renderToolCallStart("my_custom_tool", "some input");
            assertTrue(output.contains("My Custom Tool"), "Should prettify unknown tool name");
        }

        @Test
        void grepWithPatternAndPath_shouldShowBoth() {
            String output = renderer.renderToolCallStart("grep",
                    "{\"pattern\":\"class.*Test\",\"path\":\"src/test\"}");
            assertTrue(output.contains("Grep"));
            assertTrue(output.contains("class.*Test"));
            assertTrue(output.contains("src/test"));
        }
    }

    // ===================================================================
    // renderToolCallRunning
    // ===================================================================

    @Nested
    class RenderToolCallRunning {

        @Test
        void shouldShowToolNameAndRunning() {
            // ANSI disabled → spinner is "..." instead of braille
            String output = renderer.renderToolCallRunning("bash", 0);
            assertTrue(output.contains("Bash"), "Should show prettified tool name");
            assertTrue(output.contains("running..."), "Should show running state");
        }

        @Test
        void differentSpinnerFrames_shouldAllContainRunning() {
            for (int i = 0; i < 8; i++) {
                String output = renderer.renderToolCallRunning("read", i);
                assertTrue(output.contains("Read"));
                assertTrue(output.contains("running..."));
            }
        }

        @Test
        void mcpPrefixedTool_shouldStripPrefix() {
            String output = renderer.renderToolCallRunning("mcp__kompile__edit", 3);
            assertTrue(output.contains("Edit"));
            assertFalse(output.contains("mcp__"));
        }

        @Test
        void ansiDisabled_shouldUseDotDotDotSpinner() {
            String output = renderer.renderToolCallRunning("bash", 0);
            assertTrue(output.contains("..."), "Should use ... spinner when ANSI disabled");
        }
    }

    // ===================================================================
    // renderToolCallComplete
    // ===================================================================

    @Nested
    class RenderToolCallComplete {

        @Test
        void successResult_shouldShowCheckmark() {
            ToolResult result = ToolResult.success("file read", "contents here");
            String output = renderer.renderToolCallComplete("read", result);
            assertTrue(output.contains("Read"), "Should show prettified name");
            assertTrue(output.contains("file read"), "Should show title");
        }

        @Test
        void successResultWithEmptyTitle_shouldOmitTitle() {
            ToolResult result = ToolResult.success("output data");
            String output = renderer.renderToolCallComplete("bash", result);
            assertTrue(output.contains("Bash"));
        }

        @Test
        void errorResult_shouldShowCross() {
            ToolResult result = ToolResult.error("command failed: exit code 1");
            String output = renderer.renderToolCallComplete("bash", result);
            assertTrue(output.contains("Bash"), "Should show prettified name");
            assertTrue(output.contains("command failed"), "Should show error message");
        }

        @Test
        void successWithMetadata_shouldShowMetadata() {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("lines", 42);
            meta.put("size", "1.2KB");
            ToolResult result = ToolResult.success("done", "content", meta);
            String output = renderer.renderToolCallComplete("read", result);
            assertTrue(output.contains("lines=42"), "Should render metadata");
            assertTrue(output.contains("size=1.2KB"), "Should render metadata");
        }

        @Test
        void metadataWithPathKey_shouldBeSkipped() {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", "/some/path");
            meta.put("count", 5);
            ToolResult result = ToolResult.success("done", "content", meta);
            String output = renderer.renderToolCallComplete("grep", result);
            // "path" metadata key is filtered out by renderMetadata
            assertFalse(output.contains("path=/some/path"), "path key should be filtered");
            assertTrue(output.contains("count=5"));
        }

        @Test
        void mcpPrefixedTool_shouldStripPrefix() {
            ToolResult result = ToolResult.success("found 3 matches");
            String output = renderer.renderToolCallComplete("mcp__kompile__grep", result);
            assertTrue(output.contains("Grep"));
            assertFalse(output.contains("mcp__"));
        }

        @Test
        void bashToolComplete_shouldShowOutputPreview() {
            ToolResult result = ToolResult.success("ls", "file1.txt\nfile2.txt\nfile3.txt");
            String output = renderer.renderToolCallComplete("bash", result);
            assertTrue(output.contains("Bash"));
            // bash is in shouldShowPreview list, so output should appear
            assertTrue(output.contains("file1.txt"), "Bash output should be previewed");
        }

        @Test
        void errorResult_shouldShowErrorPreview() {
            ToolResult result = ToolResult.error("Permission denied: /etc/shadow");
            String output = renderer.renderToolCallComplete("read", result);
            assertTrue(output.contains("Permission denied"), "Error details should be shown");
        }
    }

    // ===================================================================
    // renderToolCallDenied
    // ===================================================================

    @Nested
    class RenderToolCallDenied {

        @Test
        void withReason_shouldShowDeniedAndReason() {
            String output = renderer.renderToolCallDenied("bash", "User rejected execution");
            assertTrue(output.contains("Bash"));
            assertTrue(output.contains("denied"), "Should show denied marker");
            assertTrue(output.contains("User rejected execution"), "Should show reason");
        }

        @Test
        void withNullReason_shouldShowDeniedOnly() {
            String output = renderer.renderToolCallDenied("write", null);
            assertTrue(output.contains("Write"));
            assertTrue(output.contains("denied"));
        }

        @Test
        void mcpPrefixedTool_shouldStripPrefix() {
            String output = renderer.renderToolCallDenied("mcp__kompile__bash", "blocked by policy");
            assertTrue(output.contains("Bash"));
            assertFalse(output.contains("mcp__"));
            assertTrue(output.contains("blocked by policy"));
        }
    }

    // ===================================================================
    // renderSubagentToolCall
    // ===================================================================

    @Nested
    class RenderSubagentToolCall {

        @Test
        void successToolCall_shouldShowCheckmark() {
            String output = renderer.renderSubagentToolCall("read", false);
            assertTrue(output.contains("Read"));
        }

        @Test
        void errorToolCall_shouldShowCross() {
            String output = renderer.renderSubagentToolCall("bash", true);
            assertTrue(output.contains("Bash"));
        }

        @Test
        void mcpPrefixedTool_shouldStripPrefix() {
            String output = renderer.renderSubagentToolCall("mcp__kompile__grep", false);
            assertTrue(output.contains("Grep"));
            assertFalse(output.contains("mcp__"));
        }
    }

    // ===================================================================
    // renderSubagentStart / Complete / Error
    // ===================================================================

    @Nested
    class SubagentLifecycle {

        @Test
        void start_shouldShowAgentTypeAndDescription() {
            String output = renderer.renderSubagentStart("code-review", "Reviewing PR #42");
            assertTrue(output.contains("Subagent: code-review"));
            assertTrue(output.contains("Reviewing PR #42"));
        }

        @Test
        void startWithNullDescription_shouldOmitDescription() {
            String output = renderer.renderSubagentStart("search", null);
            assertTrue(output.contains("Subagent: search"));
        }

        @Test
        void complete_withDuration_shouldShowTiming() {
            String output = renderer.renderSubagentComplete("code-review", 5200L);
            assertTrue(output.contains("Subagent complete"));
            assertTrue(output.contains("5200ms"));
        }

        @Test
        void complete_zeroDuration_shouldOmitTiming() {
            String output = renderer.renderSubagentComplete("search", 0L);
            assertTrue(output.contains("Subagent complete"));
            assertFalse(output.contains("0ms"), "Zero duration should be suppressed");
        }

        @Test
        void error_shouldShowErrorMessage() {
            String output = renderer.renderSubagentError("code-review", "Timeout after 30s");
            assertTrue(output.contains("Subagent failed"));
            assertTrue(output.contains("Timeout after 30s"));
        }
    }

    // ===================================================================
    // renderContextGroup
    // ===================================================================

    @Nested
    class RenderContextGroup {

        @Test
        void multipleTools_shouldAggregate() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("read", 5);
            counts.put("grep", 3);
            counts.put("glob", 1);
            String output = renderer.renderContextGroup(counts);
            assertTrue(output.contains("Gathered context"));
            assertTrue(output.contains("9"), "Total should be 9 calls");
        }

        @Test
        void singleTool_shouldShowSingularCall() {
            Map<String, Integer> counts = Map.of("read", 1);
            String output = renderer.renderContextGroup(counts);
            assertTrue(output.contains("Gathered context"));
            assertTrue(output.contains("1"));
        }

        @Test
        void emptyMap_shouldReturnEmpty() {
            String output = renderer.renderContextGroup(Map.of());
            assertEquals("", output);
        }

        @Test
        void nullMap_shouldReturnEmpty() {
            String output = renderer.renderContextGroup(null);
            assertEquals("", output);
        }

        @Test
        void mcpPrefixedNames_shouldBeStripped() {
            Map<String, Integer> counts = Map.of("mcp__kompile__read", 3);
            String output = renderer.renderContextGroup(counts);
            assertTrue(output.contains("Read"), "MCP prefix should be stripped in display");
        }

        @Test
        void moreThanFourBuckets_shouldShowOverflow() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("read", 5);
            counts.put("grep", 3);
            counts.put("glob", 2);
            counts.put("bash", 1);
            counts.put("write", 4);
            String output = renderer.renderContextGroup(counts);
            assertTrue(output.contains("more"), "Should show overflow for >4 buckets");
        }

        @Test
        void zeroCountEntries_shouldBeIgnored() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("read", 3);
            counts.put("bash", 0);
            String output = renderer.renderContextGroup(counts);
            assertTrue(output.contains("3"), "Only non-zero counts");
        }
    }

    // ===================================================================
    // truncatePreview (static utility)
    // ===================================================================

    @Nested
    class TruncatePreview {

        @Test
        void shortText_shouldReturnAsIs() {
            assertEquals("hello", TerminalRenderer.truncatePreview("hello", 80));
        }

        @Test
        void longText_shouldTruncateWithEllipsis() {
            String longText = "a".repeat(100);
            String result = TerminalRenderer.truncatePreview(longText, 20);
            assertEquals(20, result.length());
            assertTrue(result.endsWith("..."));
        }

        @Test
        void exactLength_shouldReturnAsIs() {
            String text = "12345";
            assertEquals("12345", TerminalRenderer.truncatePreview(text, 5));
        }

        @Test
        void nullInput_shouldReturnEmpty() {
            assertEquals("", TerminalRenderer.truncatePreview(null, 80));
        }

        @Test
        void multilineText_shouldCollapseToSingleLine() {
            String text = "line1\nline2\nline3";
            String result = TerminalRenderer.truncatePreview(text, 80);
            assertFalse(result.contains("\n"), "Newlines should be replaced");
            assertTrue(result.contains("line1 line2 line3"));
        }

        @Test
        void carriageReturns_shouldBeReplaced() {
            String text = "line1\r\nline2";
            String result = TerminalRenderer.truncatePreview(text, 80);
            assertFalse(result.contains("\r"));
        }
    }

    // ===================================================================
    // Miscellaneous rendering
    // ===================================================================

    @Nested
    class MiscRendering {

        @Test
        void renderAgentTurnStart_shouldShowStepCounter() {
            String output = renderer.renderAgentTurnStart(3, 10);
            assertTrue(output.contains("step 3/10"));
        }

        @Test
        void renderCompactionNotice_shouldShowTokenCounts() {
            String output = renderer.renderCompactionNotice(50000, 20000);
            assertTrue(output.contains("50000"));
            assertTrue(output.contains("20000"));
            assertTrue(output.contains("context compacted"));
        }

        @Test
        void renderMaxStepsWarning_shouldShowLimit() {
            String output = renderer.renderMaxStepsWarning(25);
            assertTrue(output.contains("25"));
            assertTrue(output.contains("maximum steps"));
        }

        @Test
        void ansiHelpers_withAnsiDisabled_shouldReturnPlainText() {
            assertEquals("hello", renderer.bold("hello"));
            assertEquals("hello", renderer.dim("hello"));
            assertEquals("hello", renderer.red("hello"));
            assertEquals("hello", renderer.green("hello"));
            assertEquals("hello", renderer.yellow("hello"));
            assertEquals("hello", renderer.blue("hello"));
            assertEquals("hello", renderer.magenta("hello"));
            assertEquals("hello", renderer.cyan("hello"));
        }

        @Test
        void ansiHelpers_withAnsiEnabled_shouldWrapWithEscapeCodes() {
            TerminalRenderer ansiRenderer = new TerminalRenderer(true);
            String bold = ansiRenderer.bold("test");
            assertTrue(bold.contains("\033["), "Should contain ANSI escape");
            assertTrue(bold.contains("test"));
            assertTrue(bold.contains("\033[0m"), "Should end with reset");
        }

        @Test
        void isAnsiEnabled_shouldReflectConstructorArg() {
            assertFalse(renderer.isAnsiEnabled());
            TerminalRenderer ansiRenderer = new TerminalRenderer(true);
            assertTrue(ansiRenderer.isAnsiEnabled());
        }
    }
}

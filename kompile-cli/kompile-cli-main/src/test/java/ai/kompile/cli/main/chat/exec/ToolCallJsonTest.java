package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stream-json form of a completed tool call must be the terminal's row and bounded detail
 * as data, with the language the terminal would highlight each run in.
 */
class ToolCallJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readCarriesTheTerminalRowAndOneHighlightedRun() {
        String input = "{\"file_path\":\"src/main/java/App.java\"}";
        ToolResult result = ToolResult.success("src/main/java/App.java",
                "package demo;\n/* a block\n   comment */\nclass App {}\n");

        ObjectNode detail = ToolCallJson.detail(mapper, "read", input, result);

        assertEquals("Read", detail.path("displayName").asText());
        assertRow(TerminalRenderer.toolRow("read", input, result, false), detail);
        JsonNode section = only(detail.path("sections"));
        assertEquals("content", section.path("label").asText());
        assertFalse(section.has("diff"));
        assertFalse(section.has("note"));
        assertRun(only(section.path("runs")), "package demo;\n/* a block\n   comment */\nclass App {}",
                "src/main/java/App.java", "clike");
    }

    @Test
    void editShowsItsDiffUnstyledThenTheResult() {
        String input = "{\"file_path\":\"a.py\",\"old_string\":\"x = 1\",\"new_string\":\"x = 2\"}";

        JsonNode sections = ToolCallJson.detail(mapper, "edit", input,
                ToolResult.success("a.py", "Applied 1 edit")).path("sections");

        assertEquals(2, sections.size());
        assertEquals("diff", sections.path(0).path("label").asText());
        assertTrue(sections.path(0).path("diff").asBoolean());
        // Diff lines are colored by their prefix, so they carry no language.
        assertRun(only(sections.path(0).path("runs")), "  a.py\n- x = 1\n+ x = 2", null, null);
        assertEquals("result", sections.path(1).path("label").asText());
        assertFalse(sections.path(1).has("diff"));
        assertRun(only(sections.path(1).path("runs")), "Applied 1 edit", null, null);
    }

    @Test
    void writeShowsTheContentInTheFilesLanguage() {
        String input = "{\"file_path\":\"tool.py\",\"content\":\"def f():\\n    return 1\\n\"}";

        JsonNode sections = ToolCallJson.detail(mapper, "write", input,
                ToolResult.success("tool.py", "Wrote 2 lines")).path("sections");

        assertEquals(2, sections.size());
        assertEquals("content", sections.path(0).path("label").asText());
        assertRun(only(sections.path(0).path("runs")), "def f():\n    return 1", "tool.py", "python");
        assertEquals("output", sections.path(1).path("label").asText());
        assertRun(only(sections.path(1).path("runs")), "Wrote 2 lines", null, null);
    }

    @Test
    void searchHitsTakeTheirLanguageFromEachLine() {
        // A directory path names no language, so each hit is styled from its own file.
        String input = "{\"pattern\":\"foo\",\"path\":\"src\"}";
        ToolResult result = ToolResult.success("grep: foo",
                "src/A.java:3: foo();\nsrc/A.java:9: foo(\"open);\nsrc/b.py:7: foo()\nsummary: 3 matches");

        JsonNode section = only(ToolCallJson.detail(mapper, "grep", input, result).path("sections"));

        assertEquals("content", section.path("label").asText());
        JsonNode runs = section.path("runs");
        assertEquals(4, runs.size(), "hits never share a run, so an open quote cannot leak: " + runs);
        assertRun(runs.path(0), "src/A.java:3: foo();", "src/A.java", "clike");
        assertRun(runs.path(1), "src/A.java:9: foo(\"open);", "src/A.java", "clike");
        assertRun(runs.path(2), "src/b.py:7: foo()", "src/b.py", "python");
        assertRun(runs.path(3), "summary: 3 matches", null, null);
    }

    @Test
    void batchReadStylesEachFileUnderItsHeader() {
        String output = "== src/App.java (2 lines)\nclass App {\n}\n== run.sh (1 lines)\necho hi";

        JsonNode runs = only(ToolCallJson.detail(mapper, "read_batch",
                "{\"files\":[\"src/App.java\",\"run.sh\"]}", ToolResult.success(output))
                .path("sections")).path("runs");

        assertEquals(4, runs.size(), runs.toString());
        assertRun(runs.path(0), "== src/App.java (2 lines)", null, null);
        assertRun(runs.path(1), "class App {\n}", "src/App.java", "clike");
        assertRun(runs.path(2), "== run.sh (1 lines)", null, null);
        assertRun(runs.path(3), "echo hi", "run.sh", "hash");
    }

    @Test
    void plainOutputIsOneRunCutAtTheTerminalBounds() {
        JsonNode tall = only(ToolCallJson.detail(mapper, "bash", "{\"command\":\"seq 1 100\"}",
                ToolResult.success(numbers(100))).path("sections"));

        assertEquals(TerminalRenderer.DETAIL_TRUNCATED_NOTE, tall.path("note").asText());
        assertRun(only(tall.path("runs")), numbers(48), null, null);

        JsonNode wide = only(ToolCallJson.detail(mapper, "bash", "{\"command\":\"cat wide.log\"}",
                ToolResult.success("y".repeat(3_000))).path("sections"));

        assertEquals(TerminalRenderer.DETAIL_TRUNCATED_NOTE, wide.path("note").asText());
        assertEquals("y".repeat(2_400), only(wide.path("runs")).path("text").asText());
    }

    @Test
    void rowAndBodyArePrintable() {
        ToolResult colored = ToolResult.success("\033[31mred\033[0m\007 alert\n\033[2Jnext");

        ObjectNode detail = ToolCallJson.detail(mapper, "bash", "{\"command\":\"make\"}", colored);

        assertEquals("red alert (+1 lines)", detail.path("preview").asText());
        assertRun(only(only(detail.path("sections")).path("runs")), "red alert\nnext", null, null);
    }

    @Test
    void failedCallCarriesItsErrorAndNoTitle() {
        ObjectNode detail = ToolCallJson.detail(mapper, "bash", "{\"command\":\"false\"}",
                ToolResult.error("boom"));

        assertEquals("boom", detail.path("error").asText());
        assertFalse(detail.has("title"), "the error factory's placeholder title is not shown");
        assertFalse(detail.has("preview"));
    }

    @Test
    void noResultMeansNoDetail() {
        assertEquals(0, ToolCallJson.detail(mapper, "read", "{}", null).size());
    }

    private static String numbers(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(Integer::toString)
                .collect(Collectors.joining("\n"));
    }

    private static JsonNode only(JsonNode array) {
        assertEquals(1, array.size(), array.toString());
        return array.path(0);
    }

    private static void assertRun(JsonNode run, String text, String file, String family) {
        assertEquals(text, run.path("text").asText());
        assertEquals(file, run.has("file") ? run.path("file").asText() : null, run.toString());
        assertEquals(family, run.has("family") ? run.path("family").asText() : null, run.toString());
    }

    private static void assertRow(TerminalRenderer.ToolRow row, JsonNode detail) {
        assertEquals(row.displayName(), detail.path("displayName").asText());
        assertText(row.action(), detail, "action");
        assertText(row.title(), detail, "title");
        assertText(row.metadata(), detail, "metadata");
        assertText(row.preview(), detail, "preview");
        assertText(row.errorPreview(), detail, "error");
    }

    private static void assertText(String expected, JsonNode detail, String field) {
        if (expected == null || expected.isBlank()) {
            assertFalse(detail.has(field), field + " in " + detail);
        } else {
            assertEquals(expected, detail.path(field).asText(), field);
        }
    }
}

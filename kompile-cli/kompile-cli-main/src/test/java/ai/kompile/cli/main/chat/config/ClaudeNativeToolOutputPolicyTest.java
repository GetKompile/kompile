package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.FetchResultTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolResultReferenceCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ClaudeNativeToolOutputPolicyTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final ToolResultReferenceCache cache = new ToolResultReferenceCache();
    private final ClaudeNativeToolOutputPolicy policy = new ClaudeNativeToolOutputPolicy(mapper, cache);

    @Test
    void largeReadRetainsExactTextAcrossCacheInstancesAndDoesNotSplitUnicode() throws Exception {
        String text = "x".repeat(999) + "😀" + "z".repeat(20_000) + "TAIL_MARKER";
        ObjectNode event = read(text);
        JsonNode before = event.deepCopy();
        ObjectNode projected = policy.project(event, "native-output-test");
        JsonNode specific = projected.path("hookSpecificOutput");
        JsonNode output = specific.path("updatedToolOutput");
        String excerpt = output.path("file").path("content").asText();
        assertEquals(999, excerpt.length());
        assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1)));
        assertEquals(42, output.path("file").path("startLine").asInt());
        assertEquals(900, output.path("file").path("totalLines").asInt());
        assertEquals(1, output.path("file").path("numLines").asInt());
        assertTrue(output.path("file").path("truncatedByTokenCap").asBoolean());
        assertFalse(projected.toString().contains("TAIL_MARKER"));
        String handle = handle(specific.path("additionalContext").asText());
        assertTrue(handle.startsWith("d1.native-output-test."), handle);
        ToolResultReferenceCache restarted = new ToolResultReferenceCache();
        assertEquals(text, fetchLongLine(restarted, handle));
        assertEquals(before, event, "native source output must not be mutated");
        assertFalse(specific.has("permissionDecision"));
    }

    @Test
    void ordinaryTextReadIsUnchanged() {
        ObjectNode event = read("small\nread\n");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
    }

    @Test
    void bashSharesThresholdAcrossStreamsAndKeepsFailureAndBackgroundMetadata() {
        ObjectNode event = event("Bash");
        ObjectNode raw = event.putObject("tool_response");
        raw.put("stdout", "o".repeat(9_000));
        raw.put("stderr", "error\n".repeat(2_000));
        raw.put("interrupted", true).put("isImage", false).put("exitCode", 7);
        raw.put("backgroundTaskId", "task-1").put("returnCodeInterpretation", "failed");
        JsonNode projected = policy.project(event, "native-output-test").path("hookSpecificOutput");
        JsonNode output = projected.path("updatedToolOutput");
        assertTrue(output.path("stdout").asText().length() <= 1_000);
        assertTrue(output.path("stderr").asText().length() <= 1_000);
        assertTrue(output.path("interrupted").asBoolean());
        assertEquals(7, output.path("exitCode").asInt());
        assertEquals("failed", output.path("returnCodeInterpretation").asText());
        assertEquals("task-1", output.path("backgroundTaskId").asText());
        assertEquals(2, projected.path("additionalContext").asText().split("retained as ref:", -1).length - 1);
    }

    @Test
    void grepBoundsContentAndNamesWithoutInventingTruncatedPaths() throws Exception {
        ObjectNode event = event("Grep");
        ObjectNode raw = event.putObject("tool_response");
        raw.put("mode", "content").put("numFiles", 50).put("numLines", 4_000);
        raw.put("content", "match\n".repeat(4_000));
        for (int i = 0; i < 50; i++) raw.withArray("filenames").add(
                i == 0 ? "/project/line\nbreak" : "/project/" + "x".repeat(400) + i);
        JsonNode result = policy.project(event, "native-output-test").path("hookSpecificOutput");
        JsonNode output = result.path("updatedToolOutput");
        assertTrue(output.path("content").asText().length() <= 1_000);
        assertTrue(output.path("filenames").size() < 50);
        assertEquals(50, output.path("totalFiles").asInt());
        assertEquals(output.path("filenames").size(), output.path("numFiles").asInt());
        for (int i = 0; i < output.path("filenames").size(); i++) {
            assertEquals(raw.path("filenames").get(i), output.path("filenames").get(i));
        }
        String note = result.path("additionalContext").asText();
        Matcher refs = Pattern.compile("retained as ref:([^ ]+)\\.").matcher(note);
        assertTrue(refs.find());
        assertTrue(refs.find());
        assertEquals(raw.path("filenames"), mapper.readTree(fetchLongLine(cache, refs.group(1))));
        assertTrue(note.contains("fetch_result"));
    }

    @Test
    void imagesPdfsStructuredResultsUnknownToolsAndFailureEventsAreNotRewritten() {
        ObjectNode event = event("Read");
        event.putObject("tool_response").put("type", "image").putObject("file")
                .put("base64", "a".repeat(20_000)).put("type", "image/png");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
        ((ObjectNode) event.path("tool_response")).put("type", "pdf");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
        event = read("x".repeat(20_000));
        event.put("tool_name", "mcp__kompile__read");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
        event.put("tool_name", "Edit");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
        event.put("tool_name", "Read").put("hook_event_name", "PostToolUseFailure");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
        event = event("Bash");
        event.putObject("tool_response").put("stdout", "x".repeat(20_000))
                .put("stderr", "").put("interrupted", false).put("isImage", true);
        assertTrue(policy.project(event, "native-output-test").isEmpty());
    }

    @Test
    void malformedNativeSchemaIsNotReplacedWithAnInvalidOutput() {
        ObjectNode event = read("x".repeat(20_000));
        ((ObjectNode) event.path("tool_response").path("file")).remove("startLine");
        assertTrue(policy.project(event, "native-output-test").isEmpty());
    }

    @Test
    void failedDurablePublicationRetainsTheOriginalRatherThanIssuingABogusHandle() {
        ToolResultReferenceCache failing = new ToolResultReferenceCache(1, 60_000) {
            @Override
            public ToolResult storeAndSummarize(String owner, String tool, String title, String text,
                                                Map<String, Object> metadata) {
                return new ToolResult(title, text, Map.of("cached", false, "durable", false), false);
            }
        };
        ObjectNode event = read("x".repeat(20_000));
        JsonNode before = event.deepCopy();
        JsonNode specific = new ClaudeNativeToolOutputPolicy(mapper, failing)
                .project(event, "native-output-test").path("hookSpecificOutput");
        assertFalse(specific.has("updatedToolOutput"));
        assertFalse(specific.toString().contains("ref:"));
        assertTrue(specific.path("additionalContext").asText().contains("original result is retained"));
        assertEquals(before, event);
    }

    @Test
    void secondFieldPublicationFailureDoesNotLeakAPartialReplacement() {
        ToolResultReferenceCache failing = new ToolResultReferenceCache() {
            private int publications;
            @Override
            public ToolResult storeAndSummarize(String owner, String tool, String title, String text,
                                                Map<String, Object> metadata) {
                if (++publications == 2) throw new IllegalStateException("second publication failed");
                return super.storeAndSummarize(owner, tool, title, text, metadata);
            }
        };
        ObjectNode event = event("Bash");
        event.putObject("tool_response").put("stdout", "o".repeat(10_000))
                .put("stderr", "e".repeat(10_000)).put("interrupted", false);
        JsonNode before = event.deepCopy();
        JsonNode result = new ClaudeNativeToolOutputPolicy(mapper, failing).project(event, "native-output-test");
        assertFalse(result.path("hookSpecificOutput").has("updatedToolOutput"));
        assertFalse(result.toString().contains("ref:"));
        assertEquals(before, event);
    }

    private ObjectNode event(String tool) {
        return mapper.createObjectNode().put("hook_event_name", "PostToolUse").put("tool_name", tool)
                .put("tool_use_id", "native-call").put("session_id", "vendor-not-owner");
    }

    private ObjectNode read(String content) {
        ObjectNode event = event("Read");
        event.putObject("tool_response").put("type", "text").putObject("file")
                .put("filePath", "/project/source.txt").put("content", content)
                .put("numLines", content.lines().count()).put("startLine", 42).put("totalLines", 900);
        return event;
    }

    private String fetchLongLine(ToolResultReferenceCache source, String handle) throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext("native-output-test", null, permissions,
                Path.of(System.getProperty("user.home")), new ToolRegistry(mapper));
        FetchResultTool fetch = new FetchResultTool(source);
        ObjectNode args = mapper.createObjectNode().put("result_id", handle).put("offset", 1).put("limit", 1);
        StringBuilder full = new StringBuilder();
        int character = 0;
        for (int page = 0; page < 10; page++) {
            ToolResult result = fetch.execute(args, context);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().length() <= ToolResultReferenceCache.MAX_FETCH_CHARS);
            if (!Boolean.TRUE.equals(result.getMetadata().get("truncated"))) {
                assertTrue(result.getOutput().endsWith("\n"));
                return full.append(result.getOutput(), 0, result.getOutput().length() - 1).toString();
            }
            int next = ((Number) result.getMetadata().get("nextCharacterOffset")).intValue();
            assertTrue(next > character, "long-line pagination must advance");
            full.append(result.getOutput(), 0, next - character);
            args.put("offset", ((Number) result.getMetadata().get("nextOffset")).intValue());
            args.put("character_offset", next);
            character = next;
        }
        fail("long-line fixture did not finish within ten bounded pages");
        return "";
    }

    private String handle(String note) {
        Matcher matcher = Pattern.compile("retained as ref:([^ ]+)\\.").matcher(note);
        assertTrue(matcher.find(), note);
        return matcher.group(1);
    }
}

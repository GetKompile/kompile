package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioPostFeedbackToolTest {

    @TempDir
    Path tempDir;

    @Test
    void backendErrorIsNotRetriedOrReportedAsInvalidJson() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                throw new AssertionError("post feedback must use the structured verdict path");
            }

            @Override
            public String generateJson(
                    String userPrompt, String systemPrompt, JsonSchema outputSchema) {
                calls.incrementAndGet();
                return "[Error: all judge backends are in cooldown]";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        StdioPostFeedbackTool tool = new StdioPostFeedbackTool(
                new ObjectMapper(), tempDir, (config, mapper) -> backend);

        ToolResult result = tool.execute(arguments());

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("backend unavailable"));
        assertFalse(result.getOutput().contains("valid JSON"));
        assertEquals(1, calls.get(), "transport errors must never enter format repair");
    }

    @Test
    void malformedVerdictGetsOneStructuredRepair() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JudgeBackend.JsonSchema> schema = new AtomicReference<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                throw new AssertionError("post feedback must use the structured verdict path");
            }

            @Override
            public String generateJson(
                    String userPrompt, String systemPrompt, JsonSchema outputSchema) {
                schema.set(outputSchema);
                if (calls.getAndIncrement() == 0) {
                    return "{\"status\": context}";
                }
                return "{\"status\":\"PASS\",\"score\":1.0,\"findings\":[],"
                        + "\"evidence\":[\"focused tests passed\"],\"next_actions\":[],"
                        + "\"correction_prompt\":\"\",\"reasoning\":\"verified\"}";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describe() {
                return "structured-post-feedback-test";
            }
        };
        StdioPostFeedbackTool tool = new StdioPostFeedbackTool(
                new ObjectMapper(), tempDir, (config, mapper) -> backend);

        ToolResult result = tool.execute(arguments());

        assertFalse(result.isError());
        assertEquals("pass", result.getMetadata().get("status"));
        assertEquals(2, calls.get());
        assertEquals("kompile_post_feedback", schema.get().name());
        assertTrue(schema.get().strict());
        assertEquals("object", schema.get().schema().path("type").asText());
    }

    private static Map<String, Object> arguments() {
        return Map.of(
                "original_prompt", "Implement the parser fix",
                "agent_output", "Implemented and tested the parser fix",
                "include_diff", false,
                "include_mcp_log", false,
                "run_tests", false);
    }
}

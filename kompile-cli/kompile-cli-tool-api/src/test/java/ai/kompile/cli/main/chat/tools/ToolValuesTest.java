package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolValuesTest {
    @Test
    void constructorsAndFactoriesPreserveTitlesOutputsErrorsAndMetadata() {
        var metadata = new HashMap<String, Object>();
        metadata.put(ToolResult.CHART_METADATA, "chart-data");
        var result = new ToolResult("title", "body", metadata);
        assertEquals("title", result.getTitle());
        assertEquals("body", result.getOutput());
        assertEquals("title: body", result.toString());
        assertSame(metadata, result.getMetadata());
        assertFalse(result.isError());
        assertEquals("chart-data", result.getMetadata().get("chart"));
        metadata.put("late", 42);
        assertEquals(42, result.getMetadata().get("late"));
        assertTrue(new ToolResult("title", "body", metadata, true).isError());

        var empty = new ToolResult(null, null, null);
        assertNull(empty.getTitle());
        assertNull(empty.getOutput());
        assertNull(empty.toString());
        assertEquals(Map.of(), empty.getMetadata());
        assertFalse(empty.isError());
        assertEquals("body", new ToolResult("", "body", null).toString());
        assertEquals("body", new ToolResult(null, "body", null).toString());

        var simple = ToolResult.success("body");
        assertEquals("", simple.getTitle());
        assertEquals("body", simple.getOutput());
        assertEquals(Map.of(), simple.getMetadata());
        assertFalse(simple.isError());
        var titled = ToolResult.success("title", "body");
        assertEquals("title: body", titled.toString());
        assertFalse(titled.isError());
        assertSame(metadata, ToolResult.success("title", "body", metadata).getMetadata());
        var error = ToolResult.error("failure");
        assertEquals("error", error.getTitle());
        assertEquals("failure", error.getOutput());
        assertEquals(Map.of(), error.getMetadata());
        assertTrue(error.isError());
    }

    @Test
    void streamedOutputUsesOnlyBooleanTrueOrBooleanParsedStrings() {
        assertEquals("outputStreamed", ToolResult.OUTPUT_STREAMED_METADATA);
        assertEquals("chart", ToolResult.CHART_METADATA);
        assertFalse(ToolResult.success("body").isOutputStreamed());
        for (Object value : new Object[]{true, "true", "TRUE", "TrUe"}) {
            assertTrue(new ToolResult("", "", Map.of("outputStreamed", value)).isOutputStreamed());
        }
        for (Object value : new Object[]{false, "false", " true ", "yes", "1", 1}) {
            assertFalse(new ToolResult("", "", Map.of("outputStreamed", value)).isOutputStreamed());
        }
        var metadata = new HashMap<String, Object>();
        metadata.put("outputStreamed", null);
        assertFalse(new ToolResult("", "", metadata).isOutputStreamed());
    }

    @Test
    void exceptionConstructorsPreserveMessageCauseAndPermissionFlag() {
        var plain = new ToolExecutionException("failure");
        assertEquals("failure", plain.getMessage());
        assertNull(plain.getCause());
        assertFalse(plain.isPermissionDenied());
        var cause = new IllegalStateException("cause");
        var chained = new ToolExecutionException("wrapped", cause);
        assertEquals("wrapped", chained.getMessage());
        assertSame(cause, chained.getCause());
        assertFalse(chained.isPermissionDenied());
        var denied = new ToolExecutionException("denied", true);
        assertEquals("denied", denied.getMessage());
        assertNull(denied.getCause());
        assertTrue(denied.isPermissionDenied());
        assertFalse(new ToolExecutionException(null, false).isPermissionDenied());
        assertNull(new ToolExecutionException(null).getMessage());
    }
}

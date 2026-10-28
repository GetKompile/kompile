package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubagentTelemetryTest {
    @Test
    void measuredDeltasAndStreamingEstimatesRemainSeparate() {
        var metrics = new SubagentTelemetry();
        assertFalse(metrics.snapshot().containsKey("input_tokens"), "unknown is not zero");
        metrics.put("model", "child-model");
        metrics.put("vendor", "vendor");
        metrics.usage(100, 0, 20, 5);
        metrics.stream("123456789");
        var live = metrics.snapshot();
        assertEquals("3", live.get("stream_output_tokens"));
        assertEquals("0", live.get("output_tokens"));
        assertThrows(UnsupportedOperationException.class, () -> live.put("model", "wrong"));
        metrics.usage(0, 7, 0, 0);
        metrics.tool();
        assertEquals("0", metrics.snapshot().get("stream_output_tokens"));
        assertEquals("7", metrics.snapshot().get("output_tokens"));
        assertEquals("100", metrics.snapshot().get("input_tokens"));
        assertEquals("20", metrics.snapshot().get("cache_read_tokens"));
        assertEquals("5", metrics.snapshot().get("cache_creation_tokens"));
        assertEquals("1", metrics.snapshot().get("tool_uses"));
        metrics.beginRequest();
        metrics.usage(50, 2, 0, 0);
        assertEquals("150", metrics.snapshot().get("input_tokens"));
        assertEquals("9", metrics.snapshot().get("output_tokens"));
        assertEquals("3", live.get("stream_output_tokens"), "old snapshots remain stable");
    }
}

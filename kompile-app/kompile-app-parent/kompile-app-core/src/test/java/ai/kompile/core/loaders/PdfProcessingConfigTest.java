package ai.kompile.core.loaders;

import ai.kompile.ocr.VlmOutputFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfProcessingConfigTest {
    @Test
    void vlmDefaultsUsePackageProtocolAndModelContext() {
        PdfProcessingConfig config = PdfProcessingConfig.vlm("model");

        assertEquals(VlmOutputFormat.RAW, config.getVlmOutputFormat());
        assertEquals(0, config.getMaxNewTokens());
        assertEquals(0, config.getMaxKvLen());
        assertEquals(16L * 1024L * 1024L, config.getMaxResponseBytes());
        assertFalse(config.isAdaptiveRegionFallbackEnabled());
        assertEquals(3584, config.getAdaptiveFullPageMaxNewTokens());
        assertEquals(1024, config.getAdaptiveRegionMaxNewTokens());
        assertEquals(1.1, config.getAdaptiveRegionRepetitionPenalty(), 1e-9);
        assertNull(config.getVlmOutputProtocol());
    }

    @Test
    void mapRoundTripPreservesProtocolAndGenerationControls() {
        PdfProcessingConfig config = PdfProcessingConfig.fromMap(Map.ofEntries(
                Map.entry("processingMode", "VLM"),
                Map.entry("vlmModelId", "qwen-vl"),
                Map.entry("vlmOutputFormat", "RAW"),
                Map.entry("vlmOutputProtocol", "chat-text"),
                Map.entry("vlmTask", "formatted_ocr"),
                Map.entry("vlmPromptOverride", "Read this page"),
                Map.entry("maxNewTokens", 0),
                Map.entry("maxResponseBytes", 4096),
                Map.entry("adaptiveRegionFallbackEnabled", true),
                Map.entry("adaptiveFullPageMaxNewTokens", 2048),
                Map.entry("adaptiveRegionMaxNewTokens", 768),
                Map.entry("adaptiveRegionRepetitionPenalty", 1.2),
                Map.entry("topK", 17),
                Map.entry("samplingPreset", "precise"),
                Map.entry("repetitionPenalty", 1.15),
                Map.entry("maxKvLen", 8192)));

        assertTrue(config.isUseVlm());
        assertEquals("chat-text", config.getVlmOutputProtocol());
        assertEquals("formatted_ocr", config.getVlmTask());
        assertEquals("Read this page", config.getVlmPromptOverride());
        assertEquals(4096L, config.getMaxResponseBytes());
        assertTrue(config.isAdaptiveRegionFallbackEnabled());
        assertEquals(2048, config.getAdaptiveFullPageMaxNewTokens());
        assertEquals(768, config.getAdaptiveRegionMaxNewTokens());
        assertEquals(1.2, config.getAdaptiveRegionRepetitionPenalty(), 1e-9);
        assertEquals(17, config.getTopK());
        assertEquals("precise", config.getSamplingPreset());
        assertEquals(1.15, config.getRepetitionPenalty(), 1e-9);
        assertEquals(8192, config.getMaxKvLen());
        assertEquals("chat-text", config.toMap().get("vlmOutputProtocol"));
        assertEquals("formatted_ocr", config.toMap().get("vlmTask"));
        assertEquals(768, config.toMap().get("adaptiveRegionMaxNewTokens"));
        assertEquals(17, config.toMap().get("topK"));
        assertEquals("precise", config.toMap().get("samplingPreset"));
    }
}

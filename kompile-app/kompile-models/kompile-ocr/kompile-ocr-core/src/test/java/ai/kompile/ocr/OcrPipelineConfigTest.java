package ai.kompile.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OcrPipelineConfigTest {
    @Test
    void vlmDefaultsGenerateToModelEosWithIndependentResponseGuard() {
        OcrPipelineConfig config = OcrPipelineConfig.vlmMarkdown("smoldocling-256m");

        assertEquals(0, config.getMaxNewTokens());
        assertEquals(0, config.getMaxKvLen());
        assertEquals(16L * 1024L * 1024L, config.getMaxResponseBytes());
        assertFalse(config.isAdaptiveRegionFallbackEnabled());
        assertEquals(3584, config.getAdaptiveFullPageMaxNewTokens());
        assertEquals(1024, config.getAdaptiveRegionMaxNewTokens());
        assertEquals(1.1, config.getAdaptiveRegionRepetitionPenalty(), 1e-9);
        assertEquals(64, config.getAdaptiveNativeRepetitionMaxPeriod());
        assertEquals(4, config.getAdaptiveNativeRepetitionMaxRepeats());
    }
}

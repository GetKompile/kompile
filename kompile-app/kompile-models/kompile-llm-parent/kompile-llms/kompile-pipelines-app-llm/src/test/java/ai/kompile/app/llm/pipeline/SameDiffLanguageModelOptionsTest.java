package ai.kompile.app.llm.pipeline;

import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SameDiffLanguageModelOptionsTest {

    @Test
    void propagatesModelSamplingProfileIncludingRepetitionPenaltyAndSeed() {
        SamplingConfig sampling = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(
                        "doSample", true,
                        "topP", 0.95,
                        "repetitionPenalty", 1.05,
                        "seed", 1234L),
                512,
                0.1,
                50);

        assertTrue(sampling.isDoSample());
        assertEquals(0.1, sampling.getTemperature());
        assertEquals(50, sampling.getTopK());
        assertEquals(0.95, sampling.getTopP());
        assertEquals(1.05, sampling.getRepetitionPenalty());
        assertEquals(Long.valueOf(1234L), sampling.getSeed());
        assertEquals(512, sampling.getMaxNewTokens());
    }

    @Test
    void resolvesStructuredToolFormatAliases() {
        assertEquals(ChatTemplate.ToolDefinitionFormat.STANDARD,
                SameDiffLanguageModelImpl.toolDefinitionFormatOpt(Map.of()));
        assertEquals(ChatTemplate.ToolDefinitionFormat.STANDARD,
                SameDiffLanguageModelImpl.toolDefinitionFormatOpt(
                        Map.of("toolDefinitionFormat", "openai-function")));
        assertEquals(ChatTemplate.ToolDefinitionFormat.FLAT,
                SameDiffLanguageModelImpl.toolDefinitionFormatOpt(
                        Map.of("toolDefinitionFormat", "flat")));

        assertNull(SameDiffLanguageModelImpl.toolCallFormatOpt(Map.of()),
                "the imported model must own the default tool-call protocol");
        assertNull(SameDiffLanguageModelImpl.toolCallFormatOpt(
                Map.of("toolCallFormat", "auto")));
        assertEquals(ChatTemplate.ToolCallFormat.JSON,
                SameDiffLanguageModelImpl.toolCallFormatOpt(
                        Map.of("toolCallFormat", "openai-json")));
        assertEquals(ChatTemplate.ToolCallFormat.NATIVE,
                SameDiffLanguageModelImpl.toolCallFormatOpt(
                        Map.of("toolCallFormat", "model-native")));
    }

    @Test
    void rejectsUnknownStructuredToolFormats() {
        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.toolDefinitionFormatOpt(
                        Map.of("toolDefinitionFormat", "not-a-format")));
        assertThrows(IllegalArgumentException.class,
                () -> SameDiffLanguageModelImpl.toolCallFormatOpt(
                        Map.of("toolCallFormat", "not-a-format")));
    }
}

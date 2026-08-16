package ai.kompile.app.llm.pipeline;

import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        "minP", 0.05,
                        "repetitionPenalty", 1.05,
                        "frequencyPenalty", 0.25,
                        "presencePenalty", 1.5,
                        "seed", 1234L),
                512,
                SamplingConfig.sample(0.1, 50, 1.0));

        assertTrue(sampling.isDoSample());
        assertEquals(0.1, sampling.getTemperature());
        assertEquals(50, sampling.getTopK());
        assertEquals(0.95, sampling.getTopP());
        assertEquals(0.05, sampling.getMinP());
        assertEquals(1.05, sampling.getRepetitionPenalty());
        assertEquals(0.25, sampling.getFrequencyPenalty());
        assertEquals(1.5, sampling.getPresencePenalty());
        assertEquals(Long.valueOf(1234L), sampling.getSeed());
        assertEquals(512, sampling.getMaxNewTokens());
    }

    @Test
    void resolvesLfm25DefaultsThroughTheExistingLfm2Family() {
        SamplingConfig defaults = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "lfm2.5-1.2b-instruct",
                "lfm2",
                "LFM2.5-1.2B-Instruct-Q4_K_M.gguf");
        SamplingConfig sampling = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(), 256, defaults);

        assertTrue(sampling.isDoSample());
        assertEquals(0.1, sampling.getTemperature());
        assertEquals(50, sampling.getTopK());
        assertEquals(1.0, sampling.getTopP());
        assertEquals(1.05, sampling.getRepetitionPenalty());
    }

    @Test
    void resolvesQwen35DefaultsAndPreservesAllPenaltyFields() {
        SamplingConfig defaults = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "qwen3.5-0.8b-instruct",
                "qwen3.5",
                "Qwen3.5-0.8B-Q4_K_M.gguf");
        SamplingConfig sampling = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(), 256, defaults);

        assertTrue(sampling.isDoSample());
        assertEquals(1.0, sampling.getTemperature());
        assertEquals(20, sampling.getTopK());
        assertEquals(1.0, sampling.getTopP());
        assertEquals(0.0, sampling.getMinP());
        assertEquals(2.0, sampling.getPresencePenalty());
        assertEquals(1.0, sampling.getRepetitionPenalty());
    }

    @Test
    void explicitSamplingOptionsOverrideLfm25DefaultsPerField() {
        SamplingConfig defaults = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "lfm2.5-1.2b-instruct",
                "lfm2",
                "LFM2.5-1.2B-Instruct-Q4_K_M.gguf");
        SamplingConfig sampling = SameDiffLanguageModelImpl.configuredSampling(
                Map.of(
                        "temperature", 0.0,
                        "topK", 1,
                        "topP", 0.8,
                        "repetitionPenalty", 1.2),
                128,
                defaults);

        assertFalse(sampling.isDoSample());
        assertEquals(0.8, sampling.getTopP());
        assertEquals(1.2, sampling.getRepetitionPenalty());
        assertEquals(128, sampling.getMaxNewTokens());
    }

    @Test
    void unrelatedModelKeepsGenericSamplingDefaults() {
        SamplingConfig defaults = SameDiffLanguageModelImpl.modelSamplingDefaults(
                "mistral-7b-instruct", "mistral", "Mistral-7B-Instruct.gguf");

        assertTrue(defaults.isDoSample());
        assertEquals(0.7, defaults.getTemperature());
        assertEquals(0, defaults.getTopK());
        assertEquals(1.0, defaults.getRepetitionPenalty());
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

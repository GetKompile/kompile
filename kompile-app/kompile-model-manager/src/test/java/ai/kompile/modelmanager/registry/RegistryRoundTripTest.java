package ai.kompile.modelmanager.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the registry round-trips correctly through save/load,
 * particularly for LLM model entries with .sdnb and tokenizer.json.
 */
class RegistryRoundTripTest {

    @TempDir
    Path tempDir;

    private RegistryService registryService;

    @BeforeEach
    void setUp() {
        registryService = new RegistryService(tempDir);
    }

    @Test
    void llmModelEntryRoundTrips() {
        ModelEntry llmEntry = ModelEntry.builder()
                .modelId("lfm2.5-1.2b-instruct")
                .type(ModelType.LLM_GGML)
                .path("encoders/lfm2.5-1.2b-instruct")
                .modelFile("model.sdnb")
                .vocabFile("tokenizer.json")
                .checksum("sha256:614437")
                .status(ModelStatus.ACTIVE)
                .promotedAt("2026-05-21T22:19:21Z")
                .metadata(ModelMetadata.builder()
                        .framework("samediff")
                        .modelType("dense")
                        .build())
                .tokenizer(TokenizerConfig.builder()
                        .doLowerCase(false)
                        .stripAccents(false)
                        .addSpecialTokens(true)
                        .maxLength(512)
                        .padding("max_length")
                        .truncation(true)
                        .build())
                .build();

        registryService.addModel(llmEntry);

        // Load back from disk
        ModelRegistry loaded = registryService.loadRegistry();
        ModelEntry result = loaded.getModel("lfm2.5-1.2b-instruct");

        assertNotNull(result);
        assertEquals(ModelType.LLM_GGML, result.getType());
        assertEquals("model.sdnb", result.getModelFile());
        assertEquals("tokenizer.json", result.getVocabFile());
        assertEquals("encoders/lfm2.5-1.2b-instruct", result.getPath());
        assertEquals("encoders/lfm2.5-1.2b-instruct/model.sdnb", result.getModelFilePath());
        assertEquals("encoders/lfm2.5-1.2b-instruct/tokenizer.json", result.getVocabFilePath());
        assertTrue(result.getType().isLlm());
    }

    @Test
    void encoderModelEntryRoundTrips() {
        ModelEntry encoderEntry = ModelEntry.builder()
                .modelId("bge-base-en-v1.5")
                .type(ModelType.DENSE_ENCODER)
                .path("encoders/bge-base-en-v1.5")
                .modelFile("model.sdz")
                .vocabFile("vocab.txt")
                .status(ModelStatus.ACTIVE)
                .build();

        registryService.addModel(encoderEntry);

        ModelRegistry loaded = registryService.loadRegistry();
        ModelEntry result = loaded.getModel("bge-base-en-v1.5");

        assertNotNull(result);
        assertEquals(ModelType.DENSE_ENCODER, result.getType());
        assertEquals("model.sdz", result.getModelFile());
        assertEquals("vocab.txt", result.getVocabFile());
        assertFalse(result.getType().isLlm());
    }

    @Test
    void multipleModelTypesCoexist() {
        ModelEntry llm = ModelEntry.builder()
                .modelId("llm-model")
                .type(ModelType.LLM_GGML)
                .path("encoders/llm-model")
                .modelFile("model.sdnb")
                .vocabFile("tokenizer.json")
                .status(ModelStatus.ACTIVE)
                .build();

        ModelEntry encoder = ModelEntry.builder()
                .modelId("encoder-model")
                .type(ModelType.DENSE_ENCODER)
                .path("encoders/encoder-model")
                .modelFile("model.sdz")
                .vocabFile("vocab.txt")
                .status(ModelStatus.ACTIVE)
                .build();

        registryService.addModel(llm);
        registryService.addModel(encoder);

        ModelRegistry loaded = registryService.loadRegistry();
        assertEquals(2, loaded.getTotalModelCount());

        ModelEntry loadedLlm = loaded.getModel("llm-model");
        ModelEntry loadedEncoder = loaded.getModel("encoder-model");

        assertEquals("model.sdnb", loadedLlm.getModelFile());
        assertEquals("tokenizer.json", loadedLlm.getVocabFile());
        assertEquals("model.sdz", loadedEncoder.getModelFile());
        assertEquals("vocab.txt", loadedEncoder.getVocabFile());
    }
}

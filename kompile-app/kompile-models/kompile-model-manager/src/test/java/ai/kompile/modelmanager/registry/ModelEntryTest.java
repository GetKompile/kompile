package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModelEntry} JSON serialization and computed path behavior.
 */
class ModelEntryTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void computedGettersNotSerializedToJson() throws Exception {
        ModelEntry entry = ModelEntry.builder()
                .modelId("test-model")
                .type(ModelType.LLM_GGML)
                .path("encoders/test-model")
                .modelFile("model.sdnb")
                .vocabFile("tokenizer.json")
                .status(ModelStatus.ACTIVE)
                .build();

        String json = mapper.writeValueAsString(entry);
        JsonNode node = mapper.readTree(json);

        // Computed getters must NOT appear as separate JSON fields
        assertFalse(node.has("modelFilePath"),
                "modelFilePath should not be serialized — it's computed from path + model_file");
        assertFalse(node.has("vocabFilePath"),
                "vocabFilePath should not be serialized — it's computed from path + vocab_file");
        assertFalse(node.has("effectiveVersion"),
                "effectiveVersion should not be serialized — it's computed from metadata");
        assertFalse(node.has("active"),
                "active should not be serialized — it's computed from status");

        // Canonical fields must be present
        assertEquals("model.sdnb", node.get("model_file").asText());
        assertEquals("tokenizer.json", node.get("vocab_file").asText());
        assertEquals("encoders/test-model", node.get("path").asText());
        assertEquals("llm_ggml", node.get("type").asText());
        assertEquals("active", node.get("status").asText());
    }

    @Test
    void llmModelFilePathComputed() {
        ModelEntry entry = ModelEntry.builder()
                .modelId("lfm-model")
                .type(ModelType.LLM_GGML)
                .path("encoders/lfm-model")
                .modelFile("model.sdnb")
                .vocabFile("tokenizer.json")
                .build();

        assertEquals("encoders/lfm-model/model.sdnb", entry.getModelFilePath());
        assertEquals("encoders/lfm-model/tokenizer.json", entry.getVocabFilePath());
    }

    @Test
    void encoderModelFilePathComputed() {
        ModelEntry entry = ModelEntry.builder()
                .modelId("bge-base")
                .type(ModelType.DENSE_ENCODER)
                .path("encoders/bge-base")
                .modelFile("model.sdz")
                .vocabFile("vocab.txt")
                .build();

        assertEquals("encoders/bge-base/model.sdz", entry.getModelFilePath());
        assertEquals("encoders/bge-base/vocab.txt", entry.getVocabFilePath());
    }

    @Test
    void builderDefaultsApplyWhenNotSet() {
        ModelEntry entry = ModelEntry.builder()
                .modelId("default-test")
                .type(ModelType.DENSE_ENCODER)
                .path("encoders/default-test")
                .build();

        assertEquals("model.sdz", entry.getModelFile(),
                "Default model file should be model.sdz for backward compatibility");
        assertEquals("vocab.txt", entry.getVocabFile(),
                "Default vocab file should be vocab.txt for backward compatibility");
    }

    @Test
    void roundTripSerializationPreservesLlmFields() throws Exception {
        ModelEntry original = ModelEntry.builder()
                .modelId("lfm2.5")
                .type(ModelType.LLM_GGML)
                .path("encoders/lfm2.5")
                .modelFile("model.sdnb")
                .vocabFile("tokenizer.json")
                .checksum("sha256:abc123")
                .status(ModelStatus.ACTIVE)
                .metadata(ModelMetadata.builder()
                        .framework("samediff")
                        .build())
                .tokenizer(TokenizerConfig.builder()
                        .doLowerCase(false)
                        .addSpecialTokens(true)
                        .maxLength(512)
                        .build())
                .build();

        String json = mapper.writeValueAsString(original);
        ModelEntry deserialized = mapper.readValue(json, ModelEntry.class);

        assertEquals("lfm2.5", deserialized.getModelId());
        assertEquals(ModelType.LLM_GGML, deserialized.getType());
        assertEquals("model.sdnb", deserialized.getModelFile());
        assertEquals("tokenizer.json", deserialized.getVocabFile());
        assertEquals("encoders/lfm2.5", deserialized.getPath());
        assertEquals("sha256:abc123", deserialized.getChecksum());
        assertEquals(ModelStatus.ACTIVE, deserialized.getStatus());
        // Computed paths still work after round-trip
        assertEquals("encoders/lfm2.5/model.sdnb", deserialized.getModelFilePath());
        assertEquals("encoders/lfm2.5/tokenizer.json", deserialized.getVocabFilePath());
    }

    @Test
    void deserializationIgnoresLegacyComputedFields() throws Exception {
        // Simulate a registry.json written before @JsonIgnore was added,
        // which includes the now-removed modelFilePath/vocabFilePath fields
        String legacyJson = """
                {
                  "model_id": "old-model",
                  "type": "llm_ggml",
                  "path": "encoders/old-model",
                  "model_file": "model.sdnb",
                  "vocab_file": "tokenizer.json",
                  "modelFilePath": "encoders/old-model/model.sdnb",
                  "vocabFilePath": "encoders/old-model/tokenizer.json",
                  "effectiveVersion": null,
                  "active": true,
                  "status": "active"
                }
                """;

        ModelEntry entry = mapper.readValue(legacyJson, ModelEntry.class);

        // Should parse without error (ignoreUnknown = true handles the extra fields)
        assertEquals("old-model", entry.getModelId());
        assertEquals(ModelType.LLM_GGML, entry.getType());
        assertEquals("model.sdnb", entry.getModelFile());
        assertEquals("tokenizer.json", entry.getVocabFile());
    }
}

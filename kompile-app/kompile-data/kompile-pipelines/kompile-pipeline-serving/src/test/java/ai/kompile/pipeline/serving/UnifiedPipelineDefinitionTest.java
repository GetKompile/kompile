package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedPipelineDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testDefinitionSerializationRoundTrip() throws Exception {
        UnifiedPipelineDefinition def = UnifiedPipelineDefinition.builder()
                .pipelineId("test-pipeline")
                .displayName("Test Pipeline")
                .description("A test pipeline for validation")
                .kind(UnifiedPipelineDefinition.PipelineKind.LLM)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .modelSetId("mistral-7b-instruct")
                .modelBindings(Map.of("generator", "mistral-config"))
                .modelDefinitions(Map.of("mistral-config", Map.of(
                        "modelId", "mistral-7b-instruct",
                        "role", "generator")))
                .resolvedModels(Map.of("generator", Map.of(
                        "modelId", "mistral-7b-instruct",
                        "modelPath", "/models/mistral/model.gguf")))
                .llmConfig(Map.of("maxNewTokens", 512, "temperature", 0.7))
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "test-pipeline",
                        "steps", java.util.List.of(Map.of(
                                "@class", "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
                                "runnerClassName", "ai.kompile.pipelines.framework.runtime.steps.samediff.SameDiffLLMStepRunner",
                                "parameters", Map.of("modelSetId", "mistral-7b-instruct")
                        ))
                ))
                .serving(UnifiedPipelineDefinition.ServingConfig.builder()
                        .heapSize("16g")
                        .gpuDeviceId("0")
                        .build())
                .enabled(true)
                .createdAt("2025-06-01T00:00:00Z")
                .build();

        // Serialize
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(def);
        assertNotNull(json);
        assertTrue(json.contains("test-pipeline"));
        assertTrue(json.contains("LLM"));
        assertTrue(json.contains("16g"));

        // Deserialize
        UnifiedPipelineDefinition deserialized = mapper.readValue(json, UnifiedPipelineDefinition.class);
        assertEquals("test-pipeline", deserialized.getPipelineId());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.LLM, deserialized.getKind());
        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE, deserialized.getTopology());
        assertEquals("mistral-7b-instruct", deserialized.getModelSetId());
        assertEquals("mistral-config", deserialized.getModelBindings().get("generator"));
        assertEquals("mistral-7b-instruct",
                deserialized.getModelDefinitions().get("mistral-config").get("modelId"));
        assertEquals("/models/mistral/model.gguf",
                deserialized.getResolvedModels().get("generator").get("modelPath"));
        assertEquals("16g", deserialized.getServing().getHeapSize());
        assertEquals("0", deserialized.getServing().getGpuDeviceId());
        assertTrue(deserialized.isEnabled());
    }

    @Test
    void validatesPlainMapStepParametersAdvertisedByMcp() {
        UnifiedPipelineDefinition definition = UnifiedPipelineDefinition.builder()
                .pipelineId("vlm-document")
                .kind(UnifiedPipelineDefinition.PipelineKind.VLM)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "vlm-document",
                        "steps", java.util.List.of(Map.of(
                                "@class", "ai.kompile.pipelines.framework.core.config.GenericStepConfig",
                                "runnerClassName", "ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner",
                                "parameters", Map.of(
                                        "outputFormat", "MARKDOWN",
                                        "pdfRenderDpi", 96,
                                        "pageBatchSize", 1)))))
                .build();

        PipelineDefinitionValidator.Validation validation =
                PipelineDefinitionValidator.validate(definition);

        assertTrue(validation.valid(), () -> String.join("; ", validation.errors()));
    }

    @Test
    void testSubprocessArgsTempFile() throws Exception {
        PipelineServingSubprocessArgs args = new PipelineServingSubprocessArgs(
                "{\"pipelineId\":\"test\"}");

        Path tempFile = args.writeToTempFile();
        try {
            assertTrue(Files.exists(tempFile));
            PipelineServingSubprocessArgs loaded = PipelineServingSubprocessArgs.fromFile(tempFile);
            assertEquals("{\"pipelineId\":\"test\"}", loaded.pipelineDefinitionJson());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testDefaultServingConfig() {
        UnifiedPipelineDefinition.ServingConfig config = UnifiedPipelineDefinition.ServingConfig.builder().build();
        assertEquals("8g", config.getHeapSize());
        assertNull(config.getGpuDeviceId());
    }
}

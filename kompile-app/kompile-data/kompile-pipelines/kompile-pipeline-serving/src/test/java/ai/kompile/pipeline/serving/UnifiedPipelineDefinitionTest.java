package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.definition.PipelineDefinitionIdentity;
import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    void standaloneChatDefinitionRoundTripsWithoutTensorPipeline() throws Exception {
        UnifiedPipelineDefinition definition = chatDefinition();
        definition.setModelBindings(Map.of("default", "remote-generator"));
        definition.setModelDefinitions(Map.of("remote-generator", Map.of(
                "source", "chat", "provider", "claude", "modelId", "selected-model")));
        definition.setInputs(Map.of("text", UnifiedPipelineDefinition.DataContract.builder()
                .type("string").required(true).build()));
        definition.setOutputs(Map.of("text", UnifiedPipelineDefinition.DataContract.builder().type("string").build()));

        UnifiedPipelineDefinition restored = mapper.readValue(
                mapper.writeValueAsBytes(definition), UnifiedPipelineDefinition.class);
        PipelineDefinitionValidator.Validation validation = PipelineDefinitionValidator.validate(restored);
        assertTrue(validation.valid(), validation.errors().toString());
        assertEquals(definition.getProcessor(), restored.getProcessor());
        assertNull(restored.getPipelineSpec());
        assertNull(restored.getResolvedModels());
    }

    @Test
    void standaloneTranslationRoundTripsWithIndependentChatModelAndNestedOptions() throws Exception {
        UnifiedPipelineDefinition definition = translationDefinition();
        definition.setModelBindings(Map.of("generator", "translator"));
        definition.setModelDefinitions(Map.of("translator", Map.of(
                "source", "chat", "provider", "custom", "modelId", "manual-unlisted-model")));
        definition.setInputs(Map.of("text", UnifiedPipelineDefinition.DataContract.builder().type("string").build()));
        definition.setOutputs(Map.of("text", UnifiedPipelineDefinition.DataContract.builder().type("string").build()));
        UnifiedPipelineDefinition restored = mapper.readValue(mapper.writeValueAsBytes(definition), UnifiedPipelineDefinition.class);
        var validation = PipelineDefinitionValidator.validate(restored);
        assertTrue(validation.valid(), validation.errors().toString());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.TRANSLATION, restored.getKind());
        assertEquals(definition.getProcessor(), restored.getProcessor());
        assertEquals(definition.getModelBindings(), restored.getModelBindings());
        assertEquals(definition.getModelDefinitions(), restored.getModelDefinitions());
        assertNull(restored.getPipelineSpec());
        assertNull(restored.getResolvedModels());
    }

    @Test
    void translationRejectsKindOperationMismatchAndTensorFallback() {
        List<Consumer<UnifiedPipelineDefinition>> invalid = List.of(
                value -> value.setKind(UnifiedPipelineDefinition.PipelineKind.LLM),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "operation", "text")),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL")),
                value -> value.setProcessor(null),
                value -> {
                    value.setProcessor(null);
                    value.setPipelineSpec(Map.of("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline", "steps", List.of()));
                });
        for (Consumer<UnifiedPipelineDefinition> mutation : invalid) {
            UnifiedPipelineDefinition definition = translationDefinition();
            mutation.accept(definition);
            assertFalse(PipelineDefinitionValidator.validate(definition).valid(), definition.toString());
        }
    }

    @Test
    void translationRejectsMediaContractsAndUnsupportedOrMalformedOptions() {
        for (String field : List.of("path", "filePath", "image")) {
            UnifiedPipelineDefinition definition = translationDefinition();
            definition.setInputs(Map.of(field, UnifiedPipelineDefinition.DataContract.builder().type("string").build()));
            assertFalse(PipelineDefinitionValidator.validate(definition).valid(), field);
        }
        for (Map<String, Object> processor : List.<Map<String, Object>>of(
                Map.of("type", "CHAT_MODEL", "operation", "translation"),
                Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "und")),
                Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "fr", "maxCharsPerRequest", "100")),
                Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "fr"), "prompt", "silently replace core prompt"),
                Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "fr"), "maxPages", 10))) {
            UnifiedPipelineDefinition definition = translationDefinition();
            definition.setProcessor(processor);
            assertFalse(PipelineDefinitionValidator.validate(definition).valid(), processor.toString());
        }
    }

    @Test
    void translationOptionsAffectPortableDefinitionIdentity() {
        UnifiedPipelineDefinition definition = translationDefinition();
        String first = PipelineDefinitionIdentity.contentDigest(mapper, definition);
        definition.setProcessor(Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "de")));
        assertNotEquals(first, PipelineDefinitionIdentity.contentDigest(mapper, definition));
    }

    private UnifiedPipelineDefinition translationDefinition() {
        return UnifiedPipelineDefinition.builder().pipelineId("host-translation")
                .kind(UnifiedPipelineDefinition.PipelineKind.TRANSLATION)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .processor(Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", Map.of("targetLanguage", "fr")))
                .build();
    }

    @Test
    void hostChatRejectsMixedExecutorsUnsupportedRolesAndCredentials() {
        List<Consumer<UnifiedPipelineDefinition>> invalid = List.of(
                value -> value.setKind(UnifiedPipelineDefinition.PipelineKind.VLM),
                value -> value.setTopology(UnifiedPipelineDefinition.ExecutionTopology.GRAPH),
                value -> value.setPipelineSpec(Map.of()),
                value -> value.setPipelineSpec(Map.of("steps", List.of(Map.of(), Map.of()))),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "steps", List.of(Map.of(), Map.of()))),
                value -> value.setProcessor(Map.of("type", "REMOTE_TENSOR")),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "operation", "embedding")),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "apiKey", "never-persist")),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "maxPages", 201)),
                value -> value.setProcessor(Map.of("type", "CHAT_MODEL", "operation", "json_schema")),
                value -> value.setModelBindings(Map.of("embedding", "remote-generator")),
                value -> value.setModelBindings(Map.of("default", "first", "generator", "second")),
                value -> value.setModelDefinitions(Map.of("bad", Map.of("source", "local", "localPath", "/tmp/model"))),
                value -> value.setModelDefinitions(Map.of("bad", Map.of("source", "chat", "credentials", Map.of("token", "never-persist")))),
                value -> value.setInputs(Map.of("input_ids", UnifiedPipelineDefinition.DataContract.builder().type("tensor").build())),
                value -> value.setInputs(Map.of("text", UnifiedPipelineDefinition.DataContract.builder().type("tensor").build())),
                value -> value.setOutputs(Map.of("embedding", UnifiedPipelineDefinition.DataContract.builder().type("tensor").build())),
                value -> value.setResolvedModels(Map.of()),
                value -> value.setRuntimeRequirements(UnifiedPipelineDefinition.RuntimeRequirements.builder().backend("cpu").build()),
                value -> value.setLlmConfig(Map.of("temperature", 0.1)));
        for (Consumer<UnifiedPipelineDefinition> mutation : invalid) {
            UnifiedPipelineDefinition definition = chatDefinition();
            mutation.accept(definition);
            assertFalse(PipelineDefinitionValidator.validate(definition).valid(), definition.toString());
        }
    }

    @Test
    void chatProcessorChangesAreDigestedAndVersioned(@TempDir Path root) throws Exception {
        PipelineDefinitionStore store = new PipelineDefinitionStore(root, mapper);
        UnifiedPipelineDefinition definition = chatDefinition();
        UnifiedPipelineDefinition first = store.save(definition, 0L, "test");
        definition.setProcessor(Map.of("type", "CHAT_MODEL", "provider", "claude", "modelId", "second-model", "prompt", "second prompt"));
        UnifiedPipelineDefinition second = store.save(definition, 1L, "test");

        assertNotEquals(first.getContentDigest(), second.getContentDigest());
        assertEquals(first.getProcessor(), store.version("host-chat", 1).orElseThrow().getProcessor());
        assertEquals(second.getProcessor(), store.version("host-chat", 2).orElseThrow().getProcessor());
        assertEquals(first.getContentDigest(), PipelineDefinitionIdentity.contentDigest(mapper, first));
        assertEquals(second.getContentDigest(), store.promote("host-chat", 2, 1L, "test").getContentDigest());
    }

    @Test
    void localPipelinesRejectChatBindingsWithoutChangingLocalValidation() {
        UnifiedPipelineDefinition definition = UnifiedPipelineDefinition.builder()
                .pipelineId("local-pipeline").kind(UnifiedPipelineDefinition.PipelineKind.GENERIC)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "local-pipeline", "steps", List.of())).build();
        assertTrue(PipelineDefinitionValidator.validate(definition).valid());
        definition.setModelBindings(Map.of("default", "remote-generator"));
        definition.setModelDefinitions(Map.of("remote-generator", Map.of("source", "chat", "modelId", "model")));
        PipelineDefinitionValidator.Validation validation = PipelineDefinitionValidator.validate(definition);
        assertFalse(validation.valid());
        assertTrue(validation.errors().toString().contains("Local tensor pipelines"));
    }

    private UnifiedPipelineDefinition chatDefinition() {
        return UnifiedPipelineDefinition.builder().pipelineId("host-chat")
                .kind(UnifiedPipelineDefinition.PipelineKind.LLM)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .processor(Map.of("type", "CHAT_MODEL", "provider", "codex", "modelId", "first-model", "prompt", "first prompt"))
                .build();
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

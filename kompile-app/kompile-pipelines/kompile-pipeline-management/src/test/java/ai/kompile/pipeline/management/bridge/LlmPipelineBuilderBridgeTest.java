package ai.kompile.pipeline.management.bridge;

import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmPipelineBuilderBridgeTest {

    private final LlmPipelineBuilderBridge bridge = new LlmPipelineBuilderBridge();

    @Test
    void testSequencePipelineConversion() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("test-llm-pipeline")
                .displayName("Test LLM Pipeline")
                .description("A test LLM pipeline")
                .pipelineType(LlmPipelineDefinition.PipelineType.SEQUENCE)
                .modelSetId("mistral-7b-instruct")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        assertEquals("test-llm-pipeline", unified.getPipelineId());
        assertEquals("Test LLM Pipeline", unified.getDisplayName());
        assertEquals("A test LLM pipeline", unified.getDescription());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.LLM, unified.getKind());
        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE, unified.getTopology());
        assertEquals("mistral-7b-instruct", unified.getModelSetId());
        assertTrue(unified.isEnabled());
        assertFalse(unified.isBuiltin());
    }

    @Test
    void testGraphPipelineConversion() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("graph-llm-pipeline")
                .displayName("Graph LLM Pipeline")
                .pipelineType(LlmPipelineDefinition.PipelineType.GRAPH)
                .modelSetId("llama-3-8b")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.GRAPH, unified.getTopology());
    }

    @Test
    void testDefaultParametersPreserved() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("params-llm-pipeline")
                .displayName("Params LLM Pipeline")
                .modelSetId("mistral-7b")
                .addParameter("maxNewTokens", 512)
                .addParameter("temperature", 0.7)
                .addParameter("topP", 0.9)
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        // Default parameters should be in llmConfig
        assertNotNull(unified.getLlmConfig());
        assertEquals(512, unified.getLlmConfig().get("maxNewTokens"));
        assertEquals(0.7, unified.getLlmConfig().get("temperature"));
        assertEquals(0.9, unified.getLlmConfig().get("topP"));

        // Also in pipelineSpec
        assertNotNull(unified.getPipelineSpec());
        @SuppressWarnings("unchecked")
        Map<String, Object> specParams = (Map<String, Object>) unified.getPipelineSpec().get("defaultParameters");
        assertNotNull(specParams);
        assertEquals(512, specParams.get("maxNewTokens"));
    }

    @Test
    void testPipelineSpecContainsBridgeMarker() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("bridge-marker-test")
                .displayName("Bridge Marker Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        Map<String, Object> spec = unified.getPipelineSpec();
        assertNotNull(spec);
        assertEquals("llm", spec.get("@bridge"));
        assertEquals("bridge-marker-test", spec.get("pipelineId"));
        assertEquals("SEQUENCE", spec.get("pipelineType"));
        assertEquals("test-model", spec.get("modelSetId"));
    }

    @Test
    void testServingConfigDefaultsTo16g() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("heap-test")
                .displayName("Heap Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        assertNotNull(unified.getServing());
        assertEquals("16g", unified.getServing().getHeapSize());
    }

    @Test
    void testBuiltinFlagPreserved() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("builtin-test")
                .displayName("Builtin Test")
                .modelSetId("test-model")
                .isBuiltin(true)
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);
        assertTrue(unified.isBuiltin());
    }

    @Test
    void testTimestampsPreserved() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("timestamp-test")
                .displayName("Timestamp Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();
        // Builder sets createdAt/updatedAt via Instant.now()

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        assertNotNull(unified.getCreatedAt());
        assertNotNull(unified.getUpdatedAt());
    }

    @Test
    void testNullDefaultParametersHandled() {
        LlmPipelineDefinition llmDef = LlmPipelineDefinition.builder()
                .pipelineId("null-params-test")
                .displayName("Null Params Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();

        // Clear default parameters
        llmDef.setDefaultParameters(null);

        UnifiedPipelineDefinition unified = bridge.toUnified(llmDef);

        // Should not throw, llmConfig should be null
        assertNull(unified.getLlmConfig());
        // pipelineSpec should not contain defaultParameters key
        assertFalse(unified.getPipelineSpec().containsKey("defaultParameters"));
    }
}

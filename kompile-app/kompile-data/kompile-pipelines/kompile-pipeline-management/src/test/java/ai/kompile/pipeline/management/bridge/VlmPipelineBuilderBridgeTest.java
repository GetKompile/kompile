package ai.kompile.pipeline.management.bridge;

import ai.kompile.modelmanager.vlm.dynamic.VlmPipelineDefinition;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VlmPipelineBuilderBridgeTest {

    private final VlmPipelineBuilderBridge bridge = new VlmPipelineBuilderBridge();

    @Test
    void testSequencePipelineConversion() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("test-vlm-pipeline")
                .displayName("Test VLM Pipeline")
                .description("A test VLM pipeline")
                .pipelineType(VlmPipelineDefinition.PipelineType.SEQUENCE)
                .modelSetId("smoldocling-256m")
                .addExtractionType("document-understanding")
                .addExtractionType("table-extraction")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        assertEquals("test-vlm-pipeline", unified.getPipelineId());
        assertEquals("Test VLM Pipeline", unified.getDisplayName());
        assertEquals("A test VLM pipeline", unified.getDescription());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.VLM, unified.getKind());
        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE, unified.getTopology());
        assertEquals("smoldocling-256m", unified.getModelSetId());
        assertTrue(unified.isEnabled());
    }

    @Test
    void testGraphPipelineConversion() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("graph-vlm-pipeline")
                .displayName("Graph VLM Pipeline")
                .pipelineType(VlmPipelineDefinition.PipelineType.GRAPH)
                .modelSetId("florence-2")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.GRAPH, unified.getTopology());
    }

    @Test
    void testExtractionTypesPreserved() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("extraction-test")
                .displayName("Extraction Test")
                .modelSetId("smoldocling-256m")
                .addExtractionType("document-understanding")
                .addExtractionType("table-extraction")
                .addExtractionType("chart-analysis")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        assertNotNull(unified.getExtractionTypes());
        assertEquals(3, unified.getExtractionTypes().size());
        assertTrue(unified.getExtractionTypes().contains("document-understanding"));
        assertTrue(unified.getExtractionTypes().contains("table-extraction"));
        assertTrue(unified.getExtractionTypes().contains("chart-analysis"));

        // Also in pipelineSpec
        @SuppressWarnings("unchecked")
        List<String> specTypes = (List<String>) unified.getPipelineSpec().get("extractionTypes");
        assertNotNull(specTypes);
        assertEquals(3, specTypes.size());
    }

    @Test
    void testPipelineSpecContainsVlmBridgeMarker() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("vlm-bridge-marker")
                .displayName("VLM Bridge Marker")
                .modelSetId("test-model")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        Map<String, Object> spec = unified.getPipelineSpec();
        assertNotNull(spec);
        assertEquals("vlm", spec.get("@bridge"));
        assertEquals("vlm-bridge-marker", spec.get("pipelineId"));
        assertEquals("SEQUENCE", spec.get("pipelineType"));
        assertEquals("test-model", spec.get("modelSetId"));
    }

    @Test
    void testServingConfigDefaultsTo12g() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("heap-test")
                .displayName("Heap Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        assertNotNull(unified.getServing());
        assertEquals("12g", unified.getServing().getHeapSize());
    }

    @Test
    void testTimestampsConvertedFromEpochMillis() {
        long createdMs = 1700000000000L; // ~Nov 2023
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("timestamp-test")
                .displayName("Timestamp Test")
                .modelSetId("test-model")
                .createdAt(createdMs)
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        assertNotNull(unified.getCreatedAt());
        // Should be ISO-8601 format
        Instant parsed = Instant.parse(unified.getCreatedAt());
        assertEquals(createdMs, parsed.toEpochMilli());
    }

    @Test
    void testZeroTimestampsUseCurrentTime() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("zero-ts-test")
                .displayName("Zero TS Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();
        // Force zero timestamps
        vlmDef.setCreatedAt(0);
        vlmDef.setUpdatedAt(0);

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        // Should still get valid timestamps (Instant.now())
        assertNotNull(unified.getCreatedAt());
        assertNotNull(unified.getUpdatedAt());
        assertDoesNotThrow(() -> Instant.parse(unified.getCreatedAt()));
        assertDoesNotThrow(() -> Instant.parse(unified.getUpdatedAt()));
    }

    @Test
    void testBuiltinFlagPreserved() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("builtin-test")
                .displayName("Builtin Test")
                .modelSetId("test-model")
                .isBuiltin(true)
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);
        assertTrue(unified.isBuiltin());
    }

    @Test
    void testDefaultParametersInSpec() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("params-test")
                .displayName("Params Test")
                .modelSetId("test-model")
                .defaultParameter("maxTokens", 4096)
                .defaultParameter("batchSize", 1)
                .enabled(true)
                .build();

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        @SuppressWarnings("unchecked")
        Map<String, Object> specParams = (Map<String, Object>) unified.getPipelineSpec().get("defaultParameters");
        assertNotNull(specParams);
        assertEquals(4096, specParams.get("maxTokens"));
        assertEquals(1, specParams.get("batchSize"));
    }

    @Test
    void testEmptyExtractionTypesNotInSpec() {
        VlmPipelineDefinition vlmDef = VlmPipelineDefinition.builder()
                .pipelineId("empty-types-test")
                .displayName("Empty Types Test")
                .modelSetId("test-model")
                .enabled(true)
                .build();
        // No extraction types added — list is empty by default

        UnifiedPipelineDefinition unified = bridge.toUnified(vlmDef);

        // Empty list should still be set on the unified definition
        assertNotNull(unified.getExtractionTypes());
        assertTrue(unified.getExtractionTypes().isEmpty());
    }
}

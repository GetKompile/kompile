package ai.kompile.pipeline.management.service;

import ai.kompile.pipeline.management.dto.PipelineSummaryDto;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineManagementServiceTest {

    @TempDir
    Path tempDir;

    private PipelineManagementService service;

    @BeforeEach
    void setUp() {
        service = new PipelineManagementService(tempDir);
    }

    @Test
    void testSaveAndGetUnified() {
        UnifiedPipelineDefinition def = createTestDefinition("save-get-test", "LLM");

        service.saveUnified(def);

        UnifiedPipelineDefinition loaded = service.getUnified("save-get-test");
        assertNotNull(loaded);
        assertEquals("save-get-test", loaded.getPipelineId());
        assertEquals("Test Pipeline: save-get-test", loaded.getDisplayName());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.LLM, loaded.getKind());
    }

    @Test
    void testGetUnifiedReturnsNullForMissing() {
        UnifiedPipelineDefinition result = service.getUnified("nonexistent");
        assertNull(result);
    }

    @Test
    void testSaveUnifiedCreatesFile() {
        UnifiedPipelineDefinition def = createTestDefinition("file-test", "VLM");
        service.saveUnified(def);

        Path expectedFile = tempDir.resolve("unified/file-test/versions/00000000000000000001.json");
        assertTrue(Files.exists(expectedFile));
    }

    @Test
    void testSaveUnifiedOverwritesExisting() {
        UnifiedPipelineDefinition def1 = createTestDefinition("overwrite-test", "LLM");
        def1.setDescription("Version 1");
        service.saveUnified(def1);

        UnifiedPipelineDefinition def2 = createTestDefinition("overwrite-test", "LLM");
        def2.setDescription("Version 2");
        service.saveUnified(def2);

        UnifiedPipelineDefinition loaded = service.getUnified("overwrite-test");
        assertEquals("Version 2", loaded.getDescription());
    }

    @Test
    void testListAllIncludesUnifiedDefinitions() {
        service.saveUnified(createTestDefinition("unified-1", "LLM"));
        service.saveUnified(createTestDefinition("unified-2", "VLM"));

        List<PipelineSummaryDto> list = service.listAll();
        assertEquals(2, list.size());

        List<String> ids = list.stream().map(PipelineSummaryDto::getPipelineId).sorted().toList();
        assertEquals(List.of("unified-1", "unified-2"), ids);
    }

    @Test
    void testListAllReturnsCorrectKind() {
        service.saveUnified(createTestDefinition("llm-pipeline", "LLM"));
        service.saveUnified(createTestDefinition("vlm-pipeline", "VLM"));

        List<PipelineSummaryDto> list = service.listAll();
        for (PipelineSummaryDto summary : list) {
            if ("llm-pipeline".equals(summary.getPipelineId())) {
                assertEquals("LLM", summary.getKind());
            } else if ("vlm-pipeline".equals(summary.getPipelineId())) {
                assertEquals("VLM", summary.getKind());
            }
        }
    }

    @Test
    void testDeleteRemovesUnifiedFile() {
        service.saveUnified(createTestDefinition("delete-test", "GENERIC"));

        assertNotNull(service.getUnified("delete-test"));
        boolean deleted = service.delete("delete-test");
        assertTrue(deleted);
        assertNull(service.getUnified("delete-test"));
    }

    @Test
    void testDeleteNonexistentReturnsFalse() {
        boolean deleted = service.delete("nonexistent");
        assertFalse(deleted);
    }

    @Test
    void testListAllEmptyDirectory() {
        List<PipelineSummaryDto> list = service.listAll();
        assertTrue(list.isEmpty());
    }

    @Test
    void testUnifiedDefinitionSerializationPreservesAllFields() {
        UnifiedPipelineDefinition def = UnifiedPipelineDefinition.builder()
                .pipelineId("full-fields-test")
                .displayName("Full Fields Test")
                .description("Testing all fields survive round-trip")
                .kind(UnifiedPipelineDefinition.PipelineKind.RAG)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.GRAPH)
                .modelSetId("rag-model-set")
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "full-fields-test",
                        "steps", List.of()))
                .ragConfig(Map.of("chunkSize", 512, "chunkOverlap", 50))
                .extractionTypes(List.of("text-extraction"))
                .tags(Map.of("team", "ml", "version", "2"))
                .builtin(false)
                .enabled(true)
                .createdAt("2025-06-01T00:00:00Z")
                .updatedAt("2025-06-01T12:00:00Z")
                .serving(UnifiedPipelineDefinition.ServingConfig.builder()
                        .heapSize("12g")
                        .gpuDeviceId("0")
                        .build())
                .build();

        service.saveUnified(def);
        UnifiedPipelineDefinition loaded = service.getUnified("full-fields-test");

        assertEquals("full-fields-test", loaded.getPipelineId());
        assertEquals("Full Fields Test", loaded.getDisplayName());
        assertEquals("Testing all fields survive round-trip", loaded.getDescription());
        assertEquals(UnifiedPipelineDefinition.PipelineKind.RAG, loaded.getKind());
        assertEquals(UnifiedPipelineDefinition.ExecutionTopology.GRAPH, loaded.getTopology());
        assertEquals("rag-model-set", loaded.getModelSetId());
        assertNotNull(loaded.getRagConfig());
        assertEquals(512, loaded.getRagConfig().get("chunkSize"));
        assertNotNull(loaded.getTags());
        assertEquals("ml", loaded.getTags().get("team"));
        assertFalse(loaded.isBuiltin());
        assertTrue(loaded.isEnabled());
        assertEquals("2025-06-01T00:00:00Z", loaded.getCreatedAt());
        assertNotEquals("2025-06-01T12:00:00Z", loaded.getUpdatedAt());
        assertEquals(1L, loaded.getDefinitionVersion());
        assertNotNull(loaded.getContentDigest());

        // Serving config
        UnifiedPipelineDefinition.ServingConfig servingConfig = loaded.getServing();
        assertNotNull(servingConfig);
        assertEquals("12g", servingConfig.getHeapSize());
        assertEquals("0", servingConfig.getGpuDeviceId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UnifiedPipelineDefinition createTestDefinition(String id, String kind) {
        return UnifiedPipelineDefinition.builder()
                .pipelineId(id)
                .displayName("Test Pipeline: " + id)
                .description("Test description for " + id)
                .kind(UnifiedPipelineDefinition.PipelineKind.valueOf(kind))
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", id,
                        "steps", List.of()))
                .modelSetId("test-model-set")
                .enabled(true)
                .createdAt("2025-06-01T00:00:00Z")
                .updatedAt("2025-06-01T00:00:00Z")
                .build();
    }
}

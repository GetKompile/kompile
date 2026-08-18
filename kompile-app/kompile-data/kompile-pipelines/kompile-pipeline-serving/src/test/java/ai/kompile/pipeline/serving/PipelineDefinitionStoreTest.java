package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineDefinitionStoreTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void versionsAreImmutableAndPromotionIsAtomic() throws Exception {
        PipelineDefinitionStore store = new PipelineDefinitionStore(tempDir, mapper);
        UnifiedPipelineDefinition first = store.save(definition("v1"), 0L, "agent-a");

        assertEquals(1L, first.getDefinitionVersion());
        assertEquals(UnifiedPipelineDefinition.LifecycleState.ACTIVE, first.getLifecycleState());
        assertNotNull(first.getContentDigest());
        assertEquals(1L, store.active("doc-pipeline").orElseThrow().getDefinitionVersion());

        UnifiedPipelineDefinition second = store.save(definition("v2"), 1L, "agent-b");
        assertEquals(2L, second.getDefinitionVersion());
        assertEquals(UnifiedPipelineDefinition.LifecycleState.DRAFT, second.getLifecycleState());
        assertEquals(1L, store.active("doc-pipeline").orElseThrow().getDefinitionVersion());

        UnifiedPipelineDefinition promoted = store.promote("doc-pipeline", 2L, 1L, "agent-b");
        assertEquals(2L, promoted.getDefinitionVersion());
        assertEquals(2L, store.active("doc-pipeline").orElseThrow().getDefinitionVersion());
        assertEquals(List.of(1L, 2L), store.versions("doc-pipeline").stream()
                .map(UnifiedPipelineDefinition::getDefinitionVersion).toList());
    }

    @Test
    void staleExpectedVersionIsRejected() throws Exception {
        PipelineDefinitionStore store = new PipelineDefinitionStore(tempDir, mapper);
        store.save(definition("v1"), 0L, "agent-a");

        ConcurrentModificationException failure = assertThrows(
                ConcurrentModificationException.class,
                () -> store.save(definition("stale"), 7L, "agent-b"));
        assertTrue(failure.getMessage().contains("expected 7"));
    }

    @Test
    void archiveRemovesOnlyTheActivePointer() throws Exception {
        PipelineDefinitionStore store = new PipelineDefinitionStore(tempDir, mapper);
        store.save(definition("v1"), 0L, "agent-a");

        assertTrue(store.archive("doc-pipeline", 1L, "agent-a"));
        assertTrue(store.active("doc-pipeline").isEmpty());
        assertEquals(1, store.versions("doc-pipeline").size());
    }

    private UnifiedPipelineDefinition definition(String description) {
        return UnifiedPipelineDefinition.builder()
                .pipelineId("doc-pipeline")
                .displayName("Document pipeline")
                .description(description)
                .kind(UnifiedPipelineDefinition.PipelineKind.GENERIC)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "doc-pipeline",
                        "steps", List.of()))
                .build();
    }
}

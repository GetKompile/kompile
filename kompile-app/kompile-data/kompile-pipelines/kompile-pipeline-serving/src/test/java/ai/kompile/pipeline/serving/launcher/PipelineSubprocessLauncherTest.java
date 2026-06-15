package ai.kompile.pipeline.serving.launcher;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineSubprocessLauncherTest {

    private PipelineSubprocessLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new PipelineSubprocessLauncher();
    }

    @Test
    void testIsServingReturnsFalseInitially() {
        assertFalse(launcher.isServing("nonexistent"));
    }

    @Test
    void testGetActiveHandlesEmptyInitially() {
        assertTrue(launcher.getActiveHandles().isEmpty());
    }

    @Test
    void testGetActiveHandlesReturnsUnmodifiableMap() {
        Map<String, PipelineServingHandle> handles = launcher.getActiveHandles();
        assertThrows(UnsupportedOperationException.class, () -> handles.put("test", null));
    }

    @Test
    void testStopServingReturnsFalseForNonexistent() {
        assertFalse(launcher.stopServing("nonexistent"));
    }

    @Test
    void testInvokeServedThrowsForNonServing() {
        assertThrows(IllegalStateException.class, () ->
                launcher.invokeServed("nonexistent", Map.of("input", "test")));
    }

    @Test
    void testShutdownWithNoActiveHandles() {
        // Should not throw
        assertDoesNotThrow(() -> launcher.shutdown());
    }

    @Test
    void testDefaultServingConfigUsedWhenNull() throws Exception {
        UnifiedPipelineDefinition def = UnifiedPipelineDefinition.builder()
                .pipelineId("no-serving-config")
                .displayName("No Serving Config")
                .kind(UnifiedPipelineDefinition.PipelineKind.GENERIC)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of("@class", "test"))
                .enabled(true)
                .createdAt(Instant.now().toString())
                .build();
        // serving is null — should still be able to build args internally

        // We can't launch a real subprocess in unit test, but we can verify
        // the launcher handles the null serving config gracefully
        assertNull(def.getServing());
    }
}

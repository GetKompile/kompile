package ai.kompile.app.subprocess.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelInitSubprocessLauncherTest {

    @Test
    void deviceRoutingOverlayPreservesEveryNonRoutingArgument() {
        ModelInitSubprocessArgs source = ModelInitSubprocessArgs.builder()
                .taskId("original-task")
                .modelIdentifier("model-id")
                .modelSourceType("staging")
                .stagingUrl("http://staging:8090")
                .stagingApiKey("secret")
                .archivePath("/models/archive.karch")
                .optimalBatchSize(17)
                .maxBatchSize(29)
                .nd4jConfigJson("original-routing")
                .callbackBaseUrl("http://callback:8080")
                .memoryThresholdPercent(71)
                .memoryCriticalPercent(81)
                .memoryKillThresholdPercent(91)
                .memoryCheckIntervalMs(1234L)
                .gpuMemoryThresholdPercent(72)
                .gpuMemoryCriticalPercent(82)
                .gpuMemoryKillThresholdPercent(92)
                .offHeapThresholdPercent(73)
                .offHeapCriticalPercent(83)
                .offHeapKillThresholdPercent(93)
                .skipValidation(true)
                .validationTestText("validation")
                .options(Map.of("cacheOnly", true))
                .build();

        ModelInitSubprocessArgs routed = ModelInitSubprocessLauncher.copyWithTaskAndNd4jConfig(
                source, "routed-task", "routed-config");

        assertAll(
                () -> assertEquals("routed-task", routed.taskId()),
                () -> assertEquals("routed-config", routed.nd4jConfigJson()),
                () -> assertEquals(source.modelIdentifier(), routed.modelIdentifier()),
                () -> assertEquals(source.modelSourceType(), routed.modelSourceType()),
                () -> assertEquals(source.stagingUrl(), routed.stagingUrl()),
                () -> assertEquals(source.stagingApiKey(), routed.stagingApiKey()),
                () -> assertEquals(source.archivePath(), routed.archivePath()),
                () -> assertEquals(source.optimalBatchSize(), routed.optimalBatchSize()),
                () -> assertEquals(source.maxBatchSize(), routed.maxBatchSize()),
                () -> assertEquals(source.callbackBaseUrl(), routed.callbackBaseUrl()),
                () -> assertEquals(source.memoryThresholdPercent(), routed.memoryThresholdPercent()),
                () -> assertEquals(source.memoryCriticalPercent(), routed.memoryCriticalPercent()),
                () -> assertEquals(source.memoryKillThresholdPercent(), routed.memoryKillThresholdPercent()),
                () -> assertEquals(source.memoryCheckIntervalMs(), routed.memoryCheckIntervalMs()),
                () -> assertEquals(source.gpuMemoryThresholdPercent(), routed.gpuMemoryThresholdPercent()),
                () -> assertEquals(source.gpuMemoryCriticalPercent(), routed.gpuMemoryCriticalPercent()),
                () -> assertEquals(source.gpuMemoryKillThresholdPercent(), routed.gpuMemoryKillThresholdPercent()),
                () -> assertEquals(source.offHeapThresholdPercent(), routed.offHeapThresholdPercent()),
                () -> assertEquals(source.offHeapCriticalPercent(), routed.offHeapCriticalPercent()),
                () -> assertEquals(source.offHeapKillThresholdPercent(), routed.offHeapKillThresholdPercent()),
                () -> assertEquals(source.skipValidation(), routed.skipValidation()),
                () -> assertEquals(source.validationTestText(), routed.validationTestText()),
                () -> assertEquals(source.options(), routed.options()));
    }
}

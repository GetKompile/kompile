package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingMessage;
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
                        .port(9091)
                        .gpuDeviceId("0")
                        .memoryStopPercent(80)
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
        assertEquals("16g", deserialized.getServing().getHeapSize());
        assertEquals(9091, deserialized.getServing().getPort());
        assertEquals(80, deserialized.getServing().getMemoryStopPercent());
        assertTrue(deserialized.isEnabled());
    }

    @Test
    void testServingMessageSerialization() throws Exception {
        PipelineServingMessage.Ready ready = new PipelineServingMessage.Ready(
                "task-123", 1500L, "my-pipeline", "LLM", 9091, 12345L
        );

        String json = mapper.writeValueAsString(ready);
        assertTrue(json.contains("READY"));
        assertTrue(json.contains("task-123"));
        assertTrue(json.contains("my-pipeline"));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Ready.class, deserialized);
        PipelineServingMessage.Ready readyResult = (PipelineServingMessage.Ready) deserialized;
        assertEquals("task-123", readyResult.taskId());
        assertEquals(9091, readyResult.port());
    }

    @Test
    void testSubprocessArgsTempFile() throws Exception {
        PipelineServingSubprocessArgs args = new PipelineServingSubprocessArgs(
                "task-456",
                "{\"pipelineId\":\"test\"}",
                PipelineServingSubprocessArgs.MODE_ONE_SHOT,
                "{\"input\":\"hello\"}",
                0,
                null,
                80, 90, 95, 5000L,
                80, 90, 95,
                3000L,
                null
        );

        Path tempFile = args.writeToTempFile();
        try {
            assertTrue(Files.exists(tempFile));
            PipelineServingSubprocessArgs loaded = PipelineServingSubprocessArgs.fromFile(tempFile);
            assertEquals("task-456", loaded.taskId());
            assertEquals(PipelineServingSubprocessArgs.MODE_ONE_SHOT, loaded.executionMode());
            assertEquals("{\"input\":\"hello\"}", loaded.requestDataJson());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testHeartbeatMessage() throws Exception {
        PipelineServingMessage.Heartbeat heartbeat = new PipelineServingMessage.Heartbeat(
                "task-789", 60000L,
                512 * 1024 * 1024L, 8192L * 1024 * 1024,
                256 * 1024 * 1024L,
                2048L * 1024 * 1024, 8192L * 1024 * 1024,
                3, 100L
        );

        String json = mapper.writeValueAsString(heartbeat);
        assertTrue(json.contains("HEARTBEAT"));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Heartbeat.class, deserialized);
        PipelineServingMessage.Heartbeat hb = (PipelineServingMessage.Heartbeat) deserialized;
        assertEquals(3, hb.activeRequests());
        assertEquals(100L, hb.totalRequestsServed());
    }

    @Test
    void testDefaultServingConfig() {
        UnifiedPipelineDefinition.ServingConfig config = UnifiedPipelineDefinition.ServingConfig.builder().build();
        assertEquals("8g", config.getHeapSize());
        assertEquals(0, config.getPort());
        assertEquals(1, config.getReplicas());
        assertEquals(80, config.getMemoryStopPercent());
        assertEquals(90, config.getMemoryCriticalPercent());
        assertEquals(95, config.getMemoryKillPercent());
        assertEquals(3000L, config.getHeartbeatIntervalMs());
        assertEquals(120000L, config.getStaleTimeoutMs());
    }
}

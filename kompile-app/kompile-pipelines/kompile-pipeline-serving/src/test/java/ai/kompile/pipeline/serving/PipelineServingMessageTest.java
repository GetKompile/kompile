package ai.kompile.pipeline.serving;

import ai.kompile.pipeline.serving.subprocess.PipelineServingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests serialization round-trip for all 8 PipelineServingMessage record types.
 */
class PipelineServingMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReadyRoundTrip() throws Exception {
        PipelineServingMessage.Ready msg = new PipelineServingMessage.Ready(
                "task-1", 1500L, "my-pipeline", "LLM", 9091, 12345L
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"READY\""));
        assertTrue(json.contains("\"pipelineId\":\"my-pipeline\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Ready.class, deserialized);
        PipelineServingMessage.Ready ready = (PipelineServingMessage.Ready) deserialized;

        assertEquals("task-1", ready.taskId());
        assertEquals(1500L, ready.startupTimeMs());
        assertEquals("my-pipeline", ready.pipelineId());
        assertEquals("LLM", ready.pipelineKind());
        assertEquals(9091, ready.port());
        assertEquals(12345L, ready.pid());
    }

    @Test
    void testProgressRoundTrip() throws Exception {
        PipelineServingMessage.Progress msg = new PipelineServingMessage.Progress(
                "task-2", "LOADING_MODEL", 45, "Loading weights: layer 12/26"
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"PROGRESS\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Progress.class, deserialized);
        PipelineServingMessage.Progress progress = (PipelineServingMessage.Progress) deserialized;

        assertEquals("task-2", progress.taskId());
        assertEquals("LOADING_MODEL", progress.phase());
        assertEquals(45, progress.progressPercent());
        assertEquals("Loading weights: layer 12/26", progress.message());
    }

    @Test
    void testPhaseTransitionRoundTrip() throws Exception {
        PipelineServingMessage.PhaseTransition msg = new PipelineServingMessage.PhaseTransition(
                "task-3", "INITIALIZING_STEPS", "READY", 2500L
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"PHASE_TRANSITION\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.PhaseTransition.class, deserialized);
        PipelineServingMessage.PhaseTransition pt = (PipelineServingMessage.PhaseTransition) deserialized;

        assertEquals("task-3", pt.taskId());
        assertEquals("INITIALIZING_STEPS", pt.fromPhase());
        assertEquals("READY", pt.toPhase());
        assertEquals(2500L, pt.phaseDurationMs());
    }

    @Test
    void testHeartbeatRoundTrip() throws Exception {
        PipelineServingMessage.Heartbeat msg = new PipelineServingMessage.Heartbeat(
                "task-4", 60000L,
                512 * 1024 * 1024L, 8192L * 1024 * 1024,
                256 * 1024 * 1024L,
                2048L * 1024 * 1024, 8192L * 1024 * 1024,
                3, 150L
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"HEARTBEAT\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Heartbeat.class, deserialized);
        PipelineServingMessage.Heartbeat hb = (PipelineServingMessage.Heartbeat) deserialized;

        assertEquals("task-4", hb.taskId());
        assertEquals(60000L, hb.uptimeMs());
        assertEquals(512 * 1024 * 1024L, hb.heapUsedBytes());
        assertEquals(8192L * 1024 * 1024, hb.heapMaxBytes());
        assertEquals(256 * 1024 * 1024L, hb.offHeapUsedBytes());
        assertEquals(2048L * 1024 * 1024, hb.gpuUsedBytes());
        assertEquals(8192L * 1024 * 1024, hb.gpuMaxBytes());
        assertEquals(3, hb.activeRequests());
        assertEquals(150L, hb.totalRequestsServed());
    }

    @Test
    void testCompletedRoundTrip() throws Exception {
        Map<String, Object> outputData = Map.of(
                "result", "Hello, world!",
                "tokens", 15,
                "latencyMs", 230
        );
        PipelineServingMessage.Completed msg = new PipelineServingMessage.Completed(
                "task-5", "req-001", 250L, outputData
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"COMPLETED\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Completed.class, deserialized);
        PipelineServingMessage.Completed completed = (PipelineServingMessage.Completed) deserialized;

        assertEquals("task-5", completed.taskId());
        assertEquals("req-001", completed.requestId());
        assertEquals(250L, completed.durationMs());
        assertNotNull(completed.outputData());
        assertEquals("Hello, world!", completed.outputData().get("result"));
        assertEquals(15, completed.outputData().get("tokens"));
    }

    @Test
    void testFailedRoundTrip() throws Exception {
        PipelineServingMessage.Failed msg = new PipelineServingMessage.Failed(
                "task-6", "INITIALIZING_STEPS", "Model file not found: /models/test.fb",
                "java.io.FileNotFoundException",
                "java.io.FileNotFoundException: /models/test.fb\n\tat java.io.FileInputStream.<init>"
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"FAILED\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Failed.class, deserialized);
        PipelineServingMessage.Failed failed = (PipelineServingMessage.Failed) deserialized;

        assertEquals("task-6", failed.taskId());
        assertEquals("INITIALIZING_STEPS", failed.phase());
        assertEquals("Model file not found: /models/test.fb", failed.errorMessage());
        assertEquals("java.io.FileNotFoundException", failed.errorType());
        assertTrue(failed.stackTrace().contains("FileInputStream"));
    }

    @Test
    void testRequestResultSuccessRoundTrip() throws Exception {
        Map<String, Object> outputData = Map.of("generated", "text output");
        PipelineServingMessage.RequestResult msg = new PipelineServingMessage.RequestResult(
                "task-7", "req-002", true, 180L, outputData, null
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"REQUEST_RESULT\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.RequestResult.class, deserialized);
        PipelineServingMessage.RequestResult rr = (PipelineServingMessage.RequestResult) deserialized;

        assertEquals("task-7", rr.taskId());
        assertEquals("req-002", rr.requestId());
        assertTrue(rr.success());
        assertEquals(180L, rr.durationMs());
        assertEquals("text output", rr.outputData().get("generated"));
        assertNull(rr.errorMessage());
    }

    @Test
    void testRequestResultFailureRoundTrip() throws Exception {
        PipelineServingMessage.RequestResult msg = new PipelineServingMessage.RequestResult(
                "task-8", "req-003", false, 50L, null, "Input validation failed: missing prompt"
        );

        String json = mapper.writeValueAsString(msg);
        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.RequestResult.class, deserialized);
        PipelineServingMessage.RequestResult rr = (PipelineServingMessage.RequestResult) deserialized;

        assertFalse(rr.success());
        assertNull(rr.outputData());
        assertEquals("Input validation failed: missing prompt", rr.errorMessage());
    }

    @Test
    void testLogRoundTrip() throws Exception {
        PipelineServingMessage.Log msg = new PipelineServingMessage.Log(
                "task-9", "WARN", "ai.kompile.pipeline.serving",
                "GPU memory usage above 80%", 1700000000000L
        );

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"type\":\"LOG\""));

        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Log.class, deserialized);
        PipelineServingMessage.Log log = (PipelineServingMessage.Log) deserialized;

        assertEquals("task-9", log.taskId());
        assertEquals("WARN", log.level());
        assertEquals("ai.kompile.pipeline.serving", log.source());
        assertEquals("GPU memory usage above 80%", log.message());
        assertEquals(1700000000000L, log.timestamp());
    }

    @Test
    void testMessagePrefix() {
        assertEquals("PIPELINE_MSG:", PipelineServingMessage.MESSAGE_PREFIX);
    }

    @Test
    void testCompletedWithNullOutputData() throws Exception {
        PipelineServingMessage.Completed msg = new PipelineServingMessage.Completed(
                "task-null", "req-null", 100L, null
        );

        String json = mapper.writeValueAsString(msg);
        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Completed.class, deserialized);
        assertNull(((PipelineServingMessage.Completed) deserialized).outputData());
    }

    @Test
    void testHeartbeatZeroValues() throws Exception {
        PipelineServingMessage.Heartbeat msg = new PipelineServingMessage.Heartbeat(
                "task-zero", 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L
        );

        String json = mapper.writeValueAsString(msg);
        PipelineServingMessage deserialized = mapper.readValue(json, PipelineServingMessage.class);
        assertInstanceOf(PipelineServingMessage.Heartbeat.class, deserialized);
        PipelineServingMessage.Heartbeat hb = (PipelineServingMessage.Heartbeat) deserialized;
        assertEquals(0, hb.activeRequests());
        assertEquals(0L, hb.totalRequestsServed());
    }
}

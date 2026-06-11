package ai.kompile.pipeline.serving.launcher;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PipelineServingHandleTest {

    @Test
    void testBaseUrl() {
        PipelineServingHandle handle = new PipelineServingHandle(
                "test-pipeline", "LLM", null, 9091, 12345L,
                Instant.now(), "task-1"
        );

        assertEquals("http://localhost:9091", handle.baseUrl());
    }

    @Test
    void testBaseUrlDifferentPort() {
        PipelineServingHandle handle = new PipelineServingHandle(
                "test-pipeline", "VLM", null, 8080, 99999L,
                Instant.now(), "task-2"
        );

        assertEquals("http://localhost:8080", handle.baseUrl());
    }

    @Test
    void testIsAliveWithNullProcess() {
        PipelineServingHandle handle = new PipelineServingHandle(
                "test-pipeline", "GENERIC", null, 9091, 0L,
                Instant.now(), "task-3"
        );

        assertFalse(handle.isAlive());
    }

    @Test
    void testRecordAccessors() {
        Instant now = Instant.now();
        PipelineServingHandle handle = new PipelineServingHandle(
                "my-pipeline", "RAG", null, 9090, 42L,
                now, "task-42"
        );

        assertEquals("my-pipeline", handle.pipelineId());
        assertEquals("RAG", handle.kind());
        assertNull(handle.process());
        assertEquals(9090, handle.port());
        assertEquals(42L, handle.pid());
        assertEquals(now, handle.startedAt());
        assertEquals("task-42", handle.taskId());
    }
}

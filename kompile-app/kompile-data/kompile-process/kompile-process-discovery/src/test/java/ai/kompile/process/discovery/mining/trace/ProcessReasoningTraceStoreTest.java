/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.process.discovery.mining.trace;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessReasoningTraceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndLoadsVersionedJsonAtomically() throws Exception {
        ProcessReasoningTraceStore store = new ProcessReasoningTraceStore(tempDir);
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.fact("observed", 0.9, "source"));

        store.save("suggestion-1", trace);

        assertEquals(trace.steps(), store.get("suggestion-1").orElseThrow().steps());
        assertTrue(Files.readString(tempDir.resolve("suggestion-1.json"))
                .contains("\"formatVersion\":1"));
    }

    @Test
    void legacySerializedFilesRemainInertAndUnsafeIdsAreRejected() throws Exception {
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.fact("legacy", 1.0, "source"));
        try (ObjectOutputStream out = new ObjectOutputStream(
                Files.newOutputStream(tempDir.resolve("legacy.ser")))) {
            out.writeObject(trace);
        }
        ProcessReasoningTraceStore store = new ProcessReasoningTraceStore(tempDir);

        assertTrue(store.get("legacy").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.save("../escape", trace));
    }

    @Test
    void deleteIsDurableAndIdempotent() {
        ProcessReasoningTraceStore store = new ProcessReasoningTraceStore(tempDir);
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.fact("x", 1.0, "source"));
        store.save("suggestion-1", trace);

        store.delete("suggestion-1");
        store.delete("suggestion-1");

        assertTrue(store.get("suggestion-1").isEmpty());
    }
}

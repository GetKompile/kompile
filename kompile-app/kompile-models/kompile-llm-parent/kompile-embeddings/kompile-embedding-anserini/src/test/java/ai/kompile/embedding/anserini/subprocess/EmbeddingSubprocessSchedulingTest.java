/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.embedding.anserini.subprocess;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the embedding subprocess request scheduler
 * ({@link EmbeddingSubprocessMain#priorityFor} / {@link EmbeddingSubprocessMain#isControlPlane}).
 *
 * <p>These pin the fast-lane semantics that keep a giant crawl {@code EmbedBatchRequest}
 * from head-of-line-blocking interactive single-text {@code EmbedRequest}s and status
 * queries: the reader thread only classifies + enqueues, a single compute worker drains
 * a priority lane, and status is answered inline off the compute path.</p>
 *
 * <p>No ND4J init, no subprocess, and no model load — only the static classifier methods
 * (widened to package-private, {@code // visible for testing}). The default
 * {@code optimalBatchSize} (32) applies since {@code main()} never runs.</p>
 */
class EmbeddingSubprocessSchedulingTest {

    private static EmbeddingSubprocessMessage.EmbedRequest embed(String text) {
        return new EmbeddingSubprocessMessage.EmbedRequest("r", text);
    }

    private static EmbeddingSubprocessMessage.EmbedBatchRequest batch(int n) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            texts.add("t" + i);
        }
        return new EmbeddingSubprocessMessage.EmbedBatchRequest("r", texts);
    }

    @Test
    void singleEmbedRidesFastLaneAheadOfLargeBatch() {
        // A single interactive embed must sort strictly ahead of a large throughput batch
        // (lower priority value = drained first by the PriorityBlockingQueue).
        assertTrue(EmbeddingSubprocessMain.priorityFor(embed("hello"))
                        < EmbeddingSubprocessMain.priorityFor(batch(512)),
                "single embed should preempt a large batch");
    }

    @Test
    void smallBatchRidesFastLaneWithSingleEmbeds() {
        // Batches at/under the optimal size (default 32) are cheap enough for the fast lane.
        assertEquals(EmbeddingSubprocessMain.priorityFor(embed("hello")),
                EmbeddingSubprocessMain.priorityFor(batch(8)),
                "small batch should share the fast lane with single embeds");
        assertTrue(EmbeddingSubprocessMain.priorityFor(batch(8))
                        < EmbeddingSubprocessMain.priorityFor(batch(512)),
                "small batch should outrank a large batch");
    }

    @Test
    void shutdownPreemptsAllQueuedComputeWork() {
        EmbeddingSubprocessMessage.ShutdownRequest shutdown =
                new EmbeddingSubprocessMessage.ShutdownRequest("r");
        assertTrue(EmbeddingSubprocessMain.priorityFor(shutdown)
                        < EmbeddingSubprocessMain.priorityFor(embed("hello")),
                "graceful shutdown should preempt queued embeds");
        assertTrue(EmbeddingSubprocessMain.priorityFor(shutdown)
                        < EmbeddingSubprocessMain.priorityFor(batch(512)),
                "graceful shutdown should preempt queued batches");
    }

    @Test
    void onlyStatusIsHandledInlineOffTheComputePath() {
        assertTrue(EmbeddingSubprocessMain.isControlPlane(
                new EmbeddingSubprocessMessage.StatusRequest("r")));
        // Everything that touches the encoder must go through the serial compute worker.
        assertFalse(EmbeddingSubprocessMain.isControlPlane(embed("hello")));
        assertFalse(EmbeddingSubprocessMain.isControlPlane(batch(4)));
        assertFalse(EmbeddingSubprocessMain.isControlPlane(
                new EmbeddingSubprocessMessage.ShutdownRequest("r")));
    }
}

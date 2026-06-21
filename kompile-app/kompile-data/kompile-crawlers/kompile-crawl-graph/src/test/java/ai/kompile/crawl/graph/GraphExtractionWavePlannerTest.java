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

package ai.kompile.crawl.graph;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GraphExtractionOrchestrator#planGraphWave}: the greedy per-wave packer that
 * fills batches up to the adaptive char budget, capped by the item-count safety limit, advancing a
 * shared cursor and stamping global batch indices.
 */
class GraphExtractionWavePlannerTest {

    private static RetrievedDoc doc(String id, int chars) {
        return new RetrievedDoc(id, "x".repeat(chars), new HashMap<>());
    }

    @Test
    void packsChunksUpToCharBudgetAcrossTheWaveAndAdvancesCursor() {
        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        List<RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) docs.add(doc("d" + i, 1_000)); // 10 chunks × 1000 chars
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger gidx = new AtomicInteger(0);

        // budget 3000 chars, maxItems 16, waveWidth 2 → 3 chunks/batch, 2 batches (6 chunks) this wave.
        var wave = orch.planGraphWave(docs, cursor, 3_000, 16, 2, gidx);

        assertEquals(2, wave.size());
        assertEquals(3, wave.get(0).items().size());
        assertEquals(3, wave.get(1).items().size());
        assertEquals(3_000L, wave.get(0).cost());
        assertEquals(6, cursor.get());          // cursor advanced past consumed chunks
        assertEquals(1, wave.get(0).index());    // global, monotonically increasing indices
        assertEquals(2, wave.get(1).index());
    }

    @Test
    void itemCountSafetyCapBindsBeforeAGenerousCharBudget() {
        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        List<RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) docs.add(doc("d" + i, 100)); // tiny chunks
        AtomicInteger cursor = new AtomicInteger(0);

        // Huge char budget but maxItems 2 → batch capped at 2 chunks.
        var wave = orch.planGraphWave(docs, cursor, 1_000_000, 2, 1, new AtomicInteger(0));

        assertEquals(1, wave.size());
        assertEquals(2, wave.get(0).items().size());
        assertEquals(2, cursor.get());
    }

    @Test
    void aChunkLargerThanTheBudgetStillGetsItsOwnBatch() {
        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        List<RetrievedDoc> docs = List.of(doc("big", 50_000)); // larger than the budget
        AtomicInteger cursor = new AtomicInteger(0);

        var wave = orch.planGraphWave(docs, cursor, 10_000, 16, 4, new AtomicInteger(0));

        assertEquals(1, wave.size());
        assertEquals(1, wave.get(0).items().size()); // never dropped
        assertEquals(1, cursor.get());
    }

    @Test
    void emptyRemainingYieldsAnEmptyWave() {
        GraphExtractionOrchestrator orch = new GraphExtractionOrchestrator();
        List<RetrievedDoc> docs = List.of(doc("only", 100));
        AtomicInteger cursor = new AtomicInteger(1); // already past the end

        var wave = orch.planGraphWave(docs, cursor, 10_000, 16, 4, new AtomicInteger(0));

        assertEquals(0, wave.size());
    }
}

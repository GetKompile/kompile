/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.embedding.impl;

import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.Triple;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded reproduction of the TransE native-memory leak.
 *
 * <p>Uses 500 triples (vs 73,500 in production), dim=100 (same), batchSize=256 (same),
 * negativeSamples=10 (same), but ONLY 3 epochs with per-batch RSS written to
 * /tmp/kge-rss-trace.log.  The test asserts that RSS does not grow by more than 1 GB
 * across the measured batches — a growth larger than that indicates the workspace
 * is not being reclaimed.</p>
 *
 * <p>This test is intentionally NOT annotated @SpringBootTest; it runs the model
 * directly so there is no Spring context overhead.  The surefire JVM for this module
 * carries nd4j-native on the test classpath.</p>
 *
 * <p><b>Safety</b>: bounded to 3 epochs × at most 2 batches of triples (500 / 256 = 2
 * batches per epoch).  Peak native RSS from this test is dominated by the workspace
 * initialSize — at most a few hundred MB above the ND4J baseline.</p>
 */
class TransEMemoryLeakReproTest {

    private static final Path TRACE_FILE = Paths.get("/tmp/kge-rss-trace.log");

    /** Number of synthetic triples — small enough to finish in seconds, large enough to see the batch pattern. */
    private static final int NUM_TRIPLES = 500;
    /** Use the same dim/batchSize/neg as production so workspace allocation is realistic. */
    private static final int DIM = 100;
    private static final int BATCH_SIZE = 256;
    private static final int NEG_SAMPLES = 10;
    /** Run enough epochs to distinguish linear growth (leak) from a plateau (bounded). */
    private static final int EPOCHS = 15;

    @Test
    void rss_does_not_grow_unbounded_across_batches() throws IOException {
        // Force-initialize ND4J once so its JNI bootstrap is not charged to the first batch.
        Nd4j.create(1);
        System.gc();
        long baselineRss = Pointer.physicalBytes() >> 20; // MB
        log("BASELINE", -1, -1, baselineRss, Pointer.totalBytes() >> 20);

        List<Triple> triples = generateTriples(NUM_TRIPLES);

        // Instrument callback writes per-epoch RSS to the trace file.
        List<Long> perEpochRss = new ArrayList<>();
        KGEmbeddingConfig config = KGEmbeddingConfig.builder()
                .embeddingDim(DIM)
                .epochs(EPOCHS)
                .learningRate(0.01)
                .batchSize(BATCH_SIZE)
                .margin(1.0)
                .negativeSamples(NEG_SAMPLES)
                .normalizeEntities(true)
                .progressCallback(p -> {
                    long rssMb   = Pointer.physicalBytes() >> 20;
                    long offMb   = Pointer.totalBytes()    >> 20;
                    perEpochRss.add(rssMb);
                    try {
                        log("EPOCH", p.epoch(), p.totalEpochs(), rssMb, offMb);
                    } catch (IOException ignored) { }
                })
                .build();

        TransEModel model = new TransEModel();
        model.train(triples, config);

        long finalRss = Pointer.physicalBytes() >> 20;
        log("FINAL", EPOCHS, EPOCHS, finalRss, Pointer.totalBytes() >> 20);

        // Assert: RSS growth from baseline to end must be below 2 GB (generous headroom for ND4J init).
        // If the workspace is leaking 665 MB per batch, 6 batches = 3.9 GB → this assertion fails.
        long growthMb = finalRss - baselineRss;
        String msg = String.format(
                "RSS grew by %d MB (baseline=%d MB, final=%d MB). "
                        + "Check /tmp/kge-rss-trace.log for per-batch breakdown. "
                        + "A growth >2 GB indicates the workspace is not being reclaimed.",
                growthMb, baselineRss, finalRss);
        assertTrue(growthMb < 2048, msg);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Triple> generateTriples(int count) {
        List<Triple> triples = new ArrayList<>(count);
        int numEntities   = Math.max(100, count / 3);
        int numRelations  = 10;
        for (int i = 0; i < count; i++) {
            String head     = "entity_" + (i % numEntities);
            String relation = "rel_" + (i % numRelations);
            String tail     = "entity_" + ((i + 1) % numEntities);
            // Avoid self-loops
            if (head.equals(tail)) tail = "entity_" + ((i + 2) % numEntities);
            triples.add(new Triple(head, relation, tail));
        }
        return triples;
    }

    private static void log(String phase, int epoch, int total, long rssMb, long offMb) throws IOException {
        String line = String.format("[KGE-REPRO] phase=%-12s epoch=%3d/%d rssMB=%6d offHeapMB=%6d%n",
                phase, epoch, total, rssMb, offMb);
        Files.writeString(TRACE_FILE, line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

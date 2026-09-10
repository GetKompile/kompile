/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.learning.subprocess;

import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphKgeLifecycle;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableGraphLearningJobTest {
    @TempDir
    Path tempDir;

    @Test
    void learnsCompletePortableArchiveAndPreservesUnrelatedArtifacts() throws Exception {
        Path input = tempDir.resolve("input.kgraph");
        Path output = tempDir.resolve("output.kgraph");
        new UnifiedGraph()
                .graphId("portable-test")
                .factSheetId(42L)
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("acme", "COMPANY", "Acme")
                .addRelation("r1", "alice", "acme", "WORKS_AT", 0.8)
                .putEntityVector("semantic", "alice", new double[]{0.2, 0.8})
                .putEntityOpinion("alice", Opinion.fromSoftTruth(0.75))
                .putArtifactText("custom/keep.txt", "keep-me")
                .save(input);

        PortableGraphLearningJob.Result result = PortableGraphLearningJob.run(args(input, output));
        UnifiedGraph learned = UnifiedGraph.load(output);

        assertEquals(2, learned.entityCount());
        assertEquals(1, learned.relationCount());
        assertEquals("keep-me", learned.artifactText("custom/keep.txt"));
        assertArrayEquals(new double[]{0.2, 0.8},
                learned.vectorLayer("semantic").get("alice"), 1e-6);
        assertNotNull(learned.entityOpinion("alice"));
        assertNotNull(learned.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER));
        assertNotNull(learned.artifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT));
        assertNotNull(learned.artifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT));
        assertNotNull(learned.artifactText(UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT));
        assertEquals("SUBPROCESS", learned.meta().get("learning.execution"));
        assertTrue(result.embedding().enabled());
        assertTrue(result.reasoning().enabled());
    }

    @Test
    void rejectsUnknownProtocolBeforeWritingOutput() throws Exception {
        Path input = tempDir.resolve("input-invalid.kgraph");
        Path output = tempDir.resolve("output-invalid.kgraph");
        new UnifiedGraph().graphId("invalid").addEntity("a", "NODE", "A").save(input);
        PortableGraphLearningSubprocessArgs valid = args(input, output);
        PortableGraphLearningSubprocessArgs invalid = new PortableGraphLearningSubprocessArgs(
                99, valid.operation(), valid.crawlJobId(), valid.factSheetId(),
                valid.inputGraphPath(), valid.outputGraphPath(), valid.embeddingEnabled(),
                valid.embeddingAlgorithm(), valid.embeddingDim(), valid.embeddingEpochs(),
                valid.embeddingLearningRate(), valid.embeddingWarmStartEpochs(), valid.embeddingSeed(),
                valid.reasoningEnabled(), valid.pslSteps(), valid.mebnEpochs(),
                valid.consensusRounds(), valid.consensusWeight(), valid.maxRelationTypes());

        assertThrows(IllegalArgumentException.class, () -> PortableGraphLearningJob.run(invalid));
    }

    private PortableGraphLearningSubprocessArgs args(Path input, Path output) {
        return new PortableGraphLearningSubprocessArgs(
                PortableGraphLearningSubprocessArgs.PROTOCOL_VERSION,
                PortableGraphLearningSubprocessArgs.OPERATION,
                "crawl-1", 42L, input.toString(), output.toString(),
                true, "TRANSE", 4, 1, 0.05, 1, 1234L,
                true, 1, 1, 1, 0.35, 25);
    }
}

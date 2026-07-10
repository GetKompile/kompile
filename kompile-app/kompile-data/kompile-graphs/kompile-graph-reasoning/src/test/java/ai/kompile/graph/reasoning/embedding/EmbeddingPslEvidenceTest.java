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
package ai.kompile.graph.reasoning.embedding;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate tests for {@link EmbeddingPslEvidence} (§6.1 — embeddings as PSL evidence).
 *
 * <h3>Design of the embedding fixture</h3>
 * <p>Rather than training a full node2vec or RotatE model (which adds latency and
 * non-determinism from ND4J), the tests hand-construct an {@link EmbeddingTable} whose
 * vectors encode a clear cluster structure:</p>
 * <ul>
 *   <li>{@code seed}   — high-cosine with {@code target} (close cluster)</li>
 *   <li>{@code target} — to be inferred; similar to {@code seed}</li>
 *   <li>{@code outlier} — orthogonal to {@code seed}/{@code target} (separate cluster)</li>
 * </ul>
 * <p>The cosine between {@code seed} and {@code target} is deliberately &ge; 0.9, while
 * {@code outlier} is orthogonal (cosine &asymp; 0). After calling
 * {@link EmbeddingPslEvidence#addSimilarityEvidence} and running
 * {@link HlMrfMapInference#solve}, the target's inferred {@code Label} value must be
 * elevated toward the seed's observed label because of the high-similarity atom.</p>
 *
 * <h3>PSL program structure</h3>
 * <pre>
 *   // Evidence: embedding-similarity atoms (injected by EmbeddingPslEvidence)
 *   observe: similar(seed, target) = cosine &ge; 0.9
 *   observe: similar(target, seed) = cosine &ge; 0.9
 *   observe: similar(seed, outlier) &asymp; 0.0  (below threshold — NOT added)
 *
 *   // Propagation rule: similarity acts as soft bridge for labels
 *   5.0: similar(X, Y) &amp; Label(X) -&gt; Label(Y) ^2
 *
 *   // Seed is observed labelled
 *   observe: Label(seed) = 1.0
 *
 *   // Target and outlier are inference targets (prior 0.0 — uninitialised)
 *   target: Label(target)
 *   target: Label(outlier)
 * </pre>
 * <p>Expected outcome:
 * <ul>
 *   <li>{@code Label(target) &gt; 0.5} — pulled toward 1.0 by similarity to seed</li>
 *   <li>{@code Label(outlier) &le; 0.5} — no similarity evidence, stays near prior</li>
 *   <li>Atom count &gt; 0 — similarity atoms were actually added</li>
 * </ul>
 */
class EmbeddingPslEvidenceTest {

    // ── Entity ids ────────────────────────────────────────────────────────────
    private static final String SEED    = "seed";
    private static final String TARGET  = "target";
    private static final String OUTLIER = "outlier";
    private static final String PRED    = "similar";

    // ── Handcrafted embedding fixture ─────────────────────────────────────────

    /**
     * Construct an {@link EmbeddingTable} where:
     * <ul>
     *   <li>{@code seed} and {@code target} are nearly co-linear — cosine &ge; 0.9</li>
     *   <li>{@code outlier} is orthogonal to both — cosine &asymp; 0.0</li>
     * </ul>
     */
    private static EmbeddingTable buildClusteredTable() {
        // Use 4-dimensional vectors for legibility.
        // seed:    [1, 0, 0, 0]         (unit x-axis)
        // target:  [0.9, 0.436, 0, 0]   (nearly co-linear; cosine(seed,target)=0.9)
        // outlier: [0, 0, 1, 0]          (y-axis; orthogonal to seed and target)
        List<String> ids = Arrays.asList(SEED, TARGET, OUTLIER);
        EmbeddingTable table = new EmbeddingTable(ids, 4, 0L);
        double[] vSeed    = table.vector(SEED);
        double[] vTarget  = table.vector(TARGET);
        double[] vOutlier = table.vector(OUTLIER);

        // Overwrite the randomly-initialised vectors with our known-geometry vectors.
        // EmbeddingTable.vector() returns the LIVE row, so in-place modification is the
        // documented pattern (see EmbeddingTable Javadoc).
        vSeed[0]    = 1.0; vSeed[1]    = 0.0; vSeed[2]    = 0.0; vSeed[3]    = 0.0;
        vTarget[0]  = 0.9; vTarget[1]  = 0.436; vTarget[2] = 0.0; vTarget[3] = 0.0;
        vOutlier[0] = 0.0; vOutlier[1] = 0.0; vOutlier[2] = 1.0; vOutlier[3] = 0.0;

        return table;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — GATE: atoms added > 0 and inferred Label(target) is elevated
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * THE decisive embedding-PSL bridge gate.
     *
     * <p>Asserts two things:
     * <ol>
     *   <li>Similarity atoms were added to the program (returned count &gt; 0).</li>
     *   <li>The target entity's inferred Label value is elevated above 0.5 (closer to
     *       the seed's label of 1.0 than its prior of 0.0) because the embedding-similarity
     *       evidence drives the PSL rule {@code similar(X,Y) &amp; Label(X) -&gt; Label(Y)}.</li>
     * </ol>
     * </p>
     *
     * <p>The assertion is deliberately loose (Label(target) &gt; 0.5) rather than requiring
     * a precise value: the PSL solver is a continuous optimiser, and the exact result
     * depends on the rule weight and solver tolerance. The structural invariant —
     * embedding similarity causes label propagation — is the important property.</p>
     */
    @Test
    void embeddingSimilarityElevatesInferredLabel() {
        EmbeddingTable table = buildClusteredTable();

        // Verify our fixture geometry before building the program
        double cosineSeedTarget  = Embeddings.cosine(table.vector(SEED),    table.vector(TARGET));
        double cosineSeedOutlier = Embeddings.cosine(table.vector(SEED),    table.vector(OUTLIER));
        assertTrue(cosineSeedTarget  >= 0.85, "Fixture: seed-target cosine must be >= 0.85, got " + cosineSeedTarget);
        assertTrue(cosineSeedOutlier <= 0.15, "Fixture: seed-outlier cosine must be <= 0.15, got " + cosineSeedOutlier);

        // Build PSL program
        PslProgram program = new PslProgram();
        // The propagation rule: similarity acts as a soft bridge for labels
        program.addRule("5.0: " + PRED + "(X, Y) & Label(X) -> Label(Y) ^2");
        // Seed is a labelled anchor
        program.observe("Label", 1.0, SEED);
        // Declare similar as closed so absent atoms default to 0 (not pruning the rule)
        program.declareClosed(PRED, 2);
        // Inference targets
        program.target("Label", TARGET);
        program.target("Label", OUTLIER);

        // Inject embedding-similarity evidence with threshold 0.5
        // — seed-target pair qualifies (cosine >= 0.9), seed-outlier does not (cosine ~= 0)
        int atomsAdded = EmbeddingPslEvidence.addSimilarityEvidence(program, table, PRED, 0.5);

        // Gate (a): at least some atoms were added
        assertTrue(atomsAdded > 0, "addSimilarityEvidence must add at least one atom, got 0");
        System.out.printf("[EmbeddingPslEvidenceTest] atoms added=%d (threshold=0.5)%n", atomsAdded);

        // Run HL-MRF MAP inference
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        Map<String, Double> values = result.values();

        double labelTarget  = values.getOrDefault("Label(target)",  0.0);
        double labelOutlier = values.getOrDefault("Label(outlier)", 0.0);
        double labelSeed    = values.getOrDefault("Label(seed)",    0.0); // should stay 1.0 (observed)

        System.out.printf(
                "[EmbeddingPslEvidenceTest] Label(seed)=%.4f  Label(target)=%.4f  Label(outlier)=%.4f%n",
                labelSeed, labelTarget, labelOutlier);

        // Gate (b): target is elevated above 0.5 (embedding similarity pulled it toward seed's label)
        assertTrue(labelTarget > 0.5,
                "Label(target) must be elevated above 0.5 by embedding-similarity evidence. "
                + "Got Label(target)=" + labelTarget + " — embedding bridge is not influencing PSL.");

        // Gate (c): target is more labelled than outlier (similarity was specific to seed-target pair)
        assertTrue(labelTarget > labelOutlier,
                "Label(target) must be higher than Label(outlier) because only target is "
                + "similar to the seed. target=" + labelTarget + " outlier=" + labelOutlier);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — addSimilarityEvidence: threshold=0.0 captures all pairs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void thresholdZeroCapturesAllPairs() {
        EmbeddingTable table = buildClusteredTable();
        PslProgram program = new PslProgram();

        // With threshold 0.0, every non-self pair with positive cosine should be added.
        // For 3 entities: maximum possible directed pairs = 3*(3-1) = 6
        int atomsAdded = EmbeddingPslEvidence.addSimilarityEvidence(program, table, PRED, 0.0);

        // Seed–target (cosine ~0.9) contributes both directions.
        // Outlier has orthogonal cosine ~0.0 with seed and target — may not reach threshold=0.0
        // if cosine is exactly 0.0 (the method skips cosine < threshold).
        // At minimum the seed-target pair (2 atoms) must be present.
        assertTrue(atomsAdded >= 2,
                "At least seed-target bidirectional atoms must be added at threshold=0.0, got " + atomsAdded);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — addSimilarityEvidence: threshold=1.1 captures nothing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void thresholdAboveOneAddsNoAtoms() {
        EmbeddingTable table = buildClusteredTable();
        PslProgram program = new PslProgram();

        int atomsAdded = EmbeddingPslEvidence.addSimilarityEvidence(program, table, PRED, 1.1);

        assertEquals(0, atomsAdded,
                "threshold > 1.0 should add no atoms (cosine is always <= 1.0). Got: " + atomsAdded);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — addKnnSimilarityEvidence: k=1 adds exactly seed-target pair
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void knnEvidenceWithK1AddsSeedTargetPair() {
        EmbeddingTable table = buildClusteredTable();

        // Verify geometry: seed's nearest neighbour must be target (not outlier)
        double cosineSeedTarget  = Embeddings.cosine(table.vector(SEED), table.vector(TARGET));
        double cosineSeedOutlier = Embeddings.cosine(table.vector(SEED), table.vector(OUTLIER));
        assertTrue(cosineSeedTarget > cosineSeedOutlier,
                "Fixture: seed must be closer to target than to outlier");

        PslProgram program = new PslProgram();
        int atomsAdded = EmbeddingPslEvidence.addKnnSimilarityEvidence(program, table, PRED, 1);

        // k=1: each entity contributes 2 atoms (both directions to its nearest neighbour).
        // 3 entities × 2 directions = up to 6 atoms, but symmetric pairs count separately.
        assertTrue(atomsAdded >= 2,
                "k=1 must add at least 2 atoms (seed-target bidirectional), got " + atomsAdded);

        // The seed-target similar atom must be present in the program.
        // PslAtom.key() format: "predicate(arg1, arg2)" — note the space after comma.
        assertTrue(program.contains(PRED + "(seed, target)"),
                "similar(seed, target) must be in the program after k=1 knn evidence");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — addKnnSimilarityEvidence: k<1 throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void knnWithKLessThanOneThrows() {
        EmbeddingTable table = buildClusteredTable();
        PslProgram program = new PslProgram();
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingPslEvidence.addKnnSimilarityEvidence(program, table, PRED, 0),
                "k=0 should throw IllegalArgumentException");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 — self-pairs are never added
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void selfPairsAreNeverAdded() {
        EmbeddingTable table = buildClusteredTable();
        PslProgram program = new PslProgram();

        EmbeddingPslEvidence.addSimilarityEvidence(program, table, PRED, 0.0);

        // None of the self-pair atoms should appear.
        // PslAtom.key() format: "predicate(arg1, arg2)" with a space after the comma.
        for (String id : Arrays.asList(SEED, TARGET, OUTLIER)) {
            String selfKey = PRED + "(" + id + ", " + id + ")";
            assertTrue(!program.contains(selfKey),
                    "Self-pair atom '" + selfKey + "' must NOT be added");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7 (WP1a) — anti-correlated pairs are observed as 0.0, never negative
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * WP1a: a negative cosine means "no evidence", not "negative evidence". When a below-threshold
     * pair qualifies (here the threshold is set to −1 so the anti-correlated pair passes the gate),
     * the observed soft-truth must be clamped to {@code 0.0} — PSL soft-truth lives in [0,1], and a
     * raw cosine ∈ [−1,1] would violate that domain.
     */
    @Test
    void antiCorrelatedPairObservedAsZeroNeverNegative() {
        List<String> ids = Arrays.asList(SEED, "anti");
        EmbeddingTable table = new EmbeddingTable(ids, 4, 0L);
        double[] vSeed = table.vector(SEED);
        double[] vAnti = table.vector("anti");
        vSeed[0] = 1.0;  vSeed[1] = 0.0; vSeed[2] = 0.0; vSeed[3] = 0.0;
        vAnti[0] = -1.0; vAnti[1] = 0.0; vAnti[2] = 0.0; vAnti[3] = 0.0;

        // cosine(seed, anti) = -1.0; a permissive threshold of -1 makes the pair qualify.
        assertEquals(-1.0, Embeddings.cosine(vSeed, vAnti), 1e-9);

        PslProgram program = new PslProgram();
        int added = EmbeddingPslEvidence.addSimilarityEvidence(program, table, PRED, -1.0);

        assertTrue(added >= 1, "anti-correlated pair should still be gated in at threshold -1");
        String key = PRED + "(seed, anti)";
        assertTrue(program.contains(key), "atom must be present: " + key);
        // The decisive WP1a invariant: observed value clamped to 0.0, NOT the raw -1.0.
        assertEquals(0.0, program.value(key), 1e-12,
                "anti-correlation must be observed as 0.0 (no evidence), never negative");
    }
}

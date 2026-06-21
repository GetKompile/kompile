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
package ai.kompile.graph.reasoning.embedding.learn;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RotatELearner} — RotatE knowledge-graph embedding on ND4J SameDiff.
 *
 * <h3>Knowledge graph fixture</h3>
 * <p>Small, clearly-structured KG used in the scoring/loss tests:</p>
 * <pre>
 *   Alice  --[KNOWS]--> Bob
 *   Bob    --[KNOWS]--> Carol
 *   Alice  --[KNOWS]--> Carol
 *   Alice  --[WORKS_AT]--> CompanyX
 *   Bob    --[WORKS_AT]--> CompanyX
 *   Carol  --[WORKS_AT]--> CompanyY
 * </pre>
 * <p>Two relation types: {@code KNOWS} (person graph) and {@code WORKS_AT} (employment).
 * The correct tail for {@code (Alice, KNOWS, ?)} is {@code Bob} or {@code Carol}, not
 * {@code CompanyX} or {@code CompanyY}. After training the true tail should score lower
 * distance than a corrupted tail from a different semantic cluster.</p>
 */
class RotatELearnerTest {

    // ── Entity ids ────────────────────────────────────────────────────────────
    private static final String ALICE    = "Alice";
    private static final String BOB      = "Bob";
    private static final String CAROL    = "Carol";
    private static final String COMPANY_X = "CompanyX";
    private static final String COMPANY_Y = "CompanyY";

    private static final String KNOWS    = "KNOWS";
    private static final String WORKS_AT = "WORKS_AT";

    /** Config with enough epochs to learn clear separation on the toy KG. */
    private static final RotatEConfig TRAIN_CFG = new RotatEConfig(
            16,     // dim
            4.0,    // margin γ
            4,      // negSamples K
            80,     // epochs — plenty for toy KG
            0.02,   // learningRate
            0.1,    // initRange
            42L,    // seed
            4       // batchSize
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture builder
    // ─────────────────────────────────────────────────────────────────────────

    private MutableReasoningGraph buildKg() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(ALICE,     "Person",  "Alice");
        g.addEntity(BOB,       "Person",  "Bob");
        g.addEntity(CAROL,     "Person",  "Carol");
        g.addEntity(COMPANY_X, "Company", "CompanyX");
        g.addEntity(COMPANY_Y, "Company", "CompanyY");

        g.addRelation("r1", ALICE,  BOB,       KNOWS,    1.0);
        g.addRelation("r2", BOB,    CAROL,     KNOWS,    1.0);
        g.addRelation("r3", ALICE,  CAROL,     KNOWS,    1.0);
        g.addRelation("r4", ALICE,  COMPANY_X, WORKS_AT, 1.0);
        g.addRelation("r5", BOB,    COMPANY_X, WORKS_AT, 1.0);
        g.addRelation("r6", CAROL,  COMPANY_Y, WORKS_AT, 1.0);
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — True triple scores lower distance than corrupted triple
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * THE decisive correctness gate.
     *
     * <p>After training on the structured KG, the true tail {@code (Alice, KNOWS, Bob)} must
     * score a strictly lower RotatE distance than the corrupted tail
     * {@code (Alice, KNOWS, CompanyX)}. CompanyX is a "Company" entity, not a "Person", so it
     * is structurally wrong as the target of a KNOWS relation.</p>
     *
     * <p>If RotatE's complex rotation fails to separate these (e.g. gradients are zero or NaN),
     * this test fails. Do not weaken this gate.</p>
     */
    @Test
    void trueTailScoresLowerDistanceThanCorruptedTail() {
        MutableReasoningGraph kg = buildKg();
        RotatELearner learner = new RotatELearner(TRAIN_CFG);
        RotatELearner.TrainedRotatE model = learner.train(kg, TRAIN_CFG);

        double dTrue    = model.score(ALICE, KNOWS, BOB);
        double dCorrupt = model.score(ALICE, KNOWS, COMPANY_X);

        assertFiniteAndNonNegative(dTrue,    "true-triple distance (Alice, KNOWS, Bob)");
        assertFiniteAndNonNegative(dCorrupt, "corrupt-triple distance (Alice, KNOWS, CompanyX)");

        System.out.printf("[RotatELearnerTest] true-dist=%.4f  corrupt-dist=%.4f  gap=%.4f%n",
                dTrue, dCorrupt, dCorrupt - dTrue);

        assertTrue(dTrue < dCorrupt,
                "RotatE true triple must score LOWER distance than corrupted triple. "
                + "true=(" + ALICE + "," + KNOWS + "," + BOB + ")=" + dTrue
                + "  corrupt=(" + ALICE + "," + KNOWS + "," + COMPANY_X + ")=" + dCorrupt
                + ". Check SameDiff graph wiring and gradient flow.");
    }

    /**
     * Secondary corrupt-triple check using the second relation type.
     *
     * <p>For {@code (Alice, WORKS_AT, CompanyX)}, the true tail is {@code CompanyX}.
     * A person ({@code Bob}) is a corrupted tail for this relation. After training, the
     * true company must score lower distance than the wrong-type person.</p>
     */
    @Test
    void trueTripleScoresLowerForWorksAtRelation() {
        MutableReasoningGraph kg = buildKg();
        RotatELearner learner = new RotatELearner(TRAIN_CFG);
        RotatELearner.TrainedRotatE model = learner.train(kg, TRAIN_CFG);

        double dTrue    = model.score(ALICE, WORKS_AT, COMPANY_X);
        double dCorrupt = model.score(ALICE, WORKS_AT, BOB);

        assertFiniteAndNonNegative(dTrue,    "true-triple distance (Alice, WORKS_AT, CompanyX)");
        assertFiniteAndNonNegative(dCorrupt, "corrupt-triple distance (Alice, WORKS_AT, Bob)");

        System.out.printf("[RotatELearnerTest] WORKS_AT true-dist=%.4f  corrupt-dist=%.4f  gap=%.4f%n",
                dTrue, dCorrupt, dCorrupt - dTrue);

        assertTrue(dTrue < dCorrupt,
                "RotatE true triple must score LOWER distance than corrupted triple for WORKS_AT. "
                + "true=" + dTrue + "  corrupt=" + dCorrupt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — The model learns: true-vs-corrupt gap grows with more training
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The SameDiff autodiff graph is correctly wired: the true-vs-corrupt margin gap must
     * grow with more training.
     *
     * <h3>Why gap (not absolute distance) is the right RotatE metric</h3>
     * <p>In RotatE with self-adversarial negative sampling, the loss minimises
     * {@code -log σ(γ − d_pos) − (1/K) Σ log σ(d_neg − γ)}. The optimiser can
     * simultaneously increase {@code d_neg} (push corrupt triples apart) and decrease
     * {@code d_pos} (pull true triples together), OR it can keep {@code d_pos} roughly
     * constant and only increase {@code d_neg}. Either trajectory reduces the loss.
     * The invariant that must hold is:</p>
     * <pre>
     *   (d_neg_corrupt − d_pos_true) grows with more training
     * </pre>
     * <p>If gradients are zero, NaN, or reversed, both distances stay flat or the gap
     * shrinks. A growing gap proves autodiff + Adam are correctly driving RotatE learning.</p>
     */
    @Test
    void trueVsCorruptGapGrowsWithMoreTraining() {
        MutableReasoningGraph kg = buildKg();

        // Short training (few epochs) — baseline gap
        RotatEConfig shortCfg = new RotatEConfig(16, 4.0, 4, 2, 0.02, 0.1, 42L, 4);
        RotatELearner.TrainedRotatE shortModel = new RotatELearner(shortCfg).train(kg, shortCfg);
        double gapShort = avgCorruptMinusTrueGap(shortModel);

        // Longer training — gap must be bigger
        RotatEConfig longCfg = new RotatEConfig(16, 4.0, 4, 60, 0.02, 0.1, 42L, 4);
        RotatELearner.TrainedRotatE longModel = new RotatELearner(longCfg).train(kg, longCfg);
        double gapLong = avgCorruptMinusTrueGap(longModel);

        System.out.printf(
                "[RotatELearnerTest] true-vs-corrupt gap: 2-epoch=%.4f  60-epoch=%.4f%n",
                gapShort, gapLong);

        assertTrue(gapLong > gapShort,
                "true-vs-corrupt distance gap must GROW with more training. "
                + "2-epoch gap=" + gapShort + "  60-epoch gap=" + gapLong
                + ". If gap is flat or shrinking, SameDiff autodiff or Adam is mis-wired.");
    }

    /**
     * Average over all true triples of (corrupt_distance − true_distance).
     * A positive and growing value confirms RotatE is separating true from corrupted triples.
     */
    private double avgCorruptMinusTrueGap(RotatELearner.TrainedRotatE model) {
        // For each true triple (h, r, t), pick a fixed corrupt tail and compute gap.
        double[] trueScores   = {
            model.score(ALICE, KNOWS,    BOB),
            model.score(BOB,   KNOWS,    CAROL),
            model.score(ALICE, KNOWS,    CAROL),
            model.score(ALICE, WORKS_AT, COMPANY_X),
            model.score(BOB,   WORKS_AT, COMPANY_X),
            model.score(CAROL, WORKS_AT, COMPANY_Y)
        };
        // Corrupt tails: wrong semantic type (company for KNOWS, person for WORKS_AT)
        double[] corruptScores = {
            model.score(ALICE, KNOWS,    COMPANY_X),
            model.score(BOB,   KNOWS,    COMPANY_X),
            model.score(ALICE, KNOWS,    COMPANY_Y),
            model.score(ALICE, WORKS_AT, BOB),
            model.score(BOB,   WORKS_AT, CAROL),
            model.score(CAROL, WORKS_AT, ALICE)
        };
        double sumGap = 0.0;
        for (int i = 0; i < trueScores.length; i++) {
            sumGap += corruptScores[i] - trueScores[i];
        }
        return sumGap / trueScores.length;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Entity and relation dimensions are correct
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void entityAndRelationDimensionsAreCorrect() {
        MutableReasoningGraph kg = buildKg();
        RotatEConfig cfg = new RotatEConfig(12, 3.0, 3, 5, 0.01, 0.1, 7L, 4);
        RotatELearner.TrainedRotatE model = new RotatELearner(cfg).train(kg, cfg);

        assertEquals(5, model.numEntities(), "Expected 5 entities");
        assertEquals(2, model.numRelations(), "Expected 2 relation types (KNOWS, WORKS_AT)");
        assertEquals(12, model.dim(), "dim must match config");

        // Each entity has real and imaginary vectors of length dim
        for (int i = 0; i < model.numEntities(); i++) {
            assertFiniteVector(model.entityRe(i), "entityRe[" + i + "]", 12);
            assertFiniteVector(model.entityIm(i), "entityIm[" + i + "]", 12);
        }
        for (int r = 0; r < model.numRelations(); r++) {
            assertFiniteVector(model.relPhase(r), "relPhase[" + r + "]", 12);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — learnInto writes entity real-part vectors into graph
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void learnIntoWritesEntityEmbeddingsIntoGraph() {
        MutableReasoningGraph kg = buildKg();
        RotatEConfig cfg = new RotatEConfig(8, 3.0, 3, 5, 0.01, 0.1, 99L, 4);
        EmbeddingConfig ecfg = new EmbeddingConfig(
                cfg.dim(), 5, 3, 2, cfg.negSamples(),
                1.0, 1.0, cfg.epochs(), cfg.learningRate(), cfg.seed());

        EmbeddingLearner learner = new RotatELearner(cfg);
        learner.learnInto(kg, ecfg);

        for (GraphEntity e : kg.entities()) {
            assertTrue(e.hasEmbedding(),
                    "Entity '" + e.id() + "' must have an embedding after learnInto");
            assertEquals(8, e.embedding().length,
                    "Embedding length must equal config.dim() for entity '" + e.id() + "'");
            for (double v : e.embedding()) {
                assertTrue(Double.isFinite(v),
                        "Embedding value must be finite for entity '" + e.id() + "'");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — Structural reproducibility: true>corrupt holds across two runs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Two independent training runs with the same seed must both satisfy the true>corrupt gate.
     *
     * <p>ND4J SameDiff may produce slightly different per-element float values between sequential
     * runs in the same JVM (workspace JIT effects), but the structural invariant —
     * true triple closer than corrupted triple — must hold in both runs.</p>
     */
    @Test
    void structuralReproducibilityAcrossRuns() {
        MutableReasoningGraph kg1 = buildKg();
        MutableReasoningGraph kg2 = buildKg();

        RotatELearner learner = new RotatELearner(TRAIN_CFG);
        RotatELearner.TrainedRotatE model1 = learner.train(kg1, TRAIN_CFG);
        RotatELearner.TrainedRotatE model2 = learner.train(kg2, TRAIN_CFG);

        // Both runs: true triple must beat corrupted triple
        double dTrue1    = model1.score(ALICE, KNOWS, BOB);
        double dCorrupt1 = model1.score(ALICE, KNOWS, COMPANY_X);
        double dTrue2    = model2.score(ALICE, KNOWS, BOB);
        double dCorrupt2 = model2.score(ALICE, KNOWS, COMPANY_X);

        System.out.printf("[RotatELearnerTest] reproducibility run1: true=%.4f corrupt=%.4f%n",
                dTrue1, dCorrupt1);
        System.out.printf("[RotatELearnerTest] reproducibility run2: true=%.4f corrupt=%.4f%n",
                dTrue2, dCorrupt2);

        assertFiniteAndNonNegative(dTrue1,    "run1 true-triple distance");
        assertFiniteAndNonNegative(dCorrupt1, "run1 corrupt-triple distance");
        assertFiniteAndNonNegative(dTrue2,    "run2 true-triple distance");
        assertFiniteAndNonNegative(dCorrupt2, "run2 corrupt-triple distance");

        assertTrue(dTrue1 < dCorrupt1,
                "Run 1: true triple must score lower distance than corrupted triple. "
                + "true=" + dTrue1 + " corrupt=" + dCorrupt1);
        assertTrue(dTrue2 < dCorrupt2,
                "Run 2: true triple must score lower distance than corrupted triple. "
                + "true=" + dTrue2 + " corrupt=" + dCorrupt2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void assertFiniteAndNonNegative(double v, String label) {
        assertTrue(Double.isFinite(v), label + " must be finite, got " + v);
        assertTrue(v >= 0.0, label + " must be non-negative (L2 distance), got " + v);
    }

    private static void assertFiniteVector(double[] v, String label, int expectedDim) {
        assertNotNull(v, label + " must not be null");
        assertFalse(v.length == 0, label + " must not be empty");
        assertEquals(expectedDim, v.length, label + " length must equal dim");
        for (int i = 0; i < v.length; i++) {
            assertTrue(Double.isFinite(v[i]),
                    label + "[" + i + "] must be finite, got " + v[i]);
        }
    }
}

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

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate tests for {@link LinkPredictor} (§6.3 — RotatE link prediction).
 *
 * <h3>Knowledge graph fixture</h3>
 * <p>Exactly the same small KG used in {@link RotatELearnerTest}:</p>
 * <pre>
 *   Alice  --[KNOWS]--&gt;   Bob
 *   Bob    --[KNOWS]--&gt;   Carol
 *   Alice  --[KNOWS]--&gt;   Carol
 *   Alice  --[WORKS_AT]--&gt; CompanyX
 *   Bob    --[WORKS_AT]--&gt; CompanyX
 *   Carol  --[WORKS_AT]--&gt; CompanyY
 * </pre>
 * <p>Two relation types: {@code KNOWS} (person-to-person) and {@code WORKS_AT}
 * (person-to-company). After training, a true tail like {@code Bob} for
 * {@code (Alice, KNOWS, ?)} must appear above a wrong-type entity like
 * {@code CompanyX} in the ranked list.</p>
 */
class LinkPredictorTest {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final String ALICE    = "Alice";
    private static final String BOB      = "Bob";
    private static final String CAROL    = "Carol";
    private static final String COMPANY_X = "CompanyX";
    private static final String COMPANY_Y = "CompanyY";
    private static final String KNOWS    = "KNOWS";
    private static final String WORKS_AT = "WORKS_AT";

    /**
     * Enough epochs to learn clear KNOWS vs WORKS_AT separation on this tiny KG.
     * Same config used by {@link RotatELearnerTest} so the gate is consistent.
     */
    private static final RotatEConfig TRAIN_CFG = new RotatEConfig(
            16,   // dim
            4.0,  // margin γ
            4,    // negSamples K
            80,   // epochs
            0.02, // learningRate
            0.1,  // initRange
            42L,  // seed
            4     // batchSize
    );

    private static RotatELearner.TrainedRotatE MODEL;
    private static LinkPredictor PREDICTOR;

    // ─────────────────────────────────────────────────────────────────────────
    // Setup — train once, share across all tests
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeAll
    static void trainModel() {
        MutableReasoningGraph kg = new MutableReasoningGraph();
        kg.addEntity(ALICE,     "Person",  "Alice");
        kg.addEntity(BOB,       "Person",  "Bob");
        kg.addEntity(CAROL,     "Person",  "Carol");
        kg.addEntity(COMPANY_X, "Company", "CompanyX");
        kg.addEntity(COMPANY_Y, "Company", "CompanyY");

        kg.addRelation("r1", ALICE, BOB,       KNOWS,    1.0);
        kg.addRelation("r2", BOB,   CAROL,     KNOWS,    1.0);
        kg.addRelation("r3", ALICE, CAROL,     KNOWS,    1.0);
        kg.addRelation("r4", ALICE, COMPANY_X, WORKS_AT, 1.0);
        kg.addRelation("r5", BOB,   COMPANY_X, WORKS_AT, 1.0);
        kg.addRelation("r6", CAROL, COMPANY_Y, WORKS_AT, 1.0);

        MODEL     = new RotatELearner(TRAIN_CFG).train(kg, TRAIN_CFG);
        PREDICTOR = new LinkPredictor(MODEL);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — GATE: true tail ranks above wrong-type entity in predictTails
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * THE decisive link-prediction gate.
     *
     * <p>Given the query {@code (Alice, KNOWS, ?)}, the true tail {@code Bob} must
     * appear strictly above the wrong-type entity {@code CompanyX} in the ranked list
     * (lower index = lower distance = more plausible).  If RotatE training has not
     * separated person entities from company entities for the KNOWS relation, this fails.</p>
     */
    @Test
    void trueTailRanksAboveWrongTypeEntityInPredictTails() {
        List<LinkPredictor.ScoredPrediction> preds = PREDICTOR.predictTails(ALICE, KNOWS, 5);

        assertNotNull(preds, "predictTails must not return null");
        assertFalse(preds.isEmpty(), "predictTails must return at least one result");
        // List must be sorted ascending by distance
        assertSortedAscending(preds, "predictTails results must be ascending by distance");

        int rankBob      = rankOf(preds, BOB);
        int rankCompanyX = rankOf(preds, COMPANY_X);
        double distBob      = distanceOf(preds, BOB);
        double distCompanyX = distanceOf(preds, COMPANY_X);

        System.out.printf(
                "[LinkPredictorTest] (Alice, KNOWS, ?) top-5 — Bob rank=%d dist=%.4f  CompanyX rank=%d dist=%.4f%n",
                rankBob, distBob, rankCompanyX, distCompanyX);

        assertTrue(rankBob < rankCompanyX,
                "Bob (true tail of KNOWS) must rank ABOVE CompanyX (wrong type). "
                + "Bob rank=" + rankBob + " dist=" + distBob
                + "  CompanyX rank=" + rankCompanyX + " dist=" + distCompanyX);
        assertTrue(distBob < distCompanyX,
                "Bob must have LOWER distance than CompanyX. "
                + "Bob=" + distBob + " CompanyX=" + distCompanyX);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — predictHeads: true head ranks above wrong-type entity
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Symmetric gate for the head-prediction direction.
     *
     * <p>Given {@code (?, WORKS_AT, CompanyX)}, both {@code Alice} and {@code Bob} are
     * true heads. The company {@code CompanyY} is a wrong head. At least one of the true
     * heads must rank above {@code CompanyY} in the head-prediction list.</p>
     */
    @Test
    void trueHeadRanksAboveWrongTypeEntityInPredictHeads() {
        List<LinkPredictor.ScoredPrediction> preds = PREDICTOR.predictHeads(WORKS_AT, COMPANY_X, 5);

        assertNotNull(preds, "predictHeads must not return null");
        assertFalse(preds.isEmpty(), "predictHeads must return at least one result");
        assertSortedAscending(preds, "predictHeads results must be ascending by distance");

        int rankAlice    = rankOf(preds, ALICE);
        int rankCompanyY = rankOf(preds, COMPANY_Y);
        double distAlice    = distanceOf(preds, ALICE);
        double distCompanyY = distanceOf(preds, COMPANY_Y);

        System.out.printf(
                "[LinkPredictorTest] (?, WORKS_AT, CompanyX) top-5 — Alice rank=%d dist=%.4f  CompanyY rank=%d dist=%.4f%n",
                rankAlice, distAlice, rankCompanyY, distCompanyY);

        assertTrue(rankAlice < rankCompanyY,
                "Alice (true head for WORKS_AT->CompanyX) must rank ABOVE CompanyY (wrong type). "
                + "Alice rank=" + rankAlice + " dist=" + distAlice
                + "  CompanyY rank=" + rankCompanyY + " dist=" + distCompanyY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — scoreTriple passthrough
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scoreTripleMatchesTrainedModel() {
        double fromPredictor = PREDICTOR.scoreTriple(ALICE, KNOWS, BOB);
        double fromModel     = MODEL.score(ALICE, KNOWS, BOB);

        assertTrue(Double.isFinite(fromPredictor), "scoreTriple must return a finite value");
        assertEquals(fromModel, fromPredictor, 1e-12,
                "scoreTriple must return exactly the same value as TrainedRotatE.score");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — topK truncation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void predictTailsTruncatesToTopK() {
        List<LinkPredictor.ScoredPrediction> top3 = PREDICTOR.predictTails(ALICE, KNOWS, 3);
        assertEquals(3, top3.size(), "predictTails(topK=3) must return exactly 3 results");

        List<LinkPredictor.ScoredPrediction> topAll = PREDICTOR.predictTails(ALICE, KNOWS, 0);
        assertEquals(MODEL.numEntities(), topAll.size(),
                "predictTails(topK<=0) must return all entities");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — validation: unknown entity / relation throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void unknownEntityThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> PREDICTOR.predictTails("NonExistent", KNOWS, 5),
                "Unknown entity should throw IllegalArgumentException");
    }

    @Test
    void unknownRelationThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> PREDICTOR.predictTails(ALICE, "UNKNOWN_REL", 5),
                "Unknown relation should throw IllegalArgumentException");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 — null model throws at construction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void nullModelThrowsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new LinkPredictor(null),
                "Null model should throw IllegalArgumentException at construction");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** 0-based rank of entity in the prediction list (-1 if not found). */
    private static int rankOf(List<LinkPredictor.ScoredPrediction> preds, String entityId) {
        for (int i = 0; i < preds.size(); i++) {
            if (entityId.equals(preds.get(i).entityId())) return i;
        }
        return Integer.MAX_VALUE; // not in list: worst possible rank
    }

    private static double distanceOf(List<LinkPredictor.ScoredPrediction> preds, String entityId) {
        for (LinkPredictor.ScoredPrediction p : preds) {
            if (entityId.equals(p.entityId())) return p.distance();
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void assertSortedAscending(List<LinkPredictor.ScoredPrediction> preds, String msg) {
        for (int i = 1; i < preds.size(); i++) {
            assertTrue(preds.get(i).distance() >= preds.get(i - 1).distance(),
                    msg + " (violated at index " + i + ")");
        }
    }
}

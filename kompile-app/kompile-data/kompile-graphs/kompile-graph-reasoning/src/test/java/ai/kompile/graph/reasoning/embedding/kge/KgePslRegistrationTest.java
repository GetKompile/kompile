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
package ai.kompile.graph.reasoning.embedding.kge;

import ai.kompile.graph.reasoning.psl.ExternalFunction;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PSL registration path of the KGE integration.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link KgeTripleScoreFunction}: per-grounding registration via
 *       {@link PslProgram#registerFunction}</li>
 *   <li>{@link KgePslBulkObserver}: bulk pre-scoring via {@link PslProgram#observe}</li>
 *   <li>{@link StubKgeTripleScorer}: deterministic scoring for test isolation</li>
 *   <li>Zero-score suppression (open-world default: {@code 0.0} → atom skipped)</li>
 *   <li>Threshold filtering in the bulk path</li>
 * </ul>
 *
 * <h3>PSL arg convention in these tests</h3>
 * <p>Per-grounding tests use a <em>2-arg</em> {@link ExternalFunction} lambda
 * ({@code KgeScore(A, B)}) that captures the relation type "KNOWS" in its closure.
 * This sidesteps PSL string-constant quoting in rule text and tests the core
 * grounding/zero-suppression behaviour cleanly.</p>
 *
 * <p>Bulk-path tests use the full <em>3-arg</em> {@code TripleScore(A, R, B)} predicate
 * (R is a free variable), pre-observed via {@link KgePslBulkObserver}, so grounding
 * unifies R against the constant "KNOWS" from the stored ground atoms.</p>
 */
@DisplayName("KGE PSL registration path")
class KgePslRegistrationTest {

    // ─── Stub scorer shared by all tests ─────────────────────────────────────

    /** Scores alice→KNOWS→bob=0.9, alice→KNOWS→carol=0.3, bob→KNOWS→carol=0.2, others=0.0 */
    private static final KgeTripleScorer STUB = StubKgeTripleScorer.builder()
            .withScore("alice", "KNOWS", "bob",   0.9)
            .withScore("alice", "KNOWS", "carol", 0.3)
            .withScore("bob",   "KNOWS", "carol", 0.2)
            .build();

    /**
     * 2-arg ExternalFunction adapter: scores entity pairs for the fixed relation "KNOWS".
     * PSL rule: {@code KgeScore(A, B)} — A=head, B=tail; relation captured in closure.
     */
    private static ExternalFunction kgeKnowsScore() {
        return args -> {
            if (args.length != 2) throw new IllegalArgumentException("KgeScore requires 2 args");
            return STUB.scoreTriple(args[0], "KNOWS", args[1]);
        };
    }

    // ─── StubKgeTripleScorer contract ────────────────────────────────────────

    @Nested
    @DisplayName("StubKgeTripleScorer")
    class StubTests {

        @Test
        @DisplayName("scoreTriple returns registered value")
        void returnsRegisteredScore() {
            assertEquals(0.9, STUB.scoreTriple("alice", "KNOWS", "bob"), 1e-9);
        }

        @Test
        @DisplayName("scoreTriple returns 0.0 for unregistered triple")
        void returnsZeroForUnknownTriple() {
            assertEquals(0.0, STUB.scoreTriple("carol", "KNOWS", "alice"), 1e-9);
        }

        @Test
        @DisplayName("knows() returns true for registered entities/relations")
        void knowsReturnsTrueForRegistered() {
            assertTrue(STUB.knows("alice", "KNOWS", "bob"));
        }

        @Test
        @DisplayName("knows() returns false for completely unknown entity")
        void knowsReturnsFalseForUnknown() {
            assertFalse(STUB.knows("alice", "KNOWS", "zaphod")); // zaphod not registered
        }

        @Test
        @DisplayName("builder rejects score outside [0,1]")
        void builderRejectsInvalidScore() {
            assertThrows(IllegalArgumentException.class,
                    () -> StubKgeTripleScorer.builder().withScore("a", "R", "b", 1.5));
        }
    }

    // ─── KgeTripleScoreFunction (per-grounding path) ─────────────────────────

    @Nested
    @DisplayName("KgeTripleScoreFunction — per-grounding path")
    class PerGroundingTests {

        @Test
        @DisplayName("evaluate(3 args) delegates to scorer")
        void delegatesToScorer() {
            KgeTripleScoreFunction fn = new KgeTripleScoreFunction(STUB);
            assertEquals(0.9, fn.evaluate("alice", "KNOWS", "bob"), 1e-9);
        }

        @Test
        @DisplayName("evaluate with wrong arity throws")
        void wrongArityThrows() {
            KgeTripleScoreFunction fn = new KgeTripleScoreFunction(STUB);
            assertThrows(IllegalArgumentException.class, () -> fn.evaluate("a", "b")); // only 2 args
        }

        @Test
        @DisplayName("high-score entity pair enables grounding via registerFunction")
        void highScoreEnablesGrounding() {
            // 2-arg function: KgeScore(A, B) scores alice→KNOWS→bob = 0.9
            PslProgram prog = new PslProgram()
                    .registerFunction("KgeScore", kgeKnowsScore())
                    .observe("Entity", 1.0, "alice")
                    .observe("Entity", 1.0, "bob")
                    .target("Confirmed", "alice", "bob")
                    .addRule("5.0: KgeScore(A, B) & Entity(A) & Entity(B) -> Confirmed(A, B) ^2");

            List<GroundRule> ground = prog.ground();
            assertFalse(ground.isEmpty(),
                    "High KGE score (alice→KNOWS→bob=0.9) should enable at least one ground rule");
        }

        @Test
        @DisplayName("zero-score entity pair suppresses grounding")
        void zeroScoreSuppressesGrounding() {
            // carol→KNOWS→alice is not registered in the stub → score=0.0 → atom suppressed
            PslProgram prog = new PslProgram()
                    .registerFunction("KgeScore", kgeKnowsScore())
                    .observe("Entity", 1.0, "carol")
                    .observe("Entity", 1.0, "alice")
                    .target("Confirmed", "carol", "alice")
                    .addRule("5.0: KgeScore(A, B) & Entity(A) & Entity(B) -> Confirmed(A, B) ^2");

            prog.ground();
            // KgeScore(carol, alice) = 0.0 → ExternalFunction returns 0.0 → atom NOT registered
            assertFalse(prog.contains("KgeScore(carol, alice)"),
                    "Unregistered pair (score=0.0) should not be registered as observed atom");
        }

        @Test
        @DisplayName("high KGE score pushes inference toward Confirmed")
        void highScorePushesInference() {
            PslProgram prog = new PslProgram()
                    .registerFunction("KgeScore", kgeKnowsScore())
                    .observe("Entity", 1.0, "alice")
                    .observe("Entity", 1.0, "bob")
                    .target("Confirmed", "alice", "bob")
                    .addRule("5.0: KgeScore(A, B) & Entity(A) & Entity(B) -> Confirmed(A, B) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double confirmed = result.values().getOrDefault("Confirmed(alice, bob)", 0.0);
            // alice→KNOWS→bob = 0.9 with weight=5 should push Confirmed well above 0.5
            assertTrue(confirmed > 0.5,
                    "KGE score=0.9 with weight=5 should push Confirmed above 0.5, got " + confirmed);
        }

        @Test
        @DisplayName("registerInto convenience method wires the function")
        void registerIntoConvenience() {
            KgeTripleScoreFunction fn = new KgeTripleScoreFunction(STUB);
            PslProgram prog = new PslProgram();
            PslProgram returned = fn.registerInto(prog, "KGEScore");
            assertSame(prog, returned, "registerInto should return the same program");
            assertTrue(prog.isFunction("KGEScore"), "KGEScore should be registered as a function");
        }
    }

    // ─── KgePslBulkObserver (bulk path) ──────────────────────────────────────

    @Nested
    @DisplayName("KgePslBulkObserver — bulk pre-score path")
    class BulkObserverTests {

        private final List<String> entities  = List.of("alice", "bob", "carol");
        private final List<String> relations = List.of("KNOWS");

        @Test
        @DisplayName("observeCandidates registers atoms above threshold")
        void registersAtomsAboveThreshold() {
            PslProgram prog = new PslProgram();
            int added = KgePslBulkObserver.observeCandidates(
                    prog, STUB, entities, relations, "TripleScore", 0.5);

            // Only alice→KNOWS→bob=0.9 exceeds threshold 0.5
            assertEquals(1, added, "Only 1 triple (alice-KNOWS-bob) exceeds threshold 0.5");
            assertTrue(prog.contains("TripleScore(alice, KNOWS, bob)"),
                    "alice→KNOWS→bob should be observed in the program");
            assertEquals(0.9, prog.value("TripleScore(alice, KNOWS, bob)"), 1e-9);
        }

        @Test
        @DisplayName("observeAll registers all combinations where scorer.knows() returns true")
        void registersAllKnownTriples() {
            PslProgram prog = new PslProgram();
            int added = KgePslBulkObserver.observeAll(prog, STUB, entities, relations, "TripleScore");
            // knows() returns true for ALL 3×3=9 entity pairs (all 3 IDs + "KNOWS" registered).
            // threshold=0.0 means score>=0.0 passes even zero-scored triples.
            // Explicitly scored: alice→bob=0.9, alice→carol=0.3, bob→carol=0.2 (and 6 others at 0.0)
            assertEquals(9, added,
                    "observeAll(threshold=0.0) registers all 9 known pairs (3 entities × 3 entities)");
        }

        @Test
        @DisplayName("bulk-pre-scored atoms drive PSL inference without registerFunction")
        void bulkAtomsDoNotRequireFunction() {
            // Bulk path: pre-observe TripleScore(alice, KNOWS, bob)=0.9, no ExternalFunction needed.
            // Rule uses 3 free variables (A, R, B); R gets bound to "KNOWS" from the ground atom.
            PslProgram prog = new PslProgram();
            KgePslBulkObserver.observeCandidates(
                    prog, STUB, entities, relations, "TripleScore", 0.5);

            // Declare TripleScore as closed (CWA) so unmatched triples default to 0.0
            prog.declareClosed("TripleScore", 3)
                .observe("Entity", 1.0, "alice")
                .observe("Entity", 1.0, "bob")
                .target("Confirmed", "alice", "bob")
                .addRule("5.0: TripleScore(A, R, B) & Entity(A) & Entity(B) -> Confirmed(A, B) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double confirmed = result.values().getOrDefault("Confirmed(alice, bob)", 0.0);
            assertTrue(confirmed > 0.5,
                    "Bulk-observed KGE atom (TripleScore=0.9) should drive Confirmed above 0.5; got "
                            + confirmed);
        }

        @Test
        @DisplayName("threshold=1.0 registers no triples")
        void thresholdOneRegistersNone() {
            PslProgram prog = new PslProgram();
            int added = KgePslBulkObserver.observeCandidates(
                    prog, STUB, entities, relations, "TripleScore", 1.0);
            assertEquals(0, added, "No triple achieves plausibility=1.0 exactly");
        }

        @Test
        @DisplayName("invalid threshold throws")
        void invalidThresholdThrows() {
            PslProgram prog = new PslProgram();
            assertThrows(IllegalArgumentException.class,
                    () -> KgePslBulkObserver.observeCandidates(
                            prog, STUB, entities, relations, "TripleScore", -0.1));
            assertThrows(IllegalArgumentException.class,
                    () -> KgePslBulkObserver.observeCandidates(
                            prog, STUB, entities, relations, "TripleScore", 1.5));
        }
    }
}

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

import ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.prior.CascadePriorProvider;
import ai.kompile.graph.reasoning.prior.PriorContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KgeOpinionStoreBridge}: verifies that KGE scores are correctly
 * written as {@link Opinion#fromEmbeddingScore} opinions into an {@link OpinionStore}
 * and that {@link CascadePriorProvider} subsequently resolves them at tier (b).
 */
@DisplayName("KgeOpinionStoreBridge — OpinionStore + MEBN prior integration")
class KgeOpinionStoreBridgeTest {

    /** alice→KNOWS→bob=0.9, alice→KNOWS→carol=0.3, others=0.0 */
    private static final KgeTripleScorer STUB = StubKgeTripleScorer.builder()
            .withScore("alice", "KNOWS", "bob",   0.9)
            .withScore("alice", "KNOWS", "carol", 0.3)
            .build();

    private static final List<String> ENTITIES  = List.of("alice", "bob", "carol");
    private static final List<String> RELATIONS = List.of("KNOWS");

    // ─── Opinion.fromEmbeddingScore output shape ──────────────────────────────

    @Test
    @DisplayName("fromEmbeddingScore produces a non-vacuous opinion for non-zero score")
    void fromEmbeddingScoreNonVacuous() {
        Opinion op = Opinion.fromEmbeddingScore(0.9, 0.25);
        assertFalse(op.isVacuous(), "Non-zero score should produce non-vacuous opinion");
        assertTrue(op.expectation() > 0.5, "High score (0.9) expectation should exceed 0.5");
    }

    @Test
    @DisplayName("fromEmbeddingScore uncertainty floor is respected")
    void fromEmbeddingScoreUncertaintyFloor() {
        double uncertainty = 0.25;
        Opinion op = Opinion.fromEmbeddingScore(0.9, uncertainty);
        // uncertainty should be close to the floor (not below it)
        assertTrue(op.uncertainty() >= uncertainty - 1e-9,
                "Uncertainty should not fall below the floor " + uncertainty + ", got " + op.uncertainty());
    }

    // ─── populateFrom: basic write behaviour ─────────────────────────────────

    @Nested
    @DisplayName("populateFrom — basic write behaviour")
    class PopulateFromTests {

        @Test
        @DisplayName("writes opinions for all known triples above threshold")
        void writesOpinionsAboveThreshold() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            int written = KgeOpinionStoreBridge.populateFrom(
                    store, STUB, ENTITIES, RELATIONS, 0.5);

            // Only alice→KNOWS→bob=0.9 exceeds 0.5
            assertEquals(1, written);
            String key = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "bob");
            assertTrue(store.has(key), "Opinion for alice→KNOWS→bob should be in store");
        }

        @Test
        @DisplayName("stored opinion is non-vacuous for high-plausibility triple")
        void storedOpinionNonVacuous() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            KgeOpinionStoreBridge.populateFrom(store, STUB, ENTITIES, RELATIONS, 0.0);

            String key = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "bob");
            Opinion op = store.get(key);
            assertFalse(op.isVacuous(), "High-plausibility triple should produce non-vacuous opinion");
        }

        @Test
        @DisplayName("expectation of stored opinion reflects KGE score magnitude")
        void storedOpinionExpectationReflectsScore() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            KgeOpinionStoreBridge.populateFrom(store, STUB, ENTITIES, RELATIONS, 0.0);

            String highKey  = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "bob");
            String lowKey   = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "carol");

            double highExp = store.get(highKey).expectation();
            double lowExp  = store.get(lowKey).expectation();
            assertTrue(highExp > lowExp,
                    "alice→bob (score=0.9) should have higher expectation than alice→carol (score=0.3); "
                            + "got " + highExp + " vs " + lowExp);
        }

        @Test
        @DisplayName("threshold=1.0 writes nothing")
        void thresholdOneWritesNothing() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            int written = KgeOpinionStoreBridge.populateFrom(
                    store, STUB, ENTITIES, RELATIONS, 1.0);
            assertEquals(0, written, "No triple achieves exactly 1.0 plausibility");
        }

        @Test
        @DisplayName("invalid threshold throws")
        void invalidThresholdThrows() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            assertThrows(IllegalArgumentException.class,
                    () -> KgeOpinionStoreBridge.populateFrom(store, STUB, ENTITIES, RELATIONS, 1.5));
        }

        @Test
        @DisplayName("custom key formatter is applied")
        void customKeyFormatterApplied() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            KgeOpinionStoreBridge.TripleKeyFormatter fmt = (h, r, t) -> h + "::" + r + "::" + t;
            KgeOpinionStoreBridge.populateFrom(
                    store, STUB, ENTITIES, RELATIONS, 0.5, 0.25, fmt);
            assertTrue(store.has("alice::KNOWS::bob"),
                    "Custom key format 'alice::KNOWS::bob' should be present");
        }
    }

    // ─── buildStore convenience factory ──────────────────────────────────────

    @Test
    @DisplayName("buildStore returns a populated InMemoryOpinionStore")
    void buildStoreReturnedStoreIsPopulated() {
        OpinionStore store = KgeOpinionStoreBridge.buildStore(STUB, ENTITIES, RELATIONS, 0.0);
        assertNotNull(store);
        assertTrue(store.size() >= 2, "Store should contain at least 2 opinions (alice→bob, alice→carol)");
    }

    // ─── CascadePriorProvider tier (b) integration ───────────────────────────

    @Nested
    @DisplayName("CascadePriorProvider tier (b) — KGE opinion as MEBN root prior")
    class MebnPriorTierTests {

        /**
         * Verify that once KGE scores are written into the OpinionStore, the
         * CascadePriorProvider resolves them at tier (b) without needing ANY changes
         * to MEBN / SSBNGenerator internals.
         */
        @Test
        @DisplayName("CascadePriorProvider resolves KGE opinion at tier (b)")
        void cascadePriorResolvesKgeAtTierB() {
            // 1. Populate store from KGE scorer
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            KgeOpinionStoreBridge.populateFrom(store, STUB, ENTITIES, RELATIONS, 0.0);

            // 2. Build CascadePriorProvider backed by the populated store
            CascadePriorProvider provider = new CascadePriorProvider(store);

            // 3. Resolve prior for the alice→KNOWS→bob atom key
            String rvKey = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "bob");
            double prior = provider.priorFor(rvKey, PriorContext.EMPTY);

            // The store has a non-vacuous opinion for alice→KNOWS→bob (score=0.9).
            // Tier (b) returns opinion.expectation() which should be well above 0.5.
            assertTrue(prior > 0.5,
                    "CascadePriorProvider should resolve high KGE score as prior > 0.5, got " + prior);
        }

        @Test
        @DisplayName("CascadePriorProvider falls through to 0.5 for unscored atom key")
        void cascadePriorFallsThroughForUnscored() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            // Do NOT populate store — no KGE scores written
            CascadePriorProvider provider = new CascadePriorProvider(store);

            double prior = provider.priorFor("alice->KNOWS->zaphod", PriorContext.EMPTY);

            // No opinion in store, no embedding, no type frequency, no temporal info → tier (f) = 0.5
            assertEquals(0.5, prior, 1e-9,
                    "Unscored atom should fall through to uniform 0.5 prior");
        }

        @Test
        @DisplayName("high KGE score (0.9) yields expectation consistently above low score (0.3)")
        void highScoreYieldsHigherPriorThanLowScore() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            KgeOpinionStoreBridge.populateFrom(store, STUB, ENTITIES, RELATIONS, 0.0);
            CascadePriorProvider provider = new CascadePriorProvider(store);

            String highKey = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "bob");
            String lowKey  = KgeOpinionStoreBridge.DEFAULT_KEY_FORMAT.format("alice", "KNOWS", "carol");

            double highPrior = provider.priorFor(highKey, PriorContext.EMPTY);
            double lowPrior  = provider.priorFor(lowKey, PriorContext.EMPTY);

            assertTrue(highPrior > lowPrior,
                    "alice→bob (score=0.9) should yield higher prior than alice→carol (score=0.3); "
                            + "got " + highPrior + " vs " + lowPrior);
        }
    }
}

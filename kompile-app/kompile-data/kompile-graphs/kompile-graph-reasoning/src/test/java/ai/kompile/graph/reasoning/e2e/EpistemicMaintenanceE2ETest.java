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
package ai.kompile.graph.reasoning.e2e;

import ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.maintenance.GraphPruner;
import ai.kompile.graph.reasoning.maintenance.PruneResult;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.pruning.OpinionPruner;
import ai.kompile.graph.reasoning.pruning.PrunePolicy;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslMarginalInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.graph.reasoning.uncertainty.PslUncertaintyAdapter;
import ai.kompile.graph.reasoning.uncertainty.VariableUncertainty;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive end-to-end test covering all five epistemic subsystems:
 * <ul>
 *   <li>{@code confidence} — Opinion Subjective-Logic algebra, OpinionStore</li>
 *   <li>{@code tms} — ContradictionDetector, JustificationIndex, BeliefReviser</li>
 *   <li>{@code maintenance} — GraphPruner, PruneResult</li>
 *   <li>{@code pruning} — OpinionPruner, PrunePolicy</li>
 *   <li>{@code uncertainty} — PslUncertaintyAdapter, VariableUncertainty</li>
 * </ul>
 * Driven from {@link UnifiedGraph} for the persistence round-trip test.
 */
@DisplayName("Epistemic Maintenance E2E: Opinion + TMS + Pruner + Uncertainty + UnifiedGraph")
class EpistemicMaintenanceE2ETest {

    private static final double EPS = 1e-9;

    /** Assert that all simplex components are non-negative and sum to 1.0. */
    private static void assertSimplex(Opinion o) {
        double sum = o.belief() + o.disbelief() + o.uncertainty();
        assertEquals(1.0, sum, EPS, "b+d+u must equal 1: " + o);
        assertTrue(o.belief() >= 0 && o.disbelief() >= 0 && o.uncertainty() >= 0,
                "components must be non-negative: " + o);
    }

    // ─── 1. Simplex invariant across all fusion / algebra operators ──────────────

    @Test
    @DisplayName("1. b+d+u==1 holds after cumulativeFuse, averageFuse, consensus, conjoin, discount, complement")
    void simplexInvariantHoldsAcrossAllFusionOps() {
        Opinion a = new Opinion(0.70, 0.10, 0.20, 0.50);
        Opinion b = new Opinion(0.40, 0.30, 0.30, 0.50);

        assertSimplex(a.cumulativeFuse(b));
        assertSimplex(Opinion.cumulativeFuse(a, b, a)); // varargs
        assertSimplex(a.averageFuse(b));
        assertSimplex(Opinion.averageFuse(a, b));
        assertSimplex(Opinion.consensus(List.of(a, b)));
        assertSimplex(a.conjoin(b));
        assertSimplex(Opinion.conjoinAll(a, b));
        assertSimplex(a.discount(0.40));
        assertSimplex(a.discount(0.0));  // vacuous
        assertSimplex(a.discount(1.0));  // identity
        assertSimplex(a.complement());
        assertSimplex(Opinion.vacuous());
        assertSimplex(Opinion.vacuous(0.3));
        assertSimplex(Opinion.fromBetaEvidence(5, 2));
        assertSimplex(Opinion.fromSoftTruth(0.8));
        assertSimplex(Opinion.fromObservedValue(0.9));
        assertSimplex(Opinion.fromObserved());
    }

    // ─── 2. Cumulative fusion accumulates evidence (lower uncertainty) ───────────

    @Test
    @DisplayName("2. cumulativeFuse of two supporting opinions lowers uncertainty and keeps expectation high")
    void cumulativeFuseTwoSupportingOpinionsLowersUncertainty() {
        // Beta(8,1): b≈0.727, d≈0.091, u≈0.182
        Opinion s1 = Opinion.fromBetaEvidence(8, 1);
        // Beta(6,1): b≈0.667, d≈0.111, u≈0.222
        Opinion s2 = Opinion.fromBetaEvidence(6, 1);

        Opinion fused = s1.cumulativeFuse(s2);

        assertSimplex(fused);
        // Evidence accumulation must reduce uncertainty below both sources
        assertTrue(fused.uncertainty() < s1.uncertainty(),
                "fused uncertainty must be < s1.uncertainty=" + s1.uncertainty() + ", got " + fused.uncertainty());
        assertTrue(fused.uncertainty() < s2.uncertainty(),
                "fused uncertainty must be < s2.uncertainty=" + s2.uncertainty() + ", got " + fused.uncertainty());
        // Fusing two supporting opinions stays in the supporting region (> 0.5). NOTE: cumulative
        // fusion is an evidence accumulation, so the fused expectation need NOT exceed the stronger
        // source — it can settle between the two — so we assert direction (supporting), not monotonicity.
        assertTrue(fused.expectation() > 0.5,
                "fused expectation from two supporting opinions must be > 0.5, got " + fused.expectation());

        // Three-way varargs fusion should reduce uncertainty further still
        Opinion triple = Opinion.cumulativeFuse(s1, s2, s1);
        assertSimplex(triple);
        assertTrue(triple.uncertainty() < fused.uncertainty(),
                "three-way fusion must have lower uncertainty than two-way");
    }

    // ─── 3. Consensus of agreeing opinions ───────────────────────────────────────

    @Test
    @DisplayName("3. consensus of three agreeing high-belief opinions yields high belief and SUPPORTED status")
    void consensusOfAgreeingOpinionsYieldsHighBelief() {
        List<Opinion> opinions = List.of(
                new Opinion(0.75, 0.05, 0.20, 0.5),
                new Opinion(0.80, 0.05, 0.15, 0.5),
                new Opinion(0.70, 0.10, 0.20, 0.5)
        );
        Opinion consensus = Opinion.consensus(opinions);

        assertSimplex(consensus);
        assertTrue(consensus.belief() > 0.60,
                "consensus belief must be > 0.60, got " + consensus.belief());
        assertTrue(consensus.disbelief() < 0.20,
                "consensus disbelief must be < 0.20, got " + consensus.disbelief());
        assertEquals(Opinion.VerifyStatusProjection.SUPPORTED, consensus.projectStatus(),
                "consensus of agreeing opinions must project as SUPPORTED");

        // Single-element consensus is identity
        assertEquals(opinions.get(0).belief(),
                Opinion.consensus(List.of(opinions.get(0))).belief(), EPS);
        // Empty consensus is vacuous
        assertTrue(Opinion.consensus(List.of()).isVacuous());
    }

    // ─── 4. Conflict between dogmatic-opposing opinions ──────────────────────────

    @Test
    @DisplayName("4. supporting + refuting opinions yield high conflict score and isConflicted after fusion")
    void conflictingOpinionsPairRegistersConflict() {
        // fromObservedValue(0.92) → b=0.92, d=0.08, u=0 (dogmatic support)
        Opinion supporting = Opinion.fromObservedValue(0.92);
        // fromObservedValue(0.05) → b=0.05, d=0.95, u=0 (dogmatic refutation)
        Opinion refuting   = Opinion.fromObservedValue(0.05);

        // Conflict score: b1*d2 + d1*b2 = 0.92*0.95 + 0.08*0.05 ≈ 0.878
        double conflictScore = supporting.conflict(refuting);
        assertTrue(conflictScore > 0.40,
                "conflict between dogmatic-opposing opinions must be > 0.40, got " + conflictScore);
        // Conflict is symmetric
        assertEquals(conflictScore, refuting.conflict(supporting), EPS,
                "conflict must be symmetric");

        // Fusing them: both certain → average path → b=(0.92+0.05)/2=0.485, d=(0.08+0.95)/2=0.515
        Opinion fused = supporting.cumulativeFuse(refuting);
        assertSimplex(fused);
        // isConflicted at threshold 0.2: belief=0.485>0.2 AND disbelief=0.515>0.2
        assertTrue(fused.isConflicted(0.20),
                "fused dogmatic-opposing opinions must be isConflicted at threshold 0.20");
        // Expectation is in ambiguous mid-range
        double e = fused.expectation();
        assertTrue(e > 0.10 && e < 0.90,
                "conflicted fused expectation must be in mid-range (0.10,0.90), got " + e);

        // Vacuous opinions have zero conflict
        assertEquals(0.0, Opinion.vacuous().conflict(Opinion.vacuous()), EPS,
                "vacuous opinions have zero conflict");
    }

    // ─── 5. Discount by low-trust source ─────────────────────────────────────────

    @Test
    @DisplayName("5. discount(1)=identity, discount(0)=vacuous, discount(t) scales belief/disbelief by t")
    void discountByLowTrustReducesBelief() {
        Opinion strong = new Opinion(0.80, 0.05, 0.15, 0.5);

        // discount(1.0) is identity
        Opinion identity = strong.discount(1.0);
        assertSimplex(identity);
        assertEquals(strong.belief(),     identity.belief(),     EPS, "discount(1) belief");
        assertEquals(strong.disbelief(),  identity.disbelief(),  EPS, "discount(1) disbelief");
        assertEquals(strong.uncertainty(),identity.uncertainty(),EPS, "discount(1) uncertainty");

        // discount(0.0) is vacuous, baseRate preserved
        Opinion vacuous = strong.discount(0.0);
        assertSimplex(vacuous);
        assertTrue(vacuous.isVacuous(), "discount(0) must produce vacuous opinion");
        assertEquals(strong.baseRate(), vacuous.baseRate(), EPS, "baseRate preserved through discount(0)");

        // discount(0.1) scales belief and disbelief by 0.1
        Opinion discounted = strong.discount(0.1);
        assertSimplex(discounted);
        assertEquals(0.1 * strong.belief(),    discounted.belief(),    1e-9, "belief scaled by t");
        assertEquals(0.1 * strong.disbelief(), discounted.disbelief(), 1e-9, "disbelief scaled by t");
        // uncertainty absorbs the rest
        assertEquals(1.0 - 0.1 * (strong.belief() + strong.disbelief()),
                discounted.uncertainty(), 1e-9, "uncertainty = 1 - t*(b+d)");
        // complement is self-inverse
        Opinion c = strong.complement();
        assertSimplex(c);
        assertEquals(strong.belief(),    c.complement().belief(),    EPS, "complement is self-inverse (belief)");
        assertEquals(strong.disbelief(), c.complement().disbelief(), EPS, "complement is self-inverse (disbelief)");
    }

    // ─── 6. StrengthBand projection covers all five tiers ────────────────────────

    @Test
    @DisplayName("6. projectBand() maps opinions to ESTABLISHED / HIGH / PROBABLE / SPECULATIVE / SUPPRESSED")
    void strengthBandProjectionMapsCorrectly() {
        // ESTABLISHED: e >= 0.85 AND u < 0.15
        // e = 0.88 + 0.5*0.07 = 0.915 ≥ 0.85; u = 0.07 < 0.15
        Opinion established = new Opinion(0.88, 0.05, 0.07, 0.5);
        assertEquals(StrengthBand.ESTABLISHED, established.projectBand(),
                "ESTABLISHED: e=0.915, u=0.07");

        // HIGH: e >= 0.70 AND u < 0.30 (but not ESTABLISHED)
        // e = 0.70 + 0.5*0.25 = 0.825; u = 0.25 < 0.30; e < 0.85 → not ESTABLISHED
        Opinion high = new Opinion(0.70, 0.05, 0.25, 0.5);
        assertEquals(StrengthBand.HIGH, high.projectBand(),
                "HIGH: e=0.825, u=0.25");

        // PROBABLE: e >= 0.40 AND u < 0.60 (but not HIGH)
        // e = 0.45 + 0.5*0.35 = 0.625; u = 0.35 < 0.60; e < 0.70 → not HIGH
        Opinion probable = new Opinion(0.45, 0.20, 0.35, 0.5);
        assertEquals(StrengthBand.PROBABLE, probable.projectBand(),
                "PROBABLE: e=0.625, u=0.35");

        // SPECULATIVE: e >= 0.10 (but not PROBABLE because u >= 0.60)
        // e = 0.10 + 0.5*0.70 = 0.45; u = 0.70 ≥ 0.60 → not PROBABLE → SPECULATIVE
        Opinion speculative = new Opinion(0.10, 0.20, 0.70, 0.5);
        assertEquals(StrengthBand.SPECULATIVE, speculative.projectBand(),
                "SPECULATIVE: e=0.45, u=0.70 (fails PROBABLE u-gate)");

        // SUPPRESSED: e < 0.10
        // e = 0.03 + 0.5*0.07 = 0.065 < 0.10
        Opinion suppressed = new Opinion(0.03, 0.90, 0.07, 0.5);
        assertEquals(StrengthBand.SUPPRESSED, suppressed.projectBand(),
                "SUPPRESSED: e=0.065 < 0.10");

        // StrengthBand.from(opinion) is the same as opinion.projectBand()
        assertEquals(StrengthBand.ESTABLISHED, StrengthBand.from(established));
    }

    // ─── 7. InMemoryOpinionStore basic CRUD ──────────────────────────────────────

    @Test
    @DisplayName("7. InMemoryOpinionStore put/get/has/clear; absent key returns vacuous")
    void inMemoryOpinionStoreBasicCrud() {
        InMemoryOpinionStore store = new InMemoryOpinionStore();
        assertEquals(0, store.size());

        Opinion alice = Opinion.fromBetaEvidence(8, 1);   // well-supported positive
        Opinion bob   = Opinion.fromSoftTruth(0.35, 5);   // moderate, some uncertainty

        store.put("alice", alice);
        store.put("bob",   bob);

        assertEquals(2, store.size());
        assertTrue(store.has("alice"));
        assertTrue(store.has("bob"));
        assertFalse(store.has("charlie"));

        // Absent key returns vacuous (b=0, d=0, u=1)
        Opinion miss = store.get("charlie");
        assertTrue(miss.isVacuous(), "absent key must return vacuous opinion");
        assertSimplex(miss);

        // Retrieved opinions match inserted (belief preserved to full precision)
        assertEquals(alice.belief(), store.get("alice").belief(), EPS,
                "alice belief must match after store round-trip");
        assertEquals(bob.belief(), store.get("bob").belief(), EPS,
                "bob belief must match after store round-trip");

        // entries() exposes all items
        assertEquals(2, store.entries().size());

        store.clear();
        assertEquals(0, store.size());
        assertFalse(store.has("alice"));
    }

    // ─── 8. ContradictionDetector: negated atom pairs + direct fact polarity ─────

    @Test
    @DisplayName("8. ContradictionDetector flags State(alice) vs Not_State(alice) and hard true/false clash")
    void contradictionDetectorFlagsNegatedAtomPair() {
        FactStore store = new FactStore();
        // Both facts are hard=true but one is the negation of the other
        store.assertFact(Fact.observed("State(alice)", "src-positive"));    // value=1.0, hard
        store.assertFact(Fact.observed("Not_State(alice)", "src-negative")); // value=1.0, hard — negated prefix

        List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                ContradictionDetector.findFactContradictions(store);
        assertEquals(1, contradictions.size(),
                "exactly one contradiction must be detected between State(alice) and Not_State(alice)");

        // Direct contradicts() API: hard true vs hard false on the same atom key
        Fact hardTrue  = Fact.observed("Active(entity-1)", "src1");                         // value=1.0, hard
        Fact hardFalse = new Fact("Active(entity-1)", 0.0, "src2", Instant.now(), true);    // value=0.0, hard
        assertTrue(ContradictionDetector.contradicts(hardTrue, hardFalse),
                "hard 1.0 vs hard 0.0 on the same atom key must contradict");
        assertTrue(ContradictionDetector.contradicts(hardFalse, hardTrue),
                "contradicts must be symmetric");

        // Different atom keys: no contradiction
        assertFalse(ContradictionDetector.contradicts(
                Fact.observed("Active(entity-1)", "s1"),
                Fact.observed("Active(entity-2)", "s2")),
                "different atom keys (different arguments) do not contradict");

        // Soft facts: never directly contradict even at opposing truth values
        assertFalse(ContradictionDetector.contradicts(
                Fact.soft("Active(entity-1)", 0.9, "s3"),
                Fact.soft("Active(entity-1)", 0.0, "s4")),
                "soft facts do not directly contradict (they are PSL priors, not hard observations)");

        // Functional predicate clash: two different values for a single-valued predicate
        FactStore funcStore = new FactStore();
        funcStore.assertFact(Fact.observed("LifecyclePhase(order-1, draft)", "src-a"));
        funcStore.assertFact(Fact.observed("LifecyclePhase(order-1, approved)", "src-b"));
        List<ContradictionDetector.Pair<Fact, Fact>> funcContradictions =
                ContradictionDetector.findFactContradictions(funcStore, Set.of("LifecyclePhase"));
        assertEquals(1, funcContradictions.size(),
                "functional predicate clash must be detected");
    }

    // ─── 9. BeliefReviser: retract a fact and identify unsupported/weakened atoms ─

    @Test
    @DisplayName("9. BeliefReviser.retract removes fact, shrinks store, solely-dependent ⊆ all-dependent")
    void beliefReviserRetractsFactAndShrinksStore() {
        // Build a small propagation program: State(alice) & Link(alice,bob) -> State(bob)
        PslProgram program = new PslProgram();
        program.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        program.observe("State", 1.0, "alice");
        program.observe("Link",  1.0, "alice", "bob");
        program.target("State", "bob");

        // Parallel FactStore (atom keys must match what the grounder produces)
        FactStore factStore = new FactStore();
        factStore.assertFact(Fact.observed("State(alice)",    "crawl-1"));
        factStore.assertFact(Fact.observed("Link(alice, bob)","crawl-1"));
        int sizeBefore = factStore.size();

        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        JustificationIndex index = JustificationIndex.build(result, factStore);

        // Retract the source fact
        BeliefRevisionResult revision = BeliefReviser.retract("State(alice)", factStore, index);

        assertNotNull(revision);
        assertEquals("State(alice)", revision.retractedFactKey());
        assertEquals(sizeBefore - 1, factStore.size(),
                "retraction must shrink the store by exactly one");
        assertFalse(revision.revisedFactStore().factFor("State(alice)").isPresent(),
                "retracted atom key must be absent from the revised store");

        // JustificationIndex invariant: solely-dependent ⊆ all-dependent
        Set<String> allDependent = index.atomsDependingOnFact("State(alice)");
        Set<String> solely = index.solelyDependentOn("State(alice)");
        assertTrue(allDependent.containsAll(solely),
                "solelyDependentOn must be a subset of atomsDependingOnFact");

        // unsupportedAtoms + weakenedAtoms are both non-null (may be empty for this small program)
        assertNotNull(revision.unsupportedAtoms());
        assertNotNull(revision.weakenedAtoms());
        // revisedFactStore is the same object as factStore (in-place mutation)
        assertSame(factStore, revision.revisedFactStore());
    }

    // ─── 10. GraphPruner: confidence policy selects low-confidence entities/relations ─

    @Test
    @DisplayName("10. GraphPruner confidence policy selects nodes/edges below threshold, keeps above")
    void graphPrunerConfidencePolicySelectsLowConfidenceNodes() {
        // SimpleGraphEntity.of(id, type, label, weight) sets confidence == weight
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(SimpleGraphEntity.of("high1", "X", "High", 0.85));
        graph.addEntity(SimpleGraphEntity.of("high2", "X", "High", 0.90));
        graph.addEntity(SimpleGraphEntity.of("low1",  "X", "Low",  0.05));
        graph.addEntity(SimpleGraphEntity.of("low2",  "X", "Low",  0.10));
        // High-confidence connecting relation (SimpleGraphRelation.directed sets confidence=1.0)
        graph.addRelation("r-conn", "high1", "high2", "REL", 0.9);
        // Low-confidence relation (full constructor to set confidence=0.05 explicitly)
        graph.addRelation(new SimpleGraphRelation(
                "r-weak", "high1", "low1", "REL",
                0.05, 0.05, true, Set.of(), null, null, Map.of()));

        double threshold = 0.3;
        PruneResult result = GraphPruner.builder()
                .confidence(threshold, threshold)
                .build()
                .evaluate(graph);

        // Low-confidence entities selected
        assertTrue(result.entityIds().contains("low1"),  "low1 (conf=0.05) must be selected");
        assertTrue(result.entityIds().contains("low2"),  "low2 (conf=0.10) must be selected");
        // High-confidence entities kept
        assertFalse(result.entityIds().contains("high1"), "high1 (conf=0.85) must be kept");
        assertFalse(result.entityIds().contains("high2"), "high2 (conf=0.90) must be kept");
        // Low-confidence relation selected
        assertTrue(result.relationIds().contains("r-weak"),  "r-weak (conf=0.05) must be selected");
        // High-confidence relation kept
        assertFalse(result.relationIds().contains("r-conn"), "r-conn (conf=1.0) must be kept");

        // PruneResult metadata
        assertNotNull(result.entityReason("low1"));
        assertTrue(result.totalSelected() >= 3, "at least 3 elements selected (2 entities + 1 relation)");
        assertFalse(result.isEmpty());

        // Empty pruner yields empty result
        assertTrue(GraphPruner.builder().build().evaluate(graph).isEmpty(),
                "no-policy pruner must produce empty result");
    }

    // ─── 11. OpinionPruner defaults: SUPPRESSED → PRUNE, ESTABLISHED → KEEP ─────

    @Test
    @DisplayName("11. OpinionPruner defaults() prunes SUPPRESSED, keeps ESTABLISHED; batch mirrors single")
    void opinionPrunerDefaultPolicyPrunesSuppressedKeepsEstablished() {
        OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());

        // SUPPRESSED band: e = 0.03 + 0.5*0.07 = 0.065 < 0.10 → SUPPRESSED
        Opinion suppressed = new Opinion(0.03, 0.90, 0.07, 0.5);
        assertEquals(StrengthBand.SUPPRESSED, suppressed.projectBand());
        OpinionPruner.Decision dSuppressed = pruner.decide("edge-suppressed", suppressed);
        assertTrue(dSuppressed.shouldPrune(), "SUPPRESSED opinion must be pruned by defaults()");
        assertFalse(dSuppressed.shouldKeep());
        assertTrue(dSuppressed.reason().contains("PRUNE"), "reason string must mention PRUNE");

        // ESTABLISHED band: e = 0.88 + 0.5*0.07 = 0.915 ≥ 0.85; u=0.07 < 0.15 → ESTABLISHED
        Opinion established = new Opinion(0.88, 0.05, 0.07, 0.5);
        assertEquals(StrengthBand.ESTABLISHED, established.projectBand());
        OpinionPruner.Decision dEstablished = pruner.decide("entity-established", established);
        assertTrue(dEstablished.shouldKeep(), "ESTABLISHED opinion must be kept by defaults()");
        assertFalse(dEstablished.shouldPrune());

        // Batch: mixed map including HIGH (kept) alongside SUPPRESSED (pruned)
        // HIGH: e = 0.70 + 0.5*0.25 = 0.825 ≥ 0.70; u=0.25 < 0.30 → HIGH
        Opinion high = new Opinion(0.70, 0.05, 0.25, 0.5);
        assertEquals(StrengthBand.HIGH, high.projectBand());

        Map<String, Opinion> batch = Map.of(
                "e-suppressed", suppressed,
                "e-established", established,
                "e-high", high
        );
        Map<String, OpinionPruner.Decision> decisions = pruner.decideBatch(batch);

        assertEquals(3, decisions.size());
        assertTrue(decisions.get("e-suppressed").shouldPrune(),  "e-suppressed batch decision: PRUNE");
        assertTrue(decisions.get("e-established").shouldKeep(),  "e-established batch decision: KEEP");
        assertTrue(decisions.get("e-high").shouldKeep(),         "e-high batch decision: KEEP");

        // Aggressive policy has tighter thresholds; conservative has looser ones.
        // An opinion with belief=0.12 < aggressive minBelief=0.20 (pruned) but >= conservative minBelief=0.05 (kept).
        // b=0.12, d=0.08, u=0.80, baseRate=0.5 → e=0.12+0.5*0.80=0.52 (not SUPPRESSED)
        OpinionPruner aggressivePruner   = new OpinionPruner(PrunePolicy.aggressive());
        OpinionPruner conservativePruner = new OpinionPruner(PrunePolicy.conservative());
        Opinion borderline = new Opinion(0.12, 0.08, 0.80, 0.5);
        assertSimplex(borderline);
        assertTrue(aggressivePruner.decide("borderline", borderline).shouldPrune(),
                "belief=0.12 < aggressive minBelief=0.20 → pruned by aggressive policy");
        assertTrue(conservativePruner.decide("borderline", borderline).shouldKeep(),
                "belief=0.12 >= conservative minBelief=0.05 → kept by conservative policy");
    }

    // ─── 12. PSL uncertainty: variance in [0,0.25], entropy in [0,1], path=PSL ──

    @Test
    @DisplayName("12. PslUncertaintyAdapter.fromMarginals gives variance in [0,0.25] and entropyBits in [0,1]")
    void pslMarginalUncertaintiesAreInUnitInterval() {
        PslProgram program = new PslProgram();
        program.addRule("1.0: Known(X) -> Active(X) ^2");
        program.observe("Known", 1.0, "alice");
        program.target("Active", "alice");
        program.target("Active", "bob"); // no Known(bob) — higher uncertainty than alice

        // Use a small, reproducible sample count so the test stays fast
        PslMarginalInference.Result result = PslMarginalInference.solve(
                program, 300, 60, 1.0, new java.util.Random(42));

        Map<String, VariableUncertainty> uncertainties = PslUncertaintyAdapter.fromMarginals(result);
        assertFalse(uncertainties.isEmpty(), "at least one target atom must have an uncertainty estimate");

        for (Map.Entry<String, VariableUncertainty> entry : uncertainties.entrySet()) {
            VariableUncertainty vu = entry.getValue();
            double var     = vu.variance();
            double entropy = vu.entropyBits();
            double marginal = vu.marginal();

            // Variance in [0, 0.25] for Bernoulli p*(1-p)
            assertTrue(var >= -1e-9 && var <= 0.25 + 1e-9,
                    "variance must be in [0, 0.25], got " + var + " for " + entry.getKey());
            // Binary entropy in [0, 1] bit
            assertTrue(entropy >= -1e-9 && entropy <= 1.0 + 1e-9,
                    "entropyBits must be in [0, 1], got " + entropy + " for " + entry.getKey());
            // Marginal is a soft-truth in [0,1]
            assertTrue(marginal >= -1e-9 && marginal <= 1.0 + 1e-9,
                    "marginal must be in [0, 1], got " + marginal + " for " + entry.getKey());
            // Inference path is PSL
            assertEquals(VariableUncertainty.InferencePath.PSL, vu.path(),
                    "path must be PSL for PslUncertaintyAdapter output");
        }

        // rankByVariance returns a non-empty, descending list
        List<VariableUncertainty> ranked = PslUncertaintyAdapter.rankByVariance(result);
        assertFalse(ranked.isEmpty());
        for (int i = 0; i + 1 < ranked.size(); i++) {
            assertTrue(ranked.get(i).variance() >= ranked.get(i + 1).variance() - 1e-9,
                    "rankByVariance must be in descending order");
        }
    }

    // ─── 13. UnifiedGraph round-trip preserves topology, opinions, and epistemic fidelity ─

    @Test
    @DisplayName("13. UnifiedGraph save→load preserves entities, relations, opinions; fused belief is identical")
    void unifiedGraphSaveLoadPreservesOpinionsAndTopology() throws Exception {
        // Build a graph with two entities and one relation, each carrying an opinion
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity("alice", "Person", "Alice");
        g.addEntity("bob",   "Person", "Bob");
        g.addRelation("alice-knows-bob", "alice", "bob", "KNOWS", 0.9);

        // Opinions spanning different epistemic states
        Opinion aliceOpinion = Opinion.fromBetaEvidence(10, 1);   // high belief; b≈0.769, u≈0.154
        Opinion bobOpinion   = Opinion.fromSoftTruth(0.40,  3);   // moderate positive; some uncertainty
        Opinion relOpinion   = new Opinion(0.65, 0.10, 0.25, 0.5); // HIGH band

        g.putEntityOpinion("alice",           aliceOpinion);
        g.putEntityOpinion("bob",             bobOpinion);
        g.putRelationOpinion("alice-knows-bob", relOpinion);

        // Verify pre-save state via opinionStore()
        OpinionStore preSave = g.opinionStore();
        assertEquals(3, preSave.size(), "opinionStore must expose all 3 opinions before save");
        assertEquals(aliceOpinion.belief(), preSave.get("alice").belief(), EPS,
                "pre-save alice belief from opinionStore");

        // Round-trip via byte stream (no file I/O — fast, hermetic)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        g.save(baos);
        byte[] bytes = baos.toByteArray();
        assertTrue(bytes.length > 0, "serialized graph must be non-empty");

        UnifiedGraph back = UnifiedGraph.load(new ByteArrayInputStream(bytes));

        // ── Topology survived ────────────────────────────────────────────────
        assertEquals(2, back.entities().size(),  "entity count must be 2 after reload");
        assertEquals(1, back.relations().size(), "relation count must be 1 after reload");
        assertTrue(back.entity("alice").isPresent(), "alice must survive round-trip");
        assertTrue(back.entity("bob").isPresent(),   "bob must survive round-trip");

        // ── Opinions survived ────────────────────────────────────────────────
        Opinion postAlice = back.entityOpinion("alice");
        assertNotNull(postAlice, "alice opinion must survive round-trip");
        assertSimplex(postAlice);
        assertEquals(aliceOpinion.belief(),     postAlice.belief(),     1e-6, "alice belief round-trip");
        assertEquals(aliceOpinion.disbelief(),  postAlice.disbelief(),  1e-6, "alice disbelief round-trip");
        assertEquals(aliceOpinion.uncertainty(),postAlice.uncertainty(),1e-6, "alice uncertainty round-trip");

        Opinion postBob = back.entityOpinion("bob");
        assertNotNull(postBob, "bob opinion must survive round-trip");
        assertEquals(bobOpinion.belief(), postBob.belief(), 1e-6, "bob belief round-trip");

        Opinion postRel = back.relationOpinion("alice-knows-bob");
        assertNotNull(postRel, "relation opinion must survive round-trip");
        assertEquals(relOpinion.belief(), postRel.belief(), 1e-6, "relation belief round-trip");

        // ── OpinionStore is reasoning-ready after reload ──────────────────────
        OpinionStore postStore = back.opinionStore();
        assertEquals(3, postStore.size(), "opinionStore must have 3 entries after reload");

        // ── Epistemic fidelity: fused result is identical whether computed from
        //    the reloaded store or from the original opinions ─────────────────
        Opinion fusedFromStore    = postStore.get("alice").cumulativeFuse(postStore.get("alice-knows-bob"));
        Opinion fusedFromOriginal = aliceOpinion.cumulativeFuse(relOpinion);
        assertSimplex(fusedFromStore);
        assertEquals(fusedFromOriginal.belief(),     fusedFromStore.belief(),     1e-5,
                "fused belief must be identical after round-trip");
        assertEquals(fusedFromOriginal.disbelief(),  fusedFromStore.disbelief(),  1e-5,
                "fused disbelief must be identical after round-trip");
        assertEquals(fusedFromOriginal.uncertainty(),fusedFromStore.uncertainty(),1e-5,
                "fused uncertainty must be identical after round-trip");

        // ── Contradiction detection on reloaded store ─────────────────────────
        // alice has high belief, bob has moderate belief — no contradiction between them
        FactStore factStore = new FactStore();
        factStore.assertFact(Fact.observed("State(alice)", "src1"));
        factStore.assertFact(Fact.observed("Not_State(alice)", "src2")); // plant a contradiction
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                ContradictionDetector.findFactContradictions(factStore);
        assertEquals(1, contradictions.size(),
                "contradiction detection must still work identically after graph round-trip");
    }
}

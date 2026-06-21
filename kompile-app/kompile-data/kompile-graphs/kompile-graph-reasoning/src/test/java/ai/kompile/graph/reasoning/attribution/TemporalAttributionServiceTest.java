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
package ai.kompile.graph.reasoning.attribution;

import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionConfidence;
import ai.kompile.graph.reasoning.domain.CausalHop;
import ai.kompile.graph.reasoning.domain.EvidenceType;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.model.TemporalInterval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TemporalAttributionService} covering all spec deliverables:
 *
 * <ol>
 *   <li>§5a — Precedence prune: non-preceding causes are dropped; preceding causes survive.</li>
 *   <li>§5b — Window scope: out-of-window causes are excluded.</li>
 *   <li>§5c — Decay: older surviving causes have lower strength than recent ones.</li>
 *   <li>§5d — Chain consistency: {@link AttributionChain#isTemporallyConsistent()} is true for
 *       ordered chains and false for out-of-order ones; inconsistent chains are demoted.</li>
 *   <li>{@link TemporalDecayConfig} EXPONENTIAL/LINEAR/NONE decay functions.</li>
 *   <li>End-to-end: expected surviving + decayed hops in a full service call.</li>
 * </ol>
 */
class TemporalAttributionServiceTest {

    // ─── Shared timeline ──────────────────────────────────────────────────────────

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2025-01-01T01:00:00Z"); // T0 + 1 hour
    private static final Instant T2 = Instant.parse("2025-01-01T02:00:00Z"); // T0 + 2 hours
    private static final Instant T3 = Instant.parse("2025-01-01T03:00:00Z"); // T0 + 3 hours
    private static final Instant T4 = Instant.parse("2025-01-01T04:00:00Z"); // T0 + 4 hours

    private final TemporalAttributionService service = TemporalAttributionService.INSTANCE;

    // ─── Helper builders ──────────────────────────────────────────────────────────

    /** An entity stamped at the given instant. */
    private static GraphEntity entity(String id, Instant ts) {
        return GraphEntity.builder(id).type("EVENT").label(id).timestamp(ts).build();
    }

    /** A timeless entity (no timestamp). */
    private static GraphEntity entity(String id) {
        return GraphEntity.builder(id).type("EVENT").label(id).build();
    }

    /** A CAUSES relation with weight 0.9. */
    private static GraphRelation causes(String id, String from, String to) {
        return SimpleGraphRelation.directed(id, from, to, "CAUSES", 0.9);
    }

    /** A relation with an explicit weight. */
    private static GraphRelation causes(String id, String from, String to, double weight) {
        return SimpleGraphRelation.directed(id, from, to, "CAUSES", weight);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §1: TemporalDecayConfig — unit tests for the three decay functions
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NONE decay always returns 1.0 regardless of age")
    void decayNone_alwaysReturnsOne() {
        TemporalDecayConfig cfg = TemporalDecayConfig.none();
        assertEquals(1.0, cfg.weight(Duration.ofHours(0)), 1e-9);
        assertEquals(1.0, cfg.weight(Duration.ofHours(24)), 1e-9);
        assertEquals(1.0, cfg.weight(Duration.ofDays(365)), 1e-9);
    }

    @Test
    @DisplayName("EXPONENTIAL weight halves exactly at halfLife")
    void decayExponential_halvesAtHalfLife() {
        Duration halfLife = Duration.ofHours(1);
        TemporalDecayConfig cfg = TemporalDecayConfig.exponential(halfLife);

        assertEquals(1.0,  cfg.weight(Duration.ZERO),        1e-9, "age=0 → weight=1.0");
        assertEquals(0.5,  cfg.weight(halfLife),              1e-9, "age=halfLife → weight=0.5");
        assertEquals(0.25, cfg.weight(Duration.ofHours(2)),  1e-9, "age=2*halfLife → weight=0.25");
        assertTrue(cfg.weight(Duration.ofHours(10)) < 0.01,
                "age=10*halfLife → weight very small");
    }

    @Test
    @DisplayName("LINEAR weight reaches 0.0 exactly at decayWindow")
    void decayLinear_zeroAtWindow() {
        Duration window = Duration.ofHours(2);
        TemporalDecayConfig cfg = TemporalDecayConfig.linear(window);

        assertEquals(1.0, cfg.weight(Duration.ZERO),         1e-9, "age=0 → weight=1.0");
        assertEquals(0.5, cfg.weight(Duration.ofHours(1)),   1e-9, "age=half-window → weight=0.5");
        assertEquals(0.0, cfg.weight(window),                1e-9, "age=window → weight=0.0");
        assertEquals(0.0, cfg.weight(Duration.ofHours(100)), 1e-9, "age>window → weight=0.0");
    }

    @Test
    @DisplayName("NONE weight(Instant, Instant) returns 1.0 even with null timestamps")
    void decayNone_withNullTimestamps_returnsOne() {
        TemporalDecayConfig cfg = TemporalDecayConfig.none();
        assertEquals(1.0, cfg.weight((Instant) null, null), 1e-9);
        assertEquals(1.0, cfg.weight(T0, null),             1e-9);
        assertEquals(1.0, cfg.weight(null, T1),             1e-9);
    }

    @Test
    @DisplayName("EXPONENTIAL weight(Instant, Instant) returns 1.0 when either timestamp is null")
    void decayExponential_withNullTimestamps_returnsOne() {
        TemporalDecayConfig cfg = TemporalDecayConfig.exponential(Duration.ofHours(1));
        assertEquals(1.0, cfg.weight((Instant) null, T1), 1e-9, "null cause → unknown age → no decay");
        assertEquals(1.0, cfg.weight(T0, null),            1e-9, "null effect → unknown age → no decay");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §2: §5a — Precedence prune
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Non-preceding cause (causeTs > effectTs) is pruned; no chain survives")
    void precedencePrune_removesForwardTimedCause() {
        //  effect at T1,  cause at T2  (cause is AFTER effect → must prune)
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause", T2));   // cause is future
        graph.addEntity(entity("effect", T1));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertEquals(0, result.getChains().size(),
                "Cause post-dates the effect — chain should be pruned");
        assertTrue(result.getPrunedHopCount() >= 1,
                "At least one hop should be counted as pruned");
    }

    @Test
    @DisplayName("Preceding cause (causeTs < effectTs) survives the precedence filter")
    void precedencePrune_keepsPrecedingCause() {
        //  cause at T0,  effect at T1  → causal order correct
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause", T0));
        graph.addEntity(entity("effect", T1));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty(), "Preceding cause should survive");
        assertEquals(0, result.getPrunedHopCount(),
                "No hop should be pruned when order is correct");
    }

    @Test
    @DisplayName("Simultaneous cause (causeTs == effectTs) is kept per spec §5a")
    void precedencePrune_simultaneousCauseIsKept() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause", T1));
        graph.addEntity(entity("effect", T1)); // same instant
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty(),
                "Simultaneous cause must be kept (ties are allowed per spec §5a)");
    }

    @Test
    @DisplayName("Timeless (null-timestamp) cause is kept as TEMPORAL_UNKNOWN")
    void precedencePrune_timelessCauseIsKept() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause"));        // no timestamp
        graph.addEntity(entity("effect", T1));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty(), "Timeless cause cannot be ruled out; must be kept");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §3: §5c — Decay down-weights older causes vs. recent ones
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("With EXPONENTIAL decay, older cause gets lower strength than newer cause")
    void decay_olderCauseHasLowerStrength() {
        //  Three nodes: recentCause at T3, oldCause at T0, effect at T4
        //  Both are valid causes; old one is 4 hours old, recent one is 1 hour old.
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("oldCause",    T0));
        graph.addEntity(entity("recentCause", T3));
        graph.addEntity(entity("effect",      T4));
        graph.addRelation(causes("r-old",    "oldCause",    "effect", 0.9));
        graph.addRelation(causes("r-recent", "recentCause", "effect", 0.9));

        // 1-hour half-life: old cause (4h) has 0.9 * 0.5^4 ≈ 0.056; recent (1h) has 0.9 * 0.5^1 = 0.45
        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.exponential(Duration.ofHours(1)))
                .maxDepth(2).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        // Both chains should survive; the recent cause should have higher influence score
        assertEquals(2, result.getChains().size(), "Both causes should produce a chain");

        Map<String, Double> scores = result.getTemporalInfluenceScores();
        double oldScore    = scores.getOrDefault("oldCause",    0.0);
        double recentScore = scores.getOrDefault("recentCause", 0.0);

        assertTrue(recentScore > oldScore,
                "Recent cause (1h ago) should have higher temporal influence than old cause (4h ago): "
                + recentScore + " vs " + oldScore);
    }

    @Test
    @DisplayName("With NONE decay, both causes have the same raw strength (no discounting)")
    void decay_noneConfig_strengthsEqual() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("oldCause",    T0));
        graph.addEntity(entity("recentCause", T3));
        graph.addEntity(entity("effect",      T4));
        graph.addRelation(causes("r-old",    "oldCause",    "effect", 0.9));
        graph.addRelation(causes("r-recent", "recentCause", "effect", 0.9));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(2).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        Map<String, Double> scores = result.getTemporalInfluenceScores();
        double oldScore    = scores.getOrDefault("oldCause",    0.0);
        double recentScore = scores.getOrDefault("recentCause", 0.0);

        assertEquals(oldScore, recentScore, 1e-9,
                "NONE decay must not differentiate by age");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §4: §5d — AttributionChain.isTemporallyConsistent()
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTemporallyConsistent() returns true for ordered chain (cause ≤ effect)")
    void chainConsistency_orderedChainIsConsistent() {
        CausalHop hop1 = CausalHop.builder()
                .causeNodeId("A").effectNodeId("B")
                .causeTimestamp(T0).effectTimestamp(T1)
                .strength(0.9).build();
        CausalHop hop2 = CausalHop.builder()
                .causeNodeId("B").effectNodeId("C")
                .causeTimestamp(T1).effectTimestamp(T2)
                .strength(0.9).build();

        AttributionChain chain = AttributionChain.builder()
                .hops(List.of(hop1, hop2))
                .overallConfidence(0.81)
                .build();

        assertTrue(chain.isTemporallyConsistent(), "T0→T1→T2 is properly ordered");
    }

    @Test
    @DisplayName("isTemporallyConsistent() returns false when one hop has causeTs > effectTs")
    void chainConsistency_outOfOrderHopIsInconsistent() {
        CausalHop goodHop = CausalHop.builder()
                .causeNodeId("A").effectNodeId("B")
                .causeTimestamp(T0).effectTimestamp(T1)
                .strength(0.9).build();
        CausalHop badHop = CausalHop.builder()
                .causeNodeId("B").effectNodeId("C")
                .causeTimestamp(T3).effectTimestamp(T1) // cause AFTER effect!
                .strength(0.9).build();

        AttributionChain chain = AttributionChain.builder()
                .hops(List.of(goodHop, badHop))
                .overallConfidence(0.81)
                .build();

        assertFalse(chain.isTemporallyConsistent(),
                "A hop with causeTs after effectTs makes the chain inconsistent");
    }

    @Test
    @DisplayName("isTemporallyConsistent() returns true when timestamps are null (unknown = consistent)")
    void chainConsistency_nullTimestampsAreConsistent() {
        CausalHop hop = CausalHop.builder()
                .causeNodeId("A").effectNodeId("B")
                .causeTimestamp(null).effectTimestamp(null)
                .strength(0.5).build();

        AttributionChain chain = AttributionChain.builder()
                .hops(List.of(hop))
                .overallConfidence(0.5)
                .build();

        assertTrue(chain.isTemporallyConsistent(), "Null timestamps cannot prove inconsistency");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §5: §5b — Window scope excludes out-of-window causes
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Window scope excludes a cause that falls outside [temporalStart, temporalEnd)")
    void windowScope_excludesOutOfWindowCause() {
        //  cause at T0, effect at T3; window = [T1, T4)
        //  T0 is before T1, so the cause entity should be out of the view window
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause",  T0));   // outside window (T0 < T1)
        graph.addEntity(entity("effect", T3));   // inside window
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .temporalStart(T1)
                .temporalEnd(T4)
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertEquals(0, result.getChains().size(),
                "Cause outside temporal window [T1,T4) should not appear in chains");
        assertNotNull(result.getQueryInterval(), "Effective query interval must be recorded");
    }

    @Test
    @DisplayName("Window scope includes a cause that falls within [temporalStart, temporalEnd)")
    void windowScope_includesInWindowCause() {
        //  cause at T1, effect at T3; window = [T0, T4)
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause",  T1));
        graph.addEntity(entity("effect", T3));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .temporalStart(T0)
                .temporalEnd(T4)
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty(),
                "Cause within window [T0,T4) should produce a chain");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // §6: End-to-end — multi-hop chain with precedence + decay
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("End-to-end: A→B→C (all ordered) with EXPONENTIAL decay produces one chain")
    void endToEnd_multiHopOrderedChain_singleChainSurvives() {
        //  A at T0, B at T1, C at T2.  A→B→C.  Decay halfLife=1h
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("A", T0));
        graph.addEntity(entity("B", T1));
        graph.addEntity(entity("C", T2));
        graph.addRelation(causes("r-AB", "A", "B", 1.0));
        graph.addRelation(causes("r-BC", "B", "C", 1.0));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("C")
                .decayConfig(TemporalDecayConfig.exponential(Duration.ofHours(1)))
                .maxDepth(5).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        // At least one chain must be assembled (A→B→C)
        assertFalse(result.getChains().isEmpty(), "At least one chain expected");

        // Find the chain containing node A as root cause
        boolean foundAToC = result.getChains().stream()
                .anyMatch(c -> "A".equals(c.getRootCauseNodeId()));
        assertTrue(foundAToC, "Root cause A should be in at least one chain");

        // Verify decay reduced overall confidence: 0.5^1 * 0.5^1 = 0.25 for 2-hop 1-hour chain
        result.getChains().stream()
                .filter(c -> "A".equals(c.getRootCauseNodeId()))
                .findFirst()
                .ifPresent(chain -> {
                    assertTrue(chain.getOverallConfidence() < 1.0,
                            "Decay should reduce overall confidence below 1.0");
                    assertTrue(chain.isTemporallyConsistent(),
                            "Ordered chain must pass consistency check");
                });
    }

    @Test
    @DisplayName("End-to-end: cause with wrong direction is pruned; only valid chain survives")
    void endToEnd_oneBadCausePruned_onlyValidChainSurvives() {
        //  goodCause at T0, badCause at T3, effect at T2
        //  goodCause→effect is valid; badCause→effect is pruned (T3 > T2)
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("goodCause", T0));
        graph.addEntity(entity("badCause",  T3));
        graph.addEntity(entity("effect",    T2));
        graph.addRelation(causes("r-good", "goodCause", "effect", 0.8));
        graph.addRelation(causes("r-bad",  "badCause",  "effect", 0.9));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertEquals(1, result.getChains().size(), "Only the valid chain from goodCause should survive");
        assertEquals("goodCause", result.getChains().get(0).getRootCauseNodeId());
        assertTrue(result.getPrunedHopCount() >= 1, "badCause hop must be counted as pruned");
    }

    @Test
    @DisplayName("End-to-end: TemporalAttributionResult carries correct metadata")
    void endToEnd_resultMetadataIsPopulated() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause",  T0));
        graph.addEntity(entity("effect", T2));
        graph.addRelation(causes("r1", "cause", "effect", 0.7));

        TemporalDecayConfig decay = TemporalDecayConfig.linear(Duration.ofHours(4));
        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .temporalStart(T0)
                .temporalEnd(T3)
                .decayConfig(decay)
                .maxDepth(3).maxChains(5).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertSame(decay, result.getDecayConfig(), "Decay config must be preserved in result");
        assertNotNull(result.getQueryInterval(), "Query interval must be recorded");
        assertEquals(T0, result.getQueryInterval().start(), "Query interval start must match temporalStart");
        assertEquals(T3, result.getQueryInterval().end(),   "Query interval end must match temporalEnd");
        assertNotNull(result.getComputedAt(), "computedAt must be set");
        assertTrue(result.getComputationTimeMs() >= 0, "computationTimeMs must be non-negative");
        assertTrue(result.getNodesVisited() >= 1, "At least the target node must have been visited");
    }

    @Test
    @DisplayName("TEMPORAL_PRECEDENCE evidence is present on surviving hops")
    void evidenceType_temporalPrecedenceIsRecorded() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause",  T0));
        graph.addEntity(entity("effect", T1));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(5).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty());
        AttributionChain chain = result.getChains().get(0);
        assertFalse(chain.getHops().isEmpty());
        CausalHop hop = chain.getHops().get(0);

        boolean hasPrecedenceEvidence = hop.getEvidence().stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.TEMPORAL_PRECEDENCE);
        assertTrue(hasPrecedenceEvidence,
                "Surviving hop must carry TEMPORAL_PRECEDENCE evidence");
    }

    @Test
    @DisplayName("TEMPORAL_DECAY_SCORED evidence is present when decay weight < 1.0")
    void evidenceType_temporalDecayScoredIsRecordedWhenDecayApplied() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        // T2 - T0 = 2 hours. With 1-hour halfLife, weight = 0.5^2 = 0.25 < 1.0
        graph.addEntity(entity("cause",  T0));
        graph.addEntity(entity("effect", T2));
        graph.addRelation(causes("r1", "cause", "effect"));

        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .decayConfig(TemporalDecayConfig.exponential(Duration.ofHours(1)))
                .maxDepth(3).maxChains(5).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        assertFalse(result.getChains().isEmpty());
        CausalHop hop = result.getChains().get(0).getHops().get(0);

        boolean hasDecayEvidence = hop.getEvidence().stream()
                .anyMatch(e -> e.getEvidenceType() == EvidenceType.TEMPORAL_DECAY_SCORED);
        assertTrue(hasDecayEvidence,
                "Hop decayed by EXPONENTIAL function must carry TEMPORAL_DECAY_SCORED evidence");
    }

    @Test
    @DisplayName("attributionWindow query field limits search to [effectTs - window, effectTs]")
    void attributionWindow_limitsCauseLookup() {
        // cause1 at T0 (4 hours before effect at T4) - outside 2h window
        // cause2 at T2 (2 hours before effect at T4) - exactly at the window boundary
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entity("cause1", T0));
        graph.addEntity(entity("cause2", T2));
        graph.addEntity(entity("effect", T4));
        graph.addRelation(causes("r1", "cause1", "effect", 0.9));
        graph.addRelation(causes("r2", "cause2", "effect", 0.9));

        // Window = 2 hours; temporalEnd = T4 → temporalStart computed as T4 - 2h = T2
        TemporalAttributionQuery query = TemporalAttributionQuery.forTarget("effect")
                .temporalEnd(T4)
                .attributionWindow(Duration.ofHours(2))
                .decayConfig(TemporalDecayConfig.none())
                .maxDepth(3).maxChains(10).minConfidence(0.0)
                .build();

        TemporalAttributionResult result = service.attribute(graph, query);

        // cause1 is at T0, which is before the window start T2 → should be excluded
        // cause2 is at T2, which is the window start (inclusive) → should be included
        boolean cause1InChains = result.getChains().stream()
                .anyMatch(c -> "cause1".equals(c.getRootCauseNodeId()));
        boolean cause2InChains = result.getChains().stream()
                .anyMatch(c -> "cause2".equals(c.getRootCauseNodeId()));

        assertFalse(cause1InChains, "cause1 at T0 is before window [T2, T4) — must be excluded");
        assertTrue(cause2InChains,  "cause2 at T2 is at the window boundary — must be included");
    }
}

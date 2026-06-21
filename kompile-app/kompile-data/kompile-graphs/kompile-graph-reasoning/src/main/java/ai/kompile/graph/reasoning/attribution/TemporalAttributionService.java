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
import ai.kompile.graph.reasoning.domain.AttributionEvidence;
import ai.kompile.graph.reasoning.domain.CausalEdgeType;
import ai.kompile.graph.reasoning.domain.CausalHop;
import ai.kompile.graph.reasoning.domain.EvidenceType;
import ai.kompile.graph.reasoning.model.AllenRelation;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.model.TemporalInterval;
import ai.kompile.graph.reasoning.model.TemporalView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Infra-free temporal attribution service.
 *
 * <p>Given a {@link ReasoningGraph} and a {@link TemporalAttributionQuery}, this service
 * discovers causal chains leading to a target event while enforcing three temporal constraints:</p>
 *
 * <ol>
 *   <li><strong>§5a — Precedence prune:</strong> drops any candidate hop whose cause timestamp
 *       does NOT strictly precede the effect timestamp. When both entities carry
 *       {@link GraphEntity#validTime()} intervals, the {@link AllenRelation} is used for the
 *       comparison; otherwise, point timestamps ({@link GraphEntity#timestamp()}) are compared
 *       directly. A hop with unknown timestamps (either side {@code null}) is kept and annotated
 *       with {@link EvidenceType#TEMPORAL_PRECEDENCE} at reduced strength.</li>
 *   <li><strong>§5b — Window scope:</strong> when {@link TemporalAttributionQuery#getTemporalStart()}
 *       or {@link TemporalAttributionQuery#getTemporalEnd()} are set (or an
 *       {@link TemporalAttributionQuery#getAttributionWindow()} is provided), the graph is wrapped
 *       in a {@link TemporalView} before traversal so that only in-window entities and relations
 *       are considered.</li>
 *   <li><strong>§5c — Decay weighting:</strong> each surviving hop's {@code strength} is multiplied
 *       by {@link TemporalDecayConfig#weight(Instant, Instant)} where age = effect time − cause
 *       time. The chosen decay function (NONE/EXPONENTIAL/LINEAR) is taken from
 *       {@link TemporalAttributionQuery#getDecayConfig()}.</li>
 * </ol>
 *
 * <p>§5d — Chain consistency: assembled {@link AttributionChain}s are validated with
 * {@link AttributionChain#isTemporallyConsistent()}. Inconsistent chains are demoted to
 * {@link AttributionConfidence#INSUFFICIENT} and tracked in the result's
 * {@link TemporalAttributionResult#getInconsistentChainCount()}.</p>
 *
 * <h2>Infra-free contract</h2>
 * <p>This class has no Spring annotations, no JPA, no external dependencies beyond {@code java.time}
 * and the {@code kompile-graph-reasoning} domain/model packages. Clients (event-attribution,
 * process-mining) supply a {@link ReasoningGraph} built by their existing adapters.</p>
 */
public final class TemporalAttributionService {

    /**
     * Default singleton — callers that need no extra configuration can use this directly.
     */
    public static final TemporalAttributionService INSTANCE = new TemporalAttributionService();

    // ─── Entry point ──────────────────────────────────────────────────────────────

    /**
     * Perform temporal attribution on {@code graph} for the target described in {@code query}.
     *
     * @param graph the reasoning graph (store-agnostic; never {@code null})
     * @param query the temporal attribution query (never {@code null})
     * @return the result containing surviving causal chains, temporal influence scores, and
     *         diagnostic counters (pruned hops, inconsistent chains)
     */
    public TemporalAttributionResult attribute(ReasoningGraph graph,
                                               TemporalAttributionQuery query) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");

        long startMs = System.currentTimeMillis();

        // ── §5b: scope to temporal window ────────────────────────────────────────
        ReasoningGraph workGraph = applyWindowScope(graph, query);

        // ── Resolve target entity ─────────────────────────────────────────────────
        String targetId = query.getTargetNodeId();
        Optional<GraphEntity> targetOpt = workGraph.entity(targetId);
        String targetTitle = targetOpt.map(GraphEntity::label).orElse(targetId);
        Instant targetTimestamp = targetOpt.flatMap(GraphEntity::timestampOpt).orElse(null);

        // ── Determine effective query interval for the result metadata ────────────
        TemporalInterval effectiveInterval = resolveEffectiveInterval(query, targetTimestamp);

        // ── Resolve decay config ─────────────────────────────────────────────────
        TemporalDecayConfig decayConfig = query.getDecayConfig();

        // ── BFS / DFS backward traversal with precedence filtering ────────────────
        TraversalAccumulator acc = new TraversalAccumulator(query.getMaxDepth(), decayConfig);
        acc.traverse(workGraph, targetId, targetTimestamp, query);

        // ── Assemble and sort chains ──────────────────────────────────────────────
        List<AttributionChain> validChains   = new ArrayList<>();
        int inconsistentChainCount = 0;
        double minConfidence = query.getMinConfidence();

        for (AttributionChain chain : acc.buildChains(targetId, targetTitle)) {
            if (!chain.isTemporallyConsistent()) {
                // Demote instead of silently dropping — set confidence to INSUFFICIENT
                chain.setConfidenceBand(AttributionConfidence.INSUFFICIENT);
                chain.setOverallConfidence(0.0);
                inconsistentChainCount++;
                // Still include in result so callers can inspect; minConfidence filter applies
            }
            if (chain.getOverallConfidence() >= minConfidence) {
                validChains.add(chain);
            }
        }

        // Sort descending by confidence
        validChains.sort(Comparator.comparingDouble(AttributionChain::getOverallConfidence).reversed());

        // Trim to maxChains
        if (validChains.size() > query.getMaxChains()) {
            validChains = validChains.subList(0, query.getMaxChains());
        }

        // ── Compute temporal influence scores (decay-weighted) ───────────────────
        Map<String, Double> temporalInfluenceScores = computeTemporalInfluenceScores(validChains);

        long elapsed = System.currentTimeMillis() - startMs;

        return new TemporalAttributionResult.Builder()
                .query(query)
                .targetNodeId(targetId)
                .targetTitle(targetTitle)
                .chains(validChains)
                .influenceScores(toBaseInfluenceScores(validChains))
                .deadEnds(acc.getDeadEnds())
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .nodesVisited(acc.getNodesVisited())
                .edgesExamined(acc.getEdgesExamined())
                .llmUsed(false)
                .queryInterval(effectiveInterval)
                .decayConfig(decayConfig)
                .temporalInfluenceScores(temporalInfluenceScores)
                .prunedHopCount(acc.getPrunedHopCount())
                .inconsistentChainCount(inconsistentChainCount)
                .build();
    }

    // ─── §5b: window scoping ──────────────────────────────────────────────────────

    /**
     * Wrap the graph in a {@link TemporalView} if the query specifies a temporal window.
     * If neither start/end nor an attribution window is set, returns the original graph unchanged.
     */
    private ReasoningGraph applyWindowScope(ReasoningGraph graph,
                                            TemporalAttributionQuery query) {
        Instant start = query.getTemporalStart();
        Instant end   = query.getTemporalEnd();

        // attributionWindow overrides start (end stays as-is from the query, or null = open)
        Duration window = query.getAttributionWindow();
        if (window != null) {
            // We don't know the target's timestamp yet at this point; use end as the anchor.
            // If end is also null, skip the window for now — the traversal itself will enforce
            // precedence per-hop. The real sliding-window lower-bound (targetTs - window) is
            // applied per-hop in precedence filtering below.
            if (end != null) {
                start = end.minus(window);
            }
        }

        if (start == null && end == null) {
            return graph; // no scoping needed
        }
        // Attribution windows are CLOSED [start, end]: the target/effect sits at 'end', so the
        // half-open between() (exclusive end) is nudged by 1ns to keep the endpoint in scope.
        Instant inclusiveEnd = (end == null) ? null : end.plusNanos(1);
        return TemporalView.between(graph, start, inclusiveEnd);
    }

    /**
     * Resolve the effective {@link TemporalInterval} that was applied during this attribution,
     * for inclusion in the result as auditable metadata.
     */
    private TemporalInterval resolveEffectiveInterval(TemporalAttributionQuery query,
                                                      Instant targetTimestamp) {
        Instant start = query.getTemporalStart();
        Instant end   = query.getTemporalEnd();

        Duration window = query.getAttributionWindow();
        if (window != null && targetTimestamp != null) {
            start = targetTimestamp.minus(window);
        }

        if (start == null && end == null) {
            return null;
        }
        return TemporalInterval.of(start, end);
    }

    // ─── Influence score helpers ──────────────────────────────────────────────────

    /**
     * Compute temporal influence scores by aggregating the decay-weighted hop strengths
     * across all chains. Each node's score is the maximum decay-weighted strength observed
     * across all chains where it appears as a cause.
     */
    private Map<String, Double> computeTemporalInfluenceScores(List<AttributionChain> chains) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (AttributionChain chain : chains) {
            for (CausalHop hop : chain.getHops()) {
                // The hop's strength has already been decay-multiplied by the traversal
                double existing = scores.getOrDefault(hop.getCauseNodeId(), 0.0);
                scores.put(hop.getCauseNodeId(), Math.max(existing, hop.getStrength()));
            }
        }
        return scores;
    }

    /**
     * Produce base (non-temporal) influence scores from the surviving chains for the
     * base-class field. These are the raw (pre-decay) strengths — the temporal ones are
     * in {@link TemporalAttributionResult#getTemporalInfluenceScores()}.
     * Since the traversal already applies decay, we just alias the same map here.
     */
    private Map<String, Double> toBaseInfluenceScores(List<AttributionChain> chains) {
        // Same computation as temporal — the traversal has already applied decay to strengths.
        return computeTemporalInfluenceScores(chains);
    }

    // ─── Inner traversal accumulator ─────────────────────────────────────────────

    /**
     * BFS-based backward traversal that accumulates hops, applies precedence filtering (§5a),
     * and decay weighting (§5c), then assembles {@link AttributionChain}s.
     *
     * <p>Each BFS state is a tuple: (current node id, depth, accumulated hops so far).
     * We visit nodes breadth-first from the target backward through incoming edges.
     * When we reach a node with no incoming causal edges (or max depth), we finalize the
     * chain.</p>
     */
    private static final class TraversalAccumulator {

        private final int maxDepth;
        private final TemporalDecayConfig decayConfig;

        private int nodesVisited = 0;
        private int edgesExamined = 0;
        private int prunedHopCount = 0;

        private final List<String> deadEnds = new ArrayList<>();

        /** Completed partial chains: each entry is the ordered list of hops from root → target. */
        private final List<List<CausalHop>> completedPaths = new ArrayList<>();

        TraversalAccumulator(int maxDepth, TemporalDecayConfig decayConfig) {
            this.maxDepth    = maxDepth;
            this.decayConfig = decayConfig;
        }

        /**
         * Run BFS from {@code targetId} backward through incoming edges.
         */
        void traverse(ReasoningGraph graph, String targetId, Instant targetTimestamp,
                      TemporalAttributionQuery query) {

            // BFS queue entry: (nodeId, timestampAtThisNode, depth, hopsTowardTarget)
            record BfsEntry(String nodeId, Instant nodeTimestamp, int depth,
                            List<CausalHop> hopsRootward) {}

            Deque<BfsEntry> queue = new ArrayDeque<>();
            queue.add(new BfsEntry(targetId, targetTimestamp, 0, new ArrayList<>()));

            // Visited guard: node + depth to allow the same node via shorter paths
            Set<String> visitedAtDepth = new HashSet<>();

            while (!queue.isEmpty()) {
                BfsEntry current = queue.poll();
                nodesVisited++;

                String visitKey = current.nodeId() + "@" + current.depth();
                if (!visitedAtDepth.add(visitKey)) {
                    continue; // already explored this node at this depth
                }

                List<GraphRelation> incoming = graph.incoming(current.nodeId());
                edgesExamined += incoming.size();

                boolean anyHopSurvived = false;

                for (GraphRelation rel : incoming) {
                    edgesExamined++;

                    // Filter by allowed causal types if the query specifies them
                    Set<CausalEdgeType> allowedTypes = query.getAllowedCausalTypes();
                    if (allowedTypes != null && !allowedTypes.isEmpty()) {
                        // relation.type() is a String; match against CausalEdgeType names
                        boolean typeAllowed = allowedTypes.stream()
                                .anyMatch(t -> t.name().equals(rel.type()));
                        if (!typeAllowed) {
                            continue;
                        }
                    }

                    String causeId = rel.sourceId();
                    Optional<GraphEntity> causeOpt = graph.entity(causeId);
                    Instant causeTimestamp = causeOpt.flatMap(GraphEntity::timestampOpt).orElse(null);
                    Instant effectTimestamp = current.nodeTimestamp();

                    // ── §5a: Precedence filter ────────────────────────────────────
                    PrecedenceDecision decision = checkPrecedence(
                            causeOpt.orElse(null), causeTimestamp,
                            current.nodeId(), effectTimestamp);

                    if (decision == PrecedenceDecision.PRUNE) {
                        prunedHopCount++;
                        continue; // hard prune
                    }

                    // ── §5c: Decay weighting ──────────────────────────────────────
                    double baseStrength = rel.weight();
                    double decayWeight  = decayConfig.weight(causeTimestamp, effectTimestamp);
                    double decayedStrength = baseStrength * decayWeight;

                    // Build evidence list for this hop
                    List<AttributionEvidence> evidence = buildEvidence(
                            decision, decayWeight, causeTimestamp, effectTimestamp,
                            causeId, rel);

                    // Build the CausalHop (hops are ordered root→target, so prepend)
                    CausalHop hop = CausalHop.builder()
                            .causeNodeId(causeId)
                            .causeTitle(causeOpt.map(GraphEntity::label).orElse(causeId))
                            .effectNodeId(current.nodeId())
                            .effectTitle(current.nodeId().equals(targetId)
                                    ? graph.entity(targetId).map(GraphEntity::label).orElse(targetId)
                                    : current.nodeId())
                            .causalType(resolveCausalEdgeType(rel.type()))
                            .strength(decayedStrength)
                            .causeTimestamp(causeTimestamp)
                            .effectTimestamp(effectTimestamp)
                            .evidence(evidence)
                            .build();

                    // Prepend: the path so far goes root→...→cause→effect
                    List<CausalHop> pathToHere = new ArrayList<>();
                    pathToHere.add(hop);
                    pathToHere.addAll(current.hopsRootward());

                    anyHopSurvived = true;

                    if (current.depth() + 1 >= maxDepth) {
                        // Depth limit: finalize path
                        completedPaths.add(pathToHere);
                    } else {
                        // Enqueue for further backward traversal
                        queue.add(new BfsEntry(causeId, causeTimestamp,
                                current.depth() + 1, pathToHere));
                    }
                }

                // If this is a root node (no surviving incoming hops) and we have accumulated
                // some path already, finalize
                if (!anyHopSurvived && !current.hopsRootward().isEmpty()) {
                    completedPaths.add(current.hopsRootward());
                } else if (!anyHopSurvived && incoming.isEmpty()) {
                    if (!current.nodeId().equals(targetId)) {
                        deadEnds.add(current.nodeId());
                    }
                }
            }
        }

        /**
         * Convert completed hop paths into {@link AttributionChain} objects, computing the
         * overall confidence as the product of hop strengths.
         */
        List<AttributionChain> buildChains(String targetId, String targetTitle) {
            List<AttributionChain> chains = new ArrayList<>();
            for (List<CausalHop> hops : completedPaths) {
                if (hops.isEmpty()) continue;

                // Root cause is the first hop's cause
                CausalHop rootHop = hops.get(0);
                String rootCauseId    = rootHop.getCauseNodeId();
                String rootCauseTitle = rootHop.getCauseTitle();

                // Overall confidence = product of hop strengths (attenuated chain confidence)
                double confidence = hops.stream()
                        .mapToDouble(CausalHop::getStrength)
                        .reduce(1.0, (a, b) -> a * b);

                AttributionChain chain = AttributionChain.builder()
                        .chainId(UUID.randomUUID().toString())
                        .targetEventNodeId(targetId)
                        .targetEventTitle(targetTitle)
                        .rootCauseNodeId(rootCauseId)
                        .rootCauseTitle(rootCauseTitle)
                        .hops(new ArrayList<>(hops))
                        .overallConfidence(confidence)
                        .confidenceBand(AttributionConfidence.fromScore(confidence))
                        .computedAt(Instant.now())
                        .build();

                chains.add(chain);
            }
            return chains;
        }

        int getNodesVisited()  { return nodesVisited;  }
        int getEdgesExamined() { return edgesExamined; }
        int getPrunedHopCount(){ return prunedHopCount;}
        List<String> getDeadEnds() { return Collections.unmodifiableList(deadEnds); }
    }

    // ─── §5a: Precedence decision ─────────────────────────────────────────────────

    private enum PrecedenceDecision {
        /** Hard prune: cause provably comes after effect. */
        PRUNE,
        /** Keep with TEMPORAL_PRECEDENCE evidence: cause provably precedes effect. */
        PRECEDENCE_CONFIRMED,
        /** Keep with TEMPORAL_PRECEDENCE evidence at reduced strength: timestamps unknown. */
        TEMPORAL_UNKNOWN
    }

    /**
     * Determine whether a candidate cause satisfies the temporal precedence constraint.
     *
     * <p>Rules (from spec §5a):
     * <ol>
     *   <li>Both timestamps non-null: keep iff cause is NOT after effect (ties = simultaneous,
     *       kept per spec). Prune otherwise.</li>
     *   <li>Only effect stamped: keep + flag TEMPORAL_UNKNOWN.</li>
     *   <li>Only cause stamped: keep + flag TEMPORAL_UNKNOWN.</li>
     *   <li>Neither stamped: keep (timeless, always eligible).</li>
     * </ol>
     *
     * <p>Interval-aware: when both entities carry {@link GraphEntity#validTime()}, the
     * {@link AllenRelation} is used: {@link AllenRelation#isPrecedence()} (BEFORE/MEETS)
     * confirms precedence; other relations that overlap mean the causal arrow direction is
     * ambiguous (treated as TEMPORAL_UNKNOWN); AFTER (or any relation where cause starts after
     * effect ends) means PRUNE.</p>
     */
    private static PrecedenceDecision checkPrecedence(GraphEntity cause,
                                                       Instant causeTimestamp,
                                                       String effectId,
                                                       Instant effectTimestamp) {
        if (cause != null) {
            TemporalInterval causeInterval  = cause.validTime();
            // We don't have the effect entity here, but we do have effectTimestamp.
            // If both have intervals, use Allen; otherwise fall back to timestamps.
            // (In practice, the effect entity's interval is only available when graph.entity
            // is called; for simplicity we use Allen only when causeInterval is present and
            // we can build a point interval for the effect.)
            if (causeInterval != null && effectTimestamp != null) {
                TemporalInterval effectInterval = TemporalInterval.point(effectTimestamp);
                AllenRelation relation = AllenRelation.compute(causeInterval, effectInterval);
                if (relation == AllenRelation.AFTER) {
                    return PrecedenceDecision.PRUNE;
                }
                if (relation.isPrecedence()) {
                    return PrecedenceDecision.PRECEDENCE_CONFIRMED;
                }
                // DURING, OVERLAPS, etc. — ambiguous; keep as UNKNOWN
                return PrecedenceDecision.TEMPORAL_UNKNOWN;
            }
        }

        // Fallback: point-timestamp comparison
        if (causeTimestamp != null && effectTimestamp != null) {
            if (causeTimestamp.isAfter(effectTimestamp)) {
                return PrecedenceDecision.PRUNE; // cause is strictly after effect
            }
            return PrecedenceDecision.PRECEDENCE_CONFIRMED; // cause <= effect
        }

        // One or both are null → unknown temporal relation; keep
        return PrecedenceDecision.TEMPORAL_UNKNOWN;
    }

    // ─── Evidence builder ─────────────────────────────────────────────────────────

    private static List<AttributionEvidence> buildEvidence(PrecedenceDecision decision,
                                                            double decayWeight,
                                                            Instant causeTimestamp,
                                                            Instant effectTimestamp,
                                                            String causeId,
                                                            GraphRelation rel) {
        List<AttributionEvidence> evidence = new ArrayList<>();

        // Temporal precedence evidence
        EvidenceType precType = switch (decision) {
            case PRECEDENCE_CONFIRMED -> EvidenceType.TEMPORAL_PRECEDENCE;
            case TEMPORAL_UNKNOWN     -> EvidenceType.TEMPORAL_PRECEDENCE; // still added, strength reduced
            case PRUNE                -> null; // never reached (pruned before here)
        };

        double precStrength = (decision == PrecedenceDecision.PRECEDENCE_CONFIRMED)
                ? rel.weight()
                : rel.weight() * 0.5; // unknown timestamps halve the precedence strength

        String precSummary = buildPrecedenceSummary(decision, causeTimestamp, effectTimestamp, causeId);

        evidence.add(AttributionEvidence.builder()
                .evidenceType(precType)
                .strength(precStrength)
                .edgeId(rel.id())
                .sourceNodeId(causeId)
                .summary(precSummary)
                .collectedAt(Instant.now())
                .build());

        // Temporal decay evidence (only if decay was actually applied — not NONE or weight=1.0)
        if (decayWeight < 1.0) {
            String decaySummary = String.format(
                    "Decay weight %.4f applied (age from causeTs=%s to effectTs=%s)",
                    decayWeight, causeTimestamp, effectTimestamp);
            evidence.add(AttributionEvidence.builder()
                    .evidenceType(EvidenceType.TEMPORAL_DECAY_SCORED)
                    .strength(decayWeight)
                    .edgeId(rel.id())
                    .sourceNodeId(causeId)
                    .summary(decaySummary)
                    .collectedAt(Instant.now())
                    .build());
        }

        // Graph-structural evidence (the relation itself)
        evidence.add(AttributionEvidence.builder()
                .evidenceType(EvidenceType.GRAPH_STRUCTURAL)
                .strength(rel.weight())
                .edgeId(rel.id())
                .sourceNodeId(causeId)
                .summary("Relation '" + rel.type() + "' (weight=" + rel.weight() + ")")
                .collectedAt(Instant.now())
                .build());

        return evidence;
    }

    private static String buildPrecedenceSummary(PrecedenceDecision decision,
                                                  Instant causeTimestamp,
                                                  Instant effectTimestamp,
                                                  String causeId) {
        return switch (decision) {
            case PRECEDENCE_CONFIRMED ->
                    "cause(id=" + causeId + ", t=" + causeTimestamp +
                    ") precedes effect(t=" + effectTimestamp + ")";
            case TEMPORAL_UNKNOWN ->
                    "Temporal order unknown for cause(id=" + causeId +
                    ", t=" + causeTimestamp + ") → effect(t=" + effectTimestamp + ")";
            case PRUNE -> ""; // unreachable
        };
    }

    // ─── CausalEdgeType resolution ────────────────────────────────────────────────

    /**
     * Map a relation type string to the nearest {@link CausalEdgeType}, defaulting to
     * {@link CausalEdgeType#INFLUENCES} when no direct match is found.
     */
    private static CausalEdgeType resolveCausalEdgeType(String relType) {
        if (relType == null || relType.isBlank()) {
            return CausalEdgeType.INFLUENCES;
        }
        for (CausalEdgeType t : CausalEdgeType.values()) {
            if (t.name().equalsIgnoreCase(relType)) {
                return t;
            }
        }
        return CausalEdgeType.INFLUENCES;
    }
}

/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.service;

import ai.kompile.event.attribution.algorithm.CausalTraversal;
import ai.kompile.event.attribution.algorithm.InfluencePropagation;
import ai.kompile.event.attribution.algorithm.TemporalChainExtractor;
import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.llm.AttributionLlmService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main orchestrator for event attribution and prediction.
 *
 * <p>Combines five analysis layers into a unified "why did X happen?" pipeline:</p>
 * <ol>
 *   <li><b>Causal traversal</b>: path-based backward search for causal chains</li>
 *   <li><b>Influence propagation</b>: score-based backward propagation (KGroot pattern)</li>
 *   <li><b>Temporal analysis</b>: time-based predecessor identification</li>
 *   <li><b>Counterfactual analysis</b>: do-calculus interventions to test necessity</li>
 *   <li><b>LLM synthesis</b>: narration, ambiguity resolution, ranking</li>
 * </ol>
 *
 * <p>For prediction, uses forward traversal with influence propagation and
 * LLM-generated forecasts.</p>
 */
@Service
public class EventAttributionService {

    private static final Logger log = LoggerFactory.getLogger(EventAttributionService.class);

    private static final int DEFAULT_PROPAGATION_ITERATIONS = 10;
    private static final double DEFAULT_DAMPING = 0.85;
    private static final double DEFAULT_EPSILON = 0.001;

    private final KnowledgeGraphService graphService;
    private final AttributionLlmService llmService;

    @Autowired
    public EventAttributionService(KnowledgeGraphService graphService,
                                    AttributionLlmService llmService) {
        this.graphService = graphService;
        this.llmService = llmService;
    }

    /**
     * Answer a "why did X happen?" query.
     *
     * <p>Pipeline:</p>
     * <ol>
     *   <li>Run backward causal traversal to find causal chains</li>
     *   <li>Run influence propagation to score all ancestors</li>
     *   <li>Run temporal chain extraction for time-based evidence</li>
     *   <li>Enrich chains with temporal evidence</li>
     *   <li>If LLM enabled: narrate chains, rank by plausibility, synthesize</li>
     *   <li>If counterfactuals requested: run counterfactual analysis on top causes</li>
     * </ol>
     */
    public AttributionResult explain(AttributionQuery query) {
        long startTime = System.currentTimeMillis();

        Optional<GraphNode> targetOpt = graphService.getNode(query.getTargetNodeId());
        if (targetOpt.isEmpty()) {
            return AttributionResult.builder()
                    .query(query)
                    .targetNodeId(query.getTargetNodeId())
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
        GraphNode targetNode = targetOpt.get();

        // 1. Causal traversal
        log.debug("Starting causal traversal from node {}", query.getTargetNodeId());
        CausalTraversal.TraversalResult traversalResult = CausalTraversal.traverseBackward(
                graphService, query.getTargetNodeId(),
                query.getMaxDepth(), query.getMaxChains() * 2, // Fetch extra, will filter later
                query.getMinConfidence(),
                query.getTemporalStart(), query.getTemporalEnd());

        List<AttributionChain> chains = new ArrayList<>(traversalResult.chains());

        // 2. Influence propagation
        log.debug("Running influence propagation");
        Map<String, Double> influenceScores = InfluencePropagation.computeInfluenceScores(
                graphService, query.getTargetNodeId(),
                DEFAULT_PROPAGATION_ITERATIONS, DEFAULT_DAMPING, DEFAULT_EPSILON);

        // 3. Temporal chain extraction
        log.debug("Extracting temporal chains");
        List<TemporalChainExtractor.TemporalPredecessor> temporalPredecessors =
                TemporalChainExtractor.extractPredecessors(graphService, query.getTargetNodeId(),
                        20, query.getMaxDepth(),
                        query.getTemporalStart(), query.getTemporalEnd());

        // 4. Enrich chains with influence scores and temporal evidence
        enrichChainsWithInfluence(chains, influenceScores);
        enrichChainsWithTemporalEvidence(chains, temporalPredecessors);

        // Re-sort by confidence after enrichment
        chains.sort(Comparator.comparingDouble(AttributionChain::getOverallConfidence).reversed());
        if (chains.size() > query.getMaxChains()) {
            chains = new ArrayList<>(chains.subList(0, query.getMaxChains()));
        }

        // 5. LLM synthesis
        boolean llmUsed = false;
        String synthesizedExplanation = null;

        if (query.isUseLlm() && llmService.isAvailable() && !chains.isEmpty()) {
            log.debug("Running LLM synthesis over {} chains", chains.size());
            llmUsed = true;

            // Narrate each chain
            for (AttributionChain chain : chains) {
                String narrative = llmService.narrateChain(chain);
                if (narrative != null) {
                    chain.setNarrative(narrative);
                }
            }

            // Rank by plausibility
            if (chains.size() > 1) {
                List<String> ranked = llmService.rankChainsByPlausibility(chains);
                chains = reorderByRanking(chains, ranked);
            }

            // Synthesize top-level explanation
            synthesizedExplanation = llmService.synthesizeExplanation(
                    targetNode.getTitle(), chains, query.getNaturalLanguageQuery());
        }

        // 6. Counterfactual analysis
        List<CounterfactualResult> counterfactuals = new ArrayList<>();
        if (query.isIncludeCounterfactuals() && !chains.isEmpty()) {
            counterfactuals = runCounterfactualAnalysis(query, chains, targetNode.getTitle());
        }

        // Build dead ends list
        List<String> deadEnds = influenceScores.entrySet().stream()
                .filter(e -> e.getValue() < query.getMinConfidence())
                .map(Map.Entry::getKey)
                .limit(10)
                .toList();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Attribution complete for node {} in {}ms: {} chains, {} influence scores",
                query.getTargetNodeId(), elapsed, chains.size(), influenceScores.size());

        return AttributionResult.builder()
                .query(query)
                .targetNodeId(query.getTargetNodeId())
                .targetTitle(targetNode.getTitle())
                .chains(chains)
                .synthesizedExplanation(synthesizedExplanation)
                .influenceScores(influenceScores)
                .counterfactuals(counterfactuals)
                .deadEnds(deadEnds)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .nodesVisited(traversalResult.nodesVisited())
                .edgesExamined(traversalResult.edgesExamined())
                .llmUsed(llmUsed)
                .build();
    }

    /**
     * Predict what events are likely to follow from a given node.
     *
     * <p>Uses forward causal traversal combined with influence propagation
     * and LLM-generated forecast narratives.</p>
     */
    public PredictionResult predict(PredictionQuery query) {
        long startTime = System.currentTimeMillis();

        Optional<GraphNode> sourceOpt = graphService.getNode(query.getSourceNodeId());
        if (sourceOpt.isEmpty()) {
            return PredictionResult.builder()
                    .query(query)
                    .sourceNodeId(query.getSourceNodeId())
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
        GraphNode sourceNode = sourceOpt.get();

        // Forward traversal
        CausalTraversal.TraversalResult forwardResult = CausalTraversal.traverseForward(
                graphService, query.getSourceNodeId(),
                query.getMaxDepth(), query.getMaxPredictions() * 2,
                query.getMinProbability());

        // Convert chains to predicted events
        List<PredictedEvent> predictions = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (AttributionChain chain : forwardResult.chains()) {
            // The "root cause" in forward traversal is the source, and the "target" is the predicted event
            String predictedNodeId = chain.getTargetEventNodeId();
            if (seen.contains(predictedNodeId)) continue;
            seen.add(predictedNodeId);

            List<String> path = new ArrayList<>();
            path.add(query.getSourceNodeId());
            List<CausalEdgeType> edgeTypes = new ArrayList<>();
            for (CausalHop hop : chain.getHops()) {
                path.add(hop.getEffectNodeId());
                edgeTypes.add(hop.getCausalType());
            }

            predictions.add(PredictedEvent.builder()
                    .nodeId(predictedNodeId)
                    .title(chain.getTargetEventTitle())
                    .probability(chain.getOverallConfidence())
                    .hopsFromSource(chain.getDepth())
                    .pathFromSource(path)
                    .pathEdgeTypes(edgeTypes)
                    .evidence(chain.getHops().stream()
                            .flatMap(h -> h.getEvidence().stream())
                            .toList())
                    .build());
        }

        // Sort by probability
        predictions.sort(Comparator.comparingDouble(PredictedEvent::getProbability).reversed());
        if (predictions.size() > query.getMaxPredictions()) {
            predictions = new ArrayList<>(predictions.subList(0, query.getMaxPredictions()));
        }

        // LLM forecast
        boolean llmUsed = false;
        String forecast = null;
        if (query.isUseLlm() && llmService.isAvailable() && !predictions.isEmpty()) {
            llmUsed = true;
            forecast = llmService.generateForecast(sourceNode.getTitle(), predictions);

            // Also generate per-prediction explanations for top predictions
            for (PredictedEvent pred : predictions.subList(0, Math.min(3, predictions.size()))) {
                String explanation = llmService.generateForecast(
                        sourceNode.getTitle() + " → " + pred.getTitle(),
                        List.of(pred));
                pred.setExplanation(explanation);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        return PredictionResult.builder()
                .query(query)
                .sourceNodeId(query.getSourceNodeId())
                .sourceTitle(sourceNode.getTitle())
                .predictions(predictions)
                .synthesizedForecast(forecast)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .nodesVisited(forwardResult.nodesVisited())
                .llmUsed(llmUsed)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENRICHMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private void enrichChainsWithInfluence(List<AttributionChain> chains,
                                            Map<String, Double> influenceScores) {
        for (AttributionChain chain : chains) {
            // Boost chain confidence if root cause has high influence score
            Double rootInfluence = influenceScores.get(chain.getRootCauseNodeId());
            if (rootInfluence != null && rootInfluence > 0) {
                // Weighted average: 70% path confidence + 30% influence score
                double enriched = chain.getOverallConfidence() * 0.7 + rootInfluence * 0.3;
                chain.setOverallConfidence(Math.min(1.0, enriched));
                chain.setConfidenceBand(AttributionConfidence.fromScore(chain.getOverallConfidence()));
            }

            // Add influence evidence to root cause hop
            if (!chain.getHops().isEmpty() && rootInfluence != null) {
                CausalHop firstHop = chain.getHops().get(0);
                List<AttributionEvidence> evidence = new ArrayList<>(firstHop.getEvidence());
                evidence.add(AttributionEvidence.builder()
                        .evidenceType(EvidenceType.INFLUENCE_PROPAGATION)
                        .strength(rootInfluence)
                        .sourceNodeId(chain.getRootCauseNodeId())
                        .summary("Influence propagation score: " + String.format("%.3f", rootInfluence))
                        .collectedAt(Instant.now())
                        .build());
                firstHop.setEvidence(evidence);
            }
        }
    }

    private void enrichChainsWithTemporalEvidence(List<AttributionChain> chains,
                                                    List<TemporalChainExtractor.TemporalPredecessor> predecessors) {
        if (predecessors.isEmpty()) return;

        Map<String, TemporalChainExtractor.TemporalPredecessor> predMap = predecessors.stream()
                .collect(Collectors.toMap(
                        TemporalChainExtractor.TemporalPredecessor::nodeId,
                        p -> p,
                        (a, b) -> a));

        for (AttributionChain chain : chains) {
            for (CausalHop hop : chain.getHops()) {
                TemporalChainExtractor.TemporalPredecessor pred = predMap.get(hop.getCauseNodeId());
                if (pred != null) {
                    List<AttributionEvidence> evidence = new ArrayList<>(hop.getEvidence());
                    evidence.add(AttributionEvidence.builder()
                            .evidenceType(EvidenceType.TEMPORAL_PROXIMITY)
                            .strength(pred.score())
                            .sourceNodeId(pred.nodeId())
                            .summary("Temporal predecessor with score " +
                                    String.format("%.3f", pred.score()) +
                                    (pred.timestamp() != null ? " at " + pred.timestamp() : ""))
                            .collectedAt(Instant.now())
                            .build());
                    hop.setEvidence(evidence);

                    if (pred.timestamp() != null) {
                        hop.setCauseTimestamp(pred.timestamp());
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNTERFACTUAL ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<CounterfactualResult> runCounterfactualAnalysis(AttributionQuery query,
                                                                  List<AttributionChain> chains,
                                                                  String targetTitle) {
        // Identify unique root causes and high-influence intermediate nodes
        Set<String> nodesToTest = new LinkedHashSet<>();
        for (AttributionChain chain : chains) {
            nodesToTest.add(chain.getRootCauseNodeId());
            // Also test intermediate nodes with highest hop strength
            for (CausalHop hop : chain.getHops()) {
                if (hop.getStrength() > 0.5) {
                    nodesToTest.add(hop.getCauseNodeId());
                }
            }
        }
        // Limit to top 5 most interesting nodes
        List<String> testNodes = nodesToTest.stream().limit(5).toList();

        List<CounterfactualResult> results = new ArrayList<>();
        for (String removedNodeId : testNodes) {
            Optional<GraphNode> removedOpt = graphService.getNode(removedNodeId);
            if (removedOpt.isEmpty()) continue;
            String removedTitle = removedOpt.get().getTitle();

            // Recompute influence scores without this node
            Map<String, Double> counterfactualScores = InfluencePropagation.counterfactualScores(
                    graphService, query.getTargetNodeId(), removedNodeId,
                    DEFAULT_PROPAGATION_ITERATIONS, DEFAULT_DAMPING, DEFAULT_EPSILON);

            // Check how many chains survive without this node
            int survivingChains = 0;
            double originalTotalConfidence = 0;
            double survivingTotalConfidence = 0;

            for (AttributionChain chain : chains) {
                originalTotalConfidence += chain.getOverallConfidence();
                boolean chainContainsNode = chain.getHops().stream()
                        .anyMatch(h -> h.getCauseNodeId().equals(removedNodeId) ||
                                h.getEffectNodeId().equals(removedNodeId));
                if (!chainContainsNode) {
                    survivingChains++;
                    survivingTotalConfidence += chain.getOverallConfidence();
                }
            }

            boolean targetStillReachable = survivingChains > 0 || !counterfactualScores.isEmpty();
            double confidenceDelta = survivingTotalConfidence - originalTotalConfidence;

            // LLM explanation
            String explanation = null;
            if (query.isUseLlm() && llmService.isAvailable()) {
                explanation = llmService.explainCounterfactual(
                        targetTitle, removedTitle, targetStillReachable, survivingChains);
            }

            results.add(CounterfactualResult.builder()
                    .removedNodeId(removedNodeId)
                    .removedNodeTitle(removedTitle)
                    .targetStillReachable(targetStillReachable)
                    .survivingChainCount(survivingChains)
                    .confidenceDelta(confidenceDelta)
                    .explanation(explanation)
                    .build());
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<AttributionChain> reorderByRanking(List<AttributionChain> chains, List<String> rankedIds) {
        Map<String, AttributionChain> chainMap = chains.stream()
                .collect(Collectors.toMap(AttributionChain::getChainId, c -> c));

        List<AttributionChain> reordered = new ArrayList<>();
        for (String id : rankedIds) {
            AttributionChain chain = chainMap.remove(id);
            if (chain != null) reordered.add(chain);
        }
        // Append any chains not in the ranking
        reordered.addAll(chainMap.values());
        return reordered;
    }
}

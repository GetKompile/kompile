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

import ai.kompile.event.attribution.algorithm.bayesian.*;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.*;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.GraphKnowledgeBase;
import ai.kompile.event.attribution.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.domain.InferenceStep;
import ai.kompile.event.attribution.domain.MpeResult;
import ai.kompile.event.attribution.domain.SensitivityResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service for constructing Bayesian networks from the knowledge graph and
 * performing probabilistic inference.
 *
 * <p>Bridges the existing attribution framework's edge weights and causal
 * classifications into proper Bayesian networks with conditional probability
 * tables (CPTs) built via the noisy-OR model.</p>
 *
 * <h3>Usage Patterns</h3>
 * <ul>
 *   <li><b>Posterior query</b>: "Given that node X is observed TRUE, what is
 *       the probability that node Y is TRUE?" — uses variable elimination</li>
 *   <li><b>Most probable explanation</b>: "Given evidence, what is the most
 *       likely state of all variables?" — for root cause diagnosis</li>
 *   <li><b>Risk assessment</b>: build a BN from process-bound KG nodes,
 *       set observed failures as evidence, query unobserved nodes for risk</li>
 * </ul>
 */
@Service
public class BayesianNetworkService {

    private static final Logger log = LoggerFactory.getLogger(BayesianNetworkService.class);

    private final KnowledgeGraphService graphService;

    @Autowired
    public BayesianNetworkService(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Build a Bayesian network from a subgraph centered on the given seed nodes.
     *
     * @param seedNodeIds KG node IDs to start BFS from
     * @param maxDepth    maximum traversal depth
     * @param maxNodes    maximum nodes in the network
     * @return the constructed Bayesian network
     */
    public BayesianNetwork buildNetwork(Collection<String> seedNodeIds, int maxDepth, int maxNodes) {
        return new BayesianNetworkBuilder(graphService)
                .maxDepth(maxDepth)
                .maxNodes(maxNodes)
                .build(seedNodeIds);
    }

    /**
     * Build a Bayesian network from a single target node.
     */
    public BayesianNetwork buildFromTarget(String targetNodeId, int maxDepth, int maxNodes) {
        return new BayesianNetworkBuilder(graphService)
                .maxDepth(maxDepth)
                .maxNodes(maxNodes)
                .buildFromTarget(targetNodeId);
    }

    /**
     * Query posterior probabilities for a specific variable given evidence.
     *
     * @param seedNodeIds KG node IDs defining the network scope
     * @param queryNodeId KG node ID to query
     * @param evidence    map of KG node ID → observed state (TRUE=1, FALSE=0)
     * @param maxDepth    maximum network depth
     * @param maxNodes    maximum network size
     * @return inference result with posterior probabilities
     */
    public BayesianInferenceResult queryPosterior(Collection<String> seedNodeIds,
                                                    String queryNodeId,
                                                    Map<String, Integer> evidence,
                                                    int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);

        // Translate KG node IDs to BN variable names
        String queryVar = network.getVariableForKgNodeId(queryNodeId);
        if (queryVar == null) {
            log.warn("Query node {} not found in network", queryNodeId);
            return BayesianInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        Map<String, Integer> bnEvidence = translateEvidence(network, evidence);

        // Run inference
        Factor posterior = VariableElimination.query(network, queryVar, bnEvidence);

        // Build result
        Map<String, Double> posteriors = new LinkedHashMap<>();
        double pTrue = posterior.getValues().length > 1 ? posterior.getValue(1) : posterior.getValue(0);
        posteriors.put(queryVar, pTrue);

        return buildResult(network, posteriors, bnEvidence, startTime);
    }

    /**
     * Query posterior probabilities for ALL variables given evidence.
     *
     * @param seedNodeIds KG node IDs defining the network scope
     * @param evidence    map of KG node ID → observed state
     * @param maxDepth    maximum network depth
     * @param maxNodes    maximum network size
     * @return inference result with all posterior probabilities
     */
    public BayesianInferenceResult queryAllPosteriors(Collection<String> seedNodeIds,
                                                       Map<String, Integer> evidence,
                                                       int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);
        Map<String, Integer> bnEvidence = translateEvidence(network, evidence);

        Map<String, Double> posteriors = VariableElimination.queryAll(network, bnEvidence);

        return buildResult(network, posteriors, bnEvidence, startTime);
    }

    /**
     * Find the most probable explanation: the most likely state of all variables
     * given the evidence, with full explainability (priors, posteriors, inference trace).
     *
     * @param seedNodeIds KG node IDs defining the network scope
     * @param evidence    map of KG node ID → observed state
     * @param maxDepth    maximum network depth
     * @param maxNodes    maximum network size
     * @return MpeResult with assignments, posteriors, priors, and inference trace
     */
    public MpeResult mostProbableExplanation(Collection<String> seedNodeIds,
                                                Map<String, Integer> evidence,
                                                int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);
        Map<String, Integer> bnEvidence = translateEvidence(network, evidence);

        Map<String, Integer> mpe = VariableElimination.mostProbableExplanation(network, bnEvidence);

        // Translate MPE assignments back to KG node IDs
        Map<String, Integer> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : mpe.entrySet()) {
            BayesianNode node = network.getNode(entry.getKey());
            if (node != null) {
                assignments.put(node.getKgNodeId(), entry.getValue());
            }
        }

        // Also compute posteriors and priors for full explainability
        Map<String, Double> posteriors = VariableElimination.queryAll(network, bnEvidence);
        Map<String, Double> priors;
        try {
            priors = VariableElimination.queryAll(network, Map.of());
        } catch (Exception e) {
            log.debug("Could not compute priors for MPE: {}", e.getMessage());
            priors = new LinkedHashMap<>();
        }

        List<InferenceStep> inferenceTrace = collectInferenceTrace(network, bnEvidence);

        Map<String, String> varToNodeId = new LinkedHashMap<>();
        Map<String, String> varToTitle = new LinkedHashMap<>();
        for (BayesianNode node : network.getNodes()) {
            varToNodeId.put(node.getVariableName(), node.getKgNodeId());
            varToTitle.put(node.getVariableName(), node.getTitle());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("MPE inference complete in {}ms: {} assignments, {} evidence vars",
                elapsed, assignments.size(), bnEvidence.size());

        return MpeResult.builder()
                .assignments(assignments)
                .posteriors(posteriors)
                .priors(priors)
                .evidence(bnEvidence)
                .inferenceTrace(inferenceTrace)
                .variableToNodeId(varToNodeId)
                .variableToTitle(varToTitle)
                .networkStats(network.getStatistics())
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .build();
    }

    /**
     * Get the network statistics without running inference.
     */
    public Map<String, Object> getNetworkStatistics(Collection<String> seedNodeIds,
                                                      int maxDepth, int maxNodes) {
        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);
        return network.getStatistics();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEBN: MULTI-ENTITY BAYESIAN NETWORKS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build an SSBN (Situation-Specific Bayesian Network) from an MTheory and
     * run inference.
     *
     * <p>This is the MEBN path: instead of directly converting the KG subgraph,
     * it uses an MTheory (collection of MFrag templates) to define the probabilistic
     * model. The MTheory is grounded against the actual KG entities to produce
     * a concrete BN, then variable elimination runs as usual.</p>
     *
     * @param mTheory    the multi-entity Bayesian network theory
     * @param evidence   map of grounded variable name → observed state index
     * @return inference result with all posterior probabilities
     */
    public BayesianInferenceResult queryWithMTheory(MTheory mTheory,
                                                      Map<String, Integer> evidence) {
        long startTime = System.currentTimeMillis();

        GraphKnowledgeBase kb = new GraphKnowledgeBase(graphService);

        // Register entity types for quantifier evaluation
        for (EntityType entityType : mTheory.getEntityTypes()) {
            kb.registerEntityType(entityType.getTypeName(), entityType.getEntityIds());
        }

        SSBNGenerator generator = new SSBNGenerator(mTheory, kb);
        BayesianNetwork network = generator.generate();

        if (network.size() == 0) {
            return BayesianInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        Map<String, Double> posteriors = VariableElimination.queryAll(network, evidence);

        BayesianInferenceResult result = buildResult(network, posteriors, evidence, startTime);

        // Enrich with MEBN metadata: which MFrag, role, entity type for each variable
        result.setVariableToMebnMeta(buildMebnMeta(mTheory, network));

        return result;
    }

    /**
     * Build a query-focused SSBN and run inference for a specific variable.
     *
     * @param mTheory       the MTheory
     * @param queryRvName   the random variable to query (e.g. "isActive")
     * @param queryEntityId the entity ID to substitute (for grounding)
     * @param evidence      evidence map
     * @return inference result
     */
    public BayesianInferenceResult queryMTheoryVariable(MTheory mTheory,
                                                          String queryRvName,
                                                          String queryEntityId,
                                                          Map<String, Integer> evidence) {
        long startTime = System.currentTimeMillis();

        GraphKnowledgeBase kb = new GraphKnowledgeBase(graphService);
        for (EntityType entityType : mTheory.getEntityTypes()) {
            kb.registerEntityType(entityType.getTypeName(), entityType.getEntityIds());
        }

        SSBNGenerator generator = new SSBNGenerator(mTheory, kb);
        BayesianNetwork network = generator.generateForQuery(queryRvName);

        if (network.size() == 0) {
            return BayesianInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // Find the grounded variable name
        String groundedQuery = queryRvName + "(" + queryEntityId + ")";
        BayesianNode queryNode = network.getNode(groundedQuery);
        if (queryNode == null) {
            // Try without parentheses (propositional)
            queryNode = network.getNode(queryRvName);
        }

        if (queryNode == null) {
            log.warn("Query variable '{}' not found in SSBN", groundedQuery);
            return BayesianInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        Factor posterior = VariableElimination.query(network, queryNode.getVariableName(), evidence);
        Map<String, Double> posteriors = new LinkedHashMap<>();
        double pTrue = posterior.getValues().length > 1 ? posterior.getValue(1) : posterior.getValue(0);
        posteriors.put(queryNode.getVariableName(), pTrue);

        BayesianInferenceResult result = buildResult(network, posteriors, evidence, startTime);
        result.setVariableToMebnMeta(buildMebnMeta(mTheory, network));
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-MEBN FROM KG
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Automatically build an MTheory from a KG subgraph and run MEBN inference.
     *
     * <p>This is the "auto-MEBN" path: instead of requiring the caller to
     * manually construct an MTheory, it uses {@link KgMTheoryBuilder} to
     * analyze the KG structure, group nodes by entity type, define MFrags
     * from edge patterns, and run SSBN generation + variable elimination.</p>
     *
     * @param seedNodeIds KG node IDs to build the MTheory from
     * @param evidence    map of grounded variable name → observed state index
     * @param maxDepth    maximum traversal depth
     * @param maxNodes    maximum nodes to discover
     * @return inference result with entity-specific posteriors
     */
    public BayesianInferenceResult queryMebnFromKg(Collection<String> seedNodeIds,
                                                      Map<String, Integer> evidence,
                                                      int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        KgMTheoryBuilder builder = new KgMTheoryBuilder(graphService)
                .maxDepth(maxDepth)
                .maxNodes(maxNodes);
        MTheory mTheory = builder.build(seedNodeIds);

        if (mTheory.getMFrags().isEmpty()) {
            return BayesianInferenceResult.builder()
                    .computedAt(Instant.now())
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        return queryWithMTheory(mTheory, evidence);
    }

    /**
     * Get statistics about the auto-constructed MTheory without running inference.
     */
    public Map<String, Object> getMebnStatistics(Collection<String> seedNodeIds,
                                                    int maxDepth, int maxNodes) {
        KgMTheoryBuilder builder = new KgMTheoryBuilder(graphService)
                .maxDepth(maxDepth)
                .maxNodes(maxNodes);
        MTheory mTheory = builder.build(seedNodeIds);
        return mTheory.getStatistics();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SENSITIVITY ANALYSIS & WHAT-IF
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sensitivity analysis: compute how much the posterior of a query variable
     * changes when each evidence variable's prior is shifted by epsilon.
     *
     * @param seedNodeIds KG node IDs defining the network scope
     * @param queryNodeId KG node to measure sensitivity for
     * @param evidence    current evidence
     * @param epsilon     perturbation amount (e.g. 0.01)
     * @param maxDepth    max traversal depth
     * @param maxNodes    max nodes
     * @return sensitivity result with deltas, priors, and baseline posteriors
     */
    public SensitivityResult sensitivityAnalysis(Collection<String> seedNodeIds,
                                                     String queryNodeId,
                                                     Map<String, Integer> evidence,
                                                     double epsilon,
                                                     int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);
        String queryVar = network.getVariableForKgNodeId(queryNodeId);
        if (queryVar == null) {
            return SensitivityResult.builder()
                    .queryNodeId(queryNodeId)
                    .computationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        Map<String, Integer> bnEvidence = translateEvidence(network, evidence);

        // Compute priors (no evidence) for all variables
        Map<String, Double> allPriors = VariableElimination.queryAll(network, Map.of());
        Map<String, Double> priorsByNodeId = new LinkedHashMap<>();
        for (BayesianNode node : network.getNodes()) {
            Double prior = allPriors.get(node.getVariableName());
            if (prior != null && node.getKgNodeId() != null) {
                priorsByNodeId.put(node.getKgNodeId(), prior);
            }
        }

        // Baseline posterior
        Factor baseline = VariableElimination.query(network, queryVar, bnEvidence);
        double baselinePTrue = baseline.getValues().length > 1 ? baseline.getValue(1) : baseline.getValue(0);

        // Query prior
        double queryPrior = allPriors.getOrDefault(queryVar, baselinePTrue);

        // For each non-evidence, non-query variable, measure how setting it as
        // evidence (TRUE vs FALSE) shifts the query posterior
        Map<String, Double> sensitivities = new LinkedHashMap<>();
        for (BayesianNode node : network.getNodes()) {
            String var = node.getVariableName();
            if (var.equals(queryVar) || bnEvidence.containsKey(var)) continue;

            // Set variable as TRUE evidence
            Map<String, Integer> perturbedEvidence = new LinkedHashMap<>(bnEvidence);
            perturbedEvidence.put(var, 1);

            try {
                Factor perturbed = VariableElimination.query(network, queryVar, perturbedEvidence);
                double perturbedPTrue = perturbed.getValues().length > 1 ? perturbed.getValue(1) : perturbed.getValue(0);
                double sensitivity = Math.abs(perturbedPTrue - baselinePTrue);
                sensitivities.put(node.getKgNodeId(), sensitivity);
            } catch (Exception e) {
                log.debug("Sensitivity analysis failed for variable {}: {}", var, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Sensitivity analysis for {} complete in {}ms: {} variables tested",
                queryNodeId, elapsed, sensitivities.size());

        return SensitivityResult.builder()
                .sensitivities(sensitivities)
                .priors(priorsByNodeId)
                .baselinePosterior(baselinePTrue)
                .queryPrior(queryPrior)
                .queryNodeId(queryNodeId)
                .computationTimeMs(elapsed)
                .build();
    }

    /**
     * What-if query: compute posteriors under a hypothetical evidence combination.
     * Unlike the standard query, this allows specifying evidence as probabilities
     * (soft evidence) rather than hard 0/1 observations.
     *
     * @param seedNodeIds         KG node IDs defining the network scope
     * @param hypotheticalEvidence map of KG node ID → hypothetical state (0 or 1)
     * @param maxDepth            max traversal depth
     * @param maxNodes            max nodes
     * @return inference result under the hypothetical scenario
     */
    public BayesianInferenceResult whatIfQuery(Collection<String> seedNodeIds,
                                                 Map<String, Integer> hypotheticalEvidence,
                                                 int maxDepth, int maxNodes) {
        long startTime = System.currentTimeMillis();

        BayesianNetwork network = buildNetwork(seedNodeIds, maxDepth, maxNodes);
        Map<String, Integer> bnEvidence = translateEvidence(network, hypotheticalEvidence);

        // Compute posteriors with hypothetical evidence
        // (priors are computed inside buildResult)
        Map<String, Double> posteriors = VariableElimination.queryAll(network, bnEvidence);

        return buildResult(network, posteriors, bnEvidence, startTime);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build per-variable MEBN metadata by matching grounded BN variable names
     * back to their MFrag origins. Each entry maps variable name → {mfragName,
     * nodeRole, rvName, entityType, entityId}.
     */
    private Map<String, Map<String, String>> buildMebnMeta(MTheory mTheory, BayesianNetwork network) {
        Map<String, Map<String, String>> meta = new LinkedHashMap<>();

        for (BayesianNode bnNode : network.getNodes()) {
            String varName = bnNode.getVariableName();
            String kgNodeId = bnNode.getKgNodeId();

            // Try to match this grounded variable to an MFrag's RV
            for (MFrag mfrag : mTheory.getMFrags()) {
                // Check residents
                for (RandomVariable rv : mfrag.getResidentNodes()) {
                    if (varName.startsWith(rv.getName())) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("mfragName", mfrag.getName());
                        entry.put("nodeRole", "RESIDENT");
                        entry.put("rvName", rv.getName());
                        if (kgNodeId != null) entry.put("entityId", kgNodeId);
                        // Find entity type
                        if (!rv.getArgumentTypes().isEmpty()) {
                            entry.put("entityType", rv.getArgumentTypes().get(0).getTypeName());
                        }
                        meta.put(varName, entry);
                        break;
                    }
                }
                if (meta.containsKey(varName)) break;

                // Check inputs
                for (RandomVariable rv : mfrag.getInputNodes()) {
                    if (varName.startsWith(rv.getName())) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("mfragName", mfrag.getName());
                        entry.put("nodeRole", "INPUT");
                        entry.put("rvName", rv.getName());
                        if (kgNodeId != null) entry.put("entityId", kgNodeId);
                        if (!rv.getArgumentTypes().isEmpty()) {
                            entry.put("entityType", rv.getArgumentTypes().get(0).getTypeName());
                        }
                        meta.put(varName, entry);
                        break;
                    }
                }
                if (meta.containsKey(varName)) break;
            }
        }
        return meta;
    }

    private Map<String, Integer> translateEvidence(BayesianNetwork network,
                                                     Map<String, Integer> kgEvidence) {
        Map<String, Integer> bnEvidence = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : kgEvidence.entrySet()) {
            String varName = network.getVariableForKgNodeId(entry.getKey());
            if (varName != null) {
                bnEvidence.put(varName, entry.getValue());
            } else {
                log.debug("Evidence node {} not found in network, skipping", entry.getKey());
            }
        }
        return bnEvidence;
    }

    private BayesianInferenceResult buildResult(BayesianNetwork network,
                                                  Map<String, Double> posteriors,
                                                  Map<String, Integer> bnEvidence,
                                                  long startTime) {
        // Build variable → nodeId and variable → title maps
        Map<String, String> varToNodeId = new LinkedHashMap<>();
        Map<String, String> varToTitle = new LinkedHashMap<>();
        for (BayesianNode node : network.getNodes()) {
            varToNodeId.put(node.getVariableName(), node.getKgNodeId());
            varToTitle.put(node.getVariableName(), node.getTitle());
        }

        // Compute priors (no evidence) for comparison with posteriors
        Map<String, Double> priors;
        try {
            priors = VariableElimination.queryAll(network, Map.of());
        } catch (Exception e) {
            log.debug("Could not compute priors: {}", e.getMessage());
            priors = new LinkedHashMap<>();
        }

        // Collect inference trace from a representative query (first non-evidence variable)
        List<InferenceStep> inferenceTrace = collectInferenceTrace(network, bnEvidence);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Bayesian inference complete in {}ms: {} posteriors, {} evidence vars",
                elapsed, posteriors.size(), bnEvidence.size());

        return BayesianInferenceResult.builder()
                .posteriors(posteriors)
                .priors(priors)
                .inferenceTrace(inferenceTrace)
                .evidence(bnEvidence)
                .networkStats(network.getStatistics())
                .variableToNodeId(varToNodeId)
                .variableToTitle(varToTitle)
                .computedAt(Instant.now())
                .computationTimeMs(elapsed)
                .build();
    }

    /**
     * Collect a representative inference trace by querying the first non-evidence variable.
     */
    private List<InferenceStep> collectInferenceTrace(BayesianNetwork network,
                                                        Map<String, Integer> bnEvidence) {
        try {
            // Find a representative query variable (first non-evidence variable)
            for (BayesianNode node : network.getNodes()) {
                String var = node.getVariableName();
                if (!bnEvidence.containsKey(var)) {
                    VariableElimination.TracedResult traced =
                            VariableElimination.queryWithTrace(network, var, bnEvidence);
                    return traced.getTrace();
                }
            }
        } catch (Exception e) {
            log.debug("Could not collect inference trace: {}", e.getMessage());
        }
        return new ArrayList<>();
    }
}

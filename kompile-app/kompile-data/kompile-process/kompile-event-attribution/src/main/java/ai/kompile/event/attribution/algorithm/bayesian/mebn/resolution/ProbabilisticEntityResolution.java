/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.resolution;

import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetwork;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNode;
import ai.kompile.event.attribution.algorithm.bayesian.Factor;
import ai.kompile.event.attribution.algorithm.bayesian.VariableElimination;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.*;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.Constraints;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.GraphKnowledgeBase;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.LogicalConstraint;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Probabilistic entity resolution using Multi-Entity Bayesian Networks.
 *
 * <p>Instead of hard thresholds (Levenshtein > 0.85 → match), this class
 * models entity identity as a random variable and computes
 * P(isSameEntity(e1, e2) = TRUE | observed similarities).</p>
 *
 * <h3>MTheory Structure</h3>
 * <ul>
 *   <li><b>NameSimilarity MFrag</b>: resident node "hasNameSimilarity(e1, e2)" —
 *       observed evidence based on normalized title Levenshtein distance</li>
 *   <li><b>PropertyOverlap MFrag</b>: resident node "sharesProperties(e1, e2)" —
 *       observed evidence based on shared metadata fields</li>
 *   <li><b>TypeCompatibility MFrag</b>: resident node "isTypeCompatible(e1, e2)" —
 *       observed evidence based on entity type compatibility</li>
 *   <li><b>EntityIdentity MFrag</b>: resident node "isSameEntity(e1, e2)" with
 *       parents from all three evidence MFrags — the target of inference</li>
 * </ul>
 *
 * <p>The posterior P(isSameEntity = TRUE | nameSim, propOverlap, typeCompat)
 * gives a calibrated match probability that can replace or augment hard
 * threshold scoring in {@code GraphCompactionService}.</p>
 */
public class ProbabilisticEntityResolution {

    private static final Logger log = LoggerFactory.getLogger(ProbabilisticEntityResolution.class);

    private final KnowledgeGraphService graphService;

    public ProbabilisticEntityResolution(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Compute the posterior probability that two entities are the same.
     *
     * @param nodeIdA    first entity's KG node ID
     * @param nodeIdB    second entity's KG node ID
     * @param nameSim    Levenshtein similarity of normalized titles (0.0–1.0)
     * @param propScore  attribute/property overlap score (0.0–1.0)
     * @param typeMatch  whether entity types are compatible (true/false)
     * @return the posterior P(isSameEntity = TRUE | evidence), or empty if inference fails
     */
    public Optional<Double> computeMatchProbability(String nodeIdA, String nodeIdB,
                                                      double nameSim, double propScore,
                                                      boolean typeMatch) {
        try {
            MTheory mTheory = buildResolutionMTheory(nodeIdA, nodeIdB);
            GraphKnowledgeBase kb = new GraphKnowledgeBase(graphService);

            // Register entity types for constraint evaluation
            for (EntityType et : mTheory.getEntityTypes()) {
                kb.registerEntityType(et.getTypeName(), et.getEntityIds());
            }

            SSBNGenerator generator = new SSBNGenerator(mTheory, kb);
            BayesianNetwork ssbn = generator.generate();

            if (ssbn.size() == 0) {
                return Optional.empty();
            }

            // Set observed evidence from the similarity signals
            Map<String, Integer> evidence = buildEvidence(ssbn, nodeIdA, nodeIdB,
                    nameSim, propScore, typeMatch);

            // Query P(isSameEntity(nodeIdA,nodeIdB) = TRUE | evidence)
            String queryVar = "isSameEntity(" + nodeIdA + "," + nodeIdB + ")";
            BayesianNode queryNode = ssbn.getNode(queryVar);
            if (queryNode == null) {
                log.debug("Query variable '{}' not found in SSBN", queryVar);
                return Optional.empty();
            }

            Factor posterior = VariableElimination.query(ssbn, queryVar, evidence);
            double pTrue = posterior.getValues().length > 1 ?
                    posterior.getValue(1) : posterior.getValue(0);

            log.debug("P(isSameEntity({},{}) = TRUE | nameSim={}, propScore={}, typeMatch={}) = {}",
                    nodeIdA, nodeIdB, nameSim, propScore, typeMatch, pTrue);

            return Optional.of(pTrue);

        } catch (Exception e) {
            log.debug("Probabilistic entity resolution failed for ({}, {}): {}",
                    nodeIdA, nodeIdB, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Score a batch of candidate pairs, returning posterior match probabilities.
     *
     * @param candidates list of candidate pairs with their similarity signals
     * @return map of "nodeIdA:nodeIdB" → posterior match probability
     */
    public Map<String, Double> scoreBatch(List<CandidatePair> candidates) {
        Map<String, Double> results = new LinkedHashMap<>();
        for (CandidatePair pair : candidates) {
            Optional<Double> prob = computeMatchProbability(
                    pair.nodeIdA, pair.nodeIdB,
                    pair.nameSimilarity, pair.propertyScore, pair.typeCompatible);
            prob.ifPresent(p -> results.put(pair.nodeIdA + ":" + pair.nodeIdB, p));
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MTHEORY CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    private MTheory buildResolutionMTheory(String nodeIdA, String nodeIdB) {
        MTheory mTheory = new MTheory("entity_resolution");

        // Single entity type with both candidates
        EntityType candidateType = new EntityType("Candidate", "Entity resolution candidates");
        candidateType.addEntity(nodeIdA);
        candidateType.addEntity(nodeIdB);
        mTheory.addEntityType(candidateType);

        // MFrag 1: NameSimilarity — observed evidence node
        MFrag nameFrag = new MFrag("NameSimilarity");
        RandomVariable hasNameSim = RandomVariable.binary(
                "hasNameSimilarity", candidateType, candidateType,
                RandomVariable.NodeRole.RESIDENT);
        nameFrag.addResidentNode(hasNameSim);
        nameFrag.addContextConstraint(Constraints.notEqual("Candidate_0", "Candidate_1"));
        mTheory.addMFrag(nameFrag);

        // MFrag 2: PropertyOverlap — observed evidence node
        MFrag propFrag = new MFrag("PropertyOverlap");
        RandomVariable sharesProp = RandomVariable.binary(
                "sharesProperties", candidateType, candidateType,
                RandomVariable.NodeRole.RESIDENT);
        propFrag.addResidentNode(sharesProp);
        propFrag.addContextConstraint(Constraints.notEqual("Candidate_0", "Candidate_1"));
        mTheory.addMFrag(propFrag);

        // MFrag 3: TypeCompatibility — observed evidence node
        MFrag typeFrag = new MFrag("TypeCompatibility");
        RandomVariable isTypeCompat = RandomVariable.binary(
                "isTypeCompatible", candidateType, candidateType,
                RandomVariable.NodeRole.RESIDENT);
        typeFrag.addResidentNode(isTypeCompat);
        typeFrag.addContextConstraint(Constraints.notEqual("Candidate_0", "Candidate_1"));
        mTheory.addMFrag(typeFrag);

        // MFrag 4: EntityIdentity — the inference target
        MFrag identityFrag = new MFrag("EntityIdentity");

        RandomVariable isSame = RandomVariable.binary(
                "isSameEntity", candidateType, candidateType,
                RandomVariable.NodeRole.RESIDENT);
        identityFrag.addResidentNode(isSame);

        // Input nodes from evidence MFrags
        RandomVariable nameSimInput = RandomVariable.binary(
                "hasNameSimilarity", candidateType, candidateType,
                RandomVariable.NodeRole.INPUT);
        identityFrag.addInputNode(nameSimInput);

        RandomVariable propInput = RandomVariable.binary(
                "sharesProperties", candidateType, candidateType,
                RandomVariable.NodeRole.INPUT);
        identityFrag.addInputNode(propInput);

        RandomVariable typeInput = RandomVariable.binary(
                "isTypeCompatible", candidateType, candidateType,
                RandomVariable.NodeRole.INPUT);
        identityFrag.addInputNode(typeInput);

        // Parent edges: each evidence signal influences the identity decision
        // Name similarity is the strongest signal
        identityFrag.addParentEdge("hasNameSimilarity", "isSameEntity", 0.85);
        // Property overlap is a strong corroborating signal
        identityFrag.addParentEdge("sharesProperties", "isSameEntity", 0.75);
        // Type compatibility is a necessary condition but weak on its own
        identityFrag.addParentEdge("isTypeCompatible", "isSameEntity", 0.5);

        // Context: entities must be different (can't match an entity with itself)
        identityFrag.addContextConstraint(Constraints.notEqual("Candidate_0", "Candidate_1"));

        mTheory.addMFrag(identityFrag);

        return mTheory;
    }

    /**
     * Build evidence map from similarity signals.
     *
     * <p>Each evidence variable is set to TRUE (1) or FALSE (0) based on
     * whether the corresponding similarity signal exceeds its threshold.
     * The thresholds are intentionally lower than hard-match thresholds —
     * the BN inference handles uncertainty properly.</p>
     */
    private Map<String, Integer> buildEvidence(BayesianNetwork ssbn,
                                                 String nodeIdA, String nodeIdB,
                                                 double nameSim, double propScore,
                                                 boolean typeMatch) {
        Map<String, Integer> evidence = new LinkedHashMap<>();

        // Name similarity: TRUE if > 0.6 (lower than hard threshold of 0.85)
        String nameVar = "hasNameSimilarity(" + nodeIdA + "," + nodeIdB + ")";
        if (ssbn.getNode(nameVar) != null) {
            evidence.put(nameVar, nameSim > 0.6 ? 1 : 0);
        }

        // Property overlap: TRUE if > 0.3 (any meaningful shared properties)
        String propVar = "sharesProperties(" + nodeIdA + "," + nodeIdB + ")";
        if (ssbn.getNode(propVar) != null) {
            evidence.put(propVar, propScore > 0.3 ? 1 : 0);
        }

        // Type compatibility: direct boolean
        String typeVar = "isTypeCompatible(" + nodeIdA + "," + nodeIdB + ")";
        if (ssbn.getNode(typeVar) != null) {
            evidence.put(typeVar, typeMatch ? 1 : 0);
        }

        return evidence;
    }

    /**
     * A candidate pair for batch probabilistic entity resolution.
     */
    public record CandidatePair(String nodeIdA, String nodeIdB,
                                 double nameSimilarity, double propertyScore,
                                 boolean typeCompatible) {}
}

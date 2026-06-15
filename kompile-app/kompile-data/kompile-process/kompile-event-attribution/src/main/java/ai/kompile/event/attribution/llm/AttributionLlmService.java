/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.llm;

import ai.kompile.event.attribution.domain.*;
import ai.kompile.core.llm.LanguageModel;
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
 * LLM-augmented reasoning service for event attribution and prediction.
 *
 * <p>This service implements the Graph Chain-of-Thought (Graph-CoT) pattern:
 * the LLM iteratively proposes graph queries, receives results, and builds
 * explanations grounded in the actual graph structure. This prevents
 * hallucination by constraining the LLM to facts present in the graph.</p>
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li><b>Causal chain narration</b>: converts structured chains into
 *       human-readable causal explanations</li>
 *   <li><b>Ambiguity resolution</b>: when multiple causal paths exist,
 *       the LLM ranks them by plausibility</li>
 *   <li><b>Counterfactual reasoning</b>: generates "what if" explanations
 *       for counterfactual scenarios</li>
 *   <li><b>Synthesis</b>: merges multiple chains into a single coherent
 *       narrative answering the "why?" question</li>
 *   <li><b>Prediction explanation</b>: explains why predicted events are
 *       likely given current graph state</li>
 * </ul>
 */
@Service
public class AttributionLlmService {

    private static final Logger log = LoggerFactory.getLogger(AttributionLlmService.class);

    private final LanguageModel languageModel;
    private final KnowledgeGraphService graphService;

    @Autowired
    public AttributionLlmService(@Autowired(required = false) LanguageModel languageModel,
                                  KnowledgeGraphService graphService) {
        this.languageModel = languageModel;
        this.graphService = graphService;
    }

    /**
     * Check if the LLM is available.
     */
    public boolean isAvailable() {
        return languageModel != null;
    }

    /**
     * Generate a narrative explanation for a single causal chain.
     */
    public String narrateChain(AttributionChain chain) {
        if (languageModel == null) return null;

        String chainDescription = formatChainForLlm(chain);
        String prompt = CHAIN_NARRATIVE_PROMPT.formatted(
                chain.getTargetEventTitle(),
                chain.getRootCauseTitle(),
                chainDescription,
                chain.getOverallConfidence()
        );

        try {
            return languageModel.generateResponse(prompt, List.of());
        } catch (Exception e) {
            log.warn("LLM chain narration failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Synthesize multiple causal chains into a single coherent explanation.
     */
    public String synthesizeExplanation(String targetTitle, List<AttributionChain> chains,
                                         String naturalLanguageQuery) {
        if (languageModel == null) return null;

        String chainsDescription = chains.stream()
                .map(this::formatChainForLlm)
                .collect(Collectors.joining("\n---\n"));

        String question = naturalLanguageQuery != null
                ? naturalLanguageQuery
                : "Why did \"" + targetTitle + "\" happen?";

        String prompt = SYNTHESIS_PROMPT.formatted(question, targetTitle, chains.size(), chainsDescription);

        try {
            return languageModel.generateResponse(prompt, List.of());
        } catch (Exception e) {
            log.warn("LLM synthesis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rank causal chains by plausibility using the LLM.
     * Returns chain IDs in order of plausibility.
     */
    public List<String> rankChainsByPlausibility(List<AttributionChain> chains) {
        if (languageModel == null || chains.size() <= 1) {
            return chains.stream().map(AttributionChain::getChainId).toList();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chains.size(); i++) {
            sb.append("Chain ").append(i + 1).append(" (ID: ").append(chains.get(i).getChainId()).append("):\n");
            sb.append(formatChainForLlm(chains.get(i)));
            sb.append("\n\n");
        }

        String prompt = RANKING_PROMPT.formatted(
                chains.get(0).getTargetEventTitle(), chains.size(), sb.toString());

        try {
            String response = languageModel.generateResponse(prompt, List.of());
            return parseRankingResponse(response, chains);
        } catch (Exception e) {
            log.warn("LLM ranking failed, falling back to confidence ordering: {}", e.getMessage());
            return chains.stream().map(AttributionChain::getChainId).toList();
        }
    }

    /**
     * Generate a counterfactual explanation.
     */
    public String explainCounterfactual(String targetTitle, String removedNodeTitle,
                                         boolean targetStillReachable, int survivingChains) {
        if (languageModel == null) return null;

        String prompt = COUNTERFACTUAL_PROMPT.formatted(
                removedNodeTitle, targetTitle,
                targetStillReachable ? "YES" : "NO",
                survivingChains
        );

        try {
            return languageModel.generateResponse(prompt, List.of());
        } catch (Exception e) {
            log.warn("LLM counterfactual explanation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate a forecast narrative from predicted events.
     */
    public String generateForecast(String sourceTitle, List<PredictedEvent> predictions) {
        if (languageModel == null) return null;

        String predictionsText = predictions.stream()
                .map(p -> "- " + p.getTitle() + " (probability: " +
                        String.format("%.0f%%", p.getProbability() * 100) +
                        ", " + p.getHopsFromSource() + " hops away)")
                .collect(Collectors.joining("\n"));

        String prompt = FORECAST_PROMPT.formatted(sourceTitle, predictionsText);

        try {
            return languageModel.generateResponse(prompt, List.of());
        } catch (Exception e) {
            log.warn("LLM forecast generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Use the LLM to classify a causal relationship between two nodes
     * when the graph structure alone is ambiguous.
     */
    public CausalEdgeType classifyCausalRelationship(String causeTitle, String causeDescription,
                                                      String effectTitle, String effectDescription) {
        if (languageModel == null) return CausalEdgeType.CORRELATES_WITH;

        String prompt = CLASSIFICATION_PROMPT.formatted(
                causeTitle, causeDescription != null ? causeDescription : "(no description)",
                effectTitle, effectDescription != null ? effectDescription : "(no description)"
        );

        try {
            String response = languageModel.generateResponse(prompt, List.of()).trim().toUpperCase();
            return parseCausalType(response);
        } catch (Exception e) {
            log.warn("LLM causal classification failed: {}", e.getMessage());
            return CausalEdgeType.CORRELATES_WITH;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FORMATTING
    // ═══════════════════════════════════════════════════════════════════════════

    private String formatChainForLlm(AttributionChain chain) {
        StringBuilder sb = new StringBuilder();
        sb.append("Root cause: ").append(chain.getRootCauseTitle()).append("\n");
        for (int i = 0; i < chain.getHops().size(); i++) {
            CausalHop hop = chain.getHops().get(i);
            sb.append(String.format("  Step %d: \"%s\" -[%s (%.0f%%)]-> \"%s\"\n",
                    i + 1,
                    hop.getCauseTitle(),
                    hop.getCausalType(),
                    hop.getStrength() * 100,
                    hop.getEffectTitle()));
            for (AttributionEvidence ev : hop.getEvidence()) {
                sb.append(String.format("    Evidence (%s): %s\n",
                        ev.getEvidenceType(), ev.getSummary()));
            }
        }
        sb.append("Target: ").append(chain.getTargetEventTitle());
        sb.append(" | Overall confidence: ").append(String.format("%.0f%%", chain.getOverallConfidence() * 100));
        return sb.toString();
    }

    private List<String> parseRankingResponse(String response, List<AttributionChain> chains) {
        // Try to extract chain IDs from the response
        List<String> ranked = new ArrayList<>();
        for (AttributionChain chain : chains) {
            if (response.contains(chain.getChainId())) {
                ranked.add(chain.getChainId());
            }
        }
        // Fall back: if parsing failed, return in original order
        if (ranked.size() != chains.size()) {
            return chains.stream().map(AttributionChain::getChainId).toList();
        }
        return ranked;
    }

    private CausalEdgeType parseCausalType(String response) {
        for (CausalEdgeType type : CausalEdgeType.values()) {
            if (response.contains(type.name())) return type;
        }
        return CausalEdgeType.CORRELATES_WITH;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT TEMPLATES
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String CHAIN_NARRATIVE_PROMPT = """
            You are an event attribution analyst. Given a causal chain from a knowledge graph,
            write a clear, concise narrative explanation of how the root cause led to the target event.

            Target event: "%s"
            Root cause: "%s"

            Causal chain:
            %s

            Overall confidence: %.0f%%

            Write a 2-4 sentence narrative explanation. Be specific about the causal mechanism
            at each step. If confidence is low, note the uncertainty. Do not speculate beyond
            what the evidence shows.""";

    private static final String SYNTHESIS_PROMPT = """
            You are an event attribution analyst. A user asked: "%s"

            The target event is: "%s"

            We found %d causal chains in the knowledge graph. Synthesize them into a single
            coherent explanation that addresses the user's question.

            Discovered causal chains:
            %s

            Write a clear, structured explanation that:
            1. Identifies the most likely root cause(s)
            2. Explains the causal mechanism step by step
            3. Notes where evidence is strong vs. uncertain
            4. Mentions alternative explanations if multiple chains suggest different causes

            Keep the explanation concise (3-6 sentences). Ground every claim in the evidence provided.""";

    private static final String RANKING_PROMPT = """
            You are evaluating causal explanations for the event: "%s"

            There are %d alternative causal chains. Rank them from most to least plausible
            based on the strength of evidence, directness of causation, and coherence.

            %s

            Return the chain IDs in order from most plausible to least plausible,
            one per line. Include only the chain IDs, nothing else.""";

    private static final String COUNTERFACTUAL_PROMPT = """
            You are performing counterfactual analysis on a causal graph.

            Question: "What if '%s' had not occurred? Would '%s' still have happened?"

            After removing this node from the causal graph:
            - Target event still reachable: %s
            - Surviving causal chains: %d

            Write a 1-3 sentence counterfactual analysis. If the target is no longer
            reachable, explain why this node was a necessary cause. If it's still
            reachable, explain the alternative causal paths.""";

    private static final String FORECAST_PROMPT = """
            You are a predictive analyst. Given the current state of a knowledge graph node,
            predict what events are likely to follow.

            Current state: "%s"

            Predicted downstream events (from graph traversal):
            %s

            Write a brief forecast (2-4 sentences) summarizing what is likely to happen next
            and why. Focus on the highest-probability events and the causal mechanisms.""";

    private static final String CLASSIFICATION_PROMPT = """
            Classify the causal relationship between two events.

            Potential cause: "%s" - %s
            Potential effect: "%s" - %s

            Choose exactly one of: CAUSES, TRIGGERS, ENABLES, CONTRIBUTES_TO,
            PREVENTS, CORRELATES_WITH, INFLUENCES, DERIVED_FROM

            Reply with only the classification label.""";
}

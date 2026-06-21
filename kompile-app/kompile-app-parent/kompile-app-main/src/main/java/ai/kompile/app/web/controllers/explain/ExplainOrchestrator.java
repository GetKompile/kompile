/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.explain.ConfidenceBreakdown;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.KnowledgeGraphReasoningAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Routes an explain request to the correct reasoning engine and returns a unified
 * {@link ReasoningTrail}.
 *
 * <h3>Routing rules (AUTO mode)</h3>
 * <ol>
 *   <li>{@code causal:<target>} prefix → CAUSAL (stub — returns UNKNOWN trail with a note)</li>
 *   <li>Contains {@code '('} and {@code ')'} → atom-key → GROUNDING via {@link KbGroundingService}</li>
 *   <li>Otherwise → entity-id → HYBRID via {@link HybridReasoner} over the BFS subgraph</li>
 * </ol>
 *
 * <p>An explicit {@code mode} in the request overrides auto-detection.</p>
 */
@Slf4j
@Component
public class ExplainOrchestrator {

    private static final String MODE_GROUNDING = "GROUNDING";
    private static final String MODE_HYBRID    = "HYBRID";
    private static final String MODE_CAUSAL    = "CAUSAL";

    private final KbGroundingService groundingService;
    private final KnowledgeGraphReasoningAdapter reasoningAdapter;
    private final EventAttributionService attributionService;

    @Autowired
    public ExplainOrchestrator(KbGroundingService groundingService,
                               KnowledgeGraphReasoningAdapter reasoningAdapter,
                               EventAttributionService attributionService) {
        this.groundingService = groundingService;
        this.reasoningAdapter = reasoningAdapter;
        this.attributionService = attributionService;
    }

    /**
     * Resolve and execute the explain request.
     *
     * @param target      the atom key, entity id, or {@code causal:<target>} string
     * @param factSheetId the fact-sheet scope (0L = global)
     * @param depthHint   derivation depth cap; 0 = use lib default
     * @param modeOverride optional explicit mode (GROUNDING | HYBRID | CAUSAL); null = AUTO
     * @return unified {@link ReasoningTrail} (never null)
     */
    public ReasoningTrail explain(String target, long factSheetId, int depthHint, String modeOverride) {
        String resolvedMode = resolveMode(target, modeOverride);
        log.debug("ExplainOrchestrator: target='{}' factSheet={} mode={}", target, factSheetId, resolvedMode);

        switch (resolvedMode) {
            case MODE_GROUNDING:
                return groundingTrail(target, factSheetId, depthHint);
            case MODE_HYBRID:
                return hybridTrail(target, factSheetId);
            case MODE_CAUSAL:
                return causalTrail(target, factSheetId);
            default:
                return groundingTrail(target, factSheetId, depthHint);
        }
    }

    // ── Mode resolution ──────────────────────────────────────────────────────────

    private String resolveMode(String target, String modeOverride) {
        if (modeOverride != null && !modeOverride.isBlank()) {
            String upper = modeOverride.toUpperCase();
            if (upper.equals(MODE_GROUNDING) || upper.equals(MODE_HYBRID) || upper.equals(MODE_CAUSAL)) {
                return upper;
            }
        }
        if (target.startsWith("causal:")) {
            return MODE_CAUSAL;
        }
        // Atom keys contain parentheses: isEmployedBy(Alice,Acme)
        if (target.contains("(") && target.contains(")")) {
            return MODE_GROUNDING;
        }
        // Bare entity id → hybrid
        return MODE_HYBRID;
    }

    // ── GROUNDING trail ──────────────────────────────────────────────────────────

    private ReasoningTrail groundingTrail(String atomKey, long factSheetId, int depthHint) {
        int depth = depthHint > 0
                ? Math.min(depthHint, DerivationTree.DEFAULT_MAX_DEPTH)
                : DerivationTree.DEFAULT_MAX_DEPTH;

        VerifyResult verdict = groundingService.verify(factSheetId, atomKey);
        DerivationTree tree = groundingService.explain(factSheetId, atomKey, depth);

        // Split evidence into atoms vs rules (mirrors KbGroundingController.verify)
        List<String> evidenceAtoms = new ArrayList<>();
        List<String> activatedRules = new ArrayList<>();
        for (String ev : verdict.evidence()) {
            if (ev.contains(":-") || (ev.length() > 1 && Character.isDigit(ev.charAt(0)))) {
                activatedRules.add(ev);
            } else {
                evidenceAtoms.add(ev);
            }
        }

        String summary = deterministicSummary(tree, atomKey, verdict);

        return ReasoningTrail.builder(atomKey)
                .question("Why is '" + atomKey + "' " + verdict.status().name().toLowerCase() + "?")
                .confidence(verdict.confidence())
                .breakdown(ConfidenceBreakdown.ofGrounding(verdict.confidence()))
                .derivationTree(tree)
                .evidence(evidenceAtoms)
                .activatedRules(activatedRules)
                .inferenceMode(MODE_GROUNDING)
                .computedAt(Instant.now())
                .naturalLanguageSummary(summary)
                .build();
    }

    // ── HYBRID trail ─────────────────────────────────────────────────────────────

    private ReasoningTrail hybridTrail(String entityId, long factSheetId) {
        ReasoningGraph subgraph = reasoningAdapter.subgraph(List.of(entityId));

        HybridReasoner reasoner = new HybridReasoner();
        List<HybridReasoner.ScoredEntity> scores = reasoner.rank(subgraph);

        double entityScore = scores.stream()
                .filter(se -> se.entityId().equals(entityId))
                .mapToDouble(HybridReasoner.ScoredEntity::score)
                .findFirst()
                .orElse(0.0);
        double structuralScore = scores.stream()
                .filter(se -> se.entityId().equals(entityId))
                .mapToDouble(HybridReasoner.ScoredEntity::structuralScore)
                .findFirst()
                .orElse(0.0);
        double semanticScore = scores.stream()
                .filter(se -> se.entityId().equals(entityId))
                .mapToDouble(HybridReasoner.ScoredEntity::semanticScore)
                .findFirst()
                .orElse(0.0);

        String summary = String.format(
                "Entity '%s' has a hybrid relevance score of %.2f " +
                "(structural=%.2f, semantic=%.2f) over a %d-node subgraph.",
                entityId, entityScore, structuralScore, semanticScore, subgraph.entityCount());

        return ReasoningTrail.builder(entityId)
                .question("Why is '" + entityId + "' relevant?")
                .confidence(entityScore)
                .breakdown(ConfidenceBreakdown.ofHybrid(structuralScore, semanticScore, 0.6, 0.4))
                .inferenceMode(MODE_HYBRID)
                .computedAt(Instant.now())
                .naturalLanguageSummary(summary)
                .build();
    }

    // ── CAUSAL trail ─────────────────────────────────────────────────────────────

    private ReasoningTrail causalTrail(String causalTarget, long factSheetId) {
        // Strip the "causal:" prefix to get the bare target node id / event name
        String target = causalTarget.startsWith("causal:")
                ? causalTarget.substring("causal:".length())
                : causalTarget;

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId(target)
                .naturalLanguageQuery("Why did '" + target + "' happen?")
                .factSheetId(factSheetId > 0 ? factSheetId : null)
                .maxDepth(5)
                .maxChains(5)
                .minConfidence(0.05)
                .useLlm(false)            // deterministic; no LLM dependency in the explain path
                .includeCounterfactuals(false)
                .build();

        AttributionResult result = attributionService.explain(query);

        // ── Map attribution chains → evidence strings ────────────────────────────
        List<String> evidence = result.getChains().stream()
                .flatMap(chain -> chain.getHops().stream())
                .flatMap(hop -> hop.getEvidence().stream())
                .map(ev -> ev.getSummary())
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());

        // ── Activated rules = root-cause titles from each chain ──────────────────
        List<String> activatedRules = result.getChains().stream()
                .map(chain -> "rootCause(" + chain.getRootCauseNodeId() + ")"
                        + (chain.getRootCauseTitle() != null
                                ? " [" + chain.getRootCauseTitle() + "]"
                                : ""))
                .distinct()
                .collect(Collectors.toList());

        // ── Confidence = highest overall-confidence chain, or 0.0 if no chains ──
        double confidence = result.getChains().stream()
                .mapToDouble(AttributionChain::getOverallConfidence)
                .max()
                .orElse(0.0);

        // ── Natural-language summary ─────────────────────────────────────────────
        String summary;
        if (result.getSynthesizedExplanation() != null && !result.getSynthesizedExplanation().isBlank()) {
            summary = result.getSynthesizedExplanation();
        } else if (!result.getChains().isEmpty()) {
            AttributionChain topChain = result.getChains().get(0);
            summary = String.format(
                    "Causal attribution for '%s': top chain from '%s' (confidence %.2f, %d hop%s). "
                    + "%d causal chain%s found across %d node%s visited.",
                    target,
                    topChain.getRootCauseTitle() != null ? topChain.getRootCauseTitle() : topChain.getRootCauseNodeId(),
                    topChain.getOverallConfidence(),
                    topChain.getHops().size(), topChain.getHops().size() == 1 ? "" : "s",
                    result.getChains().size(), result.getChains().size() == 1 ? "" : "s",
                    result.getNodesVisited(), result.getNodesVisited() == 1 ? "" : "s");
        } else {
            summary = String.format(
                    "No causal chains found for '%s'. "
                    + "%d node%s visited, %d edge%s examined.",
                    target,
                    result.getNodesVisited(), result.getNodesVisited() == 1 ? "" : "s",
                    result.getEdgesExamined(), result.getEdgesExamined() == 1 ? "" : "s");
        }

        log.debug("causalTrail: target='{}' chains={} confidence={}", target,
                result.getChains().size(), confidence);

        return ReasoningTrail.builder(target)
                .question("What caused '" + target + "'?")
                .confidence(confidence)
                .breakdown(ConfidenceBreakdown.empty())
                .evidence(evidence)
                .activatedRules(activatedRules)
                .inferenceMode(MODE_CAUSAL)
                .computedAt(result.getComputedAt() != null ? result.getComputedAt() : Instant.now())
                .naturalLanguageSummary(summary)
                .build();
    }

    // ── Shared helpers ───────────────────────────────────────────────────────────

    /**
     * Deterministic NL summary of a derivation tree — no LLM required.
     * Mirrors {@code KbGroundingController.deterministicSummary}.
     */
    private static String deterministicSummary(DerivationTree tree, String atom, VerifyResult verdict) {
        if (verdict.status() == VerifyResult.Status.UNKNOWN) {
            return "The atom '" + atom + "' is not derivable from the current KB.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("'").append(atom).append("' is ")
                .append(verdict.status().name().toLowerCase())
                .append(" (confidence ").append(String.format("%.2f", verdict.confidence())).append(")");
        if (!tree.children().isEmpty()) {
            sb.append(" because: ");
            List<String> childDescs = new ArrayList<>();
            for (DerivationTree child : tree.children()) {
                childDescs.add("'" + child.atomKey() + "' (confidence "
                        + String.format("%.2f", child.confidence()) + ")");
            }
            sb.append(String.join(" and ", childDescs));
            if (tree.ruleApplied() != null) {
                sb.append(", via rule: ").append(tree.ruleApplied());
            }
        }
        sb.append(".");
        return sb.toString();
    }
}

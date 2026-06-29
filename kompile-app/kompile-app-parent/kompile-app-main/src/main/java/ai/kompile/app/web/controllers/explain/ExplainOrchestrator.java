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

import ai.kompile.knowledgegraph.reasoning.KnowledgeGraphReasoningAdapter;
import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.app.ontology.GraphOntologyBindingService;
import ai.kompile.app.ontology.OwlOntologyBridge;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.OntologySchemaTypeRegistry;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.event.attribution.service.PslReasoningService;
import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.domain.PslInferenceResult;
import ai.kompile.graph.reasoning.explain.ConfidenceBreakdown;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.KnowledgeGraphReasoningAdapter;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String MODE_PSL       = "PSL";
    private static final String MODE_MEBN      = "MEBN";

    private final KbGroundingService groundingService;
    private final KnowledgeGraphService graphService;
    private final EventAttributionService attributionService;
    private final PslReasoningService pslService;
    private final BayesianNetworkService bayesianService;

    /**
     * Optional — null in plain-lib test contexts where KnowledgeGraphService is mocked
     * without a real TraceHumanizer. The grounding trail falls back to empty maps when null.
     */
    @org.springframework.lang.Nullable
    private final TraceHumanizer traceHumanizer;

    /**
     * Optional — resolves the active {@link OntologySchema} for a fact sheet so MEBN/SSBN grounding
     * can navigate is-a (subsumption). Null in plain-lib/test contexts → exact-type grounding.
     */
    @org.springframework.lang.Nullable
    private final GraphOntologyBindingService ontologyBindingService;

    /**
     * Optional — converts the active {@link OntologySchema} to an OWL TBox so MEBN type-hierarchy
     * resolution can fold OWL-RL inferred types (over the real subgraph) into subsumption grounding.
     * Null in plain-lib/test contexts → declared is-a only.
     */
    @org.springframework.lang.Nullable
    private final OwlOntologyBridge owlOntologyBridge;

    /** Primary Spring constructor: all dependencies including TraceHumanizer. */
    @Autowired
    public ExplainOrchestrator(KbGroundingService groundingService,
                               KnowledgeGraphService graphService,
                               EventAttributionService attributionService,
                               PslReasoningService pslService,
                               BayesianNetworkService bayesianService,
                               @org.springframework.lang.Nullable TraceHumanizer traceHumanizer,
                               @org.springframework.lang.Nullable GraphOntologyBindingService ontologyBindingService,
                               @org.springframework.lang.Nullable OwlOntologyBridge owlOntologyBridge) {
        this.groundingService = groundingService;
        this.graphService = graphService;
        this.attributionService = attributionService;
        this.pslService = pslService;
        this.bayesianService = bayesianService;
        this.traceHumanizer = traceHumanizer;
        this.ontologyBindingService = ontologyBindingService;
        this.owlOntologyBridge = owlOntologyBridge;
    }

    /** Test / legacy constructor: no TraceHumanizer (falls back to empty maps). */
    public ExplainOrchestrator(KbGroundingService groundingService,
                               KnowledgeGraphService graphService,
                               EventAttributionService attributionService,
                               PslReasoningService pslService,
                               BayesianNetworkService bayesianService) {
        this(groundingService, graphService, attributionService, pslService, bayesianService, null, null, null);
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
            case MODE_PSL:
                return pslTrail(target, factSheetId);
            case MODE_MEBN:
                return mebnTrail(target, factSheetId);
            default:
                return groundingTrail(target, factSheetId, depthHint);
        }
    }

    // ── Mode resolution ──────────────────────────────────────────────────────────

    private String resolveMode(String target, String modeOverride) {
        if (modeOverride != null && !modeOverride.isBlank()) {
            String upper = modeOverride.toUpperCase();
            if (upper.equals(MODE_GROUNDING) || upper.equals(MODE_HYBRID)
                    || upper.equals(MODE_CAUSAL) || upper.equals(MODE_PSL) || upper.equals(MODE_MEBN)) {
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
        List<String> rawEvidenceAtoms = new ArrayList<>();
        List<String> rawActivatedRules = new ArrayList<>();
        for (String ev : verdict.evidence()) {
            if (ev.contains(":-") || (ev.length() > 1 && Character.isDigit(ev.charAt(0)))) {
                rawActivatedRules.add(ev);
            } else {
                rawEvidenceAtoms.add(ev);
            }
        }

        // Build human-readable title and rule maps via TraceHumanizer.
        // Falls back to empty maps when traceHumanizer is null (plain-lib test contexts).
        Map<String, String> atomKeyToTitle;
        Map<String, String> ruleToHumanized;
        List<String> evidenceAtoms;
        List<String> activatedRules;
        if (traceHumanizer != null) {
            atomKeyToTitle = tree != null
                    ? traceHumanizer.buildAtomKeyToTitle(tree.allAtomKeys())
                    : Map.of();
            List<String> allRuleStrings = new ArrayList<>(rawActivatedRules);
            if (tree != null) {
                collectRuleStrings(tree, allRuleStrings);
            }
            ruleToHumanized = traceHumanizer.buildRuleMap(allRuleStrings);
            evidenceAtoms = traceHumanizer.humanizeEvidenceAtoms(rawEvidenceAtoms);
            activatedRules = traceHumanizer.humanizeRules(rawActivatedRules);
        } else {
            atomKeyToTitle = Map.of();
            ruleToHumanized = Map.of();
            evidenceAtoms = rawEvidenceAtoms;
            activatedRules = rawActivatedRules;
        }

        String summary = deterministicSummary(tree, atomKey, verdict, atomKeyToTitle);

        return ReasoningTrail.builder(atomKey)
                .question("Why is '" + atomKey + "' " + verdict.status().name().toLowerCase() + "?")
                .confidence(verdict.confidence())
                .breakdown(ConfidenceBreakdown.ofGrounding(verdict.confidence()))
                .derivationTree(tree)
                .evidence(evidenceAtoms)
                .activatedRules(activatedRules)
                .inferenceMode(MODE_GROUNDING)
                .runId(tree != null && tree.sourceProvenance() != null ? tree.sourceProvenance() : "")
                .computedAt(Instant.now())
                .naturalLanguageSummary(summary)
                .atomKeyToTitle(atomKeyToTitle)
                .ruleToHumanized(ruleToHumanized)
                .build();
    }

    /** Collect all non-null ruleApplied strings from a derivation tree (BFS). */
    private static void collectRuleStrings(DerivationTree tree, List<String> acc) {
        if (tree.ruleApplied() != null) acc.add(tree.ruleApplied());
        for (DerivationTree child : tree.children()) {
            collectRuleStrings(child, acc);
        }
    }

    // ── HYBRID trail ─────────────────────────────────────────────────────────────

    private ReasoningTrail hybridTrail(String entityId, long factSheetId) {
        ReasoningGraph subgraph = new KnowledgeGraphReasoningAdapter(graphService).subgraph(List.of(entityId));

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

    // ── PSL trail ─────────────────────────────────────────────────────────────────

    /**
     * Runs PSL (HL-MRF) MAP inference over the KG subgraph rooted at {@code target}
     * and maps the result to a {@link ReasoningTrail}.
     *
     * <p>Populated fields: {@code confidence} (soft-truth for the target atom),
     * {@code breakdown} (via {@link ConfidenceBreakdown#ofPsl}),
     * {@code entailments} (one per inferred atom), {@code activatedRules} (PSL rule bodies),
     * {@code evidence} (top constraint violations from the solver).
     * <br>Not populated: {@code derivationTree} — PSL does not produce a derivation tree.</p>
     */
    private ReasoningTrail pslTrail(String target, long factSheetId) {
        PslInferenceResult result = pslService.infer(List.of(target), Map.of(), 3, 100);

        String runId = result.getComputedAt() != null ? result.getComputedAt().toString() : "";
        Instant computedAt = result.getComputedAt() != null ? result.getComputedAt() : Instant.now();

        // Primary soft truth: atom "State(target)", fall back to average of all inferred atoms.
        String targetAtomKey = "State(" + target + ")";
        double softTruth = result.getInferredTruth().getOrDefault(
                targetAtomKey,
                result.getInferredTruth().values().stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0.0));
        // distanceToSatisfaction: 1 - softTruth is a sound per-atom approximation;
        // the global solver objective is also available but is a program-level scalar.
        double distanceToSat = 1.0 - softTruth;

        List<String> rules = new ArrayList<>(result.getRules());

        // Human-readable title map: atom key → display label (e.g. State(node_42) → "Alice Smith")
        Map<String, String> atomToTitle = result.getAtomToTitle() != null
                ? result.getAtomToTitle() : Map.of();

        // One EntailmentRecord per inferred atom, using human-readable key when available.
        List<EntailmentRecord> entailments = result.getInferredTruth().entrySet().stream()
                .map(e -> new EntailmentRecord(
                        atomToTitle.getOrDefault(e.getKey(), e.getKey()), e.getValue(),
                        List.of(),   // PSL does not expose per-atom supporting facts
                        rules,
                        computedAt, runId))
                .collect(Collectors.toList());

        // Top constraint violations surface the "why" behind the solution.
        List<String> evidence = new ArrayList<>(result.getTopViolations());

        String summary;
        if (result.getInferredTruth().isEmpty()) {
            summary = "PSL inference for '" + target + "' produced no inferred atoms "
                    + "(subgraph may be empty or target not in program).";
        } else {
            summary = String.format(
                    "PSL (HL-MRF) soft-logic inference for '%s': soft-truth=%.3f, "
                    + "%d target atom%s inferred, %d rule%s active.",
                    target, softTruth,
                    result.getInferredTruth().size(), result.getInferredTruth().size() == 1 ? "" : "s",
                    rules.size(), rules.size() == 1 ? "" : "s");
        }

        log.debug("pslTrail: target='{}' softTruth={} atoms={}", target, softTruth,
                result.getInferredTruth().size());

        return ReasoningTrail.builder(target)
                .question("What is the soft-logic truth of '" + target + "'?")
                .confidence(softTruth)
                .breakdown(ConfidenceBreakdown.ofPsl(softTruth, distanceToSat))
                .entailments(entailments)
                .evidence(evidence)
                .activatedRules(rules)
                .inferenceMode(MODE_PSL)
                .runId(runId)
                .computedAt(computedAt)
                .naturalLanguageSummary(summary)
                .atomKeyToTitle(atomToTitle)
                .build();
    }

    // ── MEBN trail ────────────────────────────────────────────────────────────────

    /**
     * Runs auto-MEBN (SSBN construction + variable elimination) over the KG subgraph
     * rooted at {@code target} and maps the result to a {@link ReasoningTrail}.
     *
     * <p>Populated fields: {@code confidence} (posterior for a variable grounded to the
     * target KG node, or the global maximum when no direct grounding matches),
     * {@code breakdown} (via {@link ConfidenceBreakdown#ofMebn}),
     * {@code entailments} (one per marginalised variable), {@code evidence} (inference
     * trace steps as human-readable strings describing each variable-elimination step).
     * <br>Not populated: {@code derivationTree} — MEBN/VE does not produce a derivation tree.
     * {@code activatedRules} — MEBN uses CPT templates rather than named rules; the
     * inference trace in {@code evidence} is the closest equivalent.</p>
     */
    /**
     * Build the subsumption {@link TypeHierarchy} for an MEBN query from the fact sheet's active
     * ontology schema (is-a from {@code parentType}) merged with the seed subgraph's membership.
     * Returns {@code null} — preserving exact-type grounding — when no schema is bound or the schema
     * declares no is-a links.
     */
    @org.springframework.lang.Nullable
    private TypeHierarchy mebnTypeHierarchy(long factSheetId, List<String> seeds) {
        if (ontologyBindingService == null) {
            return null;
        }
        OntologySchema schema = ontologyBindingService.resolveActiveOntology(factSheetId).orElse(null);
        if (schema == null) {
            return null;
        }
        ReasoningGraph rg = new KnowledgeGraphReasoningAdapter(graphService)
                .maxDepth(3).maxNodes(100)
                .subgraph(seeds);

        // OWL-RL inferred types over the REAL subgraph: an entity the reasoner classifies into a type
        // becomes groundable under that type (and its supertypes), beyond declared parentType is-a.
        Map<String, List<String>> inferredMembers = owlInferredMembers(schema, rg);

        boolean hasIsA = schema.getEntityTypes() != null && schema.getEntityTypes().stream()
                .anyMatch(e -> e != null && e.getParentType() != null && !e.getParentType().isBlank());
        if (!hasIsA && inferredMembers.isEmpty()) {
            return null; // nothing to navigate → exact-type grounding (unchanged)
        }
        return OntologySchemaTypeRegistry.toHierarchy(schema, rg, inferredMembers);
    }

    /** OWL-RL inferred type memberships ({@code typeName → entityIds}) over the real ABox, or empty. */
    private Map<String, List<String>> owlInferredMembers(OntologySchema schema, ReasoningGraph abox) {
        if (owlOntologyBridge == null) {
            return Map.of();
        }
        try {
            OwlOntology tbox = owlOntologyBridge.toOwlOntology(schema);
            OwlRlResult owl = new OwlRlReasoner().reason(abox, tbox);
            Map<String, List<String>> byType = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : owl.inferredTypes().entrySet()) {
                String typeName = localName(e.getValue());
                if (e.getKey() == null || typeName == null || typeName.isBlank()) continue;
                byType.computeIfAbsent(typeName, k -> new ArrayList<>()).add(e.getKey());
            }
            return byType;
        } catch (RuntimeException ex) {
            log.debug("OWL-RL inference for MEBN type hierarchy failed (ontology '{}'): {}",
                    schema.getId(), ex.getMessage());
            return Map.of();
        }
    }

    /** Local name of an IRI: the substring after the last '#' or '/'. */
    private static String localName(String iri) {
        if (iri == null) return null;
        int h = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return (h >= 0 && h < iri.length() - 1) ? iri.substring(h + 1) : iri;
    }

    private ReasoningTrail mebnTrail(String target, long factSheetId) {
        List<String> seeds = List.of(target);
        BayesianInferenceResult result = bayesianService.queryMebnFromKg(
                seeds, Map.of(), 3, 100, mebnTypeHierarchy(factSheetId, seeds));

        String runId = result.getComputedAt() != null ? result.getComputedAt().toString() : "";
        Instant computedAt = result.getComputedAt() != null ? result.getComputedAt() : Instant.now();

        // Primary posterior: find variable(s) whose KG node ID maps to the target;
        // fall back to the maximum posterior across all variables.
        double primaryPosterior = 0.0;
        for (Map.Entry<String, String> entry : result.getVariableToNodeId().entrySet()) {
            if (target.equals(entry.getValue())) {
                primaryPosterior = result.getPosteriors().getOrDefault(entry.getKey(), 0.0);
                break;
            }
        }
        if (primaryPosterior == 0.0 && !result.getPosteriors().isEmpty()) {
            primaryPosterior = result.getPosteriors().values().stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
        final double posterior = primaryPosterior;

        // One EntailmentRecord per marginalised variable.
        List<EntailmentRecord> entailments = result.getPosteriors().entrySet().stream()
                .map(e -> new EntailmentRecord(
                        e.getKey(), e.getValue(),
                        List.of(),   // variable-elimination does not expose supporting facts per variable
                        List.of(),   // no named rules in MEBN; trace is in evidence
                        computedAt, runId))
                .collect(Collectors.toList());

        // Evidence: variable-elimination trace steps as readable strings.
        List<String> evidence = result.getInferenceTrace().stream()
                .map(step -> String.format("eliminate(%s) prior=%.3f posterior=%.3f [%s]",
                        step.getEliminatedTitle() != null
                                ? step.getEliminatedTitle() : step.getEliminatedVariable(),
                        step.getPriorValue() != null ? step.getPriorValue() : 0.0,
                        step.getPosteriorValue() != null ? step.getPosteriorValue() : 0.0,
                        step.getOperation() != null ? step.getOperation() : ""))
                .collect(Collectors.toList());

        String summary;
        if (result.getPosteriors().isEmpty()) {
            summary = "MEBN inference for '" + target + "' produced no posteriors "
                    + "(MTheory could not be constructed for this subgraph).";
        } else {
            summary = String.format(
                    "MEBN (SSBN + variable elimination) inference for '%s': "
                    + "primary posterior=%.3f, %d variable%s marginalised.",
                    target, posterior,
                    result.getPosteriors().size(), result.getPosteriors().size() == 1 ? "" : "s");
        }

        log.debug("mebnTrail: target='{}' posterior={} vars={}", target, posterior,
                result.getPosteriors().size());

        return ReasoningTrail.builder(target)
                .question("What is the Bayesian posterior for '" + target + "'?")
                .confidence(posterior)
                .breakdown(ConfidenceBreakdown.ofMebn(posterior))
                .entailments(entailments)
                .evidence(evidence)
                .inferenceMode(MODE_MEBN)
                .runId(runId)
                .computedAt(computedAt)
                .naturalLanguageSummary(summary)
                .build();
    }

    // ── Shared helpers ───────────────────────────────────────────────────────────

    /**
     * Deterministic NL summary of a derivation tree — no LLM required.
     * Mirrors {@code KbGroundingController.deterministicSummary}.
     *
     * @param atomKeyToTitle optional map from atom key to human-readable title; used to replace
     *                       synthetic PSL constants with display names in child descriptions
     */
    private static String deterministicSummary(DerivationTree tree, String atom,
            VerifyResult verdict, Map<String, String> atomKeyToTitle) {
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
                String childLabel = atomKeyToTitle.getOrDefault(child.atomKey(), child.atomKey());
                childDescs.add("'" + childLabel + "' (confidence "
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

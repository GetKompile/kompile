/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import ai.kompile.graph.reasoning.embedding.kge.KgeTripleScorer;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a {@link ClaimDossier} by aggregating evidence from all available reasoning channels.
 *
 * <h3>Evidence channels (in assessment order)</h3>
 * <ol>
 *   <li><b>DIRECT_EDGE</b>: any relation in the graph from subject→object whose type matches
 *       the predicate (case-insensitive). Weight/confidence becomes the item probability.</li>
 *   <li><b>Verifier signals</b>: {@link DefaultKbVerifier} is run against the canonical atom key
 *       {@code PREDICATE(subject, object)}. SUPPORTED → DATALOG_PROOF or PSL supporting item;
 *       REFUTED → FUNCTIONAL_CONFLICT or NEGATED_ATOM refuting item. Evidence lists from
 *       {@link VerifyResult} become the item provenance.</li>
 *   <li><b>PATH</b>: {@link KnowledgeLinkerScorer} when no direct edge exists. Path score is
 *       squashed to {@code min(0.95, score)} — raw path scores of 1.0 would be overconfident
 *       for indirect paths; the 0.95 cap acknowledges residual uncertainty.</li>
 *   <li><b>KGE</b>: {@link KgePlausibilitySignal} when a scorer is provided. Calibrated
 *       probability is used directly.</li>
 *   <li><b>MINED_RULE</b>: {@link MinedRuleSignal} when a rule set is provided.</li>
 * </ol>
 *
 * <h3>Fusion</h3>
 * Log-odds linear fusion over all items, using {@link FusionWeights#defaults()} (overridable):
 * <pre>
 *   logit(p) = log(p / (1 - p))
 *   clamp(p) = max(1e-4, min(1 - 1e-4, p))   // avoid ±∞ logits at extremes
 *
 *   fused_logit = Σ_i  w_i · logit(clamp(p_i))    [supporting items, positive]
 *               + Σ_j -w_j · logit(clamp(p_j))    [refuting items, negative]
 *
 *   fused_score = σ(fused_logit) = 1 / (1 + exp(-fused_logit))
 * </pre>
 *
 * <p>No training is required. Default weights are interpretable reliability tiers.
 * The synthesis logistic scorer ({@link ai.kompile.graph.reasoning.synthesis.LogisticAnswerScorer})
 * can replace these weights with learned per-domain values later.
 *
 * <h3>References</h3>
 * <ul>
 *   <li>Ciampaglia et al. (2015) "Computational Fact-Checking from Knowledge Networks,"
 *       <em>PLoS ONE</em> — Knowledge Linker PATH evidence.</li>
 *   <li>Shiralkar et al. (2017) "Finding Streams in Knowledge Graphs to Support Fact-Checking,"
 *       <em>WSDM 2019</em> — ExFaKT supporting + refuting chains framing.</li>
 * </ul>
 */
public final class DossierBuilder {

    /**
     * Maximum path score accepted from {@link KnowledgeLinkerScorer}: values of 1.0 would be
     * overconfident for an indirect path — we reserve 1.0 for direct edges.
     */
    static final double PATH_SCORE_CAP = 0.95;

    /** Epsilon for log-odds clamping to avoid ±Infinity logits. */
    static final double LOGIT_EPSILON = 1e-4;

    private final FusionWeights weights;
    private final KnowledgeLinkerScorer linker;

    /** Create a builder with default fusion weights and default linker knobs. */
    public DossierBuilder() {
        this(FusionWeights.defaults(), new KnowledgeLinkerScorer());
    }

    /**
     * Create a builder with explicit fusion weights and linker.
     *
     * @param weights fusion weights (must not be null)
     * @param linker  Knowledge-Linker scorer instance (must not be null)
     */
    public DossierBuilder(FusionWeights weights, KnowledgeLinkerScorer linker) {
        this.weights = Objects.requireNonNull(weights, "weights");
        this.linker  = Objects.requireNonNull(linker,  "linker");
    }

    /**
     * Assess a claim and build the full evidence dossier.
     *
     * @param graph      the knowledge graph (never null)
     * @param subject    subject entity id
     * @param predicate  predicate / relation type
     * @param object     object entity id
     * @param facts      directly-observed fact store (never null)
     * @param inferred   materialized inference results (never null)
     * @param rulesOrNull optional rule set for mined-rule evidence (null = skip)
     * @param kgeOrNull   optional KGE scorer (null = skip KGE evidence)
     * @return a fully assembled {@link ClaimDossier}
     */
    public ClaimDossier assess(ReasoningGraph graph,
                                String subject,
                                String predicate,
                                String object,
                                FactStore facts,
                                InferredFactStore inferred,
                                FolRuleSet rulesOrNull,
                                KgeTripleScorer kgeOrNull) {
        Objects.requireNonNull(graph,    "graph");
        Objects.requireNonNull(subject,  "subject");
        Objects.requireNonNull(predicate,"predicate");
        Objects.requireNonNull(object,   "object");
        Objects.requireNonNull(facts,    "facts");
        Objects.requireNonNull(inferred, "inferred");

        List<DossierItem> supporting = new ArrayList<>();
        List<DossierItem> refuting   = new ArrayList<>();

        // ── 1. DIRECT_EDGE ─────────────────────────────────────────────────────
        boolean hasDirectEdge = false;
        for (GraphRelation rel : graph.outgoing(subject)) {
            if (predicate.equalsIgnoreCase(rel.type()) && object.equals(rel.targetId())) {
                // Use weight as the edge probability: weight carries the user-supplied strength
                // (e.g. 0.9 from addRelation(..., 0.9)). confidence() is reserved for
                // meta-level certainty and defaults to 1.0 in the convenience addRelation API.
                double prob = Math.max(0.0, Math.min(1.0, rel.weight()));
                supporting.add(new DossierItem(
                        DossierItem.Kind.DIRECT_EDGE,
                        "Direct edge: " + subject + " -[" + rel.type() + "]-> " + object
                                + " (weight=" + String.format("%.3f", prob) + ")",
                        prob,
                        List.of(rel.id())));
                hasDirectEdge = true;
            }
        }
        // Also check undirected incoming edges
        for (GraphRelation rel : graph.incoming(subject)) {
            if (!rel.directed() && predicate.equalsIgnoreCase(rel.type())
                    && object.equals(rel.sourceId())) {
                double prob = Math.max(0.0, Math.min(1.0, rel.weight()));
                supporting.add(new DossierItem(
                        DossierItem.Kind.DIRECT_EDGE,
                        "Undirected edge: " + subject + " -[" + rel.type() + "]- " + object
                                + " (weight=" + String.format("%.3f", prob) + ")",
                        prob,
                        List.of(rel.id())));
                hasDirectEdge = true;
            }
        }

        // ── 2. Verifier signals ────────────────────────────────────────────────
        String atomKey = canonicalAtomKey(predicate, subject, object);
        VerifyResult verifyResult = new DefaultKbVerifier(inferred, facts).verify(atomKey);

        switch (verifyResult.status()) {
            case SUPPORTED -> {
                // Determine whether this is a Datalog proof or PSL based on rule id hints
                DossierItem.Kind supportKind = classifyVerifierKind(verifyResult);
                supporting.add(new DossierItem(
                        supportKind,
                        "Verifier: " + atomKey + " SUPPORTED (confidence="
                                + String.format("%.3f", verifyResult.confidence()) + ")",
                        verifyResult.confidence(),
                        new ArrayList<>(verifyResult.evidence())));
                // Counter-evidence from SUPPORTED → add as refuting items
                if (!verifyResult.counterEvidence().isEmpty()) {
                    refuting.add(new DossierItem(
                            DossierItem.Kind.FUNCTIONAL_CONFLICT,
                            "Counter-evidence for " + atomKey + ": "
                                    + String.join("; ", verifyResult.counterEvidence()),
                            verifyResult.confidence(),
                            new ArrayList<>(verifyResult.counterEvidence())));
                }
            }
            case REFUTED -> {
                DossierItem.Kind refuteKind = classifyRefuteKind(verifyResult);
                refuting.add(new DossierItem(
                        refuteKind,
                        "Verifier: " + atomKey + " REFUTED — " + verifyResult.refutationBasis()
                                + " (confidence=" + String.format("%.3f", verifyResult.confidence()) + ")",
                        verifyResult.confidence(),
                        new ArrayList<>(verifyResult.counterEvidence())));
            }
            case UNKNOWN -> { /* no verifier contribution */ }
        }

        // ── 3. PATH (Knowledge Linker) — only when no direct edge ─────────────
        if (!hasDirectEdge) {
            PathEvidence path = linker.score(graph, subject, object, predicate);
            if (path != null) {
                double pathProb = Math.min(PATH_SCORE_CAP, path.score());
                supporting.add(new DossierItem(
                        DossierItem.Kind.PATH,
                        "Knowledge-Linker path: " + String.join(" → ", path.pathNodeIds())
                                + " (score=" + String.format("%.4f", path.score())
                                + ", squashed to " + String.format("%.4f", pathProb) + ")",
                        pathProb,
                        path.pathNodeIds()));
            }
        }

        // ── 4. KGE ────────────────────────────────────────────────────────────
        if (kgeOrNull != null) {
            KgePlausibilitySignal kgeSignal = new KgePlausibilitySignal(kgeOrNull, new PlattCalibrator());
            double kgeProb = kgeSignal.calibratedProbability(subject, predicate, object);
            if (!Double.isNaN(kgeProb)) {
                supporting.add(new DossierItem(
                        DossierItem.Kind.KGE,
                        "KGE plausibility: " + subject + " " + predicate + " " + object
                                + " (calibrated=" + String.format("%.4f", kgeProb) + ")",
                        kgeProb,
                        List.of(subject, predicate, object)));
            }
        }

        // ── 5. MINED_RULE ─────────────────────────────────────────────────────
        if (rulesOrNull != null) {
            MinedRuleSignal ruleSignal = new MinedRuleSignal(rulesOrNull);
            List<RuleEvidence> firingRules = ruleSignal.evaluate(graph, predicate, subject, object);
            for (RuleEvidence re : firingRules) {
                supporting.add(new DossierItem(
                        DossierItem.Kind.MINED_RULE,
                        "Rule fires: " + re.ruleDisplay(),
                        re.confidence(),
                        List.of(re.ruleName())));
            }
        }

        // ── 6. Fusion ─────────────────────────────────────────────────────────
        double fusedScore = fuse(supporting, refuting, weights);

        // Build canonical claim atom string
        String claimAtom = canonicalAtomKey(predicate, subject, object);

        return new ClaimDossier(claimAtom, subject, predicate, object,
                supporting, refuting, fusedScore);
    }

    // ── Package-private for testing ────────────────────────────────────────────

    /**
     * Log-odds linear fusion.
     *
     * <pre>
     *   fused_logit = Σ_supporting w_i · logit(clamp(p_i))
     *               - Σ_refuting  w_j · logit(clamp(p_j))
     *   fused_score = σ(fused_logit)
     * </pre>
     */
    static double fuse(List<DossierItem> supporting, List<DossierItem> refuting,
                        FusionWeights weights) {
        double logitSum = 0.0;

        for (DossierItem item : supporting) {
            double w = weights.weightFor(item.kind());
            double p = clampLogitInput(item.probability());
            logitSum += w * logit(p);
        }

        for (DossierItem item : refuting) {
            double w = weights.weightFor(item.kind());
            double p = clampLogitInput(item.probability());
            logitSum -= w * logit(p);
        }

        return sigmoid(logitSum);
    }

    static double logit(double p) {
        return Math.log(p / (1.0 - p));
    }

    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    static double clampLogitInput(double p) {
        return Math.max(LOGIT_EPSILON, Math.min(1.0 - LOGIT_EPSILON, p));
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Build the canonical atom key: {@code predicate(subject, object)}. Predicate case is
     * preserved — KB atom keys are case-sensitive, so folding case here would disconnect the
     * verifier channel from stores keyed with camelCase predicates.
     */
    static String canonicalAtomKey(String predicate, String subject, String object) {
        return predicate + "(" + subject + ", " + object + ")";
    }

    /**
     * Classify a SUPPORTED VerifyResult as DATALOG_PROOF (rule ids present) or PSL (none).
     * If the evidence list contains entries that look like rule ids (e.g. contain "weight:"),
     * it's likely a PSL inference; otherwise treat as Datalog proof.
     */
    private static DossierItem.Kind classifyVerifierKind(VerifyResult vr) {
        for (String e : vr.evidence()) {
            if (e.startsWith("weight:") || e.contains("weight:")) {
                return DossierItem.Kind.PSL;
            }
        }
        return DossierItem.Kind.DATALOG_PROOF;
    }

    /**
     * Classify a REFUTED VerifyResult as NEGATED_ATOM or FUNCTIONAL_CONFLICT based on the
     * refutation basis string.
     */
    private static DossierItem.Kind classifyRefuteKind(VerifyResult vr) {
        String basis = vr.refutationBasis();
        if (basis != null && basis.startsWith("functional-conflict")) {
            return DossierItem.Kind.FUNCTIONAL_CONFLICT;
        }
        return DossierItem.Kind.NEGATED_ATOM;
    }
}

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

import ai.kompile.graph.reasoning.fol.FolRule;
import ai.kompile.graph.reasoning.fol.FolRuleSet;
import ai.kompile.graph.reasoning.fol.ReasoningGraphKnowledgeBase;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mined-rule signal for claim evidence.
 *
 * <p>Given a {@link FolRuleSet} and a claim triple (predicate, subject, object), evaluates
 * each rule whose consequent description matches the predicate. For each matching rule, checks
 * whether the antecedent is satisfied for the claim's constants against the graph, using
 * {@link ReasoningGraphKnowledgeBase} (the existing library adapter — no reimplementation).
 *
 * <p>Predicate matching is done by checking whether the consequent's
 * {@link ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint#describe()} contains the
 * claim predicate (case-insensitive), since {@link ai.kompile.graph.reasoning.fol.FolRule}
 * uses {@link ai.kompile.graph.reasoning.mebn.logic.Constraints} atoms whose descriptions
 * encode the predicate name.
 *
 * <p>Rule confidence is {@link FolRule#weight()} clamped to {@code [0,1]}.
 * For hard-boolean rules (weight &gt; 1.0) we cap at 0.9 to remain honest about uncertainty
 * (a high-weight PSL rule is not a proven logical fact).
 */
public final class MinedRuleSignal {

    /** Weight cap applied when rule.weight() > 1.0 (PSL rules use unbounded positive reals). */
    static final double HARD_RULE_CONFIDENCE_CAP = 0.9;

    private final FolRuleSet ruleSet;

    /**
     * Create a signal backed by the given rule set.
     *
     * @param ruleSet the FOL rules to evaluate; must not be {@code null}
     */
    public MinedRuleSignal(FolRuleSet ruleSet) {
        this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    }

    /**
     * Evaluate which rules in the set fire for the given claim.
     *
     * <p>A rule "fires" when:
     * <ol>
     *   <li>Its consequent description contains {@code predicate} (case-insensitive).</li>
     *   <li>The antecedent (if present) evaluates to {@code true} against the graph
     *       with the canonical variable bindings {@code X=subject, Y=object}.</li>
     * </ol>
     *
     * @param graph     the knowledge graph providing ground truth for antecedent evaluation
     * @param predicate the claim's predicate (relation type)
     * @param subject   the claim's subject entity id (bound to variable {@code X})
     * @param object    the claim's object entity id (bound to variable {@code Y})
     * @return list of {@link RuleEvidence} for each firing rule (empty = no rules fire)
     */
    public List<RuleEvidence> evaluate(ReasoningGraph graph, String predicate,
                                        String subject, String object) {
        Objects.requireNonNull(graph,     "graph must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        Objects.requireNonNull(subject,   "subject must not be null");
        Objects.requireNonNull(object,    "object must not be null");

        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);

        // Canonical bindings: X → subject, Y → object (standard FOL rule variable convention)
        // Rules may use different variable names; we also try Z for ternary rules.
        Map<String, String> bindings = new HashMap<>();
        bindings.put("X", subject);
        bindings.put("Y", object);
        bindings.put("Z", object); // alias for rules with 3-variable chains
        // Also try lower-case variants (style varies across rule authors)
        bindings.put("x", subject);
        bindings.put("y", object);
        bindings.put("z", object);

        List<RuleEvidence> result = new ArrayList<>();

        for (FolRule rule : ruleSet.rules()) {
            // Check whether this rule's consequent concerns the target predicate
            if (!consequentMatchesPredicate(rule, predicate)) {
                continue;
            }

            // Evaluate antecedent
            boolean antecedentHolds;
            if (!rule.hasAntecedent()) {
                // Unconditional rule — always fires for any matching consequent predicate
                antecedentHolds = true;
            } else {
                try {
                    antecedentHolds = rule.antecedent().evaluate(kb, bindings);
                } catch (Exception ex) {
                    // Evaluation failure (e.g. null binding for a variable not in our canonical set)
                    // → treat as not firing; don't crash the whole dossier
                    antecedentHolds = false;
                }
            }

            if (antecedentHolds) {
                double conf = ruleConfidence(rule);
                result.add(new RuleEvidence(rule.name(), rule.toString(), conf));
            }
        }

        return result;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Check if the rule's consequent description contains the claim predicate (case-insensitive).
     * Also checks the rule name as a secondary hint, since rule names often encode the predicate.
     */
    private static boolean consequentMatchesPredicate(FolRule rule, String predicate) {
        String consequentDesc = rule.consequent().describe();
        String lower = predicate.toLowerCase(java.util.Locale.ROOT);
        if (consequentDesc.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
            return true;
        }
        // Also match against the rule name (e.g. "worksAt-locatedIn-basedIn" for predicate "basedIn")
        String ruleName = rule.name().toLowerCase(java.util.Locale.ROOT);
        return ruleName.contains(lower);
    }

    /**
     * Convert a rule's weight to a confidence in {@code [0, 1]}.
     * PSL rule weights are positive reals — clamp to 0.9 max to remain honest.
     */
    static double ruleConfidence(FolRule rule) {
        double w = rule.weight();
        if (Double.isNaN(w) || w <= 0.0) return 0.1; // degenerate
        if (w > 1.0) return HARD_RULE_CONFIDENCE_CAP;
        return Math.max(0.0, Math.min(1.0, w));
    }
}

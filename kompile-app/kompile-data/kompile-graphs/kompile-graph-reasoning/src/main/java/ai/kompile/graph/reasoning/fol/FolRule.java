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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A weighted / likelihood-bearing first-order-logic rule over a {@link ReasoningGraphKnowledgeBase}.
 *
 * <p>A {@code FolRule} bundles a {@link LogicalConstraint} antecedent (the body of the rule),
 * a {@link LogicalConstraint} consequent (the head), and a scalar {@link #weight} (or likelihood).
 * The weight is real-valued — positive reals are used as PSL-style rule weights, but callers may
 * also interpret it as a probability in {@code [0,1]}.</p>
 *
 * <pre>
 *   // "If A activates B (edge of type ACTIVATES), then B is probably active (weight 2.5)"
 *   FolRule rule = FolRule.builder("activates-propagation")
 *       .weight(2.5)
 *       .antecedent(Constraints.and(
 *           Constraints.entityExists("X"),
 *           Constraints.edgeOfType("X", "Y", "ACTIVATES")))
 *       .consequent(Constraints.entityExists("Y"))
 *       .build();
 * </pre>
 *
 * <p>Rules are evaluated in one of two ways:</p>
 * <ul>
 *   <li><b>Satisfaction check</b>: {@link #isSatisfied(KnowledgeBase, Map)} — does the
 *       implication hold for a concrete binding of the free variables?</li>
 *   <li><b>PSL inference</b>: the rule is translated to a {@link ai.kompile.graph.reasoning.psl.PslRule}
 *       by {@link FolInferenceService} and combined with a grounded {@link ai.kompile.graph.reasoning.psl.PslProgram}
 *       for soft-truth MAP inference across the whole graph.</li>
 * </ul>
 *
 * <p>Instances are immutable. Use the {@link Builder} to construct.</p>
 */
public final class FolRule {

    /** Human-readable name for debugging and reporting. */
    private final String name;

    /**
     * Rule weight. Semantics depend on the inference engine:
     * <ul>
     *   <li>PSL: positive real controlling how strongly violations are penalized.</li>
     *   <li>Direct satisfaction: ignored (rule is hard-boolean).</li>
     * </ul>
     * Default: {@code 1.0}.
     */
    private final double weight;

    /**
     * Whether the PSL encoding should use a squared hinge ({@code ^2}) rather than
     * a linear hinge ({@code ^1}). The squared hinge makes the objective smoother and
     * better-conditioned; recommended for most rules. Default: {@code true}.
     */
    private final boolean squared;

    /**
     * Optional entity-type scope. If non-null, the rule is only applied to entities
     * whose {@link ai.kompile.graph.reasoning.model.GraphEntity#type()} matches this string
     * (case-insensitive). Useful for rules that only apply to a specific class of entity.
     */
    private final String entityTypeScope;

    /** Body/antecedent: the conditions that trigger this rule. May be {@code null} for an unconditional rule. */
    private final LogicalConstraint antecedent;

    /** Head/consequent: what the rule asserts when the antecedent holds. Never {@code null}. */
    private final LogicalConstraint consequent;

    private FolRule(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.weight = b.weight;
        this.squared = b.squared;
        this.entityTypeScope = b.entityTypeScope;
        this.antecedent = b.antecedent; // null = unconditional
        this.consequent = Objects.requireNonNull(b.consequent, "consequent must be set");
    }

    // ─── Accessors ──────────────────────────────────────────────────────────────

    public String name() { return name; }

    public double weight() { return weight; }

    public boolean squared() { return squared; }

    public String entityTypeScope() { return entityTypeScope; }

    /** Whether this rule has an antecedent (body) or is unconditional. */
    public boolean hasAntecedent() { return antecedent != null; }

    public LogicalConstraint antecedent() { return antecedent; }

    public LogicalConstraint consequent() { return consequent; }

    /**
     * All free variables referenced by this rule (union of antecedent and consequent variables).
     */
    public Set<String> freeVariables() {
        Set<String> vars = new HashSet<>();
        if (antecedent != null) vars.addAll(antecedent.getFreeVariables());
        vars.addAll(consequent.getFreeVariables());
        return vars;
    }

    // ─── Evaluation ─────────────────────────────────────────────────────────────

    /**
     * Hard boolean satisfaction check: does the implication {@code antecedent → consequent}
     * hold for the given variable bindings?
     *
     * @param kb       knowledge base to evaluate against
     * @param bindings map from variable name to concrete entity id
     * @return {@code true} if the antecedent is false (vacuously satisfied) or the consequent holds
     */
    public boolean isSatisfied(KnowledgeBase kb, Map<String, String> bindings) {
        if (antecedent == null) {
            return consequent.evaluate(kb, bindings);
        }
        return !antecedent.evaluate(kb, bindings) || consequent.evaluate(kb, bindings);
    }

    /**
     * Violation degree in {@code [0, 1]} for Łukasiewicz / soft-truth semantics.
     * Returns {@code 0} when the rule is satisfied, positive values when violated.
     * Currently a binary {0, 1} approximation; the PSL engine computes the real soft degree.
     */
    public double violationDegree(KnowledgeBase kb, Map<String, String> bindings) {
        return isSatisfied(kb, bindings) ? 0.0 : 1.0;
    }

    // ─── Builder ────────────────────────────────────────────────────────────────

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Shorthand: create a rule with a fixed weight, antecedent, and consequent in one call. */
    public static FolRule of(String name, double weight,
                              LogicalConstraint antecedent,
                              LogicalConstraint consequent) {
        return builder(name).weight(weight).antecedent(antecedent).consequent(consequent).build();
    }

    /** Shorthand: unconditional rule asserting the consequent always holds. */
    public static FolRule unconditional(String name, double weight, LogicalConstraint consequent) {
        return builder(name).weight(weight).consequent(consequent).build();
    }

    public static final class Builder {
        private final String name;
        private double weight = 1.0;
        private boolean squared = true;
        private String entityTypeScope;
        private LogicalConstraint antecedent;
        private LogicalConstraint consequent;

        private Builder(String name) {
            this.name = name;
        }

        public Builder weight(double weight) { this.weight = weight; return this; }
        public Builder squared(boolean squared) { this.squared = squared; return this; }
        public Builder entityTypeScope(String typeName) { this.entityTypeScope = typeName; return this; }
        public Builder antecedent(LogicalConstraint c) { this.antecedent = c; return this; }
        public Builder consequent(LogicalConstraint c) { this.consequent = c; return this; }

        public FolRule build() { return new FolRule(this); }
    }

    @Override
    public String toString() {
        return "FolRule{" + name + ", w=" + weight
                + (entityTypeScope != null ? ", scope=" + entityTypeScope : "")
                + ", " + (antecedent != null ? antecedent.describe() + " => " : "")
                + consequent.describe() + "}";
    }
}

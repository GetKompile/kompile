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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A single, walkable, serializable reasoning trace — the unified "how was this concluded?" structure.
 *
 * <p>A trace is a tree of {@link Step}s: the root is the conclusion, and each step's
 * {@link Step#premises() premises} are the sub-steps it was derived from, down to the base
 * {@link StepKind#FACT facts}/{@link StepKind#ASSUMPTION assumptions} at the leaves. Unlike the
 * engine-specific proof structures ({@link DerivationTree} for FOL grounding, {@code OpinionTree} for
 * subjective-logic fusion), one {@code ReasoningTrace} represents <em>any</em> reasoning — logical
 * rules, probabilistic inference, evidence fusion, retrieval — in a single form you can enumerate,
 * render, bundle, and round-trip.</p>
 *
 * <p>Build one bottom-up with the {@link Step} factories, or adapt an existing FOL
 * {@link DerivationTree} via {@link #fromDerivation(DerivationTree)}. It is {@link Serializable}, so
 * it rides inside a {@code .kgraph} through {@code UnifiedGraph.putModel(name, trace)}.</p>
 */
public final class ReasoningTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The category of a reasoning step. */
    public enum StepKind {
        /** An observed/base fact — a leaf. */
        FACT,
        /** A hypothesized/assumed input — a leaf. */
        ASSUMPTION,
        /** A logical (FOL/Datalog) rule application. */
        RULE,
        /** Subjective-logic / evidence fusion of premises. */
        FUSION,
        /** Probabilistic inference (PSL/HL-MRF, Bayesian, MEBN). */
        INFERENCE,
        /** A retrieval / lookup step. */
        QUERY,
        /** A deterministic numeric calculation or aggregation. */
        CALCULATION,
        /** A unit, dimensional, replay, or reconciliation validation. */
        VALIDATION,
        /** A goal-seek or constrained optimization step. */
        OPTIMIZATION,
        /**
         * Counter-evidence or argument that attacks (rebuts) the parent conclusion.
         * Used to surface contradictions, defeated rules, and disconfirming observations in the trace.
         */
        REBUTTAL,
        /**
         * A belief-revision event: the reasoner revised or retracted a prior conclusion
         * (e.g. BeliefReviser accepted a defeating argument, BeliefReviser.retract fired).
         * Attach the old conclusion in {@link Step#operation()} and the new belief state in meta.
         */
        REVISION
    }

    /**
     * One node in a {@link ReasoningTrace}: a {@link #conclusion()} reached by an {@link #operation}
     * over its {@link #premises()}, with a {@link #confidence()} in {@code [0, 1]} and optional
     * {@link #source()} provenance.
     *
     * <p><b>E3 additions (serialVersionUID = 1L preserved)</b>: {@link #opinion} carries the full
     * subjective-logic {@link Opinion} (b,d,u,a) when available (nullable); {@link #meta} holds
     * arbitrary string key/value annotations (never null — defaults to {@link Map#of()}).
     * Old serialized traces load with null/empty for these two new components because Java record
     * serialization restores fields by name — missing fields default to null for reference types.</p>
     */
    public record Step(
            StepKind kind,
            String conclusion,
            String operation,
            double confidence,
            String source,
            List<Step> premises,
            Opinion opinion,
            Map<String, String> meta) implements Serializable {

        private static final long serialVersionUID = 1L;

        public Step {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(conclusion, "conclusion");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
            }
            operation = operation == null ? "" : operation;
            premises = premises == null ? List.of() : List.copyOf(premises);
            // opinion stays nullable — absence means "scalar confidence only"
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }

        /** Whether this is a base step (no premises). */
        public boolean isLeaf() {
            return premises.isEmpty();
        }

        // ── Existing factories — signatures unchanged, pass null opinion / empty meta ──

        /** A base FACT leaf. */
        public static Step fact(String conclusion, double confidence, String source) {
            return new Step(StepKind.FACT, conclusion, "observed", confidence, source,
                    List.of(), null, null);
        }

        /** A hypothesized ASSUMPTION leaf. */
        public static Step assumption(String conclusion, double confidence) {
            return new Step(StepKind.ASSUMPTION, conclusion, "assumed", confidence, null,
                    List.of(), null, null);
        }

        /** A derived step over the given premises. */
        public static Step derived(StepKind kind, String conclusion, String operation,
                                   double confidence, List<Step> premises) {
            return new Step(kind, conclusion, operation, confidence, null, premises, null, null);
        }

        /** A derived step over the given premises (varargs convenience). */
        public static Step derived(StepKind kind, String conclusion, String operation,
                                   double confidence, Step... premises) {
            return new Step(kind, conclusion, operation, confidence, null, List.of(premises), null, null);
        }

        // ── E3 enrichment factories ────────────────────────────────────────────

        /**
         * A FACT leaf with a full subjective-logic {@link Opinion} (b,d,u,a) alongside the scalar confidence.
         * Use when the opinion quantifies evidence quality and must survive the trace for gap analysis.
         */
        public static Step fact(String conclusion, double confidence, String source, Opinion opinion) {
            return new Step(StepKind.FACT, conclusion, "observed", confidence, source,
                    List.of(), opinion, null);
        }

        /**
         * A derived step with an explicit {@link Opinion}, per-step {@code meta}, and explicit premise list.
         * All fields are copied defensively; {@code opinion} may be null; {@code meta} null → empty.
         */
        public static Step derived(StepKind kind, String conclusion, String operation,
                                   double confidence, Opinion opinion, Map<String, String> meta,
                                   List<Step> premises) {
            return new Step(kind, conclusion, operation, confidence, null, premises, opinion, meta);
        }

        // ── Copy-with helpers ──────────────────────────────────────────────────

        /**
         * Return a copy of {@code base} with the given {@link Opinion} attached.
         * All other components are preserved exactly.
         */
        public static Step withOpinion(Step base, Opinion opinion) {
            return new Step(base.kind(), base.conclusion(), base.operation(), base.confidence(),
                    base.source(), base.premises(), opinion, base.meta());
        }

        /**
         * Return a copy of {@code base} with the given {@code meta} map merged on top
         * (existing meta is replaced, not merged). Null meta → empty.
         */
        public static Step withMeta(Step base, Map<String, String> meta) {
            return new Step(base.kind(), base.conclusion(), base.operation(), base.confidence(),
                    base.source(), base.premises(), base.opinion(), meta);
        }
    }

    private final Step root;

    private ReasoningTrace(Step root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Wrap a step tree as a trace (the root step is the conclusion). */
    public static ReasoningTrace of(Step root) {
        return new ReasoningTrace(root);
    }

    /**
     * Adapt a FOL {@link DerivationTree} into a unified trace: each derivation node becomes a
     * {@link StepKind#RULE} step (or a {@link StepKind#FACT} leaf), carrying its atom key as the
     * conclusion, its {@code ruleApplied} as the operation, its confidence, and its provenance.
     */
    public static ReasoningTrace fromDerivation(DerivationTree tree) {
        return new ReasoningTrace(fromDerivationNode(tree));
    }

    private static Step fromDerivationNode(DerivationTree node) {
        List<Step> premises = new ArrayList<>();
        for (DerivationTree child : node.children()) {
            premises.add(fromDerivationNode(child));
        }
        StepKind kind = node.isLeaf() ? StepKind.FACT : StepKind.RULE;
        String operation = node.ruleApplied() == null ? "" : node.ruleApplied();
        return new Step(kind, node.atomKey(), operation, node.confidence(), node.sourceProvenance(),
                premises, null, null);
    }

    // ── Enumeration ──────────────────────────────────────────────────────────────

    /** The concluding (root) step. */
    @JsonProperty("conclusion")
    public Step conclusion() {
        return root;
    }

    /** Every step in the trace, in pre-order (conclusion first, then premises depth-first). */
    public List<Step> steps() {
        List<Step> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private void collect(Step step, List<Step> out) {
        out.add(step);
        for (Step p : step.premises()) collect(p, out);
    }

    /** The base steps (facts/assumptions) the conclusion ultimately rests on. */
    public List<Step> leaves() {
        List<Step> out = new ArrayList<>();
        for (Step s : steps()) {
            if (s.isLeaf()) out.add(s);
        }
        return out;
    }

    /** Total number of steps. */
    @JsonProperty("size")
    public int size() {
        return steps().size();
    }

    /** Depth of the trace (a single leaf has depth 1). */
    @JsonProperty("depth")
    public int depth() {
        return depth(root);
    }

    private int depth(Step step) {
        int max = 0;
        for (Step p : step.premises()) max = Math.max(max, depth(p));
        return 1 + max;
    }

    /** Whether any step concludes {@code conclusion}. */
    public boolean contains(String conclusion) {
        for (Step s : steps()) {
            if (s.conclusion().equals(conclusion)) return true;
        }
        return false;
    }

    /** The direct premises of the step that concludes {@code conclusion}, or empty if none. */
    public List<Step> premisesOf(String conclusion) {
        for (Step s : steps()) {
            if (s.conclusion().equals(conclusion)) return s.premises();
        }
        return List.of();
    }

    // ── Rendering ────────────────────────────────────────────────────────────────

    /** A compact JSON rendering of the whole trace (for a UI / SSE reasoning-trace panel). */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        toJson(root, sb);
        return sb.toString();
    }

    private void toJson(Step step, StringBuilder sb) {
        sb.append("{\"kind\":\"").append(step.kind()).append("\",\"conclusion\":");
        appendString(sb, step.conclusion());
        sb.append(",\"operation\":");
        appendString(sb, step.operation());
        sb.append(",\"confidence\":").append(step.confidence());
        sb.append(",\"source\":");
        appendString(sb, step.source());
        // E3: emit opinion when present
        if (step.opinion() != null) {
            Opinion o = step.opinion();
            sb.append(",\"opinion\":{\"b\":").append(o.belief())
              .append(",\"d\":").append(o.disbelief())
              .append(",\"u\":").append(o.uncertainty())
              .append(",\"a\":").append(o.baseRate())
              .append('}');
        }
        // E3: emit meta when non-empty (keys sorted for determinism)
        if (step.meta() != null && !step.meta().isEmpty()) {
            sb.append(",\"meta\":{");
            Map<String, String> sorted = new TreeMap<>(step.meta());
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendString(sb, e.getKey());
                sb.append(':');
                appendString(sb, e.getValue());
            }
            sb.append('}');
        }
        sb.append(",\"premises\":[");
        List<Step> premises = step.premises();
        for (int i = 0; i < premises.size(); i++) {
            if (i > 0) sb.append(',');
            toJson(premises.get(i), sb);
        }
        sb.append("]}");
    }

    private static void appendString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Clamp a possibly-NaN / out-of-range value into {@code [0, 1]} for {@link Step} construction. */
    static double clamp01(double x) {
        return Double.isNaN(x) ? 0.0 : Math.max(0.0, Math.min(1.0, x));
    }
}

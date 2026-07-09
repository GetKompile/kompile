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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WP5a — an operator tree over subjective-logic {@link Opinion}s. It is simultaneously the FUSION
 * computation (each internal node combines its children with the operator matching their dependence
 * structure) and the TRACE (the tree itself is the human/machine-readable explanation of how the root
 * opinion was reached). This replaces arithmetic-mean fusion with a structured, operator-correct fold.
 *
 * <p>Leaves carry a calibrated opinion (+ optional {@code calibrationId} and {@code sourceRefs}
 * provenance); internal nodes carry an {@link Op} that is applied to the children bottom-up by
 * {@link #value()}. Immutable + record-shaped so it serializes with Jackson in the app layer while the
 * lib stays infra-free (no Jackson dependency needed here).</p>
 */
public record OpinionTree(
        String label,
        Op op,
        Opinion leafOpinion,
        Double discountTrust,
        List<OpinionTree> children,
        String calibrationId,
        List<String> sourceRefs
) {

    /** The operator applied at a node (matched to the children's dependence structure). */
    public enum Op {
        /** A calibrated evidence leaf — {@link #leafOpinion} is the value. */
        LEAF,
        /** Cumulative fusion ⊕ of INDEPENDENT sources on the same proposition (distinct docs/asserters). */
        FUSE_INDEPENDENT,
        /** Consensus of CORRELATED sources (e.g. PSL & MEBN reading the same FactStore) — never ⊕. */
        CONSENSUS,
        /** Conjunction ⊙ of independent propositions (serial derivation / requirement groups). */
        CONJOIN,
        /** Trust discount ⊗ of the single child by {@link #discountTrust}. */
        DISCOUNT,
        /** Negation of the single child. */
        COMPLEMENT
    }

    public OpinionTree {
        children = children == null ? List.of() : List.copyOf(children);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    // ── Factories ───────────────────────────────────────────────────────────────

    public static OpinionTree leaf(String label, Opinion opinion, String calibrationId, List<String> sourceRefs) {
        return new OpinionTree(label, Op.LEAF, opinion, null, List.of(), calibrationId, sourceRefs);
    }

    public static OpinionTree leaf(String label, Opinion opinion) {
        return leaf(label, opinion, null, List.of());
    }

    public static OpinionTree fuseIndependent(String label, List<OpinionTree> children) {
        return new OpinionTree(label, Op.FUSE_INDEPENDENT, null, null, children, null, List.of());
    }

    public static OpinionTree consensus(String label, List<OpinionTree> children) {
        return new OpinionTree(label, Op.CONSENSUS, null, null, children, null, List.of());
    }

    public static OpinionTree conjoin(String label, List<OpinionTree> children) {
        return new OpinionTree(label, Op.CONJOIN, null, null, children, null, List.of());
    }

    public static OpinionTree discount(String label, double trust, OpinionTree child) {
        return new OpinionTree(label, Op.DISCOUNT, null, trust, List.of(child), null, List.of());
    }

    public static OpinionTree complement(String label, OpinionTree child) {
        return new OpinionTree(label, Op.COMPLEMENT, null, null, List.of(child), null, List.of());
    }

    // ── Evaluation ──────────────────────────────────────────────────────────────

    /** The opinion at this node, computed bottom-up from the children per {@link #op}. */
    public Opinion value() {
        return switch (op) {
            case LEAF -> leafOpinion != null ? leafOpinion : Opinion.vacuous();
            case FUSE_INDEPENDENT -> children.isEmpty() ? Opinion.vacuous() : Opinion.cumulativeFuse(childValues());
            case CONSENSUS -> children.isEmpty() ? Opinion.vacuous() : Opinion.consensus(childValueList());
            case CONJOIN -> children.isEmpty() ? Opinion.vacuous() : Opinion.conjoinAll(childValues());
            case DISCOUNT -> children.isEmpty()
                    ? Opinion.vacuous()
                    : children.get(0).value().discount(discountTrust != null ? discountTrust : 1.0);
            case COMPLEMENT -> children.isEmpty() ? Opinion.vacuous() : children.get(0).value().complement();
        };
    }

    /** The projected probability of the root opinion — the headline "likelihood" of a synthesized answer. */
    public double expectation() {
        return value().expectation();
    }

    private Opinion[] childValues() {
        Opinion[] vs = new Opinion[children.size()];
        for (int i = 0; i < children.size(); i++) {
            vs[i] = children.get(i).value();
        }
        return vs;
    }

    private List<Opinion> childValueList() {
        return children.stream().map(OpinionTree::value).toList();
    }

    // ── Trace bridge ──────────────────────────────────────────────────────────────

    /**
     * Convert this opinion operator tree into the unified {@link ReasoningTrace}: each node becomes a
     * {@link ReasoningTrace.StepKind#FUSION} step (or a {@link ReasoningTrace.StepKind#FACT} leaf),
     * carrying its label, operator, and projected {@link #expectation()} as the confidence.
     *
     * <p><b>E3 enrichments</b>:
     * <ul>
     *   <li>LEAF nodes carry the full {@code leafOpinion} in {@link ReasoningTrace.Step#opinion()} so
     *       gap analyzers can distinguish "uncertain" from "contested" evidence.</li>
     *   <li>{@code calibrationId} is always emitted in step meta as {@code "calibrationId"} — even when
     *       {@code sourceRefs} is non-empty (the old code dropped calibrationId whenever sourceRefs was
     *       set; E3 fixes this).</li>
     *   <li>{@code discountTrust}, when present on DISCOUNT nodes, is emitted as {@code "discountTrust"}
     *       in step meta.</li>
     * </ul></p>
     */
    public ReasoningTrace toReasoningTrace() {
        return ReasoningTrace.of(toTraceStep(this));
    }

    private static ReasoningTrace.Step toTraceStep(OpinionTree node) {
        List<ReasoningTrace.Step> premises = new java.util.ArrayList<>();
        for (OpinionTree child : node.children()) {
            premises.add(toTraceStep(child));
        }
        ReasoningTrace.StepKind kind = node.op() == Op.LEAF
                ? ReasoningTrace.StepKind.FACT : ReasoningTrace.StepKind.FUSION;
        // Source: prefer joined sourceRefs; calibrationId now goes into meta instead (see below)
        String source = (node.sourceRefs() != null && !node.sourceRefs().isEmpty())
                ? String.join(",", node.sourceRefs()) : null;
        String label = node.label() == null ? node.op().name() : node.label();

        // E3: opinion for LEAF nodes only
        Opinion opinion = (node.op() == Op.LEAF) ? node.leafOpinion() : null;

        // E3: meta — calibrationId always present when non-null; discountTrust when set
        Map<String, String> meta = null;
        if (node.calibrationId() != null || node.discountTrust() != null) {
            meta = new HashMap<>();
            if (node.calibrationId() != null) {
                meta.put("calibrationId", node.calibrationId());
            }
            if (node.discountTrust() != null) {
                meta.put("discountTrust", String.valueOf(node.discountTrust()));
            }
        }

        return new ReasoningTrace.Step(kind, label, node.op().name(),
                ReasoningTrace.clamp01(node.expectation()), source, premises, opinion, meta);
    }
}

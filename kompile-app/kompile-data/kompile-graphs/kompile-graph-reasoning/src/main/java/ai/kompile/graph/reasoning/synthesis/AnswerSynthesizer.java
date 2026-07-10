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
package ai.kompile.graph.reasoning.synthesis;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.OpinionTree;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WP12a — the pure core of the answer-synthesis pipeline. Every signal about a candidate answer is a
 * calibrated {@link Opinion}; signals fuse with the operator matching their dependence structure and
 * the result IS an {@link OpinionTree} trace, so the likelihood is reproducible from the trace.
 *
 * <p>Composition (Part I §1): retrieval signals are INDEPENDENT so they fuse (⊕); reasoning engines
 * are CORRELATED (they read the same FactStore) so they take consensus — never ⊕ — which avoids
 * inflating confidence from agreement of dependent sources; the per-group opinions are then conjoined
 * (⊙) at the root — a candidate must satisfy retrieval AND type AND consistency AND inference, and
 * {@code E(x⊙y)=E(x)·E(y)} keeps the composite honest. Infra-free + fully unit-testable (no Spring).</p>
 */
public final class AnswerSynthesizer {

    private AnswerSynthesizer() {
    }

    /** The dependence class of a signal — decides how it fuses with its peers. */
    public enum SignalGroup { RETRIEVAL, ENGINE, TYPE, CONSISTENCY }

    /** One calibrated signal about a candidate answer (e.g. text-ANN, KGE, PSL, MEBN, type, consistency). */
    public record CandidateSignal(SignalGroup group, String label, Opinion opinion,
                                  String calibrationId, List<String> sourceRefs) {
        public static CandidateSignal of(SignalGroup group, String label, Opinion opinion) {
            return new CandidateSignal(group, label, opinion, null, List.of());
        }
    }

    /** A candidate answer entity plus the signals gathered about it. */
    public record Candidate(String entityId, List<CandidateSignal> signals) {
    }

    /** A synthesized, ranked answer: the fused opinion + the operator-tree trace that produced it. */
    public record SynthesizedAnswer(String entityId, Opinion opinion, OpinionTree trace) {
        /** The headline likelihood = the projected probability of the root opinion. */
        public double likelihood() {
            return opinion.expectation();
        }
    }

    /**
     * The operator each signal group folds with (the default {@code SynthesisPolicy}). Retrieval / type
     * / consistency signals are treated as independent (⊕); reasoning engines take consensus.
     */
    public static OpinionTree.Op operatorFor(SignalGroup group) {
        return switch (group) {
            case RETRIEVAL, TYPE, CONSISTENCY -> OpinionTree.Op.FUSE_INDEPENDENT;
            case ENGINE -> OpinionTree.Op.CONSENSUS;
        };
    }

    /** Synthesize each candidate and rank by likelihood (descending). Pure. */
    public static List<SynthesizedAnswer> synthesize(List<Candidate> candidates) {
        List<SynthesizedAnswer> answers = new ArrayList<>();
        if (candidates != null) {
            for (Candidate c : candidates) {
                OpinionTree trace = buildTree(c);
                answers.add(new SynthesizedAnswer(c.entityId(), trace.value(), trace));
            }
        }
        answers.sort((a, b) -> Double.compare(b.likelihood(), a.likelihood()));
        return answers;
    }

    /** Build one candidate's operator tree: fold each group by its operator, then conjoin the groups. */
    static OpinionTree buildTree(Candidate c) {
        Map<SignalGroup, List<CandidateSignal>> byGroup = new EnumMap<>(SignalGroup.class);
        if (c.signals() != null) {
            for (CandidateSignal s : c.signals()) {
                byGroup.computeIfAbsent(s.group(), g -> new ArrayList<>()).add(s);
            }
        }
        List<OpinionTree> groupTrees = new ArrayList<>();
        for (SignalGroup g : SignalGroup.values()) { // deterministic order
            List<CandidateSignal> sigs = byGroup.get(g);
            if (sigs == null || sigs.isEmpty()) {
                continue;
            }
            List<OpinionTree> leaves = new ArrayList<>(sigs.size());
            for (CandidateSignal s : sigs) {
                leaves.add(OpinionTree.leaf(s.label(), s.opinion(), s.calibrationId(), s.sourceRefs()));
            }
            groupTrees.add(foldGroup(g, leaves));
        }
        if (groupTrees.isEmpty()) {
            return OpinionTree.leaf(c.entityId(), Opinion.vacuous());
        }
        if (groupTrees.size() == 1) {
            return groupTrees.get(0);
        }
        return OpinionTree.conjoin(c.entityId(), groupTrees);
    }

    private static OpinionTree foldGroup(SignalGroup g, List<OpinionTree> leaves) {
        if (leaves.size() == 1) {
            return leaves.get(0);
        }
        String label = g.name().toLowerCase(Locale.ROOT);
        return switch (operatorFor(g)) {
            case CONSENSUS -> OpinionTree.consensus(label, leaves);
            case CONJOIN -> OpinionTree.conjoin(label, leaves);
            default -> OpinionTree.fuseIndependent(label, leaves);
        };
    }
}

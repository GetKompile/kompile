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
package ai.kompile.graph.reasoning.mebn.type.owl;

import ai.kompile.graph.reasoning.model.GraphRelation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result produced by an {@link OwlReasoner} after running OWL RL (or OWL DL)
 * inference over a {@link ai.kompile.graph.reasoning.model.ReasoningGraph}.
 *
 * <p>Introduced in Phase O1 as the return type of {@link OwlReasoner#reason}. The
 * OWL RL forward-chaining implementation that populates this result is added in
 * Phase O2 ({@code OwlRlReasoner}).</p>
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #inferredRelations()} — new graph relations derived by RL entailment rules
 *       (e.g. transitive closure edges, symmetric inverses, domain/range type assertions
 *       expressed as typing relations).</li>
 *   <li>{@link #inferredTypes()} — entity ID → inferred class IRI mappings derived by
 *       {@code cax-sco}, {@code prp-dom}, {@code prp-rng}, and {@code cls-oo} rules.</li>
 *   <li>{@link #inconsistencies()} — constraint violations produced by {@code cax-dw}
 *       (disjoint-class) and other consistency-checking rules.</li>
 * </ul>
 *
 * <p>Instances are immutable value objects.</p>
 */
public final class OwlRlResult {

    private final List<GraphRelation> inferredRelations;
    private final Map<String, String> inferredTypes;
    private final List<OwlInconsistency> inconsistencies;

    private OwlRlResult(
            List<GraphRelation> inferredRelations,
            Map<String, String> inferredTypes,
            List<OwlInconsistency> inconsistencies) {
        this.inferredRelations = Collections.unmodifiableList(
                Objects.requireNonNull(inferredRelations, "inferredRelations"));
        this.inferredTypes     = Collections.unmodifiableMap(
                Objects.requireNonNull(inferredTypes,     "inferredTypes"));
        this.inconsistencies   = Collections.unmodifiableList(
                Objects.requireNonNull(inconsistencies,   "inconsistencies"));
    }

    /**
     * New {@link GraphRelation} instances derived by OWL RL entailment rules
     * (e.g. transitive closure edges via rule {@code prp-trp},
     * symmetric inverses via rule {@code prp-symp}).
     * These are inferred facts — not yet materialised into the source graph.
     */
    public List<GraphRelation> inferredRelations() { return inferredRelations; }

    /**
     * Entity-ID → inferred class IRI mappings produced by
     * {@code cax-sco} (subclass propagation), {@code prp-dom} (domain typing),
     * {@code prp-rng} (range typing), and {@code cls-oo} (equivalence propagation).
     */
    public Map<String, String> inferredTypes() { return inferredTypes; }

    /**
     * Constraint violations detected during inference, most commonly from
     * {@code cax-dw} (an entity is typed as two disjoint classes).
     */
    public List<OwlInconsistency> inconsistencies() { return inconsistencies; }

    /** Whether the reasoning run produced no inconsistency violations. */
    public boolean isConsistent() { return inconsistencies.isEmpty(); }

    // ─── Factory ─────────────────────────────────────────────────────────────────

    /**
     * Build an {@link OwlRlResult}.
     *
     * @param inferredRelations new relations (copied defensively)
     * @param inferredTypes     entity type mappings (copied defensively)
     * @param inconsistencies   violations (copied defensively)
     * @return an immutable result
     */
    public static OwlRlResult of(
            List<GraphRelation> inferredRelations,
            Map<String, String> inferredTypes,
            List<OwlInconsistency> inconsistencies) {
        return new OwlRlResult(
                List.copyOf(inferredRelations),
                Map.copyOf(inferredTypes),
                List.copyOf(inconsistencies));
    }

    /** Convenience factory for an empty (no inferences, no violations) result. */
    public static OwlRlResult empty() {
        return new OwlRlResult(List.of(), Map.of(), List.of());
    }

    @Override
    public String toString() {
        return "OwlRlResult{"
                + "inferredRelations=" + inferredRelations.size()
                + ", inferredTypes="   + inferredTypes.size()
                + ", inconsistencies=" + inconsistencies.size()
                + "}";
    }
}

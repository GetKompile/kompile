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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 *   <li>{@link #inferredTypeCandidates()} — entity ID → all inferred class IRI mappings derived by
 *       {@code cax-sco}, {@code prp-dom}, {@code prp-rng}, and {@code cls-oo} rules.</li>
 *   <li>{@link #inferredTypes()} — legacy entity ID → first inferred class IRI view for older
 *       callers that cannot yet consume multiple memberships per entity.</li>
 *   <li>{@link #inconsistencies()} — constraint violations produced by {@code cax-dw}
 *       (disjoint-class) and other consistency-checking rules.</li>
 * </ul>
 *
 * <p>Instances are immutable value objects.</p>
 */
public final class OwlRlResult {

    private final List<GraphRelation> inferredRelations;
    private final Map<String, String> inferredTypes;
    private final Map<String, List<String>> inferredTypeCandidates;
    private final List<OwlInconsistency> inconsistencies;

    private OwlRlResult(
            List<GraphRelation> inferredRelations,
            Map<String, List<String>> inferredTypeCandidates,
            List<OwlInconsistency> inconsistencies) {
        this.inferredRelations = Collections.unmodifiableList(
                Objects.requireNonNull(inferredRelations, "inferredRelations"));
        this.inferredTypeCandidates = immutableTypeCandidates(
                Objects.requireNonNull(inferredTypeCandidates, "inferredTypeCandidates"));
        this.inferredTypes     = Collections.unmodifiableMap(firstTypeView(this.inferredTypeCandidates));
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
     * Legacy entity-ID → first inferred class IRI mappings produced by
     * {@code cax-sco} (subclass propagation), {@code prp-dom} (domain typing),
     * {@code prp-rng} (range typing), and {@code cls-oo} (equivalence propagation).
     *
     * <p>Use {@link #inferredTypeCandidates()} when all inferred memberships matter.</p>
     */
    public Map<String, String> inferredTypes() { return inferredTypes; }

    /**
     * Entity-ID → all inferred class IRI memberships produced by OWL reasoning.
     *
     * <p>Insertion order is preserved per entity so the first item matches the legacy
     * {@link #inferredTypes()} view.</p>
     */
    public Map<String, List<String>> inferredTypeCandidates() { return inferredTypeCandidates; }

    /** Total inferred type memberships across all entities. */
    public int inferredTypeCount() {
        return inferredTypeCandidates.values().stream().mapToInt(List::size).sum();
    }

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
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        inferredTypes.forEach((entityId, classIri) -> {
            if (entityId != null && classIri != null) {
                candidates.put(entityId, List.of(classIri));
            }
        });
        return ofMultiTypes(inferredRelations, candidates, inconsistencies);
    }

    /**
     * Build an {@link OwlRlResult} with multiple inferred type memberships per entity.
     *
     * @param inferredRelations new relations (copied defensively)
     * @param inferredTypes     entity type memberships (copied defensively)
     * @param inconsistencies   violations (copied defensively)
     * @return an immutable result
     */
    public static OwlRlResult ofMultiTypes(
            List<GraphRelation> inferredRelations,
            Map<String, ? extends Collection<String>> inferredTypes,
            List<OwlInconsistency> inconsistencies) {
        return new OwlRlResult(
                List.copyOf(inferredRelations),
                copyTypeCandidates(inferredTypes),
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
                + ", inferredTypes="   + inferredTypeCount()
                + ", inconsistencies=" + inconsistencies.size()
                + "}";
    }

    private static Map<String, List<String>> copyTypeCandidates(
            Map<String, ? extends Collection<String>> inferredTypes) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        Objects.requireNonNull(inferredTypes, "inferredTypes").forEach((entityId, classIris) -> {
            if (entityId == null || classIris == null) return;
            List<String> clean = new ArrayList<>();
            for (String classIri : classIris) {
                if (classIri != null && !clean.contains(classIri)) {
                    clean.add(classIri);
                }
            }
            if (!clean.isEmpty()) {
                copy.put(entityId, List.copyOf(clean));
            }
        });
        return copy;
    }

    private static Map<String, List<String>> immutableTypeCandidates(Map<String, List<String>> inferredTypes) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        inferredTypes.forEach((entityId, classIris) -> copy.put(entityId, List.copyOf(classIris)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> firstTypeView(Map<String, List<String>> inferredTypes) {
        Map<String, String> firstTypes = new LinkedHashMap<>();
        inferredTypes.forEach((entityId, classIris) -> {
            if (!classIris.isEmpty()) {
                firstTypes.put(entityId, classIris.get(0));
            }
        });
        return firstTypes;
    }
}

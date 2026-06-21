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
package ai.kompile.graph.reasoning.mebn.type;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.Objects;

/**
 * A structural constraint on a {@link TypeNode}, capturing the type-level rules that instances
 * and their relations must satisfy.
 *
 * <p>{@code TypeConstraint} is a sealed interface with three concrete implementations:</p>
 * <ul>
 *   <li>{@link RelationConstraint} — declares the allowed domain and range types for a relation
 *       label (equivalent to {@code OntologySchema.RelationshipTypeDefinition}).</li>
 *   <li>{@link CardinalityConstraint} — declares a cardinality restriction on a relation label
 *       (ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY).</li>
 *   <li>{@link AttributeRequiredConstraint} — declares that a named attribute must be present
 *       on every entity of the owning type (redundant with {@link AttributeDefinition#isRequired()}
 *       but usable independently when a full schema is not available).</li>
 * </ul>
 *
 * <p>Constraints are stored on {@link TypeNode#getConstraints()} and feed two consumers:
 * (a) {@code OntologicalConstraintBuilder} can be automatically populated from a
 * {@link TypeHierarchy}'s constraints (Phase 1b), and (b) a conformance checker can evaluate
 * them against a {@link ReasoningGraph} at validation time (Phase 2).</p>
 *
 * <p>This is an infra-free, pure-Java sealed interface — no Spring, no JPA.</p>
 */
public sealed interface TypeConstraint
        permits TypeConstraint.RelationConstraint,
                TypeConstraint.CardinalityConstraint,
                TypeConstraint.AttributeRequiredConstraint {

    /**
     * Evaluate this constraint against a {@link ReasoningGraph}.
     *
     * <p>Returns {@code true} when the constraint is satisfied by the graph as a whole
     * ({@link RelationConstraint} and {@link CardinalityConstraint}) or when it cannot be
     * evaluated in the absence of specific entity context ({@link AttributeRequiredConstraint}
     * — that check is entity-scoped and always returns {@code true} here; use
     * {@link AttributeRequiredConstraint#isSatisfiedBy(GraphEntity)} for per-entity checks).</p>
     *
     * @param graph the graph to validate
     * @return {@code true} if the constraint is satisfied (or not applicable at graph level)
     */
    boolean isSatisfiedBy(ReasoningGraph graph);

    // ─── Cardinality enum ────────────────────────────────────────────────────────

    /**
     * Multiplicity on a directed relation: how many target entities a single source can relate
     * to (the "many" end) and how many source entities a single target can be related from.
     */
    enum Cardinality {
        /** One source → one target, one target ← one source. */
        ONE_TO_ONE,
        /** One source → many targets. */
        ONE_TO_MANY,
        /** Many sources → one target. */
        MANY_TO_ONE,
        /** Many sources → many targets (no restriction). */
        MANY_TO_MANY
    }

    // ─── RelationConstraint ──────────────────────────────────────────────────────

    /**
     * Declares that a relation with the given label must connect a source entity of
     * {@link #domainType()} to a target entity of {@link #rangeType()}.
     *
     * <p>Satisfiability check: every relation in the graph whose {@code type()} equals
     * {@link #relationLabel()} must have its source entity's {@link GraphEntity#type()}
     * matching {@link #domainType()} and its target entity's {@link GraphEntity#type()}
     * matching {@link #rangeType()} (case-insensitive on both ends).</p>
     */
    record RelationConstraint(
            String relationLabel,
            String domainType,
            String rangeType
    ) implements TypeConstraint {

        public RelationConstraint {
            Objects.requireNonNull(relationLabel, "relationLabel");
            Objects.requireNonNull(domainType,    "domainType");
            Objects.requireNonNull(rangeType,     "rangeType");
        }

        /**
         * {@inheritDoc}
         *
         * <p>Scans every relation in {@code graph} whose type equals {@link #relationLabel()}
         * and checks that its source entity has type {@link #domainType()} and its target entity
         * has type {@link #rangeType()} (both case-insensitive). Returns {@code false} as soon
         * as any violating relation is found.</p>
         */
        @Override
        public boolean isSatisfiedBy(ReasoningGraph graph) {
            for (GraphRelation rel : graph.relations()) {
                if (!relationLabel.equalsIgnoreCase(rel.type())) continue;
                // Check domain (source entity type)
                boolean domainOk = graph.entity(rel.sourceId())
                        .map(e -> domainType.equalsIgnoreCase(e.type()))
                        .orElse(true); // absent entity → can't check, treat as ok
                // Check range (target entity type)
                boolean rangeOk = graph.entity(rel.targetId())
                        .map(e -> rangeType.equalsIgnoreCase(e.type()))
                        .orElse(true);
                if (!domainOk || !rangeOk) return false;
            }
            return true;
        }
    }

    // ─── CardinalityConstraint ───────────────────────────────────────────────────

    /**
     * Declares a cardinality restriction on a named relation label.
     *
     * <p>Satisfiability check (graph-level): enforces the cardinality on outgoing/incoming
     * counts. {@link Cardinality#ONE_TO_ONE} and {@link Cardinality#ONE_TO_MANY} require that
     * no source entity has more than one outgoing relation of this label when {@code ONE_TO_ONE}
     * or no target has more than one incoming relation when {@code MANY_TO_ONE}.</p>
     */
    record CardinalityConstraint(
            String relationLabel,
            Cardinality cardinality
    ) implements TypeConstraint {

        public CardinalityConstraint {
            Objects.requireNonNull(relationLabel, "relationLabel");
            Objects.requireNonNull(cardinality,   "cardinality");
        }

        /**
         * {@inheritDoc}
         *
         * <p>For {@link Cardinality#MANY_TO_MANY} always returns {@code true} (no structural
         * restriction). For the other three, checks whether the source-fan-out (ONE_TO_ONE,
         * ONE_TO_MANY) or target-fan-in (ONE_TO_ONE, MANY_TO_ONE) exceeds one for any entity.</p>
         */
        @Override
        public boolean isSatisfiedBy(ReasoningGraph graph) {
            if (cardinality == Cardinality.MANY_TO_MANY) return true;
            boolean checkFanOut = (cardinality == Cardinality.ONE_TO_ONE || cardinality == Cardinality.ONE_TO_MANY);
            boolean checkFanIn  = (cardinality == Cardinality.ONE_TO_ONE || cardinality == Cardinality.MANY_TO_ONE);
            for (GraphEntity entity : graph.entities()) {
                if (checkFanOut) {
                    long outCount = graph.outgoing(entity.id()).stream()
                            .filter(r -> relationLabel.equalsIgnoreCase(r.type()))
                            .count();
                    if (outCount > 1) return false;
                }
                if (checkFanIn) {
                    long inCount = graph.incoming(entity.id()).stream()
                            .filter(r -> relationLabel.equalsIgnoreCase(r.type()))
                            .count();
                    if (inCount > 1) return false;
                }
            }
            return true;
        }
    }

    // ─── AttributeRequiredConstraint ─────────────────────────────────────────────

    /**
     * Declares that a named attribute must be present on every entity of the owning type.
     *
     * <p>Graph-level satisfiability cannot be evaluated without knowing which entities belong
     * to the constrained type; {@link #isSatisfiedBy(ReasoningGraph)} always returns {@code true}.
     * Use {@link #isSatisfiedBy(GraphEntity)} for per-entity checks.</p>
     */
    record AttributeRequiredConstraint(String attributeName) implements TypeConstraint {

        public AttributeRequiredConstraint {
            Objects.requireNonNull(attributeName, "attributeName");
        }

        /**
         * Always {@code true} at the graph level — this constraint is only meaningful per entity.
         * Use {@link #isSatisfiedBy(GraphEntity)} instead.
         */
        @Override
        public boolean isSatisfiedBy(ReasoningGraph graph) {
            return true;
        }

        /**
         * Check whether the required attribute is present on {@code entity}.
         *
         * @param entity the entity to validate
         * @return {@code true} if {@code entity.attributes()} contains {@link #attributeName()}
         *         with a non-null value
         */
        public boolean isSatisfiedBy(GraphEntity entity) {
            Object val = entity.attributes().get(attributeName);
            return val != null;
        }
    }
}

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

import java.util.Objects;

/**
 * An OWL 2 class expression (restriction) attached to an {@link OwlClass}, restricting the
 * values of a property on instances of that class.
 *
 * <p>This is a sealed interface with six permitted record variants, covering the
 * OWL 2 RL restriction constructs that can be handled by the infra-free forward-chaining
 * reasoner:</p>
 * <ul>
 *   <li>{@link SomeValuesFrom}  — {@code owl:someValuesFrom} (∃P.C)</li>
 *   <li>{@link AllValuesFrom}   — {@code owl:allValuesFrom}  (∀P.C)</li>
 *   <li>{@link HasValue}        — {@code owl:hasValue}       (P value {individual})</li>
 *   <li>{@link MinCardinality}  — {@code owl:minCardinality} / {@code owl:minQualifiedCardinality}</li>
 *   <li>{@link MaxCardinality}  — {@code owl:maxCardinality} / {@code owl:maxQualifiedCardinality}</li>
 *   <li>{@link ExactCardinality}— {@code owl:exactCardinality}</li>
 * </ul>
 *
 * <p>All variants are immutable value objects (records).</p>
 *
 * <p>Relationship to {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint}:
 * {@link MaxCardinality}(n=1) on a property generalizes
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint.CardinalityConstraint}
 * at the OWL layer. {@link SomeValuesFrom} and {@link AllValuesFrom} have no Phase-1 analogue
 * and represent new expressive power. {@link HasValue} maps to
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint.AttributeRequiredConstraint}
 * only when the property is a data property with a specific literal; for object properties it
 * is new.</p>
 */
public sealed interface OwlRestriction
        permits OwlRestriction.SomeValuesFrom,
                OwlRestriction.AllValuesFrom,
                OwlRestriction.HasValue,
                OwlRestriction.MinCardinality,
                OwlRestriction.MaxCardinality,
                OwlRestriction.ExactCardinality {

    /**
     * The IRI of the property this restriction is placed on
     * (corresponds to {@code owl:onProperty} in Turtle serialization).
     *
     * @return the property IRI (never {@code null})
     */
    String onPropertyIri();

    // ─── Variant: SomeValuesFrom ─────────────────────────────────────────────────

    /**
     * {@code owl:someValuesFrom C} — existential restriction: every instance of the owning
     * class must have at least one value of property {@link #onPropertyIri()} that belongs to
     * the filler class {@link #fillerClassIri()}.
     *
     * <p>OWL RL rule {@code cls-svf1}: if {@code x: C}, {@code P(x, y)}, {@code y: D}
     * and {@code C ⊑ ∃P.D} then the filler membership can be inferred.</p>
     */
    record SomeValuesFrom(
            String onPropertyIri,
            String fillerClassIri
    ) implements OwlRestriction {
        public SomeValuesFrom {
            Objects.requireNonNull(onPropertyIri,  "onPropertyIri");
            Objects.requireNonNull(fillerClassIri, "fillerClassIri");
        }
    }

    // ─── Variant: AllValuesFrom ──────────────────────────────────────────────────

    /**
     * {@code owl:allValuesFrom C} — universal restriction: all values of property
     * {@link #onPropertyIri()} on instances of the owning class must belong to the filler
     * class {@link #fillerClassIri()}.
     *
     * <p>OWL RL rule {@code cls-avf}: if {@code a: owningClass}, {@code P(a, b)}
     * then {@code b: fillerClass} can be inferred.</p>
     */
    record AllValuesFrom(
            String onPropertyIri,
            String fillerClassIri
    ) implements OwlRestriction {
        public AllValuesFrom {
            Objects.requireNonNull(onPropertyIri,  "onPropertyIri");
            Objects.requireNonNull(fillerClassIri, "fillerClassIri");
        }
    }

    // ─── Variant: HasValue ───────────────────────────────────────────────────────

    /**
     * {@code owl:hasValue v} — hasValue restriction: every instance of the owning class must
     * have at least one value of property {@link #onPropertyIri()} equal to the individual
     * {@link #individualId()}.
     *
     * <p>OWL RL rules {@code cls-hv1} / {@code cls-hv2}.</p>
     */
    record HasValue(
            String onPropertyIri,
            String individualId
    ) implements OwlRestriction {
        public HasValue {
            Objects.requireNonNull(onPropertyIri, "onPropertyIri");
            Objects.requireNonNull(individualId,  "individualId");
        }
    }

    // ─── Variant: MinCardinality ─────────────────────────────────────────────────

    /**
     * {@code owl:minCardinality n} (or {@code owl:minQualifiedCardinality n on C}) —
     * every instance of the owning class must have at least {@link #n()} values of
     * property {@link #onPropertyIri()}.
     *
     * <p>When {@link #qualifiedOnClassIri()} is non-{@code null} this is a qualified restriction
     * and only values that are instances of that class are counted.</p>
     *
     * @param n                  the minimum cardinality (must be ≥ 0)
     * @param qualifiedOnClassIri the qualifier class IRI, or {@code null} for unqualified
     */
    record MinCardinality(
            String onPropertyIri,
            int n,
            String qualifiedOnClassIri
    ) implements OwlRestriction {
        public MinCardinality {
            Objects.requireNonNull(onPropertyIri, "onPropertyIri");
            if (n < 0) throw new IllegalArgumentException("MinCardinality n must be >= 0, got: " + n);
        }
    }

    // ─── Variant: MaxCardinality ─────────────────────────────────────────────────

    /**
     * {@code owl:maxCardinality n} (or {@code owl:maxQualifiedCardinality n on C}) —
     * every instance of the owning class must have at most {@link #n()} values of
     * property {@link #onPropertyIri()}.
     *
     * <p>When {@link #n()} is 1 this corresponds to a functional property restriction and
     * is equivalent to a
     * {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint.CardinalityConstraint}
     * with {@code ONE_TO_ONE} or {@code MANY_TO_ONE} cardinality at the Phase-1 layer.</p>
     *
     * @param n                  the maximum cardinality (must be ≥ 0)
     * @param qualifiedOnClassIri the qualifier class IRI, or {@code null} for unqualified
     */
    record MaxCardinality(
            String onPropertyIri,
            int n,
            String qualifiedOnClassIri
    ) implements OwlRestriction {
        public MaxCardinality {
            Objects.requireNonNull(onPropertyIri, "onPropertyIri");
            if (n < 0) throw new IllegalArgumentException("MaxCardinality n must be >= 0, got: " + n);
        }
    }

    // ─── Variant: ExactCardinality ───────────────────────────────────────────────

    /**
     * {@code owl:exactCardinality n} (or {@code owl:exactQualifiedCardinality n on C}) —
     * every instance of the owning class must have exactly {@link #n()} values of
     * property {@link #onPropertyIri()}.
     *
     * <p>Semantically equivalent to the conjunction of
     * {@link MinCardinality}(n) ∧ {@link MaxCardinality}(n) on the same property.</p>
     *
     * @param n                  the exact cardinality (must be ≥ 0)
     * @param qualifiedOnClassIri the qualifier class IRI, or {@code null} for unqualified
     */
    record ExactCardinality(
            String onPropertyIri,
            int n,
            String qualifiedOnClassIri
    ) implements OwlRestriction {
        public ExactCardinality {
            Objects.requireNonNull(onPropertyIri, "onPropertyIri");
            if (n < 0) throw new IllegalArgumentException("ExactCardinality n must be >= 0, got: " + n);
        }
    }
}

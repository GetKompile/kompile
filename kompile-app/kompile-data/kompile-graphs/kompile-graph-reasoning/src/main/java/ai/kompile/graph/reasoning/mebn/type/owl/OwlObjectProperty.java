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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An OWL 2 object property declaration — a first-class IRI-identified relation between
 * OWL individuals (ABox {@link ai.kompile.graph.reasoning.model.GraphEntity} instances).
 *
 * <h2>Relationship to Phase-1</h2>
 * <p>An {@code OwlObjectProperty} corresponds to a
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint.RelationConstraint}
 * (domain/range) plus one or more
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint.CardinalityConstraint}
 * records on the domain type's {@link ai.kompile.graph.reasoning.mebn.type.TypeNode}.
 * Whereas {@code TypeConstraint} records are attached to the domain type, an
 * {@code OwlObjectProperty} is a first-class entity that multiple classes can reference
 * by IRI. The OWL layer promotes properties to first-class objects; the Phase-1 bridge
 * (see {@link OwlOntology#toTypeRegistry()}) compiles them down to
 * {@code RelationConstraint}s and {@code CardinalityConstraint}s.</p>
 *
 * <h2>Property characteristics</h2>
 * <ul>
 *   <li>{@code functional}         — at most one value per individual (fan-out ≤ 1)</li>
 *   <li>{@code inverseFunctional}  — at most one individual with a given value (fan-in ≤ 1)</li>
 *   <li>{@code transitive}         — P(a,b) ∧ P(b,c) → P(a,c)</li>
 *   <li>{@code symmetric}          — P(a,b) → P(b,a)</li>
 *   <li>{@code reflexive}          — P(a,a) for all a in domain</li>
 *   <li>{@code asymmetric}         — P(a,b) → ¬P(b,a)</li>
 *   <li>{@code irreflexive}        — ¬P(a,a)</li>
 * </ul>
 *
 * <p>Instances are immutable once built. Use the {@link Builder} to construct them.</p>
 */
public final class OwlObjectProperty {

    private final String propertyIri;
    private final String domainClassIri;
    private final String rangeClassIri;
    private final boolean functional;
    private final boolean inverseFunctional;
    private final boolean transitive;
    private final boolean symmetric;
    private final boolean reflexive;
    private final boolean asymmetric;
    private final boolean irreflexive;
    private final String inverseOfIri;
    private final Set<String> subPropertyOfIris;
    private final Set<String> equivalentPropertyIris;

    private OwlObjectProperty(Builder b) {
        this.propertyIri            = Objects.requireNonNull(b.propertyIri, "propertyIri");
        this.domainClassIri         = b.domainClassIri;
        this.rangeClassIri          = b.rangeClassIri;
        this.functional             = b.functional;
        this.inverseFunctional      = b.inverseFunctional;
        this.transitive             = b.transitive;
        this.symmetric              = b.symmetric;
        this.reflexive              = b.reflexive;
        this.asymmetric             = b.asymmetric;
        this.irreflexive            = b.irreflexive;
        this.inverseOfIri           = b.inverseOfIri;
        this.subPropertyOfIris      = Collections.unmodifiableSet(new LinkedHashSet<>(b.subPropertyOfIris));
        this.equivalentPropertyIris = Collections.unmodifiableSet(new LinkedHashSet<>(b.equivalentPropertyIris));
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /** The absolute IRI identifying this property (e.g. {@code https://kompile.ai/kg/prop/owns}). */
    public String propertyIri() { return propertyIri; }

    /**
     * The IRI of the domain class ({@code rdfs:domain}), or {@code null} when no domain
     * restriction is declared.
     */
    public String domainClassIri() { return domainClassIri; }

    /**
     * The IRI of the range class ({@code rdfs:range}), or {@code null} when no range
     * restriction is declared.
     */
    public String rangeClassIri() { return rangeClassIri; }

    /** Whether this property is functional (fan-out ≤ 1 per individual). */
    public boolean isFunctional() { return functional; }

    /** Whether this property is inverse-functional (fan-in ≤ 1 per value). */
    public boolean isInverseFunctional() { return inverseFunctional; }

    /** Whether this property is transitive (P(a,b) ∧ P(b,c) → P(a,c)). */
    public boolean isTransitive() { return transitive; }

    /** Whether this property is symmetric (P(a,b) → P(b,a)). */
    public boolean isSymmetric() { return symmetric; }

    /** Whether this property is reflexive (P(a,a) for all a in the domain). */
    public boolean isReflexive() { return reflexive; }

    /** Whether this property is asymmetric (P(a,b) → ¬P(b,a)). */
    public boolean isAsymmetric() { return asymmetric; }

    /** Whether this property is irreflexive (¬P(a,a)). */
    public boolean isIrreflexive() { return irreflexive; }

    /**
     * The IRI of the inverse property ({@code owl:inverseOf}), or {@code null} when not declared.
     * OWL RL rules {@code prp-inv1} / {@code prp-inv2} exploit this.
     */
    public String inverseOfIri() { return inverseOfIri; }

    /** IRIs of declared super-properties ({@code rdfs:subPropertyOf}). */
    public Set<String> subPropertyOfIris() { return subPropertyOfIris; }

    /** IRIs of declared equivalent properties ({@code owl:equivalentObjectProperties}). */
    public Set<String> equivalentPropertyIris() { return equivalentPropertyIris; }

    /**
     * Derive the local name from the property IRI (fragment after {@code #}, or last
     * path segment after {@code /}).  Used as the relation label in the Phase-1 registry.
     *
     * @return the local name (never {@code null})
     */
    public String localName() {
        int hash = propertyIri.lastIndexOf('#');
        if (hash >= 0 && hash < propertyIri.length() - 1) return propertyIri.substring(hash + 1);
        int slash = propertyIri.lastIndexOf('/');
        if (slash >= 0 && slash < propertyIri.length() - 1) return propertyIri.substring(slash + 1);
        return propertyIri;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /** Start a builder for an object property with the given IRI. */
    public static Builder of(String propertyIri) {
        return new Builder(propertyIri);
    }

    public static final class Builder {
        private final String propertyIri;
        private String domainClassIri;
        private String rangeClassIri;
        private boolean functional;
        private boolean inverseFunctional;
        private boolean transitive;
        private boolean symmetric;
        private boolean reflexive;
        private boolean asymmetric;
        private boolean irreflexive;
        private String inverseOfIri;
        private final Set<String> subPropertyOfIris      = new LinkedHashSet<>();
        private final Set<String> equivalentPropertyIris = new LinkedHashSet<>();

        private Builder(String propertyIri) {
            this.propertyIri = Objects.requireNonNull(propertyIri, "propertyIri");
        }

        public Builder domain(String domainClassIri) {
            this.domainClassIri = domainClassIri;
            return this;
        }

        public Builder range(String rangeClassIri) {
            this.rangeClassIri = rangeClassIri;
            return this;
        }

        public Builder functional(boolean v) { this.functional = v; return this; }
        public Builder inverseFunctional(boolean v) { this.inverseFunctional = v; return this; }
        public Builder transitive(boolean v) { this.transitive = v; return this; }
        public Builder symmetric(boolean v) { this.symmetric = v; return this; }
        public Builder reflexive(boolean v) { this.reflexive = v; return this; }
        public Builder asymmetric(boolean v) { this.asymmetric = v; return this; }
        public Builder irreflexive(boolean v) { this.irreflexive = v; return this; }

        public Builder inverseOf(String iri) { this.inverseOfIri = iri; return this; }

        public Builder subPropertyOf(String iri) {
            subPropertyOfIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        public Builder equivalentProperty(String iri) {
            equivalentPropertyIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        public OwlObjectProperty build() {
            return new OwlObjectProperty(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwlObjectProperty that)) return false;
        return propertyIri.equals(that.propertyIri);
    }

    @Override
    public int hashCode() {
        return propertyIri.hashCode();
    }

    @Override
    public String toString() {
        return "OwlObjectProperty{" + propertyIri
                + (functional ? ",functional" : "")
                + (transitive ? ",transitive" : "")
                + (symmetric  ? ",symmetric"  : "")
                + "}";
    }
}

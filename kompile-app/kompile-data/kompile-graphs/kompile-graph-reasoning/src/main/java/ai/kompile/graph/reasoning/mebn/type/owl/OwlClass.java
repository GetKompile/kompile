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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An OWL 2 class declaration, enriching the Phase-1 {@link ai.kompile.graph.reasoning.mebn.type.TypeNode}
 * with OWL-specific cross-class axioms.
 *
 * <h2>Relationship to Phase-1</h2>
 * <ul>
 *   <li>{@link #subClassOfIris()} corresponds to the single
 *       {@link ai.kompile.graph.reasoning.mebn.type.TypeNode#getParent()} link but uses IRI
 *       strings, allowing references to external classes not in the local registry.</li>
 *   <li>{@link #equivalentClassIris()} has no Phase-1 analogue — it is new OWL semantics.</li>
 *   <li>{@link #disjointWithIris()} makes class-level mutual exclusion explicit; the OWL RL
 *       reasoner (Phase O2) uses it for consistency checking.</li>
 *   <li>{@link #restrictions()} generalizes
 *       {@link ai.kompile.graph.reasoning.mebn.type.TypeConstraint} to OWL class expressions.</li>
 * </ul>
 *
 * <h2>Local name convention</h2>
 * <p>The {@link #localName()} is the fragment or last-path-segment of the IRI, used as the
 * type name when mapping into the Phase-1 {@link ai.kompile.graph.reasoning.mebn.type.TypeRegistry}.
 * For example {@code https://kompile.ai/kg/class/Person} → local name {@code Person}.</p>
 *
 * <p>Instances are immutable once built. Use the {@link Builder} to construct them.</p>
 */
public final class OwlClass {

    private final String classIri;
    private final Set<String> subClassOfIris;
    private final Set<String> equivalentClassIris;
    private final Set<String> disjointWithIris;
    private final List<OwlRestriction> restrictions;

    private OwlClass(Builder b) {
        this.classIri          = Objects.requireNonNull(b.classIri, "classIri");
        this.subClassOfIris     = Collections.unmodifiableSet(new LinkedHashSet<>(b.subClassOfIris));
        this.equivalentClassIris = Collections.unmodifiableSet(new LinkedHashSet<>(b.equivalentClassIris));
        this.disjointWithIris   = Collections.unmodifiableSet(new LinkedHashSet<>(b.disjointWithIris));
        this.restrictions       = Collections.unmodifiableList(new ArrayList<>(b.restrictions));
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /**
     * The absolute IRI identifying this OWL class
     * (e.g. {@code https://kompile.ai/kg/class/Person}).
     */
    public String classIri() { return classIri; }

    /**
     * The IRIs of all declared superclasses ({@code rdfs:subClassOf}).
     * May be empty when this is a root class. Multiple entries correspond to
     * OWL multiple-superclass declarations; the Phase-1 bridge uses the first
     * entry as the single parent (single-inheritance constraint of
     * {@link ai.kompile.graph.reasoning.mebn.type.TypeRegistry#subtype}).
     */
    public Set<String> subClassOfIris() { return subClassOfIris; }

    /**
     * The IRIs of all {@code owl:equivalentClass} partners. Has no Phase-1 analogue;
     * used by the OWL RL reasoner for bidirectional type propagation.
     */
    public Set<String> equivalentClassIris() { return equivalentClassIris; }

    /**
     * The IRIs of all {@code owl:disjointWith} partners. Instances typed as both this class
     * and any disjoint class trigger an inconsistency flag in the OWL RL reasoner.
     */
    public Set<String> disjointWithIris() { return disjointWithIris; }

    /**
     * The local restrictions ({@code owl:Restriction} blank nodes in Turtle) placed on this
     * class, covering {@code someValuesFrom}, {@code allValuesFrom}, {@code hasValue}, and
     * cardinality restrictions.
     */
    public List<OwlRestriction> restrictions() { return restrictions; }

    /**
     * Derive the local name from the IRI: the fragment after {@code #} if present,
     * otherwise the last path segment after {@code /}.
     *
     * <p>This is the name used as the type name in the Phase-1 registry.</p>
     *
     * @return the local name (never {@code null}; falls back to the full IRI if no separator found)
     */
    public String localName() {
        int hash = classIri.lastIndexOf('#');
        if (hash >= 0 && hash < classIri.length() - 1) {
            return classIri.substring(hash + 1);
        }
        int slash = classIri.lastIndexOf('/');
        if (slash >= 0 && slash < classIri.length() - 1) {
            return classIri.substring(slash + 1);
        }
        return classIri;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /**
     * Start a builder for an OWL class with the given IRI.
     *
     * @param classIri the absolute IRI identifying this class (never {@code null})
     * @return a new builder
     */
    public static Builder of(String classIri) {
        return new Builder(classIri);
    }

    public static final class Builder {
        private final String classIri;
        private final Set<String> subClassOfIris     = new LinkedHashSet<>();
        private final Set<String> equivalentClassIris = new LinkedHashSet<>();
        private final Set<String> disjointWithIris   = new LinkedHashSet<>();
        private final List<OwlRestriction> restrictions = new ArrayList<>();

        private Builder(String classIri) {
            this.classIri = Objects.requireNonNull(classIri, "classIri");
        }

        /**
         * Declare a superclass for this class ({@code rdfs:subClassOf}).
         * May be called multiple times to declare multiple superclasses.
         */
        public Builder subClassOf(String superClassIri) {
            subClassOfIris.add(Objects.requireNonNull(superClassIri, "superClassIri"));
            return this;
        }

        /** Declare an equivalent class ({@code owl:equivalentClass}). */
        public Builder equivalentClass(String iri) {
            equivalentClassIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        /** Declare a disjoint class ({@code owl:disjointWith}). */
        public Builder disjointWith(String iri) {
            disjointWithIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        /** Add a local restriction on this class. */
        public Builder restriction(OwlRestriction restriction) {
            restrictions.add(Objects.requireNonNull(restriction, "restriction"));
            return this;
        }

        /** Build the immutable {@link OwlClass}. */
        public OwlClass build() {
            return new OwlClass(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwlClass that)) return false;
        return classIri.equals(that.classIri);
    }

    @Override
    public int hashCode() {
        return classIri.hashCode();
    }

    @Override
    public String toString() {
        return "OwlClass{" + classIri
                + ", subClassOf=" + subClassOfIris
                + ", restrictions=" + restrictions.size()
                + "}";
    }
}

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

import ai.kompile.graph.reasoning.mebn.type.AttributeDefinition;
import ai.kompile.graph.reasoning.mebn.type.TypeAttributeSchema;
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The TBox (terminological box) of an OWL 2 ontology in its infra-free, kompile representation.
 *
 * <p>{@code OwlOntology} holds all class and property declarations that constitute the schema
 * (TBox). The ABox (individual/fact) assertions live in the
 * {@link ai.kompile.graph.reasoning.model.ReasoningGraph}. This ABox/TBox split matches
 * OWL semantics exactly.</p>
 *
 * <h2>Contents</h2>
 * <ul>
 *   <li>{@link #classes()} — IRI → {@link OwlClass} declarations</li>
 *   <li>{@link #objectProperties()} — IRI → {@link OwlObjectProperty} declarations</li>
 *   <li>{@link #dataProperties()} — IRI → {@link OwlDataProperty} declarations</li>
 *   <li>{@link #sameAs()} — individual IRI → canonical IRI (owl:sameAs assertions)</li>
 * </ul>
 *
 * <h2>Bridge into Phase-1</h2>
 * <p>{@link #toTypeRegistry()} translates the TBox into a
 * {@link TypeRegistry} that can be consumed by the existing MEBN/PSL engines via
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeHierarchy#fromGraph(
 * ai.kompile.graph.reasoning.model.ReasoningGraph, TypeRegistry)}.
 * The translation applies these rules:</p>
 * <ol>
 *   <li>Each {@link OwlClass} is {@link TypeRegistry#declare declared}.</li>
 *   <li>The first entry of {@link OwlClass#subClassOfIris()} (if any) is wired as an isA link
 *       via {@link TypeRegistry#subtype(String, String)} — the single-parent constraint of the
 *       Phase-1 hierarchy.</li>
 *   <li>Each {@link OwlDataProperty} whose domain class is known becomes an
 *       {@link AttributeDefinition} in that class's
 *       {@link TypeAttributeSchema}.</li>
 *   <li>Each {@link OwlObjectProperty} with domain and range becomes a
 *       {@link TypeConstraint.RelationConstraint} attached to the domain type.</li>
 *   <li>A functional {@link OwlObjectProperty} additionally produces a
 *       {@link TypeConstraint.CardinalityConstraint} with cardinality
 *       {@link TypeConstraint.Cardinality#MANY_TO_ONE}.</li>
 *   <li>{@link OwlRestriction.MaxCardinality}(n=1) on a class produces a
 *       {@link TypeConstraint.CardinalityConstraint} with cardinality
 *       {@link TypeConstraint.Cardinality#MANY_TO_ONE} on that class.</li>
 * </ol>
 *
 * <p>Instances are immutable once built. Use the {@link Builder} to construct them.</p>
 */
public final class OwlOntology {

    private final String ontologyIri;
    private final Map<String, OwlClass>          classes;
    private final Map<String, OwlObjectProperty> objectProperties;
    private final Map<String, OwlDataProperty>   dataProperties;
    private final Map<String, String>            sameAs;

    private OwlOntology(Builder b) {
        this.ontologyIri      = b.ontologyIri;
        this.classes          = Collections.unmodifiableMap(new LinkedHashMap<>(b.classes));
        this.objectProperties = Collections.unmodifiableMap(new LinkedHashMap<>(b.objectProperties));
        this.dataProperties   = Collections.unmodifiableMap(new LinkedHashMap<>(b.dataProperties));
        this.sameAs           = Collections.unmodifiableMap(new LinkedHashMap<>(b.sameAs));
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /**
     * The IRI identifying this ontology (e.g. {@code https://kompile.ai/kg/ontology/MyOntology}),
     * or {@code null} when the ontology is anonymous.
     */
    public String ontologyIri() { return ontologyIri; }

    /** All declared OWL classes, keyed by class IRI (unmodifiable). */
    public Map<String, OwlClass> classes() { return classes; }

    /** All declared object properties, keyed by property IRI (unmodifiable). */
    public Map<String, OwlObjectProperty> objectProperties() { return objectProperties; }

    /** All declared data properties, keyed by property IRI (unmodifiable). */
    public Map<String, OwlDataProperty> dataProperties() { return dataProperties; }

    /**
     * Individual {@code owl:sameAs} assertions: individual IRI → canonical IRI.
     * The ABox reasoner uses these to merge coreferent entities.
     */
    public Map<String, String> sameAs() { return sameAs; }

    // ─── Bridge into Phase-1 ─────────────────────────────────────────────────────

    /**
     * Translate this OWL TBox into a Phase-1 {@link TypeRegistry} that the existing MEBN/PSL
     * engines can consume.
     *
     * <p>The resulting registry can be passed directly to
     * {@link ai.kompile.graph.reasoning.mebn.type.TypeHierarchy#fromGraph(
     * ai.kompile.graph.reasoning.model.ReasoningGraph, TypeRegistry)} to obtain a fully
     * typed, hierarchy-aware view of any {@link ai.kompile.graph.reasoning.model.ReasoningGraph}
     * whose individual types correspond to the local names of the OWL classes declared here.</p>
     *
     * <h3>Translation rules</h3>
     * <ol>
     *   <li><strong>Classes → declared types:</strong> each {@link OwlClass} is declared in
     *       the registry using its {@link OwlClass#localName()} as the type name.</li>
     *   <li><strong>subClassOf → isA link:</strong> for each {@link OwlClass}, the first
     *       entry of {@link OwlClass#subClassOfIris()} is resolved to its local name and
     *       registered as the single parent via {@link TypeRegistry#subtype(String, String)}.
     *       Additional superclass IRIs (OWL multiple inheritance) are logged as ignored because
     *       the Phase-1 registry supports single-parent isA only.</li>
     *   <li><strong>Data properties → AttributeDefinition:</strong> each
     *       {@link OwlDataProperty} whose {@link OwlDataProperty#domainClassIri()} is a
     *       known class IRI becomes an {@link AttributeDefinition} in that class's
     *       {@link TypeAttributeSchema}, with the value type derived from
     *       {@link OwlDataProperty#toAttributeValueType()} and required=functional.</li>
     *   <li><strong>Object properties → RelationConstraint:</strong> each
     *       {@link OwlObjectProperty} with a known domain and range class becomes a
     *       {@link TypeConstraint.RelationConstraint} attached to the domain type.</li>
     *   <li><strong>Functional object property → CardinalityConstraint:</strong> when
     *       {@link OwlObjectProperty#isFunctional()} is true, a
     *       {@link TypeConstraint.CardinalityConstraint} with
     *       {@link TypeConstraint.Cardinality#MANY_TO_ONE} is also attached to the domain type.</li>
     *   <li><strong>MaxCardinality(1) restriction → CardinalityConstraint:</strong>
     *       a {@link OwlRestriction.MaxCardinality} restriction with n=1 on a class produces
     *       a {@link TypeConstraint.CardinalityConstraint} with
     *       {@link TypeConstraint.Cardinality#MANY_TO_ONE} attached to that class.</li>
     * </ol>
     *
     * @return a new {@link TypeRegistry} derived from this ontology's TBox declarations;
     *         never {@code null}
     */
    public TypeRegistry toTypeRegistry() {
        TypeRegistry registry = new TypeRegistry();

        // ── Step 1: collect per-class attribute schemas from data properties ──────
        // Build a schema builder for each class that has at least one data property
        Map<String, TypeAttributeSchema.Builder> schemaBuilders = new LinkedHashMap<>();
        for (OwlDataProperty dp : dataProperties.values()) {
            if (dp.domainClassIri() == null) continue;
            OwlClass domainClass = classes.get(dp.domainClassIri());
            if (domainClass == null) continue;
            String typeName = domainClass.localName();
            schemaBuilders.computeIfAbsent(typeName, k -> TypeAttributeSchema.builder())
                    .add(AttributeDefinition.of(dp.localName(), dp.toAttributeValueType())
                            .required(dp.isFunctional())
                            .build());
        }

        // ── Step 2: declare all classes with their schemas ───────────────────────
        for (OwlClass owlClass : classes.values()) {
            String typeName = owlClass.localName();
            TypeAttributeSchema schema = schemaBuilders.containsKey(typeName)
                    ? schemaBuilders.get(typeName).build()
                    : TypeAttributeSchema.EMPTY;
            registry.declare(typeName, schema);
        }

        // ── Step 3: wire subClassOf → single-parent isA links ────────────────────
        for (OwlClass owlClass : classes.values()) {
            String childName = owlClass.localName();
            String firstParentIri = null;
            for (String parentIri : owlClass.subClassOfIris()) {
                firstParentIri = parentIri;
                break; // single-parent: use only the first declared superclass
            }
            if (firstParentIri == null) continue;
            OwlClass parentClass = classes.get(firstParentIri);
            if (parentClass == null) {
                // External IRI: derive local name without a local OwlClass entry
                String parentLocalName = localNameFromIri(firstParentIri);
                registry.subtype(childName, parentLocalName);
            } else {
                registry.subtype(childName, parentClass.localName());
            }
        }

        // ── Step 4: attach object property constraints ────────────────────────────
        for (OwlObjectProperty op : objectProperties.values()) {
            if (op.domainClassIri() == null || op.rangeClassIri() == null) continue;
            OwlClass domainClass = classes.get(op.domainClassIri());
            OwlClass rangeClass  = classes.get(op.rangeClassIri());
            if (domainClass == null || rangeClass == null) continue;

            String domainName = domainClass.localName();
            String rangeName  = rangeClass.localName();
            String label      = op.localName();

            // RelationConstraint: domain/range typing
            registry.constraint(domainName,
                    new TypeConstraint.RelationConstraint(label, domainName, rangeName));

            // CardinalityConstraint when functional
            if (op.isFunctional()) {
                registry.constraint(domainName,
                        new TypeConstraint.CardinalityConstraint(
                                label, TypeConstraint.Cardinality.MANY_TO_ONE));
            }
        }

        // ── Step 5: translate MaxCardinality(1) restrictions to CardinalityConstraints ──
        for (OwlClass owlClass : classes.values()) {
            String typeName = owlClass.localName();
            for (OwlRestriction restriction : owlClass.restrictions()) {
                if (restriction instanceof OwlRestriction.MaxCardinality mc && mc.n() == 1) {
                    String propLocalName = localNameFromIri(mc.onPropertyIri());
                    registry.constraint(typeName,
                            new TypeConstraint.CardinalityConstraint(
                                    propLocalName, TypeConstraint.Cardinality.MANY_TO_ONE));
                }
            }
        }

        return registry;
    }

    /**
     * Derive a local name from an IRI string without requiring a corresponding
     * {@link OwlClass} or property entry. Same logic as {@link OwlClass#localName()}.
     */
    private static String localNameFromIri(String iri) {
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) return iri.substring(slash + 1);
        return iri;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /** Start a builder for an ontology with the given IRI (may be {@code null} for anonymous). */
    public static Builder of(String ontologyIri) {
        return new Builder(ontologyIri);
    }

    /** Start a builder for an anonymous ontology. */
    public static Builder anonymous() {
        return new Builder(null);
    }

    public static final class Builder {
        private final String ontologyIri;
        /** The ontology IRI this builder constructs under. */
        public String ontologyIri() { return ontologyIri; }
        private final Map<String, OwlClass>          classes          = new LinkedHashMap<>();
        private final Map<String, OwlObjectProperty> objectProperties = new LinkedHashMap<>();
        private final Map<String, OwlDataProperty>   dataProperties   = new LinkedHashMap<>();
        private final Map<String, String>            sameAs           = new LinkedHashMap<>();

        private Builder(String ontologyIri) {
            this.ontologyIri = ontologyIri;
        }

        /**
         * Register an OWL class. The class IRI is used as the map key.
         *
         * @param owlClass the class to add (never {@code null})
         */
        public Builder addClass(OwlClass owlClass) {
            Objects.requireNonNull(owlClass, "owlClass");
            classes.put(owlClass.classIri(), owlClass);
            return this;
        }

        /**
         * Register an object property. The property IRI is used as the map key.
         *
         * @param property the property to add (never {@code null})
         */
        public Builder addObjectProperty(OwlObjectProperty property) {
            Objects.requireNonNull(property, "property");
            objectProperties.put(property.propertyIri(), property);
            return this;
        }

        /**
         * Register a data property. The property IRI is used as the map key.
         *
         * @param property the property to add (never {@code null})
         */
        public Builder addDataProperty(OwlDataProperty property) {
            Objects.requireNonNull(property, "property");
            dataProperties.put(property.propertyIri(), property);
            return this;
        }

        /**
         * Record an {@code owl:sameAs} assertion: {@code individualIri} is the same individual
         * as {@code canonicalIri}.
         */
        public Builder sameAs(String individualIri, String canonicalIri) {
            sameAs.put(
                    Objects.requireNonNull(individualIri, "individualIri"),
                    Objects.requireNonNull(canonicalIri,  "canonicalIri"));
            return this;
        }

        /** Build the immutable {@link OwlOntology}. */
        public OwlOntology build() {
            return new OwlOntology(this);
        }
    }

    @Override
    public String toString() {
        return "OwlOntology{"
                + (ontologyIri != null ? ontologyIri : "<anonymous>")
                + ", classes="          + classes.size()
                + ", objectProperties=" + objectProperties.size()
                + ", dataProperties="   + dataProperties.size()
                + "}";
    }
}

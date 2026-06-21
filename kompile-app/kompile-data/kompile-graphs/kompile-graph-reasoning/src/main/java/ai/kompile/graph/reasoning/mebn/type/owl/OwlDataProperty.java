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
import ai.kompile.graph.reasoning.mebn.type.AttributeValueType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An OWL 2 data property declaration — a first-class IRI-identified typed attribute
 * whose values are RDF literals (XSD datatypes), not other OWL individuals.
 *
 * <h2>Relationship to Phase-1</h2>
 * <p>An {@code OwlDataProperty} corresponds to an
 * {@link AttributeDefinition} with an IRI identity.
 * The bridge method {@link OwlOntology#toTypeRegistry()} translates each data property
 * into an {@code AttributeDefinition} attached to the domain class's
 * {@link ai.kompile.graph.reasoning.mebn.type.TypeAttributeSchema}.</p>
 *
 * <h2>XSD datatype → AttributeValueType mapping</h2>
 * <p>The {@link #rangeDatatype()} is an XSD type URI string (e.g. {@code xsd:string},
 * {@code xsd:integer}). {@link #toAttributeValueType()} maps the most common XSD types
 * onto {@link AttributeValueType} for the Phase-1 bridge:</p>
 * <ul>
 *   <li>{@code xsd:string}, {@code xsd:anyURI}, {@code xsd:date}, {@code xsd:dateTime}
 *       → {@link AttributeValueType#STRING}</li>
 *   <li>{@code xsd:integer}, {@code xsd:long}, {@code xsd:int}, {@code xsd:decimal},
 *       {@code xsd:double}, {@code xsd:float}
 *       → {@link AttributeValueType#NUMBER}</li>
 *   <li>{@code xsd:boolean}
 *       → {@link AttributeValueType#BOOLEAN}</li>
 *   <li>Anything unrecognised → {@link AttributeValueType#STRING} (safe default)</li>
 * </ul>
 *
 * <p>Instances are immutable once built. Use the {@link Builder} to construct them.</p>
 */
public final class OwlDataProperty {

    private final String propertyIri;
    private final String domainClassIri;
    private final String rangeDatatype;
    private final boolean functional;
    private final Set<String> subPropertyOfIris;
    private final Set<String> equivalentPropertyIris;

    private OwlDataProperty(Builder b) {
        this.propertyIri            = Objects.requireNonNull(b.propertyIri, "propertyIri");
        this.domainClassIri         = b.domainClassIri;
        this.rangeDatatype          = b.rangeDatatype;
        this.functional             = b.functional;
        this.subPropertyOfIris      = Collections.unmodifiableSet(new LinkedHashSet<>(b.subPropertyOfIris));
        this.equivalentPropertyIris = Collections.unmodifiableSet(new LinkedHashSet<>(b.equivalentPropertyIris));
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /** The absolute IRI identifying this data property. */
    public String propertyIri() { return propertyIri; }

    /**
     * The IRI of the domain class ({@code rdfs:domain}), or {@code null} when not declared.
     */
    public String domainClassIri() { return domainClassIri; }

    /**
     * The XSD datatype URI for the property's range (e.g. {@code xsd:string},
     * {@code xsd:integer}), or {@code null} when not declared.
     */
    public String rangeDatatype() { return rangeDatatype; }

    /** Whether this data property is functional (at most one value per individual). */
    public boolean isFunctional() { return functional; }

    /** IRIs of declared super-properties ({@code rdfs:subPropertyOf}). */
    public Set<String> subPropertyOfIris() { return subPropertyOfIris; }

    /** IRIs of declared equivalent properties ({@code owl:equivalentDataProperties}). */
    public Set<String> equivalentPropertyIris() { return equivalentPropertyIris; }

    /**
     * Derive the local name from the property IRI (fragment after {@code #}, or last path
     * segment after {@code /}).  Used as the attribute name in the Phase-1 registry.
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

    /**
     * Map the XSD {@link #rangeDatatype()} to the Phase-1 {@link AttributeValueType} bucket
     * used when generating an {@link AttributeDefinition} for the registry bridge.
     *
     * <p>Unrecognised or {@code null} datatypes fall back to {@link AttributeValueType#STRING}.</p>
     *
     * @return the best-matching {@link AttributeValueType} (never {@code null})
     */
    public AttributeValueType toAttributeValueType() {
        if (rangeDatatype == null) return AttributeValueType.STRING;
        String dt = rangeDatatype.toLowerCase();
        if (dt.contains("boolean"))                                    return AttributeValueType.BOOLEAN;
        if (dt.contains("integer") || dt.contains("long")
                || dt.contains("int")    || dt.contains("decimal")
                || dt.contains("double") || dt.contains("float"))     return AttributeValueType.NUMBER;
        // string, anyURI, date, dateTime, and any unrecognised type
        return AttributeValueType.STRING;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /** Start a builder for a data property with the given IRI. */
    public static Builder of(String propertyIri) {
        return new Builder(propertyIri);
    }

    public static final class Builder {
        private final String propertyIri;
        private String domainClassIri;
        private String rangeDatatype;
        private boolean functional;
        private final Set<String> subPropertyOfIris      = new LinkedHashSet<>();
        private final Set<String> equivalentPropertyIris = new LinkedHashSet<>();

        private Builder(String propertyIri) {
            this.propertyIri = Objects.requireNonNull(propertyIri, "propertyIri");
        }

        public Builder domain(String domainClassIri) {
            this.domainClassIri = domainClassIri;
            return this;
        }

        public Builder range(String rangeDatatype) {
            this.rangeDatatype = rangeDatatype;
            return this;
        }

        public Builder functional(boolean v) { this.functional = v; return this; }

        public Builder subPropertyOf(String iri) {
            subPropertyOfIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        public Builder equivalentProperty(String iri) {
            equivalentPropertyIris.add(Objects.requireNonNull(iri, "iri"));
            return this;
        }

        public OwlDataProperty build() {
            return new OwlDataProperty(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwlDataProperty that)) return false;
        return propertyIri.equals(that.propertyIri);
    }

    @Override
    public int hashCode() {
        return propertyIri.hashCode();
    }

    @Override
    public String toString() {
        return "OwlDataProperty{" + propertyIri
                + (rangeDatatype != null ? ",range=" + rangeDatatype : "")
                + (functional ? ",functional" : "")
                + "}";
    }
}

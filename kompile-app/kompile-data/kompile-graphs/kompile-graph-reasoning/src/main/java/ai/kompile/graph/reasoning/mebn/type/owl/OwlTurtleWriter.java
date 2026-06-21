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
import java.util.List;
import java.util.Objects;

/**
 * Serializes an {@link OwlOntology} to Turtle (TTL) format using the narrow OWL subset
 * modelled by the infra-free OWL layer.
 *
 * <h2>Turtle subset emitted</h2>
 * <ul>
 *   <li>{@code @prefix} headers for {@code owl:}, {@code rdfs:}, {@code rdf:}, {@code xsd:}
 *       using namespace constants from {@link OwlIri}.</li>
 *   <li>{@code <iri> a owl:Class ; rdfs:subClassOf … ; owl:equivalentClass … ;
 *       owl:disjointWith … .} for each {@link OwlClass}.</li>
 *   <li>Blank-node restrictions ({@code [ a owl:Restriction ; owl:onProperty … ;
 *       owl:someValuesFrom/allValuesFrom/hasValue/minCardinality/maxCardinality/exactCardinality … ]})
 *       as additional {@code rdfs:subClassOf} triples on the owning class.</li>
 *   <li>{@code <iri> a owl:ObjectProperty [, owl:TransitiveProperty, …] ;
 *       rdfs:domain … ; rdfs:range … ; owl:inverseOf … .} for each {@link OwlObjectProperty}.</li>
 *   <li>{@code <iri> a owl:DatatypeProperty [, owl:FunctionalProperty] ;
 *       rdfs:domain … ; rdfs:range xsd:… .} for each {@link OwlDataProperty}.</li>
 *   <li>The {@code owl:sameAs} map becomes
 *       {@code <individual> owl:sameAs <canonical> .} triples.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <p>This writer is intentionally not a full Turtle serializer — it generates a compact,
 * predictable subset sufficient for the constructs the OWL model supports. The output is
 * round-trip compatible with {@link OwlTurtleReader}.</p>
 *
 * <p>This class is stateless and thread-safe.</p>
 */
public final class OwlTurtleWriter {

    // ─── Prefix shorthand labels (match what the reader recognises) ───────────────

    private static final String P_OWL  = "owl";
    private static final String P_RDFS = "rdfs";
    private static final String P_RDF  = "rdf";
    private static final String P_XSD  = "xsd";

    // ─── Public API ───────────────────────────────────────────────────────────────

    /**
     * Serialize the given ontology to a Turtle string.
     *
     * @param ontology the ontology to serialize (never {@code null})
     * @return a valid Turtle document (never {@code null})
     */
    public String write(OwlOntology ontology) {
        Objects.requireNonNull(ontology, "ontology");

        StringBuilder sb = new StringBuilder(1024);

        // ── Prefix declarations ───────────────────────────────────────────────────
        sb.append("@prefix ").append(P_OWL).append(":  <").append(OwlIri.OWL).append("> .\n");
        sb.append("@prefix ").append(P_RDFS).append(": <").append(OwlIri.RDFS).append("> .\n");
        sb.append("@prefix ").append(P_RDF).append(":  <").append(OwlIri.RDF).append("> .\n");
        sb.append("@prefix ").append(P_XSD).append(":  <").append(OwlIri.XSD).append("> .\n");
        sb.append("\n");

        // ── Ontology declaration ──────────────────────────────────────────────────
        if (ontology.ontologyIri() != null) {
            sb.append(iri(ontology.ontologyIri())).append(" a owl:Ontology .\n\n");
        }

        // ── OWL classes ──────────────────────────────────────────────────────────
        for (OwlClass owlClass : ontology.classes().values()) {
            writeClass(owlClass, sb);
        }

        // ── Object properties ─────────────────────────────────────────────────────
        for (OwlObjectProperty op : ontology.objectProperties().values()) {
            writeObjectProperty(op, sb);
        }

        // ── Data properties ───────────────────────────────────────────────────────
        for (OwlDataProperty dp : ontology.dataProperties().values()) {
            writeDataProperty(dp, sb);
        }

        // ── owl:sameAs ────────────────────────────────────────────────────────────
        for (java.util.Map.Entry<String, String> entry : ontology.sameAs().entrySet()) {
            sb.append(iri(entry.getKey()))
                    .append(" owl:sameAs ").append(iri(entry.getValue())).append(" .\n");
        }

        return sb.toString();
    }

    // ─── Class serialization ──────────────────────────────────────────────────────

    private void writeClass(OwlClass owlClass, StringBuilder sb) {
        // Collect the predicate-object pairs for the "flat" part of this class
        List<String> pos = new ArrayList<>();

        pos.add("a owl:Class");

        for (String superIri : owlClass.subClassOfIris()) {
            pos.add("rdfs:subClassOf " + iri(superIri));
        }
        for (String eqIri : owlClass.equivalentClassIris()) {
            pos.add("owl:equivalentClass " + iri(eqIri));
        }
        for (String disIri : owlClass.disjointWithIris()) {
            pos.add("owl:disjointWith " + iri(disIri));
        }

        // Write the "flat" subject block (ends with ' .' if no restrictions, else ' ;')
        boolean hasRestrictions = !owlClass.restrictions().isEmpty();
        sb.append(iri(owlClass.classIri())).append("\n");
        for (int i = 0; i < pos.size(); i++) {
            boolean lastPos = (i == pos.size() - 1) && !hasRestrictions;
            sb.append("    ").append(pos.get(i));
            sb.append(lastPos ? " .\n" : " ;\n");
        }

        // Each restriction becomes a fresh rdfs:subClassOf blank-node statement
        for (int i = 0; i < owlClass.restrictions().size(); i++) {
            OwlRestriction r = owlClass.restrictions().get(i);
            boolean lastRestriction = (i == owlClass.restrictions().size() - 1);
            sb.append("    rdfs:subClassOf ").append(blankRestriction(r));
            sb.append(lastRestriction ? " .\n" : " ;\n");
        }

        sb.append("\n");
    }

    private String blankRestriction(OwlRestriction r) {
        StringBuilder bn = new StringBuilder("[ a owl:Restriction ;\n");
        bn.append("        owl:onProperty ").append(iri(r.onPropertyIri())).append(" ;\n");

        if (r instanceof OwlRestriction.SomeValuesFrom svf) {
            bn.append("        owl:someValuesFrom ").append(iri(svf.fillerClassIri()));
        } else if (r instanceof OwlRestriction.AllValuesFrom avf) {
            bn.append("        owl:allValuesFrom ").append(iri(avf.fillerClassIri()));
        } else if (r instanceof OwlRestriction.HasValue hv) {
            bn.append("        owl:hasValue ").append(iri(hv.individualId()));
        } else if (r instanceof OwlRestriction.MinCardinality mc) {
            if (mc.qualifiedOnClassIri() != null) {
                bn.append("        owl:minQualifiedCardinality \"").append(mc.n())
                        .append("\"^^xsd:nonNegativeInteger ;\n");
                bn.append("        owl:onClass ").append(iri(mc.qualifiedOnClassIri()));
            } else {
                bn.append("        owl:minCardinality \"").append(mc.n())
                        .append("\"^^xsd:nonNegativeInteger");
            }
        } else if (r instanceof OwlRestriction.MaxCardinality mc) {
            if (mc.qualifiedOnClassIri() != null) {
                bn.append("        owl:maxQualifiedCardinality \"").append(mc.n())
                        .append("\"^^xsd:nonNegativeInteger ;\n");
                bn.append("        owl:onClass ").append(iri(mc.qualifiedOnClassIri()));
            } else {
                bn.append("        owl:maxCardinality \"").append(mc.n())
                        .append("\"^^xsd:nonNegativeInteger");
            }
        } else if (r instanceof OwlRestriction.ExactCardinality ec) {
            if (ec.qualifiedOnClassIri() != null) {
                bn.append("        owl:exactQualifiedCardinality \"").append(ec.n())
                        .append("\"^^xsd:nonNegativeInteger ;\n");
                bn.append("        owl:onClass ").append(iri(ec.qualifiedOnClassIri()));
            } else {
                bn.append("        owl:exactCardinality \"").append(ec.n())
                        .append("\"^^xsd:nonNegativeInteger");
            }
        }

        bn.append("\n    ]");
        return bn.toString();
    }

    // ─── Object property serialization ────────────────────────────────────────────

    private void writeObjectProperty(OwlObjectProperty op, StringBuilder sb) {
        // Collect rdf:type values: owl:ObjectProperty is always first, then characteristics
        List<String> types = new ArrayList<>();
        types.add("owl:ObjectProperty");
        if (op.isFunctional())        types.add("owl:FunctionalProperty");
        if (op.isInverseFunctional()) types.add("owl:InverseFunctionalProperty");
        if (op.isTransitive())        types.add("owl:TransitiveProperty");
        if (op.isSymmetric())         types.add("owl:SymmetricProperty");
        if (op.isReflexive())         types.add("owl:ReflexiveProperty");
        if (op.isAsymmetric())        types.add("owl:AsymmetricProperty");
        if (op.isIrreflexive())       types.add("owl:IrreflexiveProperty");

        List<String> pos = new ArrayList<>();
        // "a owl:ObjectProperty [, owl:TransitiveProperty …]" — comma-list for types
        pos.add("a " + String.join(" , ", types));

        if (op.domainClassIri() != null) {
            pos.add("rdfs:domain " + iri(op.domainClassIri()));
        }
        if (op.rangeClassIri() != null) {
            pos.add("rdfs:range " + iri(op.rangeClassIri()));
        }
        if (op.inverseOfIri() != null) {
            pos.add("owl:inverseOf " + iri(op.inverseOfIri()));
        }
        for (String superP : op.subPropertyOfIris()) {
            pos.add("rdfs:subPropertyOf " + iri(superP));
        }
        for (String eqP : op.equivalentPropertyIris()) {
            pos.add("owl:equivalentProperty " + iri(eqP));
        }

        writePosBlock(op.propertyIri(), pos, sb);
    }

    // ─── Data property serialization ──────────────────────────────────────────────

    private void writeDataProperty(OwlDataProperty dp, StringBuilder sb) {
        List<String> types = new ArrayList<>();
        types.add("owl:DatatypeProperty");
        if (dp.isFunctional()) types.add("owl:FunctionalProperty");

        List<String> pos = new ArrayList<>();
        pos.add("a " + String.join(" , ", types));

        if (dp.domainClassIri() != null) {
            pos.add("rdfs:domain " + iri(dp.domainClassIri()));
        }
        if (dp.rangeDatatype() != null) {
            pos.add("rdfs:range " + iriOrPrefixed(dp.rangeDatatype()));
        }
        for (String superP : dp.subPropertyOfIris()) {
            pos.add("rdfs:subPropertyOf " + iri(superP));
        }
        for (String eqP : dp.equivalentPropertyIris()) {
            pos.add("owl:equivalentProperty " + iri(eqP));
        }

        writePosBlock(dp.propertyIri(), pos, sb);
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────────

    /**
     * Emit a subject + predicate-object list block ending with {@code .}.
     *
     * <pre>
     * &lt;subject&gt;
     *     predObj1 ;
     *     predObj2 .
     * </pre>
     */
    private static void writePosBlock(String subjectIri, List<String> pos, StringBuilder sb) {
        sb.append(iri(subjectIri)).append("\n");
        for (int i = 0; i < pos.size(); i++) {
            sb.append("    ").append(pos.get(i));
            sb.append(i == pos.size() - 1 ? " .\n" : " ;\n");
        }
        sb.append("\n");
    }

    /**
     * Format an IRI as a Turtle {@code <…>} term.
     */
    private static String iri(String iriString) {
        return "<" + iriString + ">";
    }

    /**
     * Format an IRI as a prefixed name when it falls under a known namespace
     * (makes the Turtle more readable), otherwise falls back to {@code <…>}.
     * Only {@code xsd:} prefix substitution is applied here (range datatypes are the
     * main use case; the reader resolves it back via the prefix map).
     */
    private static String iriOrPrefixed(String iriString) {
        if (iriString.startsWith(OwlIri.XSD)) {
            return "xsd:" + iriString.substring(OwlIri.XSD.length());
        }
        return iri(iriString);
    }
}

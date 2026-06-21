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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase O3 — round-trip tests for {@link OwlTurtleWriter} and {@link OwlTurtleReader}.
 *
 * <h2>Scenario: Dog ⊑ Animal ⊑ Thing</h2>
 * <ul>
 *   <li>{@code Dog ⊑ Animal ⊑ Thing} — three-level subClassOf chain.</li>
 *   <li>Object property {@code owns}: transitive, domain=Person, range=Thing.</li>
 *   <li>Data property {@code name}: domain=Animal, range=xsd:string, functional.</li>
 *   <li>{@code SomeValuesFrom} restriction on Dog.</li>
 *   <li>{@code equivalentClass} between Dog and Canine.</li>
 *   <li>{@code disjointWith} between Person and Robot.</li>
 * </ul>
 *
 * <p>Each test verifies a specific aspect of the write→read round-trip.</p>
 */
class OwlTurtleRoundTripTest {

    // ── IRI constants ────────────────────────────────────────────────────────────
    private static final String THING_IRI   = OwlIri.classIri("Thing");
    private static final String ANIMAL_IRI  = OwlIri.classIri("Animal");
    private static final String DOG_IRI     = OwlIri.classIri("Dog");
    private static final String CANINE_IRI  = OwlIri.classIri("Canine");
    private static final String PERSON_IRI  = OwlIri.classIri("Person");
    private static final String ROBOT_IRI   = OwlIri.classIri("Robot");

    private static final String OWNS_IRI    = OwlIri.propIri("owns");
    private static final String NAME_IRI    = OwlIri.propIri("name");

    private final OwlTurtleWriter writer = new OwlTurtleWriter();
    private final OwlTurtleReader reader = new OwlTurtleReader();

    private OwlOntology source;
    private String turtle;
    private OwlOntology reconstructed;

    @BeforeEach
    void buildAndRoundTrip() {
        // ── Build the source ontology ─────────────────────────────────────────────
        OwlClass thing   = OwlClass.of(THING_IRI).build();
        OwlClass animal  = OwlClass.of(ANIMAL_IRI)
                .subClassOf(THING_IRI)
                .build();
        OwlClass dog     = OwlClass.of(DOG_IRI)
                .subClassOf(ANIMAL_IRI)
                .equivalentClass(CANINE_IRI)
                .restriction(new OwlRestriction.SomeValuesFrom(OWNS_IRI, THING_IRI))
                .build();
        OwlClass canine  = OwlClass.of(CANINE_IRI).build();
        OwlClass person  = OwlClass.of(PERSON_IRI)
                .subClassOf(THING_IRI)
                .disjointWith(ROBOT_IRI)
                .build();
        OwlClass robot   = OwlClass.of(ROBOT_IRI)
                .disjointWith(PERSON_IRI)
                .build();

        // Object property: transitive, domain=Person, range=Thing
        OwlObjectProperty owns = OwlObjectProperty.of(OWNS_IRI)
                .domain(PERSON_IRI)
                .range(THING_IRI)
                .transitive(true)
                .build();

        // Data property: name, domain=Animal, range=xsd:string, functional
        OwlDataProperty name = OwlDataProperty.of(NAME_IRI)
                .domain(ANIMAL_IRI)
                .range(OwlIri.XSD_STRING)
                .functional(true)
                .build();

        source = OwlOntology.of(OwlIri.ontologyIri("TestOntology"))
                .addClass(thing)
                .addClass(animal)
                .addClass(dog)
                .addClass(canine)
                .addClass(person)
                .addClass(robot)
                .addObjectProperty(owns)
                .addDataProperty(name)
                .build();

        // ── Write then read ───────────────────────────────────────────────────────
        turtle        = writer.write(source);
        reconstructed = reader.read(turtle);
    }

    // ─── T1: Turtle output contains required @prefix headers ─────────────────────

    @Test
    @DisplayName("T1: write() emits @prefix headers for owl, rdfs, rdf, xsd")
    void t1_writePrefixHeaders() {
        assertTrue(turtle.contains("@prefix owl:"),  "Turtle must contain @prefix owl:");
        assertTrue(turtle.contains("@prefix rdfs:"), "Turtle must contain @prefix rdfs:");
        assertTrue(turtle.contains("@prefix rdf:"),  "Turtle must contain @prefix rdf:");
        assertTrue(turtle.contains("@prefix xsd:"),  "Turtle must contain @prefix xsd:");
    }

    // ─── T2: Turtle output marks classes with a owl:Class ────────────────────────

    @Test
    @DisplayName("T2: write() emits 'a owl:Class' for each declared class")
    void t2_writeOwlClassDeclaration() {
        assertTrue(turtle.contains("a owl:Class"), "'a owl:Class' must appear in the Turtle output");
        // All class IRIs must appear
        assertTrue(turtle.contains(DOG_IRI),    "Dog IRI must appear in Turtle");
        assertTrue(turtle.contains(ANIMAL_IRI), "Animal IRI must appear in Turtle");
        assertTrue(turtle.contains(THING_IRI),  "Thing IRI must appear in Turtle");
        assertTrue(turtle.contains(PERSON_IRI), "Person IRI must appear in Turtle");
    }

    // ─── T3: Round-trip classes — subClassOf links survive ───────────────────────

    @Test
    @DisplayName("T3: round-trip: subClassOf links survive write → read")
    void t3_roundTripSubClassOf() {
        assertNotNull(reconstructed, "read() must return a non-null OwlOntology");

        OwlClass dog = reconstructed.classes().get(DOG_IRI);
        assertNotNull(dog, "Dog class must be in the reconstructed ontology");
        assertTrue(dog.subClassOfIris().contains(ANIMAL_IRI),
                "Dog.subClassOfIris must contain Animal after round-trip");

        OwlClass animal = reconstructed.classes().get(ANIMAL_IRI);
        assertNotNull(animal, "Animal class must be in the reconstructed ontology");
        assertTrue(animal.subClassOfIris().contains(THING_IRI),
                "Animal.subClassOfIris must contain Thing after round-trip");

        OwlClass person = reconstructed.classes().get(PERSON_IRI);
        assertNotNull(person, "Person class must be in the reconstructed ontology");
        assertTrue(person.subClassOfIris().contains(THING_IRI),
                "Person.subClassOfIris must contain Thing after round-trip");
    }

    // ─── T4: Round-trip — equivalentClass survives ───────────────────────────────

    @Test
    @DisplayName("T4: round-trip: owl:equivalentClass survives write → read")
    void t4_roundTripEquivalentClass() {
        OwlClass dog = reconstructed.classes().get(DOG_IRI);
        assertNotNull(dog, "Dog must be present in reconstructed ontology");
        assertTrue(dog.equivalentClassIris().contains(CANINE_IRI),
                "Dog.equivalentClassIris must contain Canine after round-trip");
    }

    // ─── T5: Round-trip — disjointWith survives ──────────────────────────────────

    @Test
    @DisplayName("T5: round-trip: owl:disjointWith survives write → read")
    void t5_roundTripDisjointWith() {
        OwlClass person = reconstructed.classes().get(PERSON_IRI);
        assertNotNull(person, "Person must be present in reconstructed ontology");
        assertTrue(person.disjointWithIris().contains(ROBOT_IRI),
                "Person.disjointWithIris must contain Robot after round-trip");
    }

    // ─── T6: Round-trip — SomeValuesFrom restriction survives ────────────────────

    @Test
    @DisplayName("T6: round-trip: owl:someValuesFrom restriction on Dog survives write → read")
    void t6_roundTripSomeValuesFrom() {
        OwlClass dog = reconstructed.classes().get(DOG_IRI);
        assertNotNull(dog, "Dog must be present in reconstructed ontology");

        List<OwlRestriction> restrictions = dog.restrictions();
        assertFalse(restrictions.isEmpty(),
                "Dog must have at least one restriction after round-trip");

        boolean hasSvf = restrictions.stream()
                .filter(r -> r instanceof OwlRestriction.SomeValuesFrom)
                .map(r -> (OwlRestriction.SomeValuesFrom) r)
                .anyMatch(svf -> OWNS_IRI.equals(svf.onPropertyIri())
                        && THING_IRI.equals(svf.fillerClassIri()));
        assertTrue(hasSvf,
                "Dog must have SomeValuesFrom(owns, Thing) restriction after round-trip");
    }

    // ─── T7: Round-trip — ObjectProperty with characteristics and domain/range ────

    @Test
    @DisplayName("T7: round-trip: OwlObjectProperty 'owns' with transitive=true, domain, range survives")
    void t7_roundTripObjectProperty() {
        OwlObjectProperty owns = reconstructed.objectProperties().get(OWNS_IRI);
        assertNotNull(owns, "'owns' object property must be in the reconstructed ontology");

        assertTrue(owns.isTransitive(),
                "'owns' must be transitive after round-trip");
        assertEquals(PERSON_IRI, owns.domainClassIri(),
                "'owns' domain must be Person after round-trip");
        assertEquals(THING_IRI, owns.rangeClassIri(),
                "'owns' range must be Thing after round-trip");
    }

    // ─── T8: Round-trip — DataProperty with xsd range and functional flag ─────────

    @Test
    @DisplayName("T8: round-trip: OwlDataProperty 'name' with xsd:string range and functional=true survives")
    void t8_roundTripDataProperty() {
        OwlDataProperty name = reconstructed.dataProperties().get(NAME_IRI);
        assertNotNull(name, "'name' data property must be in the reconstructed ontology");

        assertTrue(name.isFunctional(),
                "'name' must be functional after round-trip");
        assertEquals(ANIMAL_IRI, name.domainClassIri(),
                "'name' domain must be Animal after round-trip");
        assertNotNull(name.rangeDatatype(),
                "'name' range datatype must not be null after round-trip");
        assertTrue(name.rangeDatatype().contains("string"),
                "'name' range must contain 'string' (xsd:string) after round-trip");
    }

    // ─── T9: Read parses a hand-written minimal Turtle snippet ───────────────────

    @Test
    @DisplayName("T9: read() parses a hand-written Turtle snippet into the correct OwlClass")
    void t9_readHandWrittenTurtle() {
        String snippet = "@prefix owl:  <" + OwlIri.OWL  + "> .\n"
                + "@prefix rdfs: <" + OwlIri.RDFS + "> .\n"
                + "@prefix rdf:  <" + OwlIri.RDF  + "> .\n"
                + "@prefix xsd:  <" + OwlIri.XSD  + "> .\n\n"
                + "<" + PERSON_IRI + "> a owl:Class ;\n"
                + "    rdfs:subClassOf <" + THING_IRI + "> ;\n"
                + "    owl:disjointWith <" + ROBOT_IRI + "> .\n"
                + "<" + ROBOT_IRI  + "> a owl:Class .\n";

        OwlOntology parsed = reader.read(snippet);
        assertNotNull(parsed, "read() must not return null for a valid snippet");

        OwlClass parsedPerson = parsed.classes().get(PERSON_IRI);
        assertNotNull(parsedPerson, "Person class must be parsed from the snippet");
        assertTrue(parsedPerson.subClassOfIris().contains(THING_IRI),
                "Parsed Person must have Thing as superclass");
        assertTrue(parsedPerson.disjointWithIris().contains(ROBOT_IRI),
                "Parsed Person must have Robot as disjointWith");

        OwlClass parsedRobot = parsed.classes().get(ROBOT_IRI);
        assertNotNull(parsedRobot, "Robot class must be parsed from the snippet");
    }

    // ─── T10: Cardinality restrictions round-trip ─────────────────────────────────

    @Test
    @DisplayName("T10: round-trip: MaxCardinality(2) and ExactCardinality(1, QualifiedClass) survive")
    void t10_roundTripCardinalityRestrictions() {
        String propIri  = OwlIri.propIri("relatedTo");
        String fillIri  = OwlIri.classIri("Category");
        String classIri = OwlIri.classIri("Widget");

        OwlOntology ont = OwlOntology.anonymous()
                .addClass(OwlClass.of(classIri)
                        .restriction(new OwlRestriction.MaxCardinality(propIri, 2, null))
                        .restriction(new OwlRestriction.ExactCardinality(propIri, 1, fillIri))
                        .restriction(new OwlRestriction.MinCardinality(propIri, 0, null))
                        .build())
                .build();

        String ttl        = writer.write(ont);
        OwlOntology back  = reader.read(ttl);

        OwlClass widget = back.classes().get(classIri);
        assertNotNull(widget, "Widget class must survive the round-trip");

        List<OwlRestriction> rs = widget.restrictions();
        assertEquals(3, rs.size(),
                "All three restrictions must survive the round-trip");

        // MaxCardinality(2, null)
        boolean hasMax2 = rs.stream()
                .filter(r -> r instanceof OwlRestriction.MaxCardinality)
                .map(r -> (OwlRestriction.MaxCardinality) r)
                .anyMatch(mc -> mc.n() == 2 && mc.qualifiedOnClassIri() == null);
        assertTrue(hasMax2, "MaxCardinality(2, unqualified) must round-trip");

        // ExactCardinality(1, fillIri)
        boolean hasExact = rs.stream()
                .filter(r -> r instanceof OwlRestriction.ExactCardinality)
                .map(r -> (OwlRestriction.ExactCardinality) r)
                .anyMatch(ec -> ec.n() == 1 && fillIri.equals(ec.qualifiedOnClassIri()));
        assertTrue(hasExact, "ExactCardinality(1, Category) must round-trip");

        // MinCardinality(0, null)
        boolean hasMin0 = rs.stream()
                .filter(r -> r instanceof OwlRestriction.MinCardinality)
                .map(r -> (OwlRestriction.MinCardinality) r)
                .anyMatch(mc -> mc.n() == 0 && mc.qualifiedOnClassIri() == null);
        assertTrue(hasMin0, "MinCardinality(0, unqualified) must round-trip");
    }

    // ─── T11: ObjectProperty characteristics — multiple flags survive ─────────────

    @Test
    @DisplayName("T11: round-trip: symmetric + inverseFunctional flags on an ObjectProperty survive")
    void t11_roundTripMultiplePropertyCharacteristics() {
        String propIri = OwlIri.propIri("knows");

        OwlOntology ont = OwlOntology.anonymous()
                .addObjectProperty(OwlObjectProperty.of(propIri)
                        .symmetric(true)
                        .inverseFunctional(true)
                        .build())
                .build();

        String ttl       = writer.write(ont);
        OwlOntology back = reader.read(ttl);

        OwlObjectProperty knows = back.objectProperties().get(propIri);
        assertNotNull(knows, "'knows' must be in the reconstructed ontology");
        assertTrue(knows.isSymmetric(),         "'knows' symmetric must survive round-trip");
        assertTrue(knows.isInverseFunctional(), "'knows' inverseFunctional must survive round-trip");
    }

    // ─── T12: owl:inverseOf survives ──────────────────────────────────────────────

    @Test
    @DisplayName("T12: round-trip: owl:inverseOf link on ObjectProperty survives")
    void t12_roundTripInverseOf() {
        String hasParentIri = OwlIri.propIri("hasParent");
        String hasChildIri  = OwlIri.propIri("hasChild");

        OwlOntology ont = OwlOntology.anonymous()
                .addObjectProperty(OwlObjectProperty.of(hasParentIri)
                        .inverseOf(hasChildIri)
                        .build())
                .addObjectProperty(OwlObjectProperty.of(hasChildIri)
                        .build())
                .build();

        String ttl       = writer.write(ont);
        OwlOntology back = reader.read(ttl);

        OwlObjectProperty hasParent = back.objectProperties().get(hasParentIri);
        assertNotNull(hasParent, "hasParent must be in the reconstructed ontology");
        assertEquals(hasChildIri, hasParent.inverseOfIri(),
                "owl:inverseOf link must survive the round-trip");
    }

    // ─── T13: HasValue restriction round-trips ────────────────────────────────────

    @Test
    @DisplayName("T13: round-trip: owl:hasValue restriction survives write → read")
    void t13_roundTripHasValue() {
        String propIri  = OwlIri.propIri("status");
        String classIri = OwlIri.classIri("ActiveEntity");
        String indId    = OwlIri.indIri("status-active");

        OwlOntology ont = OwlOntology.anonymous()
                .addClass(OwlClass.of(classIri)
                        .restriction(new OwlRestriction.HasValue(propIri, indId))
                        .build())
                .build();

        String ttl       = writer.write(ont);
        OwlOntology back = reader.read(ttl);

        OwlClass active = back.classes().get(classIri);
        assertNotNull(active, "ActiveEntity must be present after round-trip");

        boolean hasHv = active.restrictions().stream()
                .filter(r -> r instanceof OwlRestriction.HasValue)
                .map(r -> (OwlRestriction.HasValue) r)
                .anyMatch(hv -> propIri.equals(hv.onPropertyIri())
                        && indId.equals(hv.individualId()));
        assertTrue(hasHv, "HasValue(status, status-active) must survive the round-trip");
    }
}

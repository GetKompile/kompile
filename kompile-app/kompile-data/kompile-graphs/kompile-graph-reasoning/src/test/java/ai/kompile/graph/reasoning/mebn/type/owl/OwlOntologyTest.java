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
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Phase O1 of the OWL enhancement: the value objects and the critical
 * {@link OwlOntology#toTypeRegistry()} bridge into the Phase-1 type system.
 *
 * <h2>Scenario: Dog ⊑ Animal ⊑ Thing</h2>
 * <pre>
 *   Thing                  (root class)
 *   └── Animal ⊑ Thing
 *       └── Dog ⊑ Animal
 * </pre>
 *
 * <p>Plus an object property {@code owns} (Person → Thing) and a data property
 * {@code name} (Animal → xsd:string), and a {@code someValuesFrom} restriction on Dog.</p>
 */
class OwlOntologyTest {

    // IRI constants for the fixture ontology
    static final String THING_IRI  = OwlIri.classIri("Thing");
    static final String ANIMAL_IRI = OwlIri.classIri("Animal");
    static final String DOG_IRI    = OwlIri.classIri("Dog");
    static final String PERSON_IRI = OwlIri.classIri("Person");

    static final String OWNS_IRI = OwlIri.propIri("owns");
    static final String NAME_IRI = OwlIri.propIri("name");

    /** The OWL ontology fixture used across tests. */
    private OwlOntology ontology;

    /** A small graph with one entity of each type — used to build TypeHierarchy. */
    private MutableReasoningGraph graph;

    @BeforeEach
    void buildFixture() {
        // ── OWL class declarations ──────────────────────────────────────────────
        OwlClass thing  = OwlClass.of(THING_IRI).build();
        OwlClass animal = OwlClass.of(ANIMAL_IRI)
                .subClassOf(THING_IRI)
                .build();
        OwlClass dog = OwlClass.of(DOG_IRI)
                .subClassOf(ANIMAL_IRI)
                .restriction(new OwlRestriction.SomeValuesFrom(OWNS_IRI, THING_IRI))
                .build();
        OwlClass person = OwlClass.of(PERSON_IRI)
                .subClassOf(THING_IRI)
                .build();

        // ── Object property: owns (Person → Thing) ──────────────────────────────
        OwlObjectProperty owns = OwlObjectProperty.of(OWNS_IRI)
                .domain(PERSON_IRI)
                .range(THING_IRI)
                .build();

        // ── Data property: name (Animal → xsd:string) ───────────────────────────
        OwlDataProperty name = OwlDataProperty.of(NAME_IRI)
                .domain(ANIMAL_IRI)
                .range(OwlIri.XSD_STRING)
                .functional(false)
                .build();

        ontology = OwlOntology.of(OwlIri.ontologyIri("DogOntology"))
                .addClass(thing)
                .addClass(animal)
                .addClass(dog)
                .addClass(person)
                .addObjectProperty(owns)
                .addDataProperty(name)
                .build();

        // ── Graph with one individual per type ───────────────────────────────────
        graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("thing1").type("Thing").label("Some thing").build());
        graph.addEntity(GraphEntity.builder("animal1").type("Animal").label("Generic animal").build());
        graph.addEntity(GraphEntity.builder("fido").type("Dog").label("Fido").build());
        graph.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
    }

    // ─── Test 1: toTypeRegistry produces a registry and hierarchy with isA transitivity ──

    @Test
    @DisplayName("toTypeRegistry() → TypeHierarchy.isA(Dog, Thing) transitively through Animal")
    void toTypeRegistry_isATransitive_dogIsThing() {
        TypeRegistry registry = ontology.toTypeRegistry();
        assertNotNull(registry, "toTypeRegistry() must return a non-null TypeRegistry");

        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph, registry);

        // Reflexive
        assertTrue(hierarchy.isA("Dog",    "Dog"),    "Dog isA Dog (reflexive)");
        assertTrue(hierarchy.isA("Animal", "Animal"), "Animal isA Animal (reflexive)");
        assertTrue(hierarchy.isA("Thing",  "Thing"),  "Thing isA Thing (reflexive)");

        // Direct links from subClassOf
        assertTrue(hierarchy.isA("Dog",    "Animal"), "Dog isA Animal (direct subClassOf)");
        assertTrue(hierarchy.isA("Animal", "Thing"),  "Animal isA Thing (direct subClassOf)");
        assertTrue(hierarchy.isA("Person", "Thing"),  "Person isA Thing (direct subClassOf)");

        // Transitive: Dog ⊑ Animal ⊑ Thing, therefore Dog isA Thing
        assertTrue(hierarchy.isA("Dog", "Thing"),
                "Dog isA Thing must hold transitively (Dog ⊑ Animal ⊑ Thing)");

        // Negative: Thing is NOT a subtype of Dog
        assertFalse(hierarchy.isA("Thing", "Dog"),
                "Thing isA Dog must be false (reversed direction)");
        // Animal is not a Dog
        assertFalse(hierarchy.isA("Animal", "Dog"),
                "Animal isA Dog must be false (parent is not a subtype of child)");
    }

    // ─── Test 2: ancestors chain reflects subClassOf ─────────────────────────────

    @Test
    @DisplayName("TypeHierarchy.ancestors(Dog) = [Animal, Thing] — ordered parent-to-root")
    void toTypeRegistry_ancestors_dogHasAnimalAndThing() {
        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph, ontology.toTypeRegistry());

        List<String> ancestors = hierarchy.ancestors("Dog");

        // Must contain Animal and Thing in order (direct parent first)
        assertFalse(ancestors.isEmpty(), "Dog must have ancestors");
        assertEquals("Animal", ancestors.get(0),
                "First ancestor of Dog must be Animal (direct parent)");
        assertEquals("Thing", ancestors.get(1),
                "Second ancestor of Dog must be Thing (grandparent)");
    }

    // ─── Test 3: data property became AttributeDefinition on Animal ──────────────

    @Test
    @DisplayName("OwlDataProperty 'name' on Animal is translated to an AttributeDefinition in the registry")
    void toTypeRegistry_dataProperty_becomesAttributeDefinition() {
        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph, ontology.toTypeRegistry());

        Optional<ai.kompile.graph.reasoning.mebn.type.TypeNode> animalNode =
                hierarchy.forType("Animal");
        assertTrue(animalNode.isPresent(), "Animal must be in the hierarchy");

        Optional<AttributeDefinition> nameDef =
                animalNode.get().getAttributeSchema().attribute("name");
        assertTrue(nameDef.isPresent(),
                "Data property 'name' must become an AttributeDefinition on Animal");
        assertEquals(AttributeValueType.STRING, nameDef.get().getValueType(),
                "xsd:string range must map to AttributeValueType.STRING");
    }

    // ─── Test 4: object property → RelationConstraint on Person ─────────────────

    @Test
    @DisplayName("OwlObjectProperty 'owns' on Person→Thing is translated to a RelationConstraint")
    void toTypeRegistry_objectProperty_becomesRelationConstraint() {
        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph, ontology.toTypeRegistry());

        Optional<ai.kompile.graph.reasoning.mebn.type.TypeNode> personNode =
                hierarchy.forType("Person");
        assertTrue(personNode.isPresent(), "Person must be in the hierarchy");

        List<TypeConstraint> constraints = personNode.get().getConstraints();
        boolean hasRelation = constraints.stream()
                .filter(c -> c instanceof TypeConstraint.RelationConstraint)
                .map(c -> (TypeConstraint.RelationConstraint) c)
                .anyMatch(rc -> "owns".equals(rc.relationLabel())
                        && "Person".equals(rc.domainType())
                        && "Thing".equals(rc.rangeType()));
        assertTrue(hasRelation,
                "Object property 'owns' must produce a RelationConstraint(owns, Person, Thing) on Person");
    }

    // ─── Test 5: MaxCardinality(1) restriction → CardinalityConstraint ───────────

    @Test
    @DisplayName("MaxCardinality(1) restriction on a class is translated to a MANY_TO_ONE CardinalityConstraint")
    void toTypeRegistry_maxCardinalityOne_becomesCardinalityConstraint() {
        // Add a MaxCardinality(1) restriction to Dog on the 'owns' property
        OwlClass dogWithCard = OwlClass.of(DOG_IRI)
                .subClassOf(ANIMAL_IRI)
                .restriction(new OwlRestriction.MaxCardinality(OWNS_IRI, 1, null))
                .build();

        OwlOntology ont = OwlOntology.of(null)
                .addClass(OwlClass.of(THING_IRI).build())
                .addClass(OwlClass.of(ANIMAL_IRI).subClassOf(THING_IRI).build())
                .addClass(dogWithCard)
                .build();

        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph, ont.toTypeRegistry());

        Optional<ai.kompile.graph.reasoning.mebn.type.TypeNode> dogNode =
                hierarchy.forType("Dog");
        assertTrue(dogNode.isPresent(), "Dog must be in the hierarchy");

        boolean hasCardinality = dogNode.get().getConstraints().stream()
                .filter(c -> c instanceof TypeConstraint.CardinalityConstraint)
                .map(c -> (TypeConstraint.CardinalityConstraint) c)
                .anyMatch(cc -> "owns".equals(cc.relationLabel())
                        && TypeConstraint.Cardinality.MANY_TO_ONE == cc.cardinality());
        assertTrue(hasCardinality,
                "MaxCardinality(1) on 'owns' must produce a MANY_TO_ONE CardinalityConstraint on Dog");
    }

    // ─── Test 6: OwlRestriction value objects round-trip via OwlClass ────────────

    @Test
    @DisplayName("OwlClass restrictions are stored and retrieved correctly — all six variants")
    void owlRestriction_allVariants_storedAndRetrieved() {
        String propIri = OwlIri.propIri("relatedTo");
        String fillIri = OwlIri.classIri("Category");

        OwlClass c = OwlClass.of(OwlIri.classIri("Widget"))
                .restriction(new OwlRestriction.SomeValuesFrom(propIri, fillIri))
                .restriction(new OwlRestriction.AllValuesFrom(propIri, fillIri))
                .restriction(new OwlRestriction.HasValue(propIri, "cat-1"))
                .restriction(new OwlRestriction.MinCardinality(propIri, 1, null))
                .restriction(new OwlRestriction.MaxCardinality(propIri, 5, fillIri))
                .restriction(new OwlRestriction.ExactCardinality(propIri, 3, null))
                .build();

        List<OwlRestriction> restrictions = c.restrictions();
        assertEquals(6, restrictions.size(), "All six restriction variants must be stored");

        assertTrue(restrictions.get(0) instanceof OwlRestriction.SomeValuesFrom,  "Slot 0: SomeValuesFrom");
        assertTrue(restrictions.get(1) instanceof OwlRestriction.AllValuesFrom,   "Slot 1: AllValuesFrom");
        assertTrue(restrictions.get(2) instanceof OwlRestriction.HasValue,         "Slot 2: HasValue");
        assertTrue(restrictions.get(3) instanceof OwlRestriction.MinCardinality,   "Slot 3: MinCardinality");
        assertTrue(restrictions.get(4) instanceof OwlRestriction.MaxCardinality,   "Slot 4: MaxCardinality");
        assertTrue(restrictions.get(5) instanceof OwlRestriction.ExactCardinality, "Slot 5: ExactCardinality");

        OwlRestriction.MaxCardinality mc =
                (OwlRestriction.MaxCardinality) restrictions.get(4);
        assertEquals(5, mc.n(), "MaxCardinality n=5 must round-trip");
        assertEquals(fillIri, mc.qualifiedOnClassIri(), "qualified class IRI must round-trip");
    }

    // ─── Test 7: OwlIri minting and encode helpers ───────────────────────────────

    @Test
    @DisplayName("OwlIri mints canonical IRIs and encode() handles spaces")
    void owlIri_minting() {
        assertEquals("https://kompile.ai/kg/class/Person",  OwlIri.classIri("Person"));
        assertEquals("https://kompile.ai/kg/prop/owns",     OwlIri.propIri("owns"));
        assertEquals("https://kompile.ai/kg/node/alice-1",  OwlIri.indIri("alice-1"));

        // encode: space → %20
        String withSpace = OwlIri.classIri("My Class");
        assertTrue(withSpace.contains("%20"),
                "OwlIri.encode must percent-encode spaces in IRI path segments");
    }
}

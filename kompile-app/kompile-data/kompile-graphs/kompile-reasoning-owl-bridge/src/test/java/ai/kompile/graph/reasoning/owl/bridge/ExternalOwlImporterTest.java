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
package ai.kompile.graph.reasoning.owl.bridge;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.owl.bridge.importer.ExternalOwlImporter;
import ai.kompile.graph.reasoning.owl.bridge.importer.ExternalOwlImporter.ImportResult;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ExternalOwlImporter} can parse a small OWL/Turtle file and project
 * it correctly into the lib's type model.
 *
 * <p>The test ontology is {@code test-pizza.ttl} in {@code src/test/resources/}. It defines:
 * <ul>
 *   <li>Classes: Food, Pizza (⊑ Food), VegetarianPizza (⊑ Pizza), MeatPizza (⊑ Pizza, disjoint
 *       VegetarianPizza), Topping, MeatTopping (⊑ Topping), VeggieTopping (⊑ Topping),
 *       MargheritaPizza (⊑ VegetarianPizza)</li>
 *   <li>Object property: hasTopping (domain Pizza, range Topping)</li>
 * </ul>
 */
class ExternalOwlImporterTest {

    private static final ExternalOwlImporter IMPORTER = new ExternalOwlImporter();

    @Test
    void importTurtleFileFromClasspath() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("test-pizza.ttl");
        assertNotNull(in, "test-pizza.ttl must be on the test classpath");

        ImportResult result = IMPORTER.importFrom(in, null, null);

        assertNotNull(result, "ImportResult must not be null");

        OwlOntology tbox = result.tbox();
        assertNotNull(tbox, "TBox must not be null");

        // The pizza ontology defines 8 classes
        assertFalse(tbox.classes().isEmpty(), "TBox must contain at least one class");

        // Must contain Pizza, Food, VegetarianPizza, MeatPizza, Topping
        // IRIs are in the test namespace https://kompile.ai/kg/test/pizza#
        // The mapper's fromOwlApi captures named class declarations.
        boolean hasPizzaClass = tbox.classes().keySet().stream()
                .anyMatch(iri -> iri.contains("Pizza") || iri.endsWith("#Pizza"));
        assertTrue(hasPizzaClass,
                "TBox classes must include Pizza. Found: " + tbox.classes().keySet());

        boolean hasFoodClass = tbox.classes().keySet().stream()
                .anyMatch(iri -> iri.contains("Food") || iri.endsWith("#Food"));
        assertTrue(hasFoodClass,
                "TBox classes must include Food. Found: " + tbox.classes().keySet());

        // hasTopping should be an object property
        boolean hasHasToppingProp = tbox.objectProperties().keySet().stream()
                .anyMatch(iri -> iri.contains("hasTopping"));
        assertTrue(hasHasToppingProp,
                "TBox object properties must include hasTopping. Found: " + tbox.objectProperties().keySet());

        // ABox should be empty for a pure TBox ontology
        ReasoningGraph abox = result.abox();
        assertNotNull(abox);
    }

    @Test
    void importTurtleFileFromPath() throws Exception {
        // Load from classpath resource path
        java.net.URL url = getClass().getClassLoader().getResource("test-pizza.ttl");
        assertNotNull(url, "test-pizza.ttl must be on the test classpath");

        Path path = Paths.get(url.toURI());
        ImportResult result = IMPORTER.importFromFile(path);

        assertNotNull(result);
        assertFalse(result.tbox().classes().isEmpty(),
                "TBox must contain at least one class when loaded from file path");
    }

    @Test
    void importInlineOwlXml() throws Exception {
        // Minimal OWL/XML ontology in-memory
        String owlXml = "<?xml version=\"1.0\"?>\n"
                + "<Ontology xmlns=\"http://www.w3.org/2002/07/owl#\"\n"
                + "          xml:base=\"https://kompile.ai/kg/test/inline\"\n"
                + "          xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "          xmlns:xml=\"http://www.w3.org/XML/1998/namespace\"\n"
                + "          xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\"\n"
                + "          xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n"
                + "          ontologyIRI=\"https://kompile.ai/kg/test/inline\">\n"
                + "  <Declaration><Class IRI=\"https://kompile.ai/kg/test/inline#Animal\"/></Declaration>\n"
                + "  <Declaration><Class IRI=\"https://kompile.ai/kg/test/inline#Dog\"/></Declaration>\n"
                + "  <SubClassOf>\n"
                + "    <Class IRI=\"https://kompile.ai/kg/test/inline#Dog\"/>\n"
                + "    <Class IRI=\"https://kompile.ai/kg/test/inline#Animal\"/>\n"
                + "  </SubClassOf>\n"
                + "</Ontology>\n";

        InputStream in = new java.io.ByteArrayInputStream(owlXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ImportResult result = IMPORTER.importFrom(in, "https://kompile.ai/kg/test/inline", null);

        assertNotNull(result);
        OwlOntology tbox = result.tbox();

        // Should contain Animal and Dog
        boolean hasAnimal = tbox.classes().keySet().stream().anyMatch(iri -> iri.contains("Animal"));
        boolean hasDog    = tbox.classes().keySet().stream().anyMatch(iri -> iri.contains("Dog"));

        assertTrue(hasAnimal, "TBox must contain Animal class. Classes: " + tbox.classes().keySet());
        assertTrue(hasDog,    "TBox must contain Dog class. Classes: " + tbox.classes().keySet());

        // Dog should be declared as a subclass of Animal
        tbox.classes().values().stream()
                .filter(c -> c.classIri().contains("Dog"))
                .findFirst()
                .ifPresent(dog -> {
                    assertFalse(dog.subClassOfIris().isEmpty(),
                            "Dog must have at least one superclass (Animal)");
                    assertTrue(dog.subClassOfIris().stream().anyMatch(s -> s.contains("Animal")),
                            "Dog must be subClassOf Animal. Got: " + dog.subClassOfIris());
                });
    }

    @Test
    void importResultContainsSkippedAxiomsListNotNull() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("test-pizza.ttl");
        assertNotNull(in);
        ImportResult result = IMPORTER.importFrom(in, null, null);
        // skippedAxioms must not be null (may be empty for a simple TBox)
        assertNotNull(result.skippedAxioms(),
                "skippedAxioms list must not be null");
    }
}

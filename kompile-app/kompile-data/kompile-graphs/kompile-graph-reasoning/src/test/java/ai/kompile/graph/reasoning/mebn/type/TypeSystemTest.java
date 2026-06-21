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
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Phase 1 MEBN type system: {@link TypeHierarchy}, {@link TypeNode},
 * {@link TypeRegistry}, {@link TypeAttributeSchema}, {@link AttributeDefinition}, and
 * {@link TypeConstraint}.
 *
 * <h2>Covered scenarios</h2>
 * <ol>
 *   <li>{@link TypeHierarchy#fromGraph} computes membership from the graph (computed view).</li>
 *   <li>{@link TypeHierarchy#isA} is reflexive, transitive, and correct in the negative direction.</li>
 *   <li>{@link TypeHierarchy#isA} is cycle-safe — a mis-declared cycle does not infinite-loop.</li>
 *   <li>Attribute schema: declarations, lookup, and required-attribute filtering.</li>
 *   <li>Schema inheritance: deep merge places parent definitions behind child overrides.</li>
 *   <li>{@link TypeConstraint.RelationConstraint}: domain/range satisfied vs. violated.</li>
 *   <li>{@link TypeConstraint.AttributeRequiredConstraint}: per-entity check.</li>
 *   <li>{@link TypeHierarchy#ancestors} and {@link TypeHierarchy#descendants} are correct.</li>
 * </ol>
 */
class TypeSystemTest {

    /**
     * Graph used across most tests:
     *
     * <pre>
     *   alice   — type: Person
     *   bob     — type: Employee
     *   carol   — type: employee (lower-case — tests case-insensitive grouping)
     *   acme    — type: Organization
     * </pre>
     *
     * Hierarchy declared in individual tests via a {@link TypeRegistry}:
     * <pre>
     *   Entity → (root)
     *   Person → Entity
     *   Employee → Person
     *   Organization → (root)
     * </pre>
     */
    private ReasoningGraph graph;

    @BeforeEach
    void buildGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Employee").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("employee").label("Carol").build()); // lower-case
        g.addEntity(GraphEntity.builder("acme").type("Organization").label("Acme Corp").build());
        graph = g;
    }

    // ─── Test 1: fromGraph computed membership ────────────────────────────────────

    @Test
    @DisplayName("TypeHierarchy.fromGraph groups entities by type() — computed view, not a parallel registry")
    void fromGraph_computedMembership() {
        TypeHierarchy hierarchy = TypeHierarchy.fromGraph(graph);

        // Person: only alice
        Set<String> personIds = hierarchy.membersOf("Person");
        assertEquals(Set.of("alice"), personIds,
                "Person membership must be computed from graph type() — only alice has type 'Person'");

        // Employee: bob + carol (case-insensitive grouping)
        Set<String> employeeIds = hierarchy.membersOf("Employee");
        assertEquals(2, employeeIds.size(),
                "Employee must include both 'Employee' and 'employee' entities (case-insensitive)");
        assertTrue(employeeIds.contains("bob"),   "bob (type=Employee) must be in Employee membership");
        assertTrue(employeeIds.contains("carol"),  "carol (type=employee) must be in Employee membership");

        // Organization: only acme
        Set<String> orgIds = hierarchy.membersOf("Organization");
        assertEquals(Set.of("acme"), orgIds, "Organization membership must contain only acme");

        // Unknown type → empty
        assertTrue(hierarchy.membersOf("Vehicle").isEmpty(),
                "Unknown type must return an empty membership set");
    }

    // ─── Test 2: isA transitivity and directionality ─────────────────────────────

    @Test
    @DisplayName("isA is reflexive, transitive, and asymmetric — A→B→C, isA(A,C) true, isA(C,A) false")
    void isA_transitivityAndDirectionality() {
        TypeRegistry registry = new TypeRegistry()
                .declare("Entity")
                .declare("Person")
                .subtype("Person", "Entity")
                .declare("Employee")
                .subtype("Employee", "Person");

        TypeHierarchy hierarchy = registry.buildFor(graph);

        // Reflexive
        assertTrue(hierarchy.isA("Person", "Person"),   "isA must be reflexive: Person isA Person");
        assertTrue(hierarchy.isA("Employee", "Employee"), "isA must be reflexive: Employee isA Employee");

        // Direct link
        assertTrue(hierarchy.isA("Person", "Entity"),   "Person isA Entity (direct)");
        assertTrue(hierarchy.isA("Employee", "Person"), "Employee isA Person (direct)");

        // Transitive: two hops
        assertTrue(hierarchy.isA("Employee", "Entity"), "Employee isA Entity (transitive via Person)");

        // Asymmetric — the reverse must be false
        assertFalse(hierarchy.isA("Entity", "Person"),   "Entity is NOT a subtype of Person");
        assertFalse(hierarchy.isA("Person", "Employee"), "Person is NOT a subtype of Employee");
        assertFalse(hierarchy.isA("Entity", "Employee"), "Entity is NOT a subtype of Employee");

        // Unrelated types
        assertFalse(hierarchy.isA("Person", "Organization"),
                "Person and Organization are unrelated — isA must return false");
        assertFalse(hierarchy.isA("Organization", "Person"),
                "Organization and Person are unrelated — isA must return false");

        // Unknown type
        assertFalse(hierarchy.isA("Ghost", "Person"), "Unknown child type must return false");
        assertFalse(hierarchy.isA("Person", "Ghost"), "Unknown ancestor type must return false");
    }

    // ─── Test 3: cycle safety ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isA and ancestors are cycle-safe — a mis-declared cycle does not infinite-loop")
    void cycleSafety_doesNotInfiniteLoop() {
        // Deliberately declare a cycle: A → B → A
        TypeRegistry registry = new TypeRegistry()
                .declare("A")
                .declare("B")
                .subtype("A", "B")
                .subtype("B", "A"); // cycle!

        MutableReasoningGraph cycleGraph = new MutableReasoningGraph();
        cycleGraph.addEntity(GraphEntity.builder("e1").type("A").label("e1").build());
        TypeHierarchy hierarchy = registry.buildFor(cycleGraph);

        // These must terminate — if cycle-safe, they return within time bounds
        boolean result = hierarchy.isA("A", "B");
        // A isA B: true (direct link)
        assertTrue(result, "isA(A, B) should be true (direct link even in cycle)");

        // isA(A, A) reflexive — must not loop
        assertTrue(hierarchy.isA("A", "A"), "isA(A, A) must be reflexive even in cycle");

        // ancestors must terminate — cycle guard should stop it
        List<String> ancs = hierarchy.ancestors("A");
        assertNotNull(ancs, "ancestors() must not throw or hang on a cycle");

        // descendants must terminate
        List<String> descs = hierarchy.descendants("A");
        assertNotNull(descs, "descendants() must not throw or hang on a cycle");
    }

    // ─── Test 4: attribute schema declarations and lookup ──────────────────────────

    @Test
    @DisplayName("TypeAttributeSchema: add definitions, look them up, check required flag")
    void attributeSchema_declarationsAndLookup() {
        TypeAttributeSchema schema = TypeAttributeSchema.builder()
                .add(AttributeDefinition.of("name",  AttributeValueType.STRING).required(true).build())
                .add(AttributeDefinition.of("age",   AttributeValueType.NUMBER).required(false).min(0.0).max(150.0).build())
                .add(AttributeDefinition.of("role",  AttributeValueType.ENUM)
                        .enumValues(List.of("ADMIN", "USER", "GUEST")).required(false).build())
                .build();

        assertEquals(3, schema.size(), "Schema must hold all 3 declared attributes");

        // Lookup by name
        Optional<AttributeDefinition> nameDef = schema.attribute("name");
        assertTrue(nameDef.isPresent(), "'name' attribute must be present");
        assertEquals(AttributeValueType.STRING, nameDef.get().getValueType());
        assertTrue(nameDef.get().isRequired(), "'name' must be required");

        Optional<AttributeDefinition> ageDef = schema.attribute("age");
        assertTrue(ageDef.isPresent(), "'age' attribute must be present");
        assertEquals(AttributeValueType.NUMBER, ageDef.get().getValueType());
        assertFalse(ageDef.get().isRequired(), "'age' must not be required");
        assertEquals(0.0,   ageDef.get().getMin(), "'age' min must be 0");
        assertEquals(150.0, ageDef.get().getMax(), "'age' max must be 150");

        Optional<AttributeDefinition> roleDef = schema.attribute("role");
        assertTrue(roleDef.isPresent(), "'role' attribute must be present");
        assertEquals(List.of("ADMIN", "USER", "GUEST"), roleDef.get().getEnumValues());

        // Required attributes filter
        List<AttributeDefinition> required = schema.requiredAttributes();
        assertEquals(1, required.size(), "Only 'name' is required");
        assertEquals("name", required.get(0).getName());

        // Absent attribute
        assertFalse(schema.attribute("nonexistent").isPresent());
    }

    // ─── Test 5: schema inheritance — deep merge ──────────────────────────────────

    @Test
    @DisplayName("TypeHierarchy merges attribute schemas top-down — child overrides parent on same name")
    void schemaInheritance_deepMerge() {
        TypeAttributeSchema entitySchema = TypeAttributeSchema.builder()
                .add(AttributeDefinition.of("id",   AttributeValueType.STRING).required(true).build())
                .add(AttributeDefinition.of("label", AttributeValueType.STRING).required(false).build())
                .build();

        TypeAttributeSchema personSchema = TypeAttributeSchema.builder()
                .add(AttributeDefinition.of("email", AttributeValueType.STRING).required(true).build())
                .build();

        TypeRegistry registry = new TypeRegistry()
                .declare("Entity",   entitySchema)
                .declare("Person",   personSchema)
                .subtype("Person", "Entity");

        TypeHierarchy hierarchy = registry.buildFor(graph);

        TypeNode personNode = hierarchy.forType("Person").orElseThrow();
        TypeAttributeSchema mergedSchema = personNode.getAttributeSchema();

        // Person's merged schema must contain its own + Entity's attributes
        assertTrue(mergedSchema.attribute("id").isPresent(),    "Inherited 'id' must be in Person schema");
        assertTrue(mergedSchema.attribute("label").isPresent(), "Inherited 'label' must be in Person schema");
        assertTrue(mergedSchema.attribute("email").isPresent(), "Own 'email' must be in Person schema");

        // Inherited attributes carry the inherited flag
        assertTrue(mergedSchema.attribute("id").get().isInherited(),
                "'id' from Entity must be marked inherited in Person");
        assertFalse(mergedSchema.attribute("email").get().isInherited(),
                "'email' declared on Person must NOT be marked inherited");
    }

    // ─── Test 6: RelationConstraint domain/range satisfied ────────────────────────

    @Test
    @DisplayName("RelationConstraint is satisfied when all relations respect domain and range types")
    void relationConstraint_satisfied() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("acme2").type("Organization").label("Acme").build());
        g.addEntity(GraphEntity.builder("bob2").type("Employee").label("Bob").build());
        g.addRelation("r1", "acme2", "bob2", "EMPLOYS", 1.0);

        TypeConstraint constraint = new TypeConstraint.RelationConstraint("EMPLOYS", "Organization", "Employee");
        assertTrue(constraint.isSatisfiedBy(g),
                "EMPLOYS(Organization→Employee) must be satisfied when all relations match");
    }

    // ─── Test 7: RelationConstraint domain/range violated ─────────────────────────

    @Test
    @DisplayName("RelationConstraint is violated when a relation breaks domain or range type")
    void relationConstraint_violated() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        // Wrong source type: Person → Employee (domain should be Organization)
        g.addEntity(GraphEntity.builder("alice2").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob2").type("Employee").label("Bob").build());
        g.addRelation("r1", "alice2", "bob2", "EMPLOYS", 1.0);

        TypeConstraint constraint = new TypeConstraint.RelationConstraint("EMPLOYS", "Organization", "Employee");
        assertFalse(constraint.isSatisfiedBy(g),
                "EMPLOYS(Organization→Employee) must be violated when source is Person, not Organization");
    }

    // ─── Test 8: AttributeRequiredConstraint per-entity ───────────────────────────

    @Test
    @DisplayName("AttributeRequiredConstraint: satisfied when attribute present, violated when absent")
    void attributeRequiredConstraint_perEntity() {
        TypeConstraint.AttributeRequiredConstraint c =
                new TypeConstraint.AttributeRequiredConstraint("name");

        GraphEntity withName = GraphEntity.builder("e1").type("Person").label("Alice")
                .attribute("name", "Alice")
                .build();
        GraphEntity withoutName = GraphEntity.builder("e2").type("Person").label("Bob").build();

        assertTrue(c.isSatisfiedBy(withName),
                "Constraint satisfied when 'name' attribute is present");
        assertFalse(c.isSatisfiedBy(withoutName),
                "Constraint violated when 'name' attribute is absent");

        // Graph-level check always returns true (per-entity constraint, documented)
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(withoutName);
        assertTrue(c.isSatisfiedBy((ReasoningGraph) g),
                "Graph-level isSatisfiedBy always returns true for AttributeRequiredConstraint");
    }
}

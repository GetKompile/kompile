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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link TypeRegistryIO}.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>Declare a registry with types, isA links, attribute schemas, and all constraint kinds →
 *       {@link TypeRegistryIO#toJson(List)} → {@link TypeRegistryIO#fromJson} →
 *       assert the declared structure is preserved (isA links, attribute schemas, constraints).</li>
 *   <li>isA transitivity holds after restore.</li>
 *   <li>Attribute schema: attribute definitions (name, valueType, required, enumValues,
 *       min, max, refersToType) survive round-trip.</li>
 *   <li>All three constraint kinds (RELATION, CARDINALITY, ATTR_REQUIRED) survive round-trip.</li>
 *   <li>Membership is NOT persisted and is NOT present in the restored registry
 *       until {@code buildFor(graph)} is called.</li>
 * </ol>
 */
class TypeRegistryIOTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build a small reasoning graph with four typed entities. */
    private MutableReasoningGraph buildGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Employee").label("Bob").build());
        g.addEntity(GraphEntity.builder("acme").type("Organization").label("Acme").build());
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — isA links and basic types survive round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_isALinksPreserved() {
        // Build snapshot
        List<TypeRegistryIO.TypeEntrySnapshot> snapshot = List.of(
                TypeRegistryIO.TypeEntrySnapshot.of("Entity").build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Person").parent("Entity").build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Employee").parent("Person").build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Organization").build()
        );

        // Serialize → deserialize
        String json = TypeRegistryIO.toJson(snapshot);
        assertNotNull(json, "toJson must not return null");
        assertFalse(json.isEmpty(), "toJson must not return empty string");

        TypeRegistry restored = TypeRegistryIO.fromJson(json);
        TypeHierarchy h = restored.buildFor(buildGraph());

        // Direct isA links
        assertTrue(h.isA("Person",   "Entity"),    "Person isA Entity (direct)");
        assertTrue(h.isA("Employee", "Person"),    "Employee isA Person (direct)");

        // Transitive isA
        assertTrue(h.isA("Employee", "Entity"),    "Employee isA Entity (transitive)");

        // Reflexive
        assertTrue(h.isA("Entity",   "Entity"),    "Entity isA Entity (reflexive)");
        assertTrue(h.isA("Person",   "Person"),    "Person isA Person (reflexive)");

        // Asymmetric
        assertFalse(h.isA("Entity",   "Person"),   "Entity is NOT a subtype of Person");
        assertFalse(h.isA("Person",   "Employee"), "Person is NOT a subtype of Employee");

        // Unrelated
        assertFalse(h.isA("Person", "Organization"), "Person and Organization are unrelated");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — attribute schemas survive round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_attributeSchemas() {
        // Attribute with every field populated
        TypeRegistryIO.AttrSnapshot nameAttr = new TypeRegistryIO.AttrSnapshot(
                "name", "STRING", true, List.of(), null, null, null);
        TypeRegistryIO.AttrSnapshot ageAttr = new TypeRegistryIO.AttrSnapshot(
                "age", "NUMBER", false, List.of(), 0.0, 150.0, null);
        TypeRegistryIO.AttrSnapshot roleAttr = new TypeRegistryIO.AttrSnapshot(
                "role", "ENUM", false, List.of("ADMIN", "USER", "GUEST"), null, null, null);
        TypeRegistryIO.AttrSnapshot refAttr = new TypeRegistryIO.AttrSnapshot(
                "employer", "REFERENCE", false, List.of(), null, null, "Organization");

        List<TypeRegistryIO.TypeEntrySnapshot> snapshot = List.of(
                TypeRegistryIO.TypeEntrySnapshot.of("Entity").build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Person")
                        .parent("Entity")
                        .attribute(nameAttr)
                        .attribute(ageAttr)
                        .attribute(roleAttr)
                        .attribute(refAttr)
                        .build()
        );

        TypeRegistry restored = TypeRegistryIO.fromJson(TypeRegistryIO.toJson(snapshot));
        TypeHierarchy h = restored.buildFor(buildGraph());

        TypeNode personNode = h.forType("Person").orElseThrow(
                () -> new AssertionError("Person type not found after round-trip"));
        TypeAttributeSchema schema = personNode.getAttributeSchema();

        // name: STRING, required
        Optional<AttributeDefinition> nameDef = schema.attribute("name");
        assertTrue(nameDef.isPresent(), "'name' attribute must survive round-trip");
        assertEquals(AttributeValueType.STRING, nameDef.get().getValueType());
        assertTrue(nameDef.get().isRequired(), "'name' must be required");

        // age: NUMBER, min=0, max=150
        Optional<AttributeDefinition> ageDef = schema.attribute("age");
        assertTrue(ageDef.isPresent(), "'age' attribute must survive round-trip");
        assertEquals(AttributeValueType.NUMBER, ageDef.get().getValueType());
        assertFalse(ageDef.get().isRequired(), "'age' must not be required");
        assertNotNull(ageDef.get().getMin(), "'age' min must be present");
        assertNotNull(ageDef.get().getMax(), "'age' max must be present");
        assertEquals(0.0,   ageDef.get().getMin(), 1e-12, "'age' min must be 0.0");
        assertEquals(150.0, ageDef.get().getMax(), 1e-12, "'age' max must be 150.0");

        // role: ENUM with values
        Optional<AttributeDefinition> roleDef = schema.attribute("role");
        assertTrue(roleDef.isPresent(), "'role' attribute must survive round-trip");
        assertEquals(AttributeValueType.ENUM, roleDef.get().getValueType());
        assertEquals(List.of("ADMIN", "USER", "GUEST"), roleDef.get().getEnumValues(),
                "'role' enum values must survive round-trip");

        // employer: REFERENCE with refersToType
        Optional<AttributeDefinition> refDef = schema.attribute("employer");
        assertTrue(refDef.isPresent(), "'employer' attribute must survive round-trip");
        assertEquals(AttributeValueType.REFERENCE, refDef.get().getValueType());
        assertEquals("Organization", refDef.get().getRefersToType(),
                "'employer' refersToType must be 'Organization'");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — all constraint kinds survive round-trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_allConstraintKinds() {
        List<TypeRegistryIO.TypeEntrySnapshot> snapshot = List.of(
                TypeRegistryIO.TypeEntrySnapshot.of("Organization")
                        .relationConstraint("EMPLOYS", "Organization", "Employee")
                        .cardinalityConstraint("REPORTS_TO", "MANY_TO_ONE")
                        .build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Employee")
                        .parent("Organization")
                        .attrRequired("employeeId")
                        .build()
        );

        String json = TypeRegistryIO.toJson(snapshot);
        TypeRegistry restored = TypeRegistryIO.fromJson(json);

        // Build hierarchy over a small graph (entities not important for constraint check)
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("o1").type("Organization").label("Acme").build());
        g.addEntity(GraphEntity.builder("e1").type("Employee").label("Bob").build());
        g.addRelation("r1", "o1", "e1", "EMPLOYS", 1.0);
        TypeHierarchy h = restored.buildFor(g);

        TypeNode orgNode = h.forType("Organization").orElseThrow(
                () -> new AssertionError("Organization not found after round-trip"));
        TypeNode empNode = h.forType("Employee").orElseThrow(
                () -> new AssertionError("Employee not found after round-trip"));

        // Organization must have RELATION + CARDINALITY constraints
        List<TypeConstraint> orgConstraints = orgNode.getConstraints();
        assertEquals(2, orgConstraints.size(),
                "Organization must have 2 constraints after round-trip");

        boolean hasRelation     = false;
        boolean hasCardinality  = false;
        for (TypeConstraint c : orgConstraints) {
            if (c instanceof TypeConstraint.RelationConstraint rc) {
                assertEquals("EMPLOYS",      rc.relationLabel());
                assertEquals("Organization", rc.domainType());
                assertEquals("Employee",     rc.rangeType());
                hasRelation = true;
            } else if (c instanceof TypeConstraint.CardinalityConstraint cc) {
                assertEquals("REPORTS_TO", cc.relationLabel());
                assertEquals(TypeConstraint.Cardinality.MANY_TO_ONE, cc.cardinality());
                hasCardinality = true;
            }
        }
        assertTrue(hasRelation,    "RELATION constraint must survive round-trip");
        assertTrue(hasCardinality, "CARDINALITY constraint must survive round-trip");

        // Employee must have ATTR_REQUIRED constraint
        List<TypeConstraint> empConstraints = empNode.getConstraints();
        assertEquals(1, empConstraints.size(),
                "Employee must have 1 constraint (ATTR_REQUIRED) after round-trip");
        TypeConstraint ec = empConstraints.get(0);
        assertInstanceOf(TypeConstraint.AttributeRequiredConstraint.class, ec,
                "Employee constraint must be AttributeRequiredConstraint");
        assertEquals("employeeId",
                ((TypeConstraint.AttributeRequiredConstraint) ec).attributeName(),
                "AttributeRequiredConstraint.attributeName must survive round-trip");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — schema inheritance still works after restore
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_schemaInheritanceStillWorks() {
        // Entity has "id"; Person extends Entity and adds "email"
        TypeRegistryIO.AttrSnapshot idAttr    = new TypeRegistryIO.AttrSnapshot(
                "id", "STRING", true, List.of(), null, null, null);
        TypeRegistryIO.AttrSnapshot emailAttr = new TypeRegistryIO.AttrSnapshot(
                "email", "STRING", true, List.of(), null, null, null);

        List<TypeRegistryIO.TypeEntrySnapshot> snapshot = List.of(
                TypeRegistryIO.TypeEntrySnapshot.of("Entity").attribute(idAttr).build(),
                TypeRegistryIO.TypeEntrySnapshot.of("Person").parent("Entity")
                        .attribute(emailAttr).build()
        );

        TypeRegistry restored = TypeRegistryIO.fromJson(TypeRegistryIO.toJson(snapshot));
        TypeHierarchy h = restored.buildFor(buildGraph());

        TypeNode personNode = h.forType("Person").orElseThrow();
        TypeAttributeSchema mergedSchema = personNode.getAttributeSchema();

        // Person's merged schema must contain BOTH "id" (inherited) and "email" (own)
        assertTrue(mergedSchema.attribute("id").isPresent(),
                "Inherited 'id' must be in Person merged schema after round-trip");
        assertTrue(mergedSchema.attribute("email").isPresent(),
                "Own 'email' must be in Person merged schema after round-trip");

        // "id" is inherited, "email" is not
        assertTrue(mergedSchema.attribute("id").get().isInherited(),
                "'id' from Entity must be marked inherited in Person");
        assertFalse(mergedSchema.attribute("email").get().isInherited(),
                "'email' declared on Person must NOT be inherited");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — membership is NOT persisted
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_membershipIsNotPersistedOnlyComputed() {
        // Declare a type registry but do NOT call buildFor(graph).
        // The JSON should contain zero membership.
        List<TypeRegistryIO.TypeEntrySnapshot> snapshot = List.of(
                TypeRegistryIO.TypeEntrySnapshot.of("Person").build()
        );

        String json = TypeRegistryIO.toJson(snapshot);
        assertFalse(json.contains("alice"),
                "Serialized JSON must NOT contain entity membership (alice is not in the snapshot)");
        assertFalse(json.contains("entityId"),
                "Serialized JSON must NOT contain 'entityId' field (membership is not persisted)");

        // Restored registry can still build membership from a graph
        TypeRegistry restored = TypeRegistryIO.fromJson(json);
        TypeHierarchy h = restored.buildFor(buildGraph()); // graph has alice:Person
        assertTrue(h.membersOf("Person").contains("alice"),
                "After buildFor(graph), membership is computed correctly");
    }
}

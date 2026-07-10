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
package ai.kompile.process.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OntologyConformanceValidator} — the reusable engine that validates
 * graph-shaped entity/relationship data against an {@link OntologySchema}.
 */
class OntologyConformanceValidatorTest {

    private OntologySchema schema() {
        return OntologySchema.builder()
                .name("FPnA")
                .version(1)
                .entityTypes(List.of(
                        EntityTypeDefinition.builder()
                                .name("Account")
                                .fields(List.of(
                                        FieldDefinition.builder().name("code").required(true).regex("[0-9]{4}").build(),
                                        FieldDefinition.builder().name("balance").min(0.0).max(1000.0).build(),
                                        FieldDefinition.builder().name("currency").enumValues(List.of("USD", "EUR")).build()))
                                .build()))
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("FEEDS_INTO").sourceEntityType("Account").targetEntityType("Report")
                                .cardinality(Cardinality.MANY_TO_ONE).build()))
                .build();
    }

    @Test
    void unknownEntityTypeIsFlagged() {
        var r = OntologyConformanceValidator.validateEntity(schema(), "Vendor", Map.of());
        assertTrue(r.unknownType());
        assertFalse(r.conformant());
    }

    @Test
    void conformantEntityPasses() {
        var props = Map.<String, Object>of("code", "1234", "balance", 500.0, "currency", "USD");
        var r = OntologyConformanceValidator.validateEntity(schema(), "Account", props);
        assertTrue(r.conformant(), () -> "expected no violations but got " + r.violations());
        assertFalse(r.unknownType());
    }

    @Test
    void entityTypeMatchIsCaseInsensitive() {
        // Graph entity_type is free-form LLM output ("account"); ontology name is "Account".
        var r = OntologyConformanceValidator.validateEntity(schema(), "account", Map.of("code", "1234"));
        assertFalse(r.unknownType(), "case-insensitive match must resolve 'account' to 'Account'");
    }

    @Test
    void requiredFieldMissingIsAViolation() {
        var props = Map.<String, Object>of("balance", 500.0, "currency", "USD");
        var r = OntologyConformanceValidator.validateEntity(schema(), "Account", props);
        assertFalse(r.conformant());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("code") && v.contains("missing")));
    }

    @Test
    void regexMinMaxAndEnumViolationsAreAllReported() {
        var props = Map.<String, Object>of(
                "code", "99",        // fails regex [0-9]{4}
                "balance", 5000.0,   // exceeds max 1000
                "currency", "GBP");  // not in {USD, EUR}
        var r = OntologyConformanceValidator.validateEntity(schema(), "Account", props);
        assertFalse(r.conformant());
        assertEquals(3, r.violations().size(), () -> "expected 3 violations, got " + r.violations());
    }

    @Test
    void relationshipDefinedInOntologyIsAllowedWithCardinality() {
        var r = OntologyConformanceValidator.validateRelationship(schema(), "Account", "FEEDS_INTO", "Report");
        assertTrue(r.allowed());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
    }

    @Test
    void relationshipNotInOntologyIsRejected() {
        var r = OntologyConformanceValidator.validateRelationship(schema(), "Account", "OWNS", "Report");
        assertFalse(r.allowed());
        assertNotNull(r.reason());
    }

    @Test
    void schemaWithNoRelationshipTypesIsUnconstrained() {
        OntologySchema noRels = OntologySchema.builder().name("Bare").version(1)
                .entityTypes(List.of()).relationshipTypes(List.of()).build();
        assertTrue(OntologyConformanceValidator.validateRelationship(noRels, "A", "X", "B").allowed());
    }

    @Test
    void sourceCardinalityBoundsAreEnforced() {
        assertFalse(OntologyConformanceValidator.withinSourceCardinality(Cardinality.ONE_TO_ONE, 2));
        assertTrue(OntologyConformanceValidator.withinSourceCardinality(Cardinality.ONE_TO_ONE, 1));
        assertTrue(OntologyConformanceValidator.withinSourceCardinality(Cardinality.MANY_TO_ONE, 1));
        assertFalse(OntologyConformanceValidator.withinSourceCardinality(Cardinality.MANY_TO_ONE, 3));
        assertTrue(OntologyConformanceValidator.withinSourceCardinality(Cardinality.ONE_TO_MANY, 99));
        assertTrue(OntologyConformanceValidator.withinSourceCardinality(null, 99));
    }

    @Test
    void targetCardinalityBoundsAreEnforced() {
        assertFalse(OntologyConformanceValidator.withinTargetCardinality(Cardinality.ONE_TO_ONE, 2));
        assertTrue(OntologyConformanceValidator.withinTargetCardinality(Cardinality.ONE_TO_ONE, 1));
        assertTrue(OntologyConformanceValidator.withinTargetCardinality(Cardinality.ONE_TO_MANY, 1));
        assertFalse(OntologyConformanceValidator.withinTargetCardinality(Cardinality.ONE_TO_MANY, 3));
        assertTrue(OntologyConformanceValidator.withinTargetCardinality(Cardinality.MANY_TO_ONE, 99));
        assertTrue(OntologyConformanceValidator.withinTargetCardinality(null, 99));
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless conformance checks of entity/relationship data against an {@link OntologySchema}.
 *
 * <p>This is the reusable validation engine that lets the ontology actually <em>govern</em> data
 * rather than being a derived dead-end. It answers two questions:
 * <ul>
 *   <li>does an entity instance — a semantic type name plus a property map, which is exactly the
 *       shape of a knowledge-graph node's {@code metadataJson} — conform to the ontology? and</li>
 *   <li>is a directed relationship allowed by the ontology, and within its cardinality?</li>
 * </ul>
 *
 * <p>It reports <em>structural</em> conformance only (unknown type, field constraints,
 * relationship/cardinality). SpEL {@link ValidationRule}s remain the responsibility of
 * {@code ProcessEngineService.validateData}. The field-constraint logic in {@link #validateField}
 * is the single source of truth shared with the process engine's run-data validation, so the two
 * can never drift.
 *
 * <p>All type-name matching is case-insensitive and trimmed, because knowledge-graph
 * {@code entity_type} strings are free-form LLM output (e.g. {@code "person"}) while ontology
 * entity-type names are typically PascalCase (e.g. {@code "Person"}).
 */
public final class OntologyConformanceValidator {

    private OntologyConformanceValidator() {}

    /** Outcome of validating one entity instance against the ontology. */
    public record EntityConformance(String entityType, boolean conformant, boolean unknownType,
                                    List<String> violations) {
        public static EntityConformance ok(String type) {
            return new EntityConformance(type, true, false, List.of());
        }
    }

    /** Outcome of validating one directed relationship against the ontology. */
    public record RelationshipConformance(boolean allowed, Cardinality cardinality, String reason) {}

    /**
     * Validate an entity instance against the ontology: its type must be defined, and its
     * properties must satisfy the matching {@link EntityTypeDefinition}'s field constraints.
     *
     * @param schema     the ontology to validate against; {@code null} → trivially conformant
     * @param entityType the entity's semantic type (e.g. a graph node's {@code entity_type})
     * @param properties the entity's property map (e.g. a graph node's parsed metadata); may be null
     * @return the conformance outcome, never {@code null}
     */
    public static EntityConformance validateEntity(OntologySchema schema, String entityType,
                                                   Map<String, Object> properties) {
        if (schema == null || entityType == null) {
            return EntityConformance.ok(entityType);
        }
        EntityTypeDefinition def = findEntityType(schema, entityType);
        if (def == null) {
            return new EntityConformance(entityType, false, true,
                    List.of("Unknown entity type '" + entityType + "' — not defined in ontology '"
                            + schema.getName() + "' v" + schema.getVersion()));
        }
        List<String> violations = new ArrayList<>();
        if (def.getFields() != null) {
            Map<String, Object> props = properties != null ? properties : Map.of();
            for (FieldDefinition field : def.getFields()) {
                violations.addAll(validateField(field, props.get(field.getName())));
            }
        }
        return new EntityConformance(entityType, violations.isEmpty(), false, violations);
    }

    /**
     * Validate a directed relationship against the ontology's {@link RelationshipTypeDefinition}s.
     * A definition matches when its {@code type} equals {@code relationshipType} and its
     * source/target entity types match; a null/blank source or target on the definition is a wildcard.
     * When the ontology declares no relationship types it is treated as unconstrained (allowed).
     *
     * @return whether the relationship is allowed, plus the matched cardinality (if any)
     */
    public static RelationshipConformance validateRelationship(OntologySchema schema, String sourceType,
                                                               String relationshipType, String targetType) {
        if (schema == null || schema.getRelationshipTypes() == null
                || schema.getRelationshipTypes().isEmpty()) {
            return new RelationshipConformance(true, null, null);
        }
        for (RelationshipTypeDefinition rel : schema.getRelationshipTypes()) {
            if (eq(rel.getType(), relationshipType)
                    && wildcardOrEq(rel.getSourceEntityType(), sourceType)
                    && wildcardOrEq(rel.getTargetEntityType(), targetType)) {
                return new RelationshipConformance(true, rel.getCardinality(), null);
            }
        }
        return new RelationshipConformance(false, null,
                "Relationship '" + relationshipType + "' from '" + sourceType + "' to '" + targetType
                        + "' is not defined in ontology '" + schema.getName() + "'");
    }

    /**
     * Whether {@code outgoingCount} edges of a relationship leaving a single source entity are within
     * its cardinality. {@code ONE_TO_ONE}/{@code MANY_TO_ONE} cap a source at one target; the
     * {@code *_TO_MANY} cardinalities (and a {@code null} cardinality) are unbounded.
     */
    public static boolean withinSourceCardinality(Cardinality cardinality, long outgoingCount) {
        if (cardinality == null) return true;
        return switch (cardinality) {
            case ONE_TO_ONE, MANY_TO_ONE -> outgoingCount <= 1;
            case ONE_TO_MANY, MANY_TO_MANY -> true;
        };
    }

    /**
     * Whether {@code incomingCount} edges of a relationship entering a single target entity are within
     * its cardinality. {@code ONE_TO_ONE}/{@code ONE_TO_MANY} cap a target at one source; the
     * {@code MANY_TO_*} cardinalities (and a {@code null} cardinality) are unbounded.
     */
    public static boolean withinTargetCardinality(Cardinality cardinality, long incomingCount) {
        if (cardinality == null) return true;
        return switch (cardinality) {
            case ONE_TO_ONE, ONE_TO_MANY -> incomingCount <= 1;
            case MANY_TO_ONE, MANY_TO_MANY -> true;
        };
    }

    /**
     * Validate a single field's value against its {@link FieldDefinition}: required presence,
     * numeric min/max, regex, enum membership, and max length. Returns violation messages
     * (empty = valid).
     *
     * <p>This is shared verbatim with the process engine's run-data validation so a graph node and
     * a workflow run-data map are judged by identical rules.
     */
    public static List<String> validateField(FieldDefinition field, Object value) {
        List<String> violations = new ArrayList<>();
        String fieldName = field.getName();

        // Required check
        if (field.isRequired() && value == null) {
            violations.add(String.format("Required field '%s' is missing", fieldName));
            return violations; // no point checking further
        }

        if (value == null) return violations;

        // Min/max for numeric fields
        if (field.getMin() != null || field.getMax() != null) {
            try {
                double numVal = ((Number) value).doubleValue();
                if (field.getMin() != null && numVal < field.getMin()) {
                    violations.add(String.format("Field '%s' value %.2f is below minimum %.2f",
                            fieldName, numVal, field.getMin()));
                }
                if (field.getMax() != null && numVal > field.getMax()) {
                    violations.add(String.format("Field '%s' value %.2f exceeds maximum %.2f",
                            fieldName, numVal, field.getMax()));
                }
            } catch (ClassCastException e) {
                // Not a number — skip numeric checks
            }
        }

        // Regex for string fields
        if (field.getRegex() != null && value instanceof String) {
            if (!((String) value).matches(field.getRegex())) {
                violations.add(String.format("Field '%s' value '%s' does not match pattern '%s'",
                        fieldName, value, field.getRegex()));
            }
        }

        // Enum validation
        if (field.getEnumValues() != null && !field.getEnumValues().isEmpty()) {
            if (value instanceof String) {
                if (!field.getEnumValues().contains(value)) {
                    violations.add(String.format("Field '%s' value '%s' not in allowed values: %s",
                            fieldName, value, field.getEnumValues()));
                }
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (!field.getEnumValues().contains(String.valueOf(item))) {
                        violations.add(String.format("Field '%s' array item '%s' not in allowed values: %s",
                                fieldName, item, field.getEnumValues()));
                    }
                }
            }
        }

        // MaxLength for string fields
        if (field.getMaxLength() != null && value instanceof String) {
            if (((String) value).length() > field.getMaxLength()) {
                violations.add(String.format("Field '%s' length %d exceeds maximum %d",
                        fieldName, ((String) value).length(), field.getMaxLength()));
            }
        }

        return violations;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static EntityTypeDefinition findEntityType(OntologySchema schema, String entityType) {
        if (schema.getEntityTypes() == null) return null;
        for (EntityTypeDefinition def : schema.getEntityTypes()) {
            if (eq(def.getName(), entityType)) return def;
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean wildcardOrEq(String definitionType, String actual) {
        return definitionType == null || definitionType.isBlank() || eq(definitionType, actual);
    }
}

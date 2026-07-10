/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.ontology;

import ai.kompile.graph.reasoning.mebn.type.AttributeDefinition;
import ai.kompile.graph.reasoning.mebn.type.AttributeValueType;
import ai.kompile.graph.reasoning.mebn.type.TypeAttributeSchema;
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Bridges a declarative {@link OntologySchema} onto the graph-reasoning {@link TypeRegistry} /
 * {@link TypeHierarchy} so MEBN/SSBN grounding can navigate is-a (subsumption) using the schema's
 * declared {@link EntityTypeDefinition#getParentType() parent types}.
 *
 * <p>This is an infra-light <em>adapter</em>: it reads the app-side schema and produces the
 * generic, infra-free reasoning primitives. The schema supplies the is-a structure; entity
 * membership is computed by {@link TypeRegistry#buildFor(ReasoningGraph)} from the supplied graph.
 * Hand the resulting hierarchy to {@code SSBNGenerator.typeHierarchy(...)} to ground an RV declared
 * over a supertype across all of its subtypes' instances.</p>
 */
public final class OntologySchemaTypeRegistry {

    private OntologySchemaTypeRegistry() {
    }

    /**
     * Build a {@link TypeRegistry} from the schema's entity types, declared {@code parentType}
     * (subClassOf) links, fields, and relationship declarations. Types with no parent are still
     * declared so they appear as roots; self-referential parents are ignored.
     *
     * @param schema the ontology schema ({@code null} → empty registry)
     * @return a registry carrying schema declarations (membership is derived later)
     */
    public static TypeRegistry toRegistry(OntologySchema schema) {
        TypeRegistry registry = new TypeRegistry();
        if (schema == null || schema.getEntityTypes() == null) {
            return registry;
        }
        List<EntityTypeDefinition> types = schema.getEntityTypes();
        // First pass: declare every type, so a parent referenced before its own definition resolves.
        for (EntityTypeDefinition etd : types) {
            if (etd != null && etd.getName() != null) {
                registry.declare(etd.getName(), toAttributeSchema(etd.getFields()));
            }
        }
        // Second pass: wire is-a links.
        for (EntityTypeDefinition etd : types) {
            if (etd == null || etd.getName() == null) {
                continue;
            }
            String parent = etd.getParentType();
            if (parent != null && !parent.isBlank() && !parent.equalsIgnoreCase(etd.getName())) {
                registry.subtype(etd.getName(), parent);
            }
        }
        if (schema.getRelationshipTypes() != null) {
            for (RelationshipTypeDefinition rel : schema.getRelationshipTypes()) {
                addRelationshipConstraint(registry, rel);
            }
        }
        return registry;
    }

    /**
     * Build a {@link TypeHierarchy} from the schema's is-a declarations merged with entity
     * membership computed from {@code graph}.
     *
     * @param schema the ontology schema ({@code null} → hierarchy with computed membership only)
     * @param graph  the source graph providing entity membership (never {@code null})
     * @return a fully merged hierarchy, ready for {@code SSBNGenerator.typeHierarchy(...)}
     */
    public static TypeHierarchy toHierarchy(OntologySchema schema, ReasoningGraph graph) {
        return toRegistry(schema).buildFor(graph);
    }

    /**
     * As {@link #toHierarchy(OntologySchema, ReasoningGraph)}, but additionally folds OWL-RL inferred
     * type memberships ({@code typeName → entityIds}) into the hierarchy, so entities classified by
     * the OWL reasoner — not only those declared via {@code parentType} — drive subsumption grounding.
     *
     * @param schema                the ontology schema ({@code null} → membership-only hierarchy)
     * @param graph                 the source graph providing base membership (never {@code null})
     * @param inferredMembersByType OWL-inferred {@code typeName → entityIds}; may be empty
     * @return a fully merged hierarchy, ready for {@code SSBNGenerator.typeHierarchy(...)}
     */
    public static TypeHierarchy toHierarchy(OntologySchema schema, ReasoningGraph graph,
                                            Map<String, ? extends Collection<String>> inferredMembersByType) {
        return toRegistry(schema).buildFor(graph, inferredMembersByType);
    }

    private static TypeAttributeSchema toAttributeSchema(List<FieldDefinition> fields) {
        if (fields == null || fields.isEmpty()) {
            return TypeAttributeSchema.EMPTY;
        }
        TypeAttributeSchema.Builder builder = TypeAttributeSchema.builder();
        boolean hasFields = false;
        for (FieldDefinition field : fields) {
            if (field == null || !hasText(field.getName())) {
                continue;
            }
            AttributeDefinition.Builder attr = AttributeDefinition
                    .of(field.getName(), toAttributeValueType(field))
                    .required(field.isRequired())
                    .min(field.getMin())
                    .max(field.getMax());
            if (field.getEnumValues() != null) {
                attr.enumValues(field.getEnumValues());
            }
            String referenceType = referenceTargetType(field.getFkReference());
            if (referenceType != null) {
                attr.refersToType(referenceType);
            }
            builder.add(attr.build());
            hasFields = true;
        }
        return hasFields ? builder.build() : TypeAttributeSchema.EMPTY;
    }

    private static AttributeValueType toAttributeValueType(FieldDefinition field) {
        if (hasText(field.getFkReference())) {
            return AttributeValueType.REFERENCE;
        }
        FieldType type = field.getType();
        if (type == null) {
            return AttributeValueType.STRING;
        }
        return switch (type) {
            case INTEGER, DECIMAL -> AttributeValueType.NUMBER;
            case BOOLEAN -> AttributeValueType.BOOLEAN;
            case ENUM, ENUM_ARRAY -> AttributeValueType.ENUM;
            case STRING, DATE, DATETIME, MAP -> AttributeValueType.STRING;
        };
    }

    private static String referenceTargetType(String fkReference) {
        if (!hasText(fkReference)) {
            return null;
        }
        String trimmed = fkReference.trim();
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private static void addRelationshipConstraint(TypeRegistry registry, RelationshipTypeDefinition rel) {
        if (rel == null
                || !hasText(rel.getType())
                || !hasText(rel.getSourceEntityType())
                || !hasText(rel.getTargetEntityType())) {
            return;
        }
        registry.constraint(rel.getSourceEntityType(), new TypeConstraint.RelationConstraint(
                rel.getType(), rel.getSourceEntityType(), rel.getTargetEntityType()));
        if (rel.getCardinality() != null) {
            registry.constraint(rel.getSourceEntityType(), new TypeConstraint.CardinalityConstraint(
                    rel.getType(), TypeConstraint.Cardinality.valueOf(rel.getCardinality().name())));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

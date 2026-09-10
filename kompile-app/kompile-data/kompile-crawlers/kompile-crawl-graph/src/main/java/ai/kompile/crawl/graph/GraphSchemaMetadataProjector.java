/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Projects transient schema hierarchy metadata onto facts without changing their concrete types. */
final class GraphSchemaMetadataProjector {

    static final String ENTITY_PARENT_TYPE = "schema.parentType";
    static final String ENTITY_TYPE_ANCESTORS = "schema.typeAncestors";
    static final String RELATION_CONNECTION_FAMILY = "schema.connectionFamily";
    static final String SCHEMA_FINGERPRINT = "schema.fingerprint";
    private static final String LEGACY_ENTITY_PARENT_TYPE = "schema_parent_type";
    private static final String LEGACY_ENTITY_TYPE_ANCESTORS = "schema_type_ancestors";
    private static final String LEGACY_RELATION_CONNECTION_FAMILY = "schema_connection_family";
    private static final String LEGACY_SCHEMA_FINGERPRINT = "schema_fingerprint";

    private GraphSchemaMetadataProjector() {
    }

    static boolean isReservedKey(String key) {
        return ENTITY_PARENT_TYPE.equals(key)
                || ENTITY_TYPE_ANCESTORS.equals(key)
                || RELATION_CONNECTION_FAMILY.equals(key)
                || SCHEMA_FINGERPRINT.equals(key)
                || LEGACY_ENTITY_PARENT_TYPE.equals(key)
                || LEGACY_ENTITY_TYPE_ANCESTORS.equals(key)
                || LEGACY_RELATION_CONNECTION_FAMILY.equals(key)
                || LEGACY_SCHEMA_FINGERPRINT.equals(key);
    }

    static void project(Graph graph, GraphSchema schema) {
        project(graph, schema, schemaFingerprint(schema));
    }

    /** Projects one already-computed schema fingerprint across a graph batch. */
    static void project(Graph graph, GraphSchema schema, String fingerprint) {
        if (graph == null) return;
        String effectiveFingerprint = schema != null && hasText(fingerprint)
                ? fingerprint : schemaFingerprint(schema);
        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                if (entity == null) continue;
                Map<String, Object> metadata = mutable(entity.getMetadata());
                applyEntityMetadata(metadata, schema, entity.getType(), effectiveFingerprint);
                entity.setMetadata(metadata.isEmpty() ? null : metadata);
            }
        }
        if (graph.getRelationships() != null) {
            for (Relationship relationship : graph.getRelationships()) {
                if (relationship == null) continue;
                Map<String, Object> metadata = mutable(relationship.getMetadata());
                applyRelationshipMetadata(metadata, schema, relationship.getType(), effectiveFingerprint);
                relationship.setMetadata(metadata.isEmpty() ? null : metadata);
            }
        }
    }

    static String schemaFingerprint(GraphSchema schema) {
        return schema == null ? null : CrawlOntology.contentFingerprint(schema);
    }

    static void applyEntityMetadata(
            Map<String, Object> metadata, GraphSchema schema, String entityType) {
        applyEntityMetadata(metadata, schema, entityType, schemaFingerprint(schema));
    }

    static void applyEntityMetadata(
            Map<String, Object> metadata, GraphSchema schema, String entityType,
            String fingerprint) {
        if (metadata == null) return;
        removeReservedEntityMetadata(metadata);
        if (schema == null || !hasText(entityType)) return;
        if (hasText(fingerprint)) {
            metadata.put(SCHEMA_FINGERPRINT, fingerprint);
        }
        String parent = schema.getNodeParentTypes().get(canonical(entityType));
        if (hasText(parent)) {
            metadata.put(ENTITY_PARENT_TYPE, parent);
        }
        java.util.List<String> ancestors = schema.getNodeTypeAncestors(entityType);
        if (!ancestors.isEmpty()) {
            metadata.put(ENTITY_TYPE_ANCESTORS, ancestors);
        }
    }

    static void applyRelationshipMetadata(
            Map<String, Object> metadata, GraphSchema schema, String relationshipType) {
        applyRelationshipMetadata(metadata, schema, relationshipType, schemaFingerprint(schema));
    }

    static void applyRelationshipMetadata(
            Map<String, Object> metadata, GraphSchema schema, String relationshipType,
            String fingerprint) {
        if (metadata == null) return;
        removeReservedRelationshipMetadata(metadata);
        if (schema == null || !hasText(relationshipType)) return;
        if (hasText(fingerprint)) {
            metadata.put(SCHEMA_FINGERPRINT, fingerprint);
        }
        String family = schema.getRelationshipConnectionFamilies().get(canonical(relationshipType));
        if (hasText(family)) {
            metadata.put(RELATION_CONNECTION_FAMILY, family);
        }
    }

    private static Map<String, Object> mutable(Map<String, Object> metadata) {
        return metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    private static void removeReservedEntityMetadata(Map<String, Object> metadata) {
        metadata.remove(ENTITY_PARENT_TYPE);
        metadata.remove(ENTITY_TYPE_ANCESTORS);
        metadata.remove(LEGACY_ENTITY_PARENT_TYPE);
        metadata.remove(LEGACY_ENTITY_TYPE_ANCESTORS);
        metadata.remove(SCHEMA_FINGERPRINT);
        metadata.remove(LEGACY_SCHEMA_FINGERPRINT);
    }

    private static void removeReservedRelationshipMetadata(Map<String, Object> metadata) {
        metadata.remove(RELATION_CONNECTION_FAMILY);
        metadata.remove(LEGACY_RELATION_CONNECTION_FAMILY);
        metadata.remove(SCHEMA_FINGERPRINT);
        metadata.remove(LEGACY_SCHEMA_FINGERPRINT);
    }

    private static String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

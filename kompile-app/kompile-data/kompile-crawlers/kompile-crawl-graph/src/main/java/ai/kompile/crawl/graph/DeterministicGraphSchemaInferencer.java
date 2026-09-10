/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Projects the types already emitted by source-native and deterministic crawl extractors into a
 * reusable graph schema. The extractor output is the source of the vocabulary; this class does not
 * seed domain-specific labels of its own.
 */
final class DeterministicGraphSchemaInferencer {

    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private DeterministicGraphSchemaInferencer() {
    }

    static GraphSchema infer(Graph graph) {
        if (graph == null) {
            return null;
        }

        Map<String, String> entityTypesById = new TreeMap<>();
        Map<String, NodeType> nodeTypes = new TreeMap<>();
        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                String type = entity == null ? null : schemaName(entity.getType());
                if (entity == null || !hasText(entity.getId()) || type == null) {
                    continue;
                }
                entityTypesById.putIfAbsent(entity.getId(), type);
                nodeTypes.putIfAbsent(type, new NodeType(
                        type,
                        "Type emitted by deterministic or source-native crawl extraction.",
                        null,
                        SchemaHierarchyVocabulary.parentForGeneratedType(type)));
            }
        }

        Map<String, RelationshipType> relationshipTypes = new TreeMap<>();
        Set<String> patterns = new TreeSet<>();
        if (graph.getRelationships() != null) {
            for (Relationship relationship : graph.getRelationships()) {
                if (relationship == null) {
                    continue;
                }
                String type = schemaName(relationship.getType());
                String sourceType = entityTypesById.get(relationship.getSource());
                String targetType = entityTypesById.get(relationship.getTarget());
                if (type == null || sourceType == null || targetType == null) {
                    continue;
                }
                String connectionFamily =
                        SchemaHierarchyVocabulary.connectionFamilyForPredicate(type);
                if (connectionFamily == null) {
                    continue;
                }
                relationshipTypes.putIfAbsent(type, new RelationshipType(
                        type,
                        "Relationship emitted by deterministic or source-native crawl extraction.",
                        null,
                        List.of(),
                        connectionFamily));
                patterns.add("(" + sourceType + ")-[:" + type + "]->(" + targetType + ")");
            }
        }

        if (nodeTypes.isEmpty() && relationshipTypes.isEmpty() && patterns.isEmpty()) {
            return null;
        }
        return new GraphSchema(
                nodeTypes.isEmpty() ? null : List.copyOf(nodeTypes.values()),
                relationshipTypes.isEmpty() ? null : List.copyOf(relationshipTypes.values()),
                patterns.isEmpty() ? null : List.copyOf(patterns));
    }

    private static String schemaName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
        return SCHEMA_NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

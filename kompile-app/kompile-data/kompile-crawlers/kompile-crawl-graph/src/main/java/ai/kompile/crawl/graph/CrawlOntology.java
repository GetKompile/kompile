/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One mutable, crawl-scoped ontology shared by every extraction window.
 *
 * <p>The corpus prepass supplies the initial schema. Model-proposed updates are validated and merged
 * additively so concurrent windows can discover reusable node and relationship types without deleting
 * or redefining definitions that earlier windows already used.</p>
 */
public final class CrawlOntology {

    public record UpdateResult(
            boolean valid,
            boolean updated,
            long revision,
            GraphSchema schema,
            List<String> errors) {

        public UpdateResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private volatile GraphSchema current;
    private volatile long revision;

    public CrawlOntology(GraphSchema initialSchema) {
        this.current = copySchema(initialSchema);
        this.revision = hasDefinitions(this.current) ? 1L : 0L;
    }

    /** Returns a defensive snapshot suitable for prompt construction and validation. */
    public GraphSchema snapshot() {
        return copySchema(current);
    }

    public long revision() {
        return revision;
    }

    /**
     * Validates and additively applies an ontology overlay.
     *
     * <p>Existing descriptions and property contracts remain authoritative. Missing details may be
     * filled, relationship aliases are unioned, and new directed endpoint patterns are appended.</p>
     */
    public synchronized UpdateResult update(GraphSchema overlay) {
        GraphSchema before = current;
        CorpusSchemaOverlayValidator.Result validation =
                CorpusSchemaOverlayValidator.validate(before, overlay);
        if (!validation.valid()) {
            return new UpdateResult(
                    false, false, revision, copySchema(before), validation.errors());
        }

        GraphSchema merged = merge(before, overlay);
        boolean changed = !Objects.equals(before, merged);
        if (changed) {
            current = merged;
            revision++;
        }
        return new UpdateResult(
                true, changed, revision, copySchema(current), List.of());
    }

    static GraphSchema merge(GraphSchema established, GraphSchema additions) {
        Map<String, NodeType> nodes = new LinkedHashMap<>();
        addNodes(nodes, established == null ? null : established.getNodeTypes(), false);
        addNodes(nodes, additions == null ? null : additions.getNodeTypes(), true);

        Map<String, RelationshipType> relationships = new LinkedHashMap<>();
        addRelationships(
                relationships,
                established == null ? null : established.getRelationshipTypes(),
                false);
        addRelationships(
                relationships,
                additions == null ? null : additions.getRelationshipTypes(),
                true);

        Map<String, String> patterns = new LinkedHashMap<>();
        addTextValues(patterns, established == null ? null : established.getPatterns());
        addTextValues(patterns, additions == null ? null : additions.getPatterns());

        return new GraphSchema(
                nodes.isEmpty() ? null : List.copyOf(nodes.values()),
                relationships.isEmpty() ? null : List.copyOf(relationships.values()),
                patterns.isEmpty() ? null : List.copyOf(patterns.values()));
    }

    private static void addNodes(
            Map<String, NodeType> target, List<NodeType> values, boolean mergeDetails) {
        if (values == null) {
            return;
        }
        for (NodeType candidate : values) {
            if (candidate == null || !hasText(candidate.getLabel())) {
                continue;
            }
            String key = canonical(candidate.getLabel());
            NodeType existing = target.get(key);
            if (existing == null) {
                target.put(key, copyNode(candidate));
            } else if (mergeDetails) {
                target.put(key, new NodeType(
                        existing.getLabel(),
                        hasText(existing.getDescription())
                                ? existing.getDescription() : candidate.getDescription(),
                        mergeProperties(existing.getProperties(), candidate.getProperties())));
            }
        }
    }

    private static void addRelationships(
            Map<String, RelationshipType> target,
            List<RelationshipType> values,
            boolean mergeDetails) {
        if (values == null) {
            return;
        }
        for (RelationshipType candidate : values) {
            if (candidate == null || !hasText(candidate.getType())) {
                continue;
            }
            String key = canonical(candidate.getType());
            RelationshipType existing = target.get(key);
            if (existing == null) {
                target.put(key, copyRelationship(candidate));
            } else if (mergeDetails) {
                target.put(key, new RelationshipType(
                        existing.getType(),
                        hasText(existing.getDescription())
                                ? existing.getDescription() : candidate.getDescription(),
                        mergeProperties(existing.getProperties(), candidate.getProperties()),
                        mergeTextValues(existing.getAliases(), candidate.getAliases())));
            }
        }
    }

    private static List<PropertyType> mergeProperties(
            List<PropertyType> established, List<PropertyType> additions) {
        Map<String, PropertyType> merged = new LinkedHashMap<>();
        addProperties(merged, established);
        addProperties(merged, additions);
        return merged.isEmpty() ? null : List.copyOf(merged.values());
    }

    private static void addProperties(
            Map<String, PropertyType> target, List<PropertyType> values) {
        if (values == null) {
            return;
        }
        for (PropertyType property : values) {
            if (property != null && hasText(property.getName())) {
                target.putIfAbsent(
                        canonical(property.getName()),
                        new PropertyType(property.getName(), property.getType()));
            }
        }
    }

    private static List<String> mergeTextValues(List<String> first, List<String> second) {
        Map<String, String> merged = new LinkedHashMap<>();
        addTextValues(merged, first);
        addTextValues(merged, second);
        return merged.isEmpty() ? List.of() : List.copyOf(merged.values());
    }

    private static void addTextValues(Map<String, String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (hasText(value)) {
                String trimmed = value.trim();
                target.putIfAbsent(canonical(trimmed), trimmed);
            }
        }
    }

    private static GraphSchema copySchema(GraphSchema schema) {
        if (schema == null) {
            return new GraphSchema(null, null, null);
        }
        List<NodeType> nodes = schema.getNodeTypes() == null
                ? null
                : schema.getNodeTypes().stream()
                        .filter(Objects::nonNull)
                        .map(CrawlOntology::copyNode)
                        .toList();
        List<RelationshipType> relationships = schema.getRelationshipTypes() == null
                ? null
                : schema.getRelationshipTypes().stream()
                        .filter(Objects::nonNull)
                        .map(CrawlOntology::copyRelationship)
                        .toList();
        List<String> patterns = schema.getPatterns() == null
                ? null
                : schema.getPatterns().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .toList();
        return new GraphSchema(nodes, relationships, patterns);
    }

    private static NodeType copyNode(NodeType source) {
        return new NodeType(
                source.getLabel(),
                source.getDescription(),
                copyProperties(source.getProperties()));
    }

    private static RelationshipType copyRelationship(RelationshipType source) {
        return new RelationshipType(
                source.getType(),
                source.getDescription(),
                copyProperties(source.getProperties()),
                source.getAliases() == null ? List.of() : List.copyOf(source.getAliases()));
    }

    private static List<PropertyType> copyProperties(List<PropertyType> values) {
        if (values == null) {
            return null;
        }
        List<PropertyType> copy = new ArrayList<>();
        for (PropertyType value : values) {
            if (value != null) {
                copy.add(new PropertyType(value.getName(), value.getType()));
            }
        }
        return copy.isEmpty() ? null : List.copyOf(copy);
    }

    private static boolean hasDefinitions(GraphSchema schema) {
        return schema != null
                && ((schema.getNodeTypes() != null && !schema.getNodeTypes().isEmpty())
                || (schema.getRelationshipTypes() != null
                        && !schema.getRelationshipTypes().isEmpty())
                || (schema.getPatterns() != null && !schema.getPatterns().isEmpty()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}

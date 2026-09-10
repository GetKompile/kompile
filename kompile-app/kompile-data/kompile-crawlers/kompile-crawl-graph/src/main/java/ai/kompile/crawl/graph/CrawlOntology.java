/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Comparator;
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

    private static final ObjectMapper FINGERPRINT_MAPPER = JsonUtils.newStandardMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

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
        GraphSchema canonical = canonicalize(initialSchema);
        this.current = canonical == null ? copySchema(null) : canonical;
        this.revision = hasDefinitions(this.current) ? 1L : 0L;
    }

    /** Returns a defensive snapshot suitable for prompt construction and validation. */
    public GraphSchema snapshot() {
        return copySchema(current);
    }

    public long revision() {
        return revision;
    }

    /** Returns a stable content hash for the current frozen schema snapshot. */
    public String contentFingerprint() {
        return contentFingerprint(current);
    }

    /**
     * Returns a stable SHA-256 hash of a schema's canonical JSON representation.
     * List order is treated as non-semantic, while null and empty vocabulary fields remain distinct.
     */
    public static String contentFingerprint(GraphSchema schema) {
        if (schema == null) {
            return null;
        }
        try {
            return HashUtils.sha256Hex(FINGERPRINT_MAPPER.writeValueAsBytes(canonicalize(schema)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint graph schema", exception);
        }
    }

    /**
     * Validates and additively applies an ontology overlay.
     *
     * <p>Existing descriptions and property contracts remain authoritative. Missing details may be
     * filled, relationship aliases are unioned, and new directed endpoint patterns are appended.</p>
     */
    public synchronized UpdateResult update(GraphSchema overlay) {
        return update(overlay, false);
    }

    synchronized UpdateResult updateTypesOnly(GraphSchema overlay) {
        return update(overlay, true);
    }

    private UpdateResult update(GraphSchema overlay, boolean typesOnly) {
        GraphSchema before = current;
        CorpusSchemaOverlayValidator.Result validation =
                typesOnly
                        ? CorpusSchemaOverlayValidator.validateTypesOnly(before, overlay)
                        : CorpusSchemaOverlayValidator.validate(before, overlay);
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
                declaredNodes(established, additions)
                        ? sortedNodes(nodes.values()) : null,
                declaredRelationships(established, additions)
                        ? sortedRelationships(relationships.values()) : null,
                declaredPatterns(established, additions)
                        ? sortedStrings(patterns.values()) : null);
    }

    /** Returns a defensive, deterministically ordered schema without changing any source object. */
    static GraphSchema canonicalize(GraphSchema schema) {
        if (schema == null) {
            return null;
        }
        GraphSchema copy = copySchema(schema);
        return new GraphSchema(
                copy.getNodeTypes() == null ? null : sortedNodes(copy.getNodeTypes()),
                copy.getRelationshipTypes() == null ? null : sortedRelationships(copy.getRelationshipTypes()),
                copy.getPatterns() == null ? null : sortedStrings(copy.getPatterns()));
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
                        mergeProperties(existing.getProperties(), candidate.getProperties()),
                        hasText(existing.getParentType())
                                ? existing.getParentType() : candidate.getParentType()));
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
                        mergeTextValues(existing.getAliases(), candidate.getAliases()),
                        hasText(existing.getConnectionFamily())
                                ? existing.getConnectionFamily() : candidate.getConnectionFamily()));
            }
        }
    }

    private static List<PropertyType> mergeProperties(
            List<PropertyType> established, List<PropertyType> additions) {
        Map<String, PropertyType> merged = new LinkedHashMap<>();
        addProperties(merged, established);
        addProperties(merged, additions);
        if (merged.isEmpty()) {
            return (established != null || additions != null) ? List.of() : null;
        }
        List<PropertyType> values = new ArrayList<>(merged.values());
        values.sort(CrawlOntology::compareProperties);
        return List.copyOf(values);
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
        return merged.isEmpty() ? List.of() : sortedStrings(merged.values());
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
                copyProperties(source.getProperties()),
                source.getParentType());
    }

    private static RelationshipType copyRelationship(RelationshipType source) {
        return new RelationshipType(
                source.getType(),
                source.getDescription(),
                copyProperties(source.getProperties()),
                source.getAliases() == null
                        ? List.of()
                        : source.getAliases().stream()
                                .filter(Objects::nonNull)
                                .sorted()
                                .toList(),
                source.getConnectionFamily());
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
        copy.sort(CrawlOntology::compareProperties);
        return List.copyOf(copy);
    }

    private static boolean declaredNodes(GraphSchema established, GraphSchema additions) {
        return (established != null && established.getNodeTypes() != null)
                || (additions != null && additions.getNodeTypes() != null);
    }

    private static boolean declaredRelationships(GraphSchema established, GraphSchema additions) {
        return (established != null && established.getRelationshipTypes() != null)
                || (additions != null && additions.getRelationshipTypes() != null);
    }

    private static boolean declaredPatterns(GraphSchema established, GraphSchema additions) {
        return (established != null && established.getPatterns() != null)
                || (additions != null && additions.getPatterns() != null);
    }

    private static List<NodeType> sortedNodes(Iterable<NodeType> values) {
        List<NodeType> sorted = new ArrayList<>();
        values.forEach(value -> sorted.add(copyNode(value)));
        sorted.sort(CrawlOntology::compareNodes);
        return List.copyOf(sorted);
    }

    private static List<RelationshipType> sortedRelationships(Iterable<RelationshipType> values) {
        List<RelationshipType> sorted = new ArrayList<>();
        values.forEach(value -> sorted.add(copyRelationship(value)));
        sorted.sort(CrawlOntology::compareRelationships);
        return List.copyOf(sorted);
    }

    private static List<String> sortedStrings(Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        values.forEach(value -> sorted.add(value));
        sorted.sort(Comparator.nullsFirst(String::compareTo));
        return List.copyOf(sorted);
    }

    private static int compareNodes(NodeType left, NodeType right) {
        int comparison = compareStrings(left.getLabel(), right.getLabel());
        if (comparison != 0) return comparison;
        comparison = compareStrings(left.getParentType(), right.getParentType());
        if (comparison != 0) return comparison;
        comparison = compareStrings(left.getDescription(), right.getDescription());
        if (comparison != 0) return comparison;
        return compareProperties(left.getProperties(), right.getProperties());
    }

    private static int compareRelationships(RelationshipType left, RelationshipType right) {
        int comparison = compareStrings(left.getType(), right.getType());
        if (comparison != 0) return comparison;
        comparison = compareStrings(left.getConnectionFamily(), right.getConnectionFamily());
        if (comparison != 0) return comparison;
        comparison = compareStrings(left.getDescription(), right.getDescription());
        if (comparison != 0) return comparison;
        comparison = compareProperties(left.getProperties(), right.getProperties());
        if (comparison != 0) return comparison;
        return compareStrings(left.getAliases(), right.getAliases());
    }

    private static int compareProperties(List<PropertyType> left, List<PropertyType> right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        int comparison = Integer.compare(left.size(), right.size());
        for (int i = 0; comparison == 0 && i < left.size(); i++) {
            comparison = compareProperties(left.get(i), right.get(i));
        }
        return comparison;
    }

    private static int compareProperties(PropertyType left, PropertyType right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        int comparison = compareStrings(left.getName(), right.getName());
        return comparison != 0 ? comparison : compareStrings(left.getType(), right.getType());
    }

    private static int compareStrings(List<String> left, List<String> right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        int comparison = Integer.compare(left.size(), right.size());
        for (int i = 0; comparison == 0 && i < left.size(); i++) {
            comparison = compareStrings(left.get(i), right.get(i));
        }
        return comparison;
    }

    private static int compareStrings(String left, String right) {
        return Comparator.nullsFirst(String::compareTo).compare(left, right);
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

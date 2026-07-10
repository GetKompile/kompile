/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.discovery;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalizes graph relations against lightweight relation schemas.
 *
 * <p>This is deliberately smaller than an OWL reasoner: callers can provide the relation signatures
 * they care about, and this utility emits normalized relation views when an observed edge already
 * has the right type but reversed endpoints, or when an alias/inverse property should be projected
 * to one canonical relation type. The original graph is never mutated.</p>
 */
public final class RelationNormalizer {

    private RelationNormalizer() {
    }

    public enum Action {
        CANONICAL_TYPE,
        FLIPPED_DIRECTION,
        ALREADY_CANONICAL
    }

    public record RelationSchema(String name,
                                 Set<String> observedTypes,
                                 String canonicalType,
                                 Set<String> sourceTypes,
                                 Set<String> targetTypes,
                                 boolean flipWhenSwapped,
                                 boolean emitAlreadyCanonical) {
        public RelationSchema {
            name = name == null || name.isBlank() ? canonicalType : name;
            observedTypes = normalizeSet(observedTypes);
            canonicalType = canonicalType == null || canonicalType.isBlank() ? "" : canonicalType.trim();
            sourceTypes = normalizeSet(sourceTypes);
            targetTypes = normalizeSet(targetTypes);
            if (observedTypes.isEmpty() && !canonicalType.isBlank()) {
                observedTypes = Set.of(normalizeToken(canonicalType));
            }
        }

        public static RelationSchema of(String canonicalType,
                                        Collection<String> sourceTypes,
                                        Collection<String> targetTypes,
                                        String... observedTypes) {
            return new RelationSchema(canonicalType,
                    observedTypes == null || observedTypes.length == 0
                            ? Set.of(canonicalType)
                            : Set.of(observedTypes),
                    canonicalType,
                    asSet(sourceTypes),
                    asSet(targetTypes),
                    true,
                    false);
        }

        public RelationSchema emitAlreadyCanonical(boolean emitAlreadyCanonical) {
            return new RelationSchema(name, observedTypes, canonicalType, sourceTypes, targetTypes,
                    flipWhenSwapped, emitAlreadyCanonical);
        }

        public RelationSchema flipWhenSwapped(boolean flipWhenSwapped) {
            return new RelationSchema(name, observedTypes, canonicalType, sourceTypes, targetTypes,
                    flipWhenSwapped, emitAlreadyCanonical);
        }
    }

    public record NormalizedRelation(GraphRelation original,
                                     GraphRelation relation,
                                     RelationSchema schema,
                                     Action action) {
    }

    public record Result(List<NormalizedRelation> normalizations) {
        public Result {
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }

        public List<GraphRelation> relations() {
            return normalizations.stream().map(NormalizedRelation::relation).toList();
        }

        public long flippedCount() {
            return normalizations.stream().filter(n -> n.action() == Action.FLIPPED_DIRECTION).count();
        }
    }

    public static Result normalize(ReasoningGraph graph, Collection<RelationSchema> schemas) {
        Objects.requireNonNull(graph, "graph");
        if (schemas == null || schemas.isEmpty()) {
            return new Result(List.of());
        }

        Map<String, GraphEntity> entities = graph.entities().stream()
                .collect(Collectors.toMap(GraphEntity::id, e -> e, (a, b) -> a, LinkedHashMap::new));
        List<RelationSchema> schemaList = schemas.stream()
                .filter(Objects::nonNull)
                .toList();
        Set<String> existing = graph.relations().stream()
                .map(r -> relationKey(r.type(), r.sourceId(), r.targetId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<NormalizedRelation> out = new ArrayList<>();

        for (GraphRelation relation : graph.relations()) {
            for (RelationSchema schema : schemaList) {
                if (!schema.observedTypes().contains(normalizeToken(relation.type()))) {
                    continue;
                }
                GraphEntity source = entities.get(relation.sourceId());
                GraphEntity target = entities.get(relation.targetId());
                boolean direct = matches(source, schema.sourceTypes()) && matches(target, schema.targetTypes());
                boolean swapped = matches(source, schema.targetTypes()) && matches(target, schema.sourceTypes());

                if (direct) {
                    boolean typeChange = !normalizeToken(relation.type()).equals(normalizeToken(schema.canonicalType()));
                    if (typeChange || schema.emitAlreadyCanonical()) {
                        add(out, existing, relation, schema, relation.sourceId(), relation.targetId(),
                                typeChange ? Action.CANONICAL_TYPE : Action.ALREADY_CANONICAL);
                    }
                } else if (schema.flipWhenSwapped() && swapped) {
                    add(out, existing, relation, schema, relation.targetId(), relation.sourceId(),
                            Action.FLIPPED_DIRECTION);
                }
            }
        }
        out.sort(Comparator.comparing(n -> n.relation().id()));
        return new Result(out);
    }

    private static void add(List<NormalizedRelation> out,
                            Set<String> existing,
                            GraphRelation original,
                            RelationSchema schema,
                            String sourceId,
                            String targetId,
                            Action action) {
        String key = relationKey(schema.canonicalType(), sourceId, targetId);
        if (!existing.add(key)) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>(original.attributes());
        attrs.put("normalizedRelation", true);
        attrs.put("normalizationAction", action.name());
        attrs.put("normalizedFromRelationId", original.id());
        attrs.put("originalRelationType", original.type());
        attrs.put("canonicalRelationType", schema.canonicalType());
        attrs.put("relationSchema", schema.name());
        GraphRelation normalized = GraphRelation.builder("normalized:" + sanitize(schema.canonicalType())
                        + ":" + sanitize(sourceId) + ":" + sanitize(targetId) + ":" + sanitize(original.id()),
                        sourceId, targetId)
                .type(schema.canonicalType())
                .weight(original.weight())
                .confidence(original.confidence())
                .directed(true)
                .tags(mergeTags(original.tags(), action))
                .embedding(original.embedding())
                .timestamp(original.timestamp())
                .attributes(attrs)
                .build();
        out.add(new NormalizedRelation(original, normalized, schema, action));
    }

    private static Set<String> mergeTags(Set<String> originalTags, Action action) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (originalTags != null) {
            tags.addAll(originalTags);
        }
        tags.add("normalized");
        tags.add("relation-normalization");
        tags.add(action.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        return tags;
    }

    private static boolean matches(GraphEntity entity, Set<String> expectedTypes) {
        if (entity == null || expectedTypes.isEmpty()) {
            return false;
        }
        for (String type : entity.typeMemberships()) {
            if (expectedTypes.contains(normalizeToken(type))) {
                return true;
            }
        }
        return false;
    }

    private static String relationKey(String type, String sourceId, String targetId) {
        return normalizeToken(type) + "\u0000" + sourceId + "\u0000" + targetId;
    }

    private static Set<String> normalizeSet(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizeToken(value);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> asSet(Collection<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    private static String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        int iriHash = Math.max(value.lastIndexOf('#'), value.lastIndexOf('/'));
        if (iriHash >= 0 && iriHash + 1 < value.length()) {
            value = value.substring(iriHash + 1);
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String sanitize(String raw) {
        String normalized = normalizeToken(raw).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "unknown" : normalized;
    }
}

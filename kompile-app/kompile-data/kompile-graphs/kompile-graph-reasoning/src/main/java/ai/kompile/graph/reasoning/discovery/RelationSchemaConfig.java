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

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** JSON config for graph-only relation normalization schemas. */
public record RelationSchemaConfig(List<RelationNormalizer.RelationSchema> schemas) {

    public static final String DEFAULT_ARTIFACT = "relation-normalization-schemas.json";

    public RelationSchemaConfig {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
    }

    public Optional<RelationNormalizer.RelationSchema> schema(String canonicalType) {
        if (canonicalType == null || canonicalType.isBlank()) {
            return Optional.empty();
        }
        for (RelationNormalizer.RelationSchema schema : schemas) {
            if (canonicalType.equalsIgnoreCase(schema.canonicalType())) {
                return Optional.of(schema);
            }
        }
        return Optional.empty();
    }

    public RelationNormalizer.Result normalize(UnifiedGraph graph) {
        return RelationNormalizer.normalize(graph, schemas);
    }

    public RelationNormalizer.Result materialize(UnifiedGraph graph) {
        RelationNormalizer.Result result = normalize(graph);
        for (GraphRelation relation : result.relations()) {
            graph.addRelation(relation);
        }
        return result;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        List<Object> jsonSchemas = new ArrayList<>();
        for (RelationNormalizer.RelationSchema schema : schemas) {
            jsonSchemas.add(toMap(schema));
        }
        root.put("schemas", jsonSchemas);
        return MiniJson.write(root);
    }

    public void putArtifact(UnifiedGraph graph) {
        putArtifact(graph, DEFAULT_ARTIFACT);
    }

    public void putArtifact(UnifiedGraph graph, String artifactName) {
        Objects.requireNonNull(graph, "graph");
        graph.putArtifactText(artifactName, toJson());
    }

    public static RelationSchemaConfig fromArtifact(UnifiedGraph graph) {
        return fromArtifact(graph, DEFAULT_ARTIFACT);
    }

    public static RelationSchemaConfig fromArtifact(UnifiedGraph graph, String artifactName) {
        Objects.requireNonNull(graph, "graph");
        String json = graph.artifactText(artifactName);
        if (json == null || json.isBlank()) {
            return new RelationSchemaConfig(List.of());
        }
        return fromJson(json);
    }

    public static RelationSchemaConfig fromJson(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        Object schemasValue = root.get("schemas");
        List<RelationNormalizer.RelationSchema> schemas = new ArrayList<>();
        if (schemasValue instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> schemaMap) {
                    schemas.add(fromMap(schemaMap));
                }
            }
        } else if (root.containsKey("canonicalType")) {
            schemas.add(fromMap(root));
        }
        return new RelationSchemaConfig(schemas);
    }

    private static RelationNormalizer.RelationSchema fromMap(Map<?, ?> raw) {
        String canonicalType = string(raw, "canonicalType", string(raw, "type", string(raw, "name", null)));
        String name = string(raw, "name", canonicalType);
        return new RelationNormalizer.RelationSchema(
                name,
                strings(raw.get("observedTypes")),
                canonicalType,
                strings(raw.get("sourceTypes")),
                strings(raw.get("targetTypes")),
                bool(raw, "flipWhenSwapped", true),
                bool(raw, "emitAlreadyCanonical", false));
    }

    private static Map<String, Object> toMap(RelationNormalizer.RelationSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", schema.name());
        out.put("canonicalType", schema.canonicalType());
        out.put("observedTypes", new ArrayList<>(schema.observedTypes()));
        out.put("sourceTypes", new ArrayList<>(schema.sourceTypes()));
        out.put("targetTypes", new ArrayList<>(schema.targetTypes()));
        out.put("flipWhenSwapped", schema.flipWhenSwapped());
        out.put("emitAlreadyCanonical", schema.emitAlreadyCanonical());
        return out;
    }

    private static String string(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof String s) {
            addDelimited(out, s);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    addDelimited(out, String.valueOf(item));
                }
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    addDelimited(out, String.valueOf(item));
                }
            }
        }
        out.removeIf(s -> s == null || s.isBlank());
        return out;
    }

    private static void addDelimited(Set<String> out, String raw) {
        if (raw == null) {
            return;
        }
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
    }
}

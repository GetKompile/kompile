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

import ai.kompile.graph.reasoning.discovery.RelationNormalizer;
import ai.kompile.graph.reasoning.discovery.RelationSchemaConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles ontology relationship declarations into graph-reasoning relation schemas. */
public final class OntologyRelationSchemaCompiler {

    private OntologyRelationSchemaCompiler() {
    }

    /** Process-discovery semantics compiled from ontology relationship declarations. */
    public record ProcessSemanticProfile(String relationType,
                                         String canonicalType,
                                         Set<String> observedTypes,
                                         Set<String> actionCategories,
                                         Set<String> controlSignatures,
                                         Map<String, Object> policyMetadata) {
        public ProcessSemanticProfile {
            observedTypes = observedTypes == null ? Set.of() : Set.copyOf(observedTypes);
            actionCategories = actionCategories == null ? Set.of() : Set.copyOf(actionCategories);
            controlSignatures = controlSignatures == null ? Set.of() : Set.copyOf(controlSignatures);
            policyMetadata = policyMetadata == null ? Map.of() : Map.copyOf(policyMetadata);
        }

        public boolean matchesRelationType(String relationType) {
            String normalized = normalizeToken(relationType);
            if (normalized.isBlank()) {
                return false;
            }
            if (normalized.equals(normalizeToken(canonicalType)) || normalized.equals(normalizeToken(this.relationType))) {
                return true;
            }
            for (String observedType : observedTypes) {
                if (normalized.equals(normalizeToken(observedType))) {
                    return true;
                }
            }
            return false;
        }
    }

    public static RelationSchemaConfig toConfig(OntologySchema schema) {
        return new RelationSchemaConfig(toRelationSchemas(schema));
    }

    public static List<RelationNormalizer.RelationSchema> toRelationSchemas(OntologySchema schema) {
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        List<RelationNormalizer.RelationSchema> out = new ArrayList<>();
        for (RelationshipTypeDefinition rel : schema.getRelationshipTypes()) {
            RelationNormalizer.RelationSchema compiled = compile(rel);
            if (compiled != null) {
                out.add(compiled);
            }
        }
        return out;
    }

    public static List<ProcessSemanticProfile> toProcessSemanticProfiles(OntologySchema schema) {
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        List<ProcessSemanticProfile> out = new ArrayList<>();
        for (RelationshipTypeDefinition rel : schema.getRelationshipTypes()) {
            ProcessSemanticProfile profile = compileProcessProfile(rel);
            if (profile != null) {
                out.add(profile);
            }
        }
        return List.copyOf(out);
    }

    private static RelationNormalizer.RelationSchema compile(RelationshipTypeDefinition rel) {
        if (rel == null || !hasText(rel.getType())
                || !hasText(rel.getSourceEntityType()) || !hasText(rel.getTargetEntityType())) {
            return null;
        }
        Map<String, Object> metadata = rel.getMetadata();
        String canonicalType = firstText(rel.getCanonicalType(), metadataText(metadata, "canonicalType"), rel.getType());
        Set<String> observed = new LinkedHashSet<>();
        add(observed, rel.getType());
        add(observed, canonicalType);
        addAll(observed, rel.getObservedTypes());
        addAll(observed, rel.getInverseTypes());
        addAll(observed, metadataValue(metadata, "observedTypes"));
        addAll(observed, metadataValue(metadata, "relationAliases"));
        addAll(observed, metadataValue(metadata, "aliases"));
        addAll(observed, metadataValue(metadata, "inverseTypes"));
        addAll(observed, metadataValue(metadata, "inverseOf"));

        Set<String> sourceTypes = new LinkedHashSet<>();
        add(sourceTypes, rel.getSourceEntityType());
        addAll(sourceTypes, metadataValue(metadata, "sourceTypes"));
        addAll(sourceTypes, metadataValue(metadata, "sourceEntityTypes"));
        addAll(sourceTypes, metadataValue(metadata, "domain"));
        addAll(sourceTypes, metadataValue(metadata, "domains"));

        Set<String> targetTypes = new LinkedHashSet<>();
        add(targetTypes, rel.getTargetEntityType());
        addAll(targetTypes, metadataValue(metadata, "targetTypes"));
        addAll(targetTypes, metadataValue(metadata, "targetEntityTypes"));
        addAll(targetTypes, metadataValue(metadata, "range"));
        addAll(targetTypes, metadataValue(metadata, "ranges"));

        boolean flipWhenSwapped = rel.getFlipWhenSwapped() == null
                ? metadataBoolean(metadata, "flipWhenSwapped", true)
                : rel.getFlipWhenSwapped();
        boolean emitAlreadyCanonical = rel.getEmitAlreadyCanonical() == null
                ? metadataBoolean(metadata, "emitAlreadyCanonical", false)
                : rel.getEmitAlreadyCanonical();

        return new RelationNormalizer.RelationSchema(
                rel.getType(), observed, canonicalType, sourceTypes, targetTypes,
                flipWhenSwapped, emitAlreadyCanonical);
    }

    private static ProcessSemanticProfile compileProcessProfile(RelationshipTypeDefinition rel) {
        if (rel == null || !hasText(rel.getType())) {
            return null;
        }
        Map<String, Object> metadata = rel.getMetadata();
        String canonicalType = firstText(rel.getCanonicalType(), metadataText(metadata, "canonicalType"), rel.getType());

        Set<String> observed = new LinkedHashSet<>();
        add(observed, rel.getType());
        add(observed, canonicalType);
        addAll(observed, rel.getObservedTypes());
        addAll(observed, rel.getInverseTypes());
        addAll(observed, metadataValue(metadata, "observedTypes"));
        addAll(observed, metadataValue(metadata, "relationAliases"));
        addAll(observed, metadataValue(metadata, "aliases"));
        addAll(observed, metadataValue(metadata, "inverseTypes"));
        addAll(observed, metadataValue(metadata, "inverseOf"));

        Set<String> actionCategories = new LinkedHashSet<>();
        addAll(actionCategories, rel.getActionCategories());
        addAll(actionCategories, metadataValue(metadata, "actionCategory"));
        addAll(actionCategories, metadataValue(metadata, "actionCategories"));
        addAll(actionCategories, metadataValue(metadata, "actions"));

        Set<String> controlSignatures = new LinkedHashSet<>();
        addAll(controlSignatures, rel.getControlSignatures());
        addAll(controlSignatures, metadataValue(metadata, "controlSignature"));
        addAll(controlSignatures, metadataValue(metadata, "controlSignatures"));
        addAll(controlSignatures, metadataValue(metadata, "controlId"));
        addAll(controlSignatures, metadataValue(metadata, "controlIds"));

        Map<String, Object> policyMetadata = new LinkedHashMap<>();
        putPolicy(policyMetadata, rel.getPolicyMetadata());
        Object metadataPolicy = metadataValue(metadata, "policyMetadata");
        if (metadataPolicy instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    policyMetadata.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        for (String key : List.of("approvalPolicy", "routingPolicy", "escalationPolicy", "sla",
                "slaSeconds", "remediation", "remediationAction", "threshold", "thresholdField",
                "thresholdOperator")) {
            Object value = metadataValue(metadata, key);
            if (value != null) {
                policyMetadata.putIfAbsent(key, value);
            }
        }

        if (actionCategories.isEmpty() && controlSignatures.isEmpty() && policyMetadata.isEmpty()) {
            return null;
        }
        return new ProcessSemanticProfile(rel.getType(), canonicalType, observed, actionCategories,
                controlSignatures, policyMetadata);
    }

    private static Object metadataValue(Map<String, Object> metadata, String key) {
        return metadata == null ? null : metadata.get(key);
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadataValue(metadata, key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean metadataBoolean(Map<String, Object> metadata, String key, boolean defaultValue) {
        Object value = metadataValue(metadata, key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private static void putPolicy(Map<String, Object> out, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void addAll(Set<String> out, Object value) {
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
    }

    private static void addAll(Set<String> out, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            add(out, value);
        }
    }

    private static void addDelimited(Set<String> out, String raw) {
        if (raw == null) {
            return;
        }
        for (String part : raw.split(",")) {
            add(out, part);
        }
    }

    private static void add(Set<String> out, String value) {
        if (hasText(value)) {
            out.add(value.trim());
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

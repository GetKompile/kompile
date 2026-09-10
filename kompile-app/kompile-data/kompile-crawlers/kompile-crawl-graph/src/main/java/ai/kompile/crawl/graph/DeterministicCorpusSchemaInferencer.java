/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure schema inference over a frozen corpus-candidate inventory.
 *
 * <p>Instance names remain observations. Node types come from extractor categories, relationship
 * types come from relationship predicates, and endpoint patterns are emitted only when both
 * endpoints can be deterministically mapped back to observed node categories. A configured schema
 * is authoritative: matching definitions and canonical names are reused verbatim.</p>
 */
final class DeterministicCorpusSchemaInferencer {

    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "^\\(([A-Z][A-Z0-9_]*)\\)-\\[:([A-Z][A-Z0-9_]*)]\\->\\(([A-Z][A-Z0-9_]*)\\)$");
    private static final Set<String> GENERIC_TYPES = Set.of(
            "NODE", "NODE_LABEL", "REL", "REL_TYPE", "ENTITY_TYPE", "TYPE", "UNKNOWN",
            "ENTITY", "TOPIC", "THEME", "KEYWORD", "TECHNICAL", "PHRASE",
            "RELATIONSHIP", "RELATIONS", "CO_OCCURS");

    private DeterministicCorpusSchemaInferencer() {
    }

    static GraphSchema infer(
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema) {
        if (inventory == null || inventory.isEmpty()) {
            return null;
        }

        Map<String, NodeType> configuredNodes = configuredNodes(configuredSchema);
        Map<String, RelationshipType> configuredRelations = configuredRelationships(configuredSchema);
        Map<String, RelationshipType> configuredRelationAliases =
                configuredRelationshipAliases(configuredSchema);

        Map<String, NodeType> inferredNodes = new TreeMap<>();
        Map<String, Set<String>> conceptTypes = new LinkedHashMap<>();

        for (CorpusSchemaCandidates.NodeCandidate candidate :
                sortedNodes(inventory.nodeCandidates())) {
            Set<String> observedTypes = new TreeSet<>();
            for (String category : candidate.categories()) {
                String type = schemaName(category);
                if (type == null || GENERIC_TYPES.contains(type)) {
                    continue;
                }
                observedTypes.add(type);
                NodeType authoritative = configuredNodes.get(type);
                inferredNodes.putIfAbsent(type, authoritative != null
                        ? copy(authoritative)
                        : new NodeType(type,
                                "Corpus-inferred type supported by deterministic concept categories.",
                                null,
                                SchemaHierarchyVocabulary.parentForGeneratedType(type)));
            }
            if (observedTypes.isEmpty()) {
                continue;
            }
            registerConceptTypes(conceptTypes, candidate.candidateKey(), observedTypes);
            for (String surface : candidate.surfaceForms()) {
                registerConceptTypes(conceptTypes, surface, observedTypes);
            }
        }

        Map<String, RelationshipType> inferredRelations = new TreeMap<>();
        Set<String> inferredPatterns = new TreeSet<>();
        for (CorpusSchemaCandidates.RelationshipCandidate candidate :
                sortedRelationships(inventory.relationshipCandidates())) {
            String observedPredicate = schemaName(candidate.candidateKey());
            if (observedPredicate == null || GENERIC_TYPES.contains(observedPredicate)) {
                continue;
            }

            RelationshipType authoritative = configuredRelations.get(observedPredicate);
            if (authoritative == null) {
                authoritative = configuredRelationAliases.get(observedPredicate);
            }
            String canonicalPredicate = authoritative == null
                    ? observedPredicate
                    : schemaName(authoritative.getType());
            if (canonicalPredicate == null) {
                continue;
            }

            Set<String> sourceTypes = endpointTypes(candidate.sourceConcepts(), conceptTypes);
            Set<String> targetTypes = endpointTypes(candidate.targetConcepts(), conceptTypes);
            Set<String> patterns = new TreeSet<>();
            for (String sourceType : sourceTypes) {
                for (String targetType : targetTypes) {
                    patterns.add("(" + sourceType + ")-[:" + canonicalPredicate + "]->("
                            + targetType + ")");
                }
            }
            patterns.addAll(configuredPatternsFor(configuredSchema, canonicalPredicate));

            // A new relation without a deterministic endpoint shape is an instance-level observation,
            // not a reusable schema relation.
            if (authoritative == null && patterns.isEmpty()) {
                continue;
            }

            List<String> aliases = relationAliases(candidate, canonicalPredicate,
                    authoritative == null ? List.of() : authoritative.getAliases());
            String connectionFamily = authoritative == null
                    ? SchemaHierarchyVocabulary.connectionFamilyForPredicate(canonicalPredicate)
                    : authoritative.getConnectionFamily();
            if (authoritative == null && connectionFamily == null) {
                continue;
            }
            RelationshipType inferred = authoritative != null
                    ? new RelationshipType(authoritative.getType(), authoritative.getDescription(),
                            authoritative.getProperties(), aliases,
                            authoritative.getConnectionFamily())
                    : new RelationshipType(canonicalPredicate,
                            "Corpus-inferred relationship supported by deterministic predicate and endpoint evidence.",
                            null, aliases, connectionFamily);
            inferredRelations.putIfAbsent(canonicalPredicate, inferred);
            inferredPatterns.addAll(patterns);
        }

        if (inferredNodes.isEmpty() && inferredRelations.isEmpty() && inferredPatterns.isEmpty()) {
            return null;
        }
        return new GraphSchema(
                inferredNodes.isEmpty() ? null : List.copyOf(inferredNodes.values()),
                inferredRelations.isEmpty() ? null : List.copyOf(inferredRelations.values()),
                inferredPatterns.isEmpty() ? null : List.copyOf(inferredPatterns));
    }

    private static List<CorpusSchemaCandidates.NodeCandidate> sortedNodes(
            List<CorpusSchemaCandidates.NodeCandidate> values) {
        return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(value ->
                        normalizeConcept(value.candidateKey())))
                .toList();
    }

    private static List<CorpusSchemaCandidates.RelationshipCandidate> sortedRelationships(
            List<CorpusSchemaCandidates.RelationshipCandidate> values) {
        return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(value ->
                        normalizeConcept(value.candidateKey())))
                .toList();
    }

    private static Map<String, NodeType> configuredNodes(GraphSchema schema) {
        Map<String, NodeType> values = new LinkedHashMap<>();
        if (schema != null && schema.getNodeTypes() != null) {
            for (NodeType type : schema.getNodeTypes()) {
                String name = type == null ? null : schemaName(type.getLabel());
                if (name != null) {
                    values.putIfAbsent(name, type);
                }
            }
        }
        return values;
    }

    private static Map<String, RelationshipType> configuredRelationships(GraphSchema schema) {
        Map<String, RelationshipType> values = new LinkedHashMap<>();
        if (schema != null && schema.getRelationshipTypes() != null) {
            for (RelationshipType type : schema.getRelationshipTypes()) {
                String name = type == null ? null : schemaName(type.getType());
                if (name != null) {
                    values.putIfAbsent(name, type);
                }
            }
        }
        return values;
    }

    private static Map<String, RelationshipType> configuredRelationshipAliases(GraphSchema schema) {
        Map<String, RelationshipType> values = new LinkedHashMap<>();
        if (schema != null && schema.getRelationshipTypes() != null) {
            for (RelationshipType type : schema.getRelationshipTypes()) {
                if (type == null || type.getAliases() == null) {
                    continue;
                }
                for (String alias : type.getAliases()) {
                    String name = schemaName(alias);
                    if (name != null) {
                        values.putIfAbsent(name, type);
                    }
                }
            }
        }
        return values;
    }

    private static Set<String> configuredPatternsFor(GraphSchema schema, String relationType) {
        Set<String> values = new TreeSet<>();
        if (schema == null || schema.getPatterns() == null) {
            return values;
        }
        for (String pattern : schema.getPatterns()) {
            Matcher matcher = pattern == null ? null : RELATION_PATTERN.matcher(pattern.trim());
            if (matcher != null && matcher.matches() && relationType.equals(matcher.group(2))) {
                values.add(pattern.trim());
            }
        }
        return values;
    }

    private static void registerConceptTypes(
            Map<String, Set<String>> target, String concept, Set<String> types) {
        String key = normalizeConcept(concept);
        if (key.isBlank()) {
            return;
        }
        target.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(types);
    }

    private static Set<String> endpointTypes(
            List<String> concepts, Map<String, Set<String>> conceptTypes) {
        Set<String> values = new TreeSet<>();
        if (concepts != null) {
            for (String concept : concepts) {
                values.addAll(conceptTypes.getOrDefault(normalizeConcept(concept), Set.of()));
            }
        }
        return values;
    }

    private static List<String> relationAliases(
            CorpusSchemaCandidates.RelationshipCandidate candidate,
            String canonicalPredicate,
            List<String> configuredAliases) {
        Set<String> values = new TreeSet<>();
        if (configuredAliases != null) {
            configuredAliases.stream()
                    .filter(DeterministicCorpusSchemaInferencer::hasText)
                    .map(String::trim)
                    .forEach(values::add);
        }
        if (candidate.surfaceForms() != null) {
            candidate.surfaceForms().stream()
                    .filter(DeterministicCorpusSchemaInferencer::hasText)
                    .map(String::trim)
                    .filter(value -> !canonicalPredicate.equals(value))
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private static NodeType copy(NodeType type) {
        return new NodeType(type.getLabel(), type.getDescription(), type.getProperties(),
                type.getParentType());
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

    private static String normalizeConcept(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

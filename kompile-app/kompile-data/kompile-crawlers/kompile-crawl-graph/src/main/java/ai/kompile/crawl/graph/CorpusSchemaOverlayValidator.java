/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class CorpusSchemaOverlayValidator {

    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<String> GENERIC_TYPE_NAMES = Set.of(
            "NODE_LABEL", "SOURCE_TYPE", "TARGET_TYPE", "ENTITY_TYPE", "REL_TYPE",
            "TYPE", "UNKNOWN", "ENTITY", "RELATIONSHIP", "RELATIONS"
    );
    private static final List<String> ALLOWED_PROPERTY_TYPES = List.of(
            "String",
            "Integer",
            "Decimal",
            "Boolean",
            "Date",
            "Year",
            "YearMonth",
            "DateTime"
    );
    private static final Set<String> ALLOWED_PROPERTY_TYPES_NORMALIZED = Set.of(
            "STRING",
            "INTEGER",
            "DECIMAL",
            "BOOLEAN",
            "DATE",
            "YEAR",
            "YEARMONTH",
            "DATETIME"
    );

    private CorpusSchemaOverlayValidator() {
    }

    record Result(
            boolean valid,
            List<String> errors) {

        Result {
            errors = errors == null
                    ? List.of()
                    : List.copyOf(errors);
        }
    }

    static Result validate(GraphSchema configuredSchema, GraphSchema generatedOverlay) {
        List<String> errors = new ArrayList<>();
        if (generatedOverlay == null) {
            errors.add("[SCHEMA_OVERLAY] Generated schema overlay is null");
            return new Result(false, errors);
        }

        Set<String> generatedNodeTypeNames = new LinkedHashSet<>();
        Set<String> generatedRelationshipTypeNames = new LinkedHashSet<>();
        Map<String, Boolean> generatedRelationHasPattern = new LinkedHashMap<>();

        validateGeneratedNodes(generatedOverlay, errors, generatedNodeTypeNames);
        validateGeneratedRelationships(generatedOverlay, errors, generatedRelationshipTypeNames, generatedRelationHasPattern);

        validatePatterns(configuredSchema, generatedOverlay, generatedNodeTypeNames, generatedRelationshipTypeNames,
                generatedRelationHasPattern, errors);

        return new Result(errors.isEmpty(), errors);
    }

    private static void validateGeneratedNodes(
            GraphSchema generatedOverlay,
            List<String> errors,
            Set<String> generatedNodeTypeNames) {

        List<NodeType> nodes = generatedOverlay.getNodeTypes();
        Set<String> seenNodeTypes = new HashSet<>();
        for (int i = 0; i < safeSize(nodes); i++) {
            NodeType nodeType = nodes.get(i);
            if (nodeType == null) {
                errors.add("[SCHEMA_OVERLAY] Generated node definition at index " + i + " is null");
                continue;
            }

            String label = nodeType.getLabel();
            if (!hasText(label)) {
                errors.add("[SCHEMA_TYPE_NAME] Generated node label must be nonblank at index " + i);
                continue;
            }
            if (!TYPE_NAME.matcher(label).matches()) {
                errors.add("[SCHEMA_TYPE_NAME] Node label must match [A-Z][A-Z0-9_]*: " + label);
            }

            String canonicalLabel = canonical(label);
            if (GENERIC_TYPE_NAMES.contains(canonicalLabel)) {
                errors.add("[SCHEMA_GENERIC_TYPE] Generated node label is a placeholder/generic type: " + label);
            }
            if (!seenNodeTypes.add(canonicalLabel)) {
                errors.add("[SCHEMA_DUPLICATE_TYPE] Generated node label is duplicated: " + label);
            }
            generatedNodeTypeNames.add(canonicalLabel);

            if (!hasText(nodeType.getDescription())) {
                errors.add("[SCHEMA_DESCRIPTION] Node type " + label + " must have a concise description");
            }

            validateProperties(label, nodeType.getProperties(), errors);
        }
    }

    private static void validateProperties(String ownerLabel, List<PropertyType> properties,
                                         List<String> errors) {
        if (properties == null) {
            return;
        }

        Set<String> seenProperties = new HashSet<>();
        for (int i = 0; i < properties.size(); i++) {
            PropertyType property = properties.get(i);
            if (property == null) {
                errors.add("[SCHEMA_PROPERTY_NAME] " + ownerLabel + " has null property definition at index " + i);
                continue;
            }
            String name = property.getName();
            if (!hasText(name)) {
                errors.add("[SCHEMA_PROPERTY_NAME] " + ownerLabel + " property has blank name at index " + i);
            } else {
                String canonicalName = canonical(name);
                if (!seenProperties.add(canonicalName)) {
                    errors.add("[SCHEMA_PROPERTY_NAME] " + ownerLabel + " has duplicate property name: " + name);
                }
            }

            String type = property.getType();
            if (!ALLOWED_PROPERTY_TYPES_NORMALIZED.contains(normalizePropertyType(type))) {
                errors.add("[SCHEMA_PROPERTY_TYPE] " + ownerLabel + "." + (name == null ? "<unknown>" : name)
                        + " uses unsupported property type " + type + "; expected one of "
                        + String.join(", ", ALLOWED_PROPERTY_TYPES));
            }
        }
    }

    private static void validateGeneratedRelationships(
            GraphSchema generatedOverlay,
            List<String> errors,
            Set<String> generatedRelationshipTypeNames,
            Map<String, Boolean> generatedRelationHasPattern) {

        List<RelationshipType> relationships = generatedOverlay.getRelationshipTypes();
        Set<String> seenRelationshipTypes = new HashSet<>();
        for (int i = 0; i < safeSize(relationships); i++) {
            RelationshipType relationshipType = relationships.get(i);
            if (relationshipType == null) {
                errors.add("[SCHEMA_OVERLAY] Generated relationship definition at index " + i + " is null");
                continue;
            }

            String type = relationshipType.getType();
            if (!hasText(type)) {
                errors.add("[SCHEMA_TYPE_NAME] Generated relationship type must be nonblank at index " + i);
                continue;
            }
            if (!TYPE_NAME.matcher(type).matches()) {
                errors.add("[SCHEMA_TYPE_NAME] Relationship type must match [A-Z][A-Z0-9_]*: " + type);
            }

            String canonicalType = canonical(type);
            if (GENERIC_TYPE_NAMES.contains(canonicalType)) {
                errors.add("[SCHEMA_GENERIC_TYPE] Generated relationship type is a placeholder/generic type: " + type);
            }
            if (!seenRelationshipTypes.add(canonicalType)) {
                errors.add("[SCHEMA_DUPLICATE_TYPE] Generated relationship type is duplicated: " + type);
            }
            generatedRelationshipTypeNames.add(canonicalType);
            generatedRelationHasPattern.putIfAbsent(canonicalType, false);

            if (!hasText(relationshipType.getDescription())) {
                errors.add("[SCHEMA_DESCRIPTION] Relationship type " + type
                        + " must have a concise description");
            }

            validateProperties(type, relationshipType.getProperties(), errors);

            List<String> aliases = relationshipType.getAliases();
            if (aliases != null) {
                Set<String> seenAliases = new HashSet<>();
                for (String alias : aliases) {
                    if (!hasText(alias)) {
                        errors.add("[SCHEMA_ALIAS] Relationship type " + type + " has blank alias");
                        continue;
                    }
                    String canonicalAlias = canonical(alias);
                    if (!seenAliases.add(canonicalAlias)) {
                        errors.add("[SCHEMA_ALIAS] Relationship type " + type + " has duplicate alias: " + alias);
                    }
                }
            }
        }
    }

    private static void validatePatterns(GraphSchema configuredSchema,
                                       GraphSchema generatedOverlay,
                                       Set<String> generatedNodeTypeNames,
                                       Set<String> generatedRelationshipTypeNames,
                                       Map<String, Boolean> generatedRelationHasPattern,
                                       List<String> errors) {
        Set<String> combinedNodeNames = new LinkedHashSet<>();
        Set<String> combinedRelationshipNames = new LinkedHashSet<>();

        for (NodeType configuredNode : configuredSchema == null ? List.<NodeType>of() : safeList(configuredSchema.getNodeTypes())) {
            if (configuredNode != null && hasText(configuredNode.getLabel())) {
                combinedNodeNames.add(canonical(configuredNode.getLabel()));
            }
        }
        combinedNodeNames.addAll(generatedNodeTypeNames);

        for (RelationshipType configuredRel : configuredSchema == null ? List.<RelationshipType>of() : safeList(configuredSchema.getRelationshipTypes())) {
            if (configuredRel != null && hasText(configuredRel.getType())) {
                combinedRelationshipNames.add(canonical(configuredRel.getType()));
            }
        }
        combinedRelationshipNames.addAll(generatedRelationshipTypeNames);

        validatePatternSource("configured", configuredSchema == null ? List.<String>of() : safeList(configuredSchema.getPatterns()),
                combinedNodeNames, combinedRelationshipNames, generatedRelationHasPattern, errors);
        validatePatternSource("generated", generatedOverlay == null ? List.<String>of() : safeList(generatedOverlay.getPatterns()),
                combinedNodeNames, combinedRelationshipNames, generatedRelationHasPattern, errors);

        for (Map.Entry<String, Boolean> entry : generatedRelationHasPattern.entrySet()) {
            if (!entry.getValue()) {
                errors.add("[SCHEMA_RELATION_PATTERN_MISSING] Generated relationship type "
                        + entry.getKey() + " has no directed endpoint pattern");
            }
        }
    }

    private static void validatePatternSource(String source,
                                            List<String> patterns,
                                            Set<String> combinedNodeNames,
                                            Set<String> combinedRelationshipNames,
                                            Map<String, Boolean> generatedRelationHasPattern,
                                            List<String> errors) {
        for (String pattern : patterns) {
            if (!hasText(pattern)) {
                errors.add("[SCHEMA_PATTERN_FORMAT] " + source + " pattern is blank");
                continue;
            }
            Optional<GraphExtractionValidator.RelationSignature> parsed = GraphExtractionValidator.parseRelationPattern(pattern);
            if (parsed.isEmpty()) {
                errors.add("[SCHEMA_PATTERN_FORMAT] Could not parse relation pattern: " + pattern);
                continue;
            }

            GraphExtractionValidator.RelationSignature signature = parsed.get();
            if (!combinedNodeNames.contains(signature.sourceType())) {
                errors.add("[SCHEMA_PATTERN_ENDPOINT] Pattern references unknown source type "
                        + signature.sourceType() + ": " + pattern);
            }
            if (!combinedNodeNames.contains(signature.targetType())) {
                errors.add("[SCHEMA_PATTERN_ENDPOINT] Pattern references unknown target type "
                        + signature.targetType() + ": " + pattern);
            }
            if (!combinedRelationshipNames.contains(signature.relationType())) {
                errors.add("[SCHEMA_PATTERN_RELATION] Pattern references unknown relationship type "
                        + signature.relationType() + ": " + pattern);
                continue;
            }

            if (generatedRelationHasPattern.containsKey(signature.relationType()) && !generatedRelationHasPattern.get(signature.relationType())) {
                generatedRelationHasPattern.put(signature.relationType(), true);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePropertyType(String propertyType) {
        if (propertyType == null || propertyType.isBlank()) {
            return null;
        }
        return propertyType.trim().toUpperCase(Locale.ROOT);
    }

    private static int safeSize(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}

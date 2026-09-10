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

package ai.kompile.core.graphrag.model.schema;

import com.fasterxml.jackson.annotation.JsonIgnore; // Import if you plan to use Jackson for serialization
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphSchema {
    private List<NodeType> nodeTypes;
    private List<RelationshipType> relationshipTypes;
    private List<String> patterns; // e.g., "(PERSON)-[:WORKS_AT]->(COMPANY)"

    /**
     * Returns a map of node labels to a set of their allowed property names.
     * This is useful for property validation.
     */
    @JsonIgnore
    public Map<String, Set<String>> getNodePropertiesByName() {
        if (nodeTypes == null) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> properties = new LinkedHashMap<>();
        for (NodeType type : nodeTypes) {
            if (type != null && hasText(type.getLabel())) {
                List<PropertyType> effective = getEffectiveNodeProperties(type.getLabel());
                if (effective != null) {
                    properties.put(type.getLabel(), effective.stream()
                            .map(PropertyType::getName)
                            .filter(GraphSchema::hasText)
                            .collect(Collectors.toSet()));
                }
            }
        }
        return Collections.unmodifiableMap(properties);
    }

    /**
     * Returns a set of all defined node labels in the schema.
     */
    @JsonIgnore
    public Set<String> getAllNodeLabels() {
        if (nodeTypes == null) {
            return Collections.emptySet();
        }
        return nodeTypes.stream().map(NodeType::getLabel).collect(Collectors.toSet());
    }

    /**
     * Returns the raw list of NodeType objects.
     */
    public List<NodeType> getNodeTypes() {
        return nodeTypes == null ? null : Collections.unmodifiableList(nodeTypes);
    }

    /**
     * Returns the raw list of RelationshipType objects.
     * The user prompt had this.
     */
    public List<RelationshipType> getRelationshipTypes() {
        return relationshipTypes == null ? null : Collections.unmodifiableList(relationshipTypes);
    }

    /**
     * Returns the raw list of pattern strings.
     */
    public List<String> getPatterns() {
        return patterns == null ? null : Collections.unmodifiableList(patterns);
    }

    /**
     * Returns a set of all defined relationship type strings (names) in the schema.
     * This is a helper for easier checking.
     */
    @JsonIgnore
    public Set<String> getAllRelationshipTypes() {
        if (relationshipTypes == null) {
            return Collections.emptySet();
        }
        return relationshipTypes.stream().map(RelationshipType::getType).collect(Collectors.toSet());
    }

    /**
     * Returns a map of relationship types to a set of their allowed property names.
     * This is useful for property validation if relationships have properties.
     */
    @JsonIgnore
    public Map<String, Set<String>> getRelationshipPropertiesByName() {
        if (relationshipTypes == null) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> properties = new LinkedHashMap<>();
        for (RelationshipType type : relationshipTypes) {
            if (type != null && hasText(type.getType()) && type.getProperties() != null) {
                properties.put(type.getType(), type.getProperties().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(PropertyType::getName)
                        .filter(GraphSchema::hasText)
                        .collect(Collectors.toSet()));
            }
        }
        return Collections.unmodifiableMap(properties);
    }


    // Helper to get NodeType by label for cleanGraph
    @JsonIgnore
    public Map<String, NodeType> getNodeTypeMap() {
        if (nodeTypes == null) return Collections.emptyMap();
        return nodeTypes.stream().collect(Collectors.toMap(NodeType::getLabel, Function.identity()));
    }

    /** Returns canonical child-to-parent entity type declarations. */
    @JsonIgnore
    public Map<String, String> getNodeParentTypes() {
        if (nodeTypes == null) return Collections.emptyMap();
        Map<String, String> parents = new LinkedHashMap<>();
        for (NodeType type : nodeTypes) {
            if (type != null && hasText(type.getLabel()) && hasText(type.getParentType())) {
                parents.put(canonical(type.getLabel()), canonical(type.getParentType()));
            }
        }
        return Collections.unmodifiableMap(parents);
    }

    /** Returns canonical predicate-to-connection-family classifications. */
    @JsonIgnore
    public Map<String, String> getRelationshipConnectionFamilies() {
        if (relationshipTypes == null) return Collections.emptyMap();
        Map<String, String> families = new LinkedHashMap<>();
        for (RelationshipType type : relationshipTypes) {
            if (type != null && hasText(type.getType()) && hasText(type.getConnectionFamily())) {
                families.put(canonical(type.getType()), canonical(type.getConnectionFamily()));
                if (type.getAliases() != null) {
                    type.getAliases().stream().filter(GraphSchema::hasText)
                            .forEach(alias -> families.putIfAbsent(
                                    canonical(alias), canonical(type.getConnectionFamily())));
                }
            }
        }
        return Collections.unmodifiableMap(families);
    }

    /**
     * Tests whether an actual entity type is the expected type or one of its transitive subtypes.
     * Invalid cyclic declarations terminate safely and never manufacture assignability.
     */
    @JsonIgnore
    public boolean isNodeTypeAssignableTo(String actualType, String expectedType) {
        if (!hasText(actualType) || !hasText(expectedType)) return false;
        String expected = canonical(expectedType);
        String current = canonical(actualType);
        Map<String, String> parents = getNodeParentTypes();
        Set<String> visited = new HashSet<>();
        boolean matched = false;
        while (visited.add(current)) {
            matched |= current.equals(expected);
            current = parents.get(current);
            if (current == null) return matched;
        }
        return false;
    }

    /** Direct-parent-first transitive ancestor list; empty for roots, unknown types, and cycles. */
    @JsonIgnore
    public List<String> getNodeTypeAncestors(String typeName) {
        if (!hasText(typeName)) return List.of();
        Map<String, String> parents = getNodeParentTypes();
        List<String> ancestors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = canonical(typeName);
        while (visited.add(current)) {
            current = parents.get(current);
            if (current == null) return List.copyOf(ancestors);
            ancestors.add(current);
        }
        return List.of();
    }

    /**
     * Returns parent-first inherited properties. Null means no lineage member declares a property
     * contract; an explicit empty list means the hierarchy declares a deny-all contract.
     */
    @JsonIgnore
    public List<PropertyType> getEffectiveNodeProperties(String typeName) {
        if (!hasText(typeName) || nodeTypes == null) return null;
        Map<String, NodeType> byName = new LinkedHashMap<>();
        for (NodeType type : nodeTypes) {
            if (type != null && hasText(type.getLabel())) {
                byName.put(canonical(type.getLabel()), type);
            }
        }
        List<NodeType> lineage = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = canonical(typeName);
        while (current != null && visited.add(current)) {
            NodeType definition = byName.get(current);
            if (definition == null) break;
            lineage.add(0, definition);
            current = hasText(definition.getParentType())
                    ? canonical(definition.getParentType()) : null;
        }
        Map<String, PropertyType> properties = new LinkedHashMap<>();
        boolean contractDeclared = false;
        for (NodeType definition : lineage) {
            if (definition.getProperties() == null) continue;
            contractDeclared = true;
            for (PropertyType property : definition.getProperties()) {
                if (property != null && hasText(property.getName())) {
                    properties.put(canonical(property.getName()), property);
                }
            }
        }
        return contractDeclared ? List.copyOf(properties.values()) : null;
    }

    // Helper to get RelationshipType by type string for cleanGraph
    @JsonIgnore
    public Map<String, RelationshipType> getRelationshipTypeMap() {
        if (relationshipTypes == null) return Collections.emptyMap();
        return relationshipTypes.stream().collect(Collectors.toMap(RelationshipType::getType, Function.identity()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String canonical(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
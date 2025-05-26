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
import java.util.List;
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
        return nodeTypes.stream()
                .collect(Collectors.toMap(
                        NodeType::getLabel,
                        nt -> {
                            if (nt.getProperties() == null) return Collections.emptySet();
                            return nt.getProperties().stream()
                                    .map(PropertyType::getName)
                                    .collect(Collectors.toSet());
                        }
                ));
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
     * Returns the raw list of RelationshipType objects.
     * The user prompt had this.
     */
    public List<RelationshipType> getRelationshipTypes() {
        return relationshipTypes;
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
        return relationshipTypes.stream()
                .collect(Collectors.toMap(
                        RelationshipType::getType,
                        rt -> {
                            if (rt.getProperties() == null) return Collections.emptySet();
                            return rt.getProperties().stream()
                                    .map(PropertyType::getName)
                                    .collect(Collectors.toSet());
                        }
                ));
    }


    // Helper to get NodeType by label for cleanGraph
    @JsonIgnore
    public Map<String, NodeType> getNodeTypeMap() {
        if (nodeTypes == null) return Collections.emptyMap();
        return nodeTypes.stream().collect(Collectors.toMap(NodeType::getLabel, Function.identity()));
    }

    // Helper to get RelationshipType by type string for cleanGraph
    @JsonIgnore
    public Map<String, RelationshipType> getRelationshipTypeMap() {
        if (relationshipTypes == null) return Collections.emptyMap();
        return relationshipTypes.stream().collect(Collectors.toMap(RelationshipType::getType, Function.identity()));
    }
}
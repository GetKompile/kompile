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

package ai.kompile.core.graphrag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the entire constructed graph, containing lists of entities, relationships, and communities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Graph {
    /**
     * An optional identifier for this graph.
     */
    private String id;

    /**
     * Optional name for this graph.
     */
    private String name;

    /**
     * Optional description for this graph.
     */
    private String description;

    /**
     * Optional parent graph identifier for hierarchical graphs.
     */
    private String parentGraphId;

    /**
     * Associated fact sheet identifier.
     */
    private Long factSheetId;

    /**
     * Child graph identifiers for hierarchical graphs.
     */
    @Builder.Default
    private List<String> childGraphIds = new ArrayList<>();

    /**
     * A list of all entities identified in the source documents.
     */
    private List<Entity> entities;

    /**
     * A list of all relationships discovered between entities.
     */
    private List<Relationship> relationships;

    /**
     * A list of communities detected within the graph.
     */
    private List<Community> communities;

    /**
     * Arbitrary metadata for this graph.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
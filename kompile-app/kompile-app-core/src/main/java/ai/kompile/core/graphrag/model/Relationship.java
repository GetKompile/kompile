/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.model;

import lombok.Data;
import java.util.Map;

/**
 * Represents a single edge (a relationship) between two entities in the knowledge graph.
 */
@Data
public class Relationship {
    /**
     * The ID of the source entity for this relationship.
     */
    private String source;

    /**
     * The ID of the target entity for this relationship.
     */
    private String target;

    /**
     * A generated description of how the source and target entities are related.
     */
    private String description;

    /**
     * Additional metadata associated with the relationship.
     */
    private Map<String, Object> metadata;
}
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

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Represents a single node (an entity) in the knowledge graph.
 */
@Data
public class Entity {
    /**
     * A unique identifier for the entity.
     */
    private String id;

    /**
     * The primary name or title of the entity.
     */
    private String title;

    /**
     * The entity type/label (e.g., "PERSON", "ORGANIZATION", "LOCATION", "CONCEPT").
     * This is used for categorization and graph visualization.
     */
    private String type;

    /**
     * A short, generated description or summary of the entity.
     */
    private String description;

    /**
     * The list of source document chunks or text units from which this entity was extracted.
     */
    private List<String> textUnits;

    /**
     * Additional metadata associated with the entity.
     */
    private Map<String, Object> metadata;

    /**
     * Confidence score for the entity extraction (0.0 to 1.0).
     */
    private Double confidence;

    /**
     * Alternative names or aliases for this entity used in entity resolution.
     */
    private List<String> aliases;
}
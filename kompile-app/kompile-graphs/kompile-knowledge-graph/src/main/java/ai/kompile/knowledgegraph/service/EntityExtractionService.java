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
package ai.kompile.knowledgegraph.service;

import java.util.List;
import java.util.Map;

/**
 * Service for extracting named entities from text content.
 * Identifies entities like persons, organizations, locations, concepts, etc.
 */
public interface EntityExtractionService {

    /**
     * Represents an extracted entity from text.
     */
    record ExtractedEntity(
        String name,
        String type,
        int startOffset,
        int endOffset,
        double confidence,
        Map<String, Object> metadata
    ) {}

    /**
     * Represents an entity type that can be extracted.
     */
    enum EntityType {
        PERSON,
        ORGANIZATION,
        LOCATION,
        DATE,
        TECHNICAL_TERM,
        CONCEPT,
        PRODUCT,
        EVENT,
        CUSTOM
    }

    /**
     * Extract all entities from the given text.
     *
     * @param text The text to extract entities from
     * @return List of extracted entities
     */
    List<ExtractedEntity> extractEntities(String text);

    /**
     * Extract entities of specific types from the given text.
     *
     * @param text The text to extract entities from
     * @param types The entity types to look for
     * @return List of extracted entities
     */
    List<ExtractedEntity> extractEntities(String text, List<EntityType> types);

    /**
     * Extract entities with a minimum confidence threshold.
     *
     * @param text The text to extract entities from
     * @param minConfidence Minimum confidence score (0.0 to 1.0)
     * @return List of extracted entities meeting the confidence threshold
     */
    List<ExtractedEntity> extractEntities(String text, double minConfidence);

    /**
     * Normalize an entity name (lowercase, trim, remove special chars).
     *
     * @param entityName The raw entity name
     * @return Normalized entity name
     */
    String normalizeEntityName(String entityName);

    /**
     * Check if two entity names refer to the same entity (fuzzy matching).
     *
     * @param entity1 First entity name
     * @param entity2 Second entity name
     * @return True if they likely refer to the same entity
     */
    boolean isSameEntity(String entity1, String entity2);

    /**
     * Get all supported entity types.
     *
     * @return List of supported entity types
     */
    List<EntityType> getSupportedTypes();
}

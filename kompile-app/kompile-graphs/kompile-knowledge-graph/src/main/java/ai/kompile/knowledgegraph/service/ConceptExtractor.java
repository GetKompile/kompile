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
 * Service for extracting concepts from text passages.
 * Concepts are key themes, topics, and ideas found in the content.
 */
public interface ConceptExtractor {

    /**
     * Represents an extracted concept from text.
     */
    record ExtractedConcept(
        String name,           // The concept name
        String normalizedName, // Normalized form for matching
        String category,       // Category: TOPIC, THEME, KEYWORD, ENTITY, RELATIONSHIP
        double confidence,     // Extraction confidence (0.0 to 1.0)
        int frequency,         // How often this concept appears
        String context         // Sample context where concept was found
    ) {}

    /**
     * Configuration for concept extraction.
     */
    record ExtractionConfig(
        int maxConcepts,                // Maximum concepts to extract per passage
        double minConfidence,           // Minimum confidence threshold
        boolean extractKeywords,        // Extract keywords
        boolean extractTopics,          // Extract topics/themes
        boolean extractRelationships,   // Extract relationships between concepts
        List<String> focusCategories,   // Categories to focus on
        boolean useNgrams               // Use n-gram extraction
    ) {
        public static ExtractionConfig defaults() {
            return new ExtractionConfig(
                20,     // maxConcepts
                0.3,    // minConfidence
                true,   // extractKeywords
                true,   // extractTopics
                true,   // extractRelationships
                List.of("TOPIC", "THEME", "KEYWORD", "ENTITY"),
                true    // useNgrams
            );
        }
    }

    /**
     * Result of concept extraction including concepts and relationships.
     */
    record ExtractionResult(
        List<ExtractedConcept> concepts,
        List<ConceptRelationship> relationships,
        Map<String, Object> metadata
    ) {}

    /**
     * Represents a relationship between two concepts.
     */
    record ConceptRelationship(
        String sourceConcept,
        String targetConcept,
        String relationshipType,  // CO_OCCURS, RELATED_TO, PART_OF, CAUSES, etc.
        double strength           // Relationship strength (0.0 to 1.0)
    ) {}

    /**
     * Extract concepts from a single text passage.
     *
     * @param text   The text to analyze
     * @param config Extraction configuration
     * @return Extraction result with concepts and relationships
     */
    ExtractionResult extractConcepts(String text, ExtractionConfig config);

    /**
     * Extract concepts from multiple passages and find cross-passage relationships.
     *
     * @param passages Map of passage ID to text content
     * @param config   Extraction configuration
     * @return Map of passage ID to extraction results, plus cross-passage relationships
     */
    Map<String, ExtractionResult> extractConceptsFromPassages(
        Map<String, String> passages,
        ExtractionConfig config
    );

    /**
     * Find shared concepts across multiple extraction results.
     *
     * @param results   Map of passage ID to extraction result
     * @param minShared Minimum number of passages that must share a concept
     * @return List of shared concept info
     */
    List<SharedConcept> findSharedConcepts(Map<String, ExtractionResult> results, int minShared);

    /**
     * Represents a concept shared across multiple passages.
     */
    record SharedConcept(
        String conceptName,
        String normalizedName,
        List<String> passageIds,
        double averageConfidence,
        int totalFrequency
    ) {}

    /**
     * Normalize a concept name for matching.
     *
     * @param conceptName The concept name to normalize
     * @return Normalized form
     */
    String normalizeConcept(String conceptName);
}

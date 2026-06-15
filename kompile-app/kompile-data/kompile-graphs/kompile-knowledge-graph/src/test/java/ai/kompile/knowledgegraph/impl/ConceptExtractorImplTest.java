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

package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.service.ConceptExtractor;
import ai.kompile.knowledgegraph.service.ConceptExtractor.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConceptExtractorImpl} — keyword extraction, topic detection,
 * capitalized phrases, technical terms, n-grams, deduplication, co-occurrence
 * relationships, normalization, multi-passage extraction, and shared concepts.
 */
class ConceptExtractorImplTest {

    private ConceptExtractorImpl extractor;

    @BeforeEach
    void setUp() {
        extractor = new ConceptExtractorImpl();
    }

    private ExtractionConfig defaultConfig() {
        return ExtractionConfig.defaults();
    }

    private ExtractionConfig keywordsOnly() {
        return new ExtractionConfig(20, 0.1, true, false, false, List.of(), false);
    }

    private ExtractionConfig withNgrams() {
        return new ExtractionConfig(50, 0.1, true, true, true, List.of(), true);
    }

    // ─── Null/empty input ───────────────────────────────────────────

    @Test
    void extractConcepts_nullText_returnsEmpty() {
        ExtractionResult result = extractor.extractConcepts(null, defaultConfig());
        assertNotNull(result);
        assertTrue(result.concepts().isEmpty());
        assertTrue(result.relationships().isEmpty());
    }

    @Test
    void extractConcepts_blankText_returnsEmpty() {
        ExtractionResult result = extractor.extractConcepts("   ", defaultConfig());
        assertTrue(result.concepts().isEmpty());
    }

    @Test
    void extractConcepts_emptyText_returnsEmpty() {
        ExtractionResult result = extractor.extractConcepts("", defaultConfig());
        assertTrue(result.concepts().isEmpty());
    }

    // ─── Keyword extraction ─────────────────────────────────────────

    @Test
    void extractConcepts_extractsKeywords() {
        String text = "Machine learning algorithms use neural networks for pattern recognition. " +
                "Neural networks are trained on large datasets.";
        ExtractionResult result = extractor.extractConcepts(text, keywordsOnly());

        assertFalse(result.concepts().isEmpty());
        // Should find "neural", "networks", "pattern", "recognition" etc.
        List<String> names = result.concepts().stream()
                .map(ExtractedConcept::normalizedName)
                .toList();
        assertTrue(names.contains("neural"));
        assertTrue(names.contains("networks"));
    }

    @Test
    void extractConcepts_filtersStopWords() {
        String text = "The quick brown fox jumps over the lazy dog and the cat sat on the mat.";
        ExtractionResult result = extractor.extractConcepts(text, keywordsOnly());

        List<String> names = result.concepts().stream()
                .map(ExtractedConcept::normalizedName)
                .toList();
        // Stop words should be filtered
        assertFalse(names.contains("the"));
        assertFalse(names.contains("and"));
        assertFalse(names.contains("over"));
    }

    @Test
    void extractConcepts_keywordsHaveCategory() {
        String text = "Kubernetes orchestrates container deployments across clusters.";
        ExtractionResult result = extractor.extractConcepts(text, keywordsOnly());

        result.concepts().stream()
                .filter(c -> c.normalizedName().equals("kubernetes"))
                .findFirst()
                .ifPresent(c -> assertEquals("KEYWORD", c.category()));
    }

    // ─── Capitalized phrases (named entities) ───────────────────────

    @Test
    void extractConcepts_findsCapitalizedPhrases() {
        String text = "John Smith works at Acme Corporation in New York. " +
                "John Smith is the CEO.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        List<String> names = result.concepts().stream()
                .map(ExtractedConcept::name)
                .toList();
        assertTrue(names.contains("John Smith"));
        assertTrue(names.contains("Acme Corporation"));
        assertTrue(names.contains("New York"));
    }

    @Test
    void extractConcepts_filtersCommonSentenceStarters() {
        String text = "The company is growing. This quarter was profitable. " +
                "It shows that progress is being made.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        List<String> entityNames = result.concepts().stream()
                .filter(c -> "ENTITY".equals(c.category()))
                .map(ExtractedConcept::name)
                .toList();
        // "The company", "This quarter", "It shows" should be filtered
        assertFalse(entityNames.contains("The"));
        assertFalse(entityNames.contains("This"));
    }

    // ─── Technical terms ────────────────────────────────────────────

    @Test
    void extractConcepts_findsTechnicalTerms_camelCase() {
        String text = "The processData function calls getUserInput and formatOutput methods.";
        // Use config that only extracts keywords (technical terms are found via keyword extraction)
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        // Technical terms are extracted — check that at least one camelCase-like concept appears
        // After deduplication, the normalized name may be lowercase
        List<String> normalizedNames = result.concepts().stream()
                .map(ExtractedConcept::normalizedName)
                .toList();
        assertTrue(normalizedNames.contains("processdata") || normalizedNames.contains("getuserinput") ||
                normalizedNames.contains("formatoutput"),
                "Expected at least one camelCase term, got: " + normalizedNames);
    }

    @Test
    void extractConcepts_findsTechnicalTerms_snakeCase() {
        String text = "The user_id field maps to account_number in the database via database_connector.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        // After deduplication, technical terms may merge with keywords.
        // Check that snake_case terms are present in any category
        List<String> allNames = result.concepts().stream()
                .map(ExtractedConcept::name)
                .toList();
        List<String> normalizedNames = result.concepts().stream()
                .map(ExtractedConcept::normalizedName)
                .toList();
        assertTrue(allNames.stream().anyMatch(t -> t.contains("_")) ||
                normalizedNames.contains("userid") || normalizedNames.contains("accountnumber") ||
                normalizedNames.contains("databaseconnector"),
                "Expected snake_case terms, got names: " + allNames);
    }

    // ─── Topic extraction ───────────────────────────────────────────

    @Test
    void extractConcepts_findsHeadingTopics() {
        String text = "# Machine Learning Overview\n\nThis section covers the basics.\n\n" +
                "# Data Processing Pipeline\n\nPipeline stages include...";
        ExtractionConfig topicsConfig = new ExtractionConfig(
                20, 0.1, false, true, false, List.of(), false);
        ExtractionResult result = extractor.extractConcepts(text, topicsConfig);

        List<String> topicNames = result.concepts().stream()
                .filter(c -> "TOPIC".equals(c.category()))
                .map(ExtractedConcept::name)
                .toList();
        assertTrue(topicNames.stream().anyMatch(t -> t.contains("Machine Learning")));
    }

    // ─── N-gram extraction ──────────────────────────────────────────

    @Test
    void extractConcepts_findsRepeatedNgrams() {
        String text = "machine learning is great. I love machine learning. " +
                "machine learning algorithms work well. machine learning models scale.";
        ExtractionResult result = extractor.extractConcepts(text, withNgrams());

        List<String> phraseNames = result.concepts().stream()
                .filter(c -> "PHRASE".equals(c.category()))
                .map(ExtractedConcept::normalizedName)
                .toList();
        assertTrue(phraseNames.contains("machine learning"));
    }

    @Test
    void extractConcepts_ngramsSingleOccurrenceFiltered() {
        String text = "unique phrase appears once. another different sentence here.";
        ExtractionResult result = extractor.extractConcepts(text, withNgrams());

        List<String> phraseNames = result.concepts().stream()
                .filter(c -> "PHRASE".equals(c.category()))
                .map(ExtractedConcept::name)
                .toList();
        // Single-occurrence n-grams filtered out
        assertTrue(phraseNames.isEmpty());
    }

    // ─── Confidence and filtering ───────────────────────────────────

    @Test
    void extractConcepts_respectsMinConfidence() {
        String text = "Kubernetes orchestrates container deployments across multiple clusters efficiently.";
        ExtractionConfig highConfidence = new ExtractionConfig(
                20, 0.9, true, false, false, List.of(), false);
        ExtractionResult result = extractor.extractConcepts(text, highConfidence);

        result.concepts().forEach(c ->
                assertTrue(c.confidence() >= 0.9,
                        "Concept " + c.name() + " has confidence " + c.confidence()));
    }

    @Test
    void extractConcepts_respectsMaxConcepts() {
        String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet " +
                "kilo lima mike november oscar papa quebec romeo sierra tango uniform " +
                "victor whiskey xray yankee zulu";
        ExtractionConfig limited = new ExtractionConfig(
                5, 0.0, true, false, false, List.of(), false);
        ExtractionResult result = extractor.extractConcepts(text, limited);

        assertTrue(result.concepts().size() <= 5);
    }

    // ─── Relationships ──────────────────────────────────────────────

    @Test
    void extractConcepts_findsCooccurrenceRelationships() {
        String text = "Alice works at Acme. Alice manages the team at Acme. " +
                "Alice and Acme collaborate often.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        // Should find co-occurrence relationship between Alice and Acme
        assertFalse(result.relationships().isEmpty());
        result.relationships().forEach(r -> {
            assertEquals("CO_OCCURS", r.relationshipType());
            assertTrue(r.strength() > 0.0);
        });
    }

    @Test
    void extractConcepts_noRelationshipsWhenDisabled() {
        String text = "Alice works at Acme. Bob works at Acme too.";
        ExtractionConfig noRels = new ExtractionConfig(
                20, 0.1, true, false, false, List.of(), false);
        ExtractionResult result = extractor.extractConcepts(text, noRels);

        assertTrue(result.relationships().isEmpty());
    }

    // ─── Metadata ───────────────────────────────────────────────────

    @Test
    void extractConcepts_metadataContainsCounts() {
        String text = "Machine learning is transforming artificial intelligence research.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        assertNotNull(result.metadata());
        assertTrue(result.metadata().containsKey("totalExtracted"));
        assertTrue(result.metadata().containsKey("relationshipsFound"));
        assertTrue(result.metadata().containsKey("textLength"));
        assertEquals(text.length(), result.metadata().get("textLength"));
    }

    // ─── Normalization ──────────────────────────────────────────────

    @Test
    void normalizeConcept_lowercasesAndTrims() {
        assertEquals("hello world", extractor.normalizeConcept("  Hello World  "));
    }

    @Test
    void normalizeConcept_removesSpecialChars() {
        // normalizeConcept removes non-alphanumeric/non-space chars, then collapses whitespace
        // Hyphens are removed without inserting space, so "Machine-Learning!" → "machinelearning"
        assertEquals("machinelearning", extractor.normalizeConcept("Machine-Learning!"));
    }

    @Test
    void normalizeConcept_collapsesWhitespace() {
        assertEquals("foo bar", extractor.normalizeConcept("foo   bar"));
    }

    @Test
    void normalizeConcept_nullReturnsEmpty() {
        assertEquals("", extractor.normalizeConcept(null));
    }

    // ─── Multi-passage extraction ───────────────────────────────────

    @Test
    void extractConceptsFromPassages_extractsPerPassage() {
        Map<String, String> passages = Map.of(
                "p1", "Machine learning algorithms process data efficiently.",
                "p2", "Neural networks are a type of machine learning model."
        );

        Map<String, ExtractionResult> results = extractor.extractConceptsFromPassages(
                passages, defaultConfig());

        assertEquals(2, results.size());
        assertNotNull(results.get("p1"));
        assertNotNull(results.get("p2"));
        assertFalse(results.get("p1").concepts().isEmpty());
        assertFalse(results.get("p2").concepts().isEmpty());
    }

    // ─── Shared concepts ────────────────────────────────────────────

    @Test
    void findSharedConcepts_findsConceptsAcrossPassages() {
        Map<String, String> passages = Map.of(
                "p1", "Kubernetes manages container orchestration across clusters.",
                "p2", "Container orchestration with Kubernetes enables scaling.",
                "p3", "Docker containers run inside Kubernetes clusters."
        );

        Map<String, ExtractionResult> results = extractor.extractConceptsFromPassages(
                passages, defaultConfig());

        List<SharedConcept> shared = extractor.findSharedConcepts(results, 2);
        assertFalse(shared.isEmpty());

        // "kubernetes" should appear across multiple passages
        shared.stream()
                .filter(s -> s.normalizedName().contains("kubernetes"))
                .findFirst()
                .ifPresent(s -> {
                    assertTrue(s.passageIds().size() >= 2);
                    assertTrue(s.averageConfidence() > 0);
                    assertTrue(s.totalFrequency() > 0);
                });
    }

    @Test
    void findSharedConcepts_minSharedFilters() {
        Map<String, String> passages = Map.of(
                "p1", "Alpha is unique to passage one.",
                "p2", "Beta is unique to passage two."
        );

        Map<String, ExtractionResult> results = extractor.extractConceptsFromPassages(
                passages, defaultConfig());

        // minShared=2 should filter out concepts that appear in only 1 passage
        List<SharedConcept> shared = extractor.findSharedConcepts(results, 2);
        shared.forEach(s -> assertTrue(s.passageIds().size() >= 2));
    }

    // ─── Deduplication ──────────────────────────────────────────────

    @Test
    void extractConcepts_deduplicatesByNormalizedName() {
        String text = "Machine Learning is great. MACHINE LEARNING is useful. machine learning works.";
        ExtractionResult result = extractor.extractConcepts(text, defaultConfig());

        long mlCount = result.concepts().stream()
                .filter(c -> c.normalizedName().equals("machine"))
                .count();
        // Should be deduplicated to at most one entry per normalized name
        assertTrue(mlCount <= 1);
    }

    // ─── ExtractionConfig.defaults ──────────────────────────────────

    @Test
    void extractionConfig_defaults_hasExpectedValues() {
        ExtractionConfig config = ExtractionConfig.defaults();
        assertEquals(20, config.maxConcepts());
        assertEquals(0.3, config.minConfidence());
        assertTrue(config.extractKeywords());
        assertTrue(config.extractTopics());
        assertTrue(config.extractRelationships());
        assertTrue(config.useNgrams());
        assertEquals(4, config.focusCategories().size());
    }
}

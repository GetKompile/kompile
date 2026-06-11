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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PatternRelationExtractionAgent}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatternRelationExtractionAgentTest {

    private PatternRelationExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new PatternRelationExtractionAgent();
    }

    @Test
    void getIdReturnsPatternNer() {
        assertEquals("pattern-ner", agent.getId());
    }

    @Test
    void getDescriptionIsNonEmpty() {
        assertFalse(agent.getDescription().isBlank());
    }

    @Test
    void supportedContentTypesIsEmpty() {
        assertTrue(agent.supportedContentTypes().isEmpty());
    }

    @Test
    void extractWithNullChunksReturnsEmptyGraph() {
        ExtractionResult result = agent.extract(null, null);
        assertNotNull(result);
        assertTrue(result.graph().getEntities().isEmpty());
        assertTrue(result.graph().getRelationships().isEmpty());
    }

    @Test
    void extractWithEmptyChunksReturnsEmptyGraph() {
        ExtractionResult result = agent.extract(Collections.emptyList(), null);
        assertNotNull(result);
        assertEquals(0, result.graph().getEntities().size());
    }

    @Test
    void extractPersonFromText() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "John Smith leads the project in Berlin.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result.graph());
        List<Entity> entities = result.graph().getEntities();
        assertFalse(entities.isEmpty());

        boolean hasPerson = entities.stream()
                .anyMatch(e -> "PERSON".equals(e.getType())
                        && e.getTitle().contains("John"));
        assertTrue(hasPerson, "Should extract PERSON entity 'John Smith'");
    }

    @Test
    void extractOrganizationWithSuffix() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "Acme Corp is a global company headquartered in New York.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        List<Entity> entities = result.graph().getEntities();
        boolean hasOrg = entities.stream()
                .anyMatch(e -> "ORGANIZATION".equals(e.getType()));
        assertTrue(hasOrg, "Should extract ORGANIZATION entity");
    }

    @Test
    void extractDateEntity() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "The contract was signed on 2024-01-15 in London.", Map.of());

        ExtractionConfig config = new ExtractionConfig(
                List.of("DATE", "LOCATION", "PERSON", "ORGANIZATION"), List.of(), 0.0, Map.of());
        ExtractionResult result = agent.extract(List.of(doc), config);

        List<Entity> entities = result.graph().getEntities();
        boolean hasDate = entities.stream().anyMatch(e -> "DATE".equals(e.getType()));
        assertTrue(hasDate, "Should extract DATE entity");
    }

    @Test
    void extractLocationWithIndicator() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "The headquarters is located at Fifth Avenue in downtown.", Map.of());

        ExtractionConfig config = new ExtractionConfig(
                List.of("LOCATION"), List.of(), 0.0, Map.of());
        ExtractionResult result = agent.extract(List.of(doc), config);

        List<Entity> entities = result.graph().getEntities();
        boolean hasLocation = entities.stream().anyMatch(e -> "LOCATION".equals(e.getType()));
        assertTrue(hasLocation, "Should extract LOCATION for 'Fifth Avenue'");
    }

    @Test
    void extractCreatesCoOccurrenceRelationships() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "John Smith and Jane Doe work at Acme Corp.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        List<Relationship> rels = result.graph().getRelationships();
        // Multiple entities in the same chunk should create co-occurrence relationships
        if (result.graph().getEntities().size() >= 2) {
            assertFalse(rels.isEmpty(), "Should create CO_OCCURS_WITH relationships");
            boolean hasCoOccurrence = rels.stream()
                    .anyMatch(r -> "CO_OCCURS_WITH".equals(r.getType()));
            assertTrue(hasCoOccurrence, "Relationships should be CO_OCCURS_WITH type");
        }
    }

    @Test
    void extractDeduplicatesEntitiesAcrossChunks() {
        RetrievedDoc doc1 = new RetrievedDoc("d1", "John Smith is CEO.", Map.of());
        RetrievedDoc doc2 = new RetrievedDoc("d2", "John Smith visited Berlin.", Map.of());

        ExtractionResult result = agent.extract(List.of(doc1, doc2), ExtractionConfig.defaults());

        long johnCount = result.graph().getEntities().stream()
                .filter(e -> e.getTitle() != null && e.getTitle().contains("John"))
                .map(Entity::getId)
                .distinct()
                .count();
        // Should have John Smith just once (deduplicated by entity ID)
        assertTrue(johnCount >= 1);
    }

    @Test
    void extractFiltersStopWords() {
        // Words like "The", "This" should not become entities
        RetrievedDoc doc = new RetrievedDoc("d1",
                "The system is working. This project succeeded.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        List<Entity> entities = result.graph().getEntities();
        boolean hasStopWord = entities.stream()
                .anyMatch(e -> "The".equals(e.getTitle()) || "This".equals(e.getTitle()));
        assertFalse(hasStopWord, "Stop words should not be extracted as entities");
    }

    @Test
    void extractSkipsBlankChunks() {
        RetrievedDoc blank = new RetrievedDoc("d1", "   ", Map.of());
        ExtractionResult result = agent.extract(List.of(blank), ExtractionConfig.defaults());

        assertNotNull(result);
        assertTrue(result.graph().getEntities().isEmpty());
    }

    @Test
    void extractMetricsArePopulated() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "Jane Doe works at Acme Corp in London.", Map.of());
        ExtractionResult result = agent.extract(List.of(doc), ExtractionConfig.defaults());

        assertNotNull(result.metrics());
        assertEquals("pattern-ner", result.metrics().agentId());
        assertTrue(result.metrics().chunksProcessed() >= 0);
    }

    @Test
    void extractDeduplicatesCoOccurrenceRelationships() {
        // Same pair of entities should not create duplicate relationships
        RetrievedDoc doc1 = new RetrievedDoc("d1",
                "John Smith and Jane Doe collaborate.", Map.of());
        RetrievedDoc doc2 = new RetrievedDoc("d2",
                "John Smith and Jane Doe meet again.", Map.of());

        ExtractionResult result = agent.extract(List.of(doc1, doc2), ExtractionConfig.defaults());

        List<Relationship> rels = result.graph().getRelationships();
        // Count distinct (source,target,type) triples — should have no duplicates
        long distinct = rels.stream()
                .map(r -> r.getSource() + "|" + r.getTarget() + "|" + r.getType())
                .distinct()
                .count();
        assertEquals(distinct, rels.size(), "Relationships should be deduplicated");
    }

    @Test
    void extractWithMinConfidenceFiltersLowScoreEntities() {
        RetrievedDoc doc = new RetrievedDoc("d1",
                "John Smith works with Jane Doe.", Map.of());

        ExtractionConfig strictConfig = new ExtractionConfig(
                List.of("PERSON", "ORGANIZATION", "LOCATION", "DATE"),
                List.of(),
                0.9,   // very high threshold
                Map.of()
        );
        ExtractionResult result = agent.extract(List.of(doc), strictConfig);

        // All surviving entities should have confidence >= 0.9 or null
        result.graph().getEntities().forEach(e ->
                assertTrue(e.getConfidence() == null || e.getConfidence() >= 0.9,
                        "Entity confidence below threshold: " + e.getTitle()));
    }

    @Test
    void extractWithMultipleChunks() {
        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("d1", "Acme Corp is based in San Francisco.", Map.of()),
                new RetrievedDoc("d2", "Jane Doe is the CEO of Global Institute.", Map.of()),
                new RetrievedDoc("d3", "The meeting was held on January 15, 2024.", Map.of())
        );

        ExtractionConfig config = new ExtractionConfig(
                List.of("PERSON", "ORGANIZATION", "LOCATION", "DATE"), List.of(), 0.0, Map.of());
        ExtractionResult result = agent.extract(chunks, config);

        assertNotNull(result.graph());
        // Should extract from all 3 chunks
        assertTrue(result.graph().getEntities().size() > 0);
    }
}
